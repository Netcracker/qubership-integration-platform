/**
 * `async-api-trigger` over kafka and amqp, and `service-call`'s kafka/amqp protocol and the four
 * asynchronous `integrationOperationMethod` values (subscribe, publish, send, receive), against the
 * seeded `fixtures/brokers/async-api-trigger-*` and `service-call-{kafka,amqp}-*` chains.
 *
 * Each chain references an operation of an AsyncAPI service under `fixtures/broker-services/`,
 * which the broker seeds import before the chains. The service documents are the catalog's own
 * export of an AsyncAPI import, so each environment carries the Kafka or RabbitMQ properties
 * `OperationElementPropertiesBuilder.buildProtocolSpecificProperties` reads at deploy time. An
 * environment created through the API before the import would leave those properties unset, because
 * `EnvironmentBaseService.setDefaultProperties` fills them only once the service's protocol is known.
 *
 * **AsyncAPI 2.6 gives subscribe/publish; AsyncAPI 3.0 gives send/receive.** AsyncAPI 3.0 replaced
 * the `publish`/`subscribe` channel keywords with a `send`/`receive` action
 * (`AsyncApiV3Normalizer.java`), so `service-call-async-amqp` and `async-api-v3-amqp` come from v3
 * documents and the other three services from v2 documents. Each document sits under its service's
 * `resources/`; `specs/api/specifications.spec.ts` imports the `async-api-v3-amqp` one and checks
 * that the import gives the operations the fixture holds.
 *
 * **For an AMQP specification, the operation's `name` is the channel name.**
 * `AsyncapiSpecificationParser.separate` overrides `operationId` with the channel name for the `amqp`
 * protocol only, so the AMQP operation ids carry the run token.
 *
 * The inline-payload case imports its own document from `fixtures/specifications/` into a service it
 * builds, deploys on the classic engine's domain, and removes, so the micro copy leaves it out.
 *
 * **The element, not the environment, names the `async-api-trigger` amqp queue.** `getQueueName` in
 * `OperationElementPropertiesBuilder.java` reads `integrationOperationAsyncProperties.queues` before
 * the environment, which carries no `queues` key.
 *
 * **The topic or exchange is the element's `integrationOperationPath`.** The Kafka pre-deploy check
 * reads it as the topic, and the AMQP one checks that a `service-call`'s exchange exists, so the
 * brokers seed creates both, and the `async-api-trigger` queue, from the chain fixtures
 * (`support/brokers.ts`), and the micro copy renames them.
 *
 * The Kafka `async-api-trigger` reads its topic from `earliest`: the consumer group turns Stable
 * before the consumer looks up its offset, so `latest` could skip the message this case publishes
 * right after the seed's consumer wait, and only this case publishes to the topic.
 *
 * **The amqp `acknowledgeMode` reaches the consumer URI through the async properties.**
 * `EnvironmentPropertiesHelper.asyncProperties` merges the element's
 * `integrationOperationAsyncProperties` over the environment's, so the element's mode wins over the
 * service's `AUTO`. Each acknowledgement fixture fails in an http-sender, and its async properties
 * name the dead-letter exchange, queue and routing key the brokers seed declares the trigger's queue
 * with, as `rabbitmq-trigger-2`'s own properties do. Under `AUTO` the container rejects the failed
 * delivery after its one attempt (`maximumRetryAttempts=1`), so it lands on the dead-letter queue.
 * `RabbitMqTriggerProcessor` acks a `MANUAL` delivery before the chain runs, and the broker counts a
 * `NONE` delivery as acknowledged when it sends it, so neither is dead-lettered nor delivered again.
 */
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, waitForDeployed } from "../../support/corpus.js";
import { createStepsChain, type ChainStep } from "../../support/deployable.js";
import { readSpecificationFixture, substitute } from "../../fixtures/templating.js";
import {
  BROKERS_LOGGING,
  brokerChain,
  deleteKafkaTopics,
  ensureKafkaTopics,
  readBrokersCorpusState,
  waitForKafkaConsumers,
} from "../../support/brokers.js";
import {
  bindConsumerQueue,
  consumeOneKafka,
  expectAckOutcome,
  publishAmqp,
  publishKafka,
  waitForAmqpMessage,
} from "../../support/broker-clients.js";
import { callToken, tokenized } from "../../support/run.js";
import {
  callChain,
  elementNames,
  failedElements,
  HTTP_TRIGGER_STEPS,
} from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { withBuilt } from "../../support/cleanup.js";

// ---------------------------------------------------------------------------
// async-api-trigger
// ---------------------------------------------------------------------------

