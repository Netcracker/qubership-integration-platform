// Shown failing: changing the published Kafka token makes the session lookup time out.
import { test as base } from "../../support/fixtures.js";
import { externalSettings } from "../../env/external-settings.js";
import { withBrokerTrigger } from "../../support/external-brokers.js";
import { expectBrokerTrigger } from "../../support/broker-trigger.js";
import { BROKERS_DEPLOY_TIMEOUT, KAFKA_CONSUMER_ATTACH_TIMEOUT, type BrokerChain } from "../../support/brokers.js";

type Broker = "kafka" | "rabbitmq";

const test = base.extend<{ broker: Broker; trigger: BrokerChain }>({
  broker: ["kafka", { option: true }],
  trigger: [async ({ broker, catalog, folder, run }, use) => {
    await withBrokerTrigger(broker, catalog, folder.id, run, use);
  }, { timeout: BROKERS_DEPLOY_TIMEOUT + KAFKA_CONSUMER_ATTACH_TIMEOUT }],
});

test.describe("external brokers", () => {
  const brokers = externalSettings()?.brokers;
  for (const kind of ["kafka", "rabbitmq"] as const) {
    if (!brokers?.[kind]) continue;
    test.describe(kind, () => {
      test.use({ broker: kind });
      test("a message through the trigger records a session", async ({ trigger, sessions }) => {
        await expectBrokerTrigger(kind, trigger, sessions);
      });
    });
  }
});
