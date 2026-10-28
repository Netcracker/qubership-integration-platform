/**
 * The chain editor opens inside VS Code for the web and draws the chain the extension reads.
 *
 * The UI talks to the extension only when it detects the webview, and before the fix of
 * `isVsCodeWebview()` it sent its requests to `RestApi` and stayed on its loading screen, so the
 * drawn graph and the answered `getChain` are both the evidence.
 *
 * Shown failing: `getChain` in `vscode-extension/src/web/response/apiRouter.ts` throwing turned the
 * case red on the guard (`[Extension Host] Failed to fetch data for CIP Extension API`) and on the
 * missing nodes; `navigate` answering nothing left the editor with no graph. The guard itself: a `console.error`, a scheduled throw in the content frame, and a
 * throw at the start of the extension's `activate` each turned the case red.
 */
import { test, expect } from "../../support/vscode-guard.js";

const CHAIN = "chains/1419390b-7423-4fab-853f-67c653e16a86/1419390b-7423-4fab-853f-67c653e16a86.chain.cip.yaml";
const HTTP_TRIGGER = "7f15ea75-516e-4104-81c6-e68550a9b9e5";
const SCRIPT = "54b4728d-7000-4350-b310-c58b792311c5";

test.describe("smoke", () => {
  test("the chain editor draws the chain the extension reads", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);

    const webview = vscode.webview;
    await expect(webview.getByTestId(/^rf__node-/)).toHaveCount(2);
    await expect(webview.getByTestId(`rf__node-${HTTP_TRIGGER}`)).toContainText("HTTP Trigger");
    await expect(webview.getByTestId(`rf__node-${SCRIPT}`)).toContainText("Script");
    await expect(webview.getByRole("group", { name: `Edge from ${HTTP_TRIGGER} to ${SCRIPT}`, exact: true })).toBeAttached();
    await expect.poll(() => messages.answered()).toContain("getChain");
  });
});
