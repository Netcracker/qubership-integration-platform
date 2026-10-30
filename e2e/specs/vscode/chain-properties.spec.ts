/**
 * The Properties and Masking tabs of the VS Code chain editor, and what each writes to the chain
 * file.
 *
 * In VS Code the Properties tab carries a Group field, which Apply saves through `moveChain` as the
 * chain's `metaInfo.group` before `updateChain` writes the rest.
 *
 * Deleting the first masked field used to leave it in the file, because `deleteMaskedFields` in
 * `chainApiModify.ts` tested the found index with `if (index)`, which is false for index 0 (#1222).
 * One case deletes the only field, at index 0, and the other deletes the second of two fields.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/response/` run alone:
 * - `updateChain` skipping `writeMainChain`: the file read timed out on the old name;
 * - `changeFolder` skipping `writeMainChain`: the group was missing from the written file;
 * - `updateMaskedField` skipping `writeMainChain`: the file held a field with an empty name;
 * - `getMaskedFields` answering no fields: the reopened Masking tab listed none;
 * - `createMaskedField` skipping `writeMainChain`: both masked-field cases lost the row, and the
 *   guard caught `Masked Field not found`;
 * - `deleteMaskedFields` skipping `writeMainChain`: the file read timed out on two fields;
 * - an undeclared `console.error` in the first-field case while it carried `test.fail()`: the guard
 *   turned it red, and with the guard no longer clearing `expectedStatus` the same error passed as
 *   the expected failure;
 * - `deleteMaskedFields` before the #1222 fix: the first-field case found `e2eOnly` in the file.
 */
import type { FrameLocator } from "@playwright/test";
import { test, expect } from "../../support/vscode-guard.js";
import { TableView } from "../../pages/TableView.js";
import { addTag } from "../../support/pick-option.js";
import { chainFile, MOCHA_CHAIN_ID, parseChain } from "../../support/vscode-workspace.js";

const CHAIN = chainFile(MOCHA_CHAIN_ID);

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
    await addTag(webview, field(webview, "combobox", "Labels"), "e2e-label");
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
    // Opened again, the tab lists what getMaskedFields reads from the file.
    await vscode.reopenWith("CIP Chain File Visual Editor");
    await webview.getByRole("tab", { name: "Masking", exact: true }).click();
    await expect(table.row("e2eFirst")).toBeVisible();
    await expect(table.rows).toHaveCount(1);
    expect(messages.answered()).toEqual(expect.arrayContaining(["getMaskedFields", "createMaskedField", "updateMaskedField"]));
  });

  test("a deleted first masked field leaves the chain file", async ({ vscode, messages }) => {
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
    expect(maskedNames(await vscode.readAsText((each) => parseChain(each).id === MOCHA_CHAIN_ID))).toEqual([]);
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
