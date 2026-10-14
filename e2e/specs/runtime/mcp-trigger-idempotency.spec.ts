/**
 * The idempotency axes of `mcp-trigger`, against `fixtures/chains/mcp-trigger-idempotency-*`.
 *
 * Each case calls the chain's tool twice with one argument and reads the second session. A tool call
 * carries no header, so the fixtures key on the body, which the engine sets to the call's arguments.
 * The argument is a UUID per test execution: both engines write to one idempotency table, and the
 * classic and micro copies of this spec run at the same time. The session carries no correlation
 * id either, so a call's session is the one the chain gained during the call with the call's
 * arguments in its trigger's body.
 *
 * `shared/idempotency.hbs` puts the check inside the trigger's step, so `Idempotency` follows the
 * trigger in the trace when the check is on, and `Callee Reply` is the step
 * `fixtures/chains/chain-callee/` runs inside the caller's session.
 *
 * Both engines answer a tool call from the exchange's body and never read its exception
 * (`McpToolRegistrar`), so a duplicate under `throw-exception` answers `isError: false` with the
 * call's arguments. The last case pins that as a `test.fail()`.
 */
import { randomUUID } from "node:crypto";
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain, type SeedChain } from "../../support/corpus.js";
import { PASSED_STEP } from "../../fixtures/axis-generator.js";
import { McpSession, offeredTool } from "../../support/mcp.js";
import { element, elementNames, SESSION_TIMEOUT, trace, type ExecutionStatus, type RecordedSession, type Sessions } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import type { APIRequestContext } from "@playwright/test";
import type { Env } from "../../env/index.js";
import { notTheKnownDefect, outsideTheDefect } from "../../support/known-defect.js";

const IDEMPOTENCY_STEP = "Idempotency";
const PASSED = JSON.stringify({ called: "downstream" });

interface ToolResult {
  content: Array<{ type: string; text: string }>;
  isError: boolean;
}

interface Mode {
  axisPath: "idempotency/enabled" | "idempotency/actionOnDuplicate";
  value: boolean | string;
  /** The fixture name's suffix. */
  fixture: string;
  /** What the second call does, for the title. */
  outcome: string;
  /** The text the second call answers, given the arguments the call sent; the last case pins the failed one. */
  reply?: (argumentsText: string) => string;
  executionStatus: ExecutionStatus;
  /** The second session's steps after the trigger. */
  steps: string[];
  /** The exception the `Idempotency` step records. */
  failure?: string;
}

const MODES: Mode[] = [
  { axisPath: "idempotency/enabled", value: false, fixture: "disabled", outcome: "runs the chain again", reply: () => PASSED, executionStatus: "COMPLETED_NORMALLY", steps: [PASSED_STEP.name] },
  // The check interrupts the exchange before any step writes a body, so the tool answers its own arguments.
  { axisPath: "idempotency/actionOnDuplicate", value: "ignore", fixture: "ignore", outcome: "stops at the check", reply: (sent) => sent, executionStatus: "COMPLETED_NORMALLY", steps: [IDEMPOTENCY_STEP] },
  { axisPath: "idempotency/actionOnDuplicate", value: "throw-exception", fixture: "throw-exception", outcome: "fails at the check", executionStatus: "COMPLETED_WITH_ERRORS", steps: [IDEMPOTENCY_STEP], failure: "Duplicated idempotency key" },
  { axisPath: "idempotency/actionOnDuplicate", value: "execute-subchain", fixture: "execute-subchain", outcome: "answers from the sub-chain", reply: () => JSON.stringify({ called: "chain" }), executionStatus: "COMPLETED_NORMALLY", steps: [IDEMPOTENCY_STEP, "Callee Reply"] },
];

/**
 * Calls the tool and returns its answer and its session: the one the chain gained during the call
 * whose trigger received `key`, since the last case calls the `throw-exception` tool at the same time.
 */
