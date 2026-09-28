/**
 * Live round trip for the per-type service file names (issue #553).
 *
 * The spec belongs to the `env` project because its second half restarts the catalog with
 * `QIP_EXPORT_LEGACY_FORMAT=true`, which is the only way to reach the old file names at all.
 *
 * Three rules make the assertions capable of failing, and all three come from measuring the stack
 * rather than from reading the code:
 *
 *   - DELETE BEFORE IMPORT. An import over a live id is an UPDATE, so "the ids are there" holds
 *     whether the import created them, updated them, or did nothing at all.
 *   - IMPORT THE ORIGINAL BYTES. ArchiveWriter builds `services/<id>/` unconditionally; an archive
 *     whose entries sit at the zip root imports as 204 with an empty body. Asserting basenames, or
 *     re-zipping an unpacked tree, turns a no-op import into a green run.
 *   - PIN THE STATUS. `SystemExportImportController.importSystems` answers 204 for an archive it
 *     read nothing out of and 200 with a row per service otherwise, so `< 300` accepts the silent
 *     no-op this file exists to catch.
 *
 * The catalog log is read **before** the restart that ends each half rather than after it.
 * `env.restart` is `up --force-recreate`, which destroys the container, so `docker logs` on the
 * other side of one addresses a container seconds old and no window can reach back past it.
 */
import { test, expect } from "../../support/fixtures.js";
import JSZip from "jszip";
import yaml from "js-yaml";
import { logWindowStart } from "../../support/logs.js";
import { tokenized } from "../../support/run.js";
import { entryNames, entryText } from "../../support/zip.js";
import type { Catalog } from "../../support/catalog.js";
import { RESTART_TIMEOUT } from "./constants.js";

/** Five services created, two archives exported, and both imported back. No restart. */
const EXPORT_CYCLE_TIMEOUT = 180_000;

/** The two exceptions the export and import paths raise, and the negative half of the log read. */
const EXPORT_IMPORT_FAILURE = /ServiceExportException|ServiceImportException/;

/**
 * The catalog's own audit line for the export of one service, and the positive half of the log read.
 *
 * Suite rule 18: an absence-only read is equally green when the window held no lines at all, so the
 * line that proves the path ran is asserted beside the one that would show it failing. The id is
 * what scopes it — it is minted by this case and no parallel worker can produce it. The import half
 * is not usable for this: `ActionsLogService` logs it as `Action IMPORT for SERVICES with name
 * services.zip`, naming the archive and no id, and every importing case writes that archive name.
 *
 * `\w+` rather than a literal entity type: the audit line spells the family out per type
 * (`EXTERNAL_SERVICE`, `CONTEXT_SYSTEM`, …), and which one it is has nothing to do with this read.
 */
function exportedLine(id: string): RegExp {
  return new RegExp(`Action EXPORT for \\w+ with name [^\\n]*with id: ${id}`);
}

/**
 * The two shapes, since the legacy format changes the document and not only the file name.
 *
 * Measured on this stack, the same service exports as
 * `id / $schema / name / content: {description, integrationSystemType, migrations: "[100, 101,
 * 102]"}` in the current format, and as a flat `id / name / description / integrationSystemType /
 * migrations: "[100, 102]"` with the flag on — migration 101 is the one that moved the fields under
 * `content`, and the legacy export reverts it. So the type has to be read at both levels, and a
 * reader that knows only `content` reports every legacy document as carrying no type at all.
 */
interface ServiceDocument {
  id?: string;
  name?: string;
  integrationSystemType?: string;
  content?: { integrationSystemType?: string };
}

/** The declared type wherever the document's migration level puts it. */
function declaredType(document: ServiceDocument): string | undefined {
  return document.content?.integrationSystemType ?? document.integrationSystemType;
}

/** One service document, parsed. The read throws naming the archive's entries if it is absent. */
async function documentIn(zip: Buffer, entry: string): Promise<ServiceDocument> {
  return yaml.load(await entryText(zip, entry)) as ServiceDocument;
}

/**
 * `<serviceId> <type>` per plain service document, read through the YAML parser.
 *
 * The current format carries the type in `content.integrationSystemType` and gives every plain
 * service the same generic `$schema`; the per-type schema stem of issue #553 is not in this build.
 */
async function declaredTypes(zip: Buffer): Promise<string[]> {
  const archive = await JSZip.loadAsync(zip);
  const found: string[] = [];
  for (const [name, file] of Object.entries(archive.files)) {
    if (file.dir || !name.includes(".service.")) continue;
    const document = yaml.load(await file.async("string")) as ServiceDocument;
    found.push(`${name.split("/").at(-2)} ${declaredType(document) ?? "none"}`);
  }
  return found.sort();
}

/** The ids an import answered with, which is what says it wrote anything at all. */
async function importedIds(catalog: Catalog, path: string, zip: Buffer): Promise<string[]> {
  const response = await catalog.importArchive(path, zip, "services.zip");
  expect(response.status(), `${path} answered ${response.status()}`).toBe(200);
  return ((await response.json()) as Array<{ id: string }>).map((row) => row.id).sort();
}

