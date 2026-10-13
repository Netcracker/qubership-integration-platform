/**
 * The audit log: what the catalog records about the actions a spec performs, and the spreadsheet
 * it writes them into.
 *
 * **Which version.** Two controllers serve the search. `ActionsLogController` is
 * `@Deprecated(since = "2026.3")` and answers `POST /v1/catalog/actions-log` over a time window;
 * `ActionsLogControllerV2` answers `POST /v2/catalog/actions-log` over `offset`/`limit` and no
 * window at all. These cases use **v1**: it is what the platform serves today, and deprecated is
 * not the same as gone. The day it stops, the live diff in `api-docs-live.spec.ts` reports it.
 *
 * **The write is asynchronous.** `ActionsLogService.logAction` offers the row to a queue that a
 * background thread drains into Postgres, so the action is on the record some milliseconds after
 * the call that caused it returned. Every case here polls rather than reading once.
 *
 * **The window is the query, and it is easy to get backwards.** The search is
 * `offsetTime - rangeTime < actionTime <= offsetTime`, so `offsetTime` is the **end** of the window
 * and `rangeTime` its width — not a start and a duration. The criteria default both to 0, which
 * reads as the epoch and answers an empty page, so a caller that forgets them sees "nothing was
 * logged" rather than an error.
 *
 * Four measured shapes the cases pin, none of them guessable from the endpoint names:
 *
 * - `recordsAfterRange` is **filter-aware**: it counts the rows older than the window that the same
 *   filters match, so it is a "there is more behind this" hint and never a total.
 * - A condition the column cannot translate answers **400** naming the conditions it can — the
 *   result of a fix, since these used to answer 500 or 200 with the whole table.
 * - An enum value that does not exist is refused on `IN` and **accepted on `IS`**, where it becomes
 *   a string comparison that matches nothing and answers 200 with an empty page. That asymmetry is
 *   why a case asserting "the filter works" over an empty result proves nothing.
 * - The export answers **`Content-Type: application/json` carrying an xlsx** — a zip, `PK` magic
 *   and all — with the real name only in `Content-Disposition`. A client trusting the content type
 *   parses the workbook as JSON.
 *
 * The log is global: every worker's actions land in the same table. So every case filters on a name
 * carrying the run token and asserts over the rows it created, never over the page as a whole.
 */
import JSZip from "jszip";
import { test, expect } from "../../support/fixtures.js";
import { CLOCK_SKEW, type ActionLogEntry, type Catalog } from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";

/**
 * The window the filter cases search over.
 *
 * Not `CLOCK_SKEW`, which happens to be the same number and is a different idea: this is how wide a
 * search is, and a minute covers the action the case performed a line above it.
 */
const SEARCH_WINDOW = 60_000;

/** The nine columns `ActionsLogExportConstants` writes across the first row of the sheet. */
const EXPORT_HEADERS = [
  "Action Time",
  "Initiator",
  "Operation",
  "Entity Id",
  "Entity Type",
  "Entity Name",
  "Parent Id",
  "Parent Name",
  "Request Id",
];

/** Every action this run recorded under a name, once the writer thread has caught up. */
async function actionsNamed(
  catalog: Catalog,
  name: string,
  expected: number,
): Promise<ActionLogEntry[]> {
  let rows: ActionLogEntry[] = [];
  await expect
    .poll(
      async () => {
        rows = (await catalog.recentActions([{ column: "ENTITY_NAME", condition: "IS", value: name }]))
          .actionLogs;
        return rows.length;
      },
      { message: `waiting for ${expected} audit rows naming ${name}` },
    )
    .toBe(expected);
  return rows;
}

