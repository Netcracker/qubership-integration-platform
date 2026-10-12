/**
 * `systemType` on `http-trigger`: the kind of service the trigger implements an operation of.
 *
 * Each case builds its own service, imports a one-operation OpenAPI document whose path carries the
 * run token, and points a trigger in the worker folder at that operation, writing the properties
 * `ImplementedServiceEndpoint` requires. The service gets the environment the UI creates with it:
 * `ServicesList.tsx` adds one at address `/` for an implemented or internal service, and an OpenAPI
 * import into a service with none fails (`EnvironmentBaseService.setSwaggerDefaultProperties`).
 *
 * The value decides the trigger's `from` URI (`EndpointHelperSource.integrationAddress`), so that is
 * what every case asserts. Only EXTERNAL declares its row: INTERNAL and IMPLEMENTED both take the
 * environment address, so neither case could tell its value from the other. Only IMPLEMENTED is also
 * deployed and called. The element form offers an `http-trigger` implemented services only
 * (`ServiceField.tsx`), and the other two compile a URI no request can reach. Measured: INTERNAL
 * listens on `servlet-custom:<environment address><path>`, EXTERNAL on the egress gateway's
 * `/system/…` address, both report DEPLOYED, and the engine answers 404 at the operation path and at
 * the chain's own.
 */
import crypto from "node:crypto";
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, SEED_LOGGING, waitForDeployed, waitForRoutes } from "../../support/corpus.js";
import { MARKER_HEADER, tokenizedChain, type DeployableChain } from "../../support/deployable.js";
import { noteChain } from "../../support/diagnostics.js";
import { tokenized } from "../../support/run.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { waitForRecording, withBuilt, type Built } from "../../support/cleanup.js";
import { openApi } from "./service-call.js";
import type { Catalog } from "../../support/catalog.js";

type SystemType = "IMPLEMENTED" | "INTERNAL" | "EXTERNAL";

const INTERNAL_ADDRESS = "http://e2e-internal.invalid:8080";
const EXTERNAL_ADDRESS = "http://e2e-external.invalid:8080";

interface ImplementingChain {
  chain: DeployableChain;
  /** The operation's path, which is the trigger's route. */
  path: string;
}

/** A chain whose trigger implements the one operation of a fresh service of `type`. */
async function implementingChain(
  catalog: Catalog,
  run: string,
  folderId: string,
  type: SystemType,
  built: Built,
): Promise<ImplementingChain> {
  const what = type.toLowerCase();
  const system = await catalog.createSystem(tokenized(run, `system-type-${what}`), type);
  built.services.push({ name: `the service ${system.name} (${system.id})`, remove: () => catalog.deleteSystem(system.id) });
  const address = { IMPLEMENTED: "/", INTERNAL: INTERNAL_ADDRESS, EXTERNAL: EXTERNAL_ADDRESS }[type];
  const environment = await catalog.createEnvironment(system.id, { name: what, address });
  await catalog.activateEnvironment(system.id, environment.id);

  const group = tokenized(run, `system-type-${what}`);
  const path = `/${tokenized(run, `system-type-${what}`)}/operation`;
  const imported = await catalog.awaitSpecificationImport(
    await catalog.importSpecificationGroup(system.id, group, openApi("post", path), "http"),
  );
  const [operation] = imported.operations;

  const chain = await tokenizedChain(catalog, run, { prefix: "system-type", what, parentId: folderId });
  built.chains.push(chain);
  noteChain(chain);
  await catalog.patchElementProperties(chain.id, chain.triggerId, {
    systemType: type,
    integrationSystemId: system.id,
    integrationSpecificationGroupId: `${system.id}-${group}`,
    integrationSpecificationId: imported.specifications[0].id,
    integrationOperationId: operation.id,
    integrationOperationPath: operation.path,
    httpMethodRestrict: operation.method,
    contextPath: null,
  });
  return { chain, path: operation.path };
}

async function fromUri(catalog: Catalog, chainId: string): Promise<{ snapshotId: string; uri: string }> {
  const snapshot = await catalog.createSnapshot(chainId);
  const xml = (await catalog.getSnapshot(chainId, snapshot.id)).xmlDefinition ?? "";
  const uri = xml.match(/<from uri="(servlet-custom:[^"?]*)/)?.[1];
  expect(uri, "the snapshot compiled no servlet trigger").toBeDefined();
  return { snapshotId: snapshot.id, uri: uri! };
}

test("systemType IMPLEMENTED serves the operation's path", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, catalog, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const { chain, path } = await implementingChain(catalog, run, folder.id, "IMPLEMENTED", built);
    // The environment's `/` and the operation path, joined as they are.
    const { snapshotId, uri } = await fromUri(catalog, chain.id);
    expect(uri).toBe(`servlet-custom:/${path}`);

    await catalog.saveLoggingProperties(chain.id, SEED_LOGGING);
    await catalog.deploy(chain.id, snapshotId);
    const route = { id: chain.id, name: chain.name, contextPath: path };
    await waitForDeployed(catalog, [route]);
    await waitForRoutes(env, [route]);
    await waitForRecording(env, sessions, route);

    const call = await callChain(request, env.chainUrl(path), { data: {} });
    expect(call.response.status()).toBe(200);
    expect(call.response.headers()[MARKER_HEADER]).toBe("implemented");
    const own = await fetch(env.chainUrl(chain.contextPath), { method: "POST" });
    expect(own.status, "the trigger also answers on the contextPath it was created with").toBe(404);

    const session = await sessions.byExternalId(call.token, { elements: 3 });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Header Modification"]);
  });
});

test("systemType INTERNAL compiles the service's environment address into the trigger's URI", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  await withBuilt(catalog, async (built) => {
    const { chain, path } = await implementingChain(catalog, run, folder.id, "INTERNAL", built);
    expect((await fromUri(catalog, chain.id)).uri).toBe(`servlet-custom:${INTERNAL_ADDRESS}${path}`);
  });
});

test("systemType EXTERNAL compiles the egress gateway's address for the service into the trigger's URI", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  covers("http-trigger", "systemType", "EXTERNAL");
  await withBuilt(catalog, async (built) => {
    const { chain, path } = await implementingChain(catalog, run, folder.id, "EXTERNAL", built);
    // `<gateway>/system/<design-time trigger id>/<sha1 of the environment address><path>`.
    const hash = crypto.createHash("sha1").update(EXTERNAL_ADDRESS).digest("hex");
    expect((await fromUri(catalog, chain.id)).uri).toMatch(
      new RegExp(`^servlet-custom:https?://[^/]+/system/${chain.triggerId}/${hash}${path}$`),
    );
  });
});
