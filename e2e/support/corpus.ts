/**
 * The shared fixture corpus: imported once, deployed once, read by every runtime spec.
 *
 * One assembler, called by every seed project there will ever be. Three producers feed it —
 * `fixtures/chains/` here, the script corpus, and the axis generator's output — and letting each
 * of them deploy its own chains would pay the engine's deployment pickup once per chain instead of
 * once per corpus. That pickup is not a 2.5 s tick — the e2e instruction owns the measurement and
 * what to size a poll on. Measured on this stack: ~2 s per chain deployed on its own against 0.49 s
 * each inside a batch.
 *
 * Three things happen in an order that is not negotiable:
 *
 * 1. **Delete before importing.** An import over a live id is an update, so an assertion that the
 *    chains are present passes whether the import created them or found them already there. The
 *    fixture documents carry fixed ids, so the previous run's corpus is exactly what would be
 *    updated.
 * 2. **Raise the session logging level.** `DeploymentRuntimeProperties` defaults to
 *    `SessionsLoggingLevel.OFF` and `SessionsService` writes nothing at that level, so a corpus
 *    deployed without this step records no trace at all — and every trace assertion in the suite
 *    reads that trace. The level is **not** part of the deployment: the
 *    deployment carries `maskedFields` and no runtime properties, and the engine resolves the
 *    properties per exchange out of a live Consul-backed `AtomicReference`
 *    (`CamelDebuggerProperties.getRuntimeProperties`), so a change takes effect on a running route.
 *    That is what makes the level dangerous rather than ordering-sensitive: it is one setting
 *    shared by every spec on a seed chain, and lowering it mid-run silences their trace assertions
 *    along with the one doing the lowering. A spec that needs a different level builds its own
 *    chain.
 * 3. **Gate on the route, not on the status.** `DEPLOYED` means the catalog accepted and dispatched
 *    the deployment; it is not a readiness signal. A chain whose listener never connects reports
 *    `DEPLOYED` indefinitely, and even a healthy one serves seconds after the status flips. So the
 *    status poll is followed by a poll of the routes themselves.
 */
import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Catalog, type LoggingProperties } from "./catalog.js";
import {
  assembleCorpus,
  assembleTrees,
  CHAIN_FIXTURE_DIR,
  corpusFixtureNames,
  corpusFixtures,
  MICRO_FIXTURES,
  microCopy,
  readFixtureDocument,
  renderFixture,
  SCRIPT_FIXTURE_DIR,
  type FixtureDocument,
  type RenderedTree,
} from "../fixtures/templating.js";
import { renderAxisFixtures } from "../fixtures/axis-generator.js";
import type { EngineKind, Env } from "../env/index.js";
import { MICRO_CORPUS_STATE_FILE } from "../env/k8s.js";
import { noteChain } from "./diagnostics.js";
import { keepEntities } from "./fixtures.js";
import { tokenized } from "./run.js";
import type { EndpointMockReference } from "./testing-service.js";
import { readStateFile, writeStateFile } from "./state-file.js";
import { stateFileName } from "../env/target.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * Where the seed leaves what it imported, for the projects that depend on it.
 *
 * A file rather than a fixture because the readers are other processes: Playwright runs each
 * project in its own workers, so the only thing a setup project can hand a dependent one is
 * something on disk.
 *
 * Outside `test-results/`, which Playwright clears at the start of every run — measured, and
 * before `globalSetup` runs. A corpus kept with `E2E_KEEP=1` exists to be re-run against with
 * `--no-deps`, and a state file the next run deletes makes that impossible. Each target has a file
 * of its own, so a Compose corpus is never read or discarded by a run against the cluster.
 */
export const CORPUS_STATE_FILE = path.join(HERE, "..", stateFileName(".e2e-corpus.json"));

/** Measured over 15 deploys: median 2.165 s, max 3.113 s. The route lags the status by seconds. */
export const DEPLOY_TIMEOUT = 60_000;

