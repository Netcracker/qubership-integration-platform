/**
 * The catalog imports the files the VS Code extension writes.
 *
 * `vscode-extension/src/web/test/golden/` holds eight files the extension wrote in its own
 * integration suite, and that suite fails once the extension writes other bytes. This spec imports
 * seven of them, so a change on either side of the contract fails one of the two suites:
 *
 * - the fixture chain after an `updateChain` change, zipped with the fixture's Groovy resource
 *   from `vscode-extension/src/web/test/workspace/`;
 * - the fixture context and MCP services after a name change;
 * - a chain from `qip.createChain`;
 * - an `EXTERNAL`, a `CONTEXT`, and an `MCP` service from `qip.createService`, one per import
 *   endpoint.
 *
 * A created file carries no `migrations`, so the catalog reads it as current and runs no migration on
 * it. Each case imports the file and checks that the catalog's export keeps the name and the
 * description the extension wrote. Migration 101 of a chain or a service, run on a file already in
 * the current format, nests its `content` a second time, and the description is then lost.
 *
 * The extension round-trips the workspace's INTERNAL service too, and that golden has no case here.
 * `specs/api/service-roundtrip.spec.ts` owns `3b2e9742-139c-4750-bb76-adaf1cc9e3f0` in this same
 * parallel-safe project: a one-document service imported under that id would race that case's own
 * import and export assertions, and each teardown would delete the other's subject. The extension
 * suite checks that golden on its own.
 *
 * The golden files carry fixed ids, which the spec reads off their file names, so the cases delete
 * whatever holds an id before importing, and two suite runs against one stack would import the same
 * entities.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";
import type { APIResponse } from "@playwright/test";
import type { Catalog } from "../../support/catalog.js";
import { test, expect } from "../../support/fixtures.js";
import { leftBehind } from "../../support/teardown.js";
import { archiveOf, entryText, zipOf } from "../../support/zip.js";

const EXTENSION = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../vscode-extension/src/web/test");
const GOLDEN = path.join(EXTENSION, "golden");

/**
 * Whether the extension's test workspace holds `id`, as a chain or as a service. A golden the
 * extension wrote by changing a workspace document carries that document's id; one it wrote by
 * creating a document carries an id no fixture has.
 */
function inWorkspace(id: string): boolean {
  return ["chains", "services"].some((kind) => fs.existsSync(path.join(EXTENSION, "workspace", kind, id)));
}

/** The id of the one golden file whose name ends in `suffix` and whose id passes `which`. */
function goldenId(suffix: string, which: (id: string) => boolean = () => true): string {
  const ids = fs.readdirSync(GOLDEN).filter((name) => name.endsWith(suffix)).map((name) => name.slice(0, -suffix.length)).filter(which);
  if (ids.length !== 1) throw new Error(`${GOLDEN} holds ${ids.length} matching *${suffix} files, not one: ${ids.join(", ")}`);
  return ids[0];
}

const CHANGED_CHAIN = goldenId(".chain.cip.yaml", inWorkspace);
const CREATED_CHAIN = goldenId(".chain.cip.yaml", (id) => !inWorkspace(id));
const CHANGED_CONTEXT = goldenId(".context-service.cip.yaml", inWorkspace);
const CHANGED_MCP = goldenId(".mcp-service.cip.yaml", inWorkspace);
// Every service suffix matches a created golden and a round-tripped one, so each of these three
// takes the negated predicate: without it `goldenId` throws on the second match.
const EXTERNAL = goldenId(".service.cip.yaml", (id) => !inWorkspace(id));
const CONTEXT = goldenId(".context-service.cip.yaml", (id) => !inWorkspace(id));
const MCP = goldenId(".mcp-service.cip.yaml", (id) => !inWorkspace(id));

/** Deletes what the catalog holds at `location` with `remove`, if it holds anything there. */
async function removeIfPresent(catalog: Catalog, location: string, remove: () => Promise<void>): Promise<void> {
  if (await catalog.holds(location)) await remove();
}

function goldenText(fileName: string): string {
  return fs.readFileSync(path.join(GOLDEN, fileName), "utf8");
}

/** A chain archive holding one golden chain file, plus the resources its elements name. */
async function chainArchive(id: string, resources: Record<string, string> = {}): Promise<Buffer> {
  const entries: Record<string, string> = { [`chains/${id}/${id}.chain.cip.yaml`]: goldenText(`${id}.chain.cip.yaml`) };
  for (const [name, text] of Object.entries(resources)) entries[`chains/${id}/resources/${name}`] = text;
  return await zipOf(Object.entries(entries));
}

async function serviceArchive(id: string, suffix: string): Promise<Buffer> {
  return await zipOf([[`services/${id}/${id}${suffix}`, goldenText(`${id}${suffix}`)]]);
}

interface ImportRow {
  id: string;
  status: string;
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
    const document = yaml.load(await entryText(exported, `chains/${CHANGED_CHAIN}/${CHANGED_CHAIN}.chain.cip.yaml`));
    expect(document, "the catalog's export of the imported chain").toEqual(yaml.load(goldenText(`${CHANGED_CHAIN}.chain.cip.yaml`)));
    expect(await entryText(exported, `chains/${CHANGED_CHAIN}/resources/${script}`)).toBe(resource);
  } finally {
    await removeIfPresent(catalog, location, () => catalog.deleteChain(CHANGED_CHAIN)).catch(leftBehind(`chain ${CHANGED_CHAIN}`));
  }
});

