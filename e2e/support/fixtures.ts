/**
 * The entities a run creates, and the two mechanisms that remove them again.
 *
 * Data splits two ways because one Postgres serves every worker. Everything a spec creates goes
 * inside a per-worker folder, and deleting the folder cascades — chains go with it, and with them
 * everything chain-scoped, a masked field included. That makes teardown one call.
 *
 * What the cascade does **not** reach is anything with no folder. Measured: an environment answered
 * 200 after the folder holding its chain was gone, because environments hang off a system
 * (`/v1/systems/{id}/environments`) and systems have no folder; it vanished only with
 * `DELETE /v1/systems/{id}`. So entities of that kind carry the run token in their name and a sweep
 * removes them by that name: services, specification groups, environments through their system,
 * context and MCP services, and common and secured variables. A secured variable is the one entity
 * a name alone does not address — it lives in a secret, and the delete takes that secret in the
 * path — so its row carries the secret as its `scope`.
 *
 * The sweep runs **once, after every worker has finished**, rather than in each worker's teardown.
 * It matches on the run token, which every worker shares, so a worker sweeping mid-run would delete
 * another worker's system out from under it. Playwright's `globalTeardown` is the one place with
 * that guarantee, and this module's default export is it.
 *
 * A chain's logging properties are outside the cascade too, and outside Postgres: they are a Consul
 * key, and `ChainRuntimePropertiesService.deleteCustomRuntimeProperties` has exactly one caller —
 * the controller. Deleting the chain does not delete them, so anything that wrote them deletes them
 * itself, and a chain that outlived its folder has its key dropped here before the chain goes. What
 * this cannot see is a key under a chain that is already gone: it is addressed by chain id, the id
 * carries no run token, and the catalog exposes no listing of the keys. Those are unreachable
 * through the API and are reported as a product defect rather than swept.
 *
 * None of it deletes by prefix, because no endpoint in this API takes one: services and
 * specification groups delete by id, so the sweep lists and filters client-side, and common
 * variables take `DELETE /v1/common-variables?variablesNames=a,b`, a batch by exact name.
 *
 * Two services hold residue the catalog cannot see at all, and `OUTSIDE_CATALOG` below is how the
 * sweep reaches them. The corpus deploys at `sessionsLoggingLevel: "DEBUG"`, so every call a runtime
 * spec makes is recorded in OpenSearch as a session with its element trace — roughly a hundred
 * documents a run — and deleting the chain does not delete them. An endpoint mock is the other:
 * it is keyed on `(chainId, elementId)` out of a fixture that freezes both, so a mock left behind
 * goes on intercepting that sender on **every** later run, and the failure surfaces in a run that
 * did nothing wrong.
 */
import { test as base, request as playwrightRequest } from "@playwright/test";
import { Catalog, type Named, type Residue } from "./catalog.js";
import { mocksOfRun, removeMock, TestingService, type EndpointMock } from "./testing-service.js";
import { serviceUrl } from "../env/containers.js";
import {
  carriesRunToken,
  forgetRun,
  runToken,
  sweepableRuns,
  workerFolderName,
  type RunRecord,
} from "./run.js";
import { attachDiagnostics, beginDiagnostics } from "./diagnostics.js";
import { Sessions } from "./sessions.js";
import { Engine } from "./engine.js";
import type { EngineKind, Env } from "../env/index.js";
import { targetSetup } from "../env/target-setup.js";
import { RESOURCES_FILE, stopResourceSampler } from "../env/resources.js";
import { recordingRequest } from "../registry/reached.js";

/** `E2E_KEEP=1` leaves everything behind and prints it, for a failure worth looking at by hand. */
export function keepEntities(): boolean {
  return process.env.E2E_KEEP === "1";
}

export interface WorkerFolder {
  id: string;
  name: string;
}

interface TestFixtures {
  /**
   * The per-test record a failed spec attaches: the engine's lines for the chain it addressed, and
   * the session it found. `auto`, because a spec that has to remember to ask for its own
   * diagnostics is a spec that fails without them on the day they matter.
   */
  diagnostics: void;
  /**
   * The session lookup, over the same `request` context the spec's own calls go through.
   *
   * Per test rather than per worker, which is the opposite of `catalog` and for a reason: a lookup
   * costs nothing to construct, and sharing the test's context is what keeps the lookups in the
   * trace a failed test retains.
   */
  sessions: Sessions;
}

