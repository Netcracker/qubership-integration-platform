/**
 * The axes of the remaining synchronous elements: `http-sender`, `graphql-sender`, `log-record`,
 * `context-storage`, and `mcp-trigger` once as an element.
 *
 * - `http-sender` `httpMethod` calls `fixtures/chains/method-echo/` on the engine's own port, which
 *   answers the method it received. The testing service matches no method, and a call carrying the
 *   correlation header is sent to the testing service rather than to its URI, so these calls carry
 *   none and the session is the chain's one uncorrelated session the call added. `propagateContext`
 *   is read the other way round, off a mock whose matcher requires the caller's `X-Request-Id` or
 *   requires it empty.
 * - `graphql-sender` is never mocked. Its chains call the same echo, whose steps land in the caller's
 *   session, and the echo's own trace carries the context it received.
 * - `log-record` `logLevel` is invisible in the trace (the level travels in an internal exchange
 *   property the session strips), so it is read off the engine log. A record is written only when
 *   the chain's `logLoggingLevel` lets its level through, and the corpus deploys at `INFO`.
 * - `context-storage` needs a context service id, which a seeded chain cannot carry without a service
 *   the catalog resolves on export, so its chains are built here. A `GET` into the body leaves a map
 *   there that the trigger answers as an empty body, so what it read is asserted on the step's
 *   `bodyAfter`. `useCorrelationId` true with no context id stores under the exchange's correlation
 *   id. Its chains are built once per worker in `beforeAll`.
 * - `mcp-trigger` is driven as an MCP client drives it, over the engine's streamable HTTP endpoint at
 *   `/mcp`. The protocol is spoken over the `request` fixture: three JSON-RPC calls do not justify an
 *   SDK dependency. Its session carries no correlation id, so it is found by the tool call's
 *   argument. Its idempotency rows are not covered, and the registry says why.
 *
 * Correlation ids on both senders have no case, and the registry says why; `swimlane.color` has no
 * runtime at all.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  DEPLOY_TIMEOUT,
  ENGINE_CASE_TIMEOUT,
  SEED_LOGGING,
  readCorpusState,
  seedChain,
  waitForDeployed,
  waitForRoutes,
  type SeedChain,
} from "../../support/corpus.js";
import {
  axisFixtureName,
  expectEchoedMethod,
  HTTP_METHODS,
  LOG_MESSAGE_PREFIX,
  LOG_TOKEN_HEADER,
} from "../../fixtures/axis-generator.js";
import { createStepsChain, type ChainStep } from "../../support/deployable.js";
import { logWindowStart } from "../../support/logs.js";
import { callToken, tokenized } from "../../support/run.js";
import { noChainRequestId, type RequestMatcher } from "../../support/testing-service.js";
import {
  CALLER_REQUEST_ID,
  callChain,
  element,
  elementNames,
  HTTP_TRIGGER_STEPS,
  REQUEST_ID_HEADER,
  Sessions,
  SESSION_TIMEOUT,
  type RecordedSession,
} from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { release, waitForFirstRecording, waitForRecording, withBuilt, type Built } from "../../support/cleanup.js";
import type { APIRequestContext } from "@playwright/test";
import type { Env } from "../../env/index.js";

/** How long a log line may take to reach the engine log after its step is traced. */
const LOG_TIMEOUT = 10_000;

function generated(family: string, axisPath: string, value: string | boolean): SeedChain {
  return seedChain(readCorpusState(), axisFixtureName({ family, axisPath, value }));
}

for (const method of HTTP_METHODS) {
  const branch = `httpMethod=${method}`;
  test(`${branch}: an http sender sends ${method}`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("http-sender", "httpMethod", method);
    const chain = generated("http-sender", "httpMethod", method);
    // Sessions an earlier run against a kept corpus left.
    const earlier = await sessions.idsOf(chain.id);

    await expectEchoedMethod(request, env.chainUrl(chain.contextPath), method);
    const session = await sessions.onlyOf(chain.id, 3, (each) => each.externalSessionCipId === null && !earlier.has(each.id));
    expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch]);
  });
}

const MOCKED = '{"mocked":"http-sender"}';

