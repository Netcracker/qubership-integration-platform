/**
 * Image export in VS Code: `CIP: Export All Chain Graphs as PNG` exports every chain of the
 * workspace into a folder picked in the workbench's folder dialog. The extension opens a webview
 * that draws each chain, sends each image back with `saveExportedImage`, and reports progress, a
 * skipped chain, a failed chain, and the end of the export; the extension shows each in a
 * notification.
 *
 * The workspace holds eight chains, one of them empty, which the export skips with a warning. A
 * chain fails alone when its image cannot be written: `exports/` holds a folder with the name of
 * the callee chain's PNG, so the write of that image fails with `EntryIsADirectory` and the export
 * goes on with the next chain. An unparsable chain cannot serve: `listChainExportTargets` parses
 * every chain with no catch, so one such chain stops the export before the webview opens.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/response/apiRouter.ts`:
 * - `startup` answering `{}`, with no export operation: the webview never showed `Export
 *   completed` in either case;
 * - `saveExportedImage` not writing the file: the explorer listed no PNG, and the second case's
 *   blocked write did not fail;
 * - `exportImagesProgress` throwing: no image was saved, so neither case found its summary, and the
 *   guard caught the extension host's errors;
 * - `exportImagesComplete` not ending the progress: the progress notification stayed open in both
 *   cases;
 * - `exportImagesItemWarning` showing no warning: the empty chain's notification never appeared;
 * - `exportImagesItemFailed` showing no error: the failed chain's notification never appeared.
 */
import { test, expect, withAnyStack } from "../../support/vscode-guard.js";
import type { VsCodePage } from "../../pages/VsCodePage.js";

const COMMAND = "CIP: Export All Chain Graphs as PNG";
const OPEN_LABEL = "Select output folder";
const PROGRESS = "Exporting chain PNG files";

const EMPTY_CHAIN = "e2e20006-0000-4000-8000-000000000001";
/** The callee chain, whose PNG `exports/` holds a folder in place of. */
const BLOCKED_CHAIN = "e2e20003-0000-4000-8000-000000000001";
/** Every chain of the workspace with an element, in the order the export takes them. */
const DRAWN_CHAINS = [
  "1419390b-7423-4fab-853f-67c653e16a86",
  "e2e20001-0000-4000-8000-000000000001",
  "e2e20002-0000-4000-8000-000000000001",
  BLOCKED_CHAIN,
  "e2e20004-0000-4000-8000-000000000001",
  "e2e20005-0000-4000-8000-000000000001",
  "e2e20008-0000-4000-8000-000000000001",
];

/**
 * The workspace file the blocked write targets, and what the extension logs for it. The stack names
 * a line of the workbench's file system extension, which changes with the VS Code build.
 */
const BLOCKED_URI = `vscode-test-web://mount/exports/${BLOCKED_CHAIN}.png`;
const BLOCKED_ERROR = `EntryIsADirectory (FileSystemError): ${BLOCKED_URI}`;
/** The workbench logs every error notification. */
const BLOCKED_NOTIFICATION = `Chain "${BLOCKED_CHAIN}": ${BLOCKED_URI}`;
const BLOCKED_ERRORS = [
  withAnyStack(`%c  ERR color: #f33 [Extension Host] Failed to fetch data for CIP Extension API ${BLOCKED_ERROR}`),
  withAnyStack(`%c[Extension Host] %cFailed to fetch data for CIP Extension API color: blue color:  ${BLOCKED_ERROR}`),
  BLOCKED_NOTIFICATION,
];

/** Runs the export into `folder` and waits for its summary in the webview. */
async function exportInto(vscode: VsCodePage, folder: string): Promise<void> {
  await vscode.runCommand(COMMAND);
  await vscode.selectFolder(folder, OPEN_LABEL);
  await expect(vscode.notifications.getByText(PROGRESS)).toBeVisible();
  await expect(vscode.webview.getByText("Export completed", { exact: true })).toBeVisible();
  await expect(vscode.notifications.getByText(PROGRESS)).toBeHidden();
}

/**
 * Opens the PNG of `chainId` in the media preview, which shows its dimensions in the status bar.
 * Quick Open finds no file at the workspace root (it answered "No matching results" for a PNG the
 * explorer listed), so a PNG there is opened from the explorer.
 */
async function expectImage(vscode: VsCodePage, folder: string, chainId: string): Promise<void> {
  if (folder === "/") await vscode.page.getByRole("treeitem", { name: `${chainId}.png`, exact: true }).click();
  else await vscode.openFile(`${folder.slice(1)}${chainId}.png`);
  await expect(vscode.activeTab).toContainText(chainId);
  await expect(vscode.statusBar.getByText(/^[1-9]\d*x[1-9]\d*$/)).toBeVisible();
}

test.describe("export images", () => {
  test("every chain is exported as a PNG, the empty one skipped", async ({ vscode, messages }) => {
    await vscode.open();
    await exportInto(vscode, "/");

    await expect(vscode.webview.getByText("7 of 8 image file(s) exported. 1 empty chain(s) skipped.", { exact: true })).toBeVisible();
    await expect(vscode.notifications.getByText(`Chain "${EMPTY_CHAIN}" is empty. No image file was created.`, { exact: true })).toBeVisible();
    // The explorer can list the last PNGs after the export reports its end. A row added under the
    // pointer, which rests on the PNG just clicked, brings up that PNG's hover over the next one, so
    // the clicks wait until every PNG is listed.
    await expect(vscode.page.getByRole("treeitem", { name: /\.png$/ })).toHaveCount(DRAWN_CHAINS.length);
    for (const chainId of DRAWN_CHAINS) await expectImage(vscode, "/", chainId);
    expect(messages.answered()).toEqual(
      expect.arrayContaining(["saveExportedImage", "exportImagesProgress", "exportImagesItemWarning", "exportImagesComplete"]),
    );
  });

  test("an image that cannot be written fails its chain alone", async ({ vscode, messages, expectConsoleError }) => {
    for (const message of BLOCKED_ERRORS) expectConsoleError(message);
    await vscode.open();
    await exportInto(vscode, "/exports/");

    await expect(vscode.webview.getByText("6 of 8 image file(s) exported. 1 empty chain(s) skipped. 1 failed.", { exact: true })).toBeVisible();
    await expect(vscode.notifications.getByText(BLOCKED_NOTIFICATION, { exact: true })).toBeVisible();
    // The chains after the failed one are written too.
    for (const chainId of DRAWN_CHAINS.slice(DRAWN_CHAINS.indexOf(BLOCKED_CHAIN) + 1)) await expectImage(vscode, "/exports/", chainId);
    expect(messages.answered()).toContain("exportImagesItemFailed");
  });
});