test("a chain create lands in the audit log, found by the run token", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const name = tokenized(run, "audit-create");
  const before = Date.now();
  const chain = await catalog.createChain(name, folder.id);

  const [entry] = await actionsNamed(catalog, name, 1);
  expect(entry).toMatchObject({
    entityType: "CHAIN",
    entityId: chain.id,
    entityName: name,
    operation: "CREATE",
    // No authentication anywhere on this stack, so every action is the same anonymous developer.
    // The assertion is that the column is filled at all: an empty initiator is the regression.
    username: "developer",
  });
  // The request id ties the row back to the call that caused it, which is the only way to correlate
  // one action out of a table every worker writes into.
  expect(entry.requestId, "the row carries the request id of the call").toBeTruthy();
  // Both bounds carry the same minute of tolerance. `actionTime` is stamped inside the container and
  // `before` is this process's clock, so a bare `>= before` fails whenever the two disagree — which
  // is the same skew `Catalog.recentActions` already builds the search window around.
  expect(entry.actionTime).toBeGreaterThanOrEqual(before - CLOCK_SKEW);
  expect(entry.actionTime).toBeLessThanOrEqual(Date.now() + CLOCK_SKEW);

  // A rename and a delete are two more rows against the same entity id, so the log is a history
  // rather than a current-state view. The rename is logged under the new name.
  const renamed = `${name}-renamed`;
  await catalog.updateChain(chain.id, renamed, undefined, folder.id);
  await catalog.deleteChain(chain.id);

  const after = await actionsNamed(catalog, renamed, 2);
  expect(after.map((row) => row.operation).sort()).toEqual(["DELETE", "UPDATE"]);
  for (const row of after) expect(row.entityId).toBe(chain.id);
  // The CREATE row still names the chain by the name it was created with: the log is not rewritten.
  expect((await actionsNamed(catalog, name, 1))[0].operation).toBe("CREATE");
});

test("the window bounds the page, and recordsAfterRange counts what it excluded", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const name = tokenized(run, "audit-window");
  await catalog.createChain(name, folder.id);
  const filters = [{ column: "ENTITY_NAME", condition: "IS", value: name }];
  await actionsNamed(catalog, name, 1);

  // A window that ends before the action happened holds nothing — and says so twice: an empty page,
  // and a count of the rows this filter matches behind it.
  const past = await catalog.searchActionsLog({
    offsetTime: Date.now() - 3_600_000,
    rangeTime: SEARCH_WINDOW,
    filters,
  });
  expect(past.actionLogs).toEqual([]);
  expect(past.recordsAfterRange, "recordsAfterRange is filter-aware, not a table total").toBe(0);

  // The same window, shifted so the action sits behind it rather than inside it: still an empty
  // page, but now the count reports the row it excluded.
  const behind = await catalog.searchActionsLog({
    offsetTime: Date.now() + 3_600_000,
    rangeTime: SEARCH_WINDOW,
    filters,
  });
  expect(behind.actionLogs).toEqual([]);
  expect(behind.recordsAfterRange).toBe(1);
});

test("a filter the column cannot translate is refused, and one it can is not", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  // ACTION_TIME accepts only the three time conditions, and the message names them. This used to
  // answer 500 on the type mismatch, or 200 with the whole table where the builder had no case.
  const unsupported = await catalog.raw("post", "/v1/catalog/actions-log", {
    offsetTime: Date.now(),
    rangeTime: SEARCH_WINDOW,
    filters: [{ column: "ACTION_TIME", condition: "CONTAINS", value: "x" }],
  });
  expect(unsupported.status()).toBe(400);
  expect(((await unsupported.json()) as { errorMessage: string }).errorMessage).toBe(
    "Filter condition CONTAINS is not supported for column ACTION_TIME. " +
      "Supported conditions: [IS_AFTER, IS_BEFORE, IS_WITHIN]",
  );

  const noColumn = await catalog.raw("post", "/v1/catalog/actions-log", {
    offsetTime: Date.now(),
    rangeTime: SEARCH_WINDOW,
    filters: [{ condition: "CONTAINS", value: "x" }],
  });
  expect(noColumn.status()).toBe(400);
  expect(((await noColumn.json()) as { errorMessage: string }).errorMessage).toBe(
    "Filter column is required",
  );

  const noCondition = await catalog.raw("post", "/v1/catalog/actions-log", {
    offsetTime: Date.now(),
    rangeTime: SEARCH_WINDOW,
    filters: [{ column: "ENTITY_NAME", value: "x" }],
  });
  expect(noCondition.status()).toBe(400);
  expect(((await noCondition.json()) as { errorMessage: string }).errorMessage).toBe(
    "Filter condition is required for column ENTITY_NAME",
  );
});

