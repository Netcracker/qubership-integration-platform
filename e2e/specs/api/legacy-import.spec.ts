/**
 * The frozen legacy corpus, imported.
 *
 * One archive per migration floor, each a document the platform wrote at some point in its past and
 * has to keep accepting. `frozen-corpus.spec.ts` is what says these are still the bytes that were
 * frozen; this file is what says the platform still reads them.
 *
 * **How "migrations ran" is asserted.** Not by the import status — an import answers `CREATED`
 * whether it migrated the document or read it as-is. The evidence is the round trip: the frozen
 * `[100, 101]` document comes back out of the exporter declaring the **full current chain list**,
 * because `FileMigrationService.migrate` applies every version the document does not already
 * declare and the exporter writes what the stored chain now is. Each case also pins the one visible
 * rewrite its floor is missing — for `[100, 101]` that is `V108`, which moves `content.folder` into
 * `metaInfo.group`, and it is why that chain lands in a folder the document never names as a group.
 *
 * **The refusal is a case too.** `chain-1-30-100-105` declares `[1..30, 100..105]`, and versions
 * `1`-`30` are in no chain migration the tree ships. `FileMigrationService.migrate` reads that as an
 * export from a newer platform and refuses it, so the broadest declared list is the one archive
 * here that must **not** import. Measured: 207, with `Unable to import an entity exported from a
 * newer version`.
 *
 * **Residue.** Frozen documents carry fixed ids — an id is what makes a re-import an update rather
 * than a create, so it cannot be templated. Their names and folder paths carry `{{RUN}}` instead,
 * which is what lets the teardown sweep find them; each case deletes what it created as well, so a
 * green run leaves nothing for the sweep to do.
 */
import { test, expect } from "../../support/fixtures.js";
import JSZip from "jszip";
import yaml from "js-yaml";
import type { Catalog } from "../../support/catalog.js";
import {
  FROZEN_ARCHIVES,
  FROZEN_ARCHIVE_DIR,
  currentMigrationVersions,
  declaredFloor,
  floorKey,
  type FrozenArchive,
} from "../../fixtures/archives/frozen.js";
import { assembleFixture, readFixtureDocument, renderFixtureTree } from "../../fixtures/templating.js";
import path from "node:path";
import { tokenized } from "../../support/run.js";

function archive(name: string): FrozenArchive {
  const found = FROZEN_ARCHIVES.find((each) => each.name === name);
  if (!found) throw new Error(`no frozen archive named ${name}`);
  return found;
}

/** The frozen document as this run renders it: its id, its rendered name, and the archive bytes. */
async function frozen(entry: FrozenArchive, run: string) {
  const tree = renderFixtureTree(path.join(FROZEN_ARCHIVE_DIR, entry.name), run);
  const document = readFixtureDocument(entry.name, tree);
  return {
    id: document.id,
    name: document.document.name as string,
    bytes: await assembleFixture(entry.name, run, FROZEN_ARCHIVE_DIR),
  };
}

/** The single document inside an export, parsed. */
async function exportedDocument(bytes: Buffer): Promise<Record<string, unknown>> {
  const zip = await JSZip.loadAsync(bytes);
  const entries = Object.values(zip.files).filter((file) => !file.dir);
  expect(entries).toHaveLength(1);
  return yaml.load(await entries[0].async("string")) as Record<string, unknown>;
}

/**
 * Removes a chain and, when the import built one, the root folder it landed in.
 *
 * Both halves matter. A chain deleted on its own leaves the folder the import built for it, and a
 * root folder is exactly the residue the per-worker cascade cannot reach: measured while writing
 * this file, an import of `metaInfo.group: "a/b/c"` left `a` behind after the chain was gone.
 *
 * The folder is matched by its **exact** name rather than by a prefix. These cases run in parallel
 * and every one of their folders begins `e2e-<run>-frozen-`, so a prefix match had one case
 * deleting another's folder out from under it while that case was still reading it.
 */
async function removeChainAndFolder(
  catalog: Catalog,
  chainId: string,
  folderName?: string,
): Promise<void> {
  await catalog.deleteChain(chainId).catch(() => {});
  if (folderName === undefined) return;
  for (const item of await catalog.listRootItems().catch(() => [])) {
    if (item.itemType === "FOLDER" && item.name === folderName) {
      await catalog.deleteFolder(item.id).catch(() => {});
    }
  }
}

test("a [100, 101] chain imports, and the migrations it was missing have run", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const entry = archive("chain-100-101");
  const { id, name, bytes } = await frozen(entry, run);

  try {
    // Delete before importing. The id is frozen, so an interrupted earlier run can leave it live,
    // and an import over a live id is an update that would report success either way.
    await catalog.deleteChain(id).catch(() => {});

    const response = await catalog.importChains(bytes);
    expect(response.status()).toBe(200);
    expect((await response.json()).chains).toEqual([
      expect.objectContaining({ id, name, status: "CREATED" }),
    ]);

    // V108 is the visible one: the frozen document carries `content.folder`, the current format
    // carries `metaInfo.group`, and the migration is what turns one into the other. The chain
    // landing in a root folder of that name is the only observable that says it ran.
    const chain = await catalog.getChain(id);
    const parent = await catalog.getFolder(chain.parentId as string);
    expect(parent.name).toBe(tokenized(run, "frozen-100-101"));
    expect(parent.parentId ?? null).toBeNull();

    const exported = await exportedDocument(await catalog.exportChain(id));
    expect((exported.metaInfo as { group?: string })?.group).toBe(tokenized(run, "frozen-100-101"));
    expect(exported.content).not.toHaveProperty("folder");
    // The whole current list, not merely more than it declared: this is the assertion that goes
    // red when a `V109` lands, which is the moment a new floor has to be frozen.
    expect(declaredFloor(exported)).toEqual(currentMigrationVersions("chain"));
    expect(floorKey(declaredFloor(exported))).not.toBe(floorKey(entry.floor));
  } finally {
    await removeChainAndFolder(catalog, id, tokenized(run, "frozen-100-101"));
  }
});

