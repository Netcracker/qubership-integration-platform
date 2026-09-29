/**
 * `maintenance-controller`, whole: one operation,
 * `POST /v1/catalog/maintenance/snapshots/prune`.
 *
 * **There is no maintenance mode.** The controller offers a snapshot cleanup and nothing else — no
 * read-only switch, no drain, no status. It takes `olderThanDays` and `chunk`, answers 202, and
 * runs the work on a `CompletableFuture`, so every case here polls for an effect rather than
 * reading once after the call.
 *
 * **This file fires the real prune against the whole stack, and that is a decision rather than an
 * oversight.** No parameter isolates it. `chunk` bounds one iteration of a
 * `do … while (deletedCurrent > 0)` loop and not the prune, and `olderThanDays` sets a floor that
 * cannot exclude an older snapshot while including a newer one — so a call takes every undeployed,
 * non-current snapshot on the platform, whoever created it. Three things make that acceptable and
 * all three have to hold: the stack is the suite's own, `global` runs one worker after every other
 * project, and the two snapshots a chain needs to go on working — the deployed one and the current
 * one — are the two the query protects. `e2e/README.md` says so where a person reading before a
 * run will see it, because the consequence lands on whatever else lives on the stack.
 *
 * **What the query protects is one snapshot per chain, not a chain.**
 * `SnapshotRepository.pruneByCreatedWhen` is a delete over
 * `LEFT JOIN deployments d … LEFT JOIN chains c … WHERE d.id IS NULL AND c.id IS NULL`, so a
 * deployed chain keeps its deployed snapshot and its current one and loses the rest of its
 * history. Measured on this stack: a chain carrying 30 snapshots came out of a prune holding one,
 * and `catalog.snapshots` afterwards matched the predicate nowhere — the working set is what
 * survives and the history is what goes.
 *
 * **The audit log is the prune's only report.** `pruneSnapshotsAsync` logs one
 * `SNAPSHOT_CLEANUP` / `EXECUTE` row before it starts and `pruneSnapshots` logs a `SNAPSHOT` /
 * `DELETE` row per removed snapshot, off the query's `RETURNING id, name, chain_id`. The table is
 * `catalog.logged_actions`; **a spec reads it over HTTP rather than over SQL**, because
 * `POST /v1/catalog/actions-log` serves those rows and `Catalog.recentActions` already exists,
 * and the suite has no database client. The two
 * readings were held against each other once: one prune over 30 snapshots wrote 30 rows under its
 * request id, and the endpoint answered with all 30.
 *
 * That table is global, so the rows are picked out by **request id**: `MDCInterceptor` takes
 * `X-Request-ID` off the request verbatim, and `pruneSnapshotsAsync` carries the value onto the
 * future, so one prune's asynchronous deletions are all attributable to the call that started
 * them. On the `SNAPSHOT_CLEANUP` row `entityId` and `entityName` are **absent** rather than null —
 * the catalog serializes non-null only — which is why the type and the operation are what a case
 * asserts on.
 *
 * Two shapes measured beyond the checkboxes:
 *
 * - **the parameters are refused in two different envelopes.** A value that breaks `@Min` answers
 *   the module's `{serviceName, errorMessage, errorDate}`; a parameter that is missing or does not
 *   parse never reaches the constraint and answers Spring's RFC 9457 body instead.
 * - **`olderThanDays=1` deletes nothing and still logs the `EXECUTE` row.** The row means a prune
 *   ran, never that anything was removed.
 *
 * The platform does not prune on its own during a run: `TasksScheduler` runs the cleanup on
 * `0 0 0 ? * SAT` with a 14-day floor — weekly, Saturday midnight. Both are settings
 * (`SNAPSHOTS_CLEANUP_CRON`, `SNAPSHOTS_CLEANUP_INTERVAL`) and the compose stack overrides
 * neither, so what a run sees is the default schedule.
 */
import { test, expect } from "../../support/fixtures.js";
import { DEFAULT_DOMAIN, type ActionLogEntry, type Catalog } from "../../support/catalog.js";
import { ENGINE_CASE_TIMEOUT, waitForDeployed, waitForRoutes } from "../../support/corpus.js";
import { tokenizedChain } from "../../support/deployable.js";
import { tokenized } from "../../support/run.js";

