/**
 * List tables: sideways scrolling, settings that outlive a reload, and paging by scroll.
 *
 * What the API cannot see: where the header cells sit over the body cells, what the browser keeps in
 * `localStorage` for a table, and whether scrolling a table fetches its next page. The catalog and the
 * session store return the same data whichever of these works.
 *
 * The empty-table rule `ui/src/components/table/tableScroll.ts` guards is not here. Dropping `y` on
 * an empty table changes only its DOM structure (no `.ant-table-body`): measured with the guard
 * reverted, the placeholder renders at the same place and size. jsdom sees that structure as well
 * as a browser does, so `ui/tests/components/table/tableScroll.test.tsx` asserts it.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainsPage } from "../../pages/ChainsPage.js";
import { TableView } from "../../pages/TableView.js";
import type { Catalog } from "../../support/catalog.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { notTheKnownDefect } from "../../support/known-defect.js";
import { readUntil } from "../../support/poll.js";
import { tokenized } from "../../support/run.js";
import { callChain } from "../../support/sessions.js";

/** `PAGE_SIZE` in `ui/src/hooks/useActionLog.tsx`. */
const AUDIT_PAGE = 20;

test("a table scrolled sideways keeps each header cell over its column", { tag: ["@ui", "@tier1"] }, async ({ page, request, env, sessions }) => {
  const chain = seedChain(readCorpusState(), "http-echo");
  const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "ui-tables" } });
  expect(response.status()).toBe(200);
  const session = await sessions.byExternalId(token);
  const table = new TableView(page);

  // At the pinned 1600 px the admin sessions table is wider than its body, so it scrolls sideways.
  await page.goto("/admintools/sessions");
  await page.getByRole("textbox", { name: "Search sessions..." }).fill(token);
  const row = table.rows.filter({ has: page.getByText(session.id, { exact: true }) });
  const cells = row.getByRole("cell");
  await expect(cells.first()).toBeVisible();
  const before = await TableView.columns(cells);

  await table.wheel(row, 400, 0);
  await expect.poll(async () => (await TableView.columns(cells))[0].left).toBeLessThan(before[0].left);

  // The header lives in a table of its own and follows the body's scroll position, possibly a
  // frame behind it, so both are read again until they agree or the timeout ends.
  await expect(async () => {
    const body = await TableView.columns(cells);
    const header = (await TableView.columns(page.getByRole("columnheader"))).slice(0, body.length);
    expect(header).toEqual(body);
  }).toPass({ timeout: 10_000 });
});

test("a hidden column and a sort survive a reload of the chains list", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  const first = await catalog.createChain(tokenized(run, "ui-tables-a"), folder.id);
  const second = await catalog.createChain(tokenized(run, "ui-tables-b"), folder.id);
  const chains = new ChainsPage(page);
  const table = new TableView(page);
  const order = async () => {
    const names = await table.rows.allTextContents();
    return [first.name, second.name].sort((a, b) => names.findIndex((each) => each.includes(a)) - names.findIndex((each) => each.includes(b)));
  };

  await chains.gotoFolder(folder.id);
  await expect(chains.link(second.name)).toBeVisible();
  await page.getByTestId("chains-column-settings").getByRole("button").click();
  const description = page.getByRole("checkbox", { name: "Description" });
  await description.uncheck();
  await page.keyboard.press("Escape");
  await expect(description).toBeHidden();
  await expect(table.header("Description")).toHaveCount(0);
  // Ascending, then descending.
  await table.header("Name").click();
  await table.header("Name").click();
  await expect(table.header("Name")).toHaveAttribute("aria-sort", "descending");
  await expect.poll(order).toEqual([second.name, first.name]);

  await page.reload();

  // The column settings, the sort, the widths and the filters are kept in `localStorage`.
  await expect(chains.link(second.name)).toBeVisible();
  await expect(table.header("Name")).toHaveAttribute("aria-sort", "descending");
  await expect(table.header("Description")).toHaveCount(0);
  expect(await order()).toEqual([second.name, first.name]);
});

/**
 * Makes sure the audit log holds more than one page, whatever else is in it.
 *
 * The log is global and is not filtered by the run token: these cases read only how many rows the
 * table holds, and writes from other workers can only add rows.
 */
async function fillAuditPage(catalog: Catalog, run: string, parentId: string): Promise<void> {
  await Promise.all(Array.from({ length: AUDIT_PAGE + 1 }, (_, index) => catalog.createFolder(tokenized(run, `ui-audit-${index}`), parentId)));
}

test("the audit log loads its next page when its table is scrolled to the end", { tag: ["@ui", "@tier1"] }, async ({ page, catalog, folder, run }) => {
  await fillAuditPage(catalog, run, folder.id);
  // Short enough that the first page overflows the table body, so the body can scroll.
  await page.setViewportSize({ width: 1600, height: 700 });
  const table = new TableView(page);

  await page.goto("/admintools/audit");
  await expect(table.rows).toHaveCount(AUDIT_PAGE);
  await table.wheel(table.rows.first(), 0, 5_000);

  await expect.poll(() => table.rows.count()).toBeGreaterThan(AUDIT_PAGE);
});

test("the audit log reaches its second page when the first one fits the screen", { tag: ["@ui", "@tier2"] }, async ({ page, catalog, folder, run }) => {
  await fillAuditPage(catalog, run, folder.id);
  // Tall enough that the first page fits the table body, so there is nothing to scroll.
  await page.setViewportSize({ width: 1600, height: 1100 });
  const table = new TableView(page);

  await page.goto("/admintools/audit");
  await expect(table.rows.nth(AUDIT_PAGE - 1)).toBeVisible();
  // docs/product-defects.md, "The audit log never loads past its first page when that page fits
  // the screen": ActionsLog fetches the next page only from the body's scroll event.
  // Annotated only here, so a first page that never renders fails for real above.
  test.fail();
  // A fix loads the page as soon as the first one renders; the defect never does.
  const shown = await readUntil(() => table.rows.count(), (count) => count > AUDIT_PAGE, 10_000);
  if (shown < AUDIT_PAGE) {
    notTheKnownDefect(`the audit log shows ${shown} rows; the defect keeps the first page of ${AUDIT_PAGE}`);
  }
  expect(shown, "the second page loads: delete the test.fail() annotation").toBeGreaterThan(AUDIT_PAGE);
});
