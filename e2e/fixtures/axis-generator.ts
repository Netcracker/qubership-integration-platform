/**
 * One fixture chain per axis value, generated from a declaration naming an element, an axis, and a
 * value.
 *
 * A generated chain is an HTTP trigger and the element under test: the declaration's `properties`
 * template with the one axis substituted, and a `downstream` element after it where the value needs
 * one. For `http-trigger` the trigger is the element under test. The output lands in
 * `fixtures/axes/`, which the corpus assembler reads beside `fixtures/chains/`, so the seed deploys
 * it in the same batch.
 *
 * An axis that changes the chain's shape (a second chain to call, a parent container) is written
 * by hand under `fixtures/chains/` and declared with `handWritten`. The generator emits nothing for
 * it and checks the hand-written document sets the axis, so neither path can yield a chain that
 * tests nothing.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import { expect, type APIRequestContext } from "@playwright/test";
import {
  extractDiscriminators,
  loadElementSchemas,
  type SchemaValue,
} from "../registry/discriminators.js";
import {
  AXIS_FIXTURE_DIR,
  CHAIN_FIXTURE_DIR,
  RUN_PLACEHOLDER,
  readFixtureDocument,
  renderFixtureTree,
  substitute,
  type RenderedTree,
} from "./templating.js";
import { schemaValidator, validateDocument } from "./validate.js";
import type { ChainStep } from "../support/deployable.js";

const ELEMENT_SCHEMA_ID_BASE = "http://qubership.org/schemas/product/qip/element/";

export interface AxisFixture {
  family: string;
  /** The normalized axis path, as the registry row spells it: `idempotency/actionOnDuplicate`. */
  axisPath: string;
  value: SchemaValue;
  /**
   * What the element needs beside the axis, and nothing more. The import fills in library defaults,
   * but not a property the schema requires with no default: `http-sender` needs a `uri`, and RBAC
   * needs `roles`. Generation validates the result, so an omission fails here rather than at deploy.
   */
  properties?: Record<string, unknown>;
  /** A fixture directory under `fixtures/chains/` that carries this value instead of a generated chain. */
  handWritten?: string;
  /** An element after the element under test, for a branch only a later step reaches: a failure handler needs a throw. */
  downstream?: ChainStep;
}

/** The body a handler writes when it runs: `{"handler":"validation-script"}`. */
export function handlerReply(handler: string): string {
  return JSON.stringify({ handler });
}

export function scriptHandler(handler: string): Record<string, unknown> {
  return { script: `exchange.getMessage().setBody('${handlerReply(handler)}')` };
}

/** A mapping that writes one constant into the body's `handler` field. */
export function mapperHandler(handler: string): Record<string, unknown> {
  return {
    throwException: true,
    mappingDescription: {
      source: { headers: [], properties: [] },
      target: {
        headers: [],
        properties: [],
        body: {
          name: "object",
          schema: { id: "e2e-handler-body", attributes: [{ id: "e2e-handler-attr", name: "handler", type: { name: "string" } }] },
        },
      },
      constants: [
        { id: "e2e-handler-const", name: "handler", type: { name: "string" }, valueSupplier: { kind: "given", value: handler } },
      ],
      actions: [
        {
          id: "e2e-handler-action",
          sources: [{ type: "constant", constantId: "e2e-handler-const" }],
          target: { type: "attribute", kind: "body", path: ["e2e-handler-attr"] },
        },
      ],
    },
  };
}

/** The chain trigger of `fixtures/chains/chain-callee/`, which a sub-chain failure handler calls. */
const CHAIN_CALLEE_TRIGGER_ID = "e2e00011-0000-4000-8000-000000000004";

/** A trigger that accepts only XML, so a JSON call fails validation. */
const REJECTS_JSON = { allowedContentTypes: ["application/xml"] };

/** The step a chain failure handler needs: nothing after the trigger fails otherwise. */
export const THROWING_STEP: ChainStep = {
  name: "Throwing Script",
  type: "script",
  properties: { script: "throw new IllegalStateException('e2e chain failure')" },
};

export const CORRELATION_ID_HEADER = "e2e-correlation-id";
export const CORRELATION_ID_FIELD = "e2eCorrelationId";

