/**
 * The manifest of runs: the only record of what a killed run left on the stack.
 *
 * `sweepableRuns` decides what a later run may collect and `specs/schema/report.spec.ts` pins that
 * decision. What is here is the file underneath it, where the danger is the opposite of an
 * over-eager sweep: a manifest that reads as empty collects nothing at all, and the residue it was
 * naming is then unreachable — the run token is the only handle on it, and nothing else records one.
 *
 * No stack. Every function takes the file as a parameter, so each case runs against a file of its
 * own in a temporary directory.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { forgetRun, readRuns, recordRun, writeRuns, type RunRecord } from "../../support/run.js";

/** A file path in a directory of this case's own, so nothing here can read another case's manifest. */
function manifestFile(): string {
  return path.join(fs.mkdtempSync(path.join(os.tmpdir(), "e2e-runs-")), ".e2e-runs.json");
}

const RECORDS: RunRecord[] = [
  { run: "aa11aa", pid: 4242, startedAt: "2026-09-09T10:00:00.000Z" },
  { run: "bb22bb", pid: 4343, startedAt: "2026-09-09T11:00:00.000Z" },
];

test("a run is recorded before it creates anything and dropped once its sweep is done", { tag: ["@infra", "@tier1"] }, () => {
  const file = manifestFile();
  expect(readRuns(file)).toEqual([]);

  const recorded = recordRun("aa11aa", file);
  expect(recorded.run).toBe("aa11aa");
  expect(recorded.pid).toBe(process.pid);
  recordRun("bb22bb", file);
  expect(readRuns(file).map((each) => each.run)).toEqual(["aa11aa", "bb22bb"]);

  // Recording the same token twice replaces the entry rather than adding one: a re-run under
  // `E2E_RUN` would otherwise leave two entries whose pids disagree about whether the run is alive.
  recordRun("aa11aa", file);
  expect(readRuns(file).map((each) => each.run)).toEqual(["bb22bb", "aa11aa"]);

  forgetRun("bb22bb", file);
  expect(readRuns(file).map((each) => each.run)).toEqual(["aa11aa"]);
});

test("the manifest is replaced rather than truncated in place", { tag: ["@infra", "@tier1"] }, () => {
  const file = manifestFile();
  writeRuns(RECORDS, file);
  const inode = fs.statSync(file).ino;

  forgetRun("bb22bb", file);

  // A rename replaces the inode and a write in place keeps it, and the difference is every cleanup
  // handle this machine holds: a kill during `recordRun` or `forgetRun` that truncates the file
  // leaves `readRuns` answering `[]`, and residue no sweep can then collect.
  expect(fs.statSync(file).ino).not.toBe(inode);
  expect(readRuns(file).map((each) => each.run)).toEqual(["aa11aa"]);
  // The staging file is not residue of its own.
  expect(fs.readdirSync(path.dirname(file))).toEqual([path.basename(file)]);
});

test("a manifest that is not a list of runs reads as no runs rather than failing the run", { tag: ["@infra", "@tier1"] }, () => {
  const file = manifestFile();

  // A truncated manifest is residue of its own. Refusing to parse it would stop every later run
  // rather than the one that was killed, and there is nothing to salvage: the file is the only
  // record of the tokens it named, so a reader cannot recover them from anywhere else.
  fs.writeFileSync(file, '[{ "run": "aa11aa", "pid": 42');
  expect(readRuns(file)).toEqual([]);
  fs.writeFileSync(file, '{ "run": "aa11aa" }');
  expect(readRuns(file)).toEqual([]);
});
