/**
 * `kafka-trigger-2` and `kafka-sender-2`, against `fixtures/brokers/kafka-*`.
 *
 * Correlation is per call: the producing
 * side sets a per-call `external-session-cip-id` **Kafka header**, and `SessionsService.java:91`
 * reads it off `exchange.getMessage().getHeader(...)` the same way it reads an HTTP one —
 * `KafkaHeaderStringDeserializer` is what makes a Kafka record header arrive as a plain string Camel
 * header. So a trigger case never calls the chain itself; it publishes with `kafkajs` and finds the
 * session the same way an HTTP case would.
 *
 * A sender case is the mirror image: an ordinary HTTP call drives the chain (correlated the usual
 * way, through `sessions.byExternalId`), and a `kafkajs` consumer reads the topic to prove a real
 * message landed with the payload the call carried — the session trace alone only proves the
 * `kafka-sender-2` step ran, not that anything reached the broker.
 */
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState } from "../../support/brokers.js";
import { publishKafka as publish, consumeOneKafka as consumeOne } from "../../support/broker-clients.js";
import { tokenized, callToken } from "../../support/run.js";
import { callChain, elementNames, failedElements } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { readUntil } from "../../support/poll.js";

/** What `readUntil` gets for the offset-moved case's settle window, instead of a bare literal. */
const OFFSET_SETTLE_TIMEOUT = 10_000;

// ---------------------------------------------------------------------------
// kafka-trigger-2
// ---------------------------------------------------------------------------

