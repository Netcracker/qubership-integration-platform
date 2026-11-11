/**
 * A service call to an `EXTERNAL` service goes out through the egress gateway, over GraphQL as over
 * HTTP (#1253).
 *
 * Each case builds two `EXTERNAL` services that point at the seeded `http-echo` chain on the classic
 * engine, and a caller chain for each. The GraphQL service has the echo's address. The HTTP service
 * has the engine's address and an operation whose path is the echo's route. The engine registers an
 * egress route for each call and replaces the route placeholder in the compiled call with the prefix
 * it registered, so a call reaches the echo only if its URI carries that placeholder. Before #1253
 * the GraphQL call was built from a hash of the raw address, a prefix that no route matches, and the
 * gateway responded `404 NR route_not_found`.
 *
 * The cases run on the classic engine and on a micro domain of the run. The echo stays on the
 * classic engine in both.
 *
 * Shown failing: with `service-call/template.hbs` from before #1253, both cases fail with the
 * `graphql` entry at 404 and an empty body, while the `http` entry is 200 with the echoed body.
 */
import type { APIRequestContext } from "@playwright/test";
import { test, expect } from "../../support/fixtures.js";
import { withBuilt, type Built } from "../../support/cleanup.js";
import {
  ENGINE_CASE_TIMEOUT,
  MICRO_READY_TIMEOUT,
  readCorpusState,
  seedChain,
  waitForDeployed,
  waitForRoutes,
  type SeedChain,
} from "../../support/corpus.js";
import { createStepsChain, deleteDomain, deployToDomain, tokenedDomain } from "../../support/deployable.js";
import { CLASSIC_ENGINE_SERVICE } from "../../support/kube.js";
import { tokenized } from "../../support/run.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";
import { GRAPHQL_QUERY, GRAPHQL_VARIABLES } from "../../fixtures/axis-generator.js";
import type { Catalog } from "../../support/catalog.js";

const ENGINE_ADDRESS = `http://${CLASSIC_ENGINE_SERVICE}:8080`;

/** How long Istio gets to program the gateway after the HTTPRoute changes. */
const GATEWAY_TIMEOUT = 60_000;

/** A one-operation OpenAPI document: `POST path`. */
function openApi(path: string): { name: string; mimeType: string; buffer: Buffer } {
  const buffer = Buffer.from(
    [
      "openapi: 3.0.3",
      "info:",
      "  title: E2E External Echo",
      "  version: 1.0.0",
      "paths:",
      `  ${path}:`,
      "    post:",
      "      operationId: echo",
      "      responses:",
      '        "200":',
      "          description: echoed",
    ].join("\n"),
  );
  return { name: "external-echo.openapi.yaml", mimeType: "application/yaml", buffer };
}

/** An `EXTERNAL` service at `address`, and the service-call properties for `operation` of its specification. */
async function externalService(
  catalog: Catalog,
  run: string,
  built: Built,
  what: string,
  address: string,
  protocol: "http" | "graphql",
  specification: { name: string; mimeType: string; buffer: Buffer },
  operation: string,
): Promise<Record<string, unknown>> {
  const system = await catalog.createSystem(tokenized(run, `external-${what}`), "EXTERNAL");
  built.services.push({ name: `the service ${system.name} (${system.id})`, remove: () => catalog.deleteSystem(system.id) });
  const environment = await catalog.createEnvironment(system.id, { name: what, address });
  await catalog.activateEnvironment(system.id, environment.id);

  const group = tokenized(run, `external-${what}`);
  const imported = await catalog.awaitSpecificationImport(
    await catalog.importSpecificationGroup(system.id, group, specification, protocol),
  );
  const found = imported.operations.find((each) => each.name === operation);
  expect(found, `the ${what} specification has no operation ${operation}`).toBeDefined();
  return {
    systemType: "EXTERNAL",
    integrationSystemId: system.id,
    integrationSpecificationGroupId: `${system.id}-${group}`,
    integrationSpecificationId: found!.modelId,
    integrationOperationId: found!.id,
    integrationOperationPath: found!.path,
    integrationOperationMethod: found!.method,
    integrationOperationProtocolType: protocol,
    retryCount: 0,
    retryDelay: 5000,
  };
}

