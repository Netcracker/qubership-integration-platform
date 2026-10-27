/**
 * The `schemas-build` setup's freshness check, and the `ui-server` build that relies on it to have
 * built the schemas.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { schemasAreStale, SCHEMA_OUTPUTS, SCHEMA_SOURCES } from "../../support/schemas-build.js";
import { BUNDLE_INPUTS, UI_BUILD_ARGS } from "../../support/ui-server.js";

/** A `schemas/` tree with one file per source and output directory, each with the given mtime in seconds. */
function schemasTree(mtimes: Record<string, number>): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-schemas-"));
  for (const [relative, seconds] of Object.entries(mtimes)) {
    const file = path.join(dir, relative, "file");
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, "");
    fs.utimesSync(file, seconds, seconds);
  }
  return dir;
}

test.describe("schemas-build", () => {
  test("the outputs are what the schemas build writes", () => {
    const script = fs.readFileSync(path.join(repoRoot(), "schemas", "src", "main", "scripts", "build.ts"), "utf-8");
    const written = [...script.matchAll(/path\.join\(PROJECT_ROOT, "([^"]+)"\)/g)].map((match) => match[1]);
    expect(written.sort()).toEqual([...SCHEMA_OUTPUTS].sort());
    expect(SCHEMA_SOURCES).toEqual(["src/main"]);
  });

  test("current when every output is newer than the sources", async () => {
    expect(await schemasAreStale(schemasTree({ "src/main": 100, assets: 200, types: 200, dist: 200 }))).toBe(false);
  });

  test("stale when one output is older than the sources", async () => {
    for (const output of SCHEMA_OUTPUTS) {
      const mtimes = { "src/main": 300, assets: 400, types: 400, dist: 400, [output]: 200 };
      expect(await schemasAreStale(schemasTree(mtimes)), output).toBe(true);
    }
  });

  test("stale when one output is missing", async () => {
    for (const output of SCHEMA_OUTPUTS) {
      const mtimes: Record<string, number> = { "src/main": 100, assets: 200, types: 200, dist: 200 };
      delete mtimes[output];
      expect(await schemasAreStale(schemasTree(mtimes)), output).toBe(true);
    }
  });

  test("ui-server bundles the UI without building the schemas", () => {
    expect(UI_BUILD_ARGS).toEqual(["run", "build", "--ignore-scripts", "-w", "@netcracker/qip-ui"]);
    // `--ignore-scripts` skips only the pre and post scripts, so the docs still come from `build`.
    const { scripts } = JSON.parse(fs.readFileSync(path.join(repoRoot(), "ui", "package.json"), "utf-8")) as {
      scripts: Record<string, string>;
    };
    expect(scripts.prebuild).toBe("npm run build --workspace @netcracker/qip-schemas");
    expect(scripts.build).toMatch(/^npm run fetch-docs && /);
    // A schema change still rebuilds the bundle.
    expect(BUNDLE_INPUTS).toContain("../schemas/src/main");
  });
});
