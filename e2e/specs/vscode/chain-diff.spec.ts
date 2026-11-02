/**
 * The chain diff in VS Code: the extension's visual diff of two chain files, opened with
 * `File: Compare Active File With...`, and the way from the diff to a compared chain.
 *
 * The diff runs in one webview, inline in the editor. It asks for both documents with
 * `comparedDocumentsRequest`, which the extension answers with `comparedDocumentsResponse`, so the
 * recorder counts the request by that answer. The extension posts an answer to
 * `navigateComparedDocumentInNewTab` whatever its handler did, so the navigation case asserts the
 * tab it opens.
 *
 * The fixture's second version renames the script element and the chain. The diff compares no
 * chain name, so the table lists one change.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/chainDiffEditor.ts` run alone:
 * - `comparedDocumentsRequest` answering the original document on both sides: the table listed
 *   no change, and the navigation case found no link to the second version;
 * - `navigateComparedDocumentInNewTab` opening no editor: the active tab stayed on the diff.
 *
 * Opening the original document for the right side changes nothing a case sees: the handler
 * resolves the chain by the id in the link, whichever document it starts from.
 */
import { test, expect } from "../../support/vscode-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { chainFile, MOCHA_CHAIN_ID, MOCHA_V2_CHAIN_ID, SCRIPT } from "../../support/vscode-workspace.js";

/** The values the change table shows in its Type column. */
const CHANGE_TYPE = /^(element|chain property|element property|connection)$/;

test.describe("chain diff", () => {
  test("the diff of two chain versions lists the renamed element", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(chainFile(MOCHA_CHAIN_ID));
    await expect(new ChainGraphPage(vscode.webview).nodes).toHaveCount(2);

    await vscode.compareActiveFileWith(chainFile(MOCHA_V2_CHAIN_ID));
    await expect(vscode.activeTab).toContainText(`${MOCHA_CHAIN_ID}.chain.cip.yaml - ${MOCHA_V2_CHAIN_ID}.chain.cip.yaml`);
    await vscode.webview.getByText("Table", { exact: true }).click();

    await expect(vscode.webview.getByRole("cell", { name: CHANGE_TYPE })).toHaveCount(1);
    const change = vscode.webview.getByRole("row").filter({ has: vscode.webview.getByRole("cell", { name: "element property", exact: true }) });
    await expect(change).toContainText('"Script"');
    await expect(change).toContainText('"Script v2"');
    expect(messages.answered()).toContain("comparedDocumentsRequest");
  });

  test("a chain link in the diff opens that chain", async ({ vscode, sentMessage }) => {
    await vscode.open();
    await vscode.openFile(chainFile(MOCHA_CHAIN_ID));
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await vscode.compareActiveFileWith(chainFile(MOCHA_V2_CHAIN_ID));
    await vscode.webview.getByText("vsc-fixture-chain-v2", { exact: true }).click();

    await expect(vscode.activeTab).toContainText(`${MOCHA_V2_CHAIN_ID}.chain.cip.yaml`);
    await expect(vscode.activeTab).not.toContainText(MOCHA_CHAIN_ID);
    await expect(graph.node(SCRIPT)).toContainText("Script v2");
    await sentMessage("navigateComparedDocumentInNewTab");
  });
});
