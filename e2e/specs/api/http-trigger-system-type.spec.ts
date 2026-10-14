/**
 * `systemType` INTERNAL and EXTERNAL on `http-trigger`, asserted on the compile.
 *
 * Each case builds its own service, imports a one-operation OpenAPI document whose path carries the
 * run token, and points a trigger in the worker folder at that operation, writing the properties
 * `ImplementedServiceEndpoint` requires. The service gets an environment and has it activated, since
 * an OpenAPI import into a service with none fails (`EnvironmentBaseService.setSwaggerDefaultProperties`).
 *
 * The value decides the trigger's `from` URI (`EndpointHelperSource.integrationAddress`), so that is
 * what each case asserts. Only EXTERNAL declares its row: INTERNAL takes the environment address, as
 * IMPLEMENTED does, so its case could not tell the two values apart. Neither URI is one a request can
 * reach. The element form offers an `http-trigger` implemented services only (`ServiceField.tsx`).
 * Measured: INTERNAL listens on `servlet-custom:<environment address><path>`, EXTERNAL on the egress
 * gateway's `/system/…` address, both report DEPLOYED, and the engine answers 404 at the operation
 * path and at the chain's own. IMPLEMENTED runs in `specs/runtime/http-trigger-system-type.spec.ts`.
 */
import crypto from "node:crypto";
import { test, expect } from "../../support/fixtures.js";
import { tokenizedChain, type DeployableChain } from "../../support/deployable.js";
import { noteChain } from "../../support/diagnostics.js";
import { tokenized } from "../../support/run.js";
import { covers } from "../../registry/covers.js";
import { withBuilt, type Built } from "../../support/cleanup.js";
import type { Catalog } from "../../support/catalog.js";

type SystemType = "INTERNAL" | "EXTERNAL";

const ADDRESSES: Record<SystemType, string> = {
  INTERNAL: "http://e2e-internal.invalid:8080",
  EXTERNAL: "http://e2e-external.invalid:8080",
};

/** A one-operation OpenAPI document: `path` under `post`. */
function openApi(path: string): { name: string; mimeType: string; buffer: Buffer } {
  const buffer = Buffer.from(
    [
      "openapi: 3.0.3",
      "info:",
      "  title: E2E System Type",
      "  version: 1.0.0",
      "paths:",
      `  ${path}:`,
      "    post:",
      "      operationId: e2eSystemType",
      "      responses:",
      '        "201":',
      "          description: created",
    ].join("\n"),
  );
  return { name: "system-type.openapi.yaml", mimeType: "application/yaml", buffer };
}

/** A chain whose trigger implements the one operation of a fresh service of `type`, and that operation's path. */
async function implementingChain(
  catalog: Catalog,
  run: string,
  folderId: string,
  type: SystemType,
  built: Built,
): Promise<{ chain: DeployableChain; path: string }> {
  const what = type.toLowerCase();
  const system = await catalog.createSystem(tokenized(run, `system-type-${what}`), type);
  built.services.push({ name: `the service ${system.name} (${system.id})`, remove: () => catalog.deleteSystem(system.id) });
  const environment = await catalog.createEnvironment(system.id, { name: what, address: ADDRESSES[type] });
  await catalog.activateEnvironment(system.id, environment.id);

  const group = tokenized(run, `system-type-${what}`);
  const imported = await catalog.awaitSpecificationImport(
    await catalog.importSpecificationGroup(system.id, group, openApi(`/${group}/operation`), "http"),
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

async function fromUri(catalog: Catalog, chainId: string): Promise<string> {
  const snapshot = await catalog.createSnapshot(chainId);
  const xml = (await catalog.getSnapshot(chainId, snapshot.id)).xmlDefinition ?? "";
  const uri = xml.match(/<from uri="(servlet-custom:[^"?]*)/)?.[1];
  expect(uri, "the snapshot compiled no servlet trigger").toBeDefined();
  return uri!;
}

test("systemType INTERNAL compiles the service's environment address into the trigger's URI", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  await withBuilt(catalog, async (built) => {
    const { chain, path } = await implementingChain(catalog, run, folder.id, "INTERNAL", built);
    expect(await fromUri(catalog, chain.id)).toBe(`servlet-custom:${ADDRESSES.INTERNAL}${path}`);
  });
});

test("systemType EXTERNAL compiles the egress gateway's address for the service into the trigger's URI", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  covers("http-trigger", "systemType", "EXTERNAL");
  await withBuilt(catalog, async (built) => {
    const { chain, path } = await implementingChain(catalog, run, folder.id, "EXTERNAL", built);
    // `<gateway>/system/<design-time trigger id>/<sha1 of the environment address><path>`.
    const hash = crypto.createHash("sha1").update(ADDRESSES.EXTERNAL).digest("hex");
    expect(await fromUri(catalog, chain.id)).toMatch(
      new RegExp(`^servlet-custom:https?://[^/]+/system/${chain.triggerId}/${hash}${path}$`),
    );
  });
});
