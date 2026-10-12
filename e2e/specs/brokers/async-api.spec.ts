/**
 * `async-api-trigger` over kafka and amqp, and `service-call`'s kafka/amqp protocol and the four
 * asynchronous `integrationOperationMethod` values (subscribe, publish, send, receive).
 *
 * Neither element carries ids a seeded chain can hold: `async-api-trigger` and `service-call` both
 * reference a service, a specification group, a specification and an operation, all of which exist
 * only once a specification is imported. So every case here builds its own service, imports an
 * AsyncAPI document into it, and deploys a chain in the worker folder — hand-rolled rather than
 * reusing `specs/runtime/service-call.ts`'s `serviceCallChain`, for a measured reason below.
 *
 * **`serviceCallChain` pre-creates and activates its own environment before the import, and that
 * breaks kafka and amqp.** Measured: every kafka/amqp `service-call` case built through it left its
 * chain at "no runtime state at all" past the 60 s deploy budget, serially and not only under
 * parallel workers, while the identical shape built by hand here deploys in seconds. The difference
 * is `EnvironmentBaseService.setDefaultProperties`, which stamps `KAFKA_ENVIRONMENT_PARAMETERS` or
 * `RABBIT_ENVIRONMENT_PARAMETERS` (`EnvironmentDefaultParameters.java`) onto an environment only when
 * `environment.getSystem().getProtocol()` is already known — and a service's protocol is set from
 * the import's own `protocol` query param (`SpecificationGroupService.java`), which has not run yet
 * when `serviceCallChain` creates its environment first. The environment `reconcileEnvironments`
 * then finds during the import keeps a non-blank, already-present address untouched for both
 * `INTERNAL` and `EXTERNAL` systems, so the pre-created environment's `properties` stay unset
 * forever and `OperationElementPropertiesBuilder.buildProtocolSpecificProperties` has nothing to
 * read at deploy time. The fix here is the same one `async-api-trigger` already needs: build the
 * system `EXTERNAL` with **no** environment of its own, and let the import create and activate one,
 * at a point where the system's protocol is already set. `serviceCallChain` is untouched, since http
 * and graphql resolve their address through `EndpointHelperSource.integrationAddress`, which needs
 * no environment properties at all.
 *
 * **AsyncAPI 2.6 gives subscribe/publish; AsyncAPI 3.0 gives send/receive.** AsyncAPI 3.0 replaced
 * the `publish`/`subscribe` channel keywords with a `send`/`receive` action
 * (`AsyncApiV3Normalizer.java`), so the three v2 fixtures here (`async-api-kafka.yaml`,
 * `async-api-amqp.yaml`, `service-call-async-kafka.yaml`) are what the four `integrationOperationMethod`
 * values need for their subscribe/publish half, and `service-call-async-amqp.yaml` is a v3 document
 * for the send/receive half.
 *
 * **The four documents live in `fixtures/specifications/`, not `fixtures/brokers/`.** Measured: the
 * brokers seed's `corpusFixtureNames`/`assembleCorpus` walk every top-level entry of
 * `fixtures/brokers/` — directory or `.yaml` file alike — as a **chain** fixture
 * (`chainFixtureNames` in `fixtures/templating.ts`), so an AsyncAPI document dropped there breaks the
 * seed with "no $schema, so the document kind is unknown" before this spec ever runs.
 * `fixtures/specifications/` is the tracked, seed-untouched home for exactly this class of document
 * — "the API documents a spec imports as a specification… not chains, and the seed never touches
 * them" — so the four AsyncAPI documents live there, read with `readSpecificationFixture` the way
 * every OpenAPI and GraphQL fixture is, plus this spec's own `{{RUN}}` substitution for the channel
 * names that become Kafka topics and AMQP exchanges.
 *
 * **For an AMQP specification, the operation's `name` is not what the document declares.**
 * `AsyncapiSpecificationParser.separate` overrides `operationId` to the **channel name** for the
 * `amqp` protocol only (`AMQP_BINDING_CLASS.equals(protocol)`), so an operation is found here by its
 * `path` (the channel/address) or its `method`, never by the `operationId` a fixture document writes
 * for an amqp channel.
 *
 * **The environment merge, not a template edit, is where `queues` comes from.** Neither
 * `KAFKA_ENVIRONMENT_PARAMETERS` nor `RABBIT_ENVIRONMENT_PARAMETERS`
 * (`EnvironmentDefaultParameters.java`) carries a `queues` key, and `getQueueName` in
 * `OperationElementPropertiesBuilder.java` reads the element's own
 * `integrationOperationAsyncProperties.queues` before falling back to the environment, so the
 * `async-api-trigger` amqp case sets it directly on the element rather than patching the environment
 * the import created.
 *
 * **Kafka's predeploy topic check runs for these elements too.** `KafkaTopicAndConnectionCheckAction`
 * is `applicableTo` every `isKafkaAsyncElement`, which includes `ASYNCAPI_TRIGGER` and `SERVICE_CALL`
 * (`ChainElementType.java`) — `OperationElementPropertiesBuilder` populates a literal `topics`
 * property from `integrationOperationPath` for exactly this check to read. So every kafka case here
 * creates its topic before deploying, the same way `support/brokers.ts` does for `kafka-trigger-2`.
 * AMQP's `AmpqConnectionCheckAction` treats `service-call` as a producer
 * (`AMQP_PRODUCER_ELEMENTS`) and checks only that the exchange exists; `async-api-trigger` is not a
 * producer there, so its case declares the queue the same way `rabbitmq-trigger-2` needs one.
 */
