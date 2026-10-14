/**
 * The catalog imports the files the VS Code extension writes.
 *
 * `vscode-extension/src/web/test/golden/` holds eight files at its top level, written by the
 * extension's own integration suite, and that suite fails once the extension writes other bytes.
 * This spec imports seven of them, so a change on either side of the contract fails one of the two
 * suites:
 *
 * - the fixture chain after an `updateChain` change, zipped with the fixture's Groovy resource
 *   from `vscode-extension/src/web/test/workspace/`;
 * - the fixture context and MCP services after a name change;
 * - a chain from `qip.createChain`;
 * - an `EXTERNAL`, a `CONTEXT`, and an `MCP` service from `qip.createService`, one per import
 *   endpoint.
 *
 * `golden/import/` holds three more trees, one per protocol these cases cover: SOAP, gRPC, and
 * GraphQL. The import itself covers five. `detectProtocolFromParsedContent` returns HTTP for an
 * OpenAPI or Swagger document and an async protocol for an AsyncAPI one, and
 * `SpecificationProcessorService` dispatches both, but OpenAPI and AsyncAPI stay at parser-level
 * unit coverage in `vscode-extension/` and have no import case at this level. These thirteen files
 * are written and byte-compared by the Jest cases in `vscode-extension/tests/web/import/`, not by
 * the extension's integration suite, whose `require.context` over `golden/` is non-recursive and
 * never loads them. Each tree is the state of a service folder after an import, so it carries the
 * service document, the specification group, the specification, and the source files under
 * `resources/source-<specificationId>/`. The last three cases of this spec walk one tree into
 * `services/<serviceId>/` and import it, so their ids come from the service document inside the
 * tree rather than from a file name.
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
 * Every golden carries fixed ids, so each case deletes whatever holds its id before importing, and
 * two suite runs against one stack import the same entities. The top-level goldens are addressed by
 * the ids in their file names; an `import/` tree is addressed by the id in its service document.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";
import type { APIResponse } from "@playwright/test";
import type { Catalog } from "../../support/catalog.js";
import { test, expect } from "../../support/fixtures.js";
import { leftBehind } from "../../support/teardown.js";
import { archiveOf, entryNames, entryText, zipOf } from "../../support/zip.js";

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

// ---------------------------------------------------------------------------
// The specification imports under golden/import/
// ---------------------------------------------------------------------------

const IMPORT_GOLDEN = path.join(GOLDEN, "import");

/**
 * Every file under `dir`, as a `/`-separated path relative to it, sorted. The separator is written
 * out rather than taken from `path.join`, because these strings become zip entry names and the
 * export's expected entry names, and a zip entry name is `/`-separated on every platform.
 */
function filesUnder(dir: string, within = ""): string[] {
  return fs
    .readdirSync(path.join(dir, within), { withFileTypes: true })
    .flatMap((entry) => {
      const relative = within ? `${within}/${entry.name}` : entry.name;
      return entry.isDirectory() ? filesUnder(dir, relative) : [relative];
    })
    .sort();
}

interface GoldenDocument<Content> {
  id: string;
  name: string;
  content: Content;
}

interface GoldenService {
  integrationSystemType: string;
  environments: Array<{ id: string; name: string; address: string }>;
}

interface GoldenSpecification {
  version: string;
  operations: Array<{ id: string; name: string; method: string }>;
  specificationSources: Array<{ name: string; fileName: string }>;
}

/** One protocol's golden import: the files the extension wrote into a service folder. */
interface ImportGolden {
  protocol: string;
  serviceId: string;
  /** Relative to `golden/import/<protocol>/`; the archive mirrors them under `services/<id>/`. */
  entries: string[];
  service: GoldenDocument<GoldenService>;
  group: GoldenDocument<unknown>;
  specification: GoldenDocument<GoldenSpecification>;
}

/**
 * One golden tree, read at module scope: `npx playwright test --list` therefore exercises the walk
 * and the three yaml reads without a stack.
 *
 * The tree must hold exactly one service, one specification-group and one specification document,
 * which is what an import of one file into one new group writes. A tree that gains a second
 * specification fails the whole file's collection rather than one case, so split it into a
 * directory of its own or give this function the second document.
 */
function importGolden(protocol: string): ImportGolden {
  const directory = path.join(IMPORT_GOLDEN, protocol);
  const entries = filesUnder(directory);
  function read<Content>(suffix: string): GoldenDocument<Content> {
    const names = entries.filter((entry) => entry.endsWith(suffix));
    if (names.length !== 1) throw new Error(`${directory} holds ${names.length} *${suffix} files, not one: ${names.join(", ")}`);
    return yaml.load(fs.readFileSync(path.join(directory, names[0]), "utf8")) as GoldenDocument<Content>;
  }
  const service = read<GoldenService>(".service.cip.yaml");
  return {
    protocol,
    serviceId: service.id,
    entries,
    service,
    group: read(".specification-group.cip.yaml"),
    specification: read<GoldenSpecification>(".specification.cip.yaml"),
  };
}

const IMPORTED = ["soap", "grpc", "graphql"].map(importGolden);

