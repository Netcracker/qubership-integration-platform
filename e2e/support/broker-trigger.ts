import { expect } from "@playwright/test";
import { publishAmqp, publishKafka } from "./broker-clients.js";
import type { BrokerChain } from "./brokers.js";
import { callToken } from "./run-token.js";
import { elementNames, failedElements, type Sessions } from "./sessions.js";

export async function expectBrokerTrigger(
  kind: "kafka" | "rabbitmq",
  chain: BrokerChain,
  sessions: Sessions,
): Promise<void> {
  const token = callToken(`${kind}-consume`);
  const body = { ping: kind };
  if (kind === "kafka") await publishKafka(String(chain.broker.topics), token, JSON.stringify(body));
  else await publishAmqp(String(chain.broker.exchange), String(chain.broker.routingKey), token, body);

  const session = await sessions.byExternalId(token, { elements: 2 });
  expect(session.chainId).toBe(chain.id);
  expect(session.externalSessionCipId).toBe(token);
  expect(elementNames(session)).toEqual([kind === "kafka" ? "Kafka Trigger" : "RabbitMQ Trigger", "Header Modification"]);
  expect(failedElements(session)).toEqual([]);
}
