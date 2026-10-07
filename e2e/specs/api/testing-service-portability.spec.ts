/**
 * Test cases and endpoint mocks exported from the testing service import back, and `GET /mode` is
 * a read and nothing more.
 *
 * An export is flat: `writeExportedEntity` (`internal/services/import_export.go`) writes one
 * `<id>.json` per entity at the zip root, with no directories, so the catalog's entry-path rule does
 * not apply. Each entry is the envelope `{version, type, id, name, data}`, indented by two spaces
 * and ending with a newline, and `data` is the entity as the service stores it, matcher ids included.
 *
 * An import keeps the ids, `createdAt`, and `createdBy` from the file, and stamps `updatedAt` with
 * the time of the import. So the re-export after a delete and an import matches the original export
 * in everything except `updatedAt`, which has to be later.
 *
 * The case runs in `api`: the testing service never checks a trigger or endpoint reference against
 * the catalog, so each entity carries an invented reference with the run token in it.
 *
 * `GET /api/v1/mode` has no case here. `specs/api/health.spec.ts` pins its document, and the flag
 * changes no answer of the service: it reaches `Config.Production` and the mode controller and
 * nothing else in the Go module. Its one observable effect is the UI hiding the testing section,
 * which `specs/ui/testing-section.spec.ts` covers.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";
import { entryNames, entryText } from "../../support/zip.js";
import { inventedReference, type ImportResult } from "../../support/testing-service.js";

interface Envelope {
  version: number;
  type: string;
  id: string;
  name: string;
  data: Record<string, unknown>;
}

/** Every entry of an export, parsed and keyed by entity id, after checking the entry's own shape. */
async function envelopesOf(archive: Buffer, type: string, ids: readonly string[]): Promise<Map<string, Envelope>> {
  expect(await entryNames(archive), "one flat entry per entity").toEqual(ids.map((id) => `${id}.json`).sort());
  const envelopes = new Map<string, Envelope>();
  for (const id of ids) {
    const text = await entryText(archive, `${id}.json`);
    expect(text.startsWith('{\n  "version": '), `${id}.json is indented by two spaces`).toBe(true);
    expect(text.endsWith("}\n"), `${id}.json ends with a newline`).toBe(true);
    const envelope = JSON.parse(text) as Envelope;
    expect(Object.keys(envelope)).toEqual(["version", "type", "id", "name", "data"]);
    expect(envelope).toMatchObject({ version: 1, type, id });
    expect(envelope.data).toMatchObject({ id, name: envelope.name });
    envelopes.set(id, envelope);
  }
  return envelopes;
}

/** The import answer as `{fileName, entityId, entityName, result, message}` rows, sorted by entity id. */
function rowsOf(results: ImportResult[], archive: string): Omit<ImportResult, "archive">[] {
  for (const row of results) expect(row.archive).toBe(archive);
  return results
    .map(({ archive: _archive, ...row }) => row)
    .sort((a, b) => (a.entityId ?? "").localeCompare(b.entityId ?? ""));
}

function expectedRows(envelopes: Map<string, Envelope>, result: ImportResult["result"], message = "") {
  return [...envelopes.values()]
    .map((each) => ({ fileName: `${each.id}.json`, entityId: each.id, entityName: each.name, result, message }))
    .sort((a, b) => a.entityId.localeCompare(b.entityId));
}

/** Compares a re-export with the original: equal except `data.updatedAt`, which the import set later. */
function expectReimported(original: Map<string, Envelope>, reexported: Map<string, Envelope>): void {
  for (const [id, before] of original) {
    const after = reexported.get(id)!;
    const { updatedAt: updatedBefore, ...dataBefore } = before.data;
    const { updatedAt: updatedAfter, ...dataAfter } = after.data;
    expect({ ...after, data: dataAfter }, `${id}.json`).toEqual({ ...before, data: dataBefore });
    expect(Date.parse(updatedAfter as string), `${id}.json updatedAt`).toBeGreaterThan(Date.parse(updatedBefore as string));
  }
}

