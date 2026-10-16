/**
 * The earlier runs the workflow downloads before it writes the summary and the email.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { OUTCOMES_FILE, readHistory } from "../../support/run-history.mjs";

test("the history lists the downloaded runs latest first and passes over one it cannot read", { tag: ["@infra", "@tier2"] }, () => {
  const dir = test.info().outputPath("history");
  const write = (run: string, content: string) => {
    fs.mkdirSync(path.join(dir, run), { recursive: true });
    fs.writeFileSync(path.join(dir, run, OUTCOMES_FILE), content);
  };
  write("10", JSON.stringify({ runId: "10", startedAt: "2026-10-14T02:00:00Z", failed: [], skipped: [] }));
  write("12", JSON.stringify({ runId: "12", startedAt: "2026-10-16T02:00:00Z", failed: [], skipped: [] }));
  write("11", '{"runId": "11", "fail');
  // A run whose artifact had expired leaves its directory empty.
  fs.mkdirSync(path.join(dir, "9"));

  expect(readHistory(dir).map((each) => each.runId)).toEqual(["12", "10"]);
  expect(readHistory(undefined)).toEqual([]);
  expect(readHistory(path.join(dir, "absent"))).toEqual([]);
});