/** The five services this spec owns, created once and addressed by every case. */
const services = {
  external: "",
  internal: "",
  implemented: "",
  context: "",
  mcp: "",
};

/** The three plain services, in the order every assertion sorts them into. */
const plain = () => [services.external, services.internal, services.implemented].sort();

/** Only what this run created, so a parallel worker's services never enter an assertion. */
const oursOf = async (all: Promise<Array<{ id: string }>>, mine: readonly string[]) =>
  (await all).map((each) => each.id).filter((id) => mine.includes(id)).sort();

/** Whether the catalog is running in legacy format, and therefore whether `afterAll` restores it. */
let legacyFlagOn = false;

test.describe.configure({ mode: "serial" });

test.describe("service type round trip", () => {
  test.beforeAll(async ({ env }) => {
    const settings = await env.settings("runtime-catalog");
    expect(
      settings.QIP_EXPORT_LEGACY_FORMAT,
      "the catalog already runs with the legacy flag on; this spec sets and clears it itself and " +
        "would leave the container in a state it did not find it in",
    ).not.toBe("true");
  });

  test.afterAll(async ({ catalog, env }) => {
    test.setTimeout(RESTART_TIMEOUT);
    // Before the deletes: the flag has to come off even if a delete fails, and a catalog left in
    // legacy format writes every later export in this run in the old file names.
    if (legacyFlagOn) await env.restart("runtime-catalog");

    for (const id of plain()) {
      if (id) await catalog.deleteSystem(id).catch(() => {});
    }
    if (services.context) await catalog.deleteContextSystem(services.context).catch(() => {});
    if (services.mcp) await catalog.deleteMcpSystem(services.mcp).catch(() => {});
  });

  test("a current-format archive imports back over the services it was exported from", { tag: ["@catalog", "@tier2"] }, async ({ catalog, env, run }) => {
    // No restart in this case, so RESTART_TIMEOUT would overstate it.
    test.setTimeout(EXPORT_CYCLE_TIMEOUT);
    const began = logWindowStart();

    services.external = (await catalog.createSystem(tokenized(run, "rt-external"), "EXTERNAL")).id;
    services.internal = (await catalog.createSystem(tokenized(run, "rt-internal"), "INTERNAL")).id;
    services.implemented = (
      await catalog.createSystem(tokenized(run, "rt-implemented"), "IMPLEMENTED")
    ).id;
    services.context = (await catalog.createContextSystem(tokenized(run, "rt-context"))).id;
    services.mcp = (
      await catalog.createMcpSystem({
        name: tokenized(run, "rt-mcp"),
        identifier: tokenized(run, "rt-mcp"),
        instructions: "none",
      })
    ).id;

    // Scoped by id on every family: an unscoped plain export falls back to getAll() and drains the
    // catalog.
    const currentPlainZip = await catalog.exportSystems(plain());
    const currentContextZip = await catalog.download(
      `/v1/catalog/context-system/export?systemIds=${services.context}`,
    );
    const mcpExport = await catalog.raw("post", "/v1/catalog/mcp-system/export", [services.mcp]);
    expect(mcpExport.status(), "the MCP export did not answer with an archive").toBe(200);
    const currentMcpZip = Buffer.from(await mcpExport.body());

    expect(await entryNames(currentPlainZip)).toEqual(
      plain().map((id) => `services/${id}/${id}.service.qip.yaml`).sort(),
    );
    expect(await declaredTypes(currentPlainZip)).toEqual(
      [
        `${services.external} EXTERNAL`,
        `${services.implemented} IMPLEMENTED`,
        `${services.internal} INTERNAL`,
      ].sort(),
    );
    expect(await entryNames(currentContextZip)).toEqual([
      `services/${services.context}/${services.context}.context-service.qip.yaml`,
    ]);
    expect(await entryNames(currentMcpZip)).toEqual([
      `services/${services.mcp}/${services.mcp}.mcp-service.qip.yaml`,
    ]);

    await deleteAllFive(catalog);

    expect(await importedIds(catalog, "/v1/import/system", currentPlainZip)).toEqual(plain());
    expect(
      await importedIds(catalog, "/v1/catalog/context-system/import", currentContextZip),
    ).toEqual([services.context]);
    expect(await importedIds(catalog, "/v1/catalog/mcp-system/import", currentMcpZip)).toEqual([
      services.mcp,
    ]);

    expect(await oursOf(catalog.listSystems(), plain())).toEqual(plain());
    expect(await oursOf(catalog.listContextSystems(), [services.context])).toEqual([
      services.context,
    ]);
    expect(await oursOf(catalog.listMcpSystems(), [services.mcp])).toEqual([services.mcp]);
    await expectTypesKept(catalog);

    // Read while the container that did the work is still the one running. The window starts a
    // second before the first create rather than at a fixed duration, so a neighboring spec's
    // refusal has almost no room to enter it.
    const log = await env.logs("runtime-catalog", began);
    expect(log, "the catalog logged no export for the plain services").toMatch(
      exportedLine(services.external),
    );
    expect(log, "an export or an import threw and the answer did not show it").not.toMatch(
      EXPORT_IMPORT_FAILURE,
    );
  });

  test("with the legacy flag on the export writes the old file names, and only plain services import back", { tag: ["@catalog", "@tier2"] }, async ({ catalog, env }) => {
    test.setTimeout(RESTART_TIMEOUT);
    // Set before the await, not after it: `restartWith` recreates the container and then waits up
    // to 180 s for its health check, so a throw in that wait would leave the catalog running in
    // legacy format while `afterAll` believed there was nothing to restore.
    legacyFlagOn = true;
    await env.restartWith("runtime-catalog", { QIP_EXPORT_LEGACY_FORMAT: "true" });
    const began = logWindowStart();

    const legacyPlainZip = await catalog.exportSystems(plain());
    const legacyContextZip = await catalog.download(
      `/v1/catalog/context-system/export?systemIds=${services.context}`,
    );
    const mcpExport = await catalog.raw("post", "/v1/catalog/mcp-system/export", [services.mcp]);
    expect(mcpExport.status()).toBe(200);
    const legacyMcpZip = Buffer.from(await mcpExport.body());

    expect(await entryNames(legacyPlainZip)).toEqual(
      plain().map((id) => `services/${id}/service-${id}.yaml`).sort(),
    );
    expect(await entryNames(legacyContextZip)).toEqual([
      `services/${services.context}/context-service-${services.context}.yaml`,
    ]);
    expect(await entryNames(legacyMcpZip)).toEqual([
      `services/${services.mcp}/mcp-service-${services.mcp}.yaml`,
    ]);

    // The type restated in the document is the only thing that makes a legacy archive importable,
    // and in this format it sits at the top level rather than under `content`.
    for (const [id, want] of [
      [services.external, "EXTERNAL"],
      [services.internal, "INTERNAL"],
      [services.implemented, "IMPLEMENTED"],
    ] as const) {
      const document = await documentIn(legacyPlainZip, `services/${id}/service-${id}.yaml`);
      expect(document.integrationSystemType, `legacy document of ${id}`).toBe(want);
    }

    for (const [zip, entry] of [
      [legacyContextZip, `services/${services.context}/context-service-${services.context}.yaml`],
      [legacyMcpZip, `services/${services.mcp}/mcp-service-${services.mcp}.yaml`],
    ] as const) {
      const document = await documentIn(zip, entry);
      // At either level: the downgrade is where a context or an MCP service would pick up a type it
      // never had, and a check of one level would miss it in exactly this format.
      expect(declaredType(document), `${entry} acquired a plain-service type`).toBeUndefined();
    }

    await deleteAllFive(catalog);

    expect(await importedIds(catalog, "/v1/import/system", legacyPlainZip)).toEqual(plain());

    // Nothing scans for context-service-<id>.yaml or mcp-service-<id>.yaml, in this version or any
    // older one. The file is written and discovered by nothing, so the import finds an empty
    // archive and answers 204. The empty body is not asserted beside it: 204 carries none by
    // definition, so the reading would restate the status rather than add to it. What says the
    // import found nothing is the three listings below.
    for (const [path, zip] of [
      ["/v1/catalog/context-system/import", legacyContextZip],
      ["/v1/catalog/mcp-system/import", legacyMcpZip],
    ] as const) {
      const response = await catalog.importArchive(path, zip, "services.zip");
      expect(response.status(), `${path} read something out of a legacy archive`).toBe(204);
    }

    expect(await oursOf(catalog.listSystems(), plain())).toEqual(plain());
    expect(await oursOf(catalog.listContextSystems(), [services.context])).toEqual([]);
    expect(await oursOf(catalog.listMcpSystems(), [services.mcp])).toEqual([]);
    await expectTypesKept(catalog);

    // Read before `afterAll` restarts the container back to the current format, for the same reason
    // the first case reads it before this one recreates the container.
    const log = await env.logs("runtime-catalog", began);
    expect(log, "the catalog logged no export for the plain services").toMatch(
      exportedLine(services.external),
    );
    expect(log, "an export or an import threw and the answer did not show it").not.toMatch(
      EXPORT_IMPORT_FAILURE,
    );
  });
});

/** The delete half of an import hop. Without it "the ids are there" cannot fail. */
async function deleteAllFive(catalog: Catalog): Promise<void> {
  for (const id of plain()) await catalog.deleteSystem(id);
  await catalog.deleteContextSystem(services.context);
  await catalog.deleteMcpSystem(services.mcp);

  expect(await oursOf(catalog.listSystems(), plain())).toEqual([]);
  expect(await oursOf(catalog.listContextSystems(), [services.context])).toEqual([]);
  expect(await oursOf(catalog.listMcpSystems(), [services.mcp])).toEqual([]);
}

/** One list read, not three per-id reads. */
async function expectTypesKept(catalog: Catalog): Promise<void> {
  const mine = (await catalog.listSystems())
    .filter((system) => plain().includes(system.id))
    .map((system) => `${system.id} ${system.type}`)
    .sort();
  expect(mine).toEqual(
    [
      `${services.external} EXTERNAL`,
      `${services.internal} INTERNAL`,
      `${services.implemented} IMPLEMENTED`,
    ].sort(),
  );
}
