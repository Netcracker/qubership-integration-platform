/**
 * `engine-controller`, whole — six operations, not the two an earlier draft counted — plus the
 * engine's own `GET /v1/engine/sessions`.
 *
 * The catalog side is `GET /v1/catalog/domains`, `/domains/hosts`, `/domains/{d}/engines`,
 * `/domains/{d}/engines/{host}/deployments`, `/domains/{d}/deployments/count`, and
 * `POST /domains/{d}/deployments/update`.
 *
 * Global by construction, for two separate reasons and either would be enough:
 *
 * - **`POST .../deployments/update` writes a static.** `DeploymentService` keeps a
 *   `private static final Map fullDeploymentsUpdateCache` and a `private static Long
 *   deploymentsUpdateVersion`; a request with an empty `excludeDeployments` reads that cache and
 *   fills it, for every caller, until a deployment modification clears it. It is also the engine's
 *   own feed, so a call here is a call on the wire the running engine listens to.
 * - **the counting and listing operations span the domain.** `deployments/count` counts every row
 *   in `catalog.deployments` for the domain, and `engines/{host}/deployments` lists what one pod is
 *   running. Both move whenever any worker deploys, so a delta over them is only readable where
 *   nothing else is deploying.
 *
 * `GET /v1/engine/sessions` is here rather than in `live-exchanges.spec.ts` because it is about the
 * engine as a component and not about an exchange in flight; the exchange trio has its own file.
 * The engine's other three operations — session retry, checkpoint retry and the failed-sessions
 * list — are covered by `specs/runtime/checkpoint-retry.spec.ts`, where the checkpoint fixture is
 * called, and nothing here claims them.
 *
 * Four shapes measured against the stack rather than assumed:
 *
 * - **`/domains/{d}/engines` is always empty under Compose, and that is not "no engine".** The
 *   `development` profile wires `DevModeDomainSource`, whose `getDomainPods` is `return List.of()`
 *   whatever it is asked; `ClassicDomainSource`, the one that asks Kubernetes for pods, is
 *   `@Profile("!development")` and is what the chart runs. `/domains/hosts` reads what the engines
 *   registered in Consul and answers on both targets. Which of the two the target runs is
 *   `Env.domainFacts()`, so the emptiness is pinned as the dev-mode contract on Compose rather than
 *   mistaken for an engine that is not there, and the pod list is pinned to the host list on a
 *   cluster. A running micro domain registers a host key of its own, so the host keys are read
 *   without the micro domains the catalog lists.
 * - **an engine host nothing answers to is not an error**: `/engines/{ip}/deployments` answers
 *   `200 []`, and so does `/domains/{unknown}/engines`; `/domains/{unknown}/deployments/count`
 *   answers `200 0`. There is no 404 anywhere in this controller.
 * - **a checkpoint session survives only a failure.** `CamelDebugger.finishCheckpointSession`
 *   deletes the `SessionInfo` row for a session that completed normally and keeps it for
 *   `COMPLETED_WITH_ERRORS`, which is why the chain this file builds ends in a script that throws.
 */
import { test, expect } from "../../support/fixtures.js";
import { DEPLOY_TIMEOUT, ENGINE_CASE_TIMEOUT, readCorpusState } from "../../support/corpus.js";
import { DEFAULT_DOMAIN } from "../../support/catalog.js";
import { createScriptChain, SCRIPT_CHAIN_STEPS } from "../../support/deployable.js";
import { tokenized } from "../../support/run.js";
import { callChain } from "../../support/sessions.js";
import { releaseChains } from "../../support/cleanup.js";
import { notTheKnownDefect } from "../../support/known-defect.js";
import type { Catalog, EnginePodView } from "../../support/catalog.js";
import type { SeedChain } from "../../support/corpus.js";

/** An address in the private range no container on the Compose network answers on. */
const ABSENT_HOST = "10.255.255.1";

const ABSENT_DOMAIN = "no-such-domain";

/** A deployment id shaped like the real ones and belonging to nothing. */
const ABSENT_DEPLOYMENT = "00000000-0000-4000-8000-0000000000ff";

