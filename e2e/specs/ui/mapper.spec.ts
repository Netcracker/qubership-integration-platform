/**
 * The mapper element's form: a mapping written in the browser is saved and runs once deployed.
 *
 * The mapping is typed into the Text view, whose parser creates a header the line names on either
 * side. The Graph view connects fields by HTML5 drag and drop between anchors with no accessible
 * name, so it stays uncovered here. What the API cannot see is that the form turns the line into a
 * `mappingDescription` the engine runs: the case saves it, deploys the chain through the API, and
 * calls it.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { ENGINE_CASE_TIMEOUT, SEED_LOGGING } from "../../support/corpus.js";
import { createStepsChain } from "../../support/deployable.js";
import { deployAndRecord, withBuilt } from "../../support/cleanup.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { callToken } from "../../support/run.js";

const SOURCE_HEADER = "e2e-ui-in";
const TARGET_HEADER = "e2e-ui-out";

interface NamedAttribute {
  id: string;
  name: string;
}

interface SavedMapping {
  source: { headers: NamedAttribute[] };
  target: { headers: NamedAttribute[] };
  actions: Array<{ sources: Array<{ type: string; kind?: string; path?: string[] }>; target: { type: string; kind?: string; path?: string[] } }>;
}

test("a header mapped in the mapper's text view is saved and copied by the deployed chain", { tag: ["@ui", "@tier1"] }, async ({ page, request, catalog, env, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const chain = await createStepsChain(
      catalog,
      run,
      // A call token in the route keeps repeats of the case apart.
      { what: callToken("ui-mapper"), parentId: folder.id, steps: [{ name: "Mapper", type: "mapper-2", properties: {} }], logging: SEED_LOGGING },
      built.chains,
    );
    const mapperId = chain.elements["Mapper"];
    const graph = new ChainGraphPage(page);

    await graph.goto(chain.id);
    const form = await graph.openElement(mapperId);
    await form.getByRole("tab", { name: "Mapping", exact: true }).click();
    await form.getByRole("tab", { name: "Text", exact: true }).click();
    // The model is attached once `data-uri` shows on the editor inside the id'd wrapper.
    const editor = form.getByTestId("mapping-actions-editor").locator(".monaco-editor[data-uri]");
    await expect(editor).toBeVisible();
    await editor.click();
    await page.keyboard.type(`header.${SOURCE_HEADER} -> header.${TARGET_HEADER}`);
    await form.getByRole("button", { name: "Save", exact: true }).click();
    await expect(form).toBeHidden();

    const mapping = (await catalog.getElement(chain.id, mapperId)).properties.mappingDescription as SavedMapping;
    const [source] = mapping.source.headers;
    const [target] = mapping.target.headers;
    expect([source?.name, target?.name]).toEqual([SOURCE_HEADER, TARGET_HEADER]);
    // A path names the attribute by id, which is what the compiled mapper resolves.
    expect(mapping.actions).toEqual([
      expect.objectContaining({
        sources: [expect.objectContaining({ type: "attribute", kind: "header", path: [source.id] })],
        target: expect.objectContaining({ type: "attribute", kind: "header", path: [target.id] }),
      }),
    ]);

    await deployAndRecord(catalog, env, sessions, chain);

    const value = callToken("ui-mapper");
    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "mapper" }, headers: { [SOURCE_HEADER]: value } });
    // The mapping writes no body, so the chain answers 204.
    expect(call.response.status()).toBe(204);
    expect(call.response.headers()[TARGET_HEADER]).toBe(value);
    const session = await sessions.byExternalId(call.token, { elements: HTTP_TRIGGER_STEPS.length + 1 });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Mapper"]);
  });
});
