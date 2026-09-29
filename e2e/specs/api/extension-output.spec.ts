/**
 * The catalog imports the files the VS Code extension writes.
 *
 * `vscode-extension/src/web/test/golden/` holds files the extension wrote in its own integration
 * suite, and that suite fails once the extension writes other bytes. This spec imports the same
 * bytes, so a change on either side of the contract fails one of the two suites:
 *
 * - the fixture chain after an `updateChain` change, zipped with the fixture's Groovy resource
 *   from `vscode-extension/src/web/test/workspace/`;
 * - a chain from `qip.createChain`;
 * - an `EXTERNAL`, a `CONTEXT`, and an `MCP` service from `qip.createService`, one per import
 *   endpoint.
 *
 * Only the changed chain imports as written. The catalog refuses the created chain and the external
 * and MCP services, and it imports the context service without its content. Those four cases pin
 * the defect with `test.fail()`, and `docs/product-defects.md` files it under the VS Code extension
 * section.
 *
 * The golden files carry fixed ids, which the spec reads off their file names, so the cases delete
 * whatever holds an id before importing, and two suite runs against one stack would import the same
 * entities.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual } from "node:util";
import yaml from "js-yaml";
import type { APIResponse } from "@playwright/test";
import type { Catalog } from "../../support/catalog.js";
import { test, expect } from "../../support/fixtures.js";
import { notTheKnownDefect, outsideTheDefect } from "../../support/known-defect.js";
import { leftBehind } from "../../support/teardown.js";
import { archiveOf, entryText, zipOf } from "../../support/zip.js";

const EXTENSION = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../vscode-extension/src/web/test");
const GOLDEN = path.join(EXTENSION, "golden");

/** Whether the extension's test workspace holds chain `id`: the changed chain is there, and the created one is not. */
function inWorkspace(id: string): boolean {
  return fs.existsSync(path.join(EXTENSION, "workspace/chains", id));
}

/** The id of the one golden file whose name ends in `suffix` and whose id passes `which`. */
function goldenId(suffix: string, which: (id: string) => boolean = () => true): string {
  const ids = fs.readdirSync(GOLDEN).filter((name) => name.endsWith(suffix)).map((name) => name.slice(0, -suffix.length)).filter(which);
  if (ids.length !== 1) throw new Error(`${GOLDEN} holds ${ids.length} matching *${suffix} files, not one: ${ids.join(", ")}`);
  return ids[0];
}

const CHANGED_CHAIN = goldenId(".chain.qip.yaml", inWorkspace);
const CREATED_CHAIN = goldenId(".chain.qip.yaml", (id) => !inWorkspace(id));
const EXTERNAL = goldenId(".service.qip.yaml");
const CONTEXT = goldenId(".context-service.qip.yaml");
const MCP = goldenId(".mcp-service.qip.yaml");

/** Deletes what the catalog holds at `location` with `remove`, if it holds anything there. */
async function removeIfPresent(catalog: Catalog, location: string, remove: () => Promise<void>): Promise<void> {
  if (await catalog.holds(location)) await remove();
}

function goldenText(fileName: string): string {
  return fs.readFileSync(path.join(GOLDEN, fileName), "utf8");
}

/** A chain archive holding one golden chain file, plus the resources its elements name. */
async function chainArchive(id: string, resources: Record<string, string> = {}): Promise<Buffer> {
  const entries: Record<string, string> = { [`chains/${id}/${id}.chain.qip.yaml`]: goldenText(`${id}.chain.qip.yaml`) };
  for (const [name, text] of Object.entries(resources)) entries[`chains/${id}/resources/${name}`] = text;
  return await zipOf(Object.entries(entries));
}

async function serviceArchive(id: string, suffix: string): Promise<Buffer> {
  return await zipOf([[`services/${id}/${id}${suffix}`, goldenText(`${id}${suffix}`)]]);
}

interface Service {
  content: Record<string, unknown>;
}

interface ImportRow {
  id: string;
  status: string;
  message?: string;
  errorMessage?: string;
}

/** The rows of an import answer: a bare array for services, `{chains}` for chains. */
async function rowsOf(response: APIResponse): Promise<ImportRow[]> {
  const body = await response.json().catch(() => null);
  return ((Array.isArray(body) ? body : body?.chains) as ImportRow[] | undefined) ?? [];
}

test("a chain the extension changed imports into the catalog, and the catalog exports the same document", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const script = "54b4728d-7000-4350-b310-c58b792311c5.element.script.cip.groovy";
  const resource = fs.readFileSync(path.join(EXTENSION, "workspace/chains", CHANGED_CHAIN, "resources", script), "utf8");
  const archive = await chainArchive(CHANGED_CHAIN, { [script]: resource });
  const location = `/v1/chains/${CHANGED_CHAIN}`;
  try {
    await removeIfPresent(catalog, location, () => catalog.deleteChain(CHANGED_CHAIN));

    const response = await catalog.importChains(archive);
    expect(response.status(), await response.text()).toBe(200);
    expect(await rowsOf(response)).toEqual([expect.objectContaining({ id: CHANGED_CHAIN, status: "CREATED" })]);

    const exported = await catalog.exportChain(CHANGED_CHAIN);
    const document = yaml.load(await entryText(exported, `chains/${CHANGED_CHAIN}/${CHANGED_CHAIN}.chain.qip.yaml`));
    expect(document, "the catalog's export of the imported chain").toEqual(yaml.load(goldenText(`${CHANGED_CHAIN}.chain.qip.yaml`)));
    expect(await entryText(exported, `chains/${CHANGED_CHAIN}/resources/${script}`)).toBe(resource);
  } finally {
    await removeIfPresent(catalog, location, () => catalog.deleteChain(CHANGED_CHAIN)).catch(leftBehind(`chain ${CHANGED_CHAIN}`));
  }
});

