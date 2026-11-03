/**
 * The chain editor opens inside VS Code for the web and draws the chain the extension reads.
 *
 * The UI talks to the extension only when it detects the webview, and before the fix of
 * `isVsCodeWebview()` it sent its requests to `RestApi` and stayed on its loading screen, so the
 * drawn graph and the answered `getChain` are both the evidence.
 *
 * Shown failing: `getChain` in `vscode-extension/src/web/response/apiRouter.ts` throwing turned the
 * case red on the guard (`[Extension Host] Failed to fetch data for CIP Extension API`) and on the
 * missing nodes; `navigate` answering nothing left the editor with no graph. The guard itself: a
 * `console.error`, a scheduled throw in the content frame, and a throw at the start of the
 * extension's `activate` each turned the case red.
 */
import { test, expect } from "../../support/vscode-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { chainFile, HTTP_TRIGGER, MOCHA_CHAIN_ID, SCRIPT } from "../../support/vscode-workspace.js";

test.describe("smoke", () => {
  test("the chain editor draws the chain the extension reads", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(chainFile(MOCHA_CHAIN_ID));

    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);
    await expect(graph.node(HTTP_TRIGGER)).toContainText("HTTP Trigger");
    await expect(graph.node(SCRIPT)).toContainText("Script");
    await expect(graph.edge(HTTP_TRIGGER, SCRIPT)).toBeAttached();
    await expect.poll(() => messages.answered()).toContain("getChain");
  });
});