interface WorkerFixtures {
  /** The token every worker shares. Minted in globalSetup and inherited through the environment. */
  run: string;
  /**
   * The engine this project's chains run on, set per project with `use: { engineKind }`.
   *
   * Worker-scoped, because the worker-scoped `env` depends on it.
   */
  engineKind: EngineKind;
  /**
   * The thing that runs the platform, behind the `Env` seam.
   *
   * A fixture rather than a `new ComposeEnv()` in every spec, and that is what the seam is for: a
   * spec that names the implementation has decided the target, and pointing the suite at a cluster
   * would then mean editing every file that made the decision. `targetSetup()` creates it for the
   * target `CIP_TARGET` names.
   */
  env: Env;
  /** One catalog client per worker, on the catalog's own port. */
  catalog: Catalog;
  /**
   * The engine's own REST surface, on its own port.
   *
   * A transport rather than a `request.fetch` in the spec, for the reason `support/engine.ts`
   * states: `noteReached()` fires inside a transport and nowhere else, so an engine call made any
   * other way leaves its operation registry row unverifiable in both directions.
   */
  engine: Engine;
  /**
   * The testing service's own surface, on its own port.
   *
   * The fourth transport, and a fixture for the same reason as the other three: the rulebook and
   * the operation registry treat the four as one category, and a spec that constructs one by hand
   * decides for itself which request context the call goes out on.
   */
  testingService: TestingService;
  /** `e2e-{run}-w{index}`, created before the worker's first test and deleted after its last. */
  folder: WorkerFolder;
}

/**
 * The suite's `test`, with the run token, the environment, a catalog client, a session lookup, a
 * per-worker folder, and the diagnostics a failed test attaches.
 *
 * The token, the environment, the client and the folder are worker-scoped: a folder per test would
 * cost a create and a delete per case for an isolation the token already provides, and the folder
 * is what makes teardown one call. Diagnostics and the session lookup are per test, because a
 * failure is and because the lookup rides the test's own request context.
 */
export const test = base.extend<TestFixtures, WorkerFixtures>({
  // Playwright's own fixture, wrapped so a call it makes on a service's own port records its
  // registry row. `registry/reached.ts` says why the recording is here rather than in each caller:
  // a spec that reaches around a transport still reaches the endpoint, and three review passes in a
  // row found a `not-reached` row a raw call had been reaching all along.
  request: async ({ request }, use) => {
    await use(recordingRequest(request));
  },

  diagnostics: [
    async ({ env, engineKind }, use, testInfo) => {
      beginDiagnostics();
      await use();
      // After the test body and before Playwright decides the run is over, which is the only point
      // where both the outcome and the window the test ran in are known.
      await attachDiagnostics(testInfo, env, engineKind);
    },
    { auto: true },
  ],

  sessions: async ({ request }, use) => {
    await use(new Sessions(request));
  },

  run: [
    // eslint-disable-next-line no-empty-pattern
    async ({}, use) => {
      await use(runToken());
    },
    { scope: "worker" },
  ],

  engineKind: ["classic", { option: true, scope: "worker" }],

  env: [
    async ({ engineKind }, use) => {
      await use(targetSetup().createEnv(engineKind));
    },
    { scope: "worker" },
  ],

  catalog: [
    async ({ playwright }, use) => {
      const api = await playwright.request.newContext();
      await use(new Catalog(api));
      await api.dispose();
    },
    { scope: "worker" },
  ],

  engine: [
    async ({ playwright }, use) => {
      const api = await playwright.request.newContext();
      await use(new Engine(api));
      await api.dispose();
    },
    { scope: "worker" },
  ],

  testingService: [
    async ({ playwright, env }, use) => {
      const api = await playwright.request.newContext();
      await use(new TestingService(api, env));
      await api.dispose();
    },
    { scope: "worker" },
  ],

  folder: [
    async ({ catalog, run }, use, workerInfo) => {
      const name = workerFolderName(run, workerInfo.workerIndex);
      const created = await catalog.createFolder(name);
      await use({ id: created.id, name });
      if (keepEntities()) {
        console.log(`[keep] folder ${name} (${created.id}) left behind by E2E_KEEP=1`);
        return;
      }
      await catalog.deleteFolder(created.id).catch((cause: unknown) => {
        console.error(`[teardown] folder ${name} was not deleted: ${String(cause)}`);
      });
    },
    { scope: "worker", auto: true },
  ],
});

export { expect } from "@playwright/test";

// ---------------------------------------------------------------------------
// The residue the catalog cannot see
// ---------------------------------------------------------------------------

/**
 * How many sessions one page of the search asks for.
 *
 * The parameter is `count`, and naming it anything else buys the default 20:
 * `SessionController.findAllByFilter` declares
 * `@RequestParam(required = false, defaultValue = "20") int count` (`SessionController.java:105`),
 * and Spring drops a request parameter it has no name for without saying so. A reading that asked
 * for a thousand under the wrong name was served twenty and answered as if that were the whole of
 * it.
 *
 * A thousand covers a run in one page — the order of a hundred sessions through fifteen or so
 * chains — and the service serves what it is asked for: `SessionService.getSessions` caps nothing
 * of its own, and the ceiling is OpenSearch's `max_result_window`, 10000 over `from + size`. So the
 * loop below is not for the size of a run. It is so that the size of a run stops being something
 * this reading assumes.
 */