test.fail("a context service the extension created imports into the catalog with its content", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  // docs/product-defects.md: qip.createService writes `migrations: []`, and the import drops the content.
  const suffix = ".context-service.qip.yaml";
  const location = `/v1/catalog/context-system/${CONTEXT}`;
  try {
    const archive = await outsideTheDefect("building the archive", async () => {
      await removeIfPresent(catalog, location, () => catalog.deleteContextSystem(CONTEXT));
      return await serviceArchive(CONTEXT, suffix);
    });
    const response = await outsideTheDefect(`the import of ${CONTEXT}`, () => catalog.importArchive("/v1/catalog/context-system/import", archive));
    const rows = await rowsOf(response);
    if (response.status() !== 200 || rows.length !== 1 || rows[0].status !== "CREATED") {
      notTheKnownDefect(`the import answered ${response.status()} with ${JSON.stringify(rows)}, where the defect imports the service`);
    }

    const { text, content, imported, expected } = await outsideTheDefect(`the export of ${CONTEXT}`, async () => {
      const text = await entryText(await archiveOf(await catalog.exportContextSystems([CONTEXT])), `services/${CONTEXT}/${CONTEXT}${suffix}`);
      // The catalog writes the migrations it applied, where the extension wrote an empty list.
      const { content: { migrations: _exported, ...content }, ...document } = yaml.load(text) as Service;
      const { content: { migrations: _created, ...createdContent }, ...created } = yaml.load(goldenText(`${CONTEXT}${suffix}`)) as Service;
      return { text, content, imported: { ...document, content }, expected: { ...created, content: createdContent } };
    });
    // The defect leaves an empty content; any other difference is not the one this case pins.
    if (Object.keys(content).length > 0 && !isDeepStrictEqual(imported, expected)) {
      notTheKnownDefect(`the catalog exported ${text}`);
    }
    expect(imported, "the catalog keeps the content now: delete the test.fail() annotation").toEqual(expected);
  } finally {
    await removeIfPresent(catalog, location, () => catalog.deleteContextSystem(CONTEXT)).catch(leftBehind(`context service ${CONTEXT}`));
  }
});

/** The files the catalog refuses, each with the endpoint that imports it and the message it refuses with. */
const REFUSED = [
  {
    title: "a chain the extension created imports into the catalog",
    // docs/product-defects.md: the chain qip.createChain writes carries no migrations.
    id: CREATED_CHAIN,
    archive: () => chainArchive(CREATED_CHAIN),
    importPath: "/v1/catalog/import",
    location: `/v1/chains/${CREATED_CHAIN}`,
    remove: (catalog: Catalog) => catalog.deleteChain(CREATED_CHAIN),
    message: "Failed to retrieve migration data",
  },
  {
    title: "an external service the extension created imports into the catalog",
    // docs/product-defects.md: qip.createService writes `migrations: []`, and the import drops the content.
    id: EXTERNAL,
    archive: () => serviceArchive(EXTERNAL, ".service.qip.yaml"),
    importPath: "/v1/import/system",
    location: `/v1/systems/${EXTERNAL}`,
    remove: (catalog: Catalog) => catalog.deleteSystem(EXTERNAL),
    message: "Service type is not specified",
  },
  {
    title: "an MCP service the extension created imports into the catalog",
    // docs/product-defects.md: qip.createService writes an MCP service's migrations as a list.
    id: MCP,
    archive: () => serviceArchive(MCP, ".mcp-service.qip.yaml"),
    importPath: "/v1/catalog/mcp-system/import",
    location: `/v1/catalog/mcp-system/${MCP}`,
    remove: (catalog: Catalog) => catalog.deleteMcpSystem(MCP),
    message: "Cannot deserialize value of type `java.lang.String` from Array value",
  },
];

for (const each of REFUSED) {
  test.fail(each.title, { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
    try {
      const archive = await outsideTheDefect("building the archive", async () => {
        await removeIfPresent(catalog, each.location, () => each.remove(catalog));
        return await each.archive();
      });
      const response = await outsideTheDefect(`the import of ${each.id}`, () => catalog.importArchive(each.importPath, archive));
      const status = response.status();
      const rows = await rowsOf(response);
      const row = rows.length === 1 ? rows[0] : undefined;
      const refused = status === 207 && row?.status === "ERROR" && (row.message ?? row.errorMessage ?? "").includes(each.message);
      if (!refused && !(status === 200 && row?.status === "CREATED")) {
        notTheKnownDefect(`the import of ${each.id} answered ${status} with ${JSON.stringify(rows)}, which is neither the known refusal nor a fix`);
      }
      expect({ status, row: row?.status }, "the catalog imports it now: delete the test.fail() annotation").toEqual({ status: 200, row: "CREATED" });
    } finally {
      await removeIfPresent(catalog, each.location, () => each.remove(catalog)).catch(leftBehind(each.location));
    }
  });
}