import amqp, { type Channel, type ChannelModel } from "amqplib";
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, waitForDeployed, waitForRoutes, type SeedChain } from "../../support/corpus.js";
import { createStepsChain, type ChainStep } from "../../support/deployable.js";
import { readSpecificationFixture, substitute } from "../../fixtures/templating.js";
import {
  BROKERS_LOGGING,
  RABBITMQ_HOST_URL,
  ensureKafkaTopics,
  deleteKafkaTopics,
  waitForKafkaConsumers,
  openChannel,
} from "../../support/brokers.js";
import {
  bindConsumerQueue,
  consumeOneKafka,
  declareFanout,
  publishAmqp,
  publishKafka,
  waitForAmqpMessage,
} from "../../support/broker-clients.js";
import { tokenized, callToken } from "../../support/run.js";
import { callChain, elementNames, failedElements, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { withBuilt, type Built } from "../../support/cleanup.js";
import type { Catalog } from "../../support/catalog.js";
import type { Env } from "../../env/index.js";

/** `e2e/fixtures/specifications/<file>`, with `{{RUN}}` substituted — see the header for why they live there. */
function asyncApiFixture(file: string, run: string): { name: string; mimeType: string; buffer: Buffer } {
  const fixture = readSpecificationFixture(file);
  return { ...fixture, buffer: Buffer.from(substitute(fixture.buffer.toString("utf-8"), run)) };
}

export interface ServiceCallAsyncChain {
  chain: SeedChain;
  callerName: string;
}

/**
 * An HTTP-triggered `service-call` into one operation of a fresh, `EXTERNAL` service, built and
 * deployed by hand — see the header for why this does not go through `serviceCallChain`. The system
 * carries no environment of its own; the import creates and activates one once the system's protocol
 * is set, which is what `serviceCallChain`'s pre-created environment misses for kafka and amqp.
 */
async function serviceCallAsyncChain(
  catalog: Catalog,
  env: Env,
  run: string,
  folderId: string,
  built: Built,
  options: { what: string; protocol: "kafka" | "amqp"; file: string; method: string },
): Promise<ServiceCallAsyncChain> {
  const name = tokenized(run, options.what);
  const system = await catalog.createSystem(name, "EXTERNAL");
  built.services.push({ name: `the service ${name} (${system.id})`, remove: () => catalog.deleteSystem(system.id) });

  const file = asyncApiFixture(options.file, run);
  const imported = await catalog.awaitSpecificationImport(
    await catalog.importSpecificationGroup(system.id, name, file, options.protocol),
  );
  const operation = imported.operations.find((each) => each.method === options.method);
  if (!operation) {
    throw new Error(
      `the ${options.protocol} AsyncAPI import produced no ${options.method} operation, only ` +
        JSON.stringify(imported.operations.map((each) => each.method)),
    );
  }

  const callerName = "Service Call";
  const caller: ChainStep = {
    name: callerName,
    type: "service-call",
    properties: {
      systemType: "EXTERNAL",
      integrationSystemId: system.id,
      integrationSpecificationGroupId: `${system.id}-${name}`,
      integrationSpecificationId: imported.specifications[0].id,
      integrationOperationId: operation.id,
      integrationOperationPath: operation.path,
      integrationOperationMethod: operation.method,
      integrationOperationProtocolType: options.protocol,
    },
  };
  const chain = await createStepsChain(
    catalog,
    run,
    { what: options.what, parentId: folderId, steps: [caller], logging: BROKERS_LOGGING },
    built.chains,
  );

  const snapshot = await catalog.createSnapshot(chain.id);
  await catalog.deploy(chain.id, snapshot.id);
  await waitForDeployed(catalog, [chain]);
  await waitForRoutes(env, [chain]);

  return { chain, callerName };
}

// ---------------------------------------------------------------------------
// async-api-trigger
// ---------------------------------------------------------------------------

test(
  "a Kafka message on the operation's channel finds the session an async-api-trigger recorded",
  { tag: ["@engine", "@catalog", "@sessions", "@tier1"] },
  async ({ catalog, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    covers("async-api-trigger");
    covers("async-api-trigger", "integrationOperationProtocolType", "kafka");
    covers("async-api-trigger", "systemType", "EXTERNAL");

    const name = tokenized(run, "async-api-trigger-kafka");
    const topic = tokenized(run, "async-api-kafka");
    await ensureKafkaTopics([topic]);
    try {
      await withBuilt(catalog, async (built) => {
        const system = await catalog.createSystem(name, "EXTERNAL");
        built.services.push({ name: `the service ${name} (${system.id})`, remove: () => catalog.deleteSystem(system.id) });
        const file = asyncApiFixture("async-api-kafka.yaml", run);
        const imported = await catalog.awaitSpecificationImport(
          await catalog.importSpecificationGroup(system.id, name, file, "kafka"),
        );
        const operation = imported.operations[0];
        if (!operation) throw new Error("the kafka AsyncAPI import produced no operation");
        // No covers() for integrationOperationMethod: template.hbs never reads it, so this case
        // proves nothing about the value — see R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE.

        const trigger: ChainStep = {
          name: "AsyncAPI Trigger",
          type: "async-api-trigger",
          properties: {
            systemType: "EXTERNAL",
            integrationSystemId: system.id,
            integrationSpecificationGroupId: `${system.id}-${name}`,
            integrationSpecificationId: imported.specifications[0].id,
            integrationOperationId: operation.id,
            integrationOperationPath: operation.path,
            integrationOperationMethod: operation.method,
            integrationOperationProtocolType: "kafka",
            // The schema's kafka-only default (async-api-trigger.schema.yaml); template.hbs reads it directly, with no engine-side fallback.
            reconnectDelay: 30000,
            integrationOperationAsyncProperties: { groupId: topic },
            idempotency: { enabled: false },
          },
        };
        const chain = await createStepsChain(
          catalog,
          run,
          {
            what: "async-api-trigger-kafka",
            parentId: folder.id,
            trigger,
            steps: [
              {
                name: "Header Modification",
                type: "header-modification",
                properties: { headerModificationToAdd: { "e2e-fixture": run }, headerModificationToRemove: [] },
              },
            ],
            logging: BROKERS_LOGGING,
          },
          built.chains,
        );

        const snapshot = await catalog.createSnapshot(chain.id);
        await catalog.deploy(chain.id, snapshot.id);
        await waitForDeployed(catalog, [chain]);
        await waitForKafkaConsumers([topic]);

        const token = callToken("async-api-kafka");
        await publishKafka(topic, token, JSON.stringify({ ping: "async-api-kafka" }));

        const session = await sessions.byExternalId(token, { elements: 2 });
        expect(session.chainId).toBe(chain.id);
        expect(elementNames(session)).toEqual(["AsyncAPI Trigger", "Header Modification"]);
        expect(failedElements(session)).toEqual([]);
      });
    } finally {
      // Logged rather than thrown: a delete failure here would otherwise replace whatever assertion
      // error the `try` block above raised, hiding the reason the case actually failed.
      await deleteKafkaTopics([topic]).catch((cause: unknown) => {
        console.error(`[teardown] kafka topic ${topic} was not deleted: ${String(cause)}`);
      });
    }
  },
);

test(
  "an AMQP message on the operation's channel finds the session an async-api-trigger recorded",
  { tag: ["@engine", "@catalog", "@sessions", "@tier1"] },
  async ({ catalog, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    covers("async-api-trigger", "integrationOperationProtocolType", "amqp");

    const name = tokenized(run, "async-api-trigger-amqp");
    const exchange = tokenized(run, "async-api-amqp");
    const queue = tokenized(run, "async-api-amqp-queue");
    const { connection, channel } = await declareFanout(exchange, queue);
    try {
      await withBuilt(catalog, async (built) => {
        const system = await catalog.createSystem(name, "EXTERNAL");
        built.services.push({ name: `the service ${name} (${system.id})`, remove: () => catalog.deleteSystem(system.id) });
        const file = asyncApiFixture("async-api-amqp.yaml", run);
        const imported = await catalog.awaitSpecificationImport(
          await catalog.importSpecificationGroup(system.id, name, file, "amqp"),
        );
        const operation = imported.operations[0];
        if (!operation) throw new Error("the amqp AsyncAPI import produced no operation");
        // No covers() for integrationOperationMethod: template.hbs never reads it, so this case
        // proves nothing about the value — see R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE.

        const trigger: ChainStep = {
          name: "AsyncAPI Trigger",
          type: "async-api-trigger",
          properties: {
            systemType: "EXTERNAL",
            integrationSystemId: system.id,
            integrationSpecificationGroupId: `${system.id}-${name}`,
            integrationSpecificationId: imported.specifications[0].id,
            integrationOperationId: operation.id,
            integrationOperationPath: operation.path,
            integrationOperationMethod: operation.method,
            integrationOperationProtocolType: "amqp",
            integrationOperationAsyncProperties: { acknowledgeMode: "AUTO", queues: queue },
            idempotency: { enabled: false },
          },
        };
        const chain = await createStepsChain(
          catalog,
          run,
          {
            what: "async-api-trigger-amqp",
            parentId: folder.id,
            trigger,
            steps: [
              {
                name: "Header Modification",
                type: "header-modification",
                properties: { headerModificationToAdd: { "e2e-fixture": run }, headerModificationToRemove: [] },
              },
            ],
            logging: BROKERS_LOGGING,
          },
          built.chains,
        );

        const snapshot = await catalog.createSnapshot(chain.id);
        await catalog.deploy(chain.id, snapshot.id);
        await waitForDeployed(catalog, [chain]);

        const token = callToken("async-api-amqp");
        await publishAmqp(exchange, "", token, { ping: "async-api-amqp" });

        const session = await sessions.byExternalId(token, { elements: 2 });
        expect(session.chainId).toBe(chain.id);
        expect(elementNames(session)).toEqual(["AsyncAPI Trigger", "Header Modification"]);
        expect(failedElements(session)).toEqual([]);
      });
    } finally {
      // `declareFanout` declares both durable, and `docker-compose.rabbitmq.yml` sets no queue-expiry
      // policy, so a run that never deletes these leaves them for every later run to list. Deleted on
      // the same channel before it closes.
      await channel.deleteQueue(queue).catch(() => {});
      await channel.deleteExchange(exchange).catch(() => {});
      await channel.close();
      await connection.close();
    }
  },
);

// ---------------------------------------------------------------------------
// service-call: kafka and amqp, and the four asynchronous integrationOperationMethod values
// ---------------------------------------------------------------------------

// subscribe and publish differ only in the topic-name suffix, `what`, `method` and the covers() call.
const KAFKA_METHOD_CASES = [
  { method: "subscribe", covers: () => covers("service-call", "integrationOperationProtocolType", "kafka") },
  { method: "publish", covers: () => covers("service-call") },
] as const;

for (const { method, covers: declareCoverage } of KAFKA_METHOD_CASES) {
  test(
    `service-call over kafka with integrationOperationMethod ${method} delivers to the topic`,
    { tag: ["@engine", "@catalog", "@sessions", "@tier2"] },
    async ({ request, env, catalog, sessions, folder, run }) => {
      test.setTimeout(ENGINE_CASE_TIMEOUT);
      declareCoverage();
      // No covers() for integrationOperationMethod: see R_SERVICE_CALL_METHOD_INDISTINGUISHABLE.

      const topic = tokenized(run, `service-call-kafka-${method}`);
      await ensureKafkaTopics([topic]);
      try {
        await withBuilt(catalog, async (built) => {
          const { chain, callerName } = await serviceCallAsyncChain(catalog, env, run, folder.id, built, {
            what: `async-kafka-${method}`,
            protocol: "kafka",
            file: "service-call-async-kafka.yaml",
            method,
          });

          const body = { ping: `service-call-kafka-${method}`, nonce: callToken("nonce") };
          const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
          expect(response.status()).toBe(200);

          const session = await sessions.byExternalId(token, { elements: 4 });
          expect(session.chainId).toBe(chain.id);
          expect(failedElements(session)).toEqual([]);
          expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, callerName, "Request attempt"]);

          // Started only now, after the session lookup above already spent part of its own budget: the
          // topic retains what it holds and `consumeOneKafka` subscribes `fromBeginning`, so a
          // consumer that starts this late still reads the message.
          const message = await consumeOneKafka(topic, tokenized(run, `service-call-kafka-${method}-consumer`));
          expect(message, `no message arrived on ${topic} within the wait`).not.toBeNull();
          expect(JSON.parse(message!.value ?? "null")).toEqual(body);
        });
      } finally {
        // Logged rather than thrown: a delete failure here would otherwise replace whatever assertion
        // error the `try` block above raised, hiding the reason the case actually failed.
        await deleteKafkaTopics([topic]).catch((cause: unknown) => {
          console.error(`[teardown] kafka topic ${topic} was not deleted: ${String(cause)}`);
        });
      }
    },
  );
}

// send and receive differ only in the exchange-name suffix, `what`, `method` and the covers() call —
// receive declares no coverage row for the protocol axis, since the kafka case above already does.
const AMQP_METHOD_CASES = [
  { method: "send", covers: () => covers("service-call", "integrationOperationProtocolType", "amqp") },
  { method: "receive", covers: undefined },
] as const;

for (const { method, covers: declareCoverage } of AMQP_METHOD_CASES) {
  test(
    `service-call over amqp with integrationOperationMethod ${method} delivers to the exchange`,
    { tag: ["@engine", "@catalog", "@sessions", "@tier2"] },
    async ({ request, env, catalog, sessions, folder, run }) => {
      test.setTimeout(ENGINE_CASE_TIMEOUT);
      declareCoverage?.();
      // No covers() for integrationOperationMethod: see R_SERVICE_CALL_METHOD_INDISTINGUISHABLE.

      const exchange = tokenized(run, `service-call-amqp-${method}`);
      const declareConnection: ChannelModel = await amqp.connect(RABBITMQ_HOST_URL);
      const declareChannel: Channel = await openChannel(declareConnection);

      try {
        await declareChannel.assertExchange(exchange, "fanout", { durable: true });
        await withBuilt(catalog, async (built) => {
          const { chain, callerName } = await serviceCallAsyncChain(catalog, env, run, folder.id, built, {
            what: `async-amqp-${method}`,
            protocol: "amqp",
            file: "service-call-async-amqp.yaml",
            method,
          });

          const body = { ping: `service-call-amqp-${method}`, nonce: callToken("nonce") };
          const { connection, channel, queue } = await bindConsumerQueue(exchange);
          try {
            const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
            expect(response.status()).toBe(200);

            const session = await sessions.byExternalId(token, { elements: 4 });
            expect(session.chainId).toBe(chain.id);
            expect(failedElements(session)).toEqual([]);
            expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, callerName, "Request attempt"]);

            // Started only now, after the session lookup above already spent part of its own budget:
            // the queue is already bound and retains what it holds, so the wait gets the full window.
            const message = await waitForAmqpMessage(channel, queue);
            expect(message, `no message arrived on the exchange ${exchange} within the wait`).not.toBeNull();
            expect(message!.body).toEqual(body);
          } finally {
            await channel.close();
            await connection.close();
          }
        });
      } finally {
        // `assertExchange` above is durable, so it outlives the run unless this deletes it — the
        // exchange `bindConsumerQueue`'s own queue is exclusive/autoDelete and needs no cleanup here.
        await declareChannel.deleteExchange(exchange).catch(() => {});
        await declareChannel.close();
        await declareConnection.close();
      }
    },
  );
}
