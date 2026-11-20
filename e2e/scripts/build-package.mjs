import JSZip from "jszip";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { cpSync, mkdirSync, rmSync, readFileSync, writeFileSync, lstatSync } from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const root = fileURLToPath(new URL("../", import.meta.url));
rmSync(new URL("../dist/", import.meta.url), { recursive: true, force: true });
execFileSync(process.execPath, [require.resolve("typescript/bin/tsc"), "-p", "tsconfig.package.json"], {
  cwd: root, stdio: "inherit",
});
mkdirSync(new URL("../dist/registry/", import.meta.url), { recursive: true });
cpSync(new URL("../registry/operations.cache.json", import.meta.url), new URL("../dist/registry/operations.cache.json", import.meta.url));
cpSync(new URL("../../LICENSE", import.meta.url), new URL("../LICENSE", import.meta.url));

const repository = path.resolve(root, "..");
const archive = new JSZip();
const included = [
  "e2e/env", "e2e/fixtures", "e2e/k8s", "e2e/pages", "e2e/portable", "e2e/registry",
  "e2e/specs", "e2e/suites", "e2e/support", "e2e/playwright.config.ts", "e2e/tsconfig.json",
  "schemas/src/test/resources/samples",
  "vscode-extension/src/web/test/golden", "vscode-extension/src/web/test/workspace",
  "LICENSE",
];
const listed = execFileSync("git", ["ls-files", "-z", "--", ...included], {
  cwd: repository, maxBuffer: 16 * 1024 * 1024,
}).toString().split("\0").filter(Boolean);
const files = [...new Set(listed)].sort();
const digest = createHash("sha256");
archive.file("e2e/package.json", '{"type":"module"}\n', { date: new Date("1980-01-01T00:00:00Z") });
for (const relative of files) {
  const source = path.join(repository, relative);
  if (!lstatSync(source, { throwIfNoEntry: false })?.isFile()) {
    throw new Error(`The test bundle requires the file ${relative}.`);
  }
  const data = readFileSync(source);
  archive.file(relative, data, { date: new Date("1980-01-01T00:00:00Z") });
  digest.update(relative).update("\0").update(data).update("\0");
}
writeFileSync(path.join(root, "suite.json"), JSON.stringify({
  version: JSON.parse(readFileSync(path.join(root, "package.json"), "utf8")).version,
  revision: execFileSync("git", ["rev-parse", "HEAD"], { cwd: repository }).toString().trim(),
  digest: digest.digest("hex"),
}, null, 2) + "\n");
writeFileSync(path.join(root, "suite.zip"), await archive.generateAsync({ type: "nodebuffer", compression: "DEFLATE" }));
