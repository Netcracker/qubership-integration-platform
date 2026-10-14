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
 * (`AsyncApiV3Normalizer.java`), so `service-call-async-amqp` comes from a v3 document and the other
 * three services from v2 documents. Each document sits under its service's `resources/`.
 *
 * **For an AMQP specification, the operation's `name` is the channel name.**
 * `AsyncapiSpecificationParser.separate` overrides `operationId` with the channel name for the `amqp`
 * protocol only, so the AMQP operation ids carry the run token.
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
 */
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState } from "../../support/brokers.js";
import {
  bindConsumerQueue,
  consumeOneKafka,
  publishAmqp,
  publishKafka,
  waitForAmqpMessage,
} from "../../support/broker-clients.js";
import { callToken } from "../../support/run.js";
import {
  callChain,
  elementNames,
  failedElements,
  HTTP_TRIGGER_STEPS,
  SESSION_TIMEOUT,
  trace,
  type RecordedSession,
  type Sessions,
} from "../../support/sessions.js";
import { readUntil } from "../../support/poll.js";
import { covers } from "../../registry/covers.js";

/**
 * The session read again until no step reads `IN_PROGRESS`. The Service Call step settles after its
 * session and its child: CI run 38538504420 read it `IN_PROGRESS` beside a finished Request attempt.
 */
function settled(sessions: Sessions, session: RecordedSession): Promise<RecordedSession> {
  return readUntil(
    () => sessions.session(session.id),
    (read) => trace(read).every((each) => each.executionStatus !== "IN_PROGRESS"),
    SESSION_TIMEOUT,
  );
}

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
      expect(failedElements(await settled(sessions, session))).toEqual([]);
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Service Call", "Request attempt"]);

      // Started only now, after the session lookup above already spent part of its own budget: the
      // topic retains what it holds and `consumeOneKafka` subscribes `fromBeginning`, so a
      // consumer that starts this late still reads the message.
      const message = await consumeOneKafka(topic, `${topic}-consumer`);
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
        expect(failedElements(await settled(sessions, session))).toEqual([]);
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
