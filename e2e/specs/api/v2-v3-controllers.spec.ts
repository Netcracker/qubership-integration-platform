/**
 * The `/v2` and `/v3` controllers: nine of them, and twenty-five operations.
 *
 * These are not a rewrite of the `/v1` surface. Each one is a second controller mounted beside the
 * first, and which of the two the product treats as authoritative differs per family — so every
 * case here says which, with the evidence, rather than covering both by reflex:
 *
 * - **Folders: `/v2` is authoritative.** `ui/src/api/rest/restApi.ts:1090-1164` reaches for `/v2`
 *   on create, read, path, rename, move, delete, bulk delete and the one-level listing; only the
 *   root listing (`/v1/folders`) and the nested-chain read (`/v1/folders/{id}/chains`) are still
 *   `/v1`. The two answer different documents: `/v1` gives a folder **with the items inside it**,
 *   `/v2` gives the folder alone and a separate `POST /v2/folders/list` for the level below.
 * - **Snapshots: the two are complements, not rivals.** `GET /v2/catalog/snapshots/{id}/full`
 *   needs no chain in its path and answers the element graph with **no** `xmlDefinition`;
 *   `GET /v1/catalog/chains/{c}/snapshots/{s}` needs the chain and answers `xmlDefinition` with no
 *   elements. Neither can replace the other.
 * - **The audit log: the two are complements too.** `/v1` takes a time window and no paging, `/v2`
 *   takes offset and limit and no window. One table, one filter vocabulary, and the UI calls both
 *   (`restApi.ts:1474` and `:1484`).
 * - **Common variables: `/v2` is authoritative.** `/v1/common-variables/import` is
 *   `@Deprecated(since = "24.4")` and the UI calls `/v2` (`restApi.ts:414`).
 * - **Import: `/v3` is authoritative and `/v2` is deprecated.** `ImportControllerV2` carries
 *   `@Deprecated(since = "2023.4")` and nothing in the UI calls it; `restApi.ts:1396-1436` drives
 *   `/v3`. They share one session store, which is what the `/v2` case asserts through `/v3`.
 * - **Secrets and secured variables have no `/v1` equivalent at all** for what is covered here:
 *   `SecretControllerV2` is the only secret controller, and `GET /v1/secured-variables` answers
 *   **410** while the default secret is disabled.
 *
 * Everything here is spec-scoped, so it belongs in `api`. The two reads that span the catalog are
 * scoped by predicate rather than by trust: the audit search filters on `ENTITY_ID` of a chain this
 * spec created, and the folder listing is taken under a folder this spec created rather than under
 * the worker folder, which every case in the worker shares.
 *
 * `POST /v2/folders/bulk-delete` was carried here as a `test.fail()` until #841: it refused and
 * rolled back whole over any folder holding a chain. It now deletes the folder and its chains, and
 * the case asserts that. The residue rule for secrets is separately enforced here rather than
 * stated, as an ordinary assertion with no register entry behind it — the rule is the suite's, not
 * the platform's.
 *
 * **Secrets are the one residue this suite may not create.** `SecretControllerV2` has create and
 * template and no delete, so a secret named after a run is permanent — and the local store is a
 * `ConcurrentHashMap` in the catalog process, which the `env` project's restart wipes, so it is
 * permanent for the length of a stack rather than of a repository. `e2e/AGENTS.md` states the
 * exemption in its residue section; `secretsCarryingRunToken` in `support/fixtures.ts` is the
 * reading, the case below is where it goes red, and `globalTeardown` prints the same reading for a
 * run this file is not part of.
 */
import { randomUUID } from "node:crypto";
import { test, expect, secretsCarryingRunToken } from "../../support/fixtures.js";
import { tokenized, SECRET_FIXTURE_NAME } from "../../support/run.js";
import { ABSENT_UUID } from "../../support/absent.js";
import { ensureSecretFixture } from "../../support/secret-fixture.js";
import type {
  Catalog,
  CatalogItemV2,
  EntityDifferenceV3,
  ImportAcknowledgeV2,
  ImportCommitResponseV3,
  ImportSessionV3,
  RolloutImportRequest,
} from "../../support/catalog.js";

/**
 * How long an asynchronous import is given to settle.
 *
 * Every poll in this file waits on the same thing: the controller answers 202 or 303 and the work
 * runs on a task executor, so a result appears when the executor reaches it. Sized off the deploy
 * budget rather than off a measurement of its own — `DEPLOY_TIMEOUT` is 60 s over a measured median
 * of 2.165 s, and an import still unsettled at half of that is stalled rather than slow.
 */
const IMPORT_TIMEOUT = 30_000;

/** The ids of a listing's rows, sorted, so the comparison does not pin an order. */
function idsOf(items: readonly CatalogItemV2[]): string[] {
  return items.map((each) => each.id).sort();
}

/** The chain document a rollout package carries, which is exactly what an export writes to disk. */
function rolloutChain(chainId: string, name: string, elementId: string, resourceName: string) {
  return {
    id: chainId,
    $schema: "http://qubership.org/schemas/product/qip/chain",
    name,
    content: {
      labels: [],
      elements: [
        {
          id: elementId,
          name: "Script",
          type: "script",
          properties: {
            exportFileExtension: "groovy",
            propertiesFilename: resourceName,
            propertiesToExportInSeparateFile: "script",
          },
        },
      ],
      deployAction: "NONE",
      migrations: "[100, 101, 102, 103, 104, 105, 106, 107, 108]",
    },
  };
}

