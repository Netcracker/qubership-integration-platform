/**
 * A micro domain redeployed under load answers every call (#1160).
 *
 * A rolling update hands traffic from the old pod to the new one at two moments, and each failed in
 * its own way. A pod with no readiness probe turned Ready as its container started, 20 to 25 seconds
 * before the micro-engine could serve, so the Service sent calls to it and they answered 503. And
 * the old pod's micro-engine closed its port on SIGTERM while the proxies still routed to it, so the
 * calls in flight answered 503 or 502 from the caller's sidecar. The case calls the chain every
 * 100 ms across a redeploy and holds every answer to 200, until the old pod is gone.
 *
 * The redeploy swaps the marker header, so the answers show the new version took over: a redeploy
 * that rolled nothing would pass the status check on its own.
 *
 * The case declares the `redeploy-under-traffic-micro` behavior row. The classic case is "a redeploy
 * swaps the response and never loses the route" in `specs/api/deployments.spec.ts`.
 */
import type { APIRequestContext } from "@playwright/test";
import { test, expect } from "../../support/fixtures.js";
import { coversBehavior } from "../../registry/covers.js";
import { MICRO_READY_TIMEOUT } from "../../support/corpus.js";
import { MARKER_HEADER, setMarker, tokenizedChain } from "../../support/deployable.js";
import { integrationPods, microChainUrl, microServiceOf } from "../../support/kube.js";
import { sleep } from "../../support/poll.js";
import { tokenized } from "../../support/run.js";

/** Two micro pod starts, each measured at 60 to 75 s, and the old pod's shutdown. */
const CASE_TIMEOUT = 600_000;

/** The pause between two calls of the load, as the reproduction in #1160 used. */
const CALL_INTERVAL_MS = 100;

interface Answer {
  at: string;
  status: number;
  marker?: string;
}

/** One POST of the load, with status 0 for a call that got no response at all. */
async function call(request: APIRequestContext, url: string): Promise<Answer> {
  const at = new Date().toISOString();
  try {
    const response = await request.post(url, { data: { redeploy: at }, failOnStatusCode: false, timeout: 5_000 });
    return { at, status: response.status(), marker: response.headers()[MARKER_HEADER] };
  } catch {
    return { at, status: 0 };
  }
}

test.describe("a micro domain redeployed under load", { tag: ["@catalog", "@engine", "@slow"] }, () => {
  test("answers every call with 200", async ({ catalog, folder, run, request }) => {
    test.setTimeout(CASE_TIMEOUT);
    coversBehavior("http-trigger", "redeploy-under-traffic-micro");
    const domain = tokenized(run, "redeploy");
    const resource = microServiceOf(domain);
    const chain = await tokenizedChain(catalog, run, {
      prefix: "micro",
      what: "redeploy",
      parentId: folder.id,
      marker: "before",
    });
    const url = microChainUrl(domain, chain.contextPath);

    let deployed = false;
    try {
      await catalog.deployChains([chain.id], [domain]);
      deployed = true;
      await expect
        .poll(async () => (await call(request, url)).marker, {
          timeout: MICRO_READY_TIMEOUT,
          message: `micro domain ${domain} never began to answer`,
        })
        .toBe("before");
      const oldPods = await integrationPods(resource);

      const answers: Answer[] = [];
      let loading = true;
      const load = (async () => {
        while (loading) {
          answers.push(await call(request, url));
          await sleep(CALL_INTERVAL_MS);
        }
      })();
      try {
        await setMarker(catalog, chain, "after");
        await catalog.deployChains([chain.id], [domain]);
        // The old pod leaves the list only once its containers have exited, so the calls made while
        // it shut down are inside the window.
        await expect
          .poll(async () => (await integrationPods(resource)).filter((pod) => oldPods.includes(pod)), {
            timeout: MICRO_READY_TIMEOUT,
            message: `the old pod of micro domain ${domain} was never replaced`,
          })
          .toEqual([]);
      } finally {
        loading = false;
        await load;
      }

      expect(answers.filter((each) => each.status !== 200), "every call during the redeploy answers 200").toEqual([]);
      expect(answers.at(-1)?.marker, "the redeployed version answers last").toBe("after");
      expect(answers.map((each) => each.marker), "the old version answered during the redeploy").toContain("before");
    } finally {
      if (deployed) {
        await catalog.deleteCustomResource(domain).catch((cause: unknown) => {
          console.error(`[cleanup] micro domain ${domain} was not deleted; the sweep retries it: ${String(cause)}`);
        });
      }
    }
  });
});