/** The one engine host of the one domain, which several cases below address by ip. */
async function theEngineHost(catalog: Catalog): Promise<string> {
  const hosts = await catalog.engineHosts();
  const ips = hosts[DEFAULT_DOMAIN] ?? [];
  expect(ips, `the ${DEFAULT_DOMAIN} domain registered no engine host`).not.toEqual([]);
  return ips[0];
}

test("the catalog serves the one classic domain, and names the engine host that answers for it", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog }) => {
  // One classic domain on both targets, whose id is its name: `EngineMapper.asDomainResponse`
  // answers the name as the id even where the chart's domain source sets the Deployment UID.
  const domains = await catalog.listDomains();
  const classic = domains.filter((each) => each.type === "CLASSIC");
  expect(classic.map((each) => each.name)).toEqual([DEFAULT_DOMAIN]);
  for (const domain of classic) {
    expect(domain).toMatchObject({ replicas: 1, type: "CLASSIC" });
    expect(domain.namespace, `${domain.name} reports no namespace`).toBeTruthy();
    expect(domain.id, `${domain.name}'s id is its name`).toBe(domain.name);
  }

  // The host keys of the classic domains: every key the catalog lists as a micro domain is set
  // aside, and what is left is exactly the classic list.
  const micro = new Set(domains.filter((each) => each.type === "MICRO").map((each) => each.name));
  const hosts = await catalog.engineHosts();
  expect(Object.keys(hosts).filter((key) => !micro.has(key))).toEqual([DEFAULT_DOMAIN]);
  expect(hosts[DEFAULT_DOMAIN], "no engine registered itself for the domain").toHaveLength(1);
  expect(hosts[DEFAULT_DOMAIN][0]).toMatch(/^\d+\.\d+\.\d+\.\d+$/);

  // The same address the runtime view reports each deployment against, which is what makes the two
  // readings one engine rather than two unrelated strings.
  const corpus = readCorpusState().chains;
  const rows = await catalog.runtimeDeploymentsOf(corpus[0].id);
  expect(rows.map((row) => row.host)).toEqual([hosts[DEFAULT_DOMAIN][0]]);
});

test("the host list is never empty, and no domain name is refused", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  const hosts = (await catalog.engineHosts())[DEFAULT_DOMAIN] ?? [];
  expect(hosts).not.toEqual([]);

  // A domain nothing knows about answers the same empty list rather than a 404, on both readings.
  // Its own case, so a target where the pod listing below is pinned still proves the endpoint.
  expect(await catalog.domainEngines(ABSENT_DOMAIN)).toEqual([]);
  expect(await catalog.deploymentsCount(ABSENT_DOMAIN)).toBe(0);
});

test("the pod listing follows the target's domain source", { tag: ["@catalog", "@tier2"] }, async ({ catalog, env }) => {
  // Not "the engine is missing" under Compose: the previous case pins that one is registered and
  // running the corpus. `DevModeDomainSource.getDomainPods` returns an empty list unconditionally,
  // because pods are a Kubernetes reading and there is no Kubernetes there. On a cluster,
  // `ClassicDomainSource` lists the engine pods, whose addresses are the hosts they registered.
  const hosts = (await catalog.engineHosts())[DEFAULT_DOMAIN] ?? [];

  // Where the catalog lists pods, the listing answers 400: the Kubernetes client refuses a pod
  // status field it does not know. docs/product-defects.md, "The catalog cannot list a domain's
  // engine pods on Kubernetes v1.36".
  const { listsEnginePods } = env.domainFacts();
  test.fail(listsEnginePods, "the catalog cannot read a pod status on Kubernetes v1.36");
  const listing = await catalog.raw("get", `/v1/catalog/domains/${DEFAULT_DOMAIN}/engines`);
  if (listing.status() !== 200) {
    const body = await listing.text();
    if (!listsEnginePods || listing.status() !== 400 || !body.includes("allocatedResources")) {
      notTheKnownDefect(`the pod listing answered ${listing.status()}: ${body.slice(0, 300)}`);
    }
  }
  expect(listing.status(), "the catalog lists the pods now: delete the test.fail() annotation").toBe(200);
  const pods = (await listing.json()) as EnginePodView[];
  expect(pods.map((each) => each.ip).sort()).toEqual(listsEnginePods ? [...hosts].sort() : []);
});

