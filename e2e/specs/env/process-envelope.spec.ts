/**
 * Whether the platform survived being tested, which no other spec here asks.
 *
 * Every other spec asserts that correct input produced correct output. None of them notices a
 * service that died and came back, or one that finished the run a hundred megabytes of Metaspace
 * from its ceiling — and both Metaspace defects this product has had were found with `jcmd` rather
 * than through any API. A catalog killed by an import surfaces today as dozens of unrelated red
 * specs across four files; this turns it into one named failure that says which service and when.
 *
 * `process-snapshot` records the readings after api, runtime, and ui, before `env` restarts a
 * service. This teardown project checks that saved state after the dependent projects finish.
 *
 * Nothing here names `docker` or `jcmd`. The reading comes through `Env.processEnvelope`, because
 * "did the services survive the run" is exactly the question that has to be answerable against a
 * cluster too, and a cluster has neither command.
 */
import { test, expect } from "../../support/fixtures.js";
import { ROLES } from "../../env/containers.js";
import {
  BANDS,
  formatPeaks,
  metaspaceOutsideBand,
  outsideBand,
} from "../../env/resources.js";
import { capturedValue, readProcessSnapshot } from "../../env/process-snapshot.js";
import { METASPACE_ATTACHMENT, PEAKS_ATTACHMENT } from "../../support/run-summary.mjs";
import type { ProcessEnvelope } from "../../env/index.js";

/** `role startedAt restarts`, which is the whole of the identity and reads as a diff. */
function identity(envelope: ProcessEnvelope): string {
  return `${envelope.role} started ${envelope.startedAt}, ${envelope.restarts} restarts`;
}

test.describe("process envelope", { tag: ["@infra"] }, () => {
  test("every service is still the process the run started", async ({ run }) => {
    const snapshot = readProcessSnapshot(run);
    const baseline = snapshot.baseline;
    expect(
      baseline.map((each) => each.role).sort(),
      "globalSetup records the envelope of every service before any project runs; without it there " +
        "is nothing to compare the end of the run against",
    ).toEqual([...ROLES].sort());

    // Both halves in one assertion: a container that crashed and was restarted in place keeps its
    // name and its image and changes exactly these two numbers.
    expect(
      capturedValue(snapshot.envelopes).map(identity),
      "service identities changed between globalSetup and the pre-restart snapshot",
    ).toEqual(baseline.map(identity));
  });

  test("no service ended the run against its Metaspace ceiling", async ({ run }, testInfo) => {
    const reported = capturedValue(readProcessSnapshot(run).envelopes).filter((each) => each.metaspace !== null);
    // For the CI step summary, which reads the report rather than the console.
    const readings = reported.map((each) => ({ role: each.role, ...each.metaspace, band: BANDS[each.role].metaspaceShare }));
    await testInfo.attach(METASPACE_ATTACHMENT, { body: JSON.stringify(readings), contentType: "application/json" });
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

  test("no service left its stated resource band", async ({ run }, testInfo) => {
    const peaks = capturedValue(readProcessSnapshot(run).peaks);
    const readings = peaks.map((each) => ({ ...each, band: { memoryBytes: BANDS[each.role].memoryBytes, cpuPercent: BANDS[each.role].cpuPercent } }));
    await testInfo.attach(PEAKS_ATTACHMENT, { body: JSON.stringify(readings), contentType: "application/json" });
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
});