/** Polls the v3 import session until it is done, and answers the finished document. */
async function awaitImportV3(catalog: Catalog, importId: string): Promise<ImportSessionV3> {
  let session: ImportSessionV3 | undefined;
  await expect
    .poll(
      async () => {
        const response = await catalog.importSessionV3(importId);
        if (response.status() !== 200 && response.status() !== 207) return `HTTP ${response.status()}`;
        session = (await response.json()) as ImportSessionV3;
        // Rule 12: the artifact, not the flag. `done` with no result is the shape an import that
        // imported nothing reports, and it is exactly the state a poll must not accept.
        return session.done && session.result !== undefined ? "done" : `completion ${session.completion}`;
      },
      { timeout: IMPORT_TIMEOUT, message: `import ${importId} never produced a result` },
    )
    .toBe("done");
  return session as ImportSessionV3;
}

test("the v2 folder tree creates, reads, renames, moves and deletes", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const outerName = tokenized(run, "v2tree-outer");
  const innerName = tokenized(run, "v2tree-inner");

  const outer = await catalog.createFolderV2(outerName, folder.id, "the outer one");
  expect(outer, "a v2 create answers the folder it made, flat").toMatchObject({
    name: outerName,
    description: "the outer one",
    parentId: folder.id,
    itemType: "FOLDER",
  });

  const inner = await catalog.createFolderV2(innerName, outer.id);
  expect(await catalog.getFolderV2(inner.id), "the read answers the same document as the create")
    .toMatchObject({ id: inner.id, name: innerName, parentId: outer.id, itemType: "FOLDER" });

  // The path is the ancestors **and the folder itself**, root first — so the worker folder leads it.
  const byId = await catalog.folderPathV2(inner.id);
  expect(byId.map((each) => each.id), "the path runs root first and ends at the folder asked for")
    .toEqual([folder.id, outer.id, inner.id]);
  // The by-name form is the same query addressed differently, and answering the same list is the
  // whole of its contract: nothing else distinguishes it from a search.
  expect(await catalog.folderPathByNameV2(innerName)).toEqual(byId);

  // A rename that leaves `parentId` out is a rename, not a lift to the root. Measured, and it is the
  // opposite of what the `/v1` chain update does with the same omission.
  const renamedName = tokenized(run, "v2tree-renamed");
  const renamed = await catalog.updateFolderV2(inner.id, { name: renamedName, description: "now renamed" });
  expect(renamed).toMatchObject({
    id: inner.id,
    name: renamedName,
    description: "now renamed",
    parentId: outer.id,
  });

  // The move takes its target in the **body**, where the `/v1` move takes a query parameter.
  const lifted = await catalog.moveFolderV2(inner.id, folder.id);
  expect(lifted.parentId, "the move re-parents and answers the moved folder").toBe(folder.id);
  expect((await catalog.folderPathV2(inner.id)).map((each) => each.id)).toEqual([folder.id, inner.id]);

  const pushedBack = await catalog.moveFolderV2(inner.id, outer.id);
  expect(pushedBack.parentId).toBe(outer.id);

  await catalog.deleteFolderV2(inner.id);
  const gone = await catalog.raw("get", `/v2/folders/${inner.id}`);
  expect(gone.status(), "a deleted folder is a 404, not an empty 200").toBe(404);
  // The parent is untouched by its child's delete, which is what says the delete was scoped.
  expect((await catalog.getFolderV2(outer.id)).id).toBe(outer.id);
});

test("a v2 folder listing answers one level, folders and chains together", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  // A folder of this case's own rather than the worker folder: `folder` is worker-scoped and every
  // other case in this worker puts things in it, so a listing taken there could not be asserted whole.
  const home = await catalog.createFolderV2(tokenized(run, "v2list-home"), folder.id);
  const sub = await catalog.createFolderV2(tokenized(run, "v2list-sub"), home.id);
  const beside = await catalog.createFolderV2(tokenized(run, "v2list-beside"), folder.id);

  const flatName = tokenized(run, "v2list-flat");
  const deepName = tokenized(run, "v2list-deep");
  const flat = await catalog.createChain(flatName, home.id);
  const deep = await catalog.createChain(deepName, sub.id);

  const level = await catalog.listFolderV2({ folderId: home.id });
  expect(idsOf(level), "one level: the folder and the chain directly inside, and nothing below them")
    .toEqual([sub.id, flat.id].sort());
  expect(level.find((each) => each.id === sub.id)).toMatchObject({ itemType: "FOLDER" });
  // A chain row carries `labels` and a folder row leaves the key out — the two shapes in one answer.
  expect(level.find((each) => each.id === flat.id)).toMatchObject({ itemType: "CHAIN", labels: [] });
  expect(level.find((each) => each.id === sub.id)).not.toHaveProperty("labels");

  // `searchString` filters this level by name, and matches the chain directly under it.
  expect(idsOf(await catalog.listFolderV2({ folderId: home.id, searchString: flatName })))
    .toEqual([flat.id]);
  // A name only something **below** this level carries surfaces the folder that leads to it, not
  // the match itself. That is what makes the listing a tree search rather than a filter.
  expect(idsOf(await catalog.listFolderV2({ folderId: home.id, searchString: deepName })))
    .toEqual([sub.id]);
  expect(idsOf(await catalog.listFolderV2({ folderId: home.id, searchString: tokenized(run, "v2list-nothing") })))
    .toEqual([]);

  // The controller declares an `id` query parameter and never reads it: `folderId` in the body is
  // the only thing that selects a folder. Pointed at `beside`, which holds nothing, the answer is
  // still `home`'s children — so a client written against the OpenAPI parameter reads another
  // folder's contents and nothing says so.
  expect(idsOf(await catalog.listFolderV2({ folderId: home.id }, beside.id)))
    .toEqual([sub.id, flat.id].sort());
  expect(idsOf(await catalog.listFolderV2({ folderId: beside.id })), "beside really is empty").toEqual([]);

  // The deep chain is still where it was: nothing above read it out of its folder.
  expect((await catalog.getChain(deep.id)).parentId).toBe(sub.id);
});

