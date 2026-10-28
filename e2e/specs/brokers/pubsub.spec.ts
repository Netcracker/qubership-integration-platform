/**
 * `pubsub-trigger` and `pubsub-sender`, against `fixtures/brokers/pubsub-*`, over the Google Cloud
 * Pub/Sub emulator (`infrastructure/docker/docker-compose.pubsub.yml`).
 *
 * `CAMEL_PUBSUB_PREDEPLOY_CHECK_ENABLED=false` on the `qip-engine` service (the base
 * `infrastructure/docker/docker-compose.yml`) is what makes any of this deployable at all:
 * `PubSubConnectionCheckAction` builds its own admin client from `serviceAccountKey` with no
 * emulator endpoint, so against the emulator it always reaches real googleapis and fails
 * `UNAUTHENTICATED`. A micro domain does not read that setting and runs its own check, which
 * reaches the emulator through the component. `serviceAccountKey` stays schema-required regardless,
 * so every fixture carries a syntactically valid but fake service-account key the emulator never
 * authenticates.
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
  callChain,
  elementNames,
  failedElements,
  trace,
  type ChainCall,
  type Sessions,
} from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

/** Asserts a sender call answered 200; a failure carries the exceptions its session recorded. */
async function expectSent(sessions: Sessions, call: ChainCall): Promise<void> {
  const cause = call.response.status() === 200
    ? ""
    : trace(await sessions.byExternalId(call.token, { elements: 3 })).map((step) => step.exceptionInfo?.message).filter(Boolean).join("; ");
  expect(call.response.status(), cause).toBe(200);
}

test.describe("pubsub", { tag: ["@engine", "@sessions", "@pubsub"] }, () => {
  test.describe("pubsub-trigger", () => {
    test("a message records a session found by chain id", async ({ sessions }) => {
      covers("pubsub-trigger");
      covers("pubsub-trigger", "ackMode", "AUTO");

      const corpus = readBrokersCorpusState();
      const chain = brokerChain(corpus, "pubsub-trigger-basic");
      const topic = chain.broker.destinationName as string;
      const before = await sessions.idsOf(chain.id);

      await publishPubsub(topic, { ping: "pubsub", token: callToken("pubsub-trigger") });

      const session = await sessions.onlyOf(chain.id, 2, (each) => !before.has(each.id));
      expect(elementNames(session)).toEqual(["PubSub Trigger", "Header Modification"]);
      expect(failedElements(session)).toEqual([]);
    });
  });

  test.describe("pubsub-sender", () => {
    test("an HTTP call puts the payload on the topic", async ({ request, env, sessions }) => {
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
    });

    test("messageOrderingEnabled=true sets the ordering key", async ({ request, env, sessions }) => {
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
    });

    for (const value of [true, false]) {
      const branch = `propagateContext=${value}`;
      test(`${branch} ${value ? "sends" : "drops"} the caller's request id`, async ({ request, env, sessions }) => {
        covers("pubsub-sender", "propagateContext", value);

        const chain = brokerChain(readBrokersCorpusState(), `pubsub-sender-propagate-context-${value}`);
        const topic = chain.broker.destinationName as string;
        const subscription = `${topic}-consumer`;
        const body = { ping: "pubsub-propagate-context", nonce: callToken("nonce") };

        await createPullSubscription(topic, subscription);
        try {
          const call = await callChain(request, env.chainUrl(chain.contextPath), { data: body, headers: { [REQUEST_ID_HEADER]: CALLER_REQUEST_ID } });
          await expectSent(sessions, call);
          const session = await sessions.byExternalId(call.token, { elements: 3 });
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
  });
});
