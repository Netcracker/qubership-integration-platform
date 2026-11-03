/** The schemas build's inputs, saved fingerprint, and generated outputs. */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { schemasAreStale, schemasBuildState, SCHEMA_INPUTS, SCHEMA_MARKER, SCHEMA_OUTPUTS } from "../../support/schemas-build.js";
import { BUNDLE_INPUTS } from "../../support/ui-server.js";

const made: string[] = [];

function schemasTree(): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-schemas-"));
  made.push(root);
  const dir = path.join(root, "schemas");
  for (const input of SCHEMA_INPUTS) {
    const file = path.join(dir, input === "src/main" ? "src/main/file" : input);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, "original");
    fs.utimesSync(file, 100, 100);
  }
  for (const output of SCHEMA_OUTPUTS) {
    fs.mkdirSync(path.join(dir, output), { recursive: true });
    fs.writeFileSync(path.join(dir, output, "file"), "output");
  }
  const state = schemasBuildState(dir);
  state.record(state.fingerprint());
  return dir;
}

test.describe("schemas-build", () => {
  test.afterEach(() => {
    for (const dir of made.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
  });

  test("the outputs are what the schemas build writes", () => {
    const script = fs.readFileSync(path.join(repoRoot(), "schemas", "src", "main", "scripts", "build.ts"), "utf-8");
    const written = [...script.matchAll(/path\.join\(PROJECT_ROOT, "([^"]+)"\)/g)].map((match) => match[1]);
    expect(written.sort()).toEqual([...SCHEMA_OUTPUTS].sort());
  });

  test("current when the build recorded the same inputs", async () => {
    expect(await schemasAreStale(schemasTree())).toBe(false);
  });

  test("an edit with its old mtime invalidates the build", async () => {
    const dir = schemasTree();
    const file = path.join(dir, "src/main/file");
    fs.writeFileSync(file, "changed");
    fs.utimesSync(file, 100, 100);
    expect(await schemasAreStale(dir)).toBe(true);
  });

  test("stale when one output or the build marker is missing", async () => {
    for (const output of [...SCHEMA_OUTPUTS, SCHEMA_MARKER]) {
      const dir = schemasTree();
      fs.rmSync(path.join(dir, output), { recursive: true });
      expect(await schemasAreStale(dir), output).toBe(true);
    }
  });

  test("stale when an output directory is empty", async () => {
    for (const output of ["assets", "types"]) {
      const dir = schemasTree();
      fs.rmSync(path.join(dir, output, "file"));
      expect(await schemasAreStale(dir), output).toBe(true);
    }
  });

  test("ui-server bundles the UI without building the schemas", () => {
    // `--ignore-scripts` skips only the pre and post scripts, so the docs still come from `build`.
    const { scripts } = JSON.parse(fs.readFileSync(path.join(repoRoot(), "ui", "package.json"), "utf-8")) as {
      scripts: Record<string, string>;
    };
    expect(scripts.prebuild).toBe("npm run build --workspace @netcracker/qip-schemas");
    expect(scripts.build).toMatch(/^npm run fetch-docs && /);
    expect(BUNDLE_INPUTS).toContain("../schemas/src/main");
  });

  test("deleting a schema source invalidates the build", async () => {
    const dir = schemasTree();
    expect(await schemasAreStale(dir)).toBe(false);
    fs.rmSync(path.join(dir, "src/main/file"));
    expect(await schemasAreStale(dir)).toBe(true);
  });

  test("the dependency lockfile invalidates the schemas", async () => {
    const dir = schemasTree();
    expect(await schemasAreStale(dir)).toBe(false);
    fs.writeFileSync(path.join(dir, "../package-lock.json"), "changed");
    expect(await schemasAreStale(dir)).toBe(true);
  });

  test("manifests and compiler configuration invalidate the schemas", async () => {
    for (const input of ["package.json", "tsconfig.json", "../package.json"]) {
      const dir = schemasTree();
      fs.writeFileSync(path.join(dir, input), "changed");
      expect(await schemasAreStale(dir), input).toBe(true);
    }
  });

  test("adding a source invalidates the build, touching one does not", async () => {
    const dir = schemasTree();
    fs.utimesSync(path.join(dir, "src/main/file"), 300, 300);
    expect(await schemasAreStale(dir)).toBe(false);
    fs.writeFileSync(path.join(dir, "src/main/added"), "new source");
    fs.utimesSync(path.join(dir, "src/main/added"), 100, 100);
    expect(await schemasAreStale(dir)).toBe(true);
  });
});
