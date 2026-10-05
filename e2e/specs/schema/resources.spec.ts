/**
 * The pure halves of the process envelope: the parsing and the bands.
 *
 * Every number the envelope check reports comes out of text — `docker stats` rows and a `jcmd`
 * report — and a parser that quietly answers zero turns a band check into a check that can never
 * fail. So the shapes are pinned here, against captured output, where no stack is needed.
 *
 * The `MiB`/`MB` distinction is the reason `parseSize` takes both spellings: `docker stats` reports
 * binary units and `jcmd` reports decimal ones, and reading one as the other is a 5% error that
 * compounds straight into a band.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";
import {
  BANDS,
  foldSamples,
  formatPeaks,
  mergePeaks,
  metaspaceOutsideBand,
  outsideBand,
  parseMemUsage,
  parseMetaspace,
  parsePercent,
  parseSize,
  readingOf,
  readRunResources,
  writeRunResources,
  type StatsRow,
} from "../../env/resources.js";
import type { ProcessEnvelope, ResourcePeak } from "../../env/index.js";

/** Captured from `docker stats --no-stream --format "{{json .}}"` against this stack. */
const STATS: StatsRow[] = [
  { Name: "qip-engine", MemUsage: "983.1MiB / 15.62GiB", CPUPerc: "5.56%" },
  { Name: "qip-runtime-catalog", MemUsage: "644.5MiB / 15.62GiB", CPUPerc: "0.74%" },
  { Name: "qip-sessions-management", MemUsage: "404.3MiB / 15.62GiB", CPUPerc: "0.27%" },
  { Name: "qip-testing-service", MemUsage: "36.62MiB / 15.62GiB", CPUPerc: "0.01%" },
  // Not a role the suite watches. It has to be dropped rather than reported under a made-up name.
  { Name: "postgreSQL", MemUsage: "120MiB / 15.62GiB", CPUPerc: "1.00%" },
];

/** Captured from `docker exec qip-engine jcmd 1 VM.metaspace basic`. */
const METASPACE = `
Usage:
  Non-class:    163.25 MB used.
      Class:     23.17 MB used.
       Both:    186.48 MB used.

Virtual space:
  Non-class space:      192.00 MB reserved,     164.31 MB ( 86%) committed,  3 nodes.
      Class space:      192.00 MB reserved,      24.31 MB ( 13%) committed,  1 nodes.
             Both:      384.00 MB reserved,     188.62 MB ( 49%) committed.

MaxMetaspaceSize: 224.00 MB
CompressedClassSpaceSize: 192.00 MB
`;

test("sizes parse in both the binary and the decimal spelling", { tag: ["@infra", "@tier1"] }, () => {
  expect(parseSize("983.1MiB")).toBeCloseTo(983.1 * 1024 ** 2, 0);
  expect(parseSize("186.48 MB")).toBeCloseTo(186.48 * 1000 ** 2, 0);
  expect(parseSize("15.62GiB")).toBeCloseTo(15.62 * 1024 ** 3, 0);
  // A binary and a decimal megabyte are not the same number, which is the whole reason both spellings
  // are read rather than one being assumed.
  expect(parseSize("100MiB")).toBeGreaterThan(parseSize("100MB"));
  expect(parseSize("--")).toBe(0);
  expect(parseSize("12 parsecs")).toBe(0);
});

test("a memory usage reads its used half and a percentage its number", { tag: ["@infra", "@tier1"] }, () => {
  expect(parseMemUsage("983.1MiB / 15.62GiB")).toBeCloseTo(983.1 * 1024 ** 2, 0);
  expect(parsePercent("5.56%")).toBe(5.56);
  // Above one core, which is how `docker stats` reports a service using four of them.
  expect(parsePercent("412.30%")).toBe(412.3);
  expect(parsePercent("--")).toBe(0);
});

test("a stats reading keeps the watched containers and drops the rest", { tag: ["@infra", "@tier1"] }, () => {
  const reading = readingOf(STATS);
  expect(reading.map((each) => each.role)).toEqual([
    "engine",
    "runtime-catalog",
    "sessions-management",
    "testing-service",
  ]);
  expect(reading.every((each) => each.memoryBytes > 0)).toBe(true);
});

test("peaks keep the maximum of each number independently", { tag: ["@infra", "@tier1"] }, () => {
  const first: ResourcePeak[] = [{ role: "engine", memoryBytes: 100, cpuPercent: 400, samples: 1 }];
  const second: ResourcePeak[] = [{ role: "engine", memoryBytes: 900, cpuPercent: 10, samples: 1 }];

  const merged = mergePeaks(first, second);
  // The memory peak and the CPU peak of one service land in different samples, so pairing them
  // would report whichever number happened to share a tick with the other.
  expect(merged).toEqual([{ role: "engine", memoryBytes: 900, cpuPercent: 400, samples: 2 }]);

  // A role the peaks do not carry yet is added rather than dropped.
  expect(
    mergePeaks(merged, [{ role: "testing-service", memoryBytes: 5, cpuPercent: 1, samples: 1 }]).map(
      (each) => each.role,
    ),
  ).toEqual(["engine", "testing-service"]);
});

