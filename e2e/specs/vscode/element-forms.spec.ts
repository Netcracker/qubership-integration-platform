/**
 * Element forms in the VS Code chain editor: what a saved form writes to the chain file and its
 * resources, and what the service fields list from the workspace.
 *
 * A Monaco editor in the webview asks for its worker at the workbench origin, which answers 404,
 * and the frame then raises an uncaught `Event` (#1223); the cases that open one declare it. The
 * pinned case ends soon after its editors mount, before the error arrives on some runs, so it
 * allows the error instead.
 *
 * The mapper's schema load for a GraphQL service call is pinned on #1214: the extension has no
 * handler for `getSpecificationModelSource`, answers it with no payload, and the dialog's schema
 * stays empty.
 *
 * Shown failing, each by an edit in `vscode-extension/src/web/response/` run alone:
 * - `updateElement` skipping `writeMainChain`: the property, mapper, and checkpoint cases timed out
 *   reading the file;
 * - `updateElement` dropping the description: the checkpoint case timed out;
 * - the script resource not written: the script case timed out reading the `.groovy` file;
 * - the JSON resource not written: the mapper form stayed open, and the guard caught the extension
 *   host's `EntryNotFound`;
 * - `getServices` answering `[]`: the service-call and HTTP trigger cases lost their service;
 * - `getService` answering `{}`: the HTTP trigger's base path lost the address;
 * - `getOperations` answering `[]`: the service call lost `createOrder`;
 * - `getEnvironments` answering `[]`: the additional parameters lost `soTimeout`;
 * - `getApiSpecifications` answering `[]`: the picked service brought no `widgets` specification;
 * - `getLatestApiSpecification` throwing: the specification stayed empty after the service changed;
 * - `getEnvironment` answering `{}`: the base path lost the address;
 * - `getContextServices` and `getMcpServices` each answering `[]`: the form showed no service name;
 * - a `getSpecificationModelSource` handler returning the schema, the fix: the pinned case met
 *   `notTheKnownDefect`.
 */
import { test, expect } from "../../support/vscode-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { pickOption } from "../../support/pick-option.js";
import { normalizeIds, readGolden } from "../../support/vscode-golden.js";
import { notTheKnownDefect, outsideTheDefect } from "../../support/known-defect.js";
import {
  chainFile,
  CHECKPOINT,
  CHECKPOINT_CHAIN_ID,
  CONTEXT_STORAGE,
  element,
  fixture,
  GRAPHQL_CHAIN_ID,
  HTTP_TRIGGER,
  MAPPER,
  MCP_TRIGGER,
  MOCHA_CHAIN_ID,
  parseChain,
  resourceFile,
  SCRIPT,
  SERVICE_CALL,
  SERVICES_CHAIN_ID,
} from "../../support/vscode-workspace.js";

/** The uncaught error Monaco's failed worker request raises in the webview, #1223. */
const MONACO_WORKER_ERROR = "Event";

const CHAIN = chainFile(MOCHA_CHAIN_ID);
const SCRIPT_RESOURCE = resourceFile(MOCHA_CHAIN_ID, SCRIPT, "script.cip.groovy");
const SERVICES_CHAIN = chainFile(SERVICES_CHAIN_ID);
const GRAPHQL_CHAIN = chainFile(GRAPHQL_CHAIN_ID);
const MAPPER_RESOURCE = resourceFile(GRAPHQL_CHAIN_ID, MAPPER, "mapper.cip.json");
const CHECKPOINT_CHAIN = chainFile(CHECKPOINT_CHAIN_ID);

/** The mapper's resource file, as far as the case reads it. */
interface MapperResource {
  mappingDescription: {
    source: { headers: { id: string; name: string }[] };
    target: { headers: { id: string; name: string }[] };
    actions: { sources: { path?: string[] }[]; target: { path?: string[] } }[];
  };
}

