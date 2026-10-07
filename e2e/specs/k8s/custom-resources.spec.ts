/**
 * The `/v1/cr` surface: a micro domain built, deployed, trimmed to one chain, and deleted.
 *
 * `seed-micro` reaches `POST /v1/cr/deploy-chains` only. This case drives the other four
 * operations and reads their effect off the cluster: the YAML the build answers, the Integration
 * and the ConfigMaps a deploy writes, and what a delete leaves behind. The two chains are built in
 * the worker folder; the micro domain is named with the run token, so the sweep finds one this case
 * failed to delete.
 */
import type { APIRequestContext } from "@playwright/test";
import yaml from "js-yaml";
import { test, expect } from "../../support/fixtures.js";
import { MICRO_READY_TIMEOUT } from "../../support/corpus.js";
import { createDeployableChain, MARKER_HEADER, type DeployableChain } from "../../support/deployable.js";
import {
  configMaps,
  integration,
  k8sName,
  microChainUrl,
  microServiceOf,
  type KubeObject,
} from "../../support/kube.js";
import { tokenized } from "../../support/run.js";

/** Four micro pod starts at most, each measured at 60 to 75 s, and the build and the deletes. */
const CASE_TIMEOUT = 600_000;

/** How long the garbage collector gets to remove the ConfigMaps of a deleted Integration. */
const GC_TIMEOUT = 60_000;

/** The label the build puts on a chain's source ConfigMap, holding the snapshot id through `k8sName`. */
const SNAPSHOT_LABEL = "org.qubership.integration.platform/snapshotId";

interface BuiltDocument extends KubeObject {
  kind: string;
}

/** What a GET of the chain answers: 405 while the POST-only route is live, 404 once it is gone. */
async function routeStatus(request: APIRequestContext, domain: string, chain: DeployableChain): Promise<number> {
  return await request
    .get(microChainUrl(domain, chain.contextPath), { failOnStatusCode: false })
    .then((response) => response.status())
    .catch(() => 0);
}

test("a micro domain is built, deployed, trimmed to one chain, and deleted through /v1/cr", { tag: ["@catalog", "@engine", "@tier2"] }, async ({ catalog, folder, run, request }) => {
  test.setTimeout(CASE_TIMEOUT);
  const domain = tokenized(run, "cr");
  const resource = microServiceOf(domain);

  const chain = (what: string) =>
    createDeployableChain(catalog, {
      name: tokenized(run, `cr-${what}`),
      parentId: folder.id,
      contextPath: tokenized(run, `cr-${what}`),
      marker: what,
    });
  const kept = await chain("kept");
  const dropped = await chain("dropped");
  const keptSnapshot = await catalog.createSnapshot(kept.id);
  const droppedSnapshot = await catalog.createSnapshot(dropped.id);
  const snapshotIds = [keptSnapshot.id, droppedSnapshot.id];

  await test.step("POST /v1/cr answers the resources and writes none of them", async () => {
    const documents = yaml
      .loadAll(await catalog.buildCustomResources(domain, snapshotIds))
      .filter((each): each is BuiltDocument => each !== null && typeof each === "object");
    const named = documents.map((each) => `${each.kind} ${each.metadata.name}`);
    expect(named).toEqual(
      expect.arrayContaining([
        `Integration ${resource}`,
        `Service ${resource}`,
        `HTTPRoute ${resource}-routes`,
        `ConfigMap ${resource}-src-cfg`,
      ]),
    );
    const sources = documents
      .filter((each) => each.kind === "ConfigMap" && each.metadata.labels?.[SNAPSHOT_LABEL])
      .map((each) => each.metadata.labels?.[SNAPSHOT_LABEL]);
    expect(sources.sort()).toEqual(snapshotIds.map(k8sName).sort());
    for (const each of documents) expect(each.metadata.labels?.["qip-domain"], `${each.kind} ${each.metadata.name}`).toBe(domain);

    expect(await integration(resource)).toBeNull();
    expect(await configMaps(`qip-domain=${domain}`)).toEqual([]);
  });

  let deployed = false;
  try {
    await test.step("POST /v1/cr/deploy runs both chains on the domain", async () => {
      await catalog.deployCustomResource(domain, snapshotIds);
      deployed = true;
      expect(await integration(resource)).not.toBeNull();
      await expect
        .poll(async () => [await routeStatus(request, domain, kept), await routeStatus(request, domain, dropped)], {
          timeout: MICRO_READY_TIMEOUT,
          message: `the chains on micro domain ${domain} never began to answer`,
        })
        .toEqual([405, 405]);
      const answer = await request.post(microChainUrl(domain, kept.contextPath), { data: { cr: run } });
      expect(answer.status()).toBe(200);
      expect(answer.headers()[MARKER_HEADER]).toBe("kept");
    });

    await test.step("DELETE /v1/cr/{name}/{snapshotId} removes one chain and keeps the other", async () => {
      await catalog.deleteSnapshotFromCustomResource(domain, droppedSnapshot.id);
      expect(await configMaps(`qip-domain=${domain},${SNAPSHOT_LABEL}=${k8sName(droppedSnapshot.id)}`)).toEqual([]);
      expect(await configMaps(`qip-domain=${domain},${SNAPSHOT_LABEL}=${k8sName(keptSnapshot.id)}`)).toHaveLength(1);
      await expect
        .poll(async () => [await routeStatus(request, domain, kept), await routeStatus(request, domain, dropped)], {
          timeout: MICRO_READY_TIMEOUT,
          message: `micro domain ${domain} kept serving the removed chain, or stopped serving the other`,
        })
        .toEqual([405, 404]);
    });

    await test.step("DELETE /v1/cr/{name} removes the Integration and its ConfigMaps", async () => {
      await catalog.deleteCustomResource(domain);
      deployed = false;
      expect(await integration(resource)).toBeNull();
      // The source ConfigMaps belong to the Integration, and the garbage collector removes them.
      await expect
        .poll(async () => (await configMaps(`qip-domain=${domain}`)).map((each) => each.metadata.name), {
          timeout: GC_TIMEOUT,
        })
        .toEqual([]);
    });
  } finally {
    if (deployed) {
      await catalog.deleteCustomResource(domain).catch((cause: unknown) => {
        console.error(`[cleanup] micro domain ${domain} was not deleted; the sweep retries it: ${String(cause)}`);
      });
    }
  }
});
