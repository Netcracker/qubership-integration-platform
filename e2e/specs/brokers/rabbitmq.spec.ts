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
 * The dead-letter pair pins the acknowledge-mode contract `RabbitMqTriggerProcessor.process`
 * carries: it calls `immediateAck(exchange)` as its *first* statement, before `validate` and before
 * the chain body. Under `MANUAL` that is a positive `channel.basicAck`, sent before the chain has
 * even run, so a failure afterwards can never reject the delivery and can never reach the
 * dead-letter queue. Under `AUTO` (the schema default) the container acks or nacks after the chain
 * finishes, and `elements/rabbitmq-trigger-2/template.hbs` pins `maximumRetryAttempts=1` on the
 * consumer URI, so a deterministic failure is rejected after its one attempt and lands on the
 * dead-letter queue the brokers seed declared from this element's own `deadLetterExchange`,
 * `deadLetterExchangeType`, `deadLetterQueue` and `deadLetterRoutingKey` properties.
 */
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState } from "../../support/brokers.js";
import { publishAmqp as publish, bindConsumerQueue, waitForAmqpMessage as waitForMessage } from "../../support/broker-clients.js";
import { callToken } from "../../support/run.js";
import { callChain, elementNames, failedElements } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

/** What `waitForMessage`'s MANUAL-case absence window gets, instead of a bare literal. */
const MANUAL_DLQ_ABSENCE_TIMEOUT = 15_000;

// ---------------------------------------------------------------------------
// rabbitmq-trigger-2
// ---------------------------------------------------------------------------

test(
  "a RabbitMQ message carrying the correlation header finds the session the trigger recorded",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ sessions }) => {
    covers("rabbitmq-trigger-2");
    covers("rabbitmq-trigger-2", "connectionSourceType", "manual");
    covers("rabbitmq-trigger-2", "acknowledgeMode", "AUTO");
    // No exchangeType covers() call: see the registry's own R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH
    // reason — the template does read the property, but the brokers seed declares the exchange
    // independently, so this case would pass identically against a fanout or topic exchange.

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
  },
);

test(
  "a rejected message under acknowledgeMode AUTO lands on the configured dead-letter queue",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ sessions }) => {
    // No deadLetterExchangeType covers() call, for the same reason the trigger's own exchangeType
    // case has none: see R_RABBITMQ_EXCHANGE_TYPE_PASSTHROUGH.

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "rabbitmq-trigger-dlq-auto");
    const exchange = chain.broker.exchange as string;
    const routingKey = chain.broker.routingKey as string;
    const dlx = chain.broker.deadLetterExchange as string;
    const dlk = chain.broker.deadLetterRoutingKey as string;
    const token = callToken("rabbitmq-dlq-auto");
    const body = { ping: "dlq-auto" };

    const { connection, channel, queue } = await bindConsumerQueue(dlx, dlk);
    try {
      await publish(exchange, routingKey, token, body);

      // The http-sender step fails on a host that can never resolve: this is the contract, not the
      // assertion this case exists to make.
      const session = await sessions.byExternalId(token, { elements: 2 });
      expect(session.chainId).toBe(chain.id);
      expect(failedElements(session).map((each) => each.elementName)).toEqual(["HTTP Sender"]);

      // `waitForMessage` starts its own timeout only now, after the session lookup above already
      // spent part of its own budget — a queue holds an unconsumed message regardless of when a
      // consumer attaches, so starting to listen this late loses nothing, and the wait gets the
      // full window instead of one `sessions.byExternalId` poll already ate into.
      //
      // The contract this case pins: under AUTO, maximumRetryAttempts=1 on the consumer URI means
      // the failed delivery is rejected after one attempt and lands on the dead-letter queue the
      // seed declared from this element's own deadLetter* properties.
      const message = await waitForMessage(channel, queue);
      expect(message, `no message arrived on the dead-letter queue bound to ${dlx}/${dlk}`).not.toBeNull();
      expect(message!.body).toEqual(body);
    } finally {
      await channel.close();
      await connection.close();
    }
  },
);

test(
  "a rejected message under acknowledgeMode MANUAL never reaches the dead-letter queue",
  { tag: ["@engine", "@sessions", "@tier2"] },
  async ({ sessions }) => {
    covers("rabbitmq-trigger-2", "acknowledgeMode", "MANUAL");

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "rabbitmq-trigger-dlq-manual");
    const exchange = chain.broker.exchange as string;
    const routingKey = chain.broker.routingKey as string;
    const dlx = chain.broker.deadLetterExchange as string;
    const dlk = chain.broker.deadLetterRoutingKey as string;
    const token = callToken("rabbitmq-dlq-manual");
    const body = { ping: "dlq-manual" };

    const { connection, channel, queue } = await bindConsumerQueue(dlx, dlk);
    try {
      await publish(exchange, routingKey, token, body);

      // The chain still runs and still fails -- MANUAL only changes when the ack was sent, not
      // whether the chain body executes.
      const session = await sessions.byExternalId(token, { elements: 2 });
      expect(session.chainId).toBe(chain.id);
      expect(failedElements(session).map((each) => each.elementName)).toEqual(["HTTP Sender"]);

      // `waitForMessage` starts its 15 s absence window only now, after the session lookup above
      // has already settled -- started earlier, that window could close while the lookup was still
      // polling, so a late dead-lettered message would arrive after this test had already stopped
      // listening for it and the case would pass regardless of what actually happened. A queue
      // holds an unconsumed message until something reads it, so waiting to listen loses nothing.
      //
      // The contract this case pins, the opposite of the AUTO case: RabbitMqTriggerProcessor acks a
      // MANUAL delivery before the chain body runs, so the failure that follows can never reject it
      // and it never reaches the dead-letter queue.
      expect(
        await waitForMessage(channel, queue, MANUAL_DLQ_ABSENCE_TIMEOUT),
        `a message arrived on the MANUAL case's dead-letter queue bound to ${dlx}/${dlk}, which the ` +
          `immediate ack should have made impossible`,
      ).toBeNull();
    } finally {
      await channel.close();
      await connection.close();
    }
  },
);

// ---------------------------------------------------------------------------
// rabbitmq-sender-2
// ---------------------------------------------------------------------------

test(
  "an HTTP call drives rabbitmq-sender-2, and the payload lands on the exchange",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ request, env, sessions }) => {
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
  },
);