test("the v2 folder refusals name what they could not do", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const parent = await catalog.createFolderV2(tokenized(run, "v2bad-parent"), folder.id);
  const child = await catalog.createFolderV2(tokenized(run, "v2bad-child"), parent.id);

  const missing = await catalog.raw("get", `/v2/folders/${ABSENT_UUID}`);
  expect(missing.status()).toBe(404);
  expect(await missing.text(), "the module's own envelope, naming the id").toContain(
    `Can't find folder with id: ${ABSENT_UUID}`,
  );

  const deleted = await catalog.raw("delete", `/v2/folders/${ABSENT_UUID}`);
  expect(deleted.status(), "the delete resolves the folder before removing it").toBe(404);

  const blank = await catalog.raw("post", "/v2/folders", { name: "   ", parentId: folder.id });
  expect(blank.status()).toBe(400);
  expect(await blank.text()).toContain("name must not be blank");

  // A folder cannot be moved under its own descendant, and the refusal names both folders rather
  // than saying "invalid request".
  const cycle = await catalog.raw("post", "/v2/folders/move", { id: parent.id, targetId: child.id });
  expect(cycle.status()).toBe(400);
  const message = await cycle.text();
  expect(message).toContain(parent.name);
  expect(message).toContain(child.name);
  expect((await catalog.getFolderV2(parent.id)).parentId, "the refused move moved nothing").toBe(folder.id);

  // A name nothing carries is an empty path, not a 404: the by-name lookup is a search.
  expect(await catalog.folderPathByNameV2(tokenized(run, "v2bad-nobody"))).toEqual([]);
});

test("v2 bulk folder delete removes the folders it is given and tolerates an id it cannot find", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const first = await catalog.createFolderV2(tokenized(run, "v2bulk-first"), folder.id);
  const second = await catalog.createFolderV2(tokenized(run, "v2bulk-second"), folder.id);
  const survivor = await catalog.createFolderV2(tokenized(run, "v2bulk-survivor"), folder.id);

  const response = await catalog.bulkDeleteFoldersV2([first.id, ABSENT_UUID, second.id]);
  expect(response.status(), "an id nothing answers to does not stop the batch").toBe(204);
  expect(await response.text(), "204 carries no body").toBe("");

  expect((await catalog.raw("get", `/v2/folders/${first.id}`)).status()).toBe(404);
  expect((await catalog.raw("get", `/v2/folders/${second.id}`)).status()).toBe(404);
  expect((await catalog.getFolderV2(survivor.id)).id, "a folder not named is untouched").toBe(survivor.id);
});

test("v2 bulk folder delete removes a folder that holds a chain", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  // This case pinned a defect with `test.fail()` until #841. `FolderService.deleteByIds` deleted the
  // folder tree and *then* built an audit row per chain from `chain.getParentFolder()`, a lazy proxy
  // whose row the same transaction had just removed; the proxy threw, the transaction rolled back,
  // and the answer was a 404 naming the folder that did exist. The UI's folder delete is this
  // endpoint (`ui/src/api/rest/restApi.ts:1142`), so a folder holding a chain could not be deleted
  // from the tree at all.
  const holder = await catalog.createFolderV2(tokenized(run, "v2bulk-holder"), folder.id);
  await catalog.createChain(tokenized(run, "v2bulk-held"), holder.id);

  const deleted = await catalog.bulkDeleteFoldersV2([holder.id]);
  expect(deleted.status(), "POST /v2/folders/bulk-delete").toBe(204);
  expect((await catalog.raw("get", `/v2/folders/${holder.id}`)).status()).toBe(404);
});

