import { randomUUID } from "node:crypto";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { devices, type PlaywrightTestConfig } from "@playwright/test";
import { ROLES } from "../env/containers.js";
import { setExternalSettings, type ExternalBrokers, type ExternalSettings } from "../env/external-settings.js";
import type { ServiceRole } from "../env/index.js";
import type { ExternalTest } from "../env/external-env.js";
import { packageBuild, prepareExternalFiles } from "./package-layout.js";

export type { ApiAuthContext, ApiCredentials, ExternalAuth, UiAuthContext } from "../env/external-auth.js";
export type { ExternalEnv, ExternalInfrastructure } from "../env/external-env.js";
export type { ExternalBrokers, ServiceRole };

type Requirements = readonly ServiceRole[];
const runtimeServices = ["runtime-catalog", "engine", "sessions-management"] as const;
const groups = {
  health: { requires: [], files: ["**/external/health.spec.ts"] },
  catalog: { requires: ["runtime-catalog"], files: ["**/api/chains.spec.ts", "**/api/folders.spec.ts"] },
  runtime: { requires: runtimeServices, files: ["**/external/http-echo.spec.ts"] },
  ui: { requires: ["runtime-catalog"], files: ["**/ui/chains-list.spec.ts", "**/ui/chain-properties.spec.ts"] },
  "testing-service": { requires: ["testing-service"], files: ["**/api/testing-service-mocks.spec.ts"] },
  brokers: { requires: runtimeServices, files: ["**/external/brokers.spec.ts"] },
  infrastructure: { requires: runtimeServices, files: ["**/env/external-restart.spec.ts"] },
} satisfies Record<string, { requires: Requirements; files: string[] }>;

export type ExternalSuite = keyof typeof groups;

export interface ExternalSuiteOptions {
  services: Partial<Record<ServiceRole, string | undefined>>;
  suites?: readonly ExternalSuite[];
  deployment?: ExternalSettings["deployment"];
  ui?: string;
  proxy?: string;
  routePrefix?: string;
  brokers?: ExternalBrokers;
  infrastructure?: { adapter: string; dedicatedStand: true };
  auth?: string;
  sensitiveArtifacts?: boolean;
  workspaceDir?: string;
  projects?: PlaywrightTestConfig["projects"];
  config?: Pick<PlaywrightTestConfig, "workers" | "timeout" | "expect" | "reporter" | "use" | "metadata">;
}

