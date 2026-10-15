/**
 * `pubsub-trigger` and `pubsub-sender`, against `fixtures/brokers/pubsub-*`, over the Google Cloud
 * Pub/Sub emulator (`infrastructure/docker/docker-compose.pubsub.yml`).
 *
 * `CAMEL_PUBSUB_PREDEPLOY_CHECK_ENABLED=false` on the `qip-engine` service (the base
 * `infrastructure/docker/docker-compose.yml`) is what makes any of this deployable at all:
 * `PubSubConnectionCheckAction` builds its own admin client from `serviceAccountKey` with no
 * emulator endpoint, so against the emulator it always reaches real googleapis and fails
 * `UNAUTHENTICATED`. `serviceAccountKey` stays schema-required regardless, so every fixture carries
 * a syntactically valid but fake service-account key the emulator never authenticates.
 *
 * `pubsub-trigger` carries no correlation-header path at all: `CustomCamelMessageReceiver` sets
 * `GooglePubsubConstants.ATTRIBUTES` as **one** header holding the whole attributes map, never
 * spread into individually named headers the way Kafka's record headers and RabbitMQ's AMQP headers
 * are. And `SessionsService.startSession` reads `Headers.EXTERNAL_SESSION_CIP_ID` off the exchange
 * at exchange-creation time, before any route element -- including a `header-modification` step that
 * could otherwise unpack the map -- has run. So a trigger case correlates the two-hop way
 * `sftp-trigger-2` does: `sessions.idsOf` before the publish, `sessions.onlyOf` for the one session
 * that lookup did not already carry.
 *
 * `pubsub-sender` is the mirror image of the other senders: an ordinary HTTP call drives the chain,
 * correlated through `sessions.byExternalId`, and the spec pulls its own subscription to prove a
 * real message landed with the payload the call carried. Pub/Sub does not replay a topic's backlog
 * to a subscription created after the fact, so every pull-side subscription is created **before**
 * the call that produces the message -- the same "bind before producing" rule `rabbitmq.spec.ts`
 * follows for its exchange bindings.
 *
 * `propagateContext` is read off the pulled message's attributes: `google-pubsub` publishes every
 * header its filter lets through as an attribute.
 *
 * The emulator is driven over its plain REST admin/data API (documented at the top of
 * `docker-compose.pubsub.yml`), not the `@google-cloud/pubsub` client library: the emulator needs no
 * authentication and the REST surface is small enough that a client library buys nothing here.
 */
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState } from "../../support/brokers.js";
import {
  createPullSubscription,
  deletePullSubscription,
  publishPubsub,
  pullOnePubsub,
} from "../../support/broker-clients.js";
import { tokenized, callToken } from "../../support/run.js";
import {
  CALLER_REQUEST_ID,
  HTTP_TRIGGER_STEPS,
  REQUEST_ID_HEADER,
  SESSION_TIMEOUT,
  callChain,
  elementNames,
  failedElements,
  trace,
  type ChainCall,
  type Sessions,
} from "../../support/sessions.js";
import { readUntil } from "../../support/poll.js";
import { logWindowStart } from "../../support/logs.js";
import { strikesAsKnown } from "../../support/known-defect.js";
import { covers } from "../../registry/covers.js";
import { PUBSUB_EMULATOR_BYPASSED, pubsubDelivered } from "./micro-defects.js";

/** What `readUntil` gets for the ackMode=NONE case's settle window, instead of a bare literal. */
const ACKMODE_NONE_SETTLE_TIMEOUT = 12_000;

/** Asserts a sender call answered 200; a failure carries the exceptions its session recorded. */
async function expectSent(sessions: Sessions, call: ChainCall): Promise<void> {
  // A failed step can still read IN_PROGRESS, with no exception, after its session has completed.
  const causes = async () =>
    trace(await sessions.byExternalId(call.token, { elements: 3 })).map((step) => step.exceptionInfo?.message).filter(Boolean);
  const cause = call.response.status() === 200
    ? ""
    : (await readUntil(causes, (found) => found.length > 0, SESSION_TIMEOUT, 1_000)).join("; ");
  await strikesAsKnown(PUBSUB_EMULATOR_BYPASSED, async () => expect(call.response.status(), cause).toBe(200));
}

// ---------------------------------------------------------------------------
// pubsub-trigger
// ---------------------------------------------------------------------------

