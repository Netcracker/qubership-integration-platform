import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(
  new URL("./fetch-documentation.mjs", import.meta.url),
);

const docs = {
  "docs/00__Overview/readme.md": "# Overview",
  "docs/01__Chains/readme.md": "# Chains",
  "docs/01__Chains/drafts/readme.md": "# Draft",
  "docs/01__Chains/diagram.drawio": "<mxfile/>",
};

// Runs the real script in a temporary working directory that holds a small
// doc tree and a config with the given documentation fields.
function runFetch(t, documentation) {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), "qip-doc-"));
  t.after(() => fs.rmSync(cwd, { recursive: true, force: true }));

  for (const [file, content] of Object.entries(docs)) {
    fs.mkdirSync(path.dirname(path.join(cwd, file)), { recursive: true });
    fs.writeFileSync(path.join(cwd, file), content);
  }
  fs.writeFileSync(
    path.join(cwd, ".documentation-config.json"),
    JSON.stringify({
      documentation: { source: "local", path: "docs", ...documentation },
    }),
  );

  const result = spawnSync(process.execPath, [script], {
    cwd,
    encoding: "utf-8",
  });
  const out = (file) => path.join(cwd, "public/doc", file);
  return { result, out };
}

test("copies everything without exclude", (t) => {
  const { result, out } = runFetch(t, {});

  assert.equal(result.status, 0, result.stderr);
  assert.ok(fs.existsSync(out("01__Chains/drafts/readme.md")));
  assert.ok(fs.existsSync(out("01__Chains/diagram.drawio")));
  assert.deepEqual(JSON.parse(fs.readFileSync(out("paths.json"), "utf-8")), [
    "00__Overview/readme.md",
    "01__Chains/readme.md",
    "01__Chains/drafts/readme.md",
  ]);
});

test("skips files and directories that match exclude", (t) => {
  const { result, out } = runFetch(t, {
    exclude: ["**/drafts", "**/*.drawio"],
  });

  assert.equal(result.status, 0, result.stderr);
  assert.ok(fs.existsSync(out("00__Overview/readme.md")));
  assert.ok(fs.existsSync(out("01__Chains/readme.md")));
  assert.ok(!fs.existsSync(out("01__Chains/drafts")));
  assert.ok(!fs.existsSync(out("01__Chains/diagram.drawio")));
  assert.deepEqual(JSON.parse(fs.readFileSync(out("paths.json"), "utf-8")), [
    "00__Overview/readme.md",
    "01__Chains/readme.md",
  ]);
});

test("fails before fetching when exclude is not an array", (t) => {
  const { result, out } = runFetch(t, { exclude: "**/drafts" });

  assert.equal(result.status, 1);
  assert.match(
    result.stderr,
    /"exclude" must be an array of glob pattern strings/,
  );
  assert.ok(!fs.existsSync(out("")));
});