/** How long a prune's effect is waited for. Measured: 29 snapshots went inside 33 ms, one poll. */
const PRUNE_TIMEOUT = 30_000;

/** A chain of this spec's own, plus the snapshots taken over it, oldest first. */
interface Versioned {
  id: string;
  name: string;
  snapshots: { id: string; name: string }[];
}

/**
 * A chain with `count` snapshots over it, the last of which is the chain's current one.
 *
 * The chain is bare — a snapshot builds off whatever the chain holds, and none of these are
 * deployed — except in the deployed case below, which wires a trigger of its own.
 */
async function versionedChain(
  catalog: Catalog,
  run: string,
  folderId: string,
  what: string,
  count: number,
): Promise<Versioned> {
  const name = tokenized(run, `prune-${what}`);
  const chain = await catalog.createChain(name, folderId);
  const snapshots: { id: string; name: string }[] = [];
  for (let taken = 0; taken < count; taken += 1) {
    const snapshot = await catalog.createSnapshot(chain.id);
    snapshots.push({ id: snapshot.id, name: snapshot.name });
  }
  return { id: chain.id, name, snapshots };
}

/** The names of a chain's snapshots as the catalog lists them, sorted so the order is not asserted. */
async function snapshotNames(catalog: Catalog, chainId: string): Promise<string[]> {
  return (await catalog.listSnapshots(chainId)).map((snapshot) => snapshot.name).sort();
}

/** Polls the chain's snapshots until they are exactly the names that should survive the prune. */
async function untilPruned(
  catalog: Catalog,
  chain: Versioned,
  survivors: string[],
): Promise<void> {
  await expect
    .poll(async () => snapshotNames(catalog, chain.id), {
      timeout: PRUNE_TIMEOUT,
      message: `${chain.name} did not settle on ${survivors.join(", ")} after the prune`,
    })
    .toEqual([...survivors].sort());
}

/** Every audit row one prune wrote, found by the request id that prune was fired with. */
async function auditOf(catalog: Catalog, requestId: string): Promise<ActionLogEntry[]> {
  return (
    await catalog.recentActions([{ column: "REQUEST_ID", condition: "IS", value: requestId }])
  ).actionLogs;
}

/**
 * Polls one prune's audit rows until the row deleting `snapshotId` is among them.
 *
 * The `RETURNING` clause the rows are built from names only what the statement actually removed, so
 * this row appearing under this request id is the proof that **this** prune took the snapshot.
 */
async function untilDeletionLogged(
  catalog: Catalog,
  requestId: string,
  snapshotId: string,
): Promise<ActionLogEntry[]> {
  let rows: ActionLogEntry[] = [];
  await expect
    .poll(
      async () => {
        rows = await auditOf(catalog, requestId);
        return rows.some((row) => row.entityId === snapshotId && row.operation === "DELETE");
      },
      {
        timeout: PRUNE_TIMEOUT,
        message: `no audit row for prune ${requestId} deleting snapshot ${snapshotId}`,
      },
    )
    .toBe(true);
  return rows;
}

test("the prune takes a chain's superseded snapshots and leaves the current one", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await versionedChain(catalog, run, folder.id, "superseded", 2);
  const [older, current] = chain.snapshots;
  expect(await snapshotNames(catalog, chain.id)).toEqual([older.name, current.name].sort());
  // The newest build is the chain's current one, which is the half of the protection this case
  // separates from the deployment half below.
  expect((await catalog.getChain(chain.id)).currentSnapshot?.id).toBe(current.id);

  // `olderThanDays=0` computes `Instant.now()` as the deletion date, so a snapshot built seconds
  // ago is already older than it. Nothing can backdate one — `created_when` is
  // `@Column(updatable = false)` — and nothing needs to.
  const response = await catalog.pruneSnapshots({ olderThanDays: 0 });
  expect(response.status(), "the prune is accepted asynchronously and answers no body").toBe(202);
  expect(await response.text()).toBe("");

  await untilPruned(catalog, chain, [current.name]);
  // The chain still points at the snapshot it did: a prune that took the current one would leave
  // the chain listing nothing and this reading null.
  expect((await catalog.getChain(chain.id)).currentSnapshot?.id).toBe(current.id);
});

