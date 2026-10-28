/**
 * The context service and MCP service editors in VS Code, and a chain file that no longer parses.
 *
 * Each editor shows one Parameters form, and Save writes the service file.
 *
 * A label saved on a context service never reaches its file: `updateContextService` in
 * `serviceApiModify.ts` writes the name and the description only, and `getContextService` returns
 * no labels, though the context service schema has them and the form sends them (#1225). The
 * pinned case carries it.
 *
 * A chain file that does not parse opens on `Not implemented yet`, with no error in the editor:
 * `getNavigateUri` in `apiRouter.ts` catches the parse error and answers `/services`, a route the
 * extension's UI does not have. Only the extension host's log names the file (#1224). The pinned
 * case breaks a chain itself, through the text editor, since a broken fixture would stop
 * every image export, and expects the editor to show the parser's message.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/response/`:
 * - `getContextService` answering `{}`: both context cases found no name in the form;
 * - `updateContextService` skipping `writeMainService`: both context cases timed out reading the
 *   file;
 * - `getMcpService` answering `{}`: the MCP form showed no name;
 * - `updateMcpService` dropping the labels: the MCP file had no label;
 * - `updateContextService` writing the labels, the fix: the pinned label case passed, so
 *   `test.fail()` turned it red;
 * - `getNavigateUri` rethrowing the parse error: the editor showed neither `Not implemented yet`
 *   nor the parser's message, so the pinned case failed in `outsideTheDefect`, and the guard
 *   caught the extension host's `Failed to fetch data` lines.
 */
import type { FrameLocator } from "@playwright/test";
import { load } from "js-yaml";
import { test, expect, withAnyStack } from "../../support/vscode-guard.js";
import type { VsCodePage } from "../../pages/VsCodePage.js";
import type { MessageRecorder } from "../../support/vscode-messages.js";
import { notTheKnownDefect, outsideTheDefect } from "../../support/known-defect.js";
import { chainFile } from "../../support/vscode-workspace.js";

const CONTEXT_ID = "269327ed-e44d-491e-9f86-4a3010d63108";
const CONTEXT = `services/${CONTEXT_ID}/${CONTEXT_ID}.context-service.cip.yaml`;
const MCP_ID = "0edab1e2-c311-4718-8411-adb16bfa5d54";
const MCP = `services/${MCP_ID}/${MCP_ID}.mcp-service.cip.yaml`;

const CHAIN = chainFile("1419390b-7423-4fab-853f-67c653e16a86");
const CHAIN_URI = `vscode-test-web://mount/${CHAIN}`;
/** What the extension host logs when the chain file does not parse, in the two forms the workbench prints. */
const PARSE_ERROR = "Error: Failed to parse content: not valid JSON or YAML";
const PARSE_ERRORS = [
  `%c  ERR color: #f33 [Extension Host] Chain file ${CHAIN_URI} can't be parsed from CIP Extension API ${PARSE_ERROR}`,
  `%c[Extension Host] %cChain file ${CHAIN_URI} can't be parsed from CIP Extension API color: blue color:  ${PARSE_ERROR}`,
];

interface ServiceFile {
  name: string;
  content: { description?: string; identifier?: string; instructions?: string; labels?: string[] };
}

const parseService = (text: string) => load(text) as ServiceFile;

/** Types `label` into a tags field and adds it with Enter, once the select's hidden option for it exists. */
async function addLabel(webview: FrameLocator, label: string): Promise<void> {
  const labels = webview.getByRole("combobox", { name: "Labels" });
  await labels.pressSequentially(label);
  await expect(webview.getByRole("option", { name: label, exact: true, includeHidden: true })).toBeAttached();
  await labels.press("Enter");
  // The option list stays open over the Save button.
  await labels.press("Escape");
}

/**
 * Saves the form, waits for the answer to `update`, and reads the service file once `until` holds
 * for it. The MCP form keeps Save enabled after a save (#1226), so the answer marks the end of the
 * save.
 */
async function saveAndRead(vscode: VsCodePage, messages: MessageRecorder, update: string, until: (service: ServiceFile) => boolean): Promise<ServiceFile> {
  await vscode.webview.getByRole("button", { name: "Save" }).click();
  await expect.poll(() => messages.answered()).toContain(update);
  return parseService(await vscode.readAsText((text) => until(parseService(text))));
}

