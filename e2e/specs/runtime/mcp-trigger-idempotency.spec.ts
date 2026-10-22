/**
 * `actionOnDuplicate=throw-exception` on `mcp-trigger`, against
 * `fixtures/chains/mcp-trigger-idempotency-throw-exception/`. The other idempotency modes run the
 * same `shared/idempotency.hbs` partial, which `http-trigger-idempotency.spec.ts` proves.
 *
 * The case calls the chain's tool twice with one argument and reads the second session. A tool call
 * carries no header, so the fixture keys on the body, which the engine sets to the call's arguments.
 * The argument is a UUID per test execution: both engines write to one idempotency table, and the
 * classic and micro copies of this spec run at the same time. The session carries no correlation
 * id either, so a call's session is the one the chain gained during the call with the call's
 * arguments in its trigger's body.
 *
 * A duplicate answers the tool call with `isError: true`. Until #1136 both engines answered from
 * the exchange's body and never read its exception (`McpToolRegistrar`), so the duplicate answered
 * `isError: false` with the call's arguments.
 */
import { randomUUID } from "node:crypto";
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { branchName, PASSED_STEP } from "../../fixtures/axis-generator.js";
import { McpSession, mcpSessionsOf, offeredTool } from "../../support/mcp.js";
import { element, elementNames, SESSION_TIMEOUT, trace, type RecordedSession, type Sessions } from "../../support/sessions.js";
import { coversIdempotency, firstSteps, IDEMPOTENCY_STEP, idempotencyModes } from "../../support/idempotency.js";

const PASSED = JSON.stringify({ called: "downstream" });

interface ToolResult {
  content: Array<{ type: string; text: string }>;
  isError: boolean;
}

const MODE = idempotencyModes(PASSED_STEP.name).find((each) => each.value === "throw-exception")!;

/** Calls the tool and returns its answer and its session: the one the chain gained during the call whose trigger received `key`. */
async function callTool(mcp: McpSession, sessions: Sessions, chainId: string, tool: string, key: string, elements: number): Promise<{ result: ToolResult; session: RecordedSession }> {
  const before = await sessions.idsOf(chainId);
  const result = await mcp.call<ToolResult>("tools/call", { name: tool, arguments: { key } });
  let found: RecordedSession | undefined;
  await expect(async () => {
    const mine = await mcpSessionsOf(sessions, chainId, key, before);
    expect(mine, `sessions of ${chainId} carrying ${key}`).toHaveLength(1);
    found = mine[0];
    expect(trace(found).length).toBeGreaterThanOrEqual(elements);
    expect(found.executionStatus).not.toBe("IN_PROGRESS");
  }).toPass({ timeout: SESSION_TIMEOUT });
  return { result, session: await sessions.settled(found!) };
}

test.describe("mcp-trigger idempotency", { tag: ["@engine", "@sessions"] }, () => {
  test("a duplicate under throw-exception is a tool error", async ({ request, env, sessions }) => {
    coversIdempotency("mcp-trigger", MODE);
    const chain = seedChain(readCorpusState(), `mcp-trigger-idempotency-${MODE.fixture}`);
    const mcp = new McpSession(request, `${env.engineUrl()}/mcp`);
    await mcp.open();
    // The fixture names the tool after the chain.
    await offeredTool(mcp, chain.name);
    const key = randomUUID();
    const steps = firstSteps(MODE, PASSED_STEP.name);
    const branch = branchName(MODE);

    const first = await callTool(mcp, sessions, chain.id, chain.name, key, steps.length);
    expect(first.result).toEqual({ content: [{ type: "text", text: PASSED }], isError: false });
    expect(first.session.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(elementNames(first.session)).toEqual(steps);

    const second = await callTool(mcp, sessions, chain.id, chain.name, key, MODE.steps.length + 1);
    expect(second.session.executionStatus).toBe(MODE.executionStatus);
    expect(elementNames(second.session)).toEqual([branch, ...MODE.steps]);
    expect(element(second.session, IDEMPOTENCY_STEP)?.exceptionInfo?.message).toBe(MODE.failure);
    expect(second.result.isError, `the duplicate answered ${JSON.stringify(second.result)}`).toBe(true);
    expect(second.result.content.map((each) => each.text), "the chain stopped before its downstream step").not.toContain(PASSED);
  });
});