test("an unknown enum value is refused on IN and silently matches nothing on IS", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  // The two conditions take different paths through the query builder: IN maps each value through
  // the enum converter, IS compares the raw string. So the same nonsense value is a 400 on one and
  // an empty page on the other — which is why a case asserting a filter works needs a row to find.
  const refused = await catalog.raw("post", "/v1/catalog/actions-log", {
    offsetTime: Date.now(),
    rangeTime: SEARCH_WINDOW,
    filters: [{ column: "ENTITY_TYPE", condition: "IN", value: "NOT_A_TYPE" }],
  });
  expect(refused.status()).toBe(400);
  expect(((await refused.json()) as { errorMessage: string }).errorMessage).toContain(
    "Value NOT_A_TYPE is not supported. Supported values: [FOLDER, CHAIN,",
  );

  const accepted = await catalog.searchActionsLog({
    offsetTime: Date.now() + CLOCK_SKEW,
    rangeTime: 660_000,
    filters: [{ column: "ENTITY_TYPE", condition: "IS", value: "NOT_A_TYPE" }],
  });
  expect(accepted.actionLogs, "IS on an unknown enum value answers an empty page, not a 400").toEqual([]);
});

test("the export writes a spreadsheet carrying this run's action", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const name = tokenized(run, "audit-export");
  const from = Date.now() - CLOCK_SKEW;
  await catalog.createChain(name, folder.id);
  await actionsNamed(catalog, name, 1);

  const exported = await catalog.exportActionsLog(from, Date.now() + CLOCK_SKEW);
  expect(exported.status()).toBe(200);
  // The real name is in the disposition and nowhere else: the content type says JSON while the body
  // is a workbook, so a client that trusts it hands an xlsx to a JSON parser.
  expect(exported.headers()["content-disposition"]).toBe(
    "attachment; filename=catalog-actions-log.xlsx",
  );
  expect(exported.headers()["content-type"], "measured: an xlsx served as application/json").toBe(
    "application/json",
  );

  const body = Buffer.from(await exported.body());
  expect(body.subarray(0, 2).toString("latin1"), "an xlsx is a zip").toBe("PK");

  const workbook = await JSZip.loadAsync(body);
  expect(Object.keys(workbook.files)).toContain("xl/worksheets/sheet1.xml");
  // fastexcel writes every string into the shared table and leaves indices in the sheet, so the
  // text is asserted there. Reading sheet1.xml for a name would never find one.
  const strings = await workbook.file("xl/sharedStrings.xml")!.async("string");
  for (const header of EXPORT_HEADERS) expect(strings).toContain(header);
  expect(strings, "the run's own action is in the exported workbook").toContain(name);

  // The export takes a window and no filters, so a window ending before the action still answers a
  // workbook — headers and nothing else. Asserting the name is absent is what proves the window is
  // read at all, since a file is returned either way.
  const empty = await catalog.exportActionsLog(from - 7_200_000, from - 3_600_000);
  expect(empty.status()).toBe(200);
  const emptyStrings = await (await JSZip.loadAsync(Buffer.from(await empty.body())))
    .file("xl/sharedStrings.xml")!
    .async("string");
  expect(emptyStrings).toContain("Action Time");
  expect(emptyStrings).not.toContain(name);
});

test("the export refuses a half-specified window", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  // Both parameters are required and neither has a default, so this is a Spring-level 400 in
  // `application/problem+json` rather than the catalog's own `{serviceName, errorMessage}` shape.
  const response = await catalog.raw("get", `/v1/catalog/actions-log/export?actionTimeFrom=${Date.now()}`);
  expect(response.status()).toBe(400);
  expect(response.headers()["content-type"]).toContain("application/problem+json");
  expect((await response.json()) as Record<string, unknown>).toMatchObject({
    status: 400,
    detail: "Required parameter 'actionTimeTo' is not present.",
  });
});
