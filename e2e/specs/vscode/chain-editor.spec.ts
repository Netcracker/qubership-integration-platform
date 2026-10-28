/**
 * The chain editor in VS Code: the structure edits the canvas sends to the extension, the chain
 * file the extension writes for each, and the way from a chain call to the chain it calls and back.
 *
 * Every edit case opens a fixture chain in its own browser context, whose in-memory file system
 * starts from the fixture workspace, makes one edit on the canvas, and reads the file back as text.
 * The canvas draws an edit from the extension's answer, so only the file shows whether it was
 * written. The extension opens a chain in a new tab and posts no answer, so a navigation case
 * asserts the tab and the graph it draws.
 *
 * `getConnections` stays uncovered: no screen calls it, since the editor reads the dependencies
 * from `getChain`.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/response/` run alone, each turning
 * only its own case red unless a line names more:
 * - `getElements` answering `[]`: the element list stayed empty;
 * - `getLibrary` with the Log Record element renamed: the drop found no Log Record to drag;
 * - `getLibraryElementByType` answering `{}`: the form's type badge read `http-trigger`;
 * - `createConnection`, `deleteConnections`, `deleteElements`, `ungroupElements`, and
 *   `transferElement` each skipping `writeMainChain`: the file read timed out on the old text,
 *   and for `deleteElements` the guard also caught the extension host's `EntryNotFound` for the
 *   script resource it had deleted;
 * - `createElement` skipping `writeMainChain`: the drop drew no Log Record, and the guard caught
 *   `ElementId not found`;
 * - `groupElements` skipping `writeMainChain`: both cases that group turned red, on
 *   `ElementId not found`;
 * - `transferElement` copying the element instead of moving it: the top level still held it;
 * - `deleteElements` leaving the resource files: Quick Open still listed the script's resource;
 * - `cloneElements` giving the clone the id of its source: the canvas drew two nodes, not three;
 * - `cloneElements` writing no resource for the clone: the clone kept the source's
 *   `propertiesFilename`;
 * - `findChainByElementId` answering `null`: the sequence diagram lost the callee's name;
 * - `navigateInNewTab` and `openChainInNewTab` each answering `undefined`: no tab opened, and the
 *   guard caught the extension host's error;
 * - `getElementsByType` answering `[]`: the chain call's form lost the callee's trigger, and the
 *   chain trigger lost the chain that calls it, so both navigation cases turned red.
 */
import type { Locator } from "@playwright/test";
import { test, expect } from "../../support/vscode-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { normalizeIds, readGolden } from "../../support/vscode-golden.js";
import {
  CALLEE_CHAIN_ID,
  CALLER_CHAIN_ID,
  CHAIN_CALL,
  CHAIN_TRIGGER,
  chainFile,
  fixture,
  HTTP_TRIGGER,
  MCP_TRIGGER,
  MOCHA_CHAIN_ID,
  parseChain,
  resourceFile,
  SCRIPT,
  SERVICE_CALL,
  SERVICES_CHAIN_ID,
  SERVICES_TRIGGER,
  type ChainElement,
  type ChainFile,
} from "../../support/vscode-workspace.js";

const CHAIN = chainFile(MOCHA_CHAIN_ID);
/** The file a script element's code lives in, beside the chain file. */
const scriptResource = (elementId: string) => resourceFile(MOCHA_CHAIN_ID, elementId, "script.cip.groovy");
const SERVICES_CHAIN = chainFile(SERVICES_CHAIN_ID);

const ids = (elements: readonly ChainElement[]) => elements.map((each) => each.id).sort();
const edges = (chain: ChainFile) => chain.content.dependencies.map(({ from, to }) => ({ from, to }));

/** The element id of a node, read off `@xyflow/react`'s `rf__node-<id>`. */
async function nodeId(node: Locator): Promise<string> {
  const testId = await node.getAttribute("data-testid");
  if (!testId) throw new Error("the node carries no data-testid");
  return testId.replace(/^rf__node-/, "");
}

