/** A call to the `long-running` fixture left in flight, so a case can observe its live exchange. */
import { expect, type APIRequestContext, type APIResponse } from "@playwright/test";
import type { Catalog, LiveExchangeExtView } from "./catalog.js";
import type { Env } from "../env/index.js";
import { callToken } from "./run.js";
import { CORRELATION_HEADER } from "./sessions.js";

/** A call that is left running, with the token that finds its session afterwards. */
export interface HeldCall {
  token: string;
  /** Resolves when the chain answers, whether it completed or was killed. */
  answer: Promise<APIResponse>;
}

/**
 * Starts a call that the fixture holds for `holdMs`, and does **not** wait for it.
 *
 * `callChain` in `support/sessions.ts` awaits the response, which arrives only after the hold, and
 * the case has to observe the exchange during it. The promise is returned so the case can settle it
 * before the test ends: an unawaited request outliving its `request` fixture is a dangling handle
 * Playwright reports as an unhandled error.
 */
export function hold(request: APIRequestContext, env: Env, contextPath: string, holdMs: number): HeldCall {
  const token = callToken();
  const answer = request.fetch(env.chainUrl(contextPath), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      [CORRELATION_HEADER]: token,
      "e2e-hold-ms": String(holdMs),
    },
    data: {},
    // The hold plus the engine's own overhead. Left explicit because the default would abort the
    // request mid-hold on a slow stack and the case would read that as a finished exchange.
    timeout: holdMs + 60_000,
  });
  return { token, answer };
}

/** The held exchange, waited for by chain id rather than assumed to be there by the time we look. */
export async function untilListed(catalog: Catalog, chainId: string): Promise<LiveExchangeExtView> {
  let found: LiveExchangeExtView | undefined;
  await expect
    .poll(
      async () => {
        found = (await catalog.liveExchanges()).find((each) => each.chainId === chainId);
        return found !== undefined;
      },
      { message: `no live exchange for chain ${chainId} was ever listed` },
    )
    .toBe(true);
  return found!;
}