/** The round-tripped service goldens, each with the endpoint that imports it. */
const CHANGED_SERVICES = [
  {
    title: "a context service the extension round-tripped imports into the catalog",
    id: CHANGED_CONTEXT,
    archive: () => serviceArchive(CHANGED_CONTEXT, ".context-service.cip.yaml"),
    importPath: "/v1/catalog/context-system/import",
    location: `/v1/catalog/context-system/${CHANGED_CONTEXT}`,
    remove: (catalog: Catalog) => catalog.deleteContextSystem(CHANGED_CONTEXT),
  },
  {
    title: "an MCP service the extension round-tripped imports into the catalog",
    id: CHANGED_MCP,
    archive: () => serviceArchive(CHANGED_MCP, ".mcp-service.cip.yaml"),
    importPath: "/v1/catalog/mcp-system/import",
    location: `/v1/catalog/mcp-system/${CHANGED_MCP}`,
    remove: (catalog: Catalog) => catalog.deleteMcpSystem(CHANGED_MCP),
  },
];

for (const each of CHANGED_SERVICES) {
  test(each.title, { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
    try {
      await removeIfPresent(catalog, each.location, () => each.remove(catalog));

      const response = await catalog.importArchive(each.importPath, await each.archive());
      expect(response.status(), await response.text()).toBe(200);
      expect(await rowsOf(response)).toEqual([expect.objectContaining({ id: each.id, status: "CREATED" })]);
    } finally {
      await removeIfPresent(catalog, each.location, () => each.remove(catalog)).catch(leftBehind(each.location));
    }
  });
}

interface Document {
  id: string;
  name: string;
  content: { description?: string };
}

/** The fields of `document` that a migration run on a current file would lose. */
function keptFields(document: Document): Record<string, unknown> {
  const { description } = document.content;
  return { id: document.id, name: document.name, content: description === undefined ? {} : { description } };
}

/** The files the extension creates, each with the endpoint that imports it and the one that exports it. */
const CREATED = [
  {
    title: "a chain the extension created imports into the catalog",
    id: CREATED_CHAIN,
    entry: `chains/${CREATED_CHAIN}/${CREATED_CHAIN}.chain.cip.yaml`,
    archive: () => chainArchive(CREATED_CHAIN),
    importPath: "/v1/catalog/import",
    location: `/v1/chains/${CREATED_CHAIN}`,
    remove: (catalog: Catalog) => catalog.deleteChain(CREATED_CHAIN),
    export: (catalog: Catalog) => catalog.exportChain(CREATED_CHAIN),
  },
  {
    title: "an external service the extension created imports into the catalog",
    id: EXTERNAL,
    entry: `services/${EXTERNAL}/${EXTERNAL}.service.cip.yaml`,
    archive: () => serviceArchive(EXTERNAL, ".service.cip.yaml"),
    importPath: "/v1/import/system",
    location: `/v1/systems/${EXTERNAL}`,
    remove: (catalog: Catalog) => catalog.deleteSystem(EXTERNAL),
    export: (catalog: Catalog) => catalog.exportSystems([EXTERNAL]),
  },
  {
    title: "a context service the extension created imports into the catalog",
    id: CONTEXT,
    entry: `services/${CONTEXT}/${CONTEXT}.context-service.cip.yaml`,
    archive: () => serviceArchive(CONTEXT, ".context-service.cip.yaml"),
    importPath: "/v1/catalog/context-system/import",
    location: `/v1/catalog/context-system/${CONTEXT}`,
    remove: (catalog: Catalog) => catalog.deleteContextSystem(CONTEXT),
    export: async (catalog: Catalog) => await archiveOf(await catalog.exportContextSystems([CONTEXT])),
  },
  {
    title: "an MCP service the extension created imports into the catalog",
    id: MCP,
    entry: `services/${MCP}/${MCP}.mcp-service.cip.yaml`,
    archive: () => serviceArchive(MCP, ".mcp-service.cip.yaml"),
    importPath: "/v1/catalog/mcp-system/import",
    location: `/v1/catalog/mcp-system/${MCP}`,
    remove: (catalog: Catalog) => catalog.deleteMcpSystem(MCP),
    export: (catalog: Catalog) => catalog.exportMcpSystems([MCP]),
  },
];

for (const each of CREATED) {
  test(each.title, { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
    try {
      await removeIfPresent(catalog, each.location, () => each.remove(catalog));

      const response = await catalog.importArchive(each.importPath, await each.archive());
      expect(response.status(), await response.text()).toBe(200);
      expect(await rowsOf(response)).toEqual([expect.objectContaining({ id: each.id, status: "CREATED" })]);

      const exported = yaml.load(await entryText(await each.export(catalog), each.entry));
      const written = yaml.load(goldenText(path.basename(each.entry))) as Document;
      expect(exported, "the catalog's export of the imported file").toMatchObject(keptFields(written));
    } finally {
      await removeIfPresent(catalog, each.location, () => each.remove(catalog)).catch(leftBehind(each.location));
    }
  });
}