/**
 * What `waitForDeployed` adds per chain on top of `DEPLOY_TIMEOUT`, because the engine deploys one
 * chain at a time: the 71 corpus chains and the 21 broker chains, seeded side by side, took 61 s.
 */
export const DEPLOY_TIMEOUT_PER_CHAIN = 1_000;

/** `cip.deployments.retry-delay` in the engine's `application.yml`: a parked deployment waits this long. */
export const RETRY_DELAY = 30_000;

/**
 * What a case gets instead of the suite's 120 s default when it waits on the engine rather than on
 * the catalog.
 *
 * Building a chain, snapshotting it, deploying it and polling its route spends most of a default
 * budget before the first assertion, and a Prometheus counter is locked for its first 30 s on top
 * of that. Three chains, or one lock read twice, put a case past 120 s. Three minutes is that with
 * room for a loaded stack; a case whose budget is decided by something else says so at its own
 * `test.setTimeout`.
 */
export const ENGINE_CASE_TIMEOUT = 180_000;

/**
 * A deployed chain as the two waiters below read one: an id to look up, a name to fail by, and a
 * route to call.
 *
 * Narrower than `SeedChain` on purpose. A spec that builds its own chain and then waits for it used
 * to hand these a `SeedChain` literal, which meant inventing a `fixture` that named no fixture
 * directory and an empty `elements` map — two padded fields, at four sites, to satisfy a type
 * neither waiter reads that far into.
 */
export interface DeployedChain {
  id: string;
  name: string;
  /** The HTTP trigger's `contextPath`, already carrying the run token. */
  contextPath: string;
}

/** One imported and deployed fixture chain, in the form a spec addresses it by. */
export interface SeedChain extends DeployedChain {
  /** The fixture directory it came from, which is how a spec asks for one by name. */
  fixture: string;
  /** Design-time element ids by element name — what an endpoint mock's reference takes. */
  elements: Record<string, string>;
}

export interface SeededCorpus {
  run: string;
  chains: SeedChain[];
}

/**
 * The session logging the corpus deploys with. `DEBUG` is the level that records a payload.
 *
 * `maskingEnabled` is on for the whole corpus rather than for the one chain that has a masked
 * field, because the flag only decides whether the chain's **own** set of masked fields is applied
 * and every other fixture's set is empty. `PayloadExtractor.extractBodyForLogging` short-circuits
 * on an empty set, so this costs the other fourteen chains nothing.
 */
export const SEED_LOGGING: LoggingProperties = {
  sessionsLoggingLevel: "DEBUG",
  logLoggingLevel: "INFO",
  logPayload: ["HEADERS", "PROPERTIES", "BODY"],
  logPayloadEnabled: false,
  dptEventsEnabled: false,
  maskingEnabled: true,
  sessionLogDetails: "OFF",
};

/**
 * The one fixture that deploys with a masked field, and the field it masks.
 *
 * The field is created between the import and the deploy for the same reason the logging level is:
 * masked fields travel to the engine inside the deployment (`IntegrationRuntimeService:428` reads
 * `deployment.getMaskedFields()`), so a field added after the deploy would not reach the running
 * route. The runtime masking spec calls this chain with a body carrying the field.
 */
export const MASKED_FIXTURE = "masking";
export const MASKED_FIELD = "cardNumber";

/**
 * The one fixture whose element property is a `#{variable}` placeholder, and the common variable
 * it names.
 *
 * Created between the import and the deploy for the reason the masked field is: the engine
 * substitutes placeholders into the route XML when it deploys (`IntegrationRuntimeService` calls
 * `VariablesService.injectVariables`), and a variable missing at that moment parks the deployment
 * in `PROCESSING` rather than letting it go live.
 */
export const PLACEHOLDER_FIXTURE = "placeholder-resolution";

/** The variable the fixture's `#{e2e-{{RUN}}-placeholder}` resolves to, named off the corpus token. */
export function placeholderVariable(run: string): string {
  return tokenized(run, "placeholder");
}

/** What the seed writes into it. Carries the token, so a value from another run cannot pass. */
export function placeholderValue(run: string): string {
  return `resolved-${run}`;
}

