/**
 * What a spec that builds its own chains has to put back.
 *
 * A spec that deploys chains of its own rather than reading the shared corpus leaves two kinds of
 * state behind. Neither is reached by the sweeps that cover the rest of the suite:
 *
 * - **A deployed chain holds its route.** The worker-folder cascade deletes the chain, and the
 *   engine keeps serving what it was told to serve until something undeploys it.
 * - **A logging-properties write is a Consul key filed under the chain id.** Deleting the chain
 *   does not remove it and deleting the folder does not either, so it outlives the id it names and
 *   orphans permanently.
 *
 * It also holds the gate such a chain passes before its first call, `waitForRecording`.
 */
import { expect, test } from "@playwright/test";
import type { Catalog } from "./catalog.js";
import { DEPLOY_TIMEOUT, type DeployedChain } from "./corpus.js";
import { readUntil } from "./poll.js";
import { callToken } from "./run.js";
import { CORRELATION_HEADER, SESSION_TIMEOUT, type Sessions } from "./sessions.js";
import type { Env } from "../env/index.js";

/**
 * What this module reads of a chain: the id it addresses and the name it reports by.
 *
 * A `SeedChain` satisfies it structurally, which is what most runtime specs hand over; a spec
 * that built one chain of its own writes the two fields and no padding.
 */
export interface ReleasableChain {
  id: string;
  name: string;
}

/**
 * The delete that the chain's last moment of addressability buys, once the move out has failed.
 *
 * `null` says the key went and nothing was left behind: the cascade then takes a chain that has no
 * key under its id, which is the outcome the whole path exists for. A sentence says the key is
 * orphaned permanently, and that is the one leak this module cannot recover from.
 */
async function lastAttempt(
  catalog: Catalog,
  chain: ReleasableChain,
  moveFailure: string,
): Promise<string | null> {
  const retry = await catalog.deleteLoggingProperties(chain.id).then(
    () => null,
    (cause: unknown) => String(cause),
  );
  if (retry === null) {
    console.error(
      `[cleanup] ${chain.name} (${chain.id}) could not be moved out of the worker folder ` +
        `(${moveFailure}), so its logging key was deleted on a second attempt, before the cascade ` +
        `takes the chain. Nothing was left behind.`,
    );
    return null;
  }
  return (
    `it could not be moved out of the worker folder (${moveFailure}) and a second delete failed ` +
    `too (${retry}), so the cascade orphans the key under an id nothing can list`
  );
}

/**
 * Drops the chains' Consul keys, undeploys them, and reports what it could not.
 *
 * `.catch(() => {})` over either hop swallows exactly the failure the `finally` exists to prevent,
 * so every failure that leaves state behind is collected and named. It is raised only when the body
 * succeeded: a teardown error thrown over a real assertion failure replaces the finding with its
 * own, and the reader then has the leak instead of the reason for it. `bodyFailed` is what separates
 * the two.
 *
 * The Consul key goes first, and the ordering is the same one the shared sweep keeps: the two hops
 * are not equally recoverable. A chain left deployed is still named by its id and its folder, so
 * the residue sweep reaches it; the key is addressed by an id that stops existing the moment the
 * worker folder cascades. A cleanup cut short is what makes the order matter: whichever hop runs
 * second may never run at all.
 *
 * **A case that hits its timeout gets neither hop.** Playwright abandons the body without rejecting
 * it, and the worker's teardown deletes the worker folder while the body still waits, so the cascade
 * can take a chain whose key was already written. That orphan is accepted rather than guarded: the
 * run is already red with the timeout, and the key names a chain id no later run reuses.
 *
 * **A failed key delete moves the chain to the root**, which is the same invariant `sweepRunToken`
 * and `teardownCorpus` keep by not deleting such a chain: while the chain exists, the key is
 * addressable and `findResidue` reports it. Here the chain is not this module's to keep — the
 * `folder` worker fixture deletes the worker folder after the worker's last test, and the cascade
 * takes every chain under it. Moving the chain out is what survives that, and the name still carries
 * the run token, so the end-of-run sweep finds it and tries the key again.
 *
 * **A move that fails too spends the last moment the key is addressable.** The chain stays in the
 * worker folder, so the cascade is about to take it and the id the key is filed under with it, and
 * no listing reaches a key whose chain is gone. So the delete is issued once more, and the outcomes
 * separate there: a second delete that answers leaves nothing behind and is reported as a line to
 * read, and one that fails too is a permanent orphan and is reported as a failure of this teardown.
 *
 * **A failed undeploy is logged and nothing more.** The chain delete undeploys on its own —
 * `ChainService.deleteByIdIfExists` calls `deploymentService.deleteAllByChainId` before it touches
 * the row (`ChainService.java:190`), and `FolderService.deleteRuntimeDeployments` does it down a
 * folder tree (`FolderService.java:283`) — so the folder cascade removes the route whether this hop
 * answered or not, and failing over it reports a leak that is not there.
 */
