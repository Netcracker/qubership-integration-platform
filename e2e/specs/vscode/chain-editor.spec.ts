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
 * Shown failing, each by an edit in `vscode-extension/src/web/response/`:
 * - `getElements` answering `[]`: the element list stayed empty;
 * - `createConnection`, `deleteConnections`, `deleteElements`, `ungroupElements`, and
 *   `transferElement` each skipping `writeMainChain`: the file read timed out on the old text,
 *   and for `deleteElements` the guard also caught the extension host's `EntryNotFound` for the
 *   script resource it had deleted; `createConnection` alone turned only its own case red;
 * - `cloneElements` giving the clone the id of its source: the canvas drew two nodes, not three;
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
import { chainFile, fixture, parseChain as parse, type ChainElement, type ChainFile } from "../../support/vscode-workspace.js";

/** The mocha workspace's chain: an HTTP trigger connected to a script. */
const CHAIN = chainFile("1419390b-7423-4fab-853f-67c653e16a86");
const HTTP_TRIGGER = "7f15ea75-516e-4104-81c6-e68550a9b9e5";
const SCRIPT = "54b4728d-7000-4350-b310-c58b792311c5";

/** Four elements bound to services, with no container among them. */
const SERVICES_CHAIN = chainFile("e2e20004-0000-4000-8000-000000000001");
const SERVICES_TRIGGER = "e2e20004-0000-4000-8000-000000000002";
const SERVICE_CALL = "e2e20004-0000-4000-8000-000000000003";
const MCP_TRIGGER = "e2e20004-0000-4000-8000-000000000005";

/** A chain whose chain call targets the trigger of the callee chain. */
const CALLER = "e2e20002-0000-4000-8000-000000000001";
const CHAIN_CALL = "e2e20002-0000-4000-8000-000000000003";
const CALLEE = "e2e20003-0000-4000-8000-000000000001";
const CHAIN_TRIGGER = "e2e20003-0000-4000-8000-000000000002";

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

    const form = await graph.openElement(SCRIPT);
    await expect(form.getByRole("button", { name: "Edit name" })).toContainText("Script");
    await expect.poll(() => messages.answered()).toContain("getLibraryElementByType");
  });

  test("a dropped element and a drawn connection are written", async ({ vscode, messages }) => {
    const original = fixture(CHAIN);
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    // A drag into the cross-origin webview is sometimes lost before it starts (1 of 11 runs), so the
    // drop repeats while no createElement has left the webview.
    await expect(async () => {
      if (!messages.sent("createElement")) await graph.dropFromLibrary("Log Record");
      expect(messages.sent("createElement")).toBe(true);
    }).toPass();
    const created = graph.nodes.filter({ hasText: "Log Record" });
    await expect(created).toHaveCount(1);
    const logRecord = await nodeId(created);
    await graph.connect(SCRIPT, logRecord);
    await expect(graph.edge(SCRIPT, logRecord)).toBeAttached();

    const text = await vscode.readAsText((each) => edges(parse(each)).length === 2);
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

    const chain = parse(await vscode.readAsText((each) => edges(parse(each)).length === 0));
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

    const chain = parse(await vscode.readAsText((each) => parse(each).content.elements.length === 1));
    expect(ids(chain.content.elements)).toEqual([HTTP_TRIGGER]);
    expect(edges(chain)).toEqual([]);
    expect(messages.answered()).toContain("deleteElements");
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

    let chain = parse(await vscode.readAsText((each) => parse(each).content.elements.length === 1));
    const [container] = chain.content.elements;
    expect(container.type).toBe("container");
    expect(ids(container.children ?? [])).toEqual([SCRIPT, HTTP_TRIGGER].sort());

    await vscode.reopenWith("CIP Chain File Visual Editor");
    await expect(graph.nodes).toHaveCount(3);
    await graph.node(container.id).click({ button: "right", position: { x: 12, y: 12 } });
    await vscode.webview.getByRole("menuitem", { name: "Ungroup", exact: true }).click();
    await expect(graph.nodes).toHaveCount(2);

    chain = parse(await vscode.readAsText((each) => parse(each).content.elements.length === 2));
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

    const chain = parse(await vscode.readAsText((each) => parse(each).content.elements.length === 3));
    const scripts = chain.content.elements.filter((each) => each.type === "script");
    expect(scripts).toHaveLength(2);
    expect(scripts.map((each) => each.id)).toContain(SCRIPT);
    expect(new Set(scripts.map((each) => each.id)).size).toBe(2);
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

    const chain = parse(
      await vscode.readAsText((each) => {
        const group = parse(each).content.elements.find((element) => element.id === containerId);
        return (group?.children ?? []).length === 3;
      }),
    );
    const group = chain.content.elements.find((element) => element.id === containerId);
    expect(ids(group?.children ?? [])).toEqual([SERVICES_TRIGGER, SERVICE_CALL, MCP_TRIGGER].sort());
    expect(messages.answered()).toContain("transferElement");
  });

  test("the sequence diagram names the chain a chain call calls", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(chainFile(CALLER));
    const graph = new ChainGraphPage(vscode.webview);
    await expect(graph.nodes).toHaveCount(2);

    await vscode.webview.getByTestId("chain-sequence-diagram").click();
    const diagram = vscode.webview.getByRole("dialog", { name: "Chain Sequence Diagram" });
    await expect(diagram).toContainText("CIP chain trigger call: vsc-fixture-chain-callee");
    expect(messages.answered()).toContain("findChainByElementId");
  });

  test("Go to chain on a chain call opens the chain it calls", async ({ vscode, messages, sentMessage }) => {
    await vscode.open();
    await vscode.openFile(chainFile(CALLER));
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(CHAIN_CALL);
    // The trigger list comes from every chain in the workspace.
    await expect(form.getByText("vsc-fixture-chain-callee", { exact: true })).toBeVisible();
    await form.getByRole("button", { name: "Go to chain" }).click();

    await expect(vscode.activeTab).toContainText(`${CALLEE}.chain.cip.yaml`);
    await expect(graph.node(CHAIN_TRIGGER)).toContainText("Chain Trigger");
    await sentMessage("navigateInNewTab");
    expect(messages.answered()).toContain("getElementsByType");
  });

  test("a chain trigger opens the chain that calls it", async ({ vscode, sentMessage }) => {
    await vscode.open();
    await vscode.openFile(chainFile(CALLEE));
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(CHAIN_TRIGGER);
    await form.getByRole("button", { name: "1 chain" }).click();
    await vscode.webview.getByRole("menuitem", { name: "vsc-fixture-chain-caller", exact: true }).click();

    await expect(vscode.activeTab).toContainText(`${CALLER}.chain.cip.yaml`);
    await expect(graph.node(CHAIN_CALL)).toContainText("Chain Call");
    await sentMessage("openChainInNewTab");
  });
});
