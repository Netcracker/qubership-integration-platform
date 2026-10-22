/**
 * `rabbitmq-trigger-2` and `rabbitmq-sender-2`, against `fixtures/brokers/rabbitmq-*`.
 *
 * Correlation is per call: the
 * producing side sets a per-call `external-session-cip-id` **AMQP header**, picked up for any
 * trigger the same way an HTTP header is. So a trigger case never calls the chain itself; it
 * publishes with `amqplib` and finds the session the same way an HTTP case would.
 *
 * A sender case is the mirror image: an ordinary HTTP call drives the chain (correlated the usual
 * way, through `sessions.byExternalId`), and the spec binds its own queue to the sender's exchange
 * with `amqplib` to prove a real message landed with the payload the call carried -- the session
 * trace alone only proves the `rabbitmq-sender-2` step ran, not that anything reached the broker.
 * `elements/rabbitmq-sender-2/template.hbs` pins no `autoDeclareProducer`, and Camel's
 * `spring-rabbitmq` defaults that to `false`, so nothing declares the exchange on the sender's own
 * side either -- the brokers seed declares it (`ensureRabbitmqTopology`'s `senderExchanges`), and
 * this spec's own queue binds to what the seed already declared.
 *
 * The dead-letter cases pin the acknowledge-mode contract `RabbitMqTriggerProcessor.process`
 * carries: it calls `immediateAck(exchange)` as its *first* statement, before `validate` and before
 * the chain body. Under `MANUAL` that is a positive `channel.basicAck`, sent before the chain has
 * even run, so a failure afterwards can never reject the delivery and can never reach the
 * dead-letter queue. Under `AUTO` (the schema default) the container acks or nacks after the chain
 * finishes, and `elements/rabbitmq-trigger-2/template.hbs` pins `maximumRetryAttempts=1` on the
 * consumer URI, so a deterministic failure is rejected after its one attempt and lands on the
 * dead-letter queue the brokers seed declared from this element's own `deadLetterExchange`,
 * `deadLetterExchangeType`, `deadLetterQueue` and `deadLetterRoutingKey` properties. Under `NONE` the
 * container consumes with `autoAck`, so the broker counts the delivery as acknowledged when it sends
 * it: the failure records one session and the message is neither redelivered nor dead-lettered.
 * MANUAL and NONE therefore leave the same state, and each differs from AUTO by the empty
 * dead-letter queue.
 *
 * `propagateContext` is read off the AMQP headers of the message the spec's own queue receives. The
 * false case is a `test.fail()`: `RabbitMqSenderProcessor` sends the request context either way.
 */
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState } from "../../support/brokers.js";
import { publishAmqp as publish, bindConsumerQueue, expectAckOutcome, waitForAmqpMessage as waitForMessage } from "../../support/broker-clients.js";
import { callToken } from "../../support/run.js";
import { CALLER_REQUEST_ID, callChain, elementNames, failedElements, HTTP_TRIGGER_STEPS, REQUEST_ID_HEADER, type Sessions } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { notTheKnownDefect, outsideTheDefect } from "../../support/known-defect.js";
import type { APIRequestContext } from "@playwright/test";
import type { Env } from "../../env/index.js";

