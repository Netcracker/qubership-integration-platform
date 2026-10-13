/**
 * Whether the platform survived being tested, which no other spec here asks.
 *
 * Every other spec asserts that correct input produced correct output. None of them notices a
 * service that died and came back, or one that finished the run a hundred megabytes of Metaspace
 * from its ceiling — and both Metaspace defects this product has had were found with `jcmd` rather
 * than through any API. A catalog killed by an import surfaces today as dozens of unrelated red
 * specs across four files; this turns it into one named failure that says which service and when.
 *
 * **This file runs first inside the `env` project, and that is load-bearing.** The project's whole
 * purpose is restarting services, so the identity reading has to be taken before any spec here has
 * restarted one. Playwright collects files in sorted order and `process-envelope` sorts before
 * `restart-resilience` and `service-type-roundtrip`. The first case asserts that ordering off the
 * directory listing before it reads anything, so a rename or a new sibling ahead of it fails here,
 * naming the ordering, instead of turning the identity reading into a report about a service that
 * a neighbor restarted on purpose.
 *
 * Nothing here names `docker` or `jcmd`. The reading comes through `Env.processEnvelope`, because
 * "did the services survive the run" is exactly the question that has to be answerable against a
 * cluster too, and a cluster has neither command.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "../../support/fixtures.js";
import { ROLES } from "../../env/containers.js";
import {
  formatPeaks,
  metaspaceOutsideBand,
  outsideBand,
  readRunResources,
} from "../../env/resources.js";
import type { Env, ProcessEnvelope } from "../../env/index.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** This file's own name, asserted against the collection order rather than trusted. */
const THIS_SPEC = path.basename(fileURLToPath(import.meta.url));

/** `role startedAt restarts`, which is the whole of the identity and reads as a diff. */
function identity(envelope: ProcessEnvelope): string {
  return `${envelope.role} started ${envelope.startedAt}, ${envelope.restarts} restarts`;
}

/**
 * One reading of every role, shared by the two cases that need it.
 *
 * An envelope costs a `docker inspect` and a `docker exec jcmd` per role, and the identity and the
 * Metaspace case ask the same question of the same four containers seconds apart. Nothing between
 * them restarts anything, so a second reading would be the same numbers at twice the price.
 */
let reading: Promise<ProcessEnvelope[]> | undefined;

function envelopes(env: Env): Promise<ProcessEnvelope[]> {
  reading ??= (async () => {
    const found: ProcessEnvelope[] = [];
    for (const role of ROLES) found.push(await env.processEnvelope(role));
    return found;
  })();
  return reading;
}

test("every service is still the process the run started", { tag: ["@infra", "@tier1"] }, async ({ env }) => {
  // The ordering the reading below rests on, asserted rather than commented. Playwright collects a
  // project's files in sorted order, so what keeps this file ahead of the two `env` specs that
  // restart a service is its name — and a rename, or a sibling added ahead of it, would change what
  // the assertion at the end of this test means without changing a line of it.
  expect(
    fs.readdirSync(HERE).filter((name) => name.endsWith(".spec.ts")).sort()[0],
    "another env spec now sorts ahead of this one. The env project restarts services on purpose, " +
      "so the identity reading below would be taken after whatever that spec did to them",
  ).toBe(THIS_SPEC);

  const baseline = readRunResources()?.baseline ?? [];
  expect(
    baseline.map((each) => each.role).sort(),
    "globalSetup records the envelope of every service before any project runs; without it there " +
      "is nothing to compare the end of the run against",
  ).toEqual([...ROLES].sort());

  // Both halves in one assertion: a container that crashed and was restarted in place keeps its
  // name and its image and changes exactly these two numbers.
  expect(
    (await envelopes(env)).map(identity),
    "a service that is not the process the run started took the whole run down with it, and every " +
      "red spec above this one is a consequence rather than a finding — unless an `env` spec that " +
      "restarts a service ran before this file, in which case the ordering is the finding",
  ).toEqual(baseline.map(identity));
});

test("no service ended the run against its Metaspace ceiling", { tag: ["@infra", "@tier1"] }, async ({ env }) => {
  const reported = (await envelopes(env)).filter((each) => each.metaspace !== null);
  expect(
    reported.map((each) => each.role).sort(),
    "the three JVM services report Metaspace; the testing service is Go and reports none",
  ).toEqual(["engine", "runtime-catalog", "sessions-management"]);

  for (const each of reported) {
    const { usedBytes, maxBytes } = each.metaspace as { usedBytes: number; maxBytes: number };
    console.log(
      `[metaspace] ${each.role}: ${(usedBytes / 1024 ** 2).toFixed(0)} MiB of ` +
        `${(maxBytes / 1024 ** 2).toFixed(0)} MiB (${((usedBytes / maxBytes) * 100).toFixed(0)}%)`,
    );
  }

  expect(metaspaceOutsideBand(reported), "a JVM this close to its ceiling is one import from OOM").toEqual([]);
});

test("no service left its stated resource band", { tag: ["@infra", "@tier1"] }, async ({ env }) => {
  const peaks = await env.resourcePeaks();
  expect(
    peaks.map((each) => each.role).sort(),
    "the sampler globalSetup started writes a peak per service; an empty reading means the run " +
      "measured nothing, and a cost nobody measured is a cost nobody notices growing",
  ).toEqual([...ROLES].sort());

  // Both numbers per service and the total, printed rather than only asserted: the band catches a
  // service that left it, and the reading is what a person compares against the last release.
  console.log(formatPeaks(peaks));

  // Every peak is sampled from more than one reading, because a peak lasts seconds and a single
  // `docker stats` at the end of a run reports a stack at rest.
  expect(
    peaks.filter((each) => each.samples < 2).map((each) => each.role),
    "a peak taken from one sample is a reading, not a peak",
  ).toEqual([]);

  expect(outsideBand(peaks), "a service that left its band leaked or spun during the run").toEqual([]);
});