test("a deployed snapshot survives the prune even once it is no longer the current one", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, env, folder, request, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const built = await tokenizedChain(catalog, run, {
    prefix: "prune",
    what: "deployed",
    parentId: folder.id,
  });
  const deployedSnapshot = await catalog.createSnapshot(built.id);
  const deployment = await catalog.deploy(built.id, deployedSnapshot.id);
  const seeded = [{ id: built.id, name: built.name, contextPath: built.contextPath }];
  await waitForDeployed(catalog, seeded);
  await waitForRoutes(env, seeded);

  // Two more builds. The second becomes the chain's current snapshot and the first is left
  // holding nothing: not deployed, no longer current, and therefore the one row of the three the
  // query is free to take.
  const orphan = await catalog.createSnapshot(built.id);
  const current = await catalog.createSnapshot(built.id);
  const chain: Versioned = {
    id: built.id,
    name: built.name,
    snapshots: [deployedSnapshot, orphan, current].map((each) => ({ id: each.id, name: each.name })),
  };

  await catalog.pruneSnapshots({ olderThanDays: 0 });
  await untilPruned(catalog, chain, [deployedSnapshot.name, current.name]);

  // The deployment is still the record it was, still pointing at a snapshot that still exists.
  const deployments = await catalog.listDeployments(built.id);
  expect(deployments.map((each) => each.id)).toEqual([deployment.id]);
  expect(deployments[0].snapshotId).toBe(deployedSnapshot.id);
  expect(deployments[0].domain).toBe(DEFAULT_DOMAIN);
  // And the route is still live, which is the assertion the catalog's own record cannot make. The
  // trigger is POST-restricted, so a GET answers 405 while the route is up and 404 once it is not.
  const probe = await request.get(env.chainUrl(built.contextPath));
  expect(probe.status(), "the prune took a snapshot the engine was serving").toBe(405);

  await catalog.undeploy(built.id, deployment.id);
});

test("the audit trail names the prune and every snapshot it removed", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await versionedChain(catalog, run, folder.id, "audited", 2);
  const [older, current] = chain.snapshots;
  const requestId = tokenized(run, "prune-audited");

  await catalog.pruneSnapshots({ olderThanDays: 0, requestId });
  const rows = await untilDeletionLogged(catalog, requestId, older.id);
  await untilPruned(catalog, chain, [current.name]);

  // The run's own row, and the whole of what it carries. `entityId` and `entityName` are absent
  // rather than null — nothing is passed for either — so the type and the operation are the only
  // things that identify it, which is what makes this row unfindable except by request id.
  const executions = rows.filter((row) => row.entityType === "SNAPSHOT_CLEANUP");
  expect(executions, "one call, one execution row").toHaveLength(1);
  expect(executions[0]).toMatchObject({ operation: "EXECUTE", username: "developer", requestId });
  expect(executions[0].entityId).toBeUndefined();
  expect(executions[0].entityName).toBeUndefined();
  expect(executions[0].parentId).toBeUndefined();
  expect(executions[0].parentType).toBeUndefined();

  // Logged before the work starts, which is the ordering that makes the row mean "a prune ran"
  // rather than "a prune finished".
  const deletion = rows.find((row) => row.entityId === older.id)!;
  expect(executions[0].actionTime).toBeLessThanOrEqual(deletion.actionTime);

  // The deletion row identifies the snapshot and the chain it belonged to, which is everything the
  // row has to carry: the snapshot it names no longer exists to be looked up.
  expect(deletion).toMatchObject({
    entityType: "SNAPSHOT",
    operation: "DELETE",
    entityId: older.id,
    entityName: older.name,
    parentType: "CHAIN",
    parentId: chain.id,
    parentName: chain.name,
    requestId,
  });
  // The surviving snapshot was not logged as deleted, which is the other half of "the row names
  // what the statement removed".
  expect(rows.some((row) => row.entityId === current.id)).toBe(false);
  // A prune writes those two kinds of row and no others.
  expect([...new Set(rows.map((row) => row.entityType))].sort()).toEqual([
    "SNAPSHOT",
    "SNAPSHOT_CLEANUP",
  ]);
});