test.describe("rabbitmq", { tag: ["@engine", "@sessions", "@rabbitmq"] }, () => {
  test.describe("rabbitmq-trigger-2", () => {
    test("a message with the correlation header records a session", async ({ sessions }) => {
      covers("rabbitmq-trigger-2");
      covers("rabbitmq-trigger-2", "connectionSourceType", "manual");
      covers("rabbitmq-trigger-2", "acknowledgeMode", "AUTO");
      // No exchangeType covers() call: the brokers seed declares the exchange itself, so this case
      // passes for any type. specs/api/rabbitmq-trigger-snapshot.spec.ts covers the axis on the compile.

      const corpus = readBrokersCorpusState();
      const chain = brokerChain(corpus, "rabbitmq-trigger-basic");
      const exchange = chain.broker.exchange as string;
      const routingKey = chain.broker.routingKey as string;
      const token = callToken("rabbitmq-consume");

      await publish(exchange, routingKey, token, { ping: "rabbitmq" });

      const session = await sessions.byExternalId(token, { elements: 2 });
      expect(session.chainId).toBe(chain.id);
      expect(session.externalSessionCipId).toBe(token);
      expect(elementNames(session)).toEqual(["RabbitMQ Trigger", "Header Modification"]);
      expect(failedElements(session)).toEqual([]);
    });

    const DEAD_LETTER_CASES = [
      { mode: "AUTO", fixture: "rabbitmq-trigger-dlq-auto", deadLettered: true },
      { mode: "MANUAL", fixture: "rabbitmq-trigger-dlq-manual", deadLettered: false },
      { mode: "NONE", fixture: "rabbitmq-trigger-dlq-none", deadLettered: false },
    ] as const;

    for (const { mode, fixture, deadLettered } of DEAD_LETTER_CASES) {
      test(`acknowledgeMode ${mode} ${deadLettered ? "dead-letters" : "drops"} a failed message`, async ({ sessions }) => {
        covers("rabbitmq-trigger-2", "acknowledgeMode", mode);
        // No deadLetterExchangeType covers() call, for the reason the first case gives for exchangeType.

        // The http-sender step fails on a host that can never resolve, in every mode.
        const chain = brokerChain(readBrokersCorpusState(), fixture);
        const target = {
          exchange: chain.broker.exchange as string,
          routingKey: chain.broker.routingKey as string,
          queue: chain.broker.queues as string,
          deadLetterExchange: chain.broker.deadLetterExchange as string,
          deadLetterRoutingKey: chain.broker.deadLetterRoutingKey as string,
        };
        await expectAckOutcome(sessions, chain.id, target, callToken(fixture), { ping: fixture }, deadLettered);
      });
    }
  });

  test.describe("rabbitmq-sender-2", () => {
    test("an HTTP call puts the payload on the exchange", async ({ request, env, sessions }) => {
      covers("rabbitmq-sender-2");
      covers("rabbitmq-sender-2", "connectionSourceType", "manual");

      const corpus = readBrokersCorpusState();
      const chain = brokerChain(corpus, "rabbitmq-sender-basic");
      const exchange = chain.broker.exchange as string;
      const routingKey = chain.broker.routingKey as string;
      const body = { ping: "rabbitmq-send", nonce: callToken("nonce") };

      // The binding has to exist before the call, the same reason a direct exchange with no bound
      // queue reports `{"routed": false}` and discards the message: an exchange retains nothing, so
      // a queue bound after the call would never see it.
      const { connection, channel, queue } = await bindConsumerQueue(exchange, routingKey);
      try {
        const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body });
        expect(response.status()).toBe(200);

        const session = await sessions.byExternalId(token, { elements: 3 });
        expect(session.chainId).toBe(chain.id);
        expect(elementNames(session)).toEqual(["HTTP Trigger", "Validate Request", "RabbitMQ Sender"]);
        expect(failedElements(session)).toEqual([]);

        // Started only now, after the session lookup above already spent part of its own budget: the
        // queue is already bound and retains what it holds, so the wait gets the full window instead
        // of one `sessions.byExternalId` poll already ate into.
        const message = await waitForMessage(channel, queue);
        expect(
          message,
          `no message arrived on the queue bound to ${exchange}/${routingKey} within the wait`,
        ).not.toBeNull();
        expect(message!.body).toEqual(body);
      } finally {
        await channel.close();
        await connection.close();
      }
    });

    /** Drives the `propagateContext` chain of `value` with the caller's request id, and returns the headers of the message it sent. */
    async function sentHeaders(request: APIRequestContext, env: Env, sessions: Sessions, value: boolean): Promise<Record<string, unknown>> {
      const chain = brokerChain(readBrokersCorpusState(), `rabbitmq-sender-propagate-context-${value}`);
      const exchange = chain.broker.exchange as string;
      const routingKey = chain.broker.routingKey as string;
      const body = { ping: "rabbitmq-propagate-context", nonce: callToken("nonce") };

      const { connection, channel, queue } = await bindConsumerQueue(exchange, routingKey);
      try {
        const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: body, headers: { [REQUEST_ID_HEADER]: CALLER_REQUEST_ID } });
        expect(response.status()).toBe(200);
        const session = await sessions.settled(await sessions.byExternalId(token, { elements: 3 }));
        expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, `propagateContext=${value}`]);
        expect(failedElements(session)).toEqual([]);

        const message = await waitForMessage(channel, queue);
        expect(message, `no message arrived on the queue bound to ${exchange}/${routingKey} within the wait`).not.toBeNull();
        expect(message!.body).toEqual(body);
        return message!.headers;
      } finally {
        await channel.close();
        await connection.close();
      }
    }

    test("propagateContext=true sends the caller's request id", async ({ request, env, sessions }) => {
      covers("rabbitmq-sender-2", "propagateContext", true);
      expect((await sentHeaders(request, env, sessions, true))[REQUEST_ID_HEADER]).toBe(CALLER_REQUEST_ID);
    });

    // `RabbitMqSenderProcessor` copies the request context into the message headers on every send, so the
    // caller's request id reaches the message with `propagateContext` false too, on both engines. No issue
    // is filed yet; the registry row stays not covered.
    test.fail("propagateContext=false drops the caller's request id", async ({ request, env, sessions }) => {
      const headers = await outsideTheDefect("sending through the chain", () => sentHeaders(request, env, sessions, false));
      const received = headers[REQUEST_ID_HEADER];
      if (received !== undefined && received !== CALLER_REQUEST_ID) {
        notTheKnownDefect(`the message carried ${REQUEST_ID_HEADER} ${String(received)}, which is neither the caller's id nor no id`);
      }
      expect(received, "the sender drops the request id: delete the test.fail() annotation and cover the row").toBeUndefined();
    });
  });
});
