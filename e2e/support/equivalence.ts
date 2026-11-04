/**
 * What the creation-path equivalence case drives and compares, shared by its classic half in
 * `specs/api/` and its micro half in `specs/runtime-micro-redeploy/`.
 */
import { expect, type APIRequestContext } from "@playwright/test";
import { MARKER_HEADER, type DeployableChain } from "./deployable.js";
import { callChain, trace, type RecordedSession, type Sessions } from "./sessions.js";
import type { Env } from "../env/index.js";

/** What the request produced, in the terms the two paths are compared on. */
export interface Answer {
  status: number;
  body: string;
  marker: string | null;
  /** `elementName camelName executionStatus` per step, depth first — the trace, not its outline. */
  steps: string[];
}

function stepsOf(session: RecordedSession): string[] {
  return trace(session).map(
    (each) => `${each.elementName} ${each.camelName} ${each.executionStatus}`,
  );
}

/**
 * How many steps `createDeployableChain`'s chain records.
 *
 * Three, not two: the trigger contributes **`HTTP Trigger`** and **`Validate Request`**, and the
 * header modification one. The floor here used to be two, and being one short made the comparison
 * vacuous rather than merely lenient — a first read that caught 2 of the 3 set the second read's
 * target to 2, the second read then saw all three, and the case reported a divergence between one
 * chain and itself. Measured once in four full runs.
 *
 * Waiting for the session to settle does not close that gap and is already done:
 * `Sessions.byExternalId` polls past `IN_PROGRESS`, and the element documents are indexed
 * separately from the session, so a settled session can still be a step short.
 */
export const RECORDED_STEPS = 3;

/** The one request both paths are driven with, and everything observable about what it did. */
export async function drive(
  request: APIRequestContext,
  env: Env,
  sessions: Sessions,
  chain: DeployableChain,
  wantedSteps: number,
): Promise<Answer> {
  const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "equivalence" },
  });
  const session = await sessions.byExternalId(token, { elements: wantedSteps });
  return {
    status: response.status(),
    body: await response.text(),
    marker: response.headers()[MARKER_HEADER] ?? null,
    steps: stepsOf(session),
  };
}

/**
 * The comparison, and it is deliberately over what the chain *did* rather than over what the
 * catalog stored: the defect this exists for is one where both records look identical and the two
 * chains behave differently.
 */
export function expectSameRun(fromDocument: Answer, fromApi: Answer): void {
  expect(fromDocument.status, "the imported chain answered a different status").toBe(fromApi.status);
  expect(fromDocument.body, "the imported chain answered a different body").toBe(fromApi.body);
  expect(fromDocument.marker, "the header modification did not survive the round trip").toBe(
    fromApi.marker,
  );
  expect(fromDocument.steps, "the imported chain ran a different trace").toEqual(fromApi.steps);
}