test.describe("context and MCP service editors", () => {
  test("the context service's name and description are written", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CONTEXT);
    const { webview } = vscode;
    const name = webview.getByRole("textbox", { name: "Name" });
    await expect(name).toHaveValue("vsc-fixture-context");

    await name.fill("vsc-context-renamed");
    await webview.getByRole("textbox", { name: "Description" }).fill("set in VS Code");
    const service = await saveAndRead(vscode, messages, "updateContextService", (each) => each.name === "vsc-context-renamed");
    expect({ name: service.name, description: service.content.description }).toEqual({ name: "vsc-context-renamed", description: "set in VS Code" });
    expect(messages.answered()).toEqual(expect.arrayContaining(["getContextService", "updateContextService"]));
  });

  test("a label saved on the context service is written", async ({ vscode, messages }) => {
    test.fail(true, "#1225: updateContextService writes no labels");
    const service = await outsideTheDefect("saving a label and a description and reading the context service file", async () => {
      await vscode.open();
      await vscode.openFile(CONTEXT);
      const { webview } = vscode;
      await expect(webview.getByRole("textbox", { name: "Name" })).toHaveValue("vsc-fixture-context");
      await webview.getByRole("textbox", { name: "Description" }).fill("labelled in VS Code");
      await addLabel(webview, "e2e-label");
      // The description shows that the save reached the file.
      return await saveAndRead(vscode, messages, "updateContextService", (each) => each.content.description === "labelled in VS Code");
    });
    const { labels } = service.content;
    if (labels !== undefined && JSON.stringify(labels) !== JSON.stringify(["e2e-label"])) {
      notTheKnownDefect(`the defect writes no labels, but the file holds ${JSON.stringify(labels)}`);
    }
    expect(labels).toEqual(["e2e-label"]);
  });

  test("the MCP service's parameters and a label are written", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(MCP);
    const { webview } = vscode;
    const name = webview.getByRole("textbox", { name: "Name" });
    await expect(name).toHaveValue("vsc-fixture-mcp");

    await name.fill("vsc-mcp-renamed");
    await webview.getByRole("textbox", { name: "Description" }).fill("set in VS Code");
    await webview.getByRole("textbox", { name: "Identifier" }).fill("vscRenamedMcp");
    await webview.getByRole("textbox", { name: "Instructions" }).fill("Answer from VS Code.");
    await addLabel(webview, "e2e-label");
    const service = await saveAndRead(vscode, messages, "updateMcpService", (each) => each.name === "vsc-mcp-renamed");
    expect({ name: service.name, ...service.content }).toEqual({
      name: "vsc-mcp-renamed",
      description: "set in VS Code",
      identifier: "vscRenamedMcp",
      instructions: "Answer from VS Code.",
      labels: ["e2e-label"],
      migrations: "[100]",
    });
    expect(messages.answered()).toEqual(expect.arrayContaining(["getMcpService", "updateMcpService"]));
  });

  test("a chain file that does not parse opens with an error", async ({ vscode, page, expectConsoleError }) => {
    test.fail(true, "#1224: the editor opens a chain file that does not parse on Not implemented yet, with no error");
    for (const error of PARSE_ERRORS) expectConsoleError(withAnyStack(error));
    const notImplemented = vscode.webview.getByText("Not implemented yet", { exact: true });
    const parseError = vscode.webview.getByText(/not valid JSON or YAML/);
    await outsideTheDefect("breaking the chain file and opening it again", async () => {
      await vscode.open();
      await vscode.openFile(CHAIN);
      await expect(vscode.webview.getByTestId(/^rf__node-/)).toHaveCount(2);
      await vscode.runCommand("View: Reopen Editor with Text Editor");
      await vscode.textEditor.locator(".view-lines").click();
      await page.keyboard.press("Control+A");
      // A mapping nested on one line is not YAML, and Monaco closes no bracket in it.
      await page.keyboard.type("name: a: b");
      // VS Code for the web saves after a delay by default, and Ctrl+S saves at once.
      await page.keyboard.press("Control+S");
      await expect(vscode.activeTab).not.toHaveClass(/\bdirty\b/);
      await vscode.reopenWith("CIP Chain File Visual Editor");
      await expect(notImplemented.or(parseError)).toBeVisible();
    });
    expect(await notImplemented.isVisible(), "the editor shows Not implemented yet").toBe(false);
    await expect(parseError).toBeVisible();
  });
});
