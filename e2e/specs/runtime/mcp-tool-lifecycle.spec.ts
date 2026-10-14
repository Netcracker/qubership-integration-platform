/**
 * When the classic engine offers an `mcp-trigger` chain as an MCP tool, and what a failed redeploy
 * does to the tool of the deployment still running (#1154).
 *
 * The engine prepares the tool while it builds the chain's Camel context and offers it once the
 * context has started. A deployment whose script does not compile, or whose context fails to start,
 * ends `FAILED`, and the deployment before it keeps running; its tool has to keep answering. Each case
 * builds its chains in the worker folder, because each one redeploys or fails to deploy, which a shared
 * corpus chain cannot absorb.
 *
 * The chains answer `{"called":"<version>"}`, so an answer from a running chain cannot be confused
 * with the call's own arguments, which is what the engine answered before #1136. They record no
 * session, since the cases sample the tool repeatedly and read no trace.
 */
import { test, expect } from "../../support/fixtures.js";
import { DEPLOY_TIMEOUT, ENGINE_CASE_TIMEOUT, SEED_LOGGING, waitForDeployed, type SeedChain } from "../../support/corpus.js";
import { createStepsChain } from "../../support/deployable.js";
import { tokenized } from "../../support/run.js";
import { withBuilt, type Built } from "../../support/cleanup.js";
import { McpSession } from "../../support/mcp.js";
import { sleep } from "../../support/poll.js";
import type { Catalog } from "../../support/catalog.js";
import type { APIRequestContext } from "@playwright/test";

interface ToolResult {
  content: Array<{ type: string; text: string }>;
  isError: boolean;
}

const SCRIPT = "Script";
const DOES_NOT_COMPILE = "def broken = (";
/** The pause between two samples of a tool, as `specs/api/deployments.spec.ts` paces its route. */
const SAMPLE_PAUSE = 100;
const answering = (version: string) => `exchange.getMessage().setBody('{"called":"${version}"}')`;
const answer = (version: string): ToolResult => ({ content: [{ type: "text", text: JSON.stringify({ called: version }) }], isError: false });
const V1 = answer("v1");
const V2 = answer("v2");

/** A chain of an `mcp-trigger` and a script, its tool named after the chain. */
async function mcpChain(catalog: Catalog, run: string, folderId: string, built: Built, what: string, script: string): Promise<SeedChain> {
  const service = await catalog.createMcpSystem({ name: tokenized(run, `${what}-service`), identifier: tokenized(run, `${what}-service`) });
  built.services.push({ name: `the MCP service ${service.name} (${service.id})`, remove: () => catalog.deleteMcpSystem(service.id) });
  return createStepsChain(catalog, run, {
    what,
    parentId: folderId,
    trigger: {
      name: "MCP Trigger",
      type: "mcp-trigger",
      properties: { mcpServiceIds: [service.id], name: tokenized(run, what), description: what, inputSchema: JSON.stringify({ type: "object" }) },
    },
    steps: [{ name: SCRIPT, type: "script", properties: { script } }],
    logging: { ...SEED_LOGGING, sessionsLoggingLevel: "OFF" },
  }, built.chains);
}

async function deploySnapshot(catalog: Catalog, chainId: string): Promise<void> {
  await catalog.deploy(chainId, (await catalog.createSnapshot(chainId)).id);
}

/** Waits until the engine reports a deployment of the chain `FAILED`; each case has only the one it just made. */
async function waitForFailed(catalog: Catalog, chainId: string): Promise<void> {
  await expect.poll(async () => (await catalog.runtimeDeploymentsOf(chainId)).map((row) => row.status), {
    timeout: DEPLOY_TIMEOUT,
    message: "the deployment never reported FAILED",
  }).toContain("FAILED");
}

async function openSession(request: APIRequestContext, url: string): Promise<McpSession> {
  const mcp = new McpSession(request, url);
  await mcp.open();
  return mcp;
}

const call = (mcp: McpSession, tool: string) => mcp.call<ToolResult>("tools/call", { name: tool, arguments: { ping: "x" } });

/** Deploys the chain and returns a session on which its tool answers v1. */
async function servingV1(catalog: Catalog, request: APIRequestContext, url: string, chain: SeedChain): Promise<McpSession> {
  await deploySnapshot(catalog, chain.id);
  await waitForDeployed(catalog, [chain]);
  const mcp = await openSession(request, url);
  expect(await call(mcp, chain.name)).toEqual(V1);
  return mcp;
}