test(
  "a pub/sub message with no correlation header fires pubsub-trigger, found by chain id",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ env, sessions }) => {
    covers("pubsub-trigger");
    covers("pubsub-trigger", "ackMode", "AUTO");

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "pubsub-trigger-basic");
    const topic = chain.broker.destinationName as string;
    const before = await sessions.idsOf(chain.id);
    const since = logWindowStart();

    await publishPubsub(topic, { ping: "pubsub", token: callToken("pubsub-trigger") });

    const session = await pubsubDelivered(env, since, () => sessions.onlyOf(chain.id, 2, (each) => !before.has(each.id)));
    expect(elementNames(session)).toEqual(["PubSub Trigger", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "ackMode NONE still records a session: the route never reads the acknowledger header",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ env, sessions }) => {
    // No `covers()` call: see the registry's own `R_PUBSUB_ACKMODE_NONE_INDISTINGUISHABLE` reason.
    // This case still runs to prove the trigger keeps recording under `ackMode: NONE` rather than
    // failing outright, which is worth having even though its chain completes, and on a completing
    // chain NONE and AUTO leave the same trace.

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "pubsub-trigger-ackmode-none");
    const topic = chain.broker.destinationName as string;
    const before = await sessions.idsOf(chain.id);
    const since = logWindowStart();

    await publishPubsub(topic, { ping: "pubsub-ackmode-none" });

    // `CustomCamelMessageReceiver` only acks when `ackMode != NONE`, so under NONE the message is
    // never acknowledged. Measured against this stack's emulator: the google-cloud-pubsub client the
    // engine embeds keeps extending the message's lease in the background for as long as the
    // exchange is in flight, so no redelivery is observable inside any bounded window a case can
    // afford to wait -- a 25 s wait produced none, and this case's own observable (one session, no
    // failed elements) is identical to the AUTO case's, so it cannot stand as proof the axis value
    // took effect. A failing chain would differ: AUTO nacks it and the emulator delivers it again
    // (trigger-idempotency.spec.ts). `readUntil` still spends the whole window rather than exiting on
    // the first (always true) reading, so this is a genuine bounded wait for "nothing more happened",
    // not a `toPass` that a first, too-early poll would satisfy.
    const settled = await readUntil(
      () => sessions.idsOf(chain.id),
      (ids) => [...ids].filter((id) => !before.has(id)).length > 1,
      ACKMODE_NONE_SETTLE_TIMEOUT,
      1_000,
    );
    const fresh = [...settled].filter((id) => !before.has(id));
    await pubsubDelivered(env, since, async () => expect(fresh.length, "exactly one session for the one message published").toBe(1));
    expect(failedElements(await sessions.session(fresh[0]))).toEqual([]);
  },
);

// ---------------------------------------------------------------------------
// pubsub-sender
// ---------------------------------------------------------------------------

test(
  "an HTTP call drives pubsub-sender, and the payload lands on the topic",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ request, env, sessions }) => {
    covers("pubsub-sender");
    covers("pubsub-sender", "messageOrderingEnabled", false);

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "pubsub-sender-basic");
    const topic = chain.broker.destinationName as string;
    const subscription = `${topic}-consumer`;
    const body = { ping: "pubsub-send", nonce: callToken("nonce") };

    await createPullSubscription(topic, subscription);
    try {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
      await expectSent(sessions, call);

      const session = await sessions.byExternalId(call.token, { elements: 3 });
      expect(session.chainId).toBe(chain.id);
      expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "PubSub Sender"]);
      expect(failedElements(session)).toEqual([]);

      const message = await pullOnePubsub(subscription);
      expect(message, `no message arrived on subscription ${subscription} within the wait`).not.toBeNull();
      expect(message!.data).toEqual(body);
    } finally {
      await deletePullSubscription(subscription);
    }
  },
);

test(
  "pubsub-sender with messageOrderingEnabled=true publishes the message under its ordering key",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ request, env, sessions }) => {
    covers("pubsub-sender", "messageOrderingEnabled", true);

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "pubsub-sender-ordering");
    const topic = chain.broker.destinationName as string;
    const subscription = `${topic}-consumer`;
    const body = { ping: "pubsub-ordering", nonce: callToken("nonce") };

    await createPullSubscription(topic, subscription);
    try {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
      await expectSent(sessions, call);

      const session = await sessions.byExternalId(call.token, { elements: 3 });
      expect(session.chainId).toBe(chain.id);
      expect(failedElements(session)).toEqual([]);

      const message = await pullOnePubsub(subscription);
      expect(message, `no message arrived on subscription ${subscription} within the wait`).not.toBeNull();
      expect(message!.data).toEqual(body);
      expect(message!.orderingKey).toBe(tokenized(corpus.run, "pubsub-sender-ordering-key"));
    } finally {
      await deletePullSubscription(subscription);
    }
  },
);

for (const value of [true, false]) {
  const branch = `propagateContext=${value}`;
  test(`${branch}: pubsub-sender ${value ? "sends" : "drops"} the caller's request id`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("pubsub-sender", "propagateContext", value);

    const chain = brokerChain(readBrokersCorpusState(), `pubsub-sender-propagate-context-${value}`);
    const topic = chain.broker.destinationName as string;
    const subscription = `${topic}-consumer`;
    const body = { ping: "pubsub-propagate-context", nonce: callToken("nonce") };

    await createPullSubscription(topic, subscription);
    try {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: body, headers: { [REQUEST_ID_HEADER]: CALLER_REQUEST_ID } });
      await expectSent(sessions, call);
      const session = await sessions.settled(await sessions.byExternalId(call.token, { elements: 3 }));
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch]);
      expect(failedElements(session)).toEqual([]);

      const message = await pullOnePubsub(subscription);
      expect(message, `no message arrived on subscription ${subscription} within the wait`).not.toBeNull();
      expect(message!.data).toEqual(body);
      expect(message!.attributes[REQUEST_ID_HEADER]).toBe(value ? CALLER_REQUEST_ID : undefined);
    } finally {
      await deletePullSubscription(subscription);
    }
  });
}