const SESSION_PAGE = 1000;

/**
 * The chains this run recorded a session for, one row each.
 *
 * `chainName` is the only field of a session that carries the run token — the fixture documents
 * freeze their chain ids, so every run's sessions pile up under the same ids — and it is a
 * `Keyword` field on the index (`SessionElementElastic.java:61`), so the `CONTAINS` wildcard matches
 * the whole name rather than an analyzed token of it. The rows come back one per session; the sweep
 * deletes by chain, so they are reduced to the chains here and the count belongs in the log rather
 * than in the residue list.
 *
 * `fetch` rather than the `Sessions` transport: this runs in `globalSetup` and `globalTeardown`,
 * where there is no spec to take a request context from, and `waitForRoutes` in `support/corpus.ts`
 * reaches the stack the same way for the same reason.
 *
 * Read to the end of the listing rather than to the end of one page. The answer carries no total:
 * `SessionSearchResponse` holds the rows and an `offset` that comes back as `offset + rows`
 * (`SessionService.java:220`), a marker for the next request rather than an echo of this one, so a
 * full page and a last one look alike and an empty page is the only ending on the wire. Advancing
 * by what the page held computes that same marker locally, which is what `mocksOfRun` in
 * `support/testing-service.ts` does against the analogous listing.
 *
 * What a short reading costs is silent, which is why this loops rather than trusting headroom: the
 * sweep would delete the chains it saw, report success, and `forgetRun` would then drop the
 * manifest entry that is the only handle on the sessions it never read.
 */
export async function sessionsOfRun(
  run: string,
  base: string = serviceUrl("sessions-management"),
): Promise<Named[]> {
  const search = `${base}/v1/sessions?count=${SESSION_PAGE}`;
  const byChain = new Map<string, string>();
  let offset = 0;
  for (;;) {
    const url = `${search}&offset=${offset}`;
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        filterRequestList: [{ feature: "CHAIN_NAME", condition: "CONTAINS", value: `e2e-${run}` }],
      }),
    });
    if (!response.ok) {
      throw new Error(`POST ${url} answered ${response.status}: ${await response.text()}`);
    }
    const page = ((await response.json()) as { sessions?: Named[] | null }).sessions ?? [];
    if (page.length === 0) return [...byChain].map(([id, name]) => ({ id, name }));
    for (const session of page as Array<{ chainId?: string; chainName?: string }>) {
      if (typeof session.chainId !== "string") continue;
      if (!carriesRunToken(session.chainName, run)) continue;
      byChain.set(session.chainId, session.chainName as string);
    }
    offset += page.length;
  }
}

/**
 * Drops every session of the named chains, in one call.
 *
 * `DELETE /v1/sessions/chains?chainIds=a,b` deletes by query with `refresh=true`
 * (`SessionService.deleteAllByChainIds`), so the residue check that follows reads the index after
 * the delete rather than before it. One call covers the set, which is why the sweep marks the rows
 * together: the endpoint answers for all of them or for none.
 */
export async function deleteSessionsOfChains(
  chainIds: readonly string[],
  base: string = serviceUrl("sessions-management"),
): Promise<void> {
  const url = `${base}/v1/sessions/chains?chainIds=${chainIds.map(encodeURIComponent).join(",")}`;
  const response = await fetch(url, { method: "DELETE" });
  if (!response.ok) {
    throw new Error(`DELETE ${url} answered ${response.status}: ${await response.text()}`);
  }
}

/**
 * The two services the catalog client cannot answer for, as the sweep addresses them.
 *
 * Injected rather than reached for, and **optional**, because `specs/schema/sweep.spec.ts` runs the
 * sweep against a stub catalog in a project defined as needing no stack: a client built at the
 * point of use would put two HTTP calls into a run that has nothing to answer them. The two live
 * entry points — the start-of-run sweep and `globalTeardown` — pass `OUTSIDE_CATALOG`, and they are
 * the only callers that face a stack.
 */
export interface OutsideCatalog {
  /** Sessions-management: the chains a run recorded, and the delete that clears them. */
  sessions?: {
    of: (run: string) => Promise<Named[]>;
    remove: (chainIds: readonly string[]) => Promise<void>;
  };
  /** The testing service: the endpoint mocks a run named, and the delete for one of them. */
  mocks?: {
    of: (run: string) => Promise<EndpointMock[]>;
    remove: (id: string) => Promise<void>;
  };
}

/** The live pair, on the two services' own ports. */
export const OUTSIDE_CATALOG: OutsideCatalog = {
  sessions: { of: sessionsOfRun, remove: deleteSessionsOfChains },
  mocks: { of: mocksOfRun, remove: removeMock },
};