for (const value of [true, false]) {
  const branch = `propagateContext=${value}`;
  test(`${branch}: an http sender ${value ? "sends" : "drops"} the caller's request id`, { tag: ["@engine", "@sessions", "@testing-service", "@tier2"] }, async ({ request, env, sessions, testingService, run }) => {
    covers("http-sender", "propagateContext", value);
    const chain = generated("http-sender", "propagateContext", value);
    const matcher: RequestMatcher = value
      ? { type: "equal", entityType: "header", entityName: REQUEST_ID_HEADER, value: CALLER_REQUEST_ID }
      : noChainRequestId(env);
    await testingService.withMock(
      {
        name: tokenized(run, `http-sender-propagateContext-${value}`),
        reference: { chainId: chain.id, elementId: chain.elements[branch] },
        response: { status: 200, body: MOCKED, headers: [{ name: "Content-Type", value: "application/json" }] },
        matchers: [matcher],
      },
      async () => {
        const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {}, headers: { [REQUEST_ID_HEADER]: CALLER_REQUEST_ID } });
        expect(call.response.status()).toBe(200);
        expect(await call.response.text()).toBe(MOCKED);
        expect(elementNames(await sessions.byExternalId(call.token, { elements: 3 }))).toEqual([...HTTP_TRIGGER_STEPS, branch]);
      },
    );
  });
}

for (const value of [true, false]) {
  const branch = `propagateContext=${value}`;
  test(`${branch}: a GraphQL sender ${value ? "hands" : "does not hand"} the caller's request id to the server`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("graphql-sender");
    covers("graphql-sender", "propagateContext", value);
    const chain = generated("graphql-sender", "propagateContext", value);

    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {}, headers: { [REQUEST_ID_HEADER]: CALLER_REQUEST_ID } });
    expect(call.response.status()).toBe(200);
    expect(JSON.parse(await call.response.text())).toEqual({ method: "POST" });
    // The correlation header reaches the echo either way, so its steps land in this session.
    const session = await sessions.byExternalId(call.token, { elements: 6 });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch, ...HTTP_TRIGGER_STEPS, "Echo Method"]);
    const received = element(session, "Echo Method")?.contextBefore?.[REQUEST_ID_HEADER];
    if (value) expect(received).toBe(CALLER_REQUEST_ID);
    else expect(received, "the echo's trigger mints a request id of its own").toMatch(/^\d+\.\d+\.\d+$/);
  });
}

/** The levels of the engine log lines that carry the record's message, in order. */
async function loggedLevels(env: Env, since: string, token: string): Promise<string[]> {
  const message = ` - ${LOG_MESSAGE_PREFIX} ${token}`;
  return (await env.logs("engine", since))
    .split("\n")
    .filter((line) => line.trimEnd().endsWith(message))
    .map((line) => /^\[[^\]]+\] \[(\w+)\s*\]/.exec(line)?.[1] ?? line);
}

async function callLogRecord(request: APIRequestContext, env: Env, sessions: Sessions, level: string, token: string): Promise<void> {
  const branch = `logLevel=${level}`;
  const chain = generated("log-record", "logLevel", level);
  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {}, headers: { [LOG_TOKEN_HEADER]: token } });
  expect(call.response.status()).toBe(200);
  const session = await sessions.byExternalId(call.token, { elements: 3 });
  expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch]);
  expect(element(session, branch)?.executionStatus).toBe("COMPLETED_NORMALLY");
}

for (const [level, logged] of [["Error", "ERROR"], ["Warning", "WARN"], ["Info", "INFO"]] as const) {
  test(`logLevel=${level}: a log record writes its message at ${logged}`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("log-record");
    covers("log-record", "logLevel", level);
    const since = logWindowStart();
    const token = callToken("log");
    await callLogRecord(request, env, sessions, level, token);
    await expect.poll(() => loggedLevels(env, since, token), { timeout: LOG_TIMEOUT }).toEqual([logged]);
  });
}