test.describe("chain editor", () => {
  test("the canvas, element list, and element form read the chain", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    await expect(graph.nodes).toHaveCount(2);
    await expect(graph.edge(HTTP_TRIGGER, SCRIPT)).toBeAttached();

    await vscode.webview.getByRole("button", { name: "Right Panel" }).click();
    await expect(vscode.webview.getByTitle("HTTP Trigger (HTTP Trigger)")).toBeVisible();
    await expect(vscode.webview.getByTitle("Script (Script)")).toBeVisible();
    await expect.poll(() => messages.answered()).toContain("getElements");

    // The trigger's form holds no script editor, whose Monaco workers fail to load in the webview (#1223).
    const form = await graph.openElement(HTTP_TRIGGER);
    // The element's name, then its type's title from the library element; without one the badge shows `http-trigger`.
    await expect(form.getByRole("button", { name: "Edit name" })).toHaveText(/^HTTP Trigger\s*HTTP Trigger$/);
    await expect.poll(() => messages.answered()).toContain("getLibraryElementByType");
  });

  test("a dropped element and a drawn connection are written", async ({ vscode, messages }) => {
    const original = fixture(CHAIN);
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await graph.dropFromLibrary("Log Record");
    const created = graph.nodes.filter({ hasText: "Log Record" });
    await expect(created).toHaveCount(1);
    const logRecord = await nodeId(created);
    await graph.connect(SCRIPT, logRecord);
    await expect(graph.edge(SCRIPT, logRecord)).toBeAttached();

    const text = await vscode.readAsText((each) => edges(parseChain(each)).length === 2);
    expect(normalizeIds(text, original)).toBe(readGolden("chain-add-element-and-connection.chain.cip.yaml"));
    expect(messages.answered()).toEqual(expect.arrayContaining(["createElement", "createConnection"]));
  });

  test("a deleted connection leaves the chain file", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await graph.clickEdge(HTTP_TRIGGER, SCRIPT);
    await vscode.page.keyboard.press("Delete");
    await expect(graph.edge(HTTP_TRIGGER, SCRIPT)).toHaveCount(0);

    const chain = parseChain(await vscode.readAsText((each) => edges(parseChain(each)).length === 0));
    expect(ids(chain.content.elements)).toEqual([SCRIPT, HTTP_TRIGGER].sort());
    expect(messages.answered()).toContain("deleteConnections");
  });

  test("a deleted element leaves the chain file with its connection", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await graph.node(SCRIPT).click({ button: "right" });
    await vscode.webview.getByRole("menuitem", { name: "Delete", exact: true }).click();
    await expect(graph.nodes).toHaveCount(1);

    const chain = parseChain(await vscode.readAsText((each) => parseChain(each).content.elements.length === 1));
    expect(ids(chain.content.elements)).toEqual([HTTP_TRIGGER]);
    expect(edges(chain)).toEqual([]);
    // The extension deletes the script's resource after the chain file, before it answers.
    await expect.poll(() => messages.answered()).toContain("deleteElements");
    expect(await vscode.listsFile(scriptResource(SCRIPT)), "the deleted script's resource is listed").toBe(false);
  });

  test("a group holds its elements until it is ungrouped", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await graph.node(HTTP_TRIGGER).click();
    await graph.node(SCRIPT).click({ modifiers: ["Control"] });
    await graph.node(SCRIPT).click({ button: "right" });
    await vscode.webview.getByRole("menuitem", { name: "Group", exact: true }).click();
    await expect(graph.nodes).toHaveCount(3);

    let chain = parseChain(await vscode.readAsText((each) => parseChain(each).content.elements.length === 1));
    const [container] = chain.content.elements;
    expect(container.type).toBe("container");
    expect(ids(container.children ?? [])).toEqual([SCRIPT, HTTP_TRIGGER].sort());

    await vscode.reopenWith("CIP Chain File Visual Editor");
    await expect(graph.nodes).toHaveCount(3);
    await graph.node(container.id).click({ button: "right", position: { x: 12, y: 12 } });
    await vscode.webview.getByRole("menuitem", { name: "Ungroup", exact: true }).click();
    await expect(graph.nodes).toHaveCount(2);

    chain = parseChain(await vscode.readAsText((each) => parseChain(each).content.elements.length === 2));
    expect(ids(chain.content.elements)).toEqual([SCRIPT, HTTP_TRIGGER].sort());
    expect(edges(chain)).toEqual([{ from: HTTP_TRIGGER, to: SCRIPT }]);
    expect(messages.answered()).toEqual(expect.arrayContaining(["groupElements", "ungroupElements"]));
  });

  test("a pasted copy is a clone with an id of its own", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await graph.node(SCRIPT).click({ button: "right" });
    await vscode.webview.getByRole("menuitem", { name: "Copy", exact: true }).click();
    const box = await graph.canvas.boundingBox();
    if (!box) throw new Error("the chain canvas is not on the page");
    await graph.canvas.click({ button: "right", position: { x: 60, y: box.height - 60 } });
    await vscode.webview.getByRole("menuitem", { name: "Paste", exact: true }).click();
    await expect(graph.nodes).toHaveCount(3);

    const chain = parseChain(await vscode.readAsText((each) => parseChain(each).content.elements.length === 3));
    const scripts = chain.content.elements.filter((each) => each.type === "script");
    expect(scripts).toHaveLength(2);
    expect(scripts.map((each) => each.id)).toContain(SCRIPT);
    const clone = scripts.find((each) => each.id !== SCRIPT);
    if (!clone) throw new Error("the clone has the id of its source");
    // The clone keeps its code in a resource of its own, with the source's code in it.
    expect(clone.properties?.propertiesFilename).toBe(`${clone.id}.element.script.cip.groovy`);
    await vscode.openFile(scriptResource(clone.id));
    expect((await vscode.readText((each) => each.trim() !== "")).trim()).toBe(fixture(scriptResource(SCRIPT)).trim());
    expect(messages.answered()).toContain("cloneElements");
  });

  test("an element dragged onto a container moves into it", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(SERVICES_CHAIN);
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(4);

    await graph.node(SERVICES_TRIGGER).click();
    await graph.node(SERVICE_CALL).click({ modifiers: ["Control"] });
    await graph.node(SERVICE_CALL).click({ button: "right" });
    await vscode.webview.getByRole("menuitem", { name: "Group", exact: true }).click();
    await expect(graph.nodes).toHaveCount(5);
    const container = graph.nodes.filter({ hasText: "Container" });
    const containerId = await nodeId(container);

    await graph.dragNode(MCP_TRIGGER, container, { x: 30, y: 30 });

    const chain = parseChain(
      await vscode.readAsText((each) => {
        const group = parseChain(each).content.elements.find((element) => element.id === containerId);
        return (group?.children ?? []).length === 3;
      }),
    );
    const group = chain.content.elements.find((element) => element.id === containerId);
    expect(ids(group?.children ?? [])).toEqual([SERVICES_TRIGGER, SERVICE_CALL, MCP_TRIGGER].sort());
    // Moved, not copied: the top level holds the container and the fourth element only.
    expect(ids(chain.content.elements)).not.toContain(MCP_TRIGGER);
    expect(messages.answered()).toContain("transferElement");
  });

  test("the sequence diagram names the chain a chain call calls", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(chainFile(CALLER_CHAIN_ID));
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await vscode.webview.getByTestId("chain-sequence-diagram").click();
    const diagram = vscode.webview.getByRole("dialog", { name: "Chain Sequence Diagram" });
    await expect(diagram).toContainText("CIP chain trigger call: vsc-fixture-chain-callee");
    expect(messages.answered()).toContain("findChainByElementId");
  });

  test("Go to chain on a chain call opens the chain it calls", async ({ vscode, messages, sentMessage }) => {
    await vscode.open();
    await vscode.openFile(chainFile(CALLER_CHAIN_ID));
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(CHAIN_CALL);
    // The trigger list comes from every chain in the workspace.
    await expect(form.getByText("vsc-fixture-chain-callee", { exact: true })).toBeVisible();
    await form.getByRole("button", { name: "Go to chain" }).click();

    await expect(vscode.activeTab).toContainText(`${CALLEE_CHAIN_ID}.chain.cip.yaml`);
    await expect(graph.node(CHAIN_TRIGGER)).toContainText("Chain Trigger");
    await sentMessage("navigateInNewTab");
    expect(messages.answered()).toContain("getElementsByType");
  });

  test("a chain trigger opens the chain that calls it", async ({ vscode, sentMessage }) => {
    await vscode.open();
    await vscode.openFile(chainFile(CALLEE_CHAIN_ID));
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(CHAIN_TRIGGER);
    await form.getByRole("button", { name: "1 chain" }).click();
    await vscode.webview.getByRole("menuitem", { name: "vsc-fixture-chain-caller", exact: true }).click();

    await expect(vscode.activeTab).toContainText(`${CALLER_CHAIN_ID}.chain.cip.yaml`);
    await expect(graph.node(CHAIN_CALL)).toContainText("Chain Call");
    await sentMessage("openChainInNewTab");
  });
});
