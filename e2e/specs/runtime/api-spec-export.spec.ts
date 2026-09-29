/**
 * `GET /v1/catalog/export/api-spec` reports over the routes a chain actually exposes.
 *
 * It is a runtime spec rather than an import and export one because it reads
 * **deployed** state: the seeded corpus is the only thing on this stack that guarantees a deployed
 * HTTP trigger with a known context path.
 *
 * Two shapes of the endpoint are pinned here, and only the first of them works. Both defects are
 * filed in `docs/product-defects.md` under "`GET /v1/catalog/export/api-spec` reports nothing with
 * `chainIds`, and 500s without it".
 *
 *   - **`chainIds` with `httpTriggerIds`** is the working form, and the only one the UI ever sends.
 *     `externalRoutes` defaults to `true` and the OpenAPI predicate then keeps only external routes,
 *     so a fixture declaring `externalRoute: false` needs `externalRoutes=false` to appear at all.
 *   - **`chainIds` alone** answers 200 with `paths: {}`. That is not a report over the chain's
 *     deployed routes: `ApiSpecificationExportService.getTriggerElements` narrows the filter with
 *     `httpTriggerIds.contains(element.getId())` whenever `chainIds` is non-empty, and
 *     `httpTriggerIds` defaults to `""`, so the predicate is empty-set membership and discards
 *     every element the chain query returned.
 *
 * The third shape, with neither parameter, exports the whole catalog, so its case is in
 * `specs/global/export-all.spec.ts`.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import yaml from "js-yaml";

interface ExportedSpecification {
  openapi?: string;
  paths?: Record<string, unknown>;
  servers?: Array<{ variables?: { basePath?: { default?: string } } }>;
}

test("the exported specification carries the context path of a deployed chain", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const chain = seedChain(readCorpusState(), "script");
  const trigger = chain.elements["HTTP Trigger"];
  expect(trigger, "the fixture carries no element named \"HTTP Trigger\"").toBeTruthy();

  const response = await catalog.raw(
    "get",
    `/v1/catalog/export/api-spec?externalRoutes=false` +
      `&chainIds=${chain.id}&httpTriggerIds=${trigger}`,
  );
  expect(response.status()).toBe(200);

  const specification = yaml.load(await response.text()) as ExportedSpecification;
  expect(specification.openapi).toBe("3.0.1");
  expect(Object.keys(specification.paths ?? {})).toContain(`/${chain.contextPath}`);
  // The base path is the engine's compiled-in route prefix, which is what a reader of the exported
  // document needs in order to call the chain.
  expect(specification.servers?.[0]?.variables?.basePath?.default).toBe("/routes");
});

test("chainIds without httpTriggerIds answers 200 over a document that reports nothing", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const chain = seedChain(readCorpusState(), "script");

  const response = await catalog.raw(
    "get",
    `/v1/catalog/export/api-spec?externalRoutes=false&chainIds=${chain.id}`,
  );
  // Pinned deliberately: this is a green answer that says nothing, and a caller who passes only the
  // chain reads the empty document as "the chain exposes no route".
  expect(response.status()).toBe(200);
  const specification = yaml.load(await response.text()) as ExportedSpecification;
  // The version first. `yaml.load` answers a scalar for a body that is not a document, and
  // `specification.paths ?? {}` reads such a body as an empty map — so an endpoint that stopped
  // answering YAML at all would satisfy the emptiness assertion below.
  expect(specification.openapi).toBe("3.0.1");
  expect(specification.paths).toEqual({});
});
