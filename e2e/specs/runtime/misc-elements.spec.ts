/**
 * The axes of the remaining synchronous elements: `http-sender`, `graphql-sender`, `log-record`,
 * `context-storage`, and `mcp-trigger` once as an element.
 *
 * - `http-sender` `httpMethod` calls `fixtures/chains/method-echo/` on the engine's own port, which
 *   answers the method it received. The testing service matches no method, and a call carrying the
 *   correlation header is sent to the testing service rather than to its URI, so these calls carry
 *   none and the session is the chain's one uncorrelated session the call added. `propagateContext`
 *   is read the other way round, off a mock whose matcher requires the caller's `X-Request-Id` or
 *   requires it empty. `correlationIdPosition` puts the correlation id in the mock's answer, in a
 *   header or in a body field, and the step after the sender writes what the sender received.
 * - `graphql-sender` is never mocked. Its `propagateContext` chains call the same echo, whose steps
 *   land in the caller's session, and the echo's own trace carries the context it received. Its
 *   `correlationIdPosition` chains call `fixtures/chains/correlation-echo/`, which answers a different
 *   correlation id in its header and in its body, so the id the sender received shows which one it
 *   read.
 * - `log-record` `logLevel` is invisible in the trace (the level travels in an internal exchange
 *   property the session strips), so it is read off the engine log. A record is written only when
 *   the chain's `logLoggingLevel` lets its level through, and the corpus deploys at `INFO`.
 * - `context-storage` chains call the seeded context service. A `GET` into the body leaves a map
 *   there that the trigger answers as an empty body, so what it read is asserted on the step's
 *   `bodyAfter`. `useCorrelationId` true with no context id stores under the exchange's correlation
 *   id. Every case stores under context ids of its own, so the cases share the chains.
 * - `mcp-trigger` is driven as an MCP client drives it, over the engine's streamable HTTP endpoint at
 *   `/mcp`, against the seeded tool on the seeded MCP service. `support/mcp.ts` speaks the protocol
 *   over the `request` fixture: three JSON-RPC calls do not justify an SDK dependency. Its session
 *   carries no correlation id, so it is found by the tool call's argument. That undeploying the
 *   chain removes the tool is a case of its own, on a chain it builds. Its idempotency axes are in
 *   `mcp-trigger-idempotency.spec.ts`.
 *
 * `swimlane.color` has no runtime at all, and the registry says why.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  DEPLOY_TIMEOUT,
  ENGINE_CASE_TIMEOUT,
  SEED_LOGGING,
  readCorpusState,
  seedChain,
  waitForDeployed,
  type SeedChain,
} from "../../support/corpus.js";
import {
  axisFixtureName,
  CORRELATION_ECHO,
  CORRELATION_ID_FIELD,
  CORRELATION_ID_HEADER,
  ECHOED_BODY_CORRELATION_ID,
  ECHOED_HEADER_CORRELATION_ID,
  expectEchoedMethod,
  HTTP_METHODS,
  LOG_MESSAGE_PREFIX,
  LOG_TOKEN_HEADER,
} from "../../fixtures/axis-generator.js";
import { createStepsChain } from "../../support/deployable.js";
import { logWindowStart } from "../../support/logs.js";
import { readUntil } from "../../support/poll.js";
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
  trace,
  type ChainCall,
  type RecordedSession,
} from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { strikesAsKnown } from "../../support/known-defect.js";
import { CONTEXT_STORE_UNTRANSACTED, MOCK_BYPASSED } from "./micro-defects.js";
import { waitForFirstRecording, withBuilt } from "../../support/cleanup.js";
import type { APIRequestContext } from "@playwright/test";
import { McpSession, offeredTool } from "../../support/mcp.js";
import type { Env } from "../../env/index.js";

/** How long a log line may take to reach the engine log after its step is traced. */
const LOG_TIMEOUT = 10_000;

function generated(axis: { family: string; axisPath: string; value: string | boolean }): SeedChain {
  return seedChain(readCorpusState(), axisFixtureName(axis));
}

