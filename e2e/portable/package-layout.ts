import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import JSZip from "jszip";

const require = createRequire(import.meta.url);
const PACKAGE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

interface PackageBuild {
  version: string;
  revision: string;
  digest: string;
}

export function packageBuild(): PackageBuild {
  return JSON.parse(fs.readFileSync(path.join(PACKAGE, "suite.json"), "utf8")) as PackageBuild;
}

function packageRoot(name: string): string {
  for (const directory of require.resolve.paths(name) ?? []) {
    const candidate = path.join(directory, name);
    const manifest = path.join(candidate, "package.json");
    if (fs.existsSync(manifest) && JSON.parse(fs.readFileSync(manifest, "utf8")).name === name) {
      return fs.realpathSync(candidate);
    }
  }
  throw new Error(`Cannot locate the installed package ${name}.`);
}

function packageDependencies() {
  const manifest = JSON.parse(fs.readFileSync(path.join(PACKAGE, "package.json"), "utf8"));
  const names = new Set([...Object.keys(manifest.dependencies ?? {}), ...Object.keys(manifest.peerDependencies ?? {})]);
  return [...names].map((name) => [name, packageRoot(name)] as const);
}

function linkDependencies(root: string, dependencies: ReturnType<typeof packageDependencies>): void {
  const directory = path.join(root, "e2e/node_modules");
  for (const [name, target] of dependencies) {
    const destination = path.join(directory, name);
    const parent = path.dirname(destination);
    const existing = fs.lstatSync(destination, { throwIfNoEntry: false });
    if (existing) {
      if (fs.existsSync(destination) && fs.realpathSync(destination) === target) continue;
      if (!existing.isSymbolicLink()) throw new Error(`Expected a dependency link at ${destination}.`);
      fs.rmSync(destination, { force: true });
    }
    fs.mkdirSync(parent, { recursive: true });
    const link = process.platform === "win32" ? target : path.relative(parent, target);
    try {
      fs.symlinkSync(link, destination, process.platform === "win32" ? "junction" : "dir");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST" || fs.realpathSync(destination) !== target) throw error;
    }
  }
}

export async function prepareExternalFiles(workspace: string): Promise<string> {
  const { version, digest } = packageBuild();
  const dependencies = packageDependencies();
  const relativePaths = dependencies.map(([name, target]) => [name, path.relative(workspace, target)]);
  const key = createHash("sha256").update(JSON.stringify([digest, relativePaths])).digest("hex");
  const root = path.join(workspace, "packages", `${version}-${key.slice(0, 16)}`);
  const marker = path.join(root, ".complete");
  if (!fs.existsSync(marker)) {
    fs.mkdirSync(path.dirname(root), { recursive: true });
    const staging = fs.mkdtempSync(`${root}-preparing-`);
    const cleanupOptions = { force: true, recursive: true };
    try {
      const archive = await JSZip.loadAsync(fs.readFileSync(path.join(PACKAGE, "suite.zip")));
      for (const entry of Object.values(archive.files)) {
        if (entry.dir) continue;
        const destination = path.join(staging, entry.name);
        fs.mkdirSync(path.dirname(destination), { recursive: true });
        fs.writeFileSync(destination, await entry.async("nodebuffer"));
      }
      linkDependencies(staging, dependencies);
      fs.writeFileSync(path.join(staging, ".complete"), digest);
      try {
        fs.renameSync(staging, root);
      } catch (error) {
        if (!fs.existsSync(marker)) throw error;
      }
    } catch (error) {
      try {
        fs.rmSync(staging, cleanupOptions);
      } catch (cleanupError) {
        throw new AggregateError(
          [error, cleanupError],
          `Could not prepare the external suite at ${root} (${String(error)}). `
          + `Could not remove the temporary directory ${staging} (${String(cleanupError)}).`,
          { cause: error },
        );
      }
      throw error;
    }
    fs.rmSync(staging, cleanupOptions);
  }
  if (fs.readFileSync(marker, "utf8") !== digest) throw new Error(`Invalid external suite cache at ${root}.`);
  // Windows junctions store absolute destinations and must be updated after moving the workspace.
  linkDependencies(root, dependencies);
  return path.join(root, "e2e");
}