test("mcp-trigger: a tool answers its chain from the moment tools/list offers it", { tag: ["@engine", "@catalog", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "it deploys chains of its own, and on a micro engine each deploy rolls the shared domain" } }, async ({ request, env, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const chain = await mcpChain(catalog, run, folder.id, built, "mcp-offered", answering("v1"));
    const mcp = await openSession(request, `${env.url("engine")}/mcp`);
    await deploySnapshot(catalog, chain.id);
    // Short and constant intervals: the engine used to offer the tool up to 2.4 s before the chain ran.
    await expect.poll(async () => (await mcp.tools()).some((each) => each.name === chain.name), {
      timeout: DEPLOY_TIMEOUT,
      intervals: [SAMPLE_PAUSE],
      message: `tools/list never offered ${chain.name}`,
    }).toBe(true);
    expect(await call(mcp, chain.name)).toEqual(V1);
  });
});

test("mcp-trigger: a redeploy switches the tool to the new version with no failed call", { tag: ["@engine", "@catalog", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "it deploys chains of its own, and on a micro engine each deploy rolls the shared domain" } }, async ({ request, env, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const chain = await mcpChain(catalog, run, folder.id, built, "mcp-redeploy", answering("v1"));
    const mcp = await servingV1(catalog, request, `${env.url("engine")}/mcp`, chain);

    await catalog.patchElementProperties(chain.id, chain.elements[SCRIPT], { script: answering("v2") });
    await deploySnapshot(catalog, chain.id);
    // The engine replaces the tool once the new context runs and stops the old context after that, so every
    // call meets a running context. Measured on three redeploys: 395 calls, each answered by the old or the new version.
    const deadline = Date.now() + DEPLOY_TIMEOUT;
    for (;;) {
      const result = await call(mcp, chain.name);
      expect([V1, V2], "a call during the redeploy").toContainEqual(result);
      if (result.content[0].text === V2.content[0].text) break;
      expect(Date.now(), "the tool never switched to v2").toBeLessThan(deadline);
      await sleep(SAMPLE_PAUSE);
    }
  });
});

test("mcp-trigger: a deployment whose script does not compile offers no tool", { tag: ["@engine", "@catalog", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "it deploys chains of its own, and on a micro engine each deploy rolls the shared domain" } }, async ({ request, env, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const chain = await mcpChain(catalog, run, folder.id, built, "mcp-broken", DOES_NOT_COMPILE);
    await deploySnapshot(catalog, chain.id);
    await waitForFailed(catalog, chain.id);
    const mcp = await openSession(request, `${env.url("engine")}/mcp`);
    expect((await mcp.tools()).map((each) => each.name)).not.toContain(chain.name);
  });
});

test("mcp-trigger: a redeploy whose script does not compile keeps the tool of the running deployment", { tag: ["@engine", "@catalog", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "it deploys chains of its own, and on a micro engine each deploy rolls the shared domain" } }, async ({ request, env, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const chain = await mcpChain(catalog, run, folder.id, built, "mcp-breaks", answering("v1"));
    const mcp = await servingV1(catalog, request, `${env.url("engine")}/mcp`, chain);

    await catalog.patchElementProperties(chain.id, chain.elements[SCRIPT], { script: DOES_NOT_COMPILE });
    await deploySnapshot(catalog, chain.id);
    await waitForFailed(catalog, chain.id);
    expect(await call(mcp, chain.name)).toEqual(V1);
  });
});

test("mcp-trigger: a redeploy whose context fails to start keeps the tool of the running deployment", { tag: ["@engine", "@catalog", "@tier2", "@classic-only"], annotation: { type: "classic-only", description: "it deploys chains of its own, and on a micro engine each deploy rolls the shared domain" } }, async ({ request, env, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await withBuilt(catalog, async (built) => {
    const chain = await mcpChain(catalog, run, folder.id, built, "mcp-no-start", answering("v1"));
    const mcp = await servingV1(catalog, request, `${env.url("engine")}/mcp`, chain);

    // A Spring scheduler parses its cron when the route starts, so the context fails to start.
    const scheduler = await catalog.createElement(chain.id, "scheduler");
    await catalog.patchElementProperties(chain.id, scheduler.id, { scheduler: "spring", "scheduler.cron": "not a cron" });
    await deploySnapshot(catalog, chain.id);
    await waitForFailed(catalog, chain.id);
    expect((await mcp.tools()).map((each) => each.name)).toContain(chain.name);
    expect(await call(mcp, chain.name)).toEqual(V1);
  });
});
