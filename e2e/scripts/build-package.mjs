import JSZip from "jszip";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { cpSync, mkdirSync, rmSync, readFileSync, writeFileSync, lstatSync, readlinkSync } from "node:fs";
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
rmSync(path.join(root, "snapshot"), { recursive: true, force: true });
const files = execFileSync("git", ["ls-files", "-z"], { cwd: repository, maxBuffer: 16 * 1024 * 1024 })
  .toString().split("\0").filter(Boolean).sort();
const digest = createHash("sha256");
const links = {};
for (const relative of files) {
  const source = path.join(repository, relative);
  if (lstatSync(source).isSymbolicLink()) {
    links[relative] = readlinkSync(source);
    digest.update(relative).update("\0symlink\0").update(links[relative]).update("\0");
    continue;
  }
  if (!lstatSync(source).isFile()) throw new Error(`The suite snapshot requires the file ${relative}.`);
  const data = readFileSync(source);
  archive.file(relative, data, { date: lstatSync(source).mtime, unixPermissions: lstatSync(source).mode });
  digest.update(relative).update("\0").update(data).update("\0");
}
writeFileSync(path.join(root, "snapshot.json"), JSON.stringify({
  version: JSON.parse(readFileSync(path.join(root, "package.json"), "utf8")).version,
  revision: execFileSync("git", ["rev-parse", "HEAD"], { cwd: repository }).toString().trim(),
  digest: digest.digest("hex"), files, links,
}, null, 2) + "\n");

writeFileSync(path.join(root, "snapshot.zip"), await archive.generateAsync({ type: "nodebuffer", platform: "UNIX", compression: "DEFLATE" }));