export async function releaseChains(
  catalog: Catalog,
  chains: readonly ReleasableChain[],
  bodyFailed: boolean,
): Promise<void> {
  reportLeaks((await releaseKeys(catalog, chains)).failures, bodyFailed);
}

/** The work of `releaseChains`, returning the ids of the chains whose key survived instead of reporting. */
async function releaseKeys(
  catalog: Catalog,
  chains: readonly ReleasableChain[],
): Promise<{ failures: string[]; keptKeys: Set<string> }> {
  const failures: string[] = [];
  const keptKeys = new Set<string>();
  for (const chain of chains) {
    const keyFailure = await catalog.deleteLoggingProperties(chain.id).then(
      () => null,
      (cause: unknown) => String(cause),
    );
    if (keyFailure !== null) {
      const moveFailure = await catalog.moveChain(chain.id).then(
        () => null,
        (cause: unknown) => String(cause),
      );
      const held =
        moveFailure === null
          ? "it was moved to the root, so the worker folder cascade cannot take it and the " +
            "end-of-run sweep can try the key again"
          : await lastAttempt(catalog, chain, moveFailure);
      if (moveFailure === null || held !== null) keptKeys.add(chain.id);
      if (held !== null) {
        failures.push(
          `the logging key of ${chain.name} (${chain.id}) was not deleted (${keyFailure}), so ${held}`,
        );
      }
    }
    await catalog.undeployAll(chain.id).catch((cause: unknown) => {
      console.error(
        `[cleanup] ${chain.name} (${chain.id}) was not undeployed before its folder cascade, ` +
          `which undeploys it anyway: ${String(cause)}`,
      );
    });
  }
  return { failures, keptKeys };
}

/** What a case built: chains in the worker folder, and what sits outside it: services, variables. */
export interface Built {
  chains: ReleasableChain[];
  services: BuiltService[];
}

/** An entity outside the worker folder, named for the report and removed by `remove`. */
export interface BuiltService {
  name: string;
  remove: () => Promise<void>;
}

/**
 * Releases the chains, then deletes them and the services they use: the catalog refuses to delete a
 * service a chain still uses ("Service used by one or more chains"), and the folder cascade comes
 * too late for that. A failed delete is reported the way `releaseChains` reports a leak: thrown
 * when the body passed, logged when it failed.
 *
 * A chain whose key survived is not deleted, for the reason `releaseChains` keeps it: it is the only
 * handle on the key. A service it still uses then fails to delete and is reported; the run-token
 * sweep removes both later.
 */
export async function release(catalog: Catalog, built: Built, bodyFailed: boolean): Promise<void> {
  const { failures, keptKeys } = await releaseKeys(catalog, built.chains);
  if (built.services.length > 0) {
    for (const chain of built.chains) {
      if (keptKeys.has(chain.id)) continue;
      await catalog.deleteChain(chain.id).catch((cause: unknown) => {
        failures.push(`the chain ${chain.name} (${chain.id}) was not deleted: ${String(cause)}`);
      });
    }
    for (const service of built.services) {
      await service.remove().catch((cause: unknown) => {
        failures.push(`${service.name} was not deleted: ${String(cause)}`);
      });
    }
  }
  reportLeaks(failures, bodyFailed);
}

