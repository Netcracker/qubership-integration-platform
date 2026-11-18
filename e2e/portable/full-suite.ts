import JSZip from "jszip";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import type { PlaywrightTestConfig } from "@playwright/test";
import type { SuiteExtension, RequestOptions } from "../env/suite-extension.js";
import type { ServiceRole } from "../env/index.js";
import type { Target } from "../env/target.js";

export type { SuiteExtension, RequestOptions };
export type { Env, EngineKind, ServiceRole } from "../env/index.js";
export type { TargetSetup, SuiteProject, StackObserver } from "../env/target-setup.js";

export interface FullSuiteOptions {
  target: Target;
  workspaceDir?: string;
  sourceRoot?: string;
  adapter?: string;
  services?: Partial<Record<ServiceRole, string>>;
  proxyUrl?: string;
  request?: RequestOptions;
  serviceRequests?: Partial<Record<ServiceRole, RequestOptions>>;
  kubernetes?: { namespace?: string; release?: string };
  config?: Omit<PlaywrightTestConfig, "projects" | "testDir" | "globalSetup" | "globalTeardown">;
  projects?: PlaywrightTestConfig["projects"];
}

interface Snapshot {
  version: string;
  digest: string;
  revision: string;
  files: string[];
  links: Record<string, string>;
}

const require = createRequire(import.meta.url);
const PACKAGE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

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

function linkDependencies(root: string): void {
  const manifest = JSON.parse(fs.readFileSync(path.join(PACKAGE, "package.json"), "utf8"));
  const names = new Set([...Object.keys(manifest.dependencies ?? {}), ...Object.keys(manifest.peerDependencies ?? {})]);
  const directory = path.join(root, "e2e/node_modules");
  fs.mkdirSync(directory, { recursive: true });
  for (const name of names) {
    const destination = path.join(directory, name);
    if (fs.existsSync(destination)) continue;
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.symlinkSync(packageRoot(name), destination, process.platform === "win32" ? "junction" : "dir");
  }
}

function snapshotMarker(root: string): string {
  return path.join(root, ".qip-e2e-snapshot.json");
}

function matchesSnapshot(root: string, snapshot: Snapshot): boolean {
  const marker = snapshotMarker(root);
  if (!fs.existsSync(marker)) return false;
  const previous = JSON.parse(fs.readFileSync(marker, "utf8")) as Snapshot;
  if (previous.digest !== snapshot.digest) {
    throw new Error(`The suite workspace ${root} belongs to another snapshot. Choose another workspaceDir.`);
  }
  return true;
}

async function extractSnapshot(root: string, snapshot: Snapshot): Promise<void> {
  const archive = await JSZip.loadAsync(fs.readFileSync(path.join(PACKAGE, "snapshot.zip")));
  for (const entry of Object.values(archive.files)) {
    if (entry.dir) continue;
    const destination = path.join(root, entry.name);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, await entry.async("nodebuffer"));
    if (process.platform !== "win32" && typeof entry.unixPermissions === "number") fs.chmodSync(destination, entry.unixPermissions & 0o777);
    fs.utimesSync(destination, entry.date, entry.date);
  }
  for (const [relative, target] of Object.entries(snapshot.links)) {
    const destination = path.join(root, relative);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    if (process.platform === "win32") fs.writeFileSync(destination, target);
    else fs.symlinkSync(target, destination);
  }
  const git = (args: string[]) => execFileSync("git", args, { cwd: root, stdio: "pipe" });
  git(["init", "--quiet", "--initial-branch=qip-e2e-snapshot"]);
  git(["config", "core.autocrlf", "false"]);
  git(["add", "--force", "--all"]);
  const tree = git(["write-tree"]).toString().trim();
  const commit = git(["-c", "user.name=QIP source snapshot", "-c", "user.email=qip-snapshot@example.invalid", "commit-tree", tree, "-m", `Source snapshot ${snapshot.revision}`]).toString().trim();
  git(["update-ref", "HEAD", commit]);
  fs.writeFileSync(snapshotMarker(root), `${JSON.stringify(snapshot)}\n`);
  fs.appendFileSync(path.join(root, ".git/info/exclude"), "\n.qip-e2e-snapshot.json\n");
  linkDependencies(root);
}