test("the metaspace report reads what is committed rather than what is reserved", { tag: ["@infra", "@tier1"] }, () => {
  const metaspace = parseMetaspace(METASPACE);
  expect(metaspace).not.toBeNull();
  // 186.48 MB used, not the 188.62 MB committed or the 384.00 MB reserved that the same report
  // carries under a second `Both:` line.
  expect(metaspace?.usedBytes).toBeCloseTo(186.48 * 1000 ** 2, 0);
  expect(metaspace?.maxBytes).toBeCloseTo(224 * 1000 ** 2, 0);
  // The Go service answers nothing at all, which is not a Metaspace of zero.
  expect(parseMetaspace("jcmd: not found")).toBeNull();
});

test("a band fails on a service that left it and passes on one that merely moved", { tag: ["@infra", "@tier1"] }, () => {
  const inside: ResourcePeak[] = [
    { role: "engine", memoryBytes: BANDS.engine.memoryBytes - 1, cpuPercent: BANDS.engine.cpuPercent - 1, samples: 9 },
  ];
  expect(outsideBand(inside)).toEqual([]);

  const over = outsideBand([
    { role: "engine", memoryBytes: BANDS.engine.memoryBytes + 1, cpuPercent: 1, samples: 9 },
    { role: "runtime-catalog", memoryBytes: 1, cpuPercent: BANDS["runtime-catalog"].cpuPercent + 1, samples: 9 },
  ]);
  expect(over).toHaveLength(2);
  expect(over[0]).toContain("engine peaked at");
  expect(over[1]).toContain("% CPU");
});

test("the metaspace band is a share of the ceiling and ignores a service with no JVM", { tag: ["@infra", "@tier1"] }, () => {
  const envelope = (role: ProcessEnvelope["role"], used: number, max: number): ProcessEnvelope => ({
    role,
    startedAt: "2026-09-09T00:00:00Z",
    restarts: 0,
    metaspace: { usedBytes: used, maxBytes: max },
  });

  expect(metaspaceOutsideBand([envelope("engine", 80, 100)])).toEqual([]);
  expect(metaspaceOutsideBand([envelope("engine", 99, 100)])[0]).toContain("engine committed 99%");
  expect(
    metaspaceOutsideBand([
      { role: "testing-service", startedAt: "", restarts: 0, metaspace: null },
    ]),
  ).toEqual([]);
});

test("the peak report prints both numbers per service and a total", { tag: ["@infra", "@tier1"] }, () => {
  const printed = formatPeaks(readingOf(STATS));
  expect(printed).toContain("[peak] engine:");
  expect(printed).toContain("% CPU");
  expect(printed).toContain("[peak] total:");
});

test("the fold reads the appended rows and survives a half-written last line", { tag: ["@infra", "@tier1"] }, () => {
  const lines = [
    JSON.stringify({ Name: "qip-engine", MemUsage: "100MiB / 15.62GiB", CPUPerc: "10.00%" }),
    JSON.stringify({ Name: "qip-engine", MemUsage: "900MiB / 15.62GiB", CPUPerc: "1.00%" }),
    "",
    // The loop appends while the reader reads, so the last line is routinely incomplete. Skipping
    // it costs one sample out of hundreds; throwing on it would make the check flaky.
    '{"Name":"qip-engine","MemUs',
  ];
  expect(foldSamples(lines)).toEqual([
    { role: "engine", memoryBytes: 900 * 1024 ** 2, cpuPercent: 10, samples: 2 },
  ]);
  expect(foldSamples([])).toEqual([]);
});

test("the sampler's record is replaced rather than truncated in place", { tag: ["@infra", "@tier1"] }, () => {
  const file = path.join(
    fs.mkdtempSync(path.join(os.tmpdir(), "e2e-resources-")),
    ".e2e-resources.json",
  );
  const record = (pid: number) => ({ startedAt: "2026-09-09T10:00:00.000Z", run: EXAMPLE_RUN_TOKEN, baseline: [], samplerPid: pid });

  writeRunResources(record(4242), file);
  const inode = fs.statSync(file).ino;
  writeRunResources(record(4343), file);

  // A rename replaces the inode and a write in place keeps it, which is the whole difference: this
  // file holds the only handle on a sampler that is already running, and a reader that meets a
  // truncation mid-write answers `null` — after which no run can stop that sampler and the next one
  // starts a second against the same samples file.
  expect(fs.statSync(file).ino).not.toBe(inode);
  expect(readRunResources(file)?.samplerPid).toBe(4343);
  // And the staging file is not residue of its own: a `.tmp` left beside the record is a second
  // handle nothing reads and nothing clears.
  expect(fs.readdirSync(path.dirname(file))).toEqual([path.basename(file)]);
});