/** Writes the `correlationId` exchange property the trigger received, `null` when it received none. */
export const CORRELATION_ECHO: ChainStep = {
  name: "Echo Correlation Id",
  type: "script",
  properties: {
    script: "exchange.getMessage().setBody('{\"correlationId\":' + groovy.json.JsonOutput.toJson(exchange.getProperty('correlationId')) + '}')",
  },
};

/** The idempotency header the trigger reads its key from. */
export const IDEMPOTENCY_KEY_HEADER = "e2e-idempotency-key";

const IDEMPOTENCY = {
  enabled: true,
  contextExpression: "e2e",
  keyExpiry: 600,
  keyExpression: `\${header.${IDEMPOTENCY_KEY_HEADER}}`,
};

/** The step a call reaches only when the idempotency check lets it through. */
export const PASSED_STEP: ChainStep = {
  name: "Downstream Reply",
  type: "script",
  properties: { script: "exchange.getMessage().setBody('{\"called\":\"downstream\"}')" },
};

/** The engine's own port as the engine sees it, not the one `Env.chainUrl` publishes. */
export const ENGINE_LOOPBACK = "http://localhost:8080";
/** `fixtures/chains/method-echo/` on the engine's own port. */
export const METHOD_ECHO_URI = `${ENGINE_LOOPBACK}/routes/e2e-${RUN_PLACEHOLDER}-method-echo`;
/** The response header the method echo answers the received method in. */
export const METHOD_HEADER = "e2e-method";

/**
 * Calls a chain whose sender reaches the method echo, and asserts the echo received `method`.
 *
 * The call carries no correlation header: with one, the testing service mocks the sender and the
 * echo never runs.
 */
export async function expectEchoedMethod(request: APIRequestContext, url: string, method: string): Promise<void> {
  const response = await request.fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, data: {} });
  expect(response.status()).toBe(200);
  const headers = response.headers();
  if (method === "OPTIONS") {
    // The servlet answers OPTIONS itself, without running the echo.
    expect(headers.allow, "only an OPTIONS request is answered with Allow").toContain("OPTIONS");
    expect(headers[METHOD_HEADER], "the echo ran: the servlet answers OPTIONS without it").toBeUndefined();
  } else {
    expect(headers[METHOD_HEADER]).toBe(method);
  }
}
/** Never resolved: only a mocked call gets an answer. */
export const MOCKED_SENDER_URI = "http://e2e-http-sender.invalid:8080/orders";

const INTERNAL_CALL = { isExternalCall: false };

/** The header a log record reads its message token from. */
export const LOG_TOKEN_HEADER = "e2e-log-token";
export const LOG_MESSAGE_PREFIX = "e2e-log";

export const HTTP_METHODS = ["POST", "GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"] as const;
export const LOG_LEVELS = ["Error", "Warning", "Info"] as const;