/** A fixture document's element, as much of its shape as `flatten` and its callers read. */
export interface FixtureElement {
  id?: unknown;
  name?: unknown;
  type?: unknown;
  properties?: Record<string, unknown>;
  children?: FixtureElement[];
}

/** Every element of a fixture document, parents before their children. */
export function flatten(elements: readonly FixtureElement[]): FixtureElement[] {
  const flat: FixtureElement[] = [];
  for (const element of elements) {
    flat.push(element);
    if (element.children) flat.push(...flatten(element.children));
  }
  return flat;
}

/**
 * Reads the fixture document for what a spec needs to address the chain it becomes.
 *
 * `elements` descends into `children`, because a container's branch is where the addressable
 * elements of half the fixtures sit — `split` keeps its two scripts one and two levels down — and a
 * name that resolves to `undefined` produces an endpoint mock keyed on nothing, which simply does
 * not apply and reads as the platform ignoring the mock.
 *
 * Two elements sharing a name is refused rather than silently resolved. `fixtures/templating.ts`
 * makes the same refusal for ids and for context paths; this is the third collision a fixture edit
 * can introduce, and the map it corrupts is what a mock and a trace assertion are keyed on.
 */
function describe(fixture: string, document: Record<string, unknown>): SeedChain {
  const content = (document.content ?? {}) as { elements?: FixtureElement[] };
  const elements = flatten(content.elements ?? []);
  const trigger = elements.find((each) => each.type === "http-trigger");
  const contextPath = (trigger?.properties?.contextPath as string | undefined) ?? "";
  if (!contextPath) {
    throw new Error(`fixture ${fixture} carries no HTTP trigger, so nothing can call it`);
  }

  // A `Map` rather than an object: the keys are fixture-supplied names, and a plain object answers
  // `constructor` and `toString` out of its prototype, which reads as a duplicate that is not one.
  const byName = new Map<string, string>();
  for (const element of elements) {
    if (typeof element.name !== "string" || typeof element.id !== "string") continue;
    const already = byName.get(element.name);
    if (already !== undefined) {
      throw new Error(
        `fixture ${fixture} names two elements ${JSON.stringify(element.name)} ` +
          `(${already} and ${element.id}): a spec addresses an element by name, so only one of ` +
          `them would ever be reachable`,
      );
    }
    byName.set(element.name, element.id);
  }

  return {
    fixture,
    id: String(document.id),
    name: String(document.name),
    contextPath,
    elements: Object.fromEntries(byName),
  };
}

/**
 * Deletes the chains an assembled corpus declares, imports it, and asserts the import created each
 * of them.
 */
async function importCorpus(
  catalog: Catalog,
  assembled: { archive: Buffer; documents: FixtureDocument[] },
  fixtures: readonly string[],
  archiveName: string,
): Promise<SeedChain[]> {
  const chains = assembled.documents.map((each, index) => describe(fixtures[index], each.document));

  // Rule 5 of the spec rules, applied to the seed: the ids are fixed, so an import over yesterday's
  // corpus is an update and every assertion below would hold without anything having been created.
  for (const chain of chains) await catalog.deleteChain(chain.id).catch(() => {});

  const response = await catalog.importChains(assembled.archive, archiveName);
  expect(response.status(), `the corpus import answered ${response.status()}`).toBe(200);
  const imported = (await response.json()) as { chains: Array<{ id: string; status: string }> };

  expect(
    imported.chains.map((each) => each.id).sort(),
    "the import wrote a different set of chains than the corpus declares",
  ).toEqual(chains.map((each) => each.id).sort());
  expect(
    imported.chains.filter((each) => each.status !== "CREATED"),
    "the delete before the import is what makes every row CREATED",
  ).toEqual([]);
  return chains;
}

/**
 * Imports the named fixture directories, raises their logging, deploys them in one batch, and
 * returns once every route answers.
 *
 * `expected` is asserted before anything is polled: "every route is live" is satisfied by zero
 * routes, and an import that imported nothing answers 200 with an empty `chains` array.
 */
