/**
 * What happens to an already-attached consumer when its broker container disappears and comes back.
 *
 * Every other spec in `specs/brokers/` proves a trigger works against a broker that was already
 * there when the deployment happened. This is the other half: the broker goes away *after* the
 * consumer attached, and the platform's own reconnect logic — not a redeploy — is what has to bring
 * it back. `Env.restartOverlay` is the one seam this needs: it restarts the broker's own container
 * in place (never recreated, never rebuilt) and waits until it is serving again, so this spec never
 * names `docker`.
 *
 * Reuses `kafka-trigger-basic` and `rabbitmq-trigger-basic` from the brokers seed rather than a
 * fixture of its own — both chains are already deployed with an attached consumer by the time this
 * runs, and the only new thing this file needs is the restart itself.
 *
 * **Runs in its own project, `brokers-restart`, strictly after `brokers` finishes** — see
 * `playwright.config.ts`. That split is load-bearing, not decoration: `brokers` runs
 * `fullyParallel: true` across eight workers with no guaranteed order between files, and every other
 * broker spec shares this run's topics, queues and consumer groups. A container restart mid-flight
 * would turn an unrelated spec's timeout into this one's fault — the same reason
 * `.apm/instructions/e2e.instructions.md` rule 3 moves a service restart out of `specs/api/` and
 * `specs/runtime/` into its own place after they are done.
 */
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState } from "../../support/brokers.js";
import { publishKafka, publishAmqp } from "../../support/broker-clients.js";
import { callToken } from "../../support/run.js";
import { failedElements } from "../../support/sessions.js";

/**
 * What `sessions.byExternalId` gets instead of `SESSION_TIMEOUT` (20 s) for a case published right
 * after a broker restart.
 *
 * The trigger's reconnect loop was measured at 5 s. `support/brokers.ts`
 * measured a fresh Kafka consumer group taking 6-9 s to attach after `DEPLOYED`, and
 * `ensureRabbitmqTopology`'s own header measured the same lag for RabbitMQ — both on a broker that
 * had never gone away, so a restart pays that attach cost again on top of the reconnect itself. This
 * is the reconnect plus the larger of the two attach measurements, doubled for headroom on a loaded
 * stack.
 */
const RESTART_SESSION_TIMEOUT = 60_000;

/** What this spec gets instead of the suite's 120 s default: the restart's own 180 s budget, plus the session wait. */
const RESTART_CASE_TIMEOUT = 240_000;

test(
  "a kafka-trigger-2 chain keeps consuming once its broker container comes back from a restart",
  { tag: ["@engine", "@sessions", "@infra", "@tier2"] },
  async ({ env, sessions }) => {
    test.setTimeout(RESTART_CASE_TIMEOUT);

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "kafka-trigger-basic");
    const topic = chain.broker.topics as string;

    await env.restartOverlay("kafka");

    const token = callToken("kafka-restart");
    await publishKafka(topic, token, JSON.stringify({ ping: "restart" }));

    const session = await sessions.byExternalId(token, { elements: 2, timeout: RESTART_SESSION_TIMEOUT });
    expect(session.chainId).toBe(chain.id);
    expect(failedElements(session)).toEqual([]);
  },
);

test(
  "a rabbitmq-trigger-2 chain keeps consuming once its broker container comes back from a restart",
  { tag: ["@engine", "@sessions", "@infra", "@tier2"] },
  async ({ env, sessions }) => {
    test.setTimeout(RESTART_CASE_TIMEOUT);

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "rabbitmq-trigger-basic");
    const exchange = chain.broker.exchange as string;
    const routingKey = chain.broker.routingKey as string;

    await env.restartOverlay("rabbitmq");

    const token = callToken("rabbitmq-restart");
    await publishAmqp(exchange, routingKey, token, { ping: "restart" });

    const session = await sessions.byExternalId(token, { elements: 2, timeout: RESTART_SESSION_TIMEOUT });
    expect(session.chainId).toBe(chain.id);
    expect(failedElements(session)).toEqual([]);
  },
);