/**
 * Each case reads what it expects out of the golden documents rather than out of a list copied
 * beside them, so a regenerated golden moves this spec with the extension's own suite.
 *
 * **None of the three cases below has ever run.** They were written against a stack that was down,
 * so every assertion in them is unproven, and the first failure to read is a wrong expectation here
 * as readily as a defect in the catalog.
 *
 * The export is read for the archive layout and the source bytes, and not compared document by
 * document against the golden. The extension writes `extendedProtocol` and `specification` into a
 * service document, and `IntegrationSystemContentDto` declares neither, so the export cannot carry
 * them back; `@JsonInclude(NON_EMPTY)` on that class and on `SystemModelContentDto` drops
 * `description: ""`, `labels: []`, and GraphQL's `activeEnvironmentId: ""` as well. The stronger
 * comparison the chain case above makes would therefore fail on a format difference rather than on
 * anything either side got wrong.
 *
 * The generated request and response schemas, which are the bulk of each golden specification, are
 * left out for the same reason and are pinned byte for byte at level 1 instead
 * (`vscode-extension/tests/web/import/`). `GET /v1/operations/{id}/info` answers draft-07 documents
 * the catalog derived, with a `$schema` and an `$id` the source never had, so an equality against
 * the golden would assert the catalog's own derivation. Once these cases have run, a reader who
 * wants the schemas here has `Catalog.operationInfo` and the `specification` fragment on
 * `GET /v1/operations/{id}`, which is what the extension wrote.
 */
for (const golden of IMPORTED) {
  test(`the ${golden.protocol} specification the extension imported into a service imports into the catalog`, { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
    const archive = await zipOf(
      golden.entries.map((entry) => [
        `services/${golden.serviceId}/${entry}`,
        fs.readFileSync(path.join(IMPORT_GOLDEN, golden.protocol, entry), "utf8"),
      ]),
    );
    const location = `/v1/systems/${golden.serviceId}`;
    try {
      await removeIfPresent(catalog, location, () => catalog.deleteSystem(golden.serviceId));

      const response = await catalog.importArchive("/v1/import/system", archive, `${golden.protocol}.zip`);
      expect(response.status(), await response.text()).toBe(200);
      // The row before any read: a refused import would otherwise be reported as a service that
      // holds no specification group. The likeliest refusal is `LIB_COMPILATION_ERROR`, and it has
      // never been observed here: `SystemExportImportService.enrichAndSaveIntegrationSystem`
      // compiles a library for every new specification, which shells out to `protoc` and the
      // grpc-java plugin for GRPC and runs the graphql-java-generator codegen and in-process
      // `javac` for GRAPHQL. Nothing under `e2e/fixtures/specifications/` has ever imported a
      // `.proto`, and `orders.graphql` carries a custom scalar, an enum, input types and non-default
      // root type names that `widgets.graphql` does not.
      expect(await rowsOf(response), `the import row of the ${golden.protocol} service`).toEqual([
        expect.objectContaining({ id: golden.serviceId, status: "CREATED" }),
      ]);

      const system = await catalog.getSystem(golden.serviceId);
      expect({ name: system.name, type: system.type }, `the service ${golden.serviceId}`).toEqual({
        name: golden.service.name,
        type: golden.service.content.integrationSystemType,
      });

      const groups = await catalog.listSpecificationGroups(golden.serviceId);
      expect(
        groups.map((group) => ({ id: group.id, name: group.name })),
        `the specification groups of the ${golden.protocol} service`,
      ).toEqual([{ id: golden.group.id, name: golden.group.name }]);

      const models = await catalog.listModels(golden.group.id);
      expect(models.map((each) => each.id), `the specifications of ${golden.group.id}`).toEqual([golden.specification.id]);

      // `type` and `format` come back absent: the extension writes neither into the specification
      // document, and `SystemModelBaseDTO` declares neither.
      const model = models[0] as unknown as Record<string, unknown>;
      expect(
        { name: model.name, version: model.version, type: model.type, format: model.format },
        `the specification ${golden.specification.id}`,
      ).toEqual({
        name: golden.specification.name,
        version: golden.specification.content.version,
        type: undefined,
        format: undefined,
      });

      // Name and method per operation. The golden's `path` is left out: SOAP writes the empty
      // string there, and whether the catalog stores an empty string or a null is another subject.
      const byId = (a: { id: string }, b: { id: string }) => a.id.localeCompare(b.id);
      const operations = await catalog.listOperations(golden.specification.id);
      expect(
        operations.map((operation) => ({ id: operation.id, name: operation.name, method: operation.method })).sort(byId),
        `the operations of ${golden.specification.id}`,
      ).toEqual(
        golden.specification.content.operations
          .map((operation) => ({ id: operation.id, name: operation.name, method: operation.method }))
          .sort(byId),
      );

      // SOAP takes its environment from the WSDL's `soap:address` and gRPC from the fallback
      // candidate, while GraphQL produces none, so this is an empty list for one of the three.
      const environments = await catalog.listEnvironments(golden.serviceId);
      expect(
        environments.map((environment) => ({ id: environment.id, name: environment.name, address: environment.address })),
        `the environments of the ${golden.protocol} service`,
      ).toEqual(
        golden.service.content.environments.map((environment) => ({
          id: environment.id,
          name: environment.name,
          address: environment.address,
        })),
      );

      // The source round trip: the reader loads each specification source's text out of the
      // archive directory, so the export carries `resources/source-<specificationId>/<name>` back
      // with the bytes the extension copied there.
      const exported = await catalog.exportSystems([golden.serviceId]);
      expect(await entryNames(exported), `the entries of the export of the ${golden.protocol} service`).toEqual(
        golden.entries.map((entry) => `services/${golden.serviceId}/${entry}`).sort(),
      );
      for (const source of golden.specification.content.specificationSources) {
        const entry = `resources/${source.fileName}`;
        expect(
          await entryText(exported, `services/${golden.serviceId}/${entry}`),
          `${source.name} as the export of ${golden.specification.id} carries it`,
        ).toBe(fs.readFileSync(path.join(IMPORT_GOLDEN, golden.protocol, entry), "utf8"));
      }
    } finally {
      await removeIfPresent(catalog, location, () => catalog.deleteSystem(golden.serviceId)).catch(leftBehind(location));
    }
  });
}