test("the v2 snapshot read carries the graph the v1 read does not", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainName = tokenized(run, "v2snap-chain");
  const chain = await catalog.createChain(chainName, folder.id);
  const first = await catalog.createElement(chain.id, "script");
  const second = await catalog.createElement(chain.id, "script");
  await catalog.createDependency(chain.id, first.id, second.id);

  const snapshot = await catalog.createSnapshot(chain.id);
  const full = await catalog.snapshotFull(snapshot.id);

  expect(full.id, "addressed by the snapshot alone: no chain in the path").toBe(snapshot.id);
  expect(full.chain, "and it answers which chain it belongs to, which the v1 read does not")
    .toMatchObject({ id: chain.id, name: chainName });

  expect(full.elements.map((each) => each.type)).toEqual(["script", "script"]);
  // A snapshot is a copy with ids of its own: none of the design-time ids survives into it, which
  // is why a mock or an alert keyed on an element id has to say which of the two it means.
  const snapshotElementIds = full.elements.map((each) => each.id).sort();
  expect(snapshotElementIds).not.toContain(first.id);
  expect(snapshotElementIds).not.toContain(second.id);

  expect(full.dependencies, "the edge travels with the copy").toHaveLength(1);
  expect([full.dependencies[0].from, full.dependencies[0].to].sort(), "between the copied elements")
    .toEqual(snapshotElementIds);

  expect(full).not.toHaveProperty("xmlDefinition");

  // The v1 read is the complement rather than the predecessor: the compiled route and no graph.
  const compiled = await catalog.getSnapshot(chain.id, snapshot.id);
  expect(compiled).toHaveProperty("xmlDefinition");
  expect(compiled).not.toHaveProperty("elements");
  expect(compiled).not.toHaveProperty("dependencies");

  const missing = await catalog.raw("get", `/v2/catalog/snapshots/${ABSENT_UUID}/full`);
  expect(missing.status()).toBe(404);
  expect(await missing.text()).toContain(`Can't find configuration with id ${ABSENT_UUID}`);
});

test("v2 bulk snapshot delete removes the ids it finds and skips the ones it does not", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "v2snapbulk"), folder.id);
  await catalog.createElement(chain.id, "script");
  const first = await catalog.createSnapshot(chain.id);
  const second = await catalog.createSnapshot(chain.id);
  const current = await catalog.createSnapshot(chain.id);

  await catalog.bulkDeleteSnapshots([first.id, ABSENT_UUID]);
  expect((await catalog.listSnapshots(chain.id)).map((each) => each.id).sort(), "one gone, one skipped")
    .toEqual([second.id, current.id].sort());

  // The **current** snapshot is not protected here — `SnapshotControllerV2` swallows only the
  // not-found case — and the chain is left with no current snapshot at all rather than falling back.
  await catalog.bulkDeleteSnapshots([current.id]);
  expect((await catalog.listSnapshots(chain.id)).map((each) => each.id)).toEqual([second.id]);
  expect(await catalog.getChain(chain.id)).not.toHaveProperty("currentSnapshot");
});

test("the v2 audit search pages by offset where the v1 one takes a time window", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "v2audit"), folder.id);
  await catalog.updateChain(chain.id, tokenized(run, "v2audit-second"), undefined, folder.id);
  await catalog.updateChain(chain.id, tokenized(run, "v2audit-third"), undefined, folder.id);

  // `ENTITY_ID` rather than `ENTITY_NAME`: the renames move the name and the predicate has to hold
  // across them, which is also what makes this read spec-scoped on a table every worker writes into.
  const mine = [{ column: "ENTITY_ID", condition: "IS", value: chain.id }];

  const page = await catalog.searchActionsLogV2({ limit: 10, filters: mine });
  expect(page.actionLogs.map((each) => each.operation), "newest first, one row per write")
    .toEqual(["UPDATE", "UPDATE", "CREATE"]);
  expect(page.offset, "the answer's offset is the next page's start, not a total").toBe(3);

  const firstPage = await catalog.searchActionsLogV2({ limit: 2, offset: 0, filters: mine });
  expect(firstPage.actionLogs).toHaveLength(2);
  expect(firstPage.offset).toBe(2);
  const secondPage = await catalog.searchActionsLogV2({ limit: 2, offset: 2, filters: mine });
  expect(secondPage.actionLogs.map((each) => each.operation)).toEqual(["CREATE"]);
  expect(secondPage.offset, "a short last page advances by what it returned, not by the limit").toBe(3);
  expect(
    [...firstPage.actionLogs, ...secondPage.actionLogs].map((each) => each.id),
    "the two pages are the whole answer, with nothing repeated and nothing dropped",
  ).toEqual(page.actionLogs.map((each) => each.id));

  const past = await catalog.searchActionsLogV2({ limit: 2, offset: 10, filters: mine });
  expect(past.actionLogs, "past the end is empty rather than a wrap or a 404").toEqual([]);
  expect(past.offset).toBe(10);

  // One table read two ways: the `/v1` window search over the same filter finds the same rows. That
  // is what says the two controllers are complements rather than one superseding the other.
  const windowed = await catalog.recentActions(mine);
  expect(windowed.actionLogs.map((each) => each.id)).toEqual(page.actionLogs.map((each) => each.id));
});

test("the v2 audit search refuses a column it does not know and a condition the column does not take", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  // An unparseable column deserializes to null, so the refusal is about it being absent rather than
  // about the word that was sent — the message does not quote it.
  const unknownColumn = await catalog.searchActionsLogV2Raw({
    limit: 1,
    filters: [{ column: "NO_SUCH_COLUMN", condition: "IS", value: "x" }],
  });
  expect(unknownColumn.status()).toBe(400);
  expect(await unknownColumn.text()).toContain("Filter column is required");

  // A column that exists but does not take the condition is refused **naming what it does take**.
  const wrongCondition = await catalog.searchActionsLogV2Raw({
    limit: 1,
    filters: [{ column: "ACTION_TIME", condition: "IS", value: "1" }],
  });
  expect(wrongCondition.status()).toBe(400);
  expect(await wrongCondition.text()).toContain(
    "Filter condition IS is not supported for column ACTION_TIME. Supported conditions: [IS_AFTER, IS_BEFORE, IS_WITHIN]",
  );
});