test.describe("context-storage", () => {
  // One worker for the describe: its chains are built once, in `beforeAll`.
  test.describe.configure({ mode: "default" });

  const CONTEXT_ID = "e2e-context-id";
  const CONTEXT_VALUE = "e2e-context-value";
  const CORRELATION = "e2e-correlation";
  const KEY = "e2eKey";
  const TARGET = "e2e-context";
  const GET_STEP = "Get Context";

  const built: Built = { chains: [], services: [] };
  const chains: Record<string, SeedChain> = {};

  test.beforeAll(async ({ catalog, env, folder, run, playwright }, workerInfo) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    // The worker index keeps a restarted worker's chains off the routes the previous one may still hold.
    const prefix = `context-storage-w${workerInfo.workerIndex}`;
    const contextService = await catalog.createContextSystem(tokenized(run, prefix));
    const contextServiceId = contextService.id;
    built.services.push({ name: `the context service ${contextService.name} (${contextServiceId})`, remove: () => catalog.deleteContextSystem(contextServiceId) });
    const byHeader = { contextServiceId, useCorrelationId: false, contextId: `\${header.${CONTEXT_ID}}` };
    const get = { ...byHeader, operation: "GET", keys: KEY };
    const set: ChainStep = { name: "Set Context", type: "context-storage", properties: { ...byHeader, operation: "SET", key: KEY, value: `\${header.${CONTEXT_VALUE}}`, ttl: 600 } };
    const definitions: Record<string, ChainStep[]> = {
      set: [set],
      get: [{ name: GET_STEP, type: "context-storage", properties: get }],
      header: [{ name: GET_STEP, type: "context-storage", properties: { ...get, target: "HEADER", targetName: TARGET } }],
      property: [
        { name: GET_STEP, type: "context-storage", properties: { ...get, target: "PROPERTY", targetName: TARGET } },
        { name: "Read Property", type: "script", properties: { script: `exchange.getMessage().setBody(String.valueOf(exchange.getProperty('${TARGET}')))` } },
      ],
      delete: [{ name: "Delete Context", type: "context-storage", properties: { ...byHeader, operation: "DELETE" } }],
      // A script sets the correlation id from a header, standing in for a trigger that receives one.
      correlation: [
        { name: "Set Correlation Id", type: "script", properties: { script: `exchange.setProperty('correlationId', exchange.getMessage().getHeader('${CORRELATION}'))` } },
        { name: "Set Context", type: "context-storage", properties: { contextServiceId, useCorrelationId: true, operation: "SET", key: KEY, value: `\${header.${CONTEXT_VALUE}}`, ttl: 600 } },
      ],
    };
    for (const [what, steps] of Object.entries(definitions)) {
      chains[what] = await createStepsChain(catalog, run, { what: `${prefix}-${what}`, parentId: folder.id, steps, logging: SEED_LOGGING }, built.chains);
    }
    const all = Object.values(chains);
    for (const chain of all) await catalog.deploy(chain.id, (await catalog.createSnapshot(chain.id)).id);
    await waitForDeployed(catalog, all);
    await waitForRoutes(env, all);
    // The hook has no `sessions` fixture, which is test-scoped.
    const api = await playwright.request.newContext();
    try {
      for (const chain of all) await waitForRecording(env, new Sessions(api), chain);
    } finally {
      await api.dispose();
    }
  });

  test.afterAll(async ({ catalog }) => {
    await release(catalog, built, false);
  });

  async function store(request: APIRequestContext, env: Env, chain: SeedChain, headers: Record<string, string>): Promise<void> {
    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {}, headers });
    expect(call.response.status()).toBe(200);
  }

  /** What a `GET` into the body read for the context id, off its step. */
  async function readBack(request: APIRequestContext, env: Env, sessions: Sessions, contextId: string): Promise<unknown> {
    const call = await callChain(request, env.chainUrl(chains.get.contextPath), { data: {}, headers: { [CONTEXT_ID]: contextId } });
    expect(call.response.status()).toBe(200);
    const session = await sessions.byExternalId(call.token, { elements: 3 });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, GET_STEP]);
    return JSON.parse(element(session, GET_STEP)?.bodyAfter ?? "null");
  }

  test("operation=SET stores a value that operation=GET reads back into the body", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("context-storage");
    covers("context-storage", "operation", "SET");
    covers("context-storage", "operation", "GET");
    covers("context-storage", "target", "BODY");
    const contextId = callToken("context");
    const value = callToken("value");
    await store(request, env, chains.set, { [CONTEXT_ID]: contextId, [CONTEXT_VALUE]: value });
    expect(await readBack(request, env, sessions, contextId)).toEqual({ [KEY]: value });
    expect(await readBack(request, env, sessions, callToken("context")), "another context id reads nothing").toEqual({});
  });

  for (const target of ["HEADER", "PROPERTY"] as const) {
    test(`target=${target}: a GET writes what it read into the named ${target.toLowerCase()}`, { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
      covers("context-storage", "target", target);
      const contextId = callToken("context");
      const value = callToken("value");
      await store(request, env, chains.set, { [CONTEXT_ID]: contextId, [CONTEXT_VALUE]: value });

      const chain = target === "HEADER" ? chains.header : chains.property;
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {}, headers: { [CONTEXT_ID]: contextId } });
      expect(call.response.status()).toBe(200);
      // Not unwrapped, so the target holds the map, rendered the way each surface renders one.
      if (target === "HEADER") expect(call.response.headers()[TARGET]).toBe(`${KEY}=${value}`);
      else expect(await call.response.text()).toBe(`{${KEY}=${value}}`);
      const session = await sessions.byExternalId(call.token, { elements: target === "HEADER" ? 3 : 4 });
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, GET_STEP, ...(target === "PROPERTY" ? ["Read Property"] : [])]);
      expect(element(session, GET_STEP)?.bodyAfter, "the body is left alone").toBe("{}");
    });
  }

  test("operation=DELETE removes the context", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("context-storage", "operation", "DELETE");
    const contextId = callToken("context");
    const value = callToken("value");
    await store(request, env, chains.set, { [CONTEXT_ID]: contextId, [CONTEXT_VALUE]: value });
    expect(await readBack(request, env, sessions, contextId)).toEqual({ [KEY]: value });

    const call = await callChain(request, env.chainUrl(chains.delete.contextPath), { data: {}, headers: { [CONTEXT_ID]: contextId } });
    expect(call.response.status()).toBe(200);
    expect(elementNames(await sessions.byExternalId(call.token, { elements: 3 }))).toEqual([...HTTP_TRIGGER_STEPS, "Delete Context"]);
    expect(await readBack(request, env, sessions, contextId)).toEqual({});
  });

  test("useCorrelationId=true stores the value under the exchange's correlation id", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("context-storage", "useCorrelationId", true);
    const correlationId = callToken("correlation");
    const value = callToken("value");
    const call = await callChain(request, env.chainUrl(chains.correlation.contextPath), { data: {}, headers: { [CORRELATION]: correlationId, [CONTEXT_VALUE]: value } });
    expect(call.response.status()).toBe(200);
    const session = await sessions.byExternalId(call.token, { elements: 4 });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Set Correlation Id", "Set Context"]);
    expect(element(session, "Set Context")?.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(await readBack(request, env, sessions, correlationId)).toEqual({ [KEY]: value });
    // The context service is this worker's own, so only this case could write under the empty id.
    expect(await readBack(request, env, sessions, ""), "nothing is stored under the empty context id").toEqual({});
  });
});