export async function seedCorpus(
  catalog: Catalog,
  env: Env,
  run: string,
  fixtures: readonly string[] = corpusFixtureNames(),
): Promise<SeededCorpus> {
  const chains = await importCorpus(catalog, await assembleCorpus(fixtures, run), fixtures, `seed-${run}.zip`);

  for (const chain of chains) await catalog.saveLoggingProperties(chain.id, SEED_LOGGING);

  const masked = chains.find((each) => each.fixture === MASKED_FIXTURE);
  if (masked) await catalog.createMaskedField(masked.id, MASKED_FIELD);

  await catalog.addCommonVariables({ [placeholderVariable(run)]: placeholderValue(run) });

  await deployCorpus(catalog, chains);

  await waitForDeployed(catalog, chains);
  await waitForRoutes(env, chains);

  return { run, chains };
}

/**
 * Deploys the whole corpus, and then deploys one chain again through a different endpoint.
 *
 * The second half is not redundancy. **A bulk deploy tells the engine nothing.**
 * `DeploymentService.bulkCreate` reaches its own `@DeploymentModification` methods by
 * self-invocation — `bulkCreate` → `deploySnapshot` → `deploySnapshotToDomain` → `create` — and
 * Spring's proxy AOP does not intercept a call an object makes to itself, so the aspect that writes
 * Consul's `deployments-update` key never runs. The engine watches that key with a blocking query
 * and fetches only when its index moves, so the deployment rows exist in the catalog and no route
 * ever comes up. Measured: three bulk deploys in a row left the key at its previous timestamp, the
 * engine issued no `deployments/update` request for ten minutes, and the chains sat at no runtime
 * state at all.
 *
 * `POST /v1/catalog/chains/{id}/deployments` reaches `create` from the **controller**, through the
 * proxy, so the aspect runs. One chain deployed that way after the batch moves the key once, and
 * the engine then collects every pending deployment in the same round — which is exactly the one
 * Consul tick the batch exists to pay.
 */
export async function deployCorpus(catalog: Catalog, chains: readonly SeedChain[]): Promise<void> {
  if (chains.length === 0) {
    throw new Error(
      "deployCorpus was given no chains: the last one is what moves Consul's deployments-update " +
        "key, so an empty corpus has nothing to deploy and nothing downstream would come up",
    );
  }
  const batch = chains.slice(0, -1);
  const last = chains[chains.length - 1];

  if (batch.length > 0) {
    const { status, rows } = await catalog.bulkDeploy(batch.map((each) => each.id));
    expect(status, `the corpus deploy answered ${status}: ${JSON.stringify(rows)}`).toBe(200);
    expect(
      rows.filter((row) => row.status !== "CREATED").map((row) => `${row.chainName}: ${row.status}`),
      "a chain the batch could not deploy",
    ).toEqual([]);
  }

  const snapshot = await catalog.createSnapshot(last.id);
  await catalog.deploy(last.id, snapshot.id);
}

/** Polls until every seeded chain is keyed in the runtime view, failing with the set difference. */
export async function waitForDeployed(
  catalog: Catalog,
  chains: readonly DeployedChain[],
): Promise<void> {
  const byId = new Map(chains.map((each) => [each.id, each.name]));
  await expect
    .poll(
      async () => {
        const reported = await catalog.runtimeDeployments();
        const missing: string[] = [];
        for (const [id, name] of byId) {
          const rows = reported[id] ?? [];
          if (rows.some((row) => row.status === "DEPLOYED")) continue;
          // The catalog sets `errorMessage` on exactly the case worth reporting: a chain the engine
          // refused sits at FAILED for the whole budget, and without this the seed spends 60 s to
          // report "never reported DEPLOYED" over a response that named the reason.
          const why = rows
            .map((row) => (row.errorMessage ? `${row.status}: ${row.errorMessage}` : row.status))
            .join("; ");
          missing.push(why ? `${name} (${why})` : `${name} (no runtime state at all)`);
        }
        return missing.sort();
      },
      {
        timeout: DEPLOY_TIMEOUT + chains.length * DEPLOY_TIMEOUT_PER_CHAIN,
        message: "seed chains the engine never reported DEPLOYED",
      },
    )
    .toEqual([]);
}