for (const method of HTTP_METHODS) {
  const branch = `httpMethod=${method}`;
  test(`${branch}: an http sender sends ${method}`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("http-sender", "httpMethod", method);
    const chain = generated({ family: "http-sender", axisPath: "httpMethod", value: method });
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
    const chain = generated({ family: "http-sender", axisPath: "propagateContext", value });
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
        const body = await call.response.text();
        await strikesAsKnown(MOCK_BYPASSED, async () => {
          expect(call.response.status(), body).toBe(200);
          expect(body).toBe(MOCKED);
        });
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
    const chain = generated({ family: "graphql-sender", axisPath: "propagateContext", value });

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

for (const position of ["header", "body"] as const) {
  const branch = `correlationIdPosition=${position}`;
  test(`${branch}: an http sender receives the correlation id the response carries`, { tag: ["@engine", "@sessions", "@testing-service", "@tier2"] }, async ({ request, env, sessions, testingService, run }) => {
    covers("http-sender", "correlationIdPosition", position);
    const chain = generated({ family: "http-sender", axisPath: "correlationIdPosition", value: position });
    const sent = callToken("correlation");
    // The mock answers an id in both places, the configured one and a decoy in the other.
    const decoy = callToken("decoy");
    const [header, field] = position === "header" ? [sent, decoy] : [decoy, sent];
    const response = {
      status: 200,
      body: JSON.stringify({ [CORRELATION_ID_FIELD]: field }),
      headers: [{ name: "Content-Type", value: "application/json" }, { name: CORRELATION_ID_HEADER, value: header }],
    };
    await testingService.withMock({ name: tokenized(run, `http-sender-correlationIdPosition-${position}`), reference: { chainId: chain.id, elementId: chain.elements[branch] }, response }, async () => {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
      const body = await call.response.text();
      await strikesAsKnown(MOCK_BYPASSED, async () => expect(call.response.status(), body).toBe(200));
      expect(elementNames(await sessions.byExternalId(call.token, { elements: 4 }))).toEqual([...HTTP_TRIGGER_STEPS, branch, CORRELATION_ECHO.name]);
      expect(JSON.parse(body)).toEqual({ correlationId: sent });
    });
  });
}

for (const [position, received] of [["header", ECHOED_HEADER_CORRELATION_ID], ["body", ECHOED_BODY_CORRELATION_ID]] as const) {
  const branch = `correlationIdPosition=${position}`;
  test(`${branch}: a GraphQL sender receives the correlation id the server answers in the ${position}`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("graphql-sender", "correlationIdPosition", position);
    const chain = generated({ family: "graphql-sender", axisPath: "correlationIdPosition", value: position });

    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    const body = await call.response.text();
    expect(call.response.status(), body).toBe(200);
    expect(JSON.parse(body)).toEqual({ correlationId: received });
    const session = await sessions.byExternalId(call.token, { elements: 7 });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch, ...HTTP_TRIGGER_STEPS, "Echo Correlation Ids", CORRELATION_ECHO.name]);
  });
}