/** Runs `body` with a fresh `Built` and releases it afterwards, before the caller asserts anything else. */
export async function withBuilt<T>(catalog: Catalog, body: (built: Built) => Promise<T>): Promise<T> {
  const built: Built = { chains: [], services: [] };
  let result: T;
  try {
    result = await body(built);
  } catch (cause) {
    await release(catalog, built, true);
    throw cause;
  }
  await release(catalog, built, false);
  return result;
}

function reportLeaks(failures: string[], bodyFailed: boolean): void {
  if (failures.length === 0) return;
  const report = `this spec left state on the stack: ${failures.join("; ")}`;
  if (bodyFailed) {
    // The assertion that failed is the finding; this is context for it and must not displace it.
    console.error(`[cleanup] ${report}`);
    return;
  }
  throw new Error(report);
}

/**
 * How many calls right after a deploy may record no session before `waitForRecording` fails.
 *
 * A chain deployed in the same engine batch as another can lose the session of its first call, and
 * only that one. Two leave one call of slack;
 * above that, a loss that grows shows up as a red case instead of slower warm-ups.
 */
export const UNRECORDED_WARM_UP_LIMIT = 2;

/** A warm-up call carrying `marker`, and the check whether the call's session arrived. */
export interface WarmUp {
  send(marker: string): Promise<void>;
  recorded(marker: string): Promise<boolean>;
}

/**
 * Returns once a warm-up call records a session, counting the calls before it that recorded none.
 *
 * A call right after a deploy can record nothing; see `UNRECORDED_WARM_UP_LIMIT`. Warm-up calls
 * carry their own markers, so they reach nothing a case asserts.
 */
export async function waitForFirstRecording(what: string, warmUp: WarmUp): Promise<void> {
  const markers: string[] = [];
  let first = -1;
  await expect
    .poll(
      async () => {
        const marker = callToken("warm-up");
        markers.push(marker);
        await warmUp.send(marker);
        for (const [index, each] of markers.entries()) {
          if (await warmUp.recorded(each)) {
            first = index;
            return true;
          }
        }
        return false;
      },
      { timeout: DEPLOY_TIMEOUT, intervals: [1_000], message: `${what} records no session` },
    )
    .toBe(true);

  // An earlier call may only be indexed later, so it counts as unrecorded after the lookup budget.
  let pending = markers.slice(0, first);
  const unrecorded = await readUntil(
    async () => {
      const still: string[] = [];
      for (const each of pending) if (!(await warmUp.recorded(each))) still.push(each);
      return (pending = still);
    },
    (still) => still.length === 0,
    SESSION_TIMEOUT,
    1_000,
  );
  if (unrecorded.length > 0) {
    const description = `${what}: ${unrecorded.length} of the first ${first + 1} warm-up call(s) recorded no session`;
    console.log(`[warm-up] ${description}`);
    test.info().annotations.push({ type: "unrecorded warm-up", description });
  }
  expect(
    unrecorded.length,
    `${what}: more calls after the deploy recorded no session than the known loss accounts for ` +
      `(a chain deployed in the same engine batch as another can lose the session of its first call)`,
  ).toBeLessThanOrEqual(UNRECORDED_WARM_UP_LIMIT);
}

/** `waitForFirstRecording` over the chain's HTTP route, correlating each warm-up by its header. */
export async function waitForRecording(env: Env, sessions: Sessions, chain: DeployedChain): Promise<void> {
  await waitForFirstRecording(chain.name, {
    send: async (marker) => {
      await fetch(env.chainUrl(chain.contextPath), {
        method: "POST",
        headers: { "Content-Type": "application/json", [CORRELATION_HEADER]: marker },
        body: "{}",
      });
    },
    recorded: async (marker) => (await sessions.attemptByExternalId(marker, false)).session !== null,
  });
}