/**
 * Polls until the engine reports none of `chains` deployed, failing with the ones it still runs.
 *
 * `undeployAll` answers once the catalog has dropped the deployments; the engine stops each context
 * later, one at a time. The runtime view is the engine's own report, so a chain leaves it only after
 * its context, and every consumer in it, has stopped.
 */
export async function waitForUndeployed(catalog: Catalog, chains: readonly DeployedChain[]): Promise<void> {
  await expect
    .poll(
      async () => {
        const reported = await catalog.runtimeDeployments();
        return chains.filter((each) => (reported[each.id] ?? []).length > 0).map((each) => each.name).sort();
      },
      {
        timeout: DEPLOY_TIMEOUT + chains.length * DEPLOY_TIMEOUT_PER_CHAIN,
        message: "chains the engine still runs after their undeploy",
      },
    )
    .toEqual([]);
}

/**
 * Polls the routes themselves, which is the seed's real gate.
 *
 * A `GET` is enough and is deliberately not a `POST`: the triggers restrict the method, so a live
 * route answers **405** and a route that is not there answers **404**. That distinction is the
 * whole reading — it separates "deployed" from "serving" without running the chain once. A trigger
 * that does allow `GET` answers **200**, so both count as serving and nothing else does: a 500 or a
 * 401 is a route that is up and broken, and passing it through the gate hands every downstream spec
 * a failure that reads as its own.
 *
 * `timeout` is a parameter because the seed is not the only caller: the restart spec waits for the
 * same routes to come back after the engine has been recreated, and that budget is a different
 * measured number from the one a fresh deploy takes.
 */
export async function waitForRoutes(
  env: Env,
  chains: readonly DeployedChain[],
  timeout: number = DEPLOY_TIMEOUT,
  message = "seed chains whose route never began to answer",
): Promise<void> {
  await expect
    .poll(
      async () => {
        const missing: string[] = [];
        for (const chain of chains) {
          const status = await fetch(env.chainUrl(chain.contextPath), { method: "GET" })
            .then((response) => response.status)
            .catch(() => 0);
          if (status !== 200 && status !== 405) missing.push(`${chain.name} (${status || "no response"})`);
        }
        return missing.sort();
      },
      { timeout, message },
    )
    .toEqual([]);
}

/**
 * Writes what the seed imported where the dependent projects read it.
 *
 * Through a temporary file and a rename, which is atomic on one filesystem: a `kill -9` partway
 * through a direct write leaves a truncated document, and every later run then fails in
 * `globalSetup` until somebody deletes the file by hand.
 */
export function writeCorpusState(corpus: SeededCorpus): void {
  writeStateFile(CORPUS_STATE_FILE, corpus);
}

/**
 * What the seed imported, or a failure naming the seed rather than the spec that asked.
 *
 * The message matters: a seed project whose `testMatch` collects nothing runs zero tests, reports
 * success, and satisfies the dependency, so every spec downstream fails for want of a corpus and
 * the diagnosis lands on the wrong file.
 */
export function readCorpusState(): SeededCorpus {
  const corpus = readStateFile<SeededCorpus>(CORPUS_STATE_FILE);
  if (!corpus) {
    throw new Error(
      `no readable seeded corpus at ${CORPUS_STATE_FILE}: the seed project did not run, ran no ` +
        `tests, or was killed partway through writing the file`,
    );
  }
  return corpus;
}

/**
 * Drops the classic and the micro state files when the corpus each names belonged to a run that
 * has just been swept.
 *
 * Without it the file outlives the chains it points at, and the next `--no-deps` run addresses
 * deleted ids and fails with 404s rather than with "the seed did not run".
 */
export function discardCorpusStateOf(runs: readonly string[]): void {
  for (const file of [CORPUS_STATE_FILE, MICRO_CORPUS_STATE_FILE]) {
    const corpus = readStateFile<SeededCorpus>(file);
    if (corpus && runs.includes(corpus.run)) fs.rmSync(file, { force: true });
  }
}