/** The levels of the engine log lines that carry the record's message, in order. */
async function loggedLevels(env: Env, since: string, token: string): Promise<string[]> {
  // Both engines' patterns close the MDC block with `] %m`. The micro engine's log manager reads the
  // pattern's `%ex{5}` as `%e` and the text `x{5}`, which then opens the next line, so the level is
  // not anchored to the line start.
  const message = `] ${LOG_MESSAGE_PREFIX} ${token}`;
  return (await env.engineLogs(since))
    .split("\n")
    .filter((line) => line.trimEnd().endsWith(message))
    .map((line) => /\[(\w+)\s*\] \[request_id=/.exec(line)?.[1] ?? line);
}

async function callLogRecord(request: APIRequestContext, env: Env, sessions: Sessions, level: string, token: string): Promise<void> {
  const branch = `logLevel=${level}`;
  const chain = generated({ family: "log-record", axisPath: "logLevel", value: level });
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
  const CONTEXT_ID = "e2e-context-id";
  const CONTEXT_VALUE = "e2e-context-value";
  const CORRELATION = "e2e-correlation";
  const KEY = "e2eKey";
  const TARGET = "e2e-context";
  const GET_STEP = "Get Context";

  /** The seeded `context-storage-<what>` chain. */
  function contextChain(what: "set" | "get" | "header" | "property" | "delete" | "correlation"): SeedChain {
    return seedChain(readCorpusState(), `context-storage-${what}`);
  }

  /** Asserts a SET answered 200. The answer names no cause, so a failure carries what the trace recorded. */
  async function expectStored(sessions: Sessions, call: ChainCall): Promise<void> {
    // A failed step can still read IN_PROGRESS, with no exception, after its session has completed.
    const causes = async () =>
      trace(await sessions.byExternalId(call.token, { elements: 3 })).map((step) => step.exceptionInfo?.message).filter(Boolean);
    const cause = call.response.status() === 200
      ? ""
      : (await readUntil(causes, (found) => found.length > 0, SESSION_TIMEOUT, 1_000)).join("; ");
    await strikesAsKnown(CONTEXT_STORE_UNTRANSACTED, async () => expect(call.response.status(), cause).toBe(200));
  }

  async function store(request: APIRequestContext, env: Env, sessions: Sessions, headers: Record<string, string>): Promise<void> {
    await expectStored(sessions, await callChain(request, env.chainUrl(contextChain("set").contextPath), { data: {}, headers }));
  }

  /** What a `GET` into the body read for the context id, off its step. */
  async function readBack(request: APIRequestContext, env: Env, sessions: Sessions, contextId: string): Promise<unknown> {
    const call = await callChain(request, env.chainUrl(contextChain("get").contextPath), { data: {}, headers: { [CONTEXT_ID]: contextId } });
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
    // The switch is off, so the context id decides; with it on, the two context ids would share one record.
    covers("context-storage", "useCorrelationId", false);
    const contextId = callToken("context");
    const value = callToken("value");
    await store(request, env, sessions, { [CONTEXT_ID]: contextId, [CONTEXT_VALUE]: value });
    expect(await readBack(request, env, sessions, contextId)).toEqual({ [KEY]: value });
    expect(await readBack(request, env, sessions, callToken("context")), "another context id reads nothing").toEqual({});
  });

  test("a second SET on the same context id replaces the value the first one stored", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    // The second SET updates the stored record in place, which only the transaction around it writes (#1128).
    covers("context-storage", "operation", "SET");
    const contextId = callToken("context");
    await store(request, env, chains.set, { [CONTEXT_ID]: contextId, [CONTEXT_VALUE]: callToken("value") });
    const second = callToken("value");
    await store(request, env, chains.set, { [CONTEXT_ID]: contextId, [CONTEXT_VALUE]: second });
    expect(await readBack(request, env, sessions, contextId)).toEqual({ [KEY]: second });
  });

  for (const target of ["HEADER", "PROPERTY"] as const) {
    test(`target=${target}: a GET writes what it read into the named ${target.toLowerCase()}`, { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
      covers("context-storage", "target", target);
      const contextId = callToken("context");
      const value = callToken("value");
      await store(request, env, sessions, { [CONTEXT_ID]: contextId, [CONTEXT_VALUE]: value });

      const chain = contextChain(target === "HEADER" ? "header" : "property");
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
    await store(request, env, sessions, { [CONTEXT_ID]: contextId, [CONTEXT_VALUE]: value });
    expect(await readBack(request, env, sessions, contextId)).toEqual({ [KEY]: value });

    const call = await callChain(request, env.chainUrl(contextChain("delete").contextPath), { data: {}, headers: { [CONTEXT_ID]: contextId } });
    expect(call.response.status()).toBe(200);
    expect(elementNames(await sessions.byExternalId(call.token, { elements: 3 }))).toEqual([...HTTP_TRIGGER_STEPS, "Delete Context"]);
    expect(await readBack(request, env, sessions, contextId)).toEqual({});
  });

  test("useCorrelationId=true stores the value under the exchange's correlation id", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("context-storage", "useCorrelationId", true);
    const correlationId = callToken("correlation");
    const value = callToken("value");
    const call = await callChain(request, env.chainUrl(contextChain("correlation").contextPath), { data: {}, headers: { [CORRELATION]: correlationId, [CONTEXT_VALUE]: value } });
    await expectStored(sessions, call);
    const session = await sessions.byExternalId(call.token, { elements: 4 });
    expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, "Set Correlation Id", "Set Context"]);
    expect(element(session, "Set Context")?.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(await readBack(request, env, sessions, correlationId)).toEqual({ [KEY]: value });
    // No chain of the seeded context service stores under the empty id unless this one does.
    expect(await readBack(request, env, sessions, ""), "nothing is stored under the empty context id").toEqual({});
  });
});

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

