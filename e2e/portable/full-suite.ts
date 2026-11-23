import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { execFileSync } from "node:child_process";
import type { PlaywrightTestConfig } from "@playwright/test";
import type { SuiteExtension, RequestOptions } from "../env/suite-extension.js";
import type { ServiceRole } from "../env/index.js";
import type { Target } from "../env/target.js";
import { setServiceUrls } from "../env/containers.js";
import { packageBuild } from "./package-layout.js";

export type { SuiteExtension, RequestOptions };
export type { Env, EngineKind, ServiceRole } from "../env/index.js";
export type { TargetSetup, SuiteProject, StackObserver } from "../env/target-setup.js";

export interface FullSuiteOptions {
  target: Target;
  sourceRoot: string;
  adapter?: string;
  services?: Partial<Record<ServiceRole, string>>;
  proxyUrl?: string;
  request?: RequestOptions;
  serviceRequests?: Partial<Record<ServiceRole, RequestOptions>>;
  kubernetes?: { namespace?: string; release?: string };
  config?: Omit<PlaywrightTestConfig, "projects" | "testDir" | "globalSetup" | "globalTeardown">;
  projects?: PlaywrightTestConfig["projects"];
}

export async function prepareSuite(options?: { sourceRoot: string }): Promise<string> {
  if (!options?.sourceRoot) {
    throw new Error('Set sourceRoot to a prepared platform checkout, for example sourceRoot: "../qubership-integration-platform".');
  }
  const root = path.resolve(options.sourceRoot);
  if (!fs.existsSync(path.join(root, "e2e/playwright.config.ts"))) {
    throw new Error(`Cannot find e2e/playwright.config.ts under sourceRoot ${root}.`);
  }
  if (!fs.existsSync(path.join(root, "e2e/node_modules/@playwright/test/package.json"))) {
    throw new Error(`Run npm ci in ${path.join(root, "e2e")} before launching the full suite.`);
  }
  return root;
}

function checkoutRevision(root: string): string | undefined {
  try {
    return execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, stdio: ["ignore", "pipe", "ignore"] }).toString().trim();
  } catch {
    return undefined;
  }
}

function checkRunner(root: string): void {
  const entrypoint = process.argv[1] && fs.existsSync(process.argv[1]) ? fs.realpathSync(process.argv[1]) : undefined;
  if (!entrypoint || ![/playwright[\\/]cli\.js$/, /@playwright[\\/]test[\\/]cli\.js$/].some((pattern) => pattern.test(entrypoint))) return;
  const active = createRequire(entrypoint).resolve("playwright/package.json");
  const checkout = createRequire(path.join(root, "e2e/package.json")).resolve("playwright/package.json");
  if (active !== checkout) {
    const cli = path.join(root, "e2e/node_modules/@playwright/test/cli.js");
    throw new Error(`Run the full suite with the checkout's Playwright. Use node "${cli}" test.`);
  }
}

export async function createSuiteConfig(options: FullSuiteOptions): Promise<PlaywrightTestConfig> {
  const root = await prepareSuite(options);
  checkRunner(root);
  delete process.env.QIP_E2E_EXTERNAL;
  process.env.QIP_E2E_WORKSPACE = root;
  process.env.CIP_TARGET = options.target;
  if (options.kubernetes?.namespace) process.env.QIP_E2E_NAMESPACE = options.kubernetes.namespace;
  if (options.kubernetes?.release) process.env.QIP_E2E_RELEASE = options.kubernetes.release;
  setServiceUrls(options.services ?? {});
  if (options.proxyUrl) process.env.CIP_PROXY_URL = options.proxyUrl;
  process.env.QIP_E2E_API_OPTIONS = JSON.stringify({ common: options.request, services: options.serviceRequests });
  const extension: SuiteExtension = options.adapter
    ? (await import(pathToFileURL(path.resolve(options.adapter)).href)).default
    : {};
  const support = await import(pathToFileURL(path.join(root, "e2e/env/suite-extension.ts")).href);
  support.configureSuite(extension);
  const { default: original } = await import(pathToFileURL(path.join(root, "e2e/playwright.config.ts")).href);
  const qipSource = { ...packageBuild(), source: "checkout", checkoutRevision: checkoutRevision(root) };
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
    metadata: { ...original.metadata, ...options.config?.metadata, qipSource },
    use: { ...original.use, ...options.config?.use },
    projects: [...original.projects.map(project), ...(options.projects ?? []).map((value) => ({ ...value, testDir: path.resolve(value.testDir ?? "tests") }))],
  };
}

export async function loadSuiteFixtures(): Promise<typeof import("../support/fixtures.js")> {
  const root = process.env.QIP_E2E_WORKSPACE;
  if (!root) throw new Error("Load a configuration created by createSuiteConfig before loading the full suite fixtures.");
  return await import(pathToFileURL(path.join(root, "e2e/support/fixtures.ts")).href);
}
