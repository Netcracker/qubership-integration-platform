/**
 * The Properties and Masking tabs of the VS Code chain editor, and what each writes to the chain
 * file.
 *
 * In VS Code the Properties tab carries a Group field, which Apply saves through `moveChain` as the
 * chain's `metaInfo.group` before `updateChain` writes the rest.
 *
 * Deleting the first masked field leaves it in the file: `deleteMaskedFields` in `chainApiModify.ts`
 * tests the found index with `if (index)`, which is false for index 0. The UI drops the row anyway,
 * so the field comes back when the chain is opened again. Not filed yet; the pinned case carries it,
 * and the other case deletes the second of two fields.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/response/chainApiModify.ts`:
 * - `updateChain` skipping `writeMainChain`: the file read timed out on the old name;
 * - `changeFolder` skipping `writeMainChain`: the group was missing from the written file;
 * - `updateMaskedField` skipping `writeMainChain`: the file held a field with an empty name;
 * - `if (index >= 0)` in `deleteMaskedFields`, the fix: the pinned case passed, so `test.fail()`
 *   turned it red.
 */
import type { FrameLocator } from "@playwright/test";
import { test, expect } from "../../support/vscode-guard.js";
import { TableView } from "../../pages/TableView.js";
import { outsideTheDefect } from "../../support/known-defect.js";
import { chainFile, parseChain } from "../../support/vscode-workspace.js";

const CHAIN = chainFile("1419390b-7423-4fab-853f-67c653e16a86");

/** A field of the Properties form by its label, which antd names with a trailing colon and, when required, a leading `* `. */
const field = (webview: FrameLocator, role: "textbox" | "combobox", label: string) =>
  webview.getByRole(role, { name: new RegExp(`^(\\* )?${label} :$`) });

const maskedNames = (text: string) => (parseChain(text).content.maskedFields ?? []).map((each) => each.name);

test.describe("chain properties", () => {
  test("an applied name, group, description, and label are written", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);
    const { webview } = vscode;
    await webview.getByRole("tab", { name: "Properties", exact: true }).click();

    const name = field(webview, "textbox", "Name");
    await expect(name).toHaveValue("vsc-fixture-chain");
    await name.fill("vsc-fixture-chain-renamed");
    await field(webview, "textbox", "Group").fill("e2e/moved");
    await field(webview, "textbox", "Description").fill("set in VS Code");
    const labels = field(webview, "combobox", "Labels");
    await labels.fill("e2e-label");
    await labels.press("Enter");
    const apply = webview.getByRole("button", { name: /Apply$/ });
    await apply.click();
    await expect(apply).toBeDisabled();

    const chain = parseChain(await vscode.readAsText((each) => parseChain(each).name === "vsc-fixture-chain-renamed"));
    expect({ name: chain.name, group: chain.metaInfo?.group, description: chain.content.description, labels: chain.content.labels }).toEqual({
      name: "vsc-fixture-chain-renamed",
      group: "e2e/moved",
      description: "set in VS Code",
      labels: ["e2e-label"],
    });
    expect(messages.answered()).toEqual(expect.arrayContaining(["moveChain", "updateChain"]));
  });

  test("masked fields are added, renamed, and deleted", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);
    const { webview } = vscode;
    const table = new TableView(webview);
    await webview.getByRole("tab", { name: "Masking", exact: true }).click();
    await expect(webview.getByText("No masked fields")).toBeVisible();

    await addMaskedField(webview, table, "e2eFirst");
    await addMaskedField(webview, table, "e2eSecond");
    await table.row("e2eSecond").getByRole("checkbox").check();
    await webview.getByTestId("masking-delete").click();
    await expect(table.rows).toHaveCount(1);
    await expect.poll(() => messages.answered()).toContain("deleteMaskedFields");

    expect(maskedNames(await vscode.readAsText((each) => maskedNames(each).length === 1))).toEqual(["e2eFirst"]);
    expect(messages.answered()).toEqual(expect.arrayContaining(["getMaskedFields", "createMaskedField", "updateMaskedField"]));
  });

  test("a deleted first masked field leaves the chain file", async ({ vscode, messages }) => {
    test.fail(true, "deleteMaskedFields tests the found index with `if (index)`, which skips index 0; not filed yet");
    const text = await outsideTheDefect("adding a masked field, deleting it, and reading the chain file", async () => {
      await vscode.open();
      await vscode.openFile(CHAIN);
      const { webview } = vscode;
      const table = new TableView(webview);
      await webview.getByRole("tab", { name: "Masking", exact: true }).click();
      await expect(webview.getByText("No masked fields")).toBeVisible();
      await addMaskedField(webview, table, "e2eOnly");
      await table.row("e2eOnly").getByRole("checkbox").check();
      await webview.getByTestId("masking-delete").click();
      await expect(webview.getByText("No masked fields")).toBeVisible();
      await expect.poll(() => messages.answered()).toContain("deleteMaskedFields");
      // Every write is in before the text editor opens, so it reads the file the delete left.
      return await vscode.readAsText();
    });
    expect(maskedNames(text)).toEqual([]);
  });
});

/** Adds a masked field and names it in the row's inline editor, which the empty name leaves as the row's only button. */
async function addMaskedField(webview: FrameLocator, table: TableView, name: string): Promise<void> {
  const rows = await table.rows.filter({ has: webview.getByRole("button") }).count();
  await webview.getByTestId("masking-create").click();
  const added = table.rows.filter({ hasNot: webview.getByRole("button", { name: /\S/ }) });
  await expect(table.rows.filter({ has: webview.getByRole("button") })).toHaveCount(rows + 1);
  await added.getByRole("button").click();
  const input = added.getByRole("textbox");
  await expect(input).toBeFocused();
  await input.fill(name);
  await input.press("Enter");
  await expect(table.row(name).getByRole("button", { name, exact: true })).toBeVisible();
}