function httpBase(value: string | undefined, field: string): string {
  const url = value && URL.parse(value);
  if (!url || !["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error(`Set ${field} to an HTTP or HTTPS base URL without credentials, a query, or a fragment.`);
  }
  return url.href.replace(/\/+$/, "");
}

export async function createExternalSuiteConfig(options: ExternalSuiteOptions): Promise<PlaywrightTestConfig> {
  const services: ExternalSettings["services"] = {};
  for (const [key, value] of Object.entries(options.services)) {
    const role = ROLES.find((role) => key === role);
    if (!role) throw new Error(`Unknown external service "${key}". Use one of ${ROLES.join(", ")}.`);
    const base = httpBase(value, `services["${role}"]`);
    if (Object.values(services).includes(base)) throw new Error(`Set a distinct base URL for services["${role}"].`);
    services[role] = base;
  }

  const suites = [...new Set(options.suites ?? ["health"])] as ExternalSuite[];
  if (suites.length === 0) throw new Error("Select at least one external suite.");
  for (const suite of suites) {
    if (!Object.hasOwn(groups, suite)) throw new Error(`Unknown external suite "${suite}".`);
    for (const role of groups[suite].requires) {
      if (!services[role]) throw new Error(`The "${suite}" suite requires services["${role}"].`);
    }
  }
  const ui = options.ui === undefined ? undefined : httpBase(options.ui, "ui");
  const proxy = options.proxy === undefined ? undefined : httpBase(options.proxy, "proxy");
  if (suites.includes("health") && Object.keys(services).length === 0) {
    throw new Error('The "health" suite requires at least one service URL.');
  }
  if (suites.includes("ui") && !ui) throw new Error('The "ui" suite requires ui.');
  if (ui && new URL(ui).pathname !== "/") throw new Error("Set ui to the origin that serves the application.");
  if (suites.some((suite) => ["runtime", "brokers", "infrastructure"].includes(suite)) && !options.deployment?.domain.trim()) {
    throw new Error("The runtime, brokers, and infrastructure suites require deployment.domain.");
  }
  if (suites.includes("brokers") && !options.brokers?.kafka && !options.brokers?.rabbitmq) {
    throw new Error('The "brokers" suite requires brokers.kafka or brokers.rabbitmq.');
  }
  for (const [broker, endpoints] of Object.entries(options.brokers ?? {})) {
    if (!endpoints?.host?.trim() || !endpoints.engine?.trim()) {
      throw new Error(`Set brokers.${broker}.host and brokers.${broker}.engine.`);
    }
  }
  if (options.brokers?.rabbitmq) {
    const rabbit = options.brokers.rabbitmq;
    const host = URL.parse(rabbit.host);
    if (!host || !["amqp:", "amqps:"].includes(host.protocol) || !host.hostname) {
      throw new Error("Set brokers.rabbitmq.host to the AMQP URL reachable by the runner.");
    }
    if (!rabbit.username || !rabbit.password) {
      throw new Error("Set brokers.rabbitmq.username and brokers.rabbitmq.password for the deployed trigger.");
    }
  }
  if (suites.includes("infrastructure") && (!options.infrastructure?.adapter || options.infrastructure.dedicatedStand !== true)) {
    throw new Error('The "infrastructure" suite requires infrastructure.adapter and infrastructure.dedicatedStand: true.');
  }
  if (suites.includes("infrastructure") && (suites.length > 1 || options.projects?.length)) {
    throw new Error('Run the "infrastructure" suite in its own configuration on a dedicated stand.');
  }

  const workspace = path.resolve(options.workspaceDir ?? ".qip-e2e");
  const run = process.env.QIP_E2E_EXTERNAL_RUN ?? randomUUID();
  process.env.QIP_E2E_EXTERNAL_RUN = run;
  const runDir = path.join(workspace, "runs", run);
  const source = await prepareExternalFiles(workspace);
  process.env.QIP_E2E_EXTERNAL_TEST_ROOT = source;
  const auth = options.auth && path.resolve(options.auth);
  const sensitiveArtifacts = options.sensitiveArtifacts === true;
  setExternalSettings({
    services, ui, proxy, auth, brokers: options.brokers,
    infrastructure: suites.includes("infrastructure") ? path.resolve(options.infrastructure!.adapter) : undefined,
    deployment: options.deployment,
    routePrefix: (options.routePrefix ?? "routes").replace(/^\/+|\/+$/g, ""),
    run, stateDir: path.join(runDir, "owned"),
  });
  const protectedRun = Boolean(auth) && !sensitiveArtifacts;
  const outputFile = path.join(runDir, "report.json");
  const use = options.config?.use;
  return {
    ...options.config,
    testDir: path.join(source, "specs"),
    outputDir: path.join(runDir, "results"),
    preserveOutput: protectedRun ? "never" : "always",
    metadata: { ...options.config?.metadata, qipSource: packageBuild(), qipExternal: { run, suites } },
    retries: 0,
    workers: options.config?.workers ?? 4,
    timeout: options.config?.timeout ?? 120_000,
    reporter: protectedRun
      ? [[path.join(source, "portable/external-reporter.ts"), { outputFile }]]
      : options.config?.reporter ?? [["list"], ["json", { outputFile }]],
    use: protectedRun ? { ...use, trace: "off", screenshot: "off", video: "off" } : use,
    projects: [
      ...suites.map((name) => ({
        name,
        testMatch: groups[name].files,
        fullyParallel: name !== "infrastructure",
        ...(name === "infrastructure" ? { workers: 1 } : {}),
        ...(name === "testing-service" && !proxy ? { grepInvert: /@infra/ } : {}),
        use: {
          baseURL: services["runtime-catalog"] && `${services["runtime-catalog"]}/`,
          ...(name === "ui" ? { ...devices["Desktop Chrome"], baseURL: ui, viewport: { width: 1600, height: 900 } } : {}),
        },
      })),
      ...(options.projects ?? []).map((value) => ({ ...value, testDir: path.resolve(value.testDir ?? "tests") })),
    ],
  };
}

function fixtureModule(file: string): string {
  const source = process.env.QIP_E2E_EXTERNAL_TEST_ROOT;
  if (!source) throw new Error("Load a configuration created by createExternalSuiteConfig before loading its fixtures.");
  return pathToFileURL(path.join(source, "support", file)).href;
}

type ExternalFixtures<T extends { test: unknown }> = Omit<T, "test"> & { test: ExternalTest<T["test"]> };

export async function loadExternalFixtures(): Promise<ExternalFixtures<typeof import("../support/fixtures.js")>> {
  const fixtures = await import(fixtureModule("fixtures.ts")) as typeof import("../support/fixtures.js");
  return { ...fixtures, test: fixtures.externalTest };
}

export async function loadExternalUiFixtures(): Promise<ExternalFixtures<typeof import("../support/page-guard.js")>> {
  return await import(fixtureModule("page-guard.ts"));
}
