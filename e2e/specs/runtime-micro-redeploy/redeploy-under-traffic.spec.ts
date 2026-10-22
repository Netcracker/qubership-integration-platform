/**
 * A chain redeployed on the micro engine while it takes calls stops answering for a bounded window,
 * then answers from the new deployment. The classic case is "a redeploy swaps the response and never
 * loses the route" in `specs/api/deployments.spec.ts`.
 *
 * A redeploy rolls the micro domain's pod, so the domain answers non-2xx between the old pod and the
 * new one. The case runs in a domain of its own for that reason, as `placeholder-redeploy.spec.ts`
 * does.
 *
 * Measured on the Kubernetes of Docker Desktop over eight redeploys: the domain answered v1 for 41 to
 * 61 calls, then 503 for 11.5 to 12.3 s, with an occasional 502 or 504 among them, then v2. The
 * limit allows about three times that window, for a slower CI runner.
 *
 * The case turns red when the window of non-2xx answers runs past `UNAVAILABLE_LIMIT`, when a 2xx
 * before the switch carries no v1 marker, when a call answers v1 after the window, or when the new
 * marker never arrives within `MICRO_READY_TIMEOUT`.
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
import { coversBehavior } from "../../registry/covers.js";
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
    coversBehavior("http-trigger", "redeploy-under-traffic-micro");
    const target = domain as TokenedDomain;
    expect(await answerOf(request, target.env, live)).toMatchObject({ status: 200, marker: "v1" });

    await setMarker(catalog, live, "v2");
    const redeploy = dispatchToDomain(catalog, target, [live]);
    // A rejected dispatch ends the sampling, and the await below rethrows it.
    let rejected = false;
    redeploy.catch(() => {
      rejected = true;
    });
    const answers = await samplesUntil(() => answerOf(request, target.env, live), (each) => rejected || each.marker === "v2", MICRO_READY_TIMEOUT, CALL_PAUSE);
    await redeploy;

    const switched = answers.findIndex((each) => each.marker === "v2");
    const failed = answers.filter((each) => !ok(each));
    const lastFailure = failed.at(-1);
    const unavailableMs = lastFailure ? lastFailure.at - failed[0].at : 0;
    test.info().annotations.push({ type: "redeploy", description: `${answers.length} calls, the first v2 answer at call ${switched + 1}, ${failed.length} non-2xx over ${unavailableMs} ms` });
    expect(switched, `the redeployed chain never answered v2 on micro domain ${target.domain}`).toBeGreaterThan(0);
    expect(unavailableMs, "the non-2xx window of the redeploy").toBeLessThanOrEqual(UNAVAILABLE_LIMIT);
    expect(answers.slice(0, switched).filter((each) => ok(each) && each.marker !== "v1"), "a 2xx before the switch carried no v1 marker").toEqual([]);
    // With no non-2xx answer at all, every answer before v2 is the old deployment's.
    const lastFailed = lastFailure ? answers.indexOf(lastFailure) : switched;
    expect(answers.slice(lastFailed + 1, switched).filter((each) => each.marker === "v1"), "v1 answers after the unavailable window").toEqual([]);
  });
});