test("the v2 common-variable import wraps what the deprecated v1 one answers bare", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const first = tokenized(run, "v2cv-first");
  const second = tokenized(run, "v2cv-second");
  const file = Buffer.from(`${first}: alpha\n${second}: beta\n`, "utf8");

  try {
    const created = await catalog.importCommonVariablesV2(file);
    expect(
      (created.variables ?? []).map((each) => ({ name: each.name, status: each.status })).sort(
        (a, b) => a.name.localeCompare(b.name),
      ),
      "the v2 body is the wrapper, and a first import reports CREATED",
    ).toEqual([
      { name: first, status: "CREATED" },
      { name: second, status: "CREATED" },
    ]);

    // The name filter selects out of the file rather than out of the catalog, and a name already
    // stored comes back UPDATED.
    const updated = await catalog.importCommonVariablesV2(file, { names: [first] });
    expect(updated.variables).toEqual([{ name: first, value: "alpha", status: "UPDATED" }]);

    // Nothing selected is the shape the two controllers disagree most about. `/v2` answers **200**
    // with `{}` — `ImportVariablesResult` is `@JsonInclude(NON_EMPTY)`, so the key is absent rather
    // than an empty list — and `/v1` answers **204** with no body at all.
    const none = await catalog.importCommonVariablesV2(file, { names: [tokenized(run, "v2cv-absent")] });
    expect(none, "an empty result serializes as {}, keys and all").toEqual({});

    const legacy = await catalog.upload("post", `/v1/common-variables/import?variablesNames=${tokenized(run, "v2cv-absent")}`, {
      multipart: { file: { name: "common-variables.yaml", mimeType: "application/x-yaml", buffer: file } },
    });
    expect(legacy.status(), "the deprecated form answers 204 for the same nothing").toBe(204);
    expect(await legacy.text()).toBe("");

    expect(await catalog.listCommonVariables()).toMatchObject({ [first]: "alpha", [second]: "beta" });
  } finally {
    // Common variables have no folder, so the run-token sweep is the only thing that would collect
    // them. Removed here so the case leaves the stack as it found it.
    await catalog.deleteCommonVariables([first, second]);
  }
});

test("deleting secured variables across secrets answers three ways", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  await ensureSecretFixture(catalog);
  const alpha = tokenized(run, "v2sv-alpha");
  const beta = tokenized(run, "v2sv-beta");
  const noSuchSecret = tokenized(run, "v2sv-nosecret");

  await catalog.addSecuredVariables(SECRET_FIXTURE_NAME, { [alpha]: "1", [beta]: "2" });

  // Every named secret found: 204, and only the variables named are gone.
  const clean = await catalog.deleteSecuredVariablesAcross({ [SECRET_FIXTURE_NAME]: [alpha] });
  expect(clean.status()).toBe(204);
  const afterClean = await catalog.securedVariablesInSecret(SECRET_FIXTURE_NAME);
  expect(afterClean).not.toContain(alpha);
  expect(afterClean, "the sibling in the same secret is untouched").toContain(beta);

  // One named secret missing: **207**, carrying one row per secret that was not found — and the
  // secret that *was* found is still processed, which is what makes this a partial success.
  const partial = await catalog.deleteSecuredVariablesAcross({
    [SECRET_FIXTURE_NAME]: [beta],
    [noSuchSecret]: ["whatever"],
  });
  expect(partial.status()).toBe(207);
  expect(await partial.json()).toEqual([
    { secretName: noSuchSecret, errorMessage: `Secret with name ${noSuchSecret} not found` },
  ]);
  expect(await catalog.securedVariablesInSecret(SECRET_FIXTURE_NAME)).not.toContain(beta);

  // No named secret found at all: the service stops calling it partial and throws, so the same
  // failure that is a 207 beside a success is a **500** on its own.
  const total = await catalog.deleteSecuredVariablesAcross({ [noSuchSecret]: ["whatever"] });
  expect(total.status()).toBe(500);
  expect(await total.text()).toContain("Failed to delete variables from multiple secrets");

  // The default secret is refused before any of that: `cip.variables.default-secret.enabled` is
  // false on this stack, and the policy check runs over every key of the request.
  const secrets = await catalog.listSecrets();
  const defaultSecret = secrets.find((each) => each.defaultSecret);
  expect(defaultSecret, "the stack still declares a default secret, disabled").toBeDefined();
  const disabled = await catalog.deleteSecuredVariablesAcross({ [defaultSecret!.secretName]: ["whatever"] });
  expect(disabled.status()).toBe(410);
  expect(await disabled.text()).toContain("Default secret functionality is disabled");
});

test("the v2 secret store round-trips, and this run leaves no secret in it", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  // `SecretControllerV2` has create and template and no delete, so this is the one entity the suite
  // creates and cannot remove. The rule that makes that survivable is that a secret is named with a
  // committed fixture name and never with the run token — and this is the assertion behind it,
  // rather than the comment `support/fixtures.ts` used to carry alone.
  await ensureSecretFixture(catalog);
  expect(
    (await catalog.listSecrets()).map((each) => each.secretName),
    "the committed fixture name is in the store, so the reading below can see a secret at all",
  ).toContain(SECRET_FIXTURE_NAME);

  expect(
    await secretsCarryingRunToken(catalog, run),
    "a secret named after this run would be permanent: name it with SECRET_FIXTURE_NAME instead",
  ).toEqual([]);
});