test("an engine pod reports the deployments it is running, and an address nothing answers on reports none", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog }) => {
  const host = await theEngineHost(catalog);
  const reported = await catalog.engineDeployments(DEFAULT_DOMAIN, host);
  const byChain = new Map(reported.map((row) => [row.chainId, row]));

  for (const chain of readCorpusState().chains) {
    const row = byChain.get(chain.id);
    expect(row, `${chain.fixture} is not among the deployments ${host} reports`).toBeDefined();
    expect(row).toMatchObject({
      chainId: chain.id,
      chainName: chain.name,
      state: { status: "DEPLOYED", suspended: false },
    });
    // The identity the catalog recorded, so a pod reporting a deployment the catalog never made
    // fails here rather than being read as agreement.
    const recorded = await catalog.listDeployments(chain.id);
    expect(row?.id).toBe(recorded[0].id);
    expect(row?.snapshotName).toBe(
      (await catalog.getSnapshot(chain.id, recorded[0].snapshotId, true)).name,
    );
  }

  // The host is a lookup key into what the engines reported, not a request to that address, so an
  // address nothing registered is an empty answer rather than a connection failure.
  expect(await catalog.engineDeployments(DEFAULT_DOMAIN, ABSENT_HOST)).toEqual([]);
});

test("the domain's deployment count follows its rows, one deployment at a time", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const before = await catalog.deploymentsCount(DEFAULT_DOMAIN);
  expect(before, "the corpus is deployed, so the domain cannot hold zero deployments").toBeGreaterThan(0);

  // A chain of this case's own, deployed and undeployed again. The delta is readable only because
  // this project runs one worker after every other one: the count is the domain's, not the chain's.
  const name = tokenized(run, "engines-count");
  const chain = await catalog.createChain(name, folder.id);
  const trigger = await catalog.createElement(chain.id, "http-trigger");
  await catalog.patchElementProperties(chain.id, trigger.id, {
    contextPath: name,
    httpMethodRestrict: "POST",
    externalRoute: false,
  });
  const snapshot = await catalog.createSnapshot(chain.id);
  const deployment = await catalog.deploy(chain.id, snapshot.id);

  expect(await catalog.deploymentsCount(DEFAULT_DOMAIN)).toBe(before + 1);

  await catalog.undeploy(chain.id, deployment.id);
  expect(await catalog.deploymentsCount(DEFAULT_DOMAIN)).toBe(before);
});

test("the update feed hands the engine every deployment it does not already have", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog }) => {
  const corpus = readCorpusState().chains;
  const mine = corpus[0];
  const deployment = (await catalog.listDeployments(mine.id))[0];

  // The full form: an empty exclude list asks for everything, and everything includes the corpus.
  // This is also the call that fills `fullDeploymentsUpdateCache` — see the header.
  const full = await catalog.deploymentsUpdate(DEFAULT_DOMAIN);
  expect(full.stop, "a full request asks the engine to stop nothing").toEqual([]);
  const listed = full.update.find(
    (each) => each.deploymentInfo.deploymentId === deployment.id,
  );
  expect(listed, `${mine.fixture} is not in the full update the engine would receive`).toBeDefined();
  expect(listed?.deploymentInfo).toMatchObject({
    chainId: mine.id,
    chainName: mine.name,
    snapshotId: deployment.snapshotId,
  });
  // The payload is the compiled route, which is the whole point of the feed: a descriptor with no
  // XML behind it would deploy nothing on the engine.
  expect(listed?.configuration?.xml, "the update carries no compiled route").toContain("<routes");

  // The delta form: a deployment the caller says it already has is not sent again.
  const delta = await catalog.deploymentsUpdate(DEFAULT_DOMAIN, [{ deploymentId: deployment.id }]);
  expect(
    delta.update.map((each) => each.deploymentInfo.deploymentId),
    "the delta re-sent a deployment the caller said it was already running",
  ).not.toContain(deployment.id);

  // And an id the caller claims to be running that the catalog no longer has comes back as a stop,
  // which is how an undeployed chain is taken off an engine.
  expect(delta.stop.map((each) => each.deploymentInfo.deploymentId)).toEqual([]);
  const stopping = await catalog.deploymentsUpdate(DEFAULT_DOMAIN, [
    { deploymentId: deployment.id },
    { deploymentId: ABSENT_DEPLOYMENT },
  ]);
  expect(stopping.stop.map((each) => each.deploymentInfo.deploymentId)).toEqual([ABSENT_DEPLOYMENT]);
});

