/**
 * `GET /v1/catalog/export/api-spec` reports over the routes a chain actually exposes.
 *
 * It is a runtime spec rather than an import and export one because it reads
 * **deployed** state: the seeded corpus is the only thing on this stack that guarantees a deployed
 * HTTP trigger with a known context path.
 *
 * **`chainIds` with `httpTriggerIds`** is the form the UI sends, and the one asserted here.
 * `externalRoutes` defaults to `true` and the OpenAPI predicate then keeps only external routes, so a
 * fixture declaring `externalRoute: false` needs `externalRoutes=false` to appear at all. The other
 * shapes fail, and that is decided as won't fix.
 *
 * The whole-catalog export, with neither parameter, is in `specs/global/export-all.spec.ts`.
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
