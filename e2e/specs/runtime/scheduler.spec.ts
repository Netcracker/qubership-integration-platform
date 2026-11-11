/**
 * `quartz-scheduler`, against `fixtures/chains/scheduler-basic`.
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
 * in the first place: a manually added row would fail `specs/harness/coverage.spec.ts`'s "no row
 * names a value the schemas no longer declare" check. The gap is recorded here, in prose, rather
 * than invented as a registry entry that check would immediately reject.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { elementNames, failedElements } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { linesOfChain, logWindowStart, withEngineLines } from "../../support/logs.js";

test.describe("quartz scheduler", { tag: ["@engine", "@sessions"] }, () => {
  test("a cron tick fires quartz-scheduler, found by chain id", async ({ env, sessions }) => {
    covers("quartz-scheduler");

    const chain = seedChain(readCorpusState(), "scheduler-basic");
    const since = logWindowStart();
    const before = await sessions.idsOf(chain.id);

    const session = await withEngineLines(env, since, `for chain ${chain.id}`, linesOfChain(chain.id), () => sessions.nextOf(chain.id, 2, before));
    expect(session.chainId).toBe(chain.id);
    expect(elementNames(session)).toEqual(["Scheduler", "Header Modification"]);
    expect(failedElements(session)).toEqual([]);
  });
});