/**
 * One seeded chain by the fixture directory it came from.
 *
 * Asking for a chain is also how a spec says which chain it is about, so the lookup records it: a
 * failed spec attaches the engine's lines for that chain, and no spec has to remember to declare
 * anything for that to happen.
 */
export function seedChain(corpus: SeededCorpus, fixture: string): SeedChain {
  // The option a project sets with `use: { engineKind }`; a project that sets none runs classic.
  const engineKind = (test.info().project.use as { engineKind?: EngineKind }).engineKind;
  const copy = engineKind === "micro" ? readMicroCorpusState() : corpus;
  const found = copy.chains.find((each) => each.fixture === fixture);
  if (!found) {
    throw new Error(
      engineKind === "micro"
        ? `the micro copy holds no chain from fixture ${fixture}: add it to MICRO_FIXTURES`
        : `the seeded corpus holds no chain from fixture ${fixture}`,
    );
  }
  noteChain({ id: found.id, name: found.name, contextPath: found.contextPath });
  return found;
}

/** A seeded chain's HTTP trigger, by the design-time ids a test case or a mock references. */
export function triggerOf(fixture: string): EndpointMockReference {
  const chain = seedChain(readCorpusState(), fixture);
  return { chainId: chain.id, elementId: chain.elements["HTTP Trigger"] };
}

/**
 * Deletes each chain's logging properties, then each chain whose properties went, and adds a line
 * to `failures` for everything left behind.
 *
 * `undeployFirst` undeploys each chain before its delete, which undeploys it anyway: the friendlier
 * order for the classic engine, logged rather than reported when it fails.
 */
async function deleteSeedChains(
  catalog: Catalog,
  chains: readonly { id: string; name: string }[],
  failures: string[],
  options: { undeployFirst: boolean },
): Promise<void> {
  // Chain ids whose Consul key survived, and which are therefore not deleted below.
  const kept = new Set<string>();
  for (const chain of chains) {
    // The key first, the way `releaseChains` in `support/cleanup.ts` orders the same two hops.
    // `ChainRuntimePropertiesService.deleteCustomRuntimeProperties` has exactly one caller, the
    // controller: neither a chain delete nor a folder delete removes what `saveLoggingProperties`
    // wrote, so every seeded chain would otherwise leave a Consul key under an id that no longer
    // exists — fifteen of them per run, and the run would report a clean stack over all of them.
    await catalog.deleteLoggingProperties(chain.id).catch((cause: unknown) => {
      failures.push(
        `${chain.name} (${chain.id}) kept its logging properties, so it was kept: ${String(cause)}`,
      );
      kept.add(chain.id);
    });
    if (!options.undeployFirst) continue;
    await catalog.undeployAll(chain.id).catch((cause: unknown) => {
      console.error(
        `[teardown] ${chain.name} (${chain.id}) was not undeployed before its delete, which ` +
          `undeploys it anyway: ${String(cause)}`,
      );
    });
  }
  // One call per chain rather than `POST /v1/chains/bulk-delete`, which deletes each id inside
  // `try { … } catch (Exception e) { log.error(…) }` and answers 204 whatever happened
  // (`ChainController.java:273`). A chain it could not delete would pass for deleted, the state
  // file naming it would go, and the next run would find fifteen chains nothing points at.
  for (const chain of chains) {
    if (kept.has(chain.id)) continue;
    await catalog.deleteChain(chain.id).catch((cause: unknown) => {
      failures.push(`${chain.name} (${chain.id}) was not deleted: ${String(cause)}`);
    });
  }
}