test("the v3 import previews, commits what it is asked for, and reports it", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const keptName = tokenized(run, "v3imp-kept");
  const droppedName = tokenized(run, "v3imp-dropped");
  const kept = await catalog.createChain(keptName, folder.id);
  const dropped = await catalog.createChain(droppedName, folder.id);
  const archive = await catalog.exportChains([kept.id, dropped.id]);

  // Rule 5: delete before importing, or "the chains are there afterwards" holds whatever happened.
  await catalog.deleteChain(kept.id);
  await catalog.deleteChain(dropped.id);

  const before = await catalog.previewImportV3(archive);
  const previewed = (before.chains ?? []).filter((each) => each.id === kept.id || each.id === dropped.id);
  expect(previewed.map((each) => each.id).sort()).toEqual([kept.id, dropped.id].sort());
  expect(
    previewed.every((each) => each.exists === false),
    "`exists` is a lookup against the catalog, and both chains have just been deleted",
  ).toBe(true);

  // `chainCommitRequests` selects out of the archive: the chain it does not name is not imported
  // and is not even reported as skipped.
  const started = await catalog.importV3(archive, {
    importRequest: { chainCommitRequests: [{ id: kept.id, deployAction: "NONE" }] },
  });
  expect(started.status(), "the import is accepted, not performed").toBe(202);
  const { importId } = (await started.json()) as ImportCommitResponseV3;
  expect(importId).toBeTruthy();

  const session = await awaitImportV3(catalog, importId);
  expect(session.result?.chains?.map((each) => ({ id: each.id, status: each.status })))
    .toEqual([{ id: kept.id, status: "CREATED" }]);
  expect(session.completion).toBe(100);

  expect((await catalog.getChain(kept.id)).name).toBe(keptName);
  expect(
    (await catalog.raw("get", `/v1/chains/${dropped.id}`)).status(),
    "the chain the request did not name was never written",
  ).toBe(404);

  // The session listing is the catalog's own record of the import, and it holds this one.
  expect(
    (await catalog.importSessionsV3()).find((each) => each.id === importId),
    "the import this case started is in the session listing",
  ).toMatchObject({ id: importId, done: true, completion: 100 });

  // The same preview after the fact, which is what makes `exists` an assertion rather than a field.
  const after = await catalog.previewImportV3(archive);
  expect((after.chains ?? []).find((each) => each.id === kept.id)?.exists).toBe(true);
  expect((after.chains ?? []).find((each) => each.id === dropped.id)?.exists).toBe(false);

  expect((await catalog.importSessionV3(ABSENT_UUID)).status(), "a session id nothing held").toBe(404);
});

test("the deprecated v2 import redirects to its own result and shares v3's session store", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chainName = tokenized(run, "v2imp-chain");
  const chain = await catalog.createChain(chainName, folder.id);
  const archive = await catalog.exportChains([chain.id]);
  await catalog.deleteChain(chain.id);

  const started = await catalog.importChainsV2(archive);
  expect(started.status()).toBe(202);
  const acknowledge = (await started.json()) as ImportAcknowledgeV2;
  expect(acknowledge.href, "the body repeats the Location it answers with").toBe(
    `/v2/import/status/${acknowledge.importId}`,
  );
  expect(started.headers().location).toBe(acknowledge.href);
  expect(started.headers()["retry-after"], "and tells the client how long to wait").toBe("60");

  // The progress read answers **303** once the import is done, and 200 with a `Retry-After` while
  // it is running — so it is polled with redirects off, or the redirect is followed and the case
  // asserts the result document while believing it asserted the status.
  let redirect: Awaited<ReturnType<typeof catalog.importStatusV2>> | undefined;
  await expect
    .poll(
      async () => {
        redirect = await catalog.importStatusV2(acknowledge.importId);
        return redirect.status();
      },
      { timeout: IMPORT_TIMEOUT, message: "the v2 import never reached its 303" },
    )
    .toBe(303);
  expect(redirect!.headers().location, "the redirect names the result, not the status again").toBe(
    `/v2/import/${acknowledge.importId}`,
  );
  expect(await redirect!.json()).toMatchObject({
    completion: 100,
    done: true,
    href: `/v2/import/${acknowledge.importId}`,
  });

  // `/v2/import/preview/{id}/status` is a second mapping on the same handler, not a preview of
  // anything: it answers the identical document.
  const alias = await catalog.importStatusV2(acknowledge.importId, { preview: true });
  expect(alias.status()).toBe(303);
  expect(await alias.json()).toEqual(await redirect!.json());

  const result = await catalog.importResultV2(acknowledge.importId);
  expect(result.status()).toBe(200);
  expect(await result.json()).toMatchObject([{ id: chain.id, name: chainName, status: "CREATED" }]);
  expect((await catalog.getChain(chain.id)).name).toBe(chainName);

  // One session store behind both controllers: the deprecated import is visible through `/v3`.
  expect(
    (await catalog.importSessionsV3()).find((each) => each.id === acknowledge.importId),
    "a v2 import is a v3 session",
  ).toMatchObject({ done: true, completion: 100 });

  expect((await catalog.importStatusV2(ABSENT_UUID)).status()).toBe(404);
  expect((await catalog.importStatusV2(ABSENT_UUID, { preview: true })).status()).toBe(404);
  expect((await catalog.importResultV2(ABSENT_UUID)).status()).toBe(404);
});

