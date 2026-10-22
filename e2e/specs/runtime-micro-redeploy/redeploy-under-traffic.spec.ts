/**
 * The micro case of `specs/runtime/redeploy-under-traffic.spec.ts`: a chain redeployed while it
 * takes calls stops answering for a bounded window, then answers from the new deployment.
 *
 * A redeploy rolls the micro domain's pod, so the domain answers non-2xx between the old pod and the
 * new one, where the classic engine answers every call. The case runs in a domain of its own for
 * that reason, as `placeholder-redeploy.spec.ts` does.
 *
 * Measured on the Kubernetes of Docker Desktop over eight redeploys: the domain answered v1 for 41 to
 * 61 calls, then 503 for 11.5 to 12.3 s, with an occasional 502 or 504 among them, then v2. The
 * limit allows about three times that window, for a slower CI runner.
 *
 * The case turns red when the window of non-2xx answers runs past `UNAVAILABLE_LIMIT`, when a call
 * answers v1 after the window, or when the new marker never arrives within `MICRO_READY_TIMEOUT`.
 *
 * Shown failing: a limit of 5 s against a window of 11830 ms, and v1 expected after the window.
 */
import type { APIRequestContext } from "@playwright/test";
import { test, expect } from "../../support/fixtures.js";
import { release, type Built } from "../../support/cleanup.js";
import { DEPLOY_TIMEOUT, MICRO_READY_TIMEOUT } from "../../support/corpus.js";
import {
  deleteDomain,
  deployToDomain,
  dispatchToDomain,
  MARKER_HEADER,
  setMarker,
  tokenedDomain,
  tokenizedChain,
  type DeployableChain,
  type TokenedDomain,
} from "../../support/deployable.js";
import { noteChain } from "../../support/diagnostics.js";
import { samplesUntil } from "../../support/poll.js";
import { callChain } from "../../support/sessions.js";
import { coversBehaviour } from "../../registry/covers.js";
import type { Env } from "../../env/index.js";

/** The domain create and the route poll, plus building the chain. */
const SETUP_TIMEOUT = MICRO_READY_TIMEOUT + 60_000;

/** The redeploy request, then the pod roll the calls wait out. */
const CASE_TIMEOUT = DEPLOY_TIMEOUT + MICRO_READY_TIMEOUT + 60_000;

const CALL_PAUSE = 50;

/** The longest run of non-2xx answers the redeploy may cause, from first to last. */
const UNAVAILABLE_LIMIT = 40_000;

const built: Built = { chains: [], services: [] };
let domain: TokenedDomain | undefined;
let live: DeployableChain;

interface Answer {
  at: number;
  status: number;
  marker: string | undefined;
}

async function answerOf(request: APIRequestContext, env: Env, chain: DeployableChain): Promise<Answer> {
  const { response } = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
  return { at: Date.now(), status: response.status(), marker: response.headers()[MARKER_HEADER] };
}

const ok = (each: Answer) => each.status >= 200 && each.status <= 299;

/** The answers as runs, such as `200 v1 x40, 503 x120, 200 v2 x1`. */
function runs(answers: readonly Answer[]): string {
  const labels = answers.map((each) => (ok(each) ? `${each.status} ${each.marker}` : `${each.status}`));
  const out: Array<[string, number]> = [];
  for (const label of labels) {
    const last = out.at(-1);
    if (last?.[0] === label) last[1]++;
    else out.push([label, 1]);
  }
  return out.map(([label, count]) => `${label} x${count}`).join(", ");
}

test.describe("redeploy under traffic on the micro engine", { tag: ["@engine", "@catalog", "@slow"] }, () => {
  test.beforeAll(async ({ catalog, folder, run, microDomainState }) => {
    test.setTimeout(SETUP_TIMEOUT);
    live = await tokenizedChain(catalog, run, { prefix: "redeploy-traffic", what: "micro", parentId: folder.id, marker: "v1" });
    built.chains.push(live);
    noteChain(live);
    domain = tokenedDomain(run, "redeploy-traffic", microDomainState);
    await deployToDomain(catalog, domain, [live]);
  });

  test.afterAll(async ({ catalog }) => {
    if (domain) await deleteDomain(catalog, domain);
    await release(catalog, built, false);
  });

  test("a redeploy answers non-2xx for a bounded window", async ({ request, catalog }) => {
    test.setTimeout(CASE_TIMEOUT);
    coversBehaviour("http-trigger", "redeploy-under-traffic-micro");
    const target = domain as TokenedDomain;
    expect(await answerOf(request, target.env, live)).toMatchObject({ status: 200, marker: "v1" });

    await setMarker(catalog, live, "v2");
    const redeploy = dispatchToDomain(catalog, target, [live]);
    const answers = await samplesUntil(() => answerOf(request, target.env, live), (each) => each.marker === "v2", MICRO_READY_TIMEOUT, CALL_PAUSE);
    await redeploy;

    const switched = answers.findIndex((each) => each.marker === "v2");
    const failed = answers.filter((each) => !ok(each));
    const window = failed.length === 0 ? 0 : failed[failed.length - 1].at - failed[0].at;
    test.info().annotations.push({ type: "redeploy", description: `${runs(answers)}, non-2xx for ${window} ms` });
    expect(switched, `the redeployed chain never answered v2 on micro domain ${target.domain}`).toBeGreaterThan(0);
    expect(window, "the non-2xx window of the redeploy").toBeLessThanOrEqual(UNAVAILABLE_LIMIT);
    // With no non-2xx answer at all, every answer before v2 is the old deployment's.
    const lastFailed = failed.length === 0 ? switched : answers.indexOf(failed[failed.length - 1]);
    expect(answers.slice(lastFailed + 1, switched).filter((each) => each.marker === "v1"), "v1 answers after the unavailable window").toEqual([]);
  });
});