/** The axis values the seed deploys a chain for. A spec that covers a value adds its entry. */
export const axisFixtures: readonly AxisFixture[] = [
  // The http-trigger handler axes.
  { family: "http-trigger", axisPath: "handleValidationAction", value: "default", properties: REJECTS_JSON },
  {
    family: "http-trigger",
    axisPath: "handleValidationAction",
    value: "script",
    properties: { ...REJECTS_JSON, handlerContainer: scriptHandler("validation-script") },
  },
  {
    family: "http-trigger",
    axisPath: "handleValidationAction",
    value: "mapper-2",
    properties: { ...REJECTS_JSON, handlerContainer: mapperHandler("validation-mapper") },
  },
  { family: "http-trigger", axisPath: "handleChainFailureAction", value: "default", downstream: THROWING_STEP },
  {
    family: "http-trigger",
    axisPath: "handleChainFailureAction",
    value: "script",
    properties: { chainFailureHandlerContainer: scriptHandler("failure-script") },
    downstream: THROWING_STEP,
  },
  {
    family: "http-trigger",
    axisPath: "handleChainFailureAction",
    value: "mapper-2",
    properties: { chainFailureHandlerContainer: mapperHandler("failure-mapper") },
    downstream: THROWING_STEP,
  },
  {
    family: "http-trigger",
    axisPath: "handleChainFailureAction",
    value: "chain-call",
    properties: { chainFailureHandlerContainer: { elementId: CHAIN_CALLEE_TRIGGER_ID } },
    downstream: THROWING_STEP,
  },

  // The http-trigger correlation id and idempotency axes. `receiveCorrelationId=true` is
  // the header position too; `idempotency/enabled=true` is every actionOnDuplicate chain.
  {
    family: "http-trigger",
    axisPath: "receiveCorrelationId",
    value: true,
    properties: { correlationIdPosition: "header", correlationIdName: CORRELATION_ID_HEADER },
    downstream: CORRELATION_ECHO,
  },
  {
    family: "http-trigger",
    axisPath: "correlationIdPosition",
    value: "body",
    properties: { receiveCorrelationId: true, correlationIdName: CORRELATION_ID_FIELD },
    downstream: CORRELATION_ECHO,
  },
  { family: "http-trigger", axisPath: "idempotency/enabled", value: false, properties: { idempotency: IDEMPOTENCY }, downstream: PASSED_STEP },
  { family: "http-trigger", axisPath: "idempotency/actionOnDuplicate", value: "ignore", properties: { idempotency: IDEMPOTENCY }, downstream: PASSED_STEP },
  { family: "http-trigger", axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", properties: { idempotency: IDEMPOTENCY }, downstream: PASSED_STEP },
  {
    family: "http-trigger",
    axisPath: "idempotency/actionOnDuplicate",
    value: "execute-subchain",
    properties: { idempotency: { ...IDEMPOTENCY, chainTriggerParameters: { triggerElementId: CHAIN_CALLEE_TRIGGER_ID } } },
    downstream: PASSED_STEP,
  },

  // The breaker's window type: the configuration is a child of `circuit-breaker-2`, so both chains
  // are hand-written.
  { family: "circuit-breaker-configuration-2", axisPath: "slidingWindowType", value: "COUNT_BASED", handWritten: "circuit-breaker-count-based" },
  { family: "circuit-breaker-configuration-2", axisPath: "slidingWindowType", value: "TIME_BASED", handWritten: "circuit-breaker-time-based" },

  // The senders and the log record. A method reaches a real echo; context propagation is
  // read off a mock's matchers for `http-sender` and off the echo's trace for `graphql-sender`.
  ...HTTP_METHODS.map((value): AxisFixture => ({ family: "http-sender", axisPath: "httpMethod", value, properties: { ...INTERNAL_CALL, uri: METHOD_ECHO_URI } })),
  // `httpMethod` is required by the schema, so it carries the library default.
  ...[true, false].map((value): AxisFixture => ({ family: "http-sender", axisPath: "propagateContext", value, properties: { ...INTERNAL_CALL, httpMethod: "GET", uri: MOCKED_SENDER_URI } })),
  ...[true, false].map((value): AxisFixture => ({ family: "graphql-sender", axisPath: "propagateContext", value, properties: { ...INTERNAL_CALL, query: "query { e2e }", uri: METHOD_ECHO_URI } })),
  ...LOG_LEVELS.map((value): AxisFixture => ({ family: "log-record", axisPath: "logLevel", value, properties: { message: `${LOG_MESSAGE_PREFIX} \${header.${LOG_TOKEN_HEADER}}` } })),
];

/**
 * The trigger every generated chain starts with. `accessControlType` and `handleChainFailureAction`
 * carry library defaults the import would supply, and are spelled out anyway: the schema's `if`
 * branches match a property that is absent, so a trigger without them does not validate.
 */
const TRIGGER_PROPERTIES = {
  // The seed's route gate issues a GET, which a POST-only trigger answers 405 without running.
  httpMethodRestrict: "POST",
  accessControlType: "NONE",
  handleChainFailureAction: "default",
  // The schema defaults to `true`. On a cluster the engine then writes one gateway rule per chain
  // into a single HTTPRoute, which the Gateway API caps at 16 rules (docs/product-defects.md), and
  // every hand-written corpus fixture keeps its trigger internal as well.
  externalRoute: false,
};

/** Matches the migration list every fixture under `fixtures/chains/` declares. */
const CHAIN_MIGRATIONS = "[100, 101, 102, 103, 104, 105, 106, 107, 108]";

/** `http-trigger` + `idempotency/actionOnDuplicate` + `ignore` → `http-trigger-idempotency-actionOnDuplicate-ignore`. */
export function axisFixtureName(fixture: AxisFixture): string {
  return [fixture.family, ...fixture.axisPath.split("/"), String(fixture.value)]
    .join("-")
    .replace(/[^A-Za-z0-9-]/g, "_");
}

/** A UUID-shaped id that stays the same across runs, so the seed's delete-before-import finds it. */
function stableId(name: string, role: string): string {
  const hex = crypto.createHash("sha256").update(`${name}/${role}`).digest("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

/** `after/items/type` = `script` → `{after: [{type: "script"}]}`. */
function setAxis(properties: Record<string, unknown>, axisPath: string, value: SchemaValue): void {
  const steps = axisPath.split("/");
  let node: Record<string, unknown> = properties;
  for (let index = 0; index < steps.length - 1; index++) {
    if (steps[index + 1] === "items") {
      node = ((node[steps[index]] ??= [{}]) as Array<Record<string, unknown>>)[0];
      index++;
    } else {
      node = (node[steps[index]] ??= {}) as Record<string, unknown>;
    }
  }
  node[steps[steps.length - 1]] = value;
}

function readAxis(properties: unknown, axisPath: string): unknown {
  let node = properties;
  for (const step of axisPath.split("/")) {
    if (step === "items") node = Array.isArray(node) ? node[0] : undefined;
    else node = (node as Record<string, unknown> | undefined)?.[step];
  }
  return node;
}

/** Refuses a declaration whose element does not declare the axis, or whose axis lacks the value. */
function assertDeclared(fixture: AxisFixture, schemas: Map<string, unknown>): void {
  const schema = schemas.get(fixture.family);
  if (schema === undefined) {
    throw new Error(`axis fixture ${axisFixtureName(fixture)}: no element schema for ${fixture.family}`);
  }
  const axes = extractDiscriminators(schema, fixture.family);
  const axis = axes.find((each) => each.axisPath === fixture.axisPath);
  if (!axis) {
    throw new Error(
      `axis fixture ${axisFixtureName(fixture)}: ${fixture.family} declares no axis ` +
        `${fixture.axisPath}; its axes are ${axes.map((each) => each.axisPath).join(", ") || "none"}`,
    );
  }
  if (!axis.values.includes(fixture.value)) {
    throw new Error(
      `axis fixture ${axisFixtureName(fixture)}: ${fixture.family} ${fixture.axisPath} has no value ` +
        `${JSON.stringify(fixture.value)}; its values are ${JSON.stringify(axis.values)}`,
    );
  }
}

/**
 * The chain document for one generated axis value, with `{{RUN}}` still in it.
 *
 * The element under test is named `<axisPath>=<value>`, which is the `elementName` a session trace
 * reports, so a failed trace assertion names the branch.
 */
export function generateAxisChain(
  fixture: AxisFixture,
  schemas: Map<string, unknown>,
): Record<string, unknown> {
  assertDeclared(fixture, schemas);

  const name = axisFixtureName(fixture);
  const branch = `${fixture.axisPath}=${String(fixture.value)}`;
  const trigger = {
    id: stableId(name, "trigger"),
    name: fixture.family === "http-trigger" ? branch : "HTTP Trigger",
    type: "http-trigger",
    properties: {
      ...TRIGGER_PROPERTIES,
      contextPath: `e2e-${RUN_PLACEHOLDER}-axis-${name}`,
    } as Record<string, unknown>,
  };
  const template = structuredClone(fixture.properties ?? {});
  const elements: Array<{ id: string; name: string; type: string; properties: Record<string, unknown> }> = [trigger];
  if (fixture.family === "http-trigger") {
    Object.assign(trigger.properties, template);
    setAxis(trigger.properties, fixture.axisPath, fixture.value);
  } else {
    const underTest = { id: stableId(name, "element"), name: branch, type: fixture.family, properties: template };
    setAxis(underTest.properties, fixture.axisPath, fixture.value);
    elements.push(underTest);
  }
  if (fixture.downstream) {
    elements.push({ id: stableId(name, "downstream"), ...structuredClone(fixture.downstream) });
  }

  const document = {
    id: stableId(name, "chain"),
    $schema: "http://qubership.org/schemas/product/qip/chain",
    name: `e2e-${RUN_PLACEHOLDER}-axis-${name}`,
    description: `Generated by fixtures/axis-generator.ts: ${fixture.family} with ${branch}.`,
    content: {
      labels: [],
      elements,
      dependencies: elements.slice(1).map((each, index) => ({ from: elements[index].id, to: each.id })),
      deployAction: "NONE",
      migrations: CHAIN_MIGRATIONS,
    },
  };

  // Each element against its own schema first: the chain schema's `oneOf` over every element type
  // reports a failure once per type, which buries the one property that is actually missing.
  const ajv = schemaValidator();
  const failures = elements.flatMap((element) => {
    const validate = ajv.getSchema(`${ELEMENT_SCHEMA_ID_BASE}${element.type}.schema.yaml`)!;
    return validate(element) ? [] : (validate.errors ?? []).map((error) => ({
      message: `${element.name}${error.instancePath} ${error.message ?? "is invalid"}`,
    }));
  });
  if (failures.length === 0) failures.push(...validateDocument(document));
  if (failures.length > 0) {
    throw new Error(
      `axis fixture ${name} does not validate: add what the value needs to its properties, or ` +
        `hand-write the chain. ` +
        failures.map((each) => each.message).join("; "),
    );
  }
  return document;
}

/** Refuses a hand-written fixture that is missing or never sets the axis to the declared value. */
function assertHandWritten(fixture: AxisFixture): void {
  const name = axisFixtureName(fixture);
  const fixtureDir = path.join(CHAIN_FIXTURE_DIR, fixture.handWritten ?? "");
  if (!fixture.handWritten || !fs.existsSync(fixtureDir)) {
    throw new Error(`axis fixture ${name}: no hand-written fixture directory ${fixtureDir}`);
  }
  const { document } = readFixtureDocument(fixture.handWritten, renderFixtureTree(fixtureDir, "run"));
  const pending: unknown[] = [...(((document.content as { elements?: unknown[] })?.elements) ?? [])];
  while (pending.length > 0) {
    const element = pending.pop() as { type?: unknown; properties?: unknown; children?: unknown[] };
    if (element.type === fixture.family && readAxis(element.properties, fixture.axisPath) === fixture.value) return;
    pending.push(...(element.children ?? []));
  }
  throw new Error(
    `axis fixture ${name}: ${fixture.handWritten} has no ${fixture.family} element with ` +
      `${fixture.axisPath}=${JSON.stringify(fixture.value)}`,
  );
}

/** The generated chain documents by fixture name, after checking every hand-written declaration. */
async function generateAxisDocuments(
  fixtures: readonly AxisFixture[],
): Promise<Map<string, Record<string, unknown>>> {
  const schemas = await loadElementSchemas();
  const generated = new Map<string, Record<string, unknown>>();
  for (const fixture of fixtures) {
    if (fixture.handWritten) {
      assertHandWritten(fixture);
      continue;
    }
    const name = axisFixtureName(fixture);
    if (generated.has(name)) throw new Error(`axis fixture ${name} is declared twice`);
    generated.set(name, generateAxisChain(fixture, schemas));
  }
  return generated;
}

function dumpAxisDocument(document: Record<string, unknown>): string {
  return yaml.dump(document, { lineWidth: -1, quotingType: '"', forceQuotes: true });
}

/**
 * Rewrites `dir` with one fixture directory per generated declaration, and checks every hand-written
 * one. Returns the generated fixture names.
 */
export async function writeAxisFixtures(
  fixtures: readonly AxisFixture[] = axisFixtures,
  dir = AXIS_FIXTURE_DIR,
): Promise<string[]> {
  const generated = await generateAxisDocuments(fixtures);
  fs.rmSync(dir, { recursive: true, force: true });
  for (const [name, document] of generated) {
    fs.mkdirSync(path.join(dir, name), { recursive: true });
    fs.writeFileSync(path.join(dir, name, `${name}.chain.qip.yaml`), dumpAxisDocument(document));
  }
  return [...generated.keys()].sort();
}

/**
 * The generated fixtures rendered with `run`, in memory, by name.
 *
 * The same documents `writeAxisFixtures` writes. `seed-micro` reads them here because `seed`, which
 * runs beside it, deletes and rewrites `fixtures/axes/`.
 */
export async function renderAxisFixtures(
  run: string,
  fixtures: readonly AxisFixture[] = axisFixtures,
): Promise<Map<string, RenderedTree>> {
  const rendered = new Map<string, RenderedTree>();
  for (const [name, document] of await generateAxisDocuments(fixtures)) {
    rendered.set(name, new Map([[`${name}.chain.qip.yaml`, substitute(dumpAxisDocument(document), run)]]));
  }
  return rendered;
}
