/**
 * The exchange type axes of `rabbitmq-trigger-2`, asserted on the compiled snapshot.
 *
 * `exchangeType` and `deadLetterExchangeType` are `query: true` in the element's description, so
 * `template.hbs` writes them into the consumer URI. The template also pins `autoDeclare=false`, so
 * the consumer never declares an exchange of that type, and the brokers seed declares every exchange
 * itself. A runtime case passes for any value, so the URI in `xmlDefinition` is where the value is
 * observable.
 */
import { test, expect } from "../../support/fixtures.js";
import { covers } from "../../registry/covers.js";
import { emptyChain } from "../../support/deployable.js";

const EXCHANGE_TYPES = ["direct", "fanout", "headers", "topic"] as const;

/** The properties every case compiles with, so the URI carries both type parameters. */
const BASE_PROPERTIES = {
  connectionSourceType: "manual",
  addresses: "rabbitmq:5672",
  username: "guest",
  password: "guest",
  exchange: "e2e-snapshot-exchange",
  routingKey: "e2e-snapshot-key",
  queues: "e2e-snapshot-queue",
  acknowledgeMode: "AUTO",
  exchangeType: "direct",
  deadLetterExchange: "e2e-snapshot-dlx",
  deadLetterExchangeType: "direct",
};

/** Every value of `parameter` in the trigger's consumer URI. */
function uriParameter(xml: string, parameter: string): string[] {
  const uri = /<from uri="(rabbitmq-custom:[^"]*)"/.exec(xml)?.[1];
  if (!uri) throw new Error(`the snapshot compiled no rabbitmq-custom consumer:\n${xml}`);
  return [...uri.replaceAll("&amp;", "&").matchAll(new RegExp(`[?&]${parameter}=([^&]*)`, "g"))].map((match) => match[1]);
}

for (const axis of ["exchangeType", "deadLetterExchangeType"] as const) {
  for (const value of EXCHANGE_TYPES) {
    test(`rabbitmq-trigger-2 ${axis} ${value} compiles into the consumer URI`, { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
      covers("rabbitmq-trigger-2", axis, value);
      const chainId = await emptyChain(catalog, run, folder.id, `rabbitmq-snapshot-${axis}-${value}`);
      const trigger = await catalog.createElement(chainId, "rabbitmq-trigger-2");
      await catalog.patchElementProperties(chainId, trigger.id, { ...BASE_PROPERTIES, [axis]: value });
      const built = await catalog.createSnapshot(chainId);
      const xml = (await catalog.getSnapshot(chainId, built.id)).xmlDefinition ?? "";

      const other = axis === "exchangeType" ? "deadLetterExchangeType" : "exchangeType";
      expect(uriParameter(xml, axis)).toEqual([value]);
      expect(uriParameter(xml, other)).toEqual(["direct"]);
    });
  }
}