interface Tool {
  name: string;
  title?: string;
  description?: string;
  annotations?: Record<string, unknown>;
}

/** A streamable HTTP MCP session: JSON-RPC over POST, answered as JSON or as one SSE event. */
class McpSession {
  private readonly request: APIRequestContext;
  private readonly url: string;
  private id = "";
  private next = 1;

  constructor(request: APIRequestContext, url: string) {
    this.request = request;
    this.url = url;
  }

  async open(): Promise<void> {
    const response = await this.post({ jsonrpc: "2.0", id: this.next++, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "qip-e2e", version: "1" } } });
    expect(response.status(), await response.text()).toBe(200);
    this.id = response.headers()["mcp-session-id"];
    expect(this.id, "the server opens a session").toBeTruthy();
    expect((await this.post({ jsonrpc: "2.0", method: "notifications/initialized" })).status()).toBe(202);
  }

  async call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const id = this.next++;
    const response = await this.post({ jsonrpc: "2.0", id, method, params });
    const text = await response.text();
    expect(response.status(), text).toBe(200);
    const payload = response.headers()["content-type"]?.startsWith("text/event-stream")
      ? text.split("\n").filter((line) => line.startsWith("data:")).map((line) => line.slice("data:".length)).join("")
      : text;
    const message = JSON.parse(payload) as { id: number; result?: T; error?: unknown };
    expect(message.id).toBe(id);
    expect(message.error, `${method} answered an error`).toBeUndefined();
    return message.result as T;
  }

  async tools(): Promise<Tool[]> {
    return (await this.call<{ tools: Tool[] }>("tools/list")).tools;
  }

  private post(data: unknown) {
    return this.request.post(this.url, {
      headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream", ...(this.id ? { "Mcp-Session-Id": this.id } : {}) },
      data,
    });
  }
}

