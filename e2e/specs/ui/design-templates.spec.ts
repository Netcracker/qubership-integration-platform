/**
 * Design Templates under Admin Tools: a template uploaded from a file and deleted from the list.
 *
 * What the API cannot see: that the dialog names the template after the dropped file, sends the
 * file's text as the content, and that the row appears and leaves with no reload. The template is
 * read back through `GET /v1/detailed-design/templates`; `specs/api/design.spec.ts` covers the
 * store and the rendering.
 */
import { writeFile } from "node:fs/promises";
import { test, expect } from "../../support/page-guard.js";
import { TableView } from "../../pages/TableView.js";
import { callToken, tokenized } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";

test.describe("design templates", { tag: ["@ui"] }, () => {
  test("an uploaded template takes the file's name and is deleted", async ({ page, catalog, run }, testInfo) => {
    // A call token keeps repeats of the case apart: the store refuses a name it already holds.
    const name = tokenized(run, callToken("ui-template"));
    const content = `# ${name}\n\nChain: \${chain.name}\n`;
    const file = testInfo.outputPath(`${name}.ftl`);
    await writeFile(file, content);
    const table = new TableView(page);
    const row = table.rows.filter({ has: page.getByRole("cell", { name, exact: true }) });

    await page.goto("/admintools/detailed-design/templates");
    await expect(table.rows.first()).toBeVisible();
    try {
      await page.getByTestId("design-templates-create").click();
      const dialog = page.getByRole("dialog", { name: "Import Template" });
      const chooser = page.waitForEvent("filechooser");
      await dialog.getByText("Drop .ftl file here to attach").click();
      await (await chooser).setFiles(file);
      // An empty Name takes the file's name without the extension.
      await expect(dialog.getByRole("textbox", { name: "Name" })).toHaveValue(name);
      await dialog.getByRole("button", { name: "Save" }).click();
      await expect(dialog).toBeHidden();

      await expect(row).toBeVisible();
      await expect(row.getByText("Custom", { exact: true })).toBeVisible();
      const stored = (await catalog.listDesignTemplates()).find((each) => each.name === name);
      expect(stored, `no template named ${name} is stored`).toBeDefined();
      expect(stored!.content).toBe(content);
      expect(stored!.builtIn).toBe(false);

      await row.getByRole("checkbox").check();
      await page.getByTestId("design-templates-delete").click();
      const confirm = page.getByRole("dialog").filter({ hasText: "Delete template?" });
      await confirm.getByRole("button", { name: "OK" }).click();
      await expect(confirm).toBeHidden();
      await expect(row).toHaveCount(0);
      expect((await catalog.raw("get", `/v1/detailed-design/templates/${stored!.id}`)).status()).toBe(404);
    } finally {
      // The id is the name lowercased, and a delete of an id nothing answers to is a 204.
      await catalog.deleteDesignTemplates([name.toLowerCase()]).catch(leftBehind(`design template ${name}`));
    }
  });
});