test("the engine keeps a failed session for retry, answers it by id, and lists none without one", { tag: ["@engine", "@tier1"] }, async ({ catalog, engine, env, folder, request, run, sessions }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  // A chain of this case's own rather than the corpus checkpoint fixture, and the reason is the
  // measured one in the header: a checkpoint session that completes normally is **deleted** by
  // `finishCheckpointSession`, so the corpus chain — which always succeeds — leaves no row. The
  // terminal script throws for exactly that reason.
  //
  // It is released through `support/cleanup.ts` like every other chain a spec builds and
  // deploys. The logging-properties write below is a Consul key filed under the chain id, and
  // neither the worker folder's cascade nor the run-token sweep reaches one: without this the case
  // would orphan a key on every run of the suite.
  const built: SeedChain[] = [];
  let bodyFailed = true;
  try {
    // `between` rather than a prologue of this file's own: the catalog fills `checkpointElementId`
    // and the generated retry `contextPath` on create, so a checkpoint element needs no properties
    // written and the builder's own wiring is the whole difference. Going through the builder is
    // also what puts this chain in `Diagnostic.chains`, and it is the one case in the suite that
    // deploys a script written to throw — so the per-chain `engine.log` section is the evidence
    // this case goes red without.
    const chain = await createScriptChain(
      catalog,
      run,
      {
        what: "engine-checkpoint",
        parentId: folder.id,
        between: "checkpoint",
        script: 'throw new RuntimeException("e2e engine-sessions failure")',
        scriptName: "Boom",
        logging: { sessionsLoggingLevel: "DEBUG" },
      },
      built,
    );
    const name = chain.name;
    const checkpointId = chain.elements.checkpoint;

    const snapshot = await catalog.createSnapshot(chain.id);
    await catalog.deploy(chain.id, snapshot.id);
    // The route, not the status: `DEPLOYED` is the catalog's record of having dispatched the
    // deployment, and the trigger restricts POST, so a live route answers 405 to a GET and a route
    // that is not there answers 404.
    await expect
      .poll(
        async () =>
          await fetch(env.chainUrl(name), { method: "GET" })
            .then((response) => response.status)
            .catch(() => 0),
        { timeout: DEPLOY_TIMEOUT, message: `the route for ${name} never began to answer` },
      )
      .toBe(405);

    const failing = await callChain(request, env.chainUrl(name), { data: {} });
    expect(failing.response.status()).toBe(500);
    // The builder's own step count rather than one more for the checkpoint: what bounds this
    // lookup is the settled `executionStatus` the poll also waits for, and the script throws last.
    const failed = await sessions.byExternalId(failing.token, { elements: SCRIPT_CHAIN_STEPS });
    expect(failed.executionStatus).toBe("COMPLETED_WITH_ERRORS");

    // The lookup, by the id the recorded session carries. The row is the engine's own copy, kept
    // because the session can be retried from the checkpoint it passed.
    const kept = await engine.checkpointSessions([failed.id]);
    expect(kept.map((each) => each.id)).toEqual([failed.id]);
    expect(kept[0]).toMatchObject({
      chainId: chain.id,
      chainName: name,
      executionStatus: "COMPLETED_WITH_ERRORS",
      loggingLevel: "DEBUG",
      snapshotName: snapshot.name,
    });
    expect(kept[0].checkpoints.map((each) => each.checkpointElementId)).toEqual([checkpointId]);

    // And it is a lookup rather than a listing, which is the assertion the row above makes provable:
    // the engine holds this session and still answers an empty list when no id is named.
    // `findSessions` is `sessionInfoRepository.findAllById(ids)`, and `ids` defaults to empty.
    expect(await engine.checkpointSessions()).toEqual([]);
    expect(await engine.checkpointSessions(["no-such-session"])).toEqual([]);

    bodyFailed = false;
  } finally {
    await releaseChains(catalog, built, bodyFailed);
  }
});