test(
  "a Kafka message on the operation's channel finds the session an async-api-trigger recorded",
  { tag: ["@engine", "@catalog", "@sessions", "@tier1"] },
  async ({ sessions }) => {
    covers("async-api-trigger");
    covers("async-api-trigger", "integrationOperationProtocolType", "kafka");
    covers("async-api-trigger", "systemType", "EXTERNAL");
    // No covers() for integrationOperationMethod: template.hbs never reads it, so this case
    // proves nothing about the value; see R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE.

    const chain = brokerChain(readBrokersCorpusState(), "async-api-trigger-kafka");
    const token = callToken("async-api-kafka");
    await publishKafka(chain.broker.integrationOperationPath as string, token, JSON.stringify({ ping: "async-api-kafka" }));

    const session = await sessions.byExternalId(token, { elements: 2 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["AsyncAPI Trigger", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);

// Before #1082 the payload of an inline message was stored with its `$ref` into `components`, which
// the operation does not carry, so the engine rejected every message, valid or not, with "Reference
// /components/schemas/OrderShipped cannot be resolved".
test(
  "an async-api-trigger validates a Kafka message against an inline payload that references a component",
  { tag: ["@engine", "@catalog", "@sessions", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "the case builds and deploys its own chain on the classic engine's domain" } },
  async ({ catalog, sessions, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);

    const name = tokenized(run, "async-api-trigger-kafka-inline-ref");
    const topic = tokenized(run, "async-api-kafka-inline-ref");
    await ensureKafkaTopics([topic]);
    try {
      await withBuilt(catalog, async (built) => {
        const system = await catalog.createSystem(name, "EXTERNAL");
        built.services.push({ name: `the service ${name} (${system.id})`, remove: () => catalog.deleteSystem(system.id) });
        const fixture = readSpecificationFixture("async-api-kafka-inline-ref.yaml");
        const file = { ...fixture, buffer: Buffer.from(substitute(fixture.buffer.toString("utf-8"), run)) };
        const imported = await catalog.awaitSpecificationImport(
          await catalog.importSpecificationGroup(system.id, name, file, "kafka"),
        );
        const operation = imported.operations[0];
        if (!operation) throw new Error("the kafka AsyncAPI import produced no operation");

        // The validation the element form adds from the operation's schema, one item per message
        // (`CustomArrayField`, `elementType === "async-api-trigger"`).
        const { responseSchemas } = await catalog.operationInfo(operation.id);
        const after = Object.entries(responseSchemas).map(([code, schema]) => ({
          code,
          label: code,
          schema: JSON.stringify(schema, null, 2),
        }));

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
            reconnectDelay: 30000,
            integrationOperationAsyncProperties: { groupId: topic },
            idempotency: { enabled: false },
            after,
          },
        };
        const chain = await createStepsChain(
          catalog,
          run,
          {
            what: "async-api-trigger-kafka-inline-ref",
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

        const valid = callToken("async-api-kafka-inline-ref-valid");
        await publishKafka(topic, valid, JSON.stringify({ orderId: "1", address: { city: "Rome" } }));
        const accepted = await sessions.byExternalId(valid, { elements: 2 });
        expect(accepted.chainId).toBe(chain.id);
        expect(elementNames(accepted)).toEqual(["AsyncAPI Trigger", "Header Modification"]);
        expect(failedElements(accepted)).toEqual([]);

        // The other half: the schema the trigger validates against still rejects a wrong type.
        const invalid = callToken("async-api-kafka-inline-ref-invalid");
        await publishKafka(topic, invalid, JSON.stringify({ orderId: "1", address: { city: 2 } }));
        const rejected = await sessions.byExternalId(invalid);
        const failures = failedElements(rejected).map((each) => [each.elementName, each.exceptionInfo?.message]);
        expect(failures).toEqual([["AsyncAPI Trigger", expect.stringContaining("Errors during message validation")]]);
      });
    } finally {
      await deleteKafkaTopics([topic]).catch((cause: unknown) => {
        console.error(`[teardown] kafka topic ${topic} was not deleted: ${String(cause)}`);
      });
    }
  },
);

test(
  "an AMQP message on the operation's channel finds the session an async-api-trigger recorded",
  { tag: ["@engine", "@catalog", "@sessions", "@tier1"] },
  async ({ sessions }) => {
    covers("async-api-trigger", "integrationOperationProtocolType", "amqp");

    const chain = brokerChain(readBrokersCorpusState(), "async-api-trigger-amqp");
    const token = callToken("async-api-amqp");
    await publishAmqp(chain.broker.integrationOperationPath as string, "", token, { ping: "async-api-amqp" });

    const session = await sessions.byExternalId(token, { elements: 2 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["AsyncAPI Trigger", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "an AMQP message on an AsyncAPI 3.0 receive operation finds the session an async-api-trigger recorded",
  { tag: ["@engine", "@catalog", "@sessions", "@tier2"] },
  async ({ sessions }) => {
    // No covers() for integrationOperationMethod: see R_ASYNC_API_TRIGGER_METHOD_INDISTINGUISHABLE.
    const chain = brokerChain(readBrokersCorpusState(), "async-api-trigger-receive");
    const token = callToken("async-api-receive");
    await publishAmqp(chain.broker.integrationOperationPath as string, "", token, { ping: "async-api-receive" });

    const session = await sessions.settled(await sessions.byExternalId(token, { elements: 2 }));
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["AsyncAPI Trigger", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);

const ACKNOWLEDGE_MODES = [
  { mode: "AUTO", deadLettered: true },
  { mode: "MANUAL", deadLettered: false },
  { mode: "NONE", deadLettered: false },
] as const;

for (const { mode, deadLettered } of ACKNOWLEDGE_MODES) {
  test(
    `an amqp async-api-trigger with acknowledgeMode ${mode} ${deadLettered ? "dead-letters" : "drops"} a message the chain failed`,
    { tag: ["@engine", "@sessions", "@tier2"] },
    async ({ sessions }) => {
      covers("async-api-trigger", "integrationOperationAsyncProperties/acknowledgeMode", mode);

      const chain = brokerChain(readBrokersCorpusState(), `async-api-trigger-ack-${mode.toLowerCase()}`);
      const async = chain.broker.integrationOperationAsyncProperties as Record<string, string>;
      const target = {
        exchange: chain.broker.integrationOperationPath as string,
        routingKey: "",
        queue: async.queues,
        deadLetterExchange: async.deadLetterExchange,
        deadLetterRoutingKey: async.deadLetterRoutingKey,
      };
      const token = callToken(`async-api-ack-${mode.toLowerCase()}`);
      await expectAckOutcome(sessions, chain.id, target, token, { ping: `async-api-ack-${mode.toLowerCase()}` }, deadLettered);
    },
  );
}

// ---------------------------------------------------------------------------
// service-call: kafka and amqp, and the four asynchronous integrationOperationMethod values
// ---------------------------------------------------------------------------

// subscribe and publish differ only in the fixture and the covers() call.
const KAFKA_METHOD_CASES = [
  { method: "subscribe", covers: () => covers("service-call", "integrationOperationProtocolType", "kafka") },
  { method: "publish", covers: () => covers("service-call") },
] as const;

for (const { method, covers: declareCoverage } of KAFKA_METHOD_CASES) {
  test(
    `service-call over kafka with integrationOperationMethod ${method} delivers to the topic`,
    { tag: ["@engine", "@catalog", "@sessions", "@tier2"] },
    async ({ request, env, sessions }) => {
      declareCoverage();
      // No covers() for integrationOperationMethod: see R_SERVICE_CALL_METHOD_INDISTINGUISHABLE.

      const chain = brokerChain(readBrokersCorpusState(), `service-call-kafka-${method}`);
      const topic = chain.broker.integrationOperationPath as string;
      const body = { ping: `service-call-kafka-${method}`, nonce: callToken("nonce") };
      const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
      expect(response.status()).toBe(200);

      const session = await sessions.byExternalId(token, { elements: 4 });
      expect(session.chainId).toBe(chain.id);
      expect(failedElements(await sessions.settled(session))).toEqual([]);
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Service Call", "Request attempt"]);

      // Started only now, after the session lookup above already spent part of its own budget: the
      // topic retains what it holds and `consumeOneKafka` subscribes `fromBeginning`, so a
      // consumer that starts this late still reads the message. The nonce skips one an earlier run left.
      const message = await consumeOneKafka(topic, `${topic}-consumer`, (value) => value?.includes(body.nonce) ?? false);
      expect(message, `no message arrived on ${topic} within the wait`).not.toBeNull();
      expect(JSON.parse(message!.value ?? "null")).toEqual(body);
    },
  );
}

// send and receive differ only in the fixture and the covers() call; receive declares no row for
// the protocol axis, since send already does.
const AMQP_METHOD_CASES = [
  { method: "send", covers: () => covers("service-call", "integrationOperationProtocolType", "amqp") },
  { method: "receive", covers: undefined },
] as const;

for (const { method, covers: declareCoverage } of AMQP_METHOD_CASES) {
  test(
    `service-call over amqp with integrationOperationMethod ${method} delivers to the exchange`,
    { tag: ["@engine", "@catalog", "@sessions", "@tier2"] },
    async ({ request, env, sessions }) => {
      declareCoverage?.();
      // No covers() for integrationOperationMethod: see R_SERVICE_CALL_METHOD_INDISTINGUISHABLE.

      const chain = brokerChain(readBrokersCorpusState(), `service-call-amqp-${method}`);
      const exchange = chain.broker.integrationOperationPath as string;
      const body = { ping: `service-call-amqp-${method}`, nonce: callToken("nonce") };
      // Bound before the call: the queue is exclusive to this case and holds only what the call sends.
      const { connection, channel, queue } = await bindConsumerQueue(exchange);
      try {
        const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
        expect(response.status()).toBe(200);

        const session = await sessions.byExternalId(token, { elements: 4 });
        expect(session.chainId).toBe(chain.id);
        expect(failedElements(await sessions.settled(session))).toEqual([]);
        expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Service Call", "Request attempt"]);

        const message = await waitForAmqpMessage(channel, queue);
        expect(message, `no message arrived on the exchange ${exchange} within the wait`).not.toBeNull();
        expect(message!.body).toEqual(body);
      } finally {
        await channel.close();
        await connection.close();
      }
    },
  );
}
