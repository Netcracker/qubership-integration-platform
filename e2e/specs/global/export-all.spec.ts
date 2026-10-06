/**
 * The reads that address the whole platform rather than one worker's entities.
 *
 * `GET /v1/catalog/export` takes no ids: it exports every chain the catalog holds. That makes it
 * the one export a spec cannot scope, and `e2e/AGENTS.md` rule 2 is what sends it here — a spec
 * needing a global view gets a project running on its own rather than a place in `specs/api/`.
 *
 * The rule earned itself on this endpoint. The exporter lists every chain and then resolves each
 * one's folder, so a folder a parallel worker deletes in between makes the whole request answer
 * **500 `EntityNotFoundException: Unable to find … Folder with id …`** — measured under a full
 * `--project=api` run, 3 of 120 calls answered that way. A retry around the call waits on nothing
 * but the other workers, which is why the project's dependency on `env` is the fix and `workers: 1`
 * is not: it runs when the rest of the suite has finished.
 *
 * The assertion is still over this spec's own chain. Another run's residue, or a chain a person
 * left on the stack, is in the same archive, so an entry count would report the stack rather than
 * the export.
 */
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, waitForDeployed } from "../../support/corpus.js";
import { tokenizedChain } from "../../support/deployable.js";
import { notTheKnownDefect, outsideTheDefect } from "../../support/known-defect.js";
import { tokenized } from "../../support/run.js";
import { leftBehind } from "../../support/teardown.js";
import { optionalEntryText } from "../../support/zip.js";

/**
 * The catalog's `errorMessage`, or the raw body when the answer is not its error shape.
 *
 * The message arrives JSON-escaped inside `{serviceName, errorMessage, errorDate}`, so it is read
 * out of the parsed body to be matched with the quotes it contains.
 */
function errorMessageOf(body: string): string {
  try {
    return String((JSON.parse(body) as { errorMessage?: unknown }).errorMessage ?? body);
  } catch {
    return body;
  }
}

test("the export of everything carries a chain exactly as its own export does", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "export-all"), folder.id, "in both");
  const entry = `chains/${chain.id}/${chain.id}.chain.cip.yaml`;

  const everything = await catalog.exportAllChains();
  const mine = await optionalEntryText(everything, entry);

  // The entry has to be there, and its document has to be the same one the chain's own export
  // writes: an archive of every chain that abbreviates what it holds would pass an entry-name check
  // and lose the content.
  expect(mine, "this chain's entry is missing from the export of everything").toBeDefined();
  expect(mine).toBe(await optionalEntryText(await catalog.exportChain(chain.id), entry));
});

/**
 * `GET /v1/catalog/export/api-spec` with no chain, snapshot, or deployment named exports every
 * deployed HTTP trigger, and answers 500 on an NPE once one of them has an external route
 * (`docs/product-defects.md`, "`GET /v1/catalog/export/api-spec` reports nothing with `chainIds`,
 * and 500s without it"). Every corpus trigger is internal, and the default `externalRoutes=true`
 * filters those out before the NPE, so the case deploys an external trigger of its own. Without one
 * the export answers 200 with `paths: {}`, as it did on a cluster holding the corpus alone.
 *
 * It reads every deployed trigger, which is why it runs here, and it narrows the `test.fail()` to
 * the NPE's message.
 */
test.fail("the export answers over the whole catalog with no ids named", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = await outsideTheDefect("building the external chain", async () => {
    const built = await tokenizedChain(catalog, run, {
      prefix: "api-spec",
      what: "external",
      parentId: folder.id,
      externalRoute: true,
    });
    await catalog.deploy(built.id, (await catalog.createSnapshot(built.id)).id);
    await waitForDeployed(catalog, [built]);
    return built;
  });

  try {
    const response = await catalog.raw("get", "/v1/catalog/export/api-spec").catch(() => null);
    if (!response) {
      notTheKnownDefect("the catalog did not answer /v1/catalog/export/api-spec at all");
    }
    const status = response.status();
    const message = errorMessageOf(await response.text());
    const isTheNpe =
      status === 500 && message.includes("Chain.getName()") && message.includes('"this.chain" is null');
    if (!isTheNpe) {
      notTheKnownDefect(
        `the export answered ${status}: ${message}. If that is a 200 the defect is fixed and the ` +
          `test.fail() should be deleted. Anything else is a new failure this annotation does not carry`,
      );
    }
    expect(status, "the export answers: delete the test.fail() annotation").toBe(200);
  } finally {
    // On Kubernetes the external route holds one of the domain's 16 HTTPRoute rules until then.
    await catalog.undeployAll(chain.id).catch(leftBehind(chain.name, "undeployed"));
  }
});