test("the v3 diff reads the catalog on the left and the archive on the right", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "v3diff"), folder.id);
  const element = await catalog.createElement(chain.id, "script");
  const archive = await catalog.exportChains([chain.id]);

  // The baseline, taken before anything has changed, and it is **not empty** — measured. A script's
  // body is exported into a file of its own, so the archive's copy carries `properties.script` read
  // back off that file while the stored row's empty body does not reach the comparison at all. What
  // says "these two are the same chain" is therefore `differing`, which is empty here, and never the
  // length of `elementsDifferences`.
  const same = await catalog.diffChainsV3(archive, { leftChainId: chain.id, rightChainId: chain.id });
  expect(same.status()).toBe(200);
  const baseline = (await same.json()) as EntityDifferenceV3;
  expect(baseline.elementsDifferences).toHaveLength(1);
  expect(baseline.elementsDifferences[0].differing, "no field of the element disagrees").toEqual([]);
  expect(baseline.elementsDifferences[0].onlyOnLeft).toEqual([]);
  expect(baseline.elementsDifferences[0].onlyOnRight, "only the externalised script body").toEqual([
    "properties.script",
  ]);

  // A rename that carries no properties, which is also what makes the two sides differ in two ways
  // at once: `PATCH .../elements/{id}` replaces the property map wholesale.
  await catalog.raw("patch", `/v1/chains/${chain.id}/elements/${element.id}`, { name: "renamed here" });

  const changed = await catalog.diffChainsV3(archive, { leftChainId: chain.id, rightChainId: chain.id });
  expect(changed.status()).toBe(200);
  const difference = (await changed.json()) as EntityDifferenceV3;

  expect(difference.leftEntity?.id, "left is the chain the catalog holds").toBe(chain.id);
  expect(difference.leftEntity, "and it is a stored row, so it carries the audit fields")
    .toHaveProperty("createdWhen");
  expect(difference.rightEntity, "right is read out of the archive and was never stored")
    .not.toHaveProperty("createdWhen");

  expect(difference.elementsDifferences).toHaveLength(1);
  const pair = difference.elementsDifferences[0];
  expect(pair.leftElement?.name, "the catalog's copy carries the rename").toBe("renamed here");
  expect(pair.rightElement?.name, "the archive's copy is what was exported before it").toBe("Script");
  expect(pair.differing).toEqual(["name"]);
  expect(pair.onlyOnRight.sort(), "the properties the rename dropped are only on the archive side")
    .toEqual([
      "properties.exportFileExtension",
      "properties.propertiesToExportInSeparateFile",
      "properties.script",
    ]);
  expect(pair.onlyOnLeft).toEqual([]);

  // Both ids are required and the refusal is built in the controller, so it names the field rather
  // than answering Spring's validation envelope.
  const incomplete = await catalog.diffChainsV3(archive, { leftChainId: chain.id });
  expect(incomplete.status()).toBe(400);
  expect(await incomplete.text()).toContain("The rightChainId must not be null");

  const noSuchLeft = await catalog.diffChainsV3(archive, {
    leftChainId: ABSENT_UUID,
    rightChainId: chain.id,
  });
  expect(noSuchLeft.status(), "the left side is looked up in the catalog and is not there").toBe(404);
});

test("the v3 extract reads the archive and nothing else", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const exportedName = tokenized(run, "v3extract");
  const chain = await catalog.createChain(exportedName, folder.id);
  await catalog.createElement(chain.id, "script");
  const archive = await catalog.exportChains([chain.id]);

  // The stored chain is renamed and emptied after the export. Everything the extract answers still
  // comes from the archive, which is the whole point of the endpoint.
  const storedName = tokenized(run, "v3extract-moved-on");
  await catalog.updateChain(chain.id, storedName, undefined, folder.id);
  await catalog.deleteElements(chain.id, (await catalog.listChainElements(chain.id)).map((each) => each.id));

  const extracted = await catalog.extractChainV3(archive, chain.id);
  expect(extracted.status()).toBe(200);
  const document = (await extracted.json()) as { id: string; name: string; elements: unknown[] };
  expect(document.id).toBe(chain.id);
  expect(document.name, "the archive's name, not the one the catalog holds now").toBe(exportedName);
  expect(document.elements, "and the archive's elements, which the catalog no longer has")
    .toHaveLength(1);
  expect((await catalog.listChainElements(chain.id)), "the catalog was not written to").toEqual([]);

  const absent = await catalog.extractChainV3(archive, ABSENT_UUID);
  expect(absent.status(), "an id the archive does not carry is a 400, not a 404").toBe(400);
  expect(await absent.text()).toContain(`Chain with id ${ABSENT_UUID} not found in the archive`);
});