async function callTool(mcp: McpSession, sessions: Sessions, chainId: string, tool: string, key: string, elements: number): Promise<{ result: ToolResult; session: RecordedSession }> {
  const before = await sessions.idsOf(chainId);
  const result = await mcp.call<ToolResult>("tools/call", { name: tool, arguments: { key } });
  let found: RecordedSession | undefined;
  await expect(async () => {
    const fresh = (await sessions.search({}, { chainId })).sessions.filter((each) => !before.has(each.id));
    const read = await Promise.all(fresh.map((each) => sessions.session(each.id)));
    const mine = read.filter((each) => trace(each)[0]?.bodyBefore?.includes(key));
    expect(mine, `sessions of ${chainId} carrying ${key}`).toHaveLength(1);
    found = mine[0];
    expect(trace(found).length).toBeGreaterThanOrEqual(elements);
    expect(found.executionStatus).not.toBe("IN_PROGRESS");
  }).toPass({ timeout: SESSION_TIMEOUT });
  return { result, session: await sessions.settled(found!) };
}

/** The chain of `mode`, with its tool offered on an open MCP session. */
async function toolOf(request: APIRequestContext, env: Env, mode: Mode): Promise<{ mcp: McpSession; chain: SeedChain }> {
  const chain = seedChain(readCorpusState(), `mcp-trigger-idempotency-${mode.fixture}`);
  const mcp = new McpSession(request, `${env.engineUrl()}/mcp`);
  await mcp.open();
  // The fixtures name the tool after the chain.
  await offeredTool(mcp, chain.name);
  return { mcp, chain };
}

for (const mode of MODES) {
  const branch = `${mode.axisPath}=${mode.value}`;
  test(`mcp-trigger ${branch}: a repeated key ${mode.outcome}`, { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
    covers("mcp-trigger", mode.axisPath, mode.value);
    // Every actionOnDuplicate chain enables the check.
    if (mode.axisPath === "idempotency/actionOnDuplicate") covers("mcp-trigger", "idempotency/enabled", true);
    const { mcp, chain } = await toolOf(request, env, mode);
    const key = randomUUID();
    const enabled = mode.value !== false;
    const firstSteps = [branch, ...(enabled ? [IDEMPOTENCY_STEP] : []), PASSED_STEP.name];

    const first = await callTool(mcp, sessions, chain.id, chain.name, key, firstSteps.length);
    expect(first.result).toEqual({ content: [{ type: "text", text: PASSED }], isError: false });
    expect(first.session.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(elementNames(first.session)).toEqual(firstSteps);

    const second = await callTool(mcp, sessions, chain.id, chain.name, key, mode.steps.length + 1);
    expect(second.session.executionStatus).toBe(mode.executionStatus);
    expect(elementNames(second.session)).toEqual([branch, ...mode.steps]);
    if (mode.failure) expect(element(second.session, IDEMPOTENCY_STEP)?.exceptionInfo?.message).toBe(mode.failure);
    if (mode.reply) expect(second.result).toEqual({ content: [{ type: "text", text: mode.reply(JSON.stringify({ key })) }], isError: false });
    else expect(second.result.content.map((each) => each.text), "the chain stopped before its downstream step").not.toContain(PASSED);
  });
}

// Not filed: `McpToolRegistrar` on both engines answers from the body and never reads the exchange's exception.
test.fail("mcp-trigger: a duplicate under throw-exception answers the tool call as an error", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ request, env, sessions }) => {
  const mode = MODES.find((each) => each.value === "throw-exception")!;
  const { mcp, chain } = await outsideTheDefect("opening the tool", () => toolOf(request, env, mode));
  const key = randomUUID();
  await outsideTheDefect("the first call", () => callTool(mcp, sessions, chain.id, chain.name, key, 3));
  const { result } = await outsideTheDefect("the duplicate call", () => callTool(mcp, sessions, chain.id, chain.name, key, 2));
  const sent = JSON.stringify({ key });
  const answeredArguments = result.content.length === 1 && result.content[0].type === "text" && result.content[0].text === sent;
  if (!result.isError && !answeredArguments) {
    notTheKnownDefect(`the duplicate answered ${JSON.stringify(result)}, which is neither an error nor the call's arguments`);
  }
  expect(result.isError, "the duplicate is answered as an error: delete the test.fail() annotation").toBe(true);
});