export async function prepareSuite(options: Pick<FullSuiteOptions, "workspaceDir" | "sourceRoot"> = {}): Promise<string> {
  if (options.sourceRoot) return path.resolve(options.sourceRoot);
  const snapshot: Snapshot = JSON.parse(fs.readFileSync(path.join(PACKAGE, "snapshot.json"), "utf8"));
  const root = path.resolve(options.workspaceDir ?? path.join(process.cwd(), ".qip-e2e", `${snapshot.version}-${snapshot.digest.slice(0, 12)}`));
  if (matchesSnapshot(root, snapshot)) return root;
  try {
    fs.rmdirSync(root);
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code !== "ENOENT") {
      if (matchesSnapshot(root, snapshot)) return root;
      if (code === "ENOTEMPTY" || code === "EEXIST") {
        throw new Error(`The suite workspace ${root} must be empty on its first use.`);
      }
      throw error;
    }
  }

  fs.mkdirSync(path.dirname(root), { recursive: true });
  // Publish the complete tree at once. Concurrent callers can reuse the winner's workspace.
  const staging = fs.mkdtempSync(`${root}.preparing-`);
  try {
    await extractSnapshot(staging, snapshot);
    try {
      fs.renameSync(staging, root);
    } catch (error) {
      if (!matchesSnapshot(root, snapshot)) throw error;
    }
  } finally {
    fs.rmSync(staging, { recursive: true, force: true });
  }
  return root;
}

export async function createSuiteConfig(options: FullSuiteOptions): Promise<PlaywrightTestConfig> {
  const root = await prepareSuite(options);
  process.env.QIP_E2E_WORKSPACE = root;
  process.env.CIP_TARGET = options.target;
  if (options.kubernetes?.namespace) process.env.QIP_E2E_NAMESPACE = options.kubernetes.namespace;
  if (options.kubernetes?.release) process.env.QIP_E2E_RELEASE = options.kubernetes.release;
  const variables: Record<ServiceRole, string> = {
    "runtime-catalog": "CIP_CATALOG_URL", engine: "CIP_ENGINE_URL",
    "sessions-management": "CIP_SESSIONS_URL", "testing-service": "CIP_TESTING_SERVICE_URL",
  };
  for (const [role, url] of Object.entries(options.services ?? {})) process.env[variables[role as ServiceRole]] = url;
  if (options.proxyUrl) process.env.CIP_PROXY_URL = options.proxyUrl;
  process.env.QIP_E2E_API_OPTIONS = JSON.stringify({ common: options.request, services: options.serviceRequests });
  const extension: SuiteExtension = options.adapter
    ? (await import(pathToFileURL(path.resolve(options.adapter)).href)).default
    : {};
  const support = await import(pathToFileURL(path.join(root, "e2e/env/suite-extension.ts")).href);
  support.configureSuite(extension);
  const { default: original } = await import(pathToFileURL(path.join(root, "e2e/playwright.config.ts")).href);
  const { version, revision, digest } = JSON.parse(fs.readFileSync(path.join(PACKAGE, "snapshot.json"), "utf8")) as Snapshot;
  const specs = path.join(root, "e2e/specs");
  const reporters = original.reporter.map((value: [string, Record<string, unknown>?]) => {
    if (value[0] === "json") return ["json", { ...value[1], outputFile: path.join(root, "e2e/test-results/report.json") }];
    if (value[0] === "html") return ["html", { ...value[1], outputFolder: path.join(root, "e2e/playwright-report") }];
    if (value[0] === "blob") return ["blob", { ...value[1], outputDir: path.join(root, "e2e/blob-report") }];
    return value;
  });
  const project = (value: NonNullable<PlaywrightTestConfig["projects"]>[number]) => ({
    ...value, testDir: path.resolve(root, "e2e", value.testDir ?? "specs"),
  });
  return {
    ...original,
    ...options.config,
    testDir: specs,
    outputDir: options.config?.outputDir ?? path.join(root, "e2e/test-results"),
    reporter: options.config?.reporter ?? reporters,
    globalSetup: path.join(root, "e2e/env/provision.ts"),
    globalTeardown: path.join(root, "e2e/support/fixtures.ts"),
    metadata: { ...original.metadata, ...options.config?.metadata, qipSource: { version, revision, digest, source: options.sourceRoot ? "checkout" : "package" } },
    use: { ...original.use, ...options.config?.use },
    projects: [...original.projects.map(project), ...(options.projects ?? []).map((value) => ({ ...value, testDir: path.resolve(value.testDir ?? "tests") }))],
  };
}

export async function loadSuiteFixtures(): Promise<typeof import("../support/fixtures.js")> {
  const root = process.env.QIP_E2E_WORKSPACE;
  if (!root) throw new Error("Load a configuration created by createSuiteConfig before loading the full suite fixtures.");
  return await import(pathToFileURL(path.join(root, "e2e/support/fixtures.ts")).href);
}