test("mcp-trigger: a deployed chain is an MCP tool that runs the chain", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  covers("mcp-trigger");
  const chain = seedChain(readCorpusState(), "mcp-trigger");
  // The fixture names the tool after its chain.
  const toolName = chain.name;
  const mcp = new McpSession(request, `${env.engineUrl()}/mcp`);
  await mcp.open();
  // Flags that differ from each other and from the defaults, so each one is read, not assumed.
  expect(await offeredTool(mcp, toolName)).toMatchObject({
    title: "E2E Tool",
    description: "Echoes its arguments",
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: true },
  });

  const ping = callToken("mcp");
  const result = await mcp.call("tools/call", { name: toolName, arguments: { ping } });
  expect(result).toEqual({ content: [{ type: "text", text: JSON.stringify({ ping }) }], isError: false });
  await expect(async () => {
    const session = await mcpSessionOf(sessions, chain.id, ping);
    expect(session, `the session of the tool call carrying ${ping}`).toBeDefined();
    expect(session?.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(elementNames(session as RecordedSession)).toEqual([MCP_TRIGGER, ECHO_STEP]);
  }).toPass({ timeout: SESSION_TIMEOUT });
});

test("mcp-trigger: undeploying the chain removes its tool", { tag: ["@engine", "@catalog", "@sessions", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "it deploys and undeploys a chain of its own, and on a micro engine each deploy rolls the shared domain" } }, async ({ request, env, catalog, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const service = await catalog.createMcpSystem({ name: tokenized(run, "mcp-service-undeploy"), identifier: tokenized(run, "mcp-service-undeploy") });
    built.services.push({ name: `the MCP service ${service.name} (${service.id})`, remove: () => catalog.deleteMcpSystem(service.id) });
    const toolName = tokenized(run, "mcp-tool-undeploy");
    const chain = await createStepsChain(catalog, run, {
      what: "mcp-trigger-undeploy",
      parentId: folder.id,
      trigger: {
        name: MCP_TRIGGER,
        type: "mcp-trigger",
        properties: {
          mcpServiceIds: [service.id],
          name: toolName,
          description: "Echoes its arguments",
          inputSchema: JSON.stringify({ type: "object", properties: { ping: { type: "string" } }, required: ["ping"] }),
        },
      },
      steps: [{ name: ECHO_STEP, type: "script", properties: { script: "exchange.getMessage().setBody(groovy.json.JsonOutput.toJson(exchange.getMessage().getBody()))" } }],
      logging: SEED_LOGGING,
    }, built.chains);
    await catalog.deploy(chain.id, (await catalog.createSnapshot(chain.id)).id);
    await waitForDeployed(catalog, [chain]);

    const mcp = new McpSession(request, `${env.engineUrl()}/mcp`);
    await mcp.open();
    await offeredTool(mcp, toolName);
    // The tool runs the chain before the undeploy, so its absence afterwards is the undeploy's doing.
    await waitForFirstRecording(chain.name, {
      send: async (marker) => {
        await mcp.call("tools/call", { name: toolName, arguments: { ping: marker } });
      },
      recorded: async (marker) => (await mcpSessionOf(sessions, chain.id, marker)) !== undefined,
    });

    await catalog.undeployAll(chain.id);
    await expect.poll(async () => (await mcp.tools()).some((each) => each.name === toolName), { timeout: DEPLOY_TIMEOUT, message: `tools/list still offers ${toolName}` }).toBe(false);
  });
});