test("a rollout package becomes a chain, and its resources become element properties", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  // The rollout import takes JSON rather than an archive: one configuration document per entity,
  // shaped exactly as the exporter writes it to disk, plus the files those documents point at.
  const chainId = randomUUID();
  const elementId = randomUUID();
  const chainName = tokenized(run, "rollout-chain");
  const resourceName = `script-${elementId}.groovy`;
  const script = "// written by the rollout package\n";

  const request: RolloutImportRequest = {
    id: tokenized(run, "rollout-package"),
    packageContent: {
      name: tokenized(run, "rollout-package"),
      version: "1.0.0",
      configurations: [rolloutChain(chainId, chainName, elementId, resourceName)],
      // `encoded` decides how `resourceContent` is read: base64 when true, verbatim when false.
      resources: [
        {
          id: "r1",
          name: resourceName,
          resourceContent: Buffer.from(script, "utf8").toString("base64"),
          encoded: true,
        },
      ],
    },
  };

  try {
    const accepted = await catalog.rolloutImport(tokenized(run, "rollout-snap"), request);
    expect(accepted.status(), "the work runs on an @Async method, so this is an acknowledgement").toBe(202);
    expect(await accepted.json()).toEqual({ status: "Rollout In Progress" });

    // No callback URL is sent, so the outcome is only ever observable as its effect.
    await expect
      .poll(async () => (await catalog.raw("get", `/v1/chains/${chainId}`)).status(), {
        timeout: IMPORT_TIMEOUT,
        message: "the rollout package never became a chain",
      })
      .toBe(200);
    expect((await catalog.getChain(chainId)).name).toBe(chainName);

    const elements = await catalog.listChainElements(chainId);
    expect(elements.map((each) => ({ id: each.id, type: each.type })), "the ids the package declared")
      .toEqual([{ id: elementId, type: "script" }]);
    // The resource is decoded and folded back into the property the document pointed at it with,
    // and `propertiesFilename` itself does not survive into the stored element.
    expect(elements[0].properties?.script, "the base64 resource became the element's script").toBe(
      script.trim(),
    );
    expect(elements[0].properties).not.toHaveProperty("propertiesFilename");
  } finally {
    // The chain lands in the root — a rollout document declares no folder — so the worker folder's
    // cascade does not reach it. It carries the run token, so the sweep would, but a case that
    // cleans up after itself leaves the sweep's report meaning what it says.
    await catalog.raw("delete", `/v1/chains/${chainId}`);
  }
});

test("a rollout configuration under an unknown schema is dropped, and the package imports nothing", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  // The same package as the case above, differing only in `$schema`. `ImportConfigFactory` sorts
  // configurations by that field alone and keeps none it does not recognize, so the package reaches
  // `processAsync` empty, throws `INVALID_ROLLOUT_SNAPSHOT_ERROR`, and reports that only down the
  // callback — behind a 202 that has already been answered.
  const rejectedId = randomUUID();
  const rejected = rolloutChain(
    rejectedId,
    tokenized(run, "rollout-unknown-schema"),
    randomUUID(),
    "script-unused.groovy",
  );
  rejected.$schema = "http://qubership.org/schemas/product/qip/not-a-thing";

  // A second package, well-formed, fired **after** the first. An absence is only an assertion once
  // something says the pipeline has run since; polling the rejected id alone would pass on its very
  // first read and prove nothing.
  //
  // The bound is the executor rather than a guess about the workload. `AsyncAutoConfiguration`
  // gives `@Async` a `ThreadPoolTaskExecutor` of two threads over a FIFO queue of 500, so the
  // rejected package is either already running when the control is submitted or dequeued ahead of
  // it — and the two do strictly unequal work: the rejected one throws in `ImportConfigFactory`
  // before a directory is written, where the control writes files and drives a whole import. So the
  // control having landed puts the rejected package's task behind it in both orderings.
  const controlId = randomUUID();
  const controlName = tokenized(run, "rollout-control");
  const controlElementId = randomUUID();
  const controlResource = `script-${controlElementId}.groovy`;
  const control = rolloutChain(controlId, controlName, controlElementId, controlResource);

  try {
    const refused = await catalog.rolloutImport(tokenized(run, "rollout-bad-snap"), {
      packageContent: {
        name: tokenized(run, "rollout-bad-package"),
        version: "1.0.0",
        configurations: [rejected],
        resources: [{ id: "r1", name: "script-unused.groovy", resourceContent: "// never read", encoded: false }],
      },
    });
    expect(refused.status(), "the endpoint reports progress whether or not there will be any").toBe(202);
    expect(await refused.json()).toEqual({ status: "Rollout In Progress" });

    const accepted = await catalog.rolloutImport(tokenized(run, "rollout-control-snap"), {
      packageContent: {
        name: tokenized(run, "rollout-control-package"),
        version: "1.0.0",
        configurations: [control],
        // Verbatim rather than base64, which is the other half of `encoded`.
        resources: [
          { id: "r1", name: controlResource, resourceContent: "// the control package", encoded: false },
        ],
      },
    });
    expect(accepted.status()).toBe(202);

    await expect
      .poll(async () => (await catalog.raw("get", `/v1/chains/${controlId}`)).status(), {
        timeout: IMPORT_TIMEOUT,
        message: "the control package never became a chain, so the absence below proves nothing",
      })
      .toBe(200);
    const controlElements = await catalog.listChainElements(controlId);
    expect(controlElements[0].properties?.script, "an unencoded resource is read verbatim").toBe(
      "// the control package",
    );

    expect(
      (await catalog.raw("get", `/v1/chains/${rejectedId}`)).status(),
      "the package under an unknown schema imported nothing, and said so nowhere a caller can see",
    ).toBe(404);
  } finally {
    await catalog.raw("delete", `/v1/chains/${controlId}`);
    await catalog.raw("delete", `/v1/chains/${rejectedId}`);
  }
});
