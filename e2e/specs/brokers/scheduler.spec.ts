/**
 * `quartz-scheduler`, against `fixtures/brokers/scheduler-basic`.
 *
 * A cron trigger with no inbound message of any kind, so a case correlates the two-hop way:
 * search by chain id, then read the one session back with its
 * trace. Not `sessions.onlyOf` -- that helper requires exactly one fresh match, which fits a case
 * that fires the chain itself. This chain's cron keeps firing it for the whole run whether or not a
 * spec is watching, so more than one fresh session can already be sitting there by the time a poll
 * reads the list; the first one found after the snapshot is enough to prove the element runs.
 *
 * `deleteJob` is not covered here, and carries no registry row either way. Quartz's own tables live
 * in `engine_qrtz_db`, no engine REST endpoint exposes scheduled jobs, and rule 16 forbids a spec
 * reading the database directly -- so whether a job's trigger was deleted after it fired has no
 * user-visible observable on this stack. `deleteJob` is also a plain boolean property with no `if`
 * branch in `quartz-scheduler.schema.yaml`, so `registry/discriminators.ts` extracts no axis for it
 * in the first place: a manually added row would fail `specs/schema/coverage.spec.ts`'s "no row
 * names a value the schemas no longer declare" check. The gap is recorded here, in prose, rather
 * than invented as a registry entry that check would immediately reject.
 */
import { test, expect } from "../../support/fixtures.js";
import { brokerChain, readBrokersCorpusState } from "../../support/brokers.js";
import {
  SESSION_TIMEOUT,
  elementNames,
  failedElements,
  hasTrace,
  trace,
  type RecordedSession,
  type Sessions,
} from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

/**
 * The next settled session of `chainId` that `before` does not carry, with its trace.
 *
 * `Sessions.onlyOf` asserts exactly one match, which is right for a case that causes the one session
 * itself. A cron fires independently of this spec, so between the snapshot and the read more than
 * one fresh session can exist; any one of them proves the same trace shape, so the first fully
 * settled one is enough.
 */
async function nextFiring(
  sessions: Sessions,
  chainId: string,
  elements: number,
): Promise<RecordedSession> {
  const before = await sessions.idsOf(chainId);
  let found: RecordedSession | undefined;
  await expect
    .poll(
      async () => {
        const page = await sessions.search({}, { chainId });
        const fresh = page.sessions.find((each) => !before.has(each.id));
        if (!fresh) return false;
        const full = await sessions.session(fresh.id);
        if (!hasTrace(full) || trace(full).length < elements || full.executionStatus === "IN_PROGRESS") {
          return false;
        }
        found = full;
        return true;
      },
      { timeout: SESSION_TIMEOUT },
    )
    .toBe(true);
  return found as RecordedSession;
}

test(
  "a cron tick fires quartz-scheduler, found by chain id",
  { tag: ["@engine", "@sessions", "@tier1"] },
  async ({ sessions }) => {
    covers("quartz-scheduler");

    const corpus = readBrokersCorpusState();
    const chain = brokerChain(corpus, "scheduler-basic");

    const session = await nextFiring(sessions, chain.id, 2);
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["Scheduler", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  },
);
