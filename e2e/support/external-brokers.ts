import { randomUUID } from "node:crypto";
import { expect, request } from "@playwright/test";
import yaml from "js-yaml";
import { externalSettings } from "../env/external-settings.js";
import { inAuthPhase } from "../env/external-auth.js";
import { newPlatformRequest } from "../env/platform-http.js";
import { assembleTrees, BROKERS_FIXTURE_DIR, readFixtureDocument, renderFixture } from "../fixtures/templating.js";
import {
  brokerCorpusOf, deleteKafkaGroups, deleteKafkaTopics, deleteRabbitmqTopology,
  ensureKafkaTopics, ensureRabbitmqTopology, waitForKafkaConsumers, type BrokerChain,
} from "./brokers.js";
import { waitForDeployed, waitForUndeployed } from "./corpus.js";
import { ownResource } from "./owned-resources.js";
import { fail, type Catalog } from "./catalog.js";

export async function withBrokerTrigger(
  kind: "kafka" | "rabbitmq",
  catalog: Catalog,
  folderId: string,
  run: string,
  use: (chain: BrokerChain) => Promise<void>,
): Promise<void> {
  const { brokers, deployment, services } = externalSettings()!;
  const options = brokers?.[kind];
  if (!options || !deployment) throw new Error(`Configure the ${kind} broker and deployment domain.`);

  const fixture = `${kind}-trigger-basic`;
  const suffix = `${run}-${randomUUID().slice(0, 8)}`;
  const tree = renderFixture(BROKERS_FIXTURE_DIR, fixture, suffix);
  const { source } = readFixtureDocument(fixture, tree);
  const ids = new Map<string, string>();
  const remapped = source.replace(/[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}/gi, (id) => {
    if (!ids.has(id)) ids.set(id, randomUUID());
    return ids.get(id)!;
  });
  const document = yaml.load(remapped) as { content: { elements: Array<{ properties: Record<string, unknown> }> } };
  const [trigger] = document.content.elements;
  if ("username" in options) {
    const vhost = decodeURIComponent(new URL(options.host).pathname.slice(1)) || "/";
    Object.assign(trigger.properties, {
      addresses: options.engine, username: options.username, password: options.password, vhost,
    });
  } else {
    Object.assign(trigger.properties, { brokers: options.engine, autoOffsetReset: "earliest" });
  }
  tree.set(`${fixture}.chain.cip.yaml`, yaml.dump(document));
  const trees = [[fixture, tree]] as const;
  const corpus = brokerCorpusOf(run, trees, []);
  const [chain] = corpus.chains;
  const group = String(chain.broker.groupId);
  const removeTopology = async () => {
    await deleteRabbitmqTopology(corpus.rabbitmqTopology);
    await deleteKafkaTopics(corpus.kafkaTopics);
    if (kind === "kafka") await deleteKafkaGroups((id) => id === group);
  };

  await ownResource({ kind, id: chain.name }, async () => {
    await inAuthPhase("setup", async () => {
      await ensureKafkaTopics(corpus.kafkaTopics);
      await ensureRabbitmqTopology(corpus.rabbitmqTopology);
    });
    const { archive } = await assembleTrees(trees);
    await ownResource({ kind: "broker-chain", id: chain.id }, async () => {
      await inAuthPhase("setup", async () => {
        const imported = await catalog.importChains(archive, `${chain.name}.zip`);
        if (!imported.ok()) await fail(imported, "POST", `${services["runtime-catalog"]}/v1/catalog/import`);
        expect(imported.status()).toBe(200);
        expect((await imported.json()).chains.map((row: { id: string }) => row.id)).toEqual([chain.id]);
        await catalog.moveChain(chain.id, folderId);
        await catalog.saveLoggingProperties(chain.id, { sessionsLoggingLevel: "DEBUG" });
        const snapshot = await catalog.createSnapshot(chain.id);
        await catalog.deploy(chain.id, snapshot.id, deployment.domain, deployment.domainType);
        await waitForDeployed(catalog, [chain]);
        if (kind === "kafka") await waitForKafkaConsumers([group]);
      });
      await use(chain);
    }, async () => {
      const existing = await catalog.raw("get", `/v1/chains/${chain.id}`);
      if (existing.status() === 404) return;
      if (!existing.ok()) await fail(existing, "GET", `${services["runtime-catalog"]}/v1/chains/${chain.id}`);
      await catalog.undeployAll(chain.id);
      await waitForUndeployed(catalog, [chain]);
      await catalog.deleteLoggingProperties(chain.id);
      const sessionApi = await newPlatformRequest(request, "sessions-management");
      try {
        const url = `${services["sessions-management"]}/v1/sessions/chains`;
        const response = await sessionApi.delete(url, {
          params: { chainIds: chain.id },
        });
        if (!response.ok()) await fail(response, "DELETE", url, "sessions-management");
      } finally {
        await sessionApi.dispose();
      }
      await catalog.deleteChain(chain.id);
    });
  }, removeTopology);
}