/**
 * Everything carrying the run token that the folder cascade does not remove, plus any folder or
 * chain that outlived it.
 *
 * A non-empty result is residue, and residue is what a suite leaves on a shared stack for the next
 * run to trip over.
 */
export async function findResidue(
  catalog: Catalog,
  run: string,
  outside: OutsideCatalog = {},
): Promise<Residue[]> {
  const found: Residue[] = [];

  for (const item of await catalog.listRootItems()) {
    if (carriesRunToken(item.name, run)) {
      found.push({ kind: item.itemType === "FOLDER" ? "folder" : "chain", id: item.id, name: item.name });
    }
  }
  for (const chain of await catalog.listChains()) {
    if (carriesRunToken(chain.name, run)) found.push({ kind: "chain", id: chain.id, name: chain.name });
  }
  // A micro domain is a camel-k Integration that `seed-micro` named after the run. The catalog lists
  // it once the operator has created its Deployment.
  for (const domain of await catalog.listDomains()) {
    if (domain.type === "MICRO" && carriesRunToken(domain.name, run)) {
      found.push({ kind: "micro-domain", id: domain.name, name: domain.name });
    }
  }
  // A chain's custom logging properties are a Consul key, and deleting the chain leaves it behind,
  // so a chain that is residue may be holding one. Only `custom` counts: `fallbackDefault` is the
  // compiled-in default and is always there. The read is served from a cache the catalog refills on
  // a 1 s tick, so a key written in the last second reads as absent. A read that throws is the same
  // case seen from the other side: it says nothing about whether a key is there. Neither costs more
  // than a report, because the sweep deletes the key of every chain it is about to delete rather
  // than only of the ones reported here.
  for (const chain of found.filter((each) => each.kind === "chain")) {
    const properties = await catalog.getLoggingProperties(chain.id).catch(() => null);
    if (properties?.custom) {
      found.push({ kind: "logging-properties", id: chain.id, name: chain.name });
    }
  }
  for (const system of await catalog.listSystems()) {
    if (!carriesRunToken(system.name, run)) continue;
    found.push({ kind: "service", id: system.id, name: system.name });
    // A parallel worker may delete its own system between the listing and these two calls. That is
    // not a failure to report residue, so the system is simply no longer residue.
    for (const group of await catalog.listSpecificationGroups(system.id).catch(() => [])) {
      found.push({ kind: "specification-group", id: group.id, name: group.name });
    }
    // `scope` is the system, because that is what an environment is deleted through: the sweep
    // never deletes one directly, and whether it went depends on whether the system's delete did.
    for (const environment of await catalog.listEnvironments(system.id).catch(() => [])) {
      found.push({
        kind: "environment",
        id: environment.id,
        name: environment.name,
        scope: system.id,
      });
    }
  }
  // Context and MCP services are their own entity families, not `IntegrationSystem` rows, so
  // `GET /v1/systems` does not list them and the systems sweep above never sees them.
  for (const context of await catalog.listContextSystems()) {
    if (carriesRunToken(context.name, run)) {
      found.push({ kind: "context-system", id: context.id, name: context.name });
    }
  }
  for (const mcp of await catalog.listMcpSystems()) {
    if (carriesRunToken(mcp.name, run)) found.push({ kind: "mcp-system", id: mcp.id, name: mcp.name });
  }
  for (const name of Object.keys(await catalog.listCommonVariables())) {
    if (carriesRunToken(name, run)) found.push({ kind: "common-variable", id: name, name });
  }
  // A detailed-design template belongs to no folder and to no chain, so nothing cascades to it, and
  // it has a delete of its own — which is the line between residue that is exempted and residue
  // that is a gap. The built-in rows are skipped by the token test alone: their names are compiled
  // in, and `DELETE /templates` would answer 204 over one without removing anything.
  for (const template of await catalog.listDesignTemplates(false)) {
    if (carriesRunToken(template.name, run)) {
      found.push({ kind: "design-template", id: template.id, name: template.name });
    }
  }
  // Read from `/v2`: the `/v1` surface answers 410 while the default secret is disabled, so a
  // sweep built on it reports an empty stack whatever is actually on it. A secured variable is
  // addressed by its secret, which is what `scope` carries.
  for (const each of await catalog.listSecuredVariables()) {
    if (carriesRunToken(each.name, run)) {
      found.push({
        kind: "secured-variable",
        id: `${each.secret}/${each.name}`,
        name: each.name,
        scope: each.secret,
      });
    }
  }

  // One row per chain that recorded a session, not per session: the delete is by chain, and a
  // hundred rows naming the same fifteen chains is a report nobody reads. The chain itself is
  // usually gone by now — its sessions outlive it, which is the whole reason this is read here
  // rather than derived from the chains above.
  for (const chain of (await outside.sessions?.of(run)) ?? []) {
    found.push({ kind: "sessions", id: chain.id, name: chain.name });
  }
  // A mock is named by this run and keyed on ids a fixture freezes, so a survivor is the one piece
  // of residue that reddens a later run rather than merely occupying the stack.
  for (const mock of (await outside.mocks?.of(run)) ?? []) {
    found.push({ kind: "endpoint-mock", id: mock.id, name: mock.name });
  }

  // A chain can be reported twice: once as a root item and once by the chain listing.
  const seen = new Set<string>();
  return found.filter((each) => {
    const key = `${each.kind}:${each.id}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/**
 * Secrets carrying the run token — the one residue this suite is allowed to create and cannot
 * remove, and therefore the one it is not allowed to create at all.
 *
 * `SecretControllerV2` is `POST /v2/secret/{name}` and `GET /v2/secret/template/{name}`, and
 * `SecretService` declares no delete. So the exemption in the residue rules is not "the sweep
 * skips secrets"; it is **"a run never names one after itself"** — `specs/api/secret-name.spec.ts`
 * posts a single committed fixture name, so the leak is one object over the repository's lifetime
 * rather than one per run. This function is what makes that an assertion instead of a comment:
 * `specs/api/v2-v3-controllers.spec.ts` fails on a non-empty answer, and `globalTeardown` prints
 * it, since a run that is over can no longer be turned red.
 *
 * Deliberately not part of `findResidue`. That list is what `sweepRunToken` deletes and what
 * `sweptClean` counts, and a row nothing can delete would keep every run in the manifest for ever.
 *
 * The reading is `GET /v2/secured-variables`, which lists **every** secret the store holds, a
 * secret with no variables in it included — measured, a bare `POST /v2/secret/{name}` appears there
 * the moment it is created.
 */
export async function secretsCarryingRunToken(catalog: Catalog, run: string): Promise<string[]> {
  const secrets = await catalog.listSecrets();
  return secrets.map((each) => each.secretName).filter((name) => carriesRunToken(name, run));
}

/** What one sweep found, what it actually removed, and what it could not. */
export interface SweepOutcome {
  /** What the sweep found and tried to remove. */
  residue: Residue[];
  /**
   * The rows that are actually gone.
   *
   * Not the same list as `residue`, and the difference is what makes the count reportable. A row
   * whose delete failed is still on the stack, and an environment is never deleted on its own — it
   * goes with its system, so it counts as removed only if that system's delete went through.
   */
  removed: Residue[];
  /** One line per delete that failed, naming the entity and quoting the catalog. */
  failures: string[];
}

/**
 * Whether the run may be dropped from the manifest: nothing failed, and nothing was left behind.
 *
 * Two readings rather than one, because they are taken differently. `failures` is what the sweep
 * writes as it goes; `removed` counts only the rows whose own delete answered for them. A row that
 * is neither removed nor reported is the residue the manifest entry exists to name, so both have to
 * agree before the name is thrown away.
 */
export function sweptClean(outcome: SweepOutcome): boolean {
  return outcome.failures.length === 0 && outcome.removed.length === outcome.residue.length;
}

/**
 * Removes what `findResidue` finds, and answers with all three parts of the result.
 *
 * A failed delete is reported rather than swallowed. Reporting "removed" over a delete that did not
 * happen is worse than reporting nothing: the caller then drops the manifest entry, and the residue
 * loses the only thing that would have let a later run collect it.
 *
 * The manifest entry is not enough on its own, because the *next* sweep has to be able to find the
 * residue again. One thing a chain owns lives outside Postgres and is addressed by chain id — its
 * logging properties, a Consul key — and `findResidue` reports it only for a chain that still
 * exists. So a chain whose logging-properties delete failed is **kept**: deleting it would leave the
 * key under an id nothing can list, and the sweep after it would find nothing, report success and
 * forget the run. A folder holding such a chain is kept with it, since deleting a folder cascades to
 * the chains under it.
 *
 * A failed **undeploy** is not in that class and keeps nothing. The chain delete undeploys on its
 * own — `ChainService.deleteByIdIfExists` calls `deploymentService.deleteAllByChainId` before it
 * touches the row (`ChainService.java:190`), and `FolderService.deleteRuntimeDeployments` does it
 * down a folder tree (`FolderService.java:283`) — so keeping the chain would block the one call
 * that removes the route. On a stack with the classic domain disabled it would also never stop:
 * `verifyClassicDomainEnabled` throws for every undeploy (`DeploymentController.java:148`), so
 * every chain of every run would be kept and the manifest would grow without bound.
 */
export async function sweepRunToken(
  catalog: Catalog,
  run: string,
  outside: OutsideCatalog = {},
): Promise<SweepOutcome> {
  const residue = await findResidue(catalog, run, outside);
  const of = (kind: string) => residue.filter((each) => each.kind === kind);
  const failures: string[] = [];
  // What is actually gone, so the count the caller prints is the count that happened. A delete
  // covering several rows marks them all or none, which is what the two variables endpoints do:
  // each hands the whole set to one service call. `POST /v1/chains/bulk-delete` does not — it
  // catches per id and answers 204 regardless — so chains go one at a time below.
  const gone = new Set<Residue>();
  const removeAll = async (
    what: string,
    rows: readonly Residue[],
    drop: Promise<unknown>,
  ): Promise<void> => {
    await drop.then(
      () => {
        for (const row of rows) gone.add(row);
      },
      (cause: unknown) => {
        failures.push(`${what}: ${String(cause)}`);
      },
    );
  };
  const remove = (what: string, row: Residue, drop: Promise<unknown>): Promise<void> =>
    removeAll(what, [row], drop);
  // Chain ids whose Consul-side cleanup did not go through, and which are therefore not deleted.
  const kept = new Set<string>();

  // The mock first, and before anything else: while it stands it answers in place of a real
  // endpoint for a sender addressed by ids no run varies, so it is the one row here that can make a
  // later run fail. Nothing else in the sweep depends on it having gone.
  const mocks = outside.mocks;
  if (mocks) {
    for (const mock of of("endpoint-mock")) {
      await remove(`endpoint-mock ${mock.name}`, mock, mocks.remove(mock.id));
    }
  }
  // One call for every chain that recorded anything. `removeAll` rather than `remove` because the
  // endpoint answers for the whole set: it deletes by query, so the rows go together or not at all.
  const sessions = outside.sessions;
  const recorded = of("sessions");
  if (sessions && recorded.length) {
    await removeAll(
      `sessions of ${recorded.map((each) => each.name).join(", ")}`,
      recorded,
      sessions.remove(recorded.map((each) => each.id)),
    );
  }

  // A micro domain runs its own build of the chains, so deleting the chains does not remove it.
  for (const domain of of("micro-domain")) {
    await remove(`micro-domain ${domain.name}`, domain, catalog.deleteCustomResource(domain.name));
  }

  // A chain left behind by an interrupted run is still deployed, and the delete is cleaner once
  // the route is gone. Normally there are no chains here at all, so this costs a run nothing.
  // A failure is logged and nothing more: the delete below undeploys the chain itself, so this
  // leaves no residue to report and must not stop anything (see the note on the docstring).
  for (const chain of of("chain")) {
    await catalog.undeployAll(chain.id).catch((cause: unknown) => {
      console.error(
        `[sweep] chain ${chain.name} was not undeployed before its delete, which undeploys it ` +
          `anyway: ${String(cause)}`,
      );
    });
  }
  // Specification groups before their system, so a group that outlives one is still named.
  for (const group of of("specification-group")) {
    await remove(
      `specification-group ${group.name}`,
      group,
      catalog.deleteSpecificationGroup(group.id),
    );
  }
  // The system takes its environments with it; nothing else does.
  for (const system of of("service")) {
    await remove(`service ${system.name}`, system, catalog.deleteSystem(system.id));
    // The environments went exactly as far as the system did.
    if (gone.has(system)) {
      for (const environment of of("environment")) {
        if (environment.scope === system.id) gone.add(environment);
      }
    }
  }
  for (const context of of("context-system")) {
    await remove(`context-system ${context.name}`, context, catalog.deleteContextSystem(context.id));
  }
  for (const mcp of of("mcp-system")) {
    await remove(`mcp-system ${mcp.name}`, mcp, catalog.deleteMcpSystem(mcp.id));
  }
  // Before the chains, because a chain's logging properties are a Consul key the chain delete does
  // not reach: once the chain is gone the key is addressed by an id nothing can list. A failure
  // here is what keeps that chain for the next sweep to try again.
  //
  // Every chain, rather than the ones `findResidue` reported a key for. That report comes from a
  // read, the read can fail, and a failed read is not "this chain has no key": taking it for one
  // lets a transient error authorize the chain delete below over a key nothing could then find. The
  // delete is idempotent (204, and 204 again), so a chain with no custom layer costs one call.
  // `reported` is the row the outcome is counted against, and there is none when the read said no
  // key or could not answer.
  const reportedKeys = new Map(of("logging-properties").map((each) => [each.id, each]));
  for (const chain of of("chain")) {
    const reported = reportedKeys.get(chain.id) ?? { ...chain, kind: "logging-properties" };
    await remove(
      `logging properties of chain ${chain.name} were not deleted, so the chain was kept`,
      reported,
      catalog.deleteLoggingProperties(chain.id),
    );
    if (!gone.has(reported)) kept.add(chain.id);
  }
  for (const folder of of("folder")) {
    // Listed only when there is a chain to protect from the cascade, and normally there is none.
    const why = kept.size
      ? await catalog.listNestedChains(folder.id).then(
          (chains) => {
            const held = chains.filter((chain) => kept.has(chain.id)).map((each) => each.name);
            return held.length ? `it holds ${held.join(", ")}, whose cleanup failed` : null;
          },
          // A listing that failed is not an empty folder. Deleting on the strength of one cascades
          // away the very chain `kept` is holding back, which is the failure this guard exists for.
          (cause: unknown) => `its chains could not be listed, so it may hold one: ${String(cause)}`,
        )
      : null;
    if (why !== null) {
      failures.push(`folder ${folder.name} was kept: ${why}`);
      continue;
    }
    await remove(`folder ${folder.name}`, folder, catalog.deleteFolder(folder.id));
  }
  // One call per chain rather than `POST /v1/chains/bulk-delete`. That endpoint deletes each id
  // inside `try { … } catch (Exception e) { log.error(…) }` and answers 204 whatever happened
  // (`ChainController.java:273`), so a chain it could not delete would count as removed, the run
  // would be forgotten as clean, and the residue would lose the only name it had left. `DELETE
  // /v1/chains/{id}` answers for the one chain it was given.
  //
  // A chain inside a run's folder is listed by `GET /v1/chains` too, so the folder delete above has
  // already removed it and its own delete answers 404. A chain the catalog no longer holds is swept.
  for (const chain of of("chain")) {
    if (kept.has(chain.id)) continue;
    const drop = catalog.deleteChain(chain.id).catch(async (cause: unknown) => {
      if (await catalog.holds(`/v1/chains/${chain.id}`)) throw cause;
    });
    await remove(`chain ${chain.name}`, chain, drop);
  }
  const templates = of("design-template");
  if (templates.length) {
    await removeAll(
      `design-templates ${templates.map((each) => each.name).join(", ")}`,
      templates,
      catalog.deleteDesignTemplates(templates.map((each) => each.id)),
    );
  }
  const variables = of("common-variable");
  if (variables.length) {
    await removeAll(
      `common-variables ${variables.map((each) => each.name).join(", ")}`,
      variables,
      catalog.deleteCommonVariables(variables.map((each) => each.name)),
    );
  }
  const secured = of("secured-variable").filter((each) => each.scope !== undefined);
  if (secured.length) {
    await removeAll(
      `secured-variables ${secured.map((each) => each.id).join(", ")}`,
      secured,
      catalog.deleteSecuredVariables(
        secured.map((each) => ({ secret: each.scope as string, name: each.name })),
      ),
    );
  }

  return { residue, removed: residue.filter((each) => gone.has(each)), failures };
}

/** What a start-of-run sweep found and removed, per previous run. */
export interface PreviousRunSweep {
  run: string;
  /** When that run started, out of the manifest: how old the residue is decides whether to care. */
  startedAt: string;
  /** Everything the run left behind that the sweep could see. */
  found: Residue[];
  /** The subset that is actually gone. A failed delete is in `found` and not here. */
  removed: Residue[];
  /** The deletes that failed. A non-empty list means the entry stayed in the manifest. */
  failures: string[];
  /**
   * Whether the manifest entry was dropped.
   *
   * The reading the caller needs, rather than one it re-derives: the corpus state a run left behind
   * is the handle on what is still deployed, and it may only be discarded for a run whose entities
   * are gone.
   */
  forgotten: boolean;
}

/**
 * Removes what a *previous* run left on the stack, before this one starts.
 *
 * Two endings leave a deployed corpus behind — a `kill -9`, and `E2E_KEEP=1`, which is the
 * prescribed way to inspect a failure — and `{{RUN}}` in every `contextPath` exists precisely so
 * runs cannot collide, which guarantees the next run does not notice the residue and reports
 * nothing about it.
 *
 * Which runs are collectable is decided by `sweepableRuns`, never by the shape of a token: every
 * run's token has the same shape, so the manifest and a live pid are what separate an interrupted
 * run's residue from a run still going. `E2E_SWEEP=never` turns the whole thing off. It is an
 * opt-out rather than an opt-in because residue that nobody is told about is the failure being
 * fixed here.
 *
 * A run is forgotten only once its entities are actually gone. Dropping the entry over a failed
 * delete would leave the residue with nothing left to name it.
 */
export async function sweepPreviousRuns(
  catalog: Catalog,
  current: string,
  records: readonly RunRecord[] = sweepableRuns(current),
  outside: OutsideCatalog = OUTSIDE_CATALOG,
): Promise<PreviousRunSweep[]> {
  if (process.env.E2E_SWEEP === "never") return [];

  const swept: PreviousRunSweep[] = [];
  const startedAt = new Map(records.map((each) => [each.run, each.startedAt]));
  for (const token of [...new Set(records.map((each) => each.run))]) {
    const outcome = await sweepRunToken(catalog, token, outside).catch((cause: unknown) => ({
      residue: [] as Residue[],
      removed: [] as Residue[],
      failures: [`the sweep itself failed: ${String(cause)}`],
    }));
    const clean = sweptClean(outcome);
    if (outcome.residue.length || outcome.failures.length) {
      swept.push({
        run: token,
        startedAt: startedAt.get(token) ?? "an unrecorded time",
        found: outcome.residue,
        removed: outcome.removed,
        failures: outcome.failures,
        forgotten: clean,
      });
    }
    for (const failure of outcome.failures) console.error(`[sweep] run ${token}, ${failure}`);
    if (clean) forgetRun(token);
  }
  return swept;
}

/** The start-of-run sweep in the words the run header prints. */
export function formatPreviousRunSweep(swept: readonly PreviousRunSweep[]): string {
  if (swept.length === 0) return "[sweep] no residue from a previous run";
  return swept
    .map((each) => {
      const named = each.removed.map((one) => `${one.kind} ${one.name}`).join(", ");
      const removed =
        `[sweep] run ${each.run}, started ${each.startedAt}, left ${each.found.length} entities, ` +
        `removed ${each.removed.length}${named ? `: ${named}` : ""}`;
      if (each.forgotten) return removed;
      const why = each.failures.length
        ? `${each.failures.length} delete(s) failed: ${each.failures.join("; ")}`
        : `${each.found.length - each.removed.length} entities are still there`;
      return `${removed}\n[sweep] run ${each.run} kept in the manifest, ${why}`;
    })
    .join("\n");
}

/**
 * The run-wide sweep, after every worker has finished.
 *
 * Wired as `globalTeardown` rather than into a worker fixture because it matches on the run token
 * and every worker shares it: a sweep inside a worker deletes another worker's entities while that
 * worker is still asserting over them.
 */
export default async function globalTeardown(): Promise<void> {
  const run = process.env.E2E_RUN;
  // A run with no token created nothing and started nothing: `globalSetup` returns before minting
  // one when every selected project declares it needs no stack.
  if (!run) return;

  // Before the sweep, because the sampler is a detached process and nothing else ends it — and
  // only for this run's own sampler. `--project=schema` is a supported, stack-free selection and CI
  // runs exactly that; stopping the sampler it finds would empty the peaks file of a full run going
  // on beside it, whose `process-envelope` band check then fails on no reading at all.
  stopResourceSampler(RESOURCES_FILE, run);

  const api = await playwrightRequest.newContext();
  const catalog = new Catalog(api);
  try {
    // Outside the sweep because nothing can delete it: a secret named after this run is a leak that
    // outlives the stack's data and every later run. `specs/api/v2-v3-controllers.spec.ts` is what
    // turns red over it; this line is what a reader sees when the offending spec is not in the run.
    const secrets = await secretsCarryingRunToken(catalog, run).catch(() => []);
    for (const name of secrets) {
      console.error(
        `[sweep] run ${run} created secret ${name}, which has no delete endpoint and is now ` +
          `permanent on this stack. Secrets are named with a committed fixture name for that ` +
          `reason — see SECRET_FIXTURE_NAME.`,
      );
    }
    if (keepEntities()) {
      const left = await findResidue(catalog, run, OUTSIDE_CATALOG);
      console.log(`[keep] E2E_KEEP=1, ${left.length} entities left behind for run ${run}`);
      for (const each of left) console.log(`[keep]   ${each.kind} ${each.name} (${each.id})`);
      return;
    }
    const outcome = await sweepRunToken(catalog, run, OUTSIDE_CATALOG);
    const { residue, removed, failures } = outcome;
    if (residue.length) {
      console.log(
        `[sweep] found ${residue.length} entities carrying run ${run}, removed ${removed.length}`,
      );
    }
    for (const failure of failures) console.error(`[sweep] run ${run}, ${failure}`);
    // The manifest exists so a *later* run can clean up after this one. A run that swept itself has
    // nothing left to hand over, and leaving the entry would make the next run sweep an empty token.
    // A run that could not delete everything does have something to hand over, so it keeps its entry.
    if (sweptClean(outcome)) forgetRun(run);
  } catch (cause) {
    // A failed sweep must not turn a green run red: it leaves residue, and the run keeps its
    // manifest entry for the next run's sweep. Silence here would be the real failure.
    console.error(`[sweep] run ${run} could not be swept: ${String(cause)}`);
  } finally {
    await api.dispose();
  }
}
