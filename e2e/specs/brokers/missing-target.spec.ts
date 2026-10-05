/**
 * The class of failure PR #781 closed: a chain deployed against a topic, queue or exchange nobody
 * ever declared must not read as a healthy deployment.
 *
 * Before the fix, only Kafka's own `KafkaTopicAndConnectionCheckAction` caught this at deploy time.
 * An AMQP consumer against a missing queue reported `DEPLOYED` with a null error while the listener
 * restart-looped, and an AMQP producer to a missing exchange answered the caller 200 with a
 * `COMPLETED_NORMALLY` session while the broker silently discarded the message — both recorded in
 * `docs/product-defects.md` under "Deployment status is not a readiness signal", and both closed by
 * the same PR: `AmpqConnectionCheckAction` now runs a passive-declare check before either side of an
 * AMQP connection goes live, the same shape Kafka's check already had.
 *
 * Every fixture here is spec-owned (`fixtures/templating.ts`'s `SPEC_OWNED_FIXTURES`) and lives
 * outside the brokers seed's corpus scan on purpose. `support/brokers.ts`'s
 * `kafkaTopicsIn`/`rabbitmqTopologyIn` create whatever topology a fixture in that scan names, so a
 * topic or queue sitting in the ordinary corpus would exist by the time this spec ran and the
 * "nobody ever declared this" premise would be false before the first assertion. This file imports,
 * deploys and deletes its own three chains instead, through `withOwnFixture` in
 * `support/spec-owned.ts`.
 *
 * Every assertion is the refusal itself — the deployment's own status and error message — never a
 * downstream symptom. A retriable `DeploymentRetriableException` parks the deployment at
 * `PROCESSING` with a non-empty `errorMessage` and no route ever comes up, the same shape
 * `script-failures.spec.ts`'s unresolved-class case already pins for a different cause. Nothing here
 * reads the engine log: what a caller can see is the deployment's status, and for the one fixture
 * that carries an HTTP trigger, whether its route answers at all.
 */
import { test, expect } from "../../support/fixtures.js";
import { BROKERS_FIXTURE_DIR } from "../../support/brokers.js";
import { DEPLOY_TIMEOUT, ENGINE_CASE_TIMEOUT } from "../../support/corpus.js";
import { deploymentRows, routeStatus, withOwnFixture } from "../../support/spec-owned.js";

test(
  "a kafka-trigger-2 chain pointed at a topic nobody declared never reaches DEPLOYED",
  { tag: ["@engine", "@catalog", "@tier1"] },
  async ({ catalog, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);

    await withOwnFixture(catalog, run, "missing-kafka-topic.yaml", BROKERS_FIXTURE_DIR, async (chain) => {
      await expect
        .poll(async () => await deploymentRows(catalog, chain.id), {
          timeout: DEPLOY_TIMEOUT,
          message: "the engine never reported the missing Kafka topic",
        })
        .toEqual([expect.stringContaining("PROCESSING: Kafka topics (")]);
    });
  },
);

test(
  "a rabbitmq-trigger-2 chain pointed at a queue nobody declared never reaches DEPLOYED",
  { tag: ["@engine", "@catalog", "@tier1"] },
  async ({ catalog, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);

    await withOwnFixture(catalog, run, "missing-rabbitmq-queue.yaml", BROKERS_FIXTURE_DIR, async (chain) => {
      await expect
        .poll(async () => await deploymentRows(catalog, chain.id), {
          timeout: DEPLOY_TIMEOUT,
          message: "the engine never reported the missing RabbitMQ queue",
        })
        .toEqual([expect.stringContaining("PROCESSING: AMQP queue")]);
    });
  },
);

test(
  "a rabbitmq-sender-2 chain pointed at an exchange nobody declared never reaches DEPLOYED",
  { tag: ["@engine", "@catalog", "@tier1"] },
  async ({ env, catalog, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);

    await withOwnFixture(catalog, run, "missing-rabbitmq-exchange.yaml", BROKERS_FIXTURE_DIR, async (chain) => {
      await expect
        .poll(async () => await deploymentRows(catalog, chain.id), {
          timeout: DEPLOY_TIMEOUT,
          message: "the engine never reported the missing RabbitMQ exchange",
        })
        .toEqual([expect.stringContaining("PROCESSING: AMQP exchange")]);

      // The other half of "never reaches DEPLOYED": nothing is serving. A 200 or 405 here would be a
      // chain that went live against an exchange nothing declared, answering the caller normally
      // while the message it sent was silently dropped — exactly the symptom PR #781 closed.
      expect(await routeStatus(env, chain), "a refused deployment is serving").toBe(404);
    });
  },
);
