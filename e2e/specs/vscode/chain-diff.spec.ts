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
 * Shown failing, each by an edit in `vscode-extension/src/web/chainDiffEditor.ts`:
 * - `comparedDocumentsRequest` answering the original document on both sides: the table listed
 *   no change, and the navigation case found no link to the second version;
 * - `navigateComparedDocumentInNewTab` opening no editor: the active tab stayed on the diff.
 *
 * Opening the original document for the right side changes nothing a case sees: the handler
 * resolves the chain by the id in the link, whichever document it starts from.
 */
import { test, expect } from "../../support/vscode-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { chainFile } from "../../support/vscode-workspace.js";

/** The mocha workspace's chain, and its second version with an id of its own. */
const ORIGINAL = "1419390b-7423-4fab-853f-67c653e16a86";
const MODIFIED = "e2e20001-0000-4000-8000-000000000001";
const SCRIPT = "54b4728d-7000-4350-b310-c58b792311c5";

/** The values the change table shows in its Type column. */
const CHANGE_TYPE = /^(element|chain property|element property|connection)$/;

test.describe("chain diff", () => {
  test("the diff of two chain versions lists the renamed element", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(chainFile(ORIGINAL));
    await expect(new ChainGraphPage(vscode.webview).nodes).toHaveCount(2);

    await vscode.compareActiveFileWith(chainFile(MODIFIED));
    await expect(vscode.activeTab).toContainText(`${ORIGINAL}.chain.cip.yaml - ${MODIFIED}.chain.cip.yaml`);
    await vscode.webview.getByText("Table", { exact: true }).click();

    await expect(vscode.webview.getByRole("cell", { name: CHANGE_TYPE })).toHaveCount(1);
    const change = vscode.webview.getByRole("row").filter({ has: vscode.webview.getByRole("cell", { name: "element property", exact: true }) });
    await expect(change).toContainText('"Script"');
    await expect(change).toContainText('"Script v2"');
    expect(messages.answered()).toContain("comparedDocumentsRequest");
  });

  test("a chain link in the diff opens that chain", async ({ vscode, sentMessage }) => {
    await vscode.open();
    await vscode.openFile(chainFile(ORIGINAL));
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await vscode.compareActiveFileWith(chainFile(MODIFIED));
    await vscode.webview.getByText("vsc-fixture-chain-v2", { exact: true }).click();

    await expect(vscode.activeTab).toContainText(`${MODIFIED}.chain.cip.yaml`);
    await expect(vscode.activeTab).not.toContainText(ORIGINAL);
    await expect(graph.node(SCRIPT)).toContainText("Script v2");
    await sentMessage("navigateComparedDocumentInNewTab");
  });
});