test(
  "a Kafka message carrying the correlation header finds the session the trigger recorded",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ sessions }) => {
    covers("kafka-trigger-2");
    covers("kafka-trigger-2", "connectionSourceType", "manual");
    covers("kafka-trigger-2", "securityProtocol", "PLAINTEXT");
    covers("kafka-trigger-2", "autoOffsetReset", "latest");
    covers("kafka-trigger-2", "keyDeserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    covers("kafka-trigger-2", "valueDeserializer", "org.apache.kafka.common.serialization.StringDeserializer");

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-trigger-basic");
    const topic = chain.broker.topics as string;
    const token = callToken("kafka-consume");

    await publish(topic, token, JSON.stringify({ ping: "kafka" }));

    const session = await sessions.byExternalId(token, { elements: 2 });
    expect(session.chainId).toBe(chain.id);
    expect(session.externalSessionCipId).toBe(token);
    expect(elementNames(session)).toEqual(["Kafka Trigger", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "autoOffsetReset=earliest consumes a message published after deploy the same as latest does",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ sessions }) => {
    // No `covers()` call: see the registry's own `R_KAFKA_OFFSET_RESET_EARLIEST_INDISTINGUISHABLE`
    // reason — with no backlog before the group's first subscribe, this case reads the message the
    // same way the schema's `latest` default does, so it cannot tell the two apart. It still runs to
    // prove the trigger consumes without throwing under the axis value.

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-trigger-offset-earliest");
    const topic = chain.broker.topics as string;
    const token = callToken("kafka-earliest");

    await publish(topic, token, JSON.stringify({ ping: "earliest" }));

    const session = await sessions.byExternalId(token, { elements: 1 });
    expect(session.chainId).toBe(chain.id);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "keyDeserializer=ByteArrayDeserializer consumes a message without throwing",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ sessions }) => {
    // No `covers()` call: see the registry's own `R_KAFKA_SERIALIZER_INDISTINGUISHABLE` reason —
    // this message's bytes are valid under `StringDeserializer` too, so the case cannot tell whether
    // `ByteArrayDeserializer` actually ran. It still runs to prove the trigger consumes without
    // throwing under the axis value.

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-trigger-key-deser");
    const topic = chain.broker.topics as string;
    const token = callToken("kafka-key-deser");

    await publish(topic, token, JSON.stringify({ ping: "key-deser" }), { key: "e2e-key" });

    // `elements: 2` and the name list, not just `elements: 1` and an absence check: the fixture
    // carries a `Header Modification` step after the trigger, and a regression that made the engine
    // ignore the deserializer axis entirely — or one that never ran the second step — would still
    // pass a bare "a session with at least one step exists" assertion.
    const session = await sessions.byExternalId(token, { elements: 2 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["Kafka Trigger", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "valueDeserializer=ByteArrayDeserializer consumes a message without throwing",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ sessions }) => {
    // No `covers()` call: see the registry's own `R_KAFKA_SERIALIZER_INDISTINGUISHABLE` reason.

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-trigger-value-deser");
    const topic = chain.broker.topics as string;
    const token = callToken("kafka-value-deser");

    await publish(topic, token, JSON.stringify({ ping: "value-deser" }));

    const session = await sessions.byExternalId(token, { elements: 2 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["Kafka Trigger", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "a message that fails the chain does not stall the consumer: the next message is still processed",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ sessions }) => {
    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-trigger-fail");
    const topic = chain.broker.topics as string;
    const firstToken = callToken("kafka-fail-1");
    const secondToken = callToken("kafka-fail-2");

    const before = await sessions.idsOf(chain.id);
    await publish(topic, firstToken, JSON.stringify({ ping: "fail-1" }));
    const first = await sessions.byExternalId(firstToken, { elements: 2 });
    expect(first.chainId).toBe(chain.id);
    // The http-sender step fails on a host that can never resolve: this is the contract, not the
    // assertion this case exists to make.
    expect(failedElements(first).map((each) => each.elementName)).toEqual(["HTTP Sender"]);

    await publish(topic, secondToken, JSON.stringify({ ping: "fail-2" }));
    // The contract this case pins: the consumer commits past a failed record and reads the next one
    // rather than re-reading the first forever. A second session appearing at all is the proof — if
    // the offset had not moved, this message would never be picked up.
    const second = await sessions.byExternalId(secondToken, { elements: 2 });
    expect(second.chainId).toBe(chain.id);
    expect(second.id).not.toBe(first.id);

    // The other half of the case: the offset moved *past* the failed
    // record rather than merely tolerating the next one, so the first message is never re-read.
    // `readUntil` spends the full window rather than exiting on the first (always true) reading, and
    // the settled set is checked against exactly the two sessions already found — a third one for
    // this chain would be `first` reprocessed.
    const settled = await readUntil(
      () => sessions.idsOf(chain.id),
      (ids) => [...ids].filter((id) => !before.has(id)).length > 2,
      OFFSET_SETTLE_TIMEOUT,
      1_000,
    );
    const fresh = [...settled].filter((id) => !before.has(id));
    expect(fresh.sort()).toEqual([first.id, second.id].sort());
  },
);

// ---------------------------------------------------------------------------
// kafka-sender-2
// ---------------------------------------------------------------------------

test(
  "an HTTP call drives kafka-sender-2, and the payload lands on the topic",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ request, env, sessions, run }) => {
    covers("kafka-sender-2");
    covers("kafka-sender-2", "connectionSourceType", "manual");
    covers("kafka-sender-2", "securityProtocol", "PLAINTEXT");
    covers("kafka-sender-2", "keySerializer", "org.apache.kafka.common.serialization.StringSerializer");
    covers("kafka-sender-2", "valueSerializer", "org.apache.kafka.common.serialization.StringSerializer");

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-sender-basic");
    const topic = chain.broker.topics as string;
    const body = { ping: "kafka-send", nonce: callToken("nonce") };

    const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
    expect(response.status()).toBe(200);

    const session = await sessions.byExternalId(token, { elements: 3 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "Kafka Sender"]);
    expect(failedElements(session)).toEqual([]);

    // Started only now, after the session lookup above already spent part of its own budget: the
    // topic retains what it holds and `consumeOne` subscribes `fromBeginning`, so a consumer that
    // starts this late still reads the message, and the wait gets the full window instead of one
    // `sessions.byExternalId` poll already ate into.
    const message = await consumeOne(topic, tokenized(run, "kafka-sender-basic-consumer"));
    expect(message, `no message arrived on ${topic} within the wait`).not.toBeNull();
    expect(JSON.parse(message!.value ?? "null")).toEqual(body);
  },
);

test(
  "kafka-sender-2 with keySerializer=ByteArraySerializer sends without throwing",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ request, env, sessions }) => {
    // No `covers()` call: see the registry's own `R_KAFKA_SERIALIZER_INDISTINGUISHABLE` reason.

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-sender-key-ser");
    const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), {
      data: { ping: "key-ser" },
    });
    expect(response.status()).toBe(200);

    const session = await sessions.byExternalId(token, { elements: 3 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "Kafka Sender"]);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "kafka-sender-2 with valueSerializer=ByteArraySerializer sends without throwing",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ request, env, sessions }) => {
    // No `covers()` call: see the registry's own `R_KAFKA_SERIALIZER_INDISTINGUISHABLE` reason.

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-sender-value-ser");
    const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), {
      data: { ping: "value-ser" },
    });
    expect(response.status()).toBe(200);

    const session = await sessions.byExternalId(token, { elements: 3 });
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "Kafka Sender"]);
    expect(failedElements(session)).toEqual([]);
  },
);