/**
 * Undeploys and deletes the corpus. `E2E_KEEP=1` leaves it and says where it is.
 *
 * A failure is reported rather than swallowed, and the state file survives it. A chain that is
 * still deployed while the teardown reports green holds its route on a shared stack, and the state
 * file is the only thing left naming it.
 *
 * A chain whose **Consul key** survived is kept, the way `sweepRunToken` keeps one: the key is
 * filed under the chain id, and deleting the chain leaves it under an id nothing can address. A
 * kept chain still carries the run token in its name, so the next run's sweep finds it and tries
 * again. A failed **undeploy** keeps nothing, because the chain delete undeploys on its own —
 * `ChainService.deleteByIdIfExists` calls `deploymentService.deleteAllByChainId` before it touches
 * the row (`ChainService.java:190`) — so keeping the chain would block the call that removes the
 * route rather than protect it.
 */
export async function teardownCorpus(catalog: Catalog, corpus: SeededCorpus): Promise<void> {
  if (keepEntities()) {
    console.log(`[keep] ${corpus.chains.length} seed chains left behind for run ${corpus.run}`);
    for (const chain of corpus.chains) console.log(`[keep]   chain ${chain.name} (${chain.id})`);
    return;
  }

  const failures: string[] = [];
  await deleteSeedChains(catalog, corpus.chains, failures, { undeployFirst: true });

  await catalog.deleteCommonVariables([placeholderVariable(corpus.run)]).catch((cause: unknown) => {
    failures.push(`the variable ${placeholderVariable(corpus.run)} was not deleted: ${String(cause)}`);
  });

  if (failures.length === 0) {
    fs.rmSync(CORPUS_STATE_FILE, { force: true });
    return;
  }
  throw new Error(
    `the seed teardown left chains of run ${corpus.run} on the stack: ${failures.join("; ")}. ` +
      `${CORPUS_STATE_FILE} is kept, because it is what still names them.`,
  );
}

// ---------------------------------------------------------------------------
// The micro copy
// ---------------------------------------------------------------------------

/** The micro copy of the corpus, and the micro domain it is deployed to. */
export interface MicroCorpus extends SeededCorpus {
  domain: string;
}

/** The micro domain of a run. It carries the run token, so the sweep finds a domain a run left. */
function microDomainName(run: string): string {
  return tokenized(run, "micro");
}

/**
 * How long the micro routes get to answer after `deploy-chains`: the operator creates the pod, and
 * the engine loads every chain before it serves one.
 *
 * Measured on Docker Desktop's Kubernetes: 63 to 73 s for the 48 chains, in the seed's log line.
 */
export const MICRO_READY_TIMEOUT = 180_000;

/**
 * The named fixtures rendered with `run`, from `fixtures/chains/`, `fixtures/script/`, or the axis
 * generator's in-memory output.
 */
export async function fixtureTrees(
  names: readonly string[],
  run: string,
): Promise<Array<[string, RenderedTree]>> {
  const located = corpusFixtures([CHAIN_FIXTURE_DIR, SCRIPT_FIXTURE_DIR]);
  const generated = await renderAxisFixtures(run);
  return names.map((name) => {
    const dir = located.get(name);
    const tree = dir === undefined ? generated.get(name) : renderFixture(dir, name, run);
    if (tree === undefined) throw new Error(`no fixture directory or axis declaration holds ${name}`);
    return [name, tree];
  });
}

/** `MICRO_FIXTURES` rendered with `run` and turned into the micro copy. */
export async function microCorpusTrees(run: string): Promise<Array<[string, RenderedTree]>> {
  return (await fixtureTrees(MICRO_FIXTURES, run)).map(([name, tree]) => [name, microCopy(name, tree)]);
}

/**
 * Imports the micro copy, raises its logging, deploys it to the run's micro domain, and returns once
 * every route answers through the proxy.
 *
 * `env` has to be a micro `Env`: it reads the domain from the state file, which is written before
 * the deploy, so a teardown after a failed deploy still finds the domain.
 */
