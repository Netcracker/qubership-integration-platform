/**
 * `actionOnDuplicate=throw-exception` on `kafka-trigger-2`, `rabbitmq-trigger-2`, `pubsub-trigger` and
 * the amqp `async-api-trigger`, against `fixtures/brokers/<broker>-trigger-idempotency-throw-exception`.
 * The other idempotency modes come from the shared `idempotency.hbs` partial, which
 * `specs/runtime/http-trigger-idempotency.spec.ts` runs in all four modes, so their rows here are shared.
 * This mode stays per broker because each transport handles the failed duplicate its own way.
 *
 * Each case publishes two messages with one key and reads the second session. The key is a UUID per
 * test execution: both engines write to one idempotency table (`PostgresIdempotentRepository`), and
 * the classic and micro copies of this spec run at the same time. Kafka and the two AMQP triggers read
 * the key off a message header; a pub/sub trigger receives its attributes as one map header, so its
 * fixture keys on the body instead, and its sessions are found by chain id (rule 19).
 *
 * The Kafka consumer commits past the failed record, and both AMQP triggers reject it without a
 * requeue (`maximumRetryAttempts=1`), so the duplicate records one session. The pub/sub trigger
 * acknowledges with `ackMode: AUTO`, which nacks a failed exchange, so the emulator delivers the
 * duplicate again and every delivery fails at the check. Measured on the classic engine: 16
 * deliveries 100 ms apart, then a few more every 60 s, until the case seeks the subscription past the
 * message.
 */
import { randomUUID } from "node:crypto";
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState, type BrokerChain } from "../../support/brokers.js";
import { publishAmqp, publishKafka, publishPubsub, seekPubsubToNow } from "../../support/broker-clients.js";
import { branchName, IDEMPOTENCY_KEY_HEADER } from "../../fixtures/axis-generator.js";
import { callToken } from "../../support/run.js";
import { element, elementNames, type RecordedSession, type Sessions } from "../../support/sessions.js";
import { readUntil } from "../../support/poll.js";
import { coversIdempotency, firstSteps, IDEMPOTENCY_STEP, idempotencyModes } from "../../support/idempotency.js";

const DOWNSTREAM_STEP = "Header Modification";
/** How long a case watches for the duplicate to be delivered again. */
const REDELIVERY_WINDOW = 10_000;

const MODE = idempotencyModes(DOWNSTREAM_STEP).find((each) => each.fixture === "throw-exception")!;

interface Delivery {
  sessions: Sessions;
  chain: BrokerChain;
  key: string;
  /** The steps the session has to carry before it is read. */
  elements: number;
  /** The chain's sessions before the publish. */
  before: Set<string>;
}

interface Broker {
  family: "kafka-trigger-2" | "rabbitmq-trigger-2" | "pubsub-trigger" | "async-api-trigger";
  name: string;
  tag: "@kafka" | "@rabbitmq" | "@pubsub";
  /** Publishes one message carrying `key` and returns the session it recorded. */
  deliver(delivery: Delivery): Promise<RecordedSession>;
  /** Stops the deliveries a failed message left behind, on a broker that delivers it again. */
  release?(chain: BrokerChain): Promise<void>;
}

/** A trigger that reads the correlation header: publishes with a call token and finds the session by it. */
function correlated(publish: (chain: BrokerChain, token: string, key: string) => Promise<void>): Broker["deliver"] {
  return async ({ sessions, chain, key, elements }) => {
    const token = callToken("idempotency");
    await publish(chain, token, key);
    return sessions.byExternalId(token, { elements });
  };
}

const BROKERS: Broker[] = [
  {
    family: "kafka-trigger-2",
    name: "kafka",
    tag: "@kafka",
    deliver: correlated((chain, token, key) => publishKafka(chain.broker.topics as string, token, JSON.stringify({ key }), { headers: { [IDEMPOTENCY_KEY_HEADER]: key } })),
  },
  {
    family: "rabbitmq-trigger-2",
    name: "rabbitmq",
    tag: "@rabbitmq",
    deliver: correlated((chain, token, key) => publishAmqp(chain.broker.exchange as string, chain.broker.routingKey as string, token, { key }, { [IDEMPOTENCY_KEY_HEADER]: key })),
  },
  {
    // The amqp operation: its exchange is the operation path, a fanout one, so the routing key is empty.
    family: "async-api-trigger",
    name: "async-api",
    tag: "@rabbitmq",
    deliver: correlated((chain, token, key) => publishAmqp(chain.broker.integrationOperationPath as string, "", token, { key }, { [IDEMPOTENCY_KEY_HEADER]: key })),
  },
  {
    family: "pubsub-trigger",
    name: "pubsub",
    tag: "@pubsub",
    async deliver({ sessions, chain, key, elements, before }) {
      await publishPubsub(chain.broker.destinationName as string, { key });
      return sessions.earliestOf(chain.id, elements, (each) => !before.has(each.id));
    },
    // The fixtures name the subscription after the topic (`pubsubTopologyIn`).
    release: (chain) => seekPubsubToNow(chain.broker.destinationName as string),
  },
];

test.describe("broker trigger idempotency", { tag: ["@engine", "@sessions"] }, () => {
  for (const broker of BROKERS) {
    test(`${broker.family}: a duplicate key fails at the check`, { tag: [broker.tag] }, async ({ sessions }) => {
      coversIdempotency(broker.family, MODE);
      const chain = brokerChain(readBrokersCorpusState(), `${broker.name}-trigger-idempotency-${MODE.fixture}`);
      const key = randomUUID();
      const first = firstSteps(MODE, DOWNSTREAM_STEP);

      const firstSession = await broker.deliver({ sessions, chain, key, elements: first.length, before: await sessions.idsOf(chain.id) });
      expect(firstSession.chainId).toBe(chain.id);
      expect(elementNames(firstSession)).toEqual(first);

      const before = await sessions.idsOf(chain.id);
      try {
        const second = await broker.deliver({ sessions, chain, key, elements: MODE.steps.length + 1, before });
        const settled = await sessions.settled(second);
        expect(settled.executionStatus).toBe(MODE.executionStatus);
        expect(elementNames(settled)).toEqual([branchName(MODE), ...MODE.steps]);
        expect(element(settled, IDEMPOTENCY_STEP)?.exceptionInfo?.message).toBe(MODE.failure);

        const fresh = await readUntil(
          async () => (await sessions.search({}, { chainId: chain.id })).sessions.filter((each) => !before.has(each.id)),
          (found) => found.length > 1,
          REDELIVERY_WINDOW,
          1_000,
        );
        if (broker.release) expect(fresh.length, "deliveries of the duplicate").toBeGreaterThan(1);
        else expect(fresh.map((each) => each.id), "deliveries of the duplicate").toEqual([second.id]);
        for (const each of fresh) expect(["COMPLETED_WITH_ERRORS", "IN_PROGRESS"]).toContain(each.executionStatus);
      } finally {
        await broker.release?.(chain);
      }
    });
  }
});