test("a [100, 101, 108] chain imports with its group path intact", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const entry = archive("chain-100-101-108");
  const { id, name, bytes } = await frozen(entry, run);

  try {
    await catalog.deleteChain(id).catch(() => {});

    const response = await catalog.importChains(bytes);
    expect(response.status()).toBe(200);
    expect((await response.json()).chains).toEqual([
      expect.objectContaining({ id, name, status: "CREATED" }),
    ]);

    // This floor has already been through V108, so its folder path travels as `metaInfo.group` and
    // the importer rebuilds it segment by segment. Three segments, three folders.
    const chain = await catalog.getChain(id);
    const inner = await catalog.getFolder(chain.parentId as string);
    const middle = await catalog.getFolder(inner.parentId as string);
    const root = await catalog.getFolder(middle.parentId as string);
    expect([root.name, middle.name, inner.name]).toEqual([
      tokenized(run, "frozen-100-101-108"),
      "a",
      "b",
    ]);
    expect(root.parentId ?? null).toBeNull();

    const exported = await exportedDocument(await catalog.exportChain(id));
    expect((exported.metaInfo as { group?: string })?.group).toBe(
      `${tokenized(run, "frozen-100-101-108")}/a/b`,
    );
    expect(declaredFloor(exported)).toEqual(currentMigrationVersions("chain"));
  } finally {
    await removeChainAndFolder(catalog, id, tokenized(run, "frozen-100-101-108"));
  }
});

test("a [100, 101, 102, 103] chain imports with every element it declared", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const entry = archive("chain-100-101-102-103");
  const { id, name, bytes } = await frozen(entry, run);

  try {
    await catalog.deleteChain(id).catch(() => {});

    const response = await catalog.importChains(bytes);
    expect(response.status()).toBe(200);
    expect((await response.json()).chains).toEqual([
      expect.objectContaining({ id, name, status: "CREATED" }),
    ]);

    // The floor closest to the current list, and the one whose payload is worth counting: five
    // `context-storage` elements, each with an operation a later migration could have dropped.
    const elements = await catalog.listChainElements(id);
    expect(elements.map((element) => element.type)).toEqual(Array(5).fill("context-storage"));
    expect(
      elements.map((element) => element.properties?.operation).sort(),
    ).toEqual(["DELETE", "GET", "GET", "GET", "SET"]);

    const exported = await exportedDocument(await catalog.exportChain(id));
    expect(declaredFloor(exported)).toEqual(currentMigrationVersions("chain"));
  } finally {
    // No folder: this document declares neither `content.folder` nor `metaInfo.group`, so the
    // chain lands at the root and its name alone is what the sweep would find.
    await removeChainAndFolder(catalog, id);
  }
});

test("a chain declaring versions the platform does not ship is refused, and says why", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const entry = archive("chain-1-30-100-105");
  const { id, bytes } = await frozen(entry, run);

  try {
    await catalog.deleteChain(id).catch(() => {});

    const response = await catalog.importChains(bytes);

    // 207, not 400 and not 500: the import reports per row, and one failed row is what makes the
    // whole response multi-status.
    expect(response.status()).toBe(207);
    const rows = (await response.json()).chains;
    expect(rows).toEqual([
      expect.objectContaining({
        id,
        status: "ERROR",
        errorMessage: expect.stringContaining("Unable to import an entity exported from a newer version"),
      }),
    ]);
    // And it wrote nothing. A refusal that half-created the chain would still report ERROR.
    expect((await catalog.raw("get", `/v1/chains/${id}`)).status()).toBe(404);
  } finally {
    await removeChainAndFolder(catalog, id, tokenized(run, "frozen-1-30-100-105"));
  }
});

test("a [100, 101] service imports through its own endpoint and its own migration list", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const entry = archive("service-100-101");
  const { id, name, bytes } = await frozen(entry, run);

  try {
    await catalog.deleteSystem(id).catch(() => {});

    // Services do not share the chain import path, and neither prefix answers on the other's.
    const response = await catalog.importArchive("/v1/import/system", bytes, "services.zip");
    expect(response.status()).toBe(200);
    expect(await response.json()).toEqual([
      expect.objectContaining({ id, name, status: "CREATED" }),
    ]);

    // The environments are the payload: a service document carries them inline rather than as
    // separate entities, so an import that dropped them would still report the service created.
    const environments = await catalog.listEnvironments(id);
    expect(environments.map((environment) => environment.address).sort()).toEqual([
      "http://petstore.swagger.io/v2",
      "https://petstore.swagger.io/v2",
    ]);

    const exported = await exportedDocument(await catalog.exportSystems([id]));
    // Against the **service** list, `[100, 101, 102]`. Reading a service floor against the chain
    // list would call this document three versions out of date.
    expect(declaredFloor(exported)).toEqual(currentMigrationVersions("service"));
    expect(floorKey(declaredFloor(exported))).not.toBe(floorKey(entry.floor));
  } finally {
    await catalog.deleteSystem(id).catch(() => {});
  }
});