test("test cases and endpoint mocks exported as a ZIP import back after a delete", { tag: ["@testing-service", "@tier1"] }, async ({ testingService, run }) => {
  // Each id is recorded as soon as it exists, so a failed create still removes the rest.
  const caseIds: string[] = [];
  const mockIds: string[] = [];
  try {
    for (const what of ["a", "b"]) {
      const created = await testingService.createTestCase({
        name: tokenized(run, `portable-case-${what}`),
        enabled: what === "a",
        trigger: inventedReference(run, `portability-trigger-${what}`),
        method: "POST",
        body: `{"case":"${what}"}`,
        headers: [{ name: "x-e2e-case", value: what }],
        queryParameters: [{ name: "q", value: what }],
        rules: [
          { name: "status", type: "equal", entityType: "status", value: "200" },
          { name: "header", type: "exist", entityType: "header", entityName: "x-e2e-case", enabled: false },
        ],
      });
      caseIds.push(created.id);
    }
    for (const what of ["a", "b"]) {
      const created = await testingService.createMock({
        name: tokenized(run, `portable-mock-${what}`),
        reference: inventedReference(run, `portability-endpoint-${what}`),
        response: { status: 202, body: `mocked ${what}`, headers: [{ name: "X-Mocked", value: what }] },
        matchers: [{ type: "contain", entityType: "body", value: what }],
      });
      mockIds.push(created.id);
    }

    const casesZip = await testingService.exportTestCases(caseIds);
    const mocksZip = await testingService.exportMocks(mockIds);
    const caseEnvelopes = await envelopesOf(casesZip, "TestCase", caseIds);
    const mockEnvelopes = await envelopesOf(mocksZip, "EndpointMock", mockIds);
    for (const id of caseIds) {
      // The read is a view that adds two rule counts; the export carries the test case alone.
      const { validationRuleCount: _rules, enabledRuleCount: _enabled, ...stored } = await testingService.getTestCase(id);
      expect(caseEnvelopes.get(id)?.data, "the exported test case is the stored one").toEqual(stored);
    }
    for (const id of mockIds) {
      expect(mockEnvelopes.get(id)?.data, "the exported mock is the stored one").toEqual(await testingService.getMock(id));
    }

    await testingService.deleteTestCases(caseIds);
    await testingService.deleteMocks(mockIds);
    for (const id of caseIds) expect((await testingService.raw("get", `/api/v1/test-cases/${id}`)).status()).toBe(404);
    for (const id of mockIds) expect((await testingService.raw("get", `/api/v1/endpoint-mocks/${id}`)).status()).toBe(404);

    // An import checks the envelope's type, so an archive of the other kind stores nothing.
    const crossed = rowsOf(await testingService.importTestCases(mocksZip, "mocks.zip"), "mocks.zip");
    expect(crossed).toEqual(expectedRows(mockEnvelopes, "error", "wrong entity type: EndpointMock"));
    const crossedBack = rowsOf(await testingService.importMocks(casesZip, "cases.zip"), "cases.zip");
    expect(crossedBack).toEqual(expectedRows(caseEnvelopes, "error", "wrong entity type: TestCase"));
    for (const id of mockIds) expect((await testingService.raw("get", `/api/v1/test-cases/${id}`)).status()).toBe(404);
    for (const id of caseIds) expect((await testingService.raw("get", `/api/v1/endpoint-mocks/${id}`)).status()).toBe(404);

    expect(rowsOf(await testingService.importTestCases(casesZip, "cases.zip"), "cases.zip")).toEqual(expectedRows(caseEnvelopes, "created"));
    expect(rowsOf(await testingService.importMocks(mocksZip, "mocks.zip"), "mocks.zip")).toEqual(expectedRows(mockEnvelopes, "created"));

    expectReimported(caseEnvelopes, await envelopesOf(await testingService.exportTestCases(caseIds), "TestCase", caseIds));
    expectReimported(mockEnvelopes, await envelopesOf(await testingService.exportMocks(mockIds), "EndpointMock", mockIds));

    // The same bytes over entities that exist update them in place.
    expect(rowsOf(await testingService.importTestCases(casesZip, "cases.zip"), "cases.zip")).toEqual(expectedRows(caseEnvelopes, "updated"));
    expect(rowsOf(await testingService.importMocks(mocksZip, "mocks.zip"), "mocks.zip")).toEqual(expectedRows(mockEnvelopes, "updated"));
  } finally {
    for (const id of caseIds) await testingService.deleteTestCase(id).catch(leftBehind(`test case ${id}`));
    for (const id of mockIds) await testingService.deleteMock(id).catch(leftBehind(`mock ${id}`));
  }
});