export async function seedMicroCorpus(
  catalog: Catalog,
  env: Env,
  run: string,
): Promise<{ corpus: MicroCorpus; readyMs: number }> {
  const trees = await microCorpusTrees(run);
  const fixtures = trees.map(([name]) => name);
  const chains = await importCorpus(catalog, await assembleTrees(trees), fixtures, `seed-micro-${run}.zip`);
  const corpus: MicroCorpus = { run, domain: microDomainName(run), chains };
  writeMicroCorpusState(corpus);

  for (const chain of chains) await catalog.saveLoggingProperties(chain.id, SEED_LOGGING);
  // The build compiles the masked fields into the Integration, so the field exists before the deploy.
  const masked = chains.find((each) => each.fixture === MASKED_FIXTURE);
  if (masked) await catalog.createMaskedField(masked.id, MASKED_FIELD);

  const deployedAt = Date.now();
  const rows = await catalog.deployChains(chains.map((each) => each.id), [corpus.domain]);
  expect(
    rows.map((row) => `${row.chainId} ${row.status}${row.errorMessage ? `: ${row.errorMessage}` : ""}`).sort(),
    "deploy-chains drops an unknown id silently, so every chain needs one CREATED row",
  ).toEqual(chains.map((each) => `${each.id} CREATED`).sort());

  await waitForRoutes(
    env,
    chains,
    MICRO_READY_TIMEOUT,
    `micro chains whose route never began to answer on ${corpus.domain}. If none answers, read the ` +
      `status of Integration ${corpus.domain} and the camel-k operator log (e2e/k8s/README.md, camel-k)`,
  );
  return { corpus, readyMs: Date.now() - deployedAt };
}

/** Writes the micro copy where `seedChain` and the micro `Env` read it. */
function writeMicroCorpusState(corpus: MicroCorpus): void {
  writeStateFile(MICRO_CORPUS_STATE_FILE, corpus);
}

let microNames: Promise<Map<string, string>> | undefined;

/** The name of every element of the micro copy by its id, rendered once per worker. */
export function microElementNames(): Promise<Map<string, string>> {
  microNames ??= microCorpusTrees(readMicroCorpusState().run).then((trees) => {
    const names = new Map<string, string>();
    for (const [name, tree] of trees) {
      const { document } = readFixtureDocument(name, tree);
      for (const element of flatten((document.content as { elements?: FixtureElement[] })?.elements ?? [])) {
        if (typeof element.id === "string" && typeof element.name === "string") {
          names.set(element.id, element.name);
        }
      }
    }
    return names;
  });
  return microNames;
}

/** The micro copy, or a failure naming `seed-micro` rather than the spec that asked. */
export function readMicroCorpusState(): MicroCorpus {
  const corpus = readStateFile<MicroCorpus>(MICRO_CORPUS_STATE_FILE);
  if (!corpus) {
    throw new Error(
      `no readable micro corpus at ${MICRO_CORPUS_STATE_FILE}: the seed-micro project did not run, ` +
        `ran no tests, or was killed partway through writing the file`,
    );
  }
  return corpus;
}

/**
 * Deletes the micro copy's logging properties, then its chains, then its domain. `E2E_KEEP=1`
 * leaves all three.
 *
 * A chain whose logging properties survived is kept, for the reason `teardownCorpus` keeps one. The
 * domain goes last, with `DELETE /v1/cr/{name}`, and the state file only once nothing failed.
 */
export async function teardownMicroCorpus(catalog: Catalog, corpus: MicroCorpus): Promise<void> {
  if (keepEntities()) {
    console.log(
      `[keep] ${corpus.chains.length} micro chains and domain ${corpus.domain} left behind for run ` +
        `${corpus.run}`,
    );
    return;
  }

  const failures: string[] = [];
  await deleteSeedChains(catalog, corpus.chains, failures, { undeployFirst: false });
  await catalog.deleteCustomResource(corpus.domain).catch((cause: unknown) => {
    failures.push(`the micro domain ${corpus.domain} was not deleted: ${String(cause)}`);
  });

  if (failures.length === 0) {
    fs.rmSync(MICRO_CORPUS_STATE_FILE, { force: true });
    return;
  }
  throw new Error(
    `the seed-micro teardown left entities of run ${corpus.run} on the stack: ${failures.join("; ")}. ` +
      `${MICRO_CORPUS_STATE_FILE} is kept, because it is what still names them.`,
  );
}