/** A GraphQL and an HTTP caller chain to the seeded echo, neither deployed. */
async function callers(
  catalog: Catalog,
  run: string,
  folderId: string,
  built: Built,
  what: string,
): Promise<{ graphql: SeedChain; http: SeedChain }> {
  const echo = seedChain(readCorpusState(), "http-echo");
  const graphqlCall = await externalService(
    catalog, run, built, `${what}-graphql`, `${ENGINE_ADDRESS}/routes/${echo.contextPath}`,
    "graphql", readSpecificationFixture("widgets.graphql"), "widget",
  );
  const httpCall = await externalService(
    catalog, run, built, `${what}-http`, ENGINE_ADDRESS, "http", openApi(`/routes/${echo.contextPath}`), "echo",
  );
  const graphql = await createStepsChain(catalog, run, {
    what: `external-${what}-graphql-call`,
    parentId: folderId,
    steps: [{
      type: "service-call",
      name: "Service Call",
      properties: {
        ...graphqlCall,
        integrationGqlQuery: GRAPHQL_QUERY,
        integrationGqlVariablesJSON: JSON.stringify(GRAPHQL_VARIABLES),
      },
    }],
    logging: {},
  }, built.chains);
  const http = await createStepsChain(catalog, run, {
    what: `external-${what}-http-call`,
    parentId: folderId,
    steps: [{ type: "service-call", name: "Service Call", properties: httpCall }],
    logging: {},
  }, built.chains);
  return { graphql, http };
}

/** What each caller returned: the status, and the body parsed as JSON where it is JSON. */
async function answers(
  request: APIRequestContext,
  url: (contextPath: string) => string,
  chains: { graphql: SeedChain; http: SeedChain },
  run: string,
): Promise<Record<string, { status: number; body: unknown }>> {
  const result: Record<string, { status: number; body: unknown }> = {};
  for (const [protocol, chain] of Object.entries(chains)) {
    const response = await request.post(url(chain.contextPath), { data: { external: run }, failOnStatusCode: false });
    const text = await response.text();
    let body: unknown = text;
    try {
      body = JSON.parse(text);
    } catch {
      // An empty or non-JSON body is compared as text.
    }
    result[protocol] = { status: response.status(), body };
  }
  return result;
}

function expected(run: string): Record<string, { status: number; body: unknown }> {
  return {
    graphql: { status: 200, body: { query: GRAPHQL_QUERY, operationName: null, variables: GRAPHQL_VARIABLES } },
    http: { status: 200, body: { external: run } },
  };
}

test.describe("service call to an external service", { tag: ["@engine", "@catalog", "@infra", "@slow"] }, () => {
  test("reaches it over graphql and http from the classic engine", async ({ request, catalog, env, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    await withBuilt(catalog, async (built) => {
      const chains = await callers(catalog, run, folder.id, built, "classic");
      for (const chain of [chains.graphql, chains.http]) {
        await catalog.deploy(chain.id, (await catalog.createSnapshot(chain.id)).id);
      }
      await waitForDeployed(catalog, [chains.graphql, chains.http]);
      await waitForRoutes(env, [chains.graphql, chains.http]);

      await expect
        .poll(() => answers(request, (path) => env.chainUrl(path), chains, run), {
          timeout: GATEWAY_TIMEOUT,
          message: "the gateway never routed the external calls to the echo",
        })
        .toEqual(expected(run));
    });
  });

  test("reaches it over graphql and http from a micro domain", async ({ request, catalog, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT + MICRO_READY_TIMEOUT);
    await withBuilt(catalog, async (built) => {
      const chains = await callers(catalog, run, folder.id, built, "micro");
      const target = tokenedDomain(run, "external-call");
      try {
        await deployToDomain(catalog, target, [chains.graphql, chains.http]);
        await expect
          .poll(() => answers(request, (path) => target.env.chainUrl(path), chains, run), {
            timeout: GATEWAY_TIMEOUT,
            message: "the gateway never routed the external calls to the echo",
          })
          .toEqual(expected(run));
      } finally {
        await deleteDomain(catalog, target);
      }
    });
  });
});
