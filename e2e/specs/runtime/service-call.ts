/**
 * A deployed trigger into a `service-call` on one operation of a fresh service, for the specs that
 * sweep the element's axes. A seed chain cannot carry the service, specification and operation ids
 * the element needs, so each case builds its own in the worker folder.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import { SEED_LOGGING, waitForDeployed, waitForRoutes, type SeedChain } from "../../support/corpus.js";
import { createStepsChain, type ChainStep } from "../../support/deployable.js";
import { tokenized } from "../../support/run.js";
import type { Sessions } from "../../support/sessions.js";
import { waitForRecording, type Built } from "../../support/cleanup.js";
import type { Catalog } from "../../support/catalog.js";
import type { Env } from "../../env/index.js";

/** Never resolved: a call the testing service does not intercept fails rather than reaching something. */
export const HTTP_ADDRESS = "http://e2e-service-call.invalid:8080";
export const HTTP_OPERATION = "e2eServiceCall";
export const ORDER_PATH = "/orders/{orderId}";
/** The `orderId` a case fills `ORDER_PATH` with. */
export const ORDER_ID = "e2e-order";
/** What an http mock answers. */
export const MOCKED_BODY = '{"mocked":"http"}';

/** A one-operation OpenAPI document: `path` under `method`, with `orderId` declared when the path has it. */
export function openApi(method = "post", path = ORDER_PATH): { name: string; mimeType: string; buffer: Buffer } {
  const parameters = path.includes("{orderId}")
    ? ["      parameters:", "        - name: orderId", "          in: path", "          required: true", "          schema:", "            type: string"]
    : [];
  const buffer = Buffer.from(
    [
      "openapi: 3.0.3",
      "info:",
      "  title: E2E Service Call",
      "  version: 1.0.0",
      "paths:",
      `  ${path}:`,
      `    ${method}:`,
      `      operationId: ${HTTP_OPERATION}`,
      ...parameters,
      "      responses:",
      '        "201":',
      "          description: created",
    ].join("\n"),
  );
  return { name: "service-call.openapi.yaml", mimeType: "application/yaml", buffer };
}

export interface ServiceCallOptions {
  what: string;
  protocol: "http" | "graphql";
  file: { name: string; mimeType: string; buffer: Buffer };
  address: string;
  /** The operation's name, which for GraphQL is the field and not an `operationId`. */
  operation: string;
  properties: Record<string, unknown>;
  /** INTERNAL unless given. */
  systemType?: "INTERNAL" | "EXTERNAL" | "IMPLEMENTED";
  /** A step after the service call, for what only a later step can read. */
  downstream?: ChainStep;
}

export interface ServiceCallChain {
  chain: SeedChain;
  systemId: string;
  callerId: string;
  callerName: string;
}

/** A deployed trigger into a service call on one operation of a fresh service. */
export async function serviceCallChain(
  catalog: Catalog,
  env: Env,
  run: string,
  folderId: string,
  sessions: Sessions,
  options: ServiceCallOptions,
  built: Built,
): Promise<ServiceCallChain> {
  const what = `service-call-${options.what}`;
  const name = tokenized(run, what);
  const systemType = options.systemType ?? "INTERNAL";
  const system = await catalog.createSystem(name, systemType);
  built.services.push({ name: `the service ${name} (${system.id})`, remove: () => catalog.deleteSystem(system.id) });
  const environment = await catalog.createEnvironment(system.id, { name: options.what, address: options.address });
  await catalog.activateEnvironment(system.id, environment.id);
  const imported = await catalog.awaitSpecificationImport(
    await catalog.importSpecificationGroup(system.id, name, options.file, options.protocol),
  );
  const operation = imported.operations.find((each) => each.name === options.operation);
  if (!operation) throw new Error(`the ${options.protocol} import produced no operation ${options.operation}`);

  const callerName = `Service Call ${options.what}`;
  // What `SystemOperationField.tsx` writes on choosing the operation.
  const caller: ChainStep = {
    name: callerName,
    type: "service-call",
    properties: {
      systemType,
      integrationSystemId: system.id,
      integrationSpecificationGroupId: `${system.id}-${name}`,
      integrationSpecificationId: imported.specifications[0].id,
      integrationOperationId: operation.id,
      integrationOperationPath: operation.path,
      integrationOperationMethod: operation.method,
      integrationOperationProtocolType: options.protocol,
      ...options.properties,
    },
  };
  const chain = await createStepsChain(catalog, run, {
    what,
    parentId: folderId,
    steps: [caller, ...(options.downstream ? [options.downstream] : [])],
    logging: SEED_LOGGING,
  }, built.chains);

  const snapshot = await catalog.createSnapshot(chain.id);
  await catalog.deploy(chain.id, snapshot.id);
  await waitForDeployed(catalog, [chain]);
  await waitForRoutes(env, [chain]);
  await waitForRecording(env, sessions, chain);
  return { chain, systemId: system.id, callerId: chain.elements[callerName], callerName };
}
