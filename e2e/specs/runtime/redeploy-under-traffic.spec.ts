/**
 * A chain redeployed while it takes calls answers every call on the classic engine, first from the
 * old deployment and then from the new one.
 *
 * `specs/runtime/mcp-tool-lifecycle.spec.ts` proves the same for an MCP tool through `McpSession`,
 * on the classic engine only. This case calls an HTTP trigger every 50 ms while the redeploy runs,
 * and the micro engine has its own case in `specs/runtime-micro-redeploy/`, where a redeploy rolls
 * the domain's pod.
 *
 * Measured on Compose over five redeploys: the first v2 answer came after 39 to 54 calls, and every
 * call answered 200.
 *
 * The case turns red when a call during the redeploy answers anything but 2xx, or when the new
 * marker never arrives within `DEPLOY_TIMEOUT`.
 *
 * Shown failing: an undeploy before the deploy (298 calls answered non-2xx), a redeploy that never
 * ran (no v2 within 60 s), and v1 expected after the deploy.
 */
import type { APIRequestContext } from "@playwright/test";
import { test, expect } from "../../support/fixtures.js";
import { DEPLOY_TIMEOUT, ENGINE_CASE_TIMEOUT, waitForDeployed, waitForRoutes } from "../../support/corpus.js";
import { MARKER_HEADER, setMarker, tokenizedChain, type DeployableChain } from "../../support/deployable.js";
import { noteChain } from "../../support/diagnostics.js";
import { samplesUntil } from "../../support/poll.js";
import { callChain } from "../../support/sessions.js";
import { withBuilt } from "../../support/cleanup.js";
import { coversBehaviour } from "../../registry/covers.js";
import type { Catalog } from "../../support/catalog.js";
import type { Env } from "../../env/index.js";

const CALL_PAUSE = 50;

interface Answer {
  status: number;
  marker: string | undefined;
}

async function answerOf(request: APIRequestContext, env: Env, chain: DeployableChain): Promise<Answer> {
  const { response } = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
  return { status: response.status(), marker: response.headers()[MARKER_HEADER] };
}

async function deploySnapshot(catalog: Catalog, chainId: string): Promise<void> {
  await catalog.deploy(chainId, (await catalog.createSnapshot(chainId)).id);
}

test.describe("redeploy under traffic", { tag: ["@engine", "@catalog", "@slow", "@classic-only"], annotation: { type: "classic-only", description: "it deploys a chain of its own, and on a micro engine each deploy rolls the shared domain; the micro case is in the runtime-micro-redeploy project" } }, () => {
  test("every call during a redeploy answers 2xx", async ({ request, env, catalog, folder, run }) => {
    test.setTimeout(ENGINE_CASE_TIMEOUT);
    coversBehaviour("http-trigger", "redeploy-under-traffic");
    await withBuilt(catalog, async (built) => {
      const chain = await tokenizedChain(catalog, run, { prefix: "redeploy-traffic", what: "live", parentId: folder.id, marker: "v1" });
      built.chains.push(chain);
      noteChain(chain);
      await deploySnapshot(catalog, chain.id);
      await waitForDeployed(catalog, [chain]);
      await waitForRoutes(env, [chain]);
      expect(await answerOf(request, env, chain)).toEqual({ status: 200, marker: "v1" });

      const redeploy = (async () => {
        await setMarker(catalog, chain, "v2");
        await deploySnapshot(catalog, chain.id);
      })();
      const answers = await samplesUntil(() => answerOf(request, env, chain), (each) => each.marker === "v2", DEPLOY_TIMEOUT, CALL_PAUSE);
      await redeploy;

      const switched = answers.findIndex((each) => each.marker === "v2");
      test.info().annotations.push({ type: "redeploy", description: `${answers.length} calls, the first v2 answer at call ${switched + 1}` });
      expect(answers.filter((each) => each.status < 200 || each.status > 299), "calls during the redeploy that answered non-2xx").toEqual([]);
      expect(switched, "the redeployed chain never answered v2").toBeGreaterThan(0);
      expect(answers.slice(0, switched).every((each) => each.marker === "v1"), "an answer before the switch carried no v1 marker").toBe(true);
    });
  });
});