const MCP_TRIGGER = "MCP Trigger";
const ECHO_STEP = "Echo Arguments";

/** The session of the chain whose echo step answered the tool call carrying `ping`, if it has arrived. */
async function mcpSessionOf(sessions: Sessions, chainId: string, ping: string): Promise<RecordedSession | undefined> {
  for (const summary of (await sessions.search({}, { chainId })).sessions) {
    const session = await sessions.session(summary.id);
    if (element(session, ECHO_STEP)?.bodyAfter?.includes(ping)) return session;
  }
  return undefined;
}

test("mcp-trigger: a deployed chain is an MCP tool that runs the chain, and undeploying it removes the tool", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, catalog, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  covers("mcp-trigger");
  await withBuilt(catalog, async (built) => {
    const service = await catalog.createMcpSystem({ name: tokenized(run, "mcp-service"), identifier: tokenized(run, "mcp-service") });
    built.services.push({ name: `the MCP service ${service.name} (${service.id})`, remove: () => catalog.deleteMcpSystem(service.id) });
    const toolName = tokenized(run, "mcp-tool");
    const chain = await createStepsChain(catalog, run, {
      what: "mcp-trigger",
      parentId: folder.id,
      // Flags that differ from each other and from the defaults, so each one is read, not assumed.
      trigger: {
        name: MCP_TRIGGER,
        type: "mcp-trigger",
        properties: {
          mcpServiceIds: [service.id],
          name: toolName,
          title: "E2E Tool",
          description: "Echoes its arguments",
          inputSchema: JSON.stringify({ type: "object", properties: { ping: { type: "string" } }, required: ["ping"] }),
          readOnly: false,
          destructive: true,
          idempotent: true,
          openWorld: true,
        },
      },
      steps: [{ name: ECHO_STEP, type: "script", properties: { script: "exchange.getMessage().setBody(groovy.json.JsonOutput.toJson(exchange.getMessage().getBody()))" } }],
      logging: SEED_LOGGING,
    }, built.chains);
    await catalog.deploy(chain.id, (await catalog.createSnapshot(chain.id)).id);
    await waitForDeployed(catalog, [chain]);

    const mcp = new McpSession(request, `${env.url("engine")}/mcp`);
    await mcp.open();
    let tool: Tool | undefined;
    await expect.poll(async () => (tool = (await mcp.tools()).find((each) => each.name === toolName)) !== undefined, { timeout: DEPLOY_TIMEOUT, message: `tools/list never offered ${toolName}` }).toBe(true);
    expect(tool).toMatchObject({
      title: "E2E Tool",
      description: "Echoes its arguments",
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: true },
    });
    // A tool call has no route to warm up, and no correlation header to find its session by.
    await waitForFirstRecording(chain.name, {
      send: async (marker) => {
        await mcp.call("tools/call", { name: toolName, arguments: { ping: marker } });
      },
      recorded: async (marker) => (await mcpSessionOf(sessions, chain.id, marker)) !== undefined,
    });

    const ping = callToken("mcp");
    const result = await mcp.call("tools/call", { name: toolName, arguments: { ping } });
    expect(result).toEqual({ content: [{ type: "text", text: JSON.stringify({ ping }) }], isError: false });
    let session: RecordedSession | undefined;
    await expect(async () => {
      session = await mcpSessionOf(sessions, chain.id, ping);
      expect(session, `the session of the tool call carrying ${ping}`).toBeDefined();
      expect(session?.executionStatus).toBe("COMPLETED_NORMALLY");
      expect(elementNames(session as RecordedSession)).toEqual([MCP_TRIGGER, ECHO_STEP]);
    }).toPass({ timeout: SESSION_TIMEOUT });

    await catalog.undeployAll(chain.id);
    await expect.poll(async () => (await mcp.tools()).some((each) => each.name === toolName), { timeout: DEPLOY_TIMEOUT, message: `tools/list still offers ${toolName}` }).toBe(false);
  });
});
