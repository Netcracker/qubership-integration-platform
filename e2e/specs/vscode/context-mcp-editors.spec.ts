/**
 * The context service and MCP service editors in VS Code, and a chain file that no longer parses.
 *
 * Each editor shows one Parameters form, and Save writes the service file.
 *
 * Saving a context service writes its labels to the file. `updateContextService` in
 * `serviceApiModify.ts` used to drop them (#1225).
 *
 * A chain file that does not parse opens on `Cannot open the file` with the parser's message:
 * `getNavigateUri` in `apiRouter.ts` lets the parse error reach the webview as the error of the
 * `navigate` answer. It used to answer `/services`, a route the extension's UI does not have, and
 * the editor showed `Not implemented yet` (#1224). The case breaks a chain itself, through the text
 * editor, since a broken fixture would stop every image export.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/response/` run alone:
 * - `getContextService` answering `{}`: both context cases found no name in the form;
 * - `updateContextService` skipping `writeMainService`: both context cases timed out reading the
 *   file;
 * - `getMcpService` answering `{}`: the MCP form showed no name;
 * - `updateMcpService` dropping the labels: the MCP file had no label;
 * - `updateContextService` before the #1225 fix: the label case found no labels in the file;
 * - `getNavigateUri` catching the parse error, as before the #1224 fix: the broken-file case found
 *   no `Cannot open the file`.
 */
import type { FrameLocator } from "@playwright/test";
import { test, expect, extensionHostErrors, withAnyStack } from "../../support/vscode-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import type { VsCodePage } from "../../pages/VsCodePage.js";
import type { MessageRecorder } from "../../support/vscode-messages.js";
import { addTag } from "../../support/pick-option.js";
import { chainFile, MOCHA_CHAIN_ID, parseService, serviceFile, type ServiceFile } from "../../support/vscode-workspace.js";

const CONTEXT = serviceFile("269327ed-e44d-491e-9f86-4a3010d63108", "context-service");
const MCP = serviceFile("0edab1e2-c311-4718-8411-adb16bfa5d54", "mcp-service");

const CHAIN = chainFile(MOCHA_CHAIN_ID);
const PARSE_ERROR = "Error: Failed to parse content: not valid JSON or YAML";
/** What the extension host logs when the chain file does not parse: the file, then the failed `navigate`. */
const PARSE_ERRORS = [
  ...extensionHostErrors(`Chain file vscode-test-web://mount/${CHAIN} can't be parsed from CIP Extension API`, PARSE_ERROR),
  ...extensionHostErrors("Failed to fetch data for CIP Extension API", PARSE_ERROR),
];

/** Adds `label` in the Labels field and closes the option list, which stays open over the Save button. */
async function addLabel(webview: FrameLocator, label: string): Promise<void> {
  const labels = webview.getByRole("combobox", { name: "Labels" });
  await addTag(webview, labels, label);
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
    await vscode.open();
    await vscode.openFile(CONTEXT);
    const { webview } = vscode;
    await expect(webview.getByRole("textbox", { name: "Name" })).toHaveValue("vsc-fixture-context");
    await webview.getByRole("textbox", { name: "Description" }).fill("labelled in VS Code");
    await addLabel(webview, "e2e-label");
    // The description shows that the save reached the file.
    const service = await saveAndRead(vscode, messages, "updateContextService", (each) => each.content.description === "labelled in VS Code");
    expect(service.content.labels).toEqual(["e2e-label"]);
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
    for (const error of PARSE_ERRORS) expectConsoleError(withAnyStack(error));
    await vscode.open();
    await vscode.openFile(CHAIN);
    await expect(new ChainGraphPage(vscode.webview).nodes).toHaveCount(2);
    await vscode.runCommand("View: Reopen Editor with Text Editor");
    await vscode.lines(vscode.textEditor).click();
    await page.keyboard.press("Control+A");
    // A mapping nested on one line is not YAML, and Monaco closes no bracket in it.
    await page.keyboard.type("name: a: b");
    // VS Code for the web saves after a delay by default, and Ctrl+S saves at once.
    await page.keyboard.press("Control+S");
    await expect(vscode.activeTab).not.toHaveClass(/\bdirty\b/);
    await vscode.reopenWith("CIP Chain File Visual Editor");
    await expect(vscode.webview.getByText("Cannot open the file", { exact: true })).toBeVisible();
    await expect(vscode.webview.getByText(/not valid JSON or YAML/)).toBeVisible();
    await expect(vscode.webview.getByText("Not implemented yet", { exact: true })).toBeHidden();
  });
});