test("olderThanDays is a floor: a snapshot minutes old outlives a one-day prune", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await versionedChain(catalog, run, folder.id, "floor", 2);
  const [older, current] = chain.snapshots;
  const floored = tokenized(run, "prune-floor-day");
  const unfloored = tokenized(run, "prune-floor-none");

  // A prune with a one-day floor, then one with none. The proof that the first left the snapshot
  // alone is that the **second** one's audit row names it: those rows are built from the delete's
  // `RETURNING`, so only the statement that actually removed the row can have logged it.
  await catalog.pruneSnapshots({ olderThanDays: 1, requestId: floored });
  await catalog.pruneSnapshots({ olderThanDays: 0, requestId: unfloored });

  await untilDeletionLogged(catalog, unfloored, older.id);
  await untilPruned(catalog, chain, [current.name]);

  // The floored prune logged that it ran and deleted nothing of this chain's. It cannot be asserted
  // to have deleted nothing at all: a stack that has been up for days may hold snapshots a one-day
  // floor does reach, and they are none of this case's business.
  const flooredRows = await auditOf(catalog, floored);
  expect(flooredRows.filter((row) => row.entityType === "SNAPSHOT_CLEANUP")).toHaveLength(1);
  const mine = new Set(chain.snapshots.map((snapshot) => snapshot.id));
  expect(flooredRows.filter((row) => row.entityId && mine.has(row.entityId))).toEqual([]);
});

test("chunk bounds one iteration of the prune rather than the prune", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await versionedChain(catalog, run, folder.id, "chunked", 4);
  const doomed = chain.snapshots.slice(0, 3);
  const current = chain.snapshots[3];
  const requestId = tokenized(run, "prune-chunked");

  // One row per statement. If `chunk` bounded the call, two of these three would survive; the loop
  // is what makes the smallest chunk the platform accepts remove everything anyway, and it is why
  // no parameter can be used to narrow the blast radius.
  await catalog.pruneSnapshots({ olderThanDays: 0, chunk: 1, requestId });
  await untilPruned(catalog, chain, [current.name]);

  const rows = await auditOf(catalog, requestId);
  const deleted = rows
    .filter((row) => row.operation === "DELETE" && row.parentId === chain.id)
    .map((row) => row.entityName)
    .sort();
  expect(deleted).toEqual(doomed.map((snapshot) => snapshot.name).sort());
});

test("the two parameters are validated, and refuse in two different envelopes", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  const path = "/v1/catalog/maintenance/snapshots/prune";

  // `@Min(0)` and `@Min(1)`, both reported through the module's own exception body. Both refusals
  // go through `pruneSnapshots` rather than around it, which is also what pins the transport: a
  // client that dropped either value would answer 202 here instead of 400.
  const negativeDays = await catalog.pruneSnapshots({ olderThanDays: -1 });
  expect(negativeDays.status()).toBe(400);
  expect(await negativeDays.json()).toMatchObject({
    serviceName: "Catalog",
    errorMessage: "Invalid request content: [pruneSnapshots.olderThanDays must be greater than or equal to 0]",
  });

  const zeroChunk = await catalog.pruneSnapshots({ olderThanDays: 0, chunk: 0 });
  expect(zeroChunk.status()).toBe(400);
  expect(await zeroChunk.json()).toMatchObject({
    serviceName: "Catalog",
    errorMessage: "Invalid request content: [pruneSnapshots.chunk must be greater than or equal to 1]",
  });

  // A parameter that is missing or does not parse never reaches the constraint, so Spring answers
  // before the handler and the body is RFC 9457 rather than the module's. A client that reads
  // `errorMessage` finds nothing on these two. The transport always sends an `olderThanDays` and
  // always sends a number, so neither shape can be built through it; these two go out raw.
  const missing = await catalog.raw("post", `${path}?chunk=5`);
  expect(missing.status()).toBe(400);
  expect(await missing.json()).toMatchObject({
    type: "about:blank",
    title: "Bad Request",
    status: 400,
    detail: "Required parameter 'olderThanDays' is not present.",
    instance: path,
  });

  const unparseable = await catalog.raw("post", `${path}?olderThanDays=abc`);
  expect(unparseable.status()).toBe(400);
  expect(await unparseable.json()).toMatchObject({
    detail: "Failed to convert 'olderThanDays' with value: 'abc'",
  });
});
