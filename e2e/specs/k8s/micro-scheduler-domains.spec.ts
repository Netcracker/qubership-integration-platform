/**
 * Scheduler chains on two micro domains each keep their schedule (#1112).
 *
 * The development build gives every micro domain one scheduler database, where DBaaS gives each
 * domain its own, and every domain used to join one Quartz cluster in it. The pod of one domain
 * then acquired the other domain's trigger and fired it into an endpoint with no consumer. Before
 * the fix, one of the two chains here recorded 5 of its 10 firings in the window. Each chain fires
 * every 3 seconds, and the case counts the sessions each one records in a window opened once both
 * domains fire. A scheduler session carries no correlation header, so the case reads each chain's
 * own sessions (rule 19) and counts the ids the window added.
 */
import type { Catalog } from "../../support/catalog.js";
import { test, expect } from "../../support/fixtures.js";
import { MICRO_READY_TIMEOUT, SEED_LOGGING } from "../../support/corpus.js";
import { sleep } from "../../support/poll.js";
import { tokenized } from "../../support/run.js";
import { SESSION_TIMEOUT } from "../../support/sessions.js";

/** Two micro pod starts, measured at 60 to 75 s each, the window, and the deletes. */
const CASE_TIMEOUT = 600_000;

const WINDOW_MS = 30_000;

/** Ten firings of a 3-second schedule fall in the window. */
const MIN_FIRINGS = 8;

/** Enough for every session a chain records during the case. */
const SESSION_PAGE = 1_000;

async function createSchedulerChain(catalog: Catalog, name: string, parentId: string, run: string): Promise<string> {
  const chain = await catalog.createChain(name, parentId);
  const trigger = await catalog.createElement(chain.id, "quartz-scheduler");
  await catalog.patchElementProperties(chain.id, trigger.id, { cron: "0/3 * * * * ?", deleteJob: false });
  const header = await catalog.createElement(chain.id, "header-modification");
  await catalog.patchElementProperties(chain.id, header.id, {
    headerModificationToAdd: { "e2e-quartz-domains": run },
    headerModificationToRemove: [],
  });
  await catalog.connectElements(chain.id, trigger.id, header.id);
  await catalog.saveLoggingProperties(chain.id, SEED_LOGGING);
  return chain.id;
}

test("scheduler chains on two micro domains each fire on schedule", { tag: ["@engine", "@sessions", "@tier2"] }, async ({ catalog, folder, run, sessions }) => {
  test.setTimeout(CASE_TIMEOUT);
  const domains = [tokenized(run, "qa"), tokenized(run, "qb")];
  const chainIds = await Promise.all(
    domains.map((domain) => createSchedulerChain(catalog, tokenized(run, `quartz-${domain.slice(-2)}`), folder.id, run)),
  );

  const deployed: string[] = [];
  try {
    for (const [index, domain] of domains.entries()) {
      await catalog.deployChains([chainIds[index]], [domain]);
      deployed.push(domain);
      await expect
        .poll(async () => (await sessions.idsOf(chainIds[index], SESSION_PAGE)).size, {
          timeout: MICRO_READY_TIMEOUT + SESSION_TIMEOUT,
          message: `the scheduler chain on micro domain ${domain} never fired`,
        })
        .toBeGreaterThan(0);
    }

    const before = await Promise.all(chainIds.map((chainId) => sessions.idsOf(chainId, SESSION_PAGE)));
    await sleep(WINDOW_MS);
    const after = await Promise.all(chainIds.map((chainId) => sessions.idsOf(chainId, SESSION_PAGE)));
    const fired = after.map((ids, index) => [...ids].filter((id) => !before[index].has(id)).length);
    for (const [index, domain] of domains.entries()) {
      expect(fired[index], `sessions on ${domain} in ${WINDOW_MS} ms of a 3-second schedule`).toBeGreaterThanOrEqual(MIN_FIRINGS);
    }
  } finally {
    for (const domain of deployed) {
      await catalog.deleteCustomResource(domain).catch((cause: unknown) => {
        console.error(`[cleanup] micro domain ${domain} was not deleted; the sweep retries it: ${String(cause)}`);
      });
    }
  }
});