test.describe("element forms", () => {
  test("a property saved in the form is written to the chain file", async ({ vscode, messages }) => {
    const original = fixture(SERVICES_CHAIN);
    await vscode.open();
    await vscode.openFile(SERVICES_CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(CONTEXT_STORAGE);
    const keys = form.getByRole("textbox", { name: "Keys" });
    await expect(keys).toHaveValue("vscKey");
    await keys.fill("vscKey,e2eKey");
    await form.getByRole("button", { name: "Save" }).click();
    await expect(form).toBeHidden();

    const text = await vscode.readAsText((each) => element(parseChain(each), CONTEXT_STORAGE)?.properties?.keys === "vscKey,e2eKey");
    expect(normalizeIds(text, original)).toBe(readGolden("element-form-context-storage-keys.chain.cip.yaml"));
    expect(messages.answered()).toContain("updateElement");
  });

  test("a script edited in Monaco is written to its resource file", async ({ vscode, expectConsoleError }) => {
    expectConsoleError(MONACO_WORKER_ERROR);
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(SCRIPT);
    const editor = vscode.monaco(form, "script-editor");
    await expect(editor).toContainText('setBody("fixture")');
    await vscode.lines(editor).click();
    await vscode.page.keyboard.press("Control+End");
    await vscode.page.keyboard.type("\n// e2e-edited");
    await expect(editor).toContainText("// e2e-edited");
    await form.getByRole("button", { name: "Save" }).click();
    await expect(form).toBeHidden();

    await vscode.openFile(SCRIPT_RESOURCE);
    const script = await vscode.readText((each) => each.includes("// e2e-edited"));
    expect(script.split("\n").filter(Boolean)).toEqual(['exchange.getMessage().setBody("fixture")', "// e2e-edited"]);
  });

  test("a mapping typed in the mapper's text view is written", async ({ vscode, expectConsoleError }) => {
    expectConsoleError(MONACO_WORKER_ERROR);
    await vscode.open();
    await vscode.openFile(GRAPHQL_CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(MAPPER);
    await form.getByRole("tab", { name: "Text", exact: true }).click();
    const editor = vscode.monaco(form, "mapping-actions-editor");
    await expect(editor).toBeVisible();
    await editor.click();
    await vscode.page.keyboard.type("header.e2e-in -> header.e2e-out");
    await form.getByRole("button", { name: "Save" }).click();
    await expect(form).toBeHidden();

    // The extension moves the mapping out of the chain file into a resource of its own.
    const chain = parseChain(await vscode.readAsText((each) => element(parseChain(each), MAPPER)?.properties?.propertiesFilename !== undefined));
    expect(element(chain, MAPPER)?.properties).toMatchObject({ propertiesFilename: `${MAPPER}.element.mapper.cip.json`, propertiesToExportInSeparateFile: "mappingDescription" });
    await vscode.openFile(MAPPER_RESOURCE);
    const { mappingDescription: mapping } = JSON.parse(await vscode.readText((each) => each.includes("e2e-out"))) as MapperResource;
    const [source] = mapping.source.headers;
    const [target] = mapping.target.headers;
    expect([source?.name, target?.name]).toEqual(["e2e-in", "e2e-out"]);
    expect(mapping.actions).toEqual([
      expect.objectContaining({
        sources: [expect.objectContaining({ path: [source.id] })],
        target: expect.objectContaining({ path: [target.id] }),
      }),
    ]);
  });

  test("a saved checkpoint keeps its retry path", async ({ vscode }) => {
    const before = element(parseChain(fixture(CHECKPOINT_CHAIN)), CHECKPOINT);
    await vscode.open();
    await vscode.openFile(CHECKPOINT_CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(CHECKPOINT);
    await form.getByRole("textbox", { name: "Description" }).fill("e2e retry point");
    await form.getByRole("button", { name: "Save" }).click();
    await expect(form).toBeHidden();

    const chain = parseChain(await vscode.readAsText((each) => JSON.stringify(element(parseChain(each), CHECKPOINT)).includes("e2e retry point")));
    expect(element(chain, CHECKPOINT)).toMatchObject({ description: "e2e retry point", properties: before?.properties });
  });

  test("a service call's form lists the workspace's services", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(SERVICES_CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(SERVICE_CALL);
    await expect(form.getByText("e2e-service-roundtrip-internal", { exact: true })).toBeVisible();
    await expect(form.getByText("createOrder", { exact: true })).toBeVisible();
    // The active environment's properties fill the additional parameters.
    await expect(form.getByRole("row", { name: "soTimeout 120000" })).toBeVisible();

    // Another service brings its latest specification along.
    await form.getByRole("combobox", { name: "*Service" }).click();
    await vscode.webview.getByText("vsc-fixture-graphql", { exact: true }).click();
    await expect(form.getByText("widgets", { exact: true })).toBeVisible();
    await expect(form.getByText("1.0.0", { exact: true })).toBeVisible();
    expect(messages.answered()).toEqual(
      expect.arrayContaining(["getServices", "getService", "getEnvironments", "getApiSpecifications", "getOperations", "getLatestApiSpecification"]),
    );
  });

  test("an implemented service shows its address in an HTTP trigger", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(HTTP_TRIGGER);
    await form.getByRole("combobox").first().click();
    await pickOption(vscode.webview, "Implemented Service");
    const service = form.getByRole("combobox", { name: "*Service" });
    await service.fill("vsc-fixture-implemented");
    // The services arrive in an answer of their own, and Enter picks nothing while the list is empty.
    await expect(vscode.webview.getByRole("listbox").locator("..").getByText("vsc-fixture-implemented", { exact: true })).toBeVisible();
    await service.press("Enter");
    // The base path joins the route prefix and the address of the service's active environment.
    await expect(form.getByText("Base path", { exact: true })).toBeVisible();
    await expect(form.locator('input[value="/cip-routes/vsc-implemented"]')).toBeDisabled();
    expect(messages.answered()).toContain("getEnvironment");
  });

  test("the context-storage form names its context service", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(SERVICES_CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(CONTEXT_STORAGE);
    await expect(form.getByText("vsc-fixture-context", { exact: true })).toBeVisible();
    expect(messages.answered()).toContain("getContextServices");
  });

  test("the MCP trigger names its MCP service", async ({ vscode, messages }) => {
    await vscode.open();
    await vscode.openFile(SERVICES_CHAIN);
    const graph = new ChainGraphPage(vscode.webview);

    const form = await graph.openElement(MCP_TRIGGER);
    await expect(form.getByText("vsc-fixture-mcp", { exact: true })).toBeVisible();
    expect(messages.answered()).toContain("getMcpServices");
  });

  test("the mapper loads the GraphQL schema of the call before it", async ({ vscode, messages, allowConsoleError }) => {
    test.fail(true, "#1214: the extension has no handler for getSpecificationModelSource, so the schema arrives empty");
    allowConsoleError(MONACO_WORKER_ERROR);
    const dialog = await outsideTheDefect("opening the mapper's Load source dialog on the GraphQL call", async () => {
      await vscode.open();
      await vscode.openFile(GRAPHQL_CHAIN);
      const graph = new ChainGraphPage(vscode.webview);
      const form = await graph.openElement(MAPPER);
      await form.getByRole("tab", { name: "Table", exact: true }).click();
      // The radio input is hidden behind its label.
      await form.getByText("Source", { exact: true }).click();
      // The row's actions button sits in its last cell, hidden until the row is hovered.
      const body = form.getByRole("row", { name: "body" });
      await body.hover();
      await expect(body.getByRole("button")).toHaveCount(2);
      await body.getByRole("button").last().click();
      await vscode.webview.getByRole("menuitem", { name: "Load" }).click();
      const loaded = vscode.webview.getByRole("dialog", { name: "Load source" });
      // The dialog fills the query and the schema together, once the schema's answer is in.
      await expect(vscode.monaco(loaded, "load-schema-graphql-query-editor")).toContainText("query Widget");
      return loaded;
    });

    const schema = (await vscode.lines(vscode.monaco(dialog, "load-schema-graphql-schema-editor")).innerText()).trim();
    if (!messages.answeredWithoutPayload("getSpecificationModelSource") || schema !== "") {
      notTheKnownDefect(`#1214 answers getSpecificationModelSource with no payload, so the schema stays empty, but the schema read ${JSON.stringify(schema)}`);
    }
    expect(schema).toContain("type Widget");
  });
});
