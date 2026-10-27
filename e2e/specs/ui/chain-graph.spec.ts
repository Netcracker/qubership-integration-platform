/**
 * The chain editor: the canvas, the element library, and the element form.
 *
 * What the API cannot see: which nodes and edges the canvas draws, which element the form loads
 * when its node is opened, that a library element dropped on the canvas shows there and raises the
 * unsaved-changes tag with no reload, and what the form shows for a value the catalog stored, a
 * script in its Monaco editor included, and the protocol the form writes when a service is picked.
 *
 * The canvas is virtualized (`onlyRenderVisibleElements`), so every graph assertion runs over a
 * chain whose elements all fit the viewport the `ui` project pins. Dragging nodes and the automatic
 * layout are left alone until a defect asks for them: the catalog stores no node position, so a
 * drag has no saved outcome to assert, and pixel coordinates move with every layout change.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { ChainTabsPage } from "../../pages/ChainTabsPage.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callToken, tokenized } from "../../support/run.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";
import { leftBehind } from "../../support/teardown.js";

/** Two elements and one dependency, pinned by `fixtures/chains/http-echo`. Read, never edited. */
const SMALL_SEED = "http-echo";

test.describe("chain editor", () => {
  test("a seed chain's graph draws every element and the dependency", async ({ page }) => {
    const chain = seedChain(readCorpusState(), SMALL_SEED);
    const trigger = chain.elements["HTTP Trigger"];
    const header = chain.elements["Header Modification"];
    const graph = new ChainGraphPage(page);

    await graph.goto(chain.id);

    await expect(graph.node(trigger)).toContainText("HTTP Trigger");
    await expect(graph.node(header)).toContainText("Header Modification");
    await expect(graph.nodes).toHaveCount(Object.keys(chain.elements).length);
    await expect(graph.edge(trigger, header)).toBeAttached();
  });

  test("a node opened on the canvas loads its element into the form", async ({ page }) => {
    const corpus = readCorpusState();
    const chain = seedChain(corpus, SMALL_SEED);
    const header = chain.elements["Header Modification"];
    const graph = new ChainGraphPage(page);

    await graph.goto(chain.id);
    const form = await graph.openElement(header);

    await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/graph/${header}$`));
    await expect(form.getByRole("button", { name: "Edit name" })).toContainText("Header Modification");
    // The header the fixture adds, which no other element of the chain carries, valued with the token
    // the corpus was rendered with.
    await expect(form.getByRole("textbox", { name: "Name", exact: true })).toHaveValue("e2e-fixture");
    await expect(form.getByRole("textbox", { name: "Value", exact: true })).toHaveValue(corpus.run);

    await form.getByRole("button", { name: "Cancel" }).click();
    await expect(form).toBeHidden();
    await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}/graph$`));
  });

  test("a dropped library element lands on the canvas and is saved", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, "ui-graph-drop"), folder.id);
    const graph = new ChainGraphPage(page);
    const { unsavedChanges: unsaved } = new ChainTabsPage(page);

    await graph.goto(chain.id);
    // A chain with no elements carries no unsaved changes, so the tag starts hidden. The title comes
    // from the same chain answer as the tag, so the tag is read only once that answer is drawn.
    await expect(graph.canvas).toBeVisible();
    await expect(page).toHaveTitle(chain.name);
    await expect(unsaved).toBeHidden();
    await graph.dropFromLibrary("Log Record");

    await expect.poll(async () => (await catalog.listChainElements(chain.id)).map((each) => each.type)).toEqual(["log-record"]);
    const [created] = await catalog.listChainElements(chain.id);
    await expect(graph.node(created.id)).toContainText("Log Record");
    await expect(graph.nodes).toHaveCount(1);
    // The page refetches the chain after the drop, so the tag shows with no reload.
    await expect(unsaved).toBeVisible();
  });

  test("a property saved in the element form reads back after reload", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, "ui-graph-edit"), folder.id);
    const element = await catalog.createElement(chain.id, "log-record");
    const sender = tokenized(run, "ui-graph-sender");
    const graph = new ChainGraphPage(page);

    await graph.goto(chain.id);
    let form = await graph.openElement(element.id);
    await form.getByRole("textbox", { name: "Sender" }).fill(sender);
    await form.getByRole("button", { name: "Save" }).click();
    await expect(form).toBeHidden();

    await expect.poll(async () => (await catalog.getElement(chain.id, element.id)).properties.sender).toBe(sender);
    // A reload drops the page's own copy, so the form renders what the catalog returned.
    await page.reload();
    form = await graph.openElement(element.id);
    await expect(form.getByRole("textbox", { name: "Sender" })).toHaveValue(sender);
  });

  test("a script element's form loads the stored script", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, "ui-graph-script"), folder.id);
    const element = await catalog.createElement(chain.id, "script");
    const marker = tokenized(run, "ui-graph-script");
    await catalog.patchElementProperties(chain.id, element.id, { script: `// ${marker}` });
    const graph = new ChainGraphPage(page);

    await graph.goto(chain.id);
    const form = await graph.openElement(element.id);
    // The id is attached at mount, and the model once `data-uri` shows on the editor inside it.
    const editor = form.getByTestId("script-editor").locator(".monaco-editor[data-uri]");
    await expect(editor).toBeVisible();
    await expect(editor).toContainText(marker);
  });

  // The form copies the protocol `GET /systems` reports for the picked service, and the catalog reports
  // a WSDL service as `http`, so no click writes the `soap` that service-call/template.hbs has no branch for.
  test("a WSDL service-call saves http and compiles an HTTP call", async ({ page, catalog, folder, run }) => {
    // Typing the name into the field picks the first service whose name contains it, so the name is unique per call.
    const service = await catalog.createSystem(tokenized(run, callToken("ui-graph-soap")), "EXTERNAL");
    let chainId: string | undefined;
    try {
      const wsdl = readSpecificationFixture("hello.wsdl");
      await catalog.awaitSpecificationImport(await catalog.importSpecificationGroup(service.id, tokenized(run, "ui-graph-wsdl"), wsdl));
      const chain = await catalog.createChain(tokenized(run, "ui-graph-soap"), folder.id);
      chainId = chain.id;
      const trigger = await catalog.createElement(chain.id, "http-trigger");
      await catalog.patchElementProperties(chain.id, trigger.id, { contextPath: tokenized(run, "ui-graph-soap"), httpMethodRestrict: "POST" });
      const call = await catalog.createElement(chain.id, "service-call");
      await catalog.connectElements(chain.id, trigger.id, call.id);
      const graph = new ChainGraphPage(page);

      await graph.goto(chain.id);
      const form = await graph.openElement(call.id);
      const serviceField = form.getByRole("combobox", { name: "Service" });
      await serviceField.fill(service.name);
      await serviceField.press("Enter");
      const specificationField = form.getByRole("combobox", { name: "API Specification" });
      await specificationField.click();
      await expect(page.getByRole("option", { name: "1.0.0" })).toBeAttached();
      await specificationField.press("Enter");
      const operationField = form.getByRole("combobox", { name: "Operation" });
      await operationField.click();
      await expect(page.getByRole("option").filter({ hasText: "sayHello" })).toBeAttached();
      await operationField.press("Enter");
      await form.getByRole("button", { name: "Save" }).click();
      await expect(form).toBeHidden();

      const saved = (await catalog.getElement(chain.id, call.id)).properties;
      expect(saved).toMatchObject({ integrationSystemId: service.id, integrationOperationMethod: "POST", integrationOperationProtocolType: "http" });
      const built = await catalog.createSnapshot(chain.id);
      const xml = (await catalog.getSnapshot(chain.id, built.id)).xmlDefinition ?? "";
      expect(xml).toContain('<process ref="httpSenderProcessor"/>');
      expect(xml).toMatch(/<toD uri="http:/);
    } finally {
      // The catalog refuses to delete a service a chain still uses.
      if (chainId) await catalog.deleteChain(chainId).catch(leftBehind(`chain ${chainId}`));
      await catalog.deleteSystem(service.id).catch(leftBehind(`service ${service.name}`));
    }
  });
});
