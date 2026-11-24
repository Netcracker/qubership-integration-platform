import { createHash, randomUUID } from "node:crypto";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { devices, type PlaywrightTestConfig } from "@playwright/test";
import { ROLES } from "../env/containers.js";
import { existsSync } from "node:fs";
import { externalRunStateDir, setExternalSettings, type ExternalBrokers, type ExternalSettings } from "../env/external-settings.js";
import type { ServiceRole } from "../env/index.js";
import type { Target } from "../env/target.js";
import type { SuiteExtension } from "../env/suite-extension.js";
import type { ExternalTest } from "../env/external-env.js";
import { ensureRunToken, generateRunToken } from "../support/run-token.js";
import { packageBuild, prepareExternalFiles } from "./package-layout.js";

export type { ApiAuthContext, ApiCredentials, ExternalAuth, UiAuthContext } from "../env/external-auth.js";
export type { ExternalEnv, ExternalInfrastructure } from "../env/external-env.js";
export type { ExternalBrokers, ServiceRole };
export type { SuiteExtension };

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
  target?: Target;
  sourceRoot?: string;
  adapter?: string;
  dedicatedStand?: true;
  kubernetes?: { namespace?: string; release?: string };
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

  const full = options.suites === undefined;
  const suites = [...new Set(options.suites ?? [])];
  if (!full && suites.length === 0) throw new Error("Select at least one external suite.");
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
  const { kafka, rabbitmq, pubsub, sftp } = options.brokers ?? {};
  const pubsubHost = pubsub && httpBase(pubsub.host, "brokers.pubsub.host");
  if (kafka && (!kafka.host?.trim() || !kafka.engine?.trim())) {
    throw new Error("Set brokers.kafka.host and brokers.kafka.engine.");
  }
  if (pubsub?.projectId !== undefined && !/^[a-z0-9][a-z0-9-]*$/i.test(pubsub.projectId)) {
    throw new Error("Set brokers.pubsub.projectId to the Pub/Sub emulator project ID.");
  }
  if (sftp && (!sftp.host?.host?.trim() || !Number.isInteger(sftp.host.port) || sftp.host.port < 1
    || sftp.host.port > 65_535 || !sftp.engine?.trim() || sftp.engine.includes("/")
    || !sftp.username || !sftp.password)) {
    throw new Error("Set brokers.sftp host, engine address, username, and password.");
  }
  if (rabbitmq) {
    const rabbit = rabbitmq;
    const host = URL.parse(rabbit.host);
    if (!host || !["amqp:", "amqps:"].includes(host.protocol) || !host.hostname) {
      throw new Error("Set brokers.rabbitmq.host to the AMQP URL reachable by the runner.");
    }
    if (!rabbit.engine?.trim() || !rabbit.username || !rabbit.password) {
      throw new Error("Set brokers.rabbitmq.engine, username, and password for the deployed trigger.");
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
  const target = options.target ?? "compose";
  const referenceStand = full ? {
    standDir: path.join(workspace, "stands", createHash("sha256").update(JSON.stringify([
      target, ROLES.map((role) => services[role]),
      target === "k8s" ? [
        options.kubernetes?.namespace ?? process.env.QIP_E2E_NAMESPACE ?? "cip-e2e",
        options.kubernetes?.release ?? process.env.QIP_E2E_RELEASE ?? "cip",
      ] : null,
    ])).digest("hex")),
    runToken: process.env.E2E_RUN === undefined ? generateRunToken() : ensureRunToken(),
    dedicatedStand: options.dedicatedStand,
  } : undefined;
  const source = await prepareExternalFiles(full ? path.join(runDir, "workspace") : workspace);
  process.env.QIP_E2E_EXTERNAL_TEST_ROOT = source;
  const auth = options.auth && path.resolve(options.auth);
  const sensitiveArtifacts = options.sensitiveArtifacts === true;
  const sourceRoot = full && options.sourceRoot ? path.resolve(options.sourceRoot) : undefined;
  if (sourceRoot && !existsSync(path.join(sourceRoot, "e2e/playwright.config.ts"))) {
    throw new Error("Set sourceRoot to a platform checkout with e2e/playwright.config.ts.");
  }
  setExternalSettings({
    services, ui, proxy, auth,
    brokers: pubsub && pubsubHost ? { ...options.brokers, pubsub: { ...pubsub, host: pubsubHost } } : options.brokers,
    infrastructure: suites.includes("infrastructure") ? path.resolve(options.infrastructure!.adapter) : undefined,
    deployment: options.deployment,
    routePrefix: (options.routePrefix ?? "routes").replace(/^\/+|\/+$/g, ""),
    run,
    stateDir: referenceStand ? externalRunStateDir(referenceStand.standDir, referenceStand.runToken) : path.join(runDir, "owned"),
    full: referenceStand && { ...referenceStand, sourceRoot },
  });
  if (full) {
    process.env.CIP_TARGET = target;
    process.env.CIP_REPO_ROOT = sourceRoot ?? path.dirname(source);
    process.env.QIP_E2E_WORKSPACE = path.dirname(source);
    if (options.kubernetes?.namespace) process.env.QIP_E2E_NAMESPACE = options.kubernetes.namespace;
    if (options.kubernetes?.release) process.env.QIP_E2E_RELEASE = options.kubernetes.release;
    const extension: SuiteExtension = options.adapter
      ? (await import(pathToFileURL(path.resolve(options.adapter)).href)).default
      : {};
    const support = await import(pathToFileURL(path.join(source, "env/suite-extension.ts")).href);
    support.configureSuite({
      ...extension,
      lifecycle: ui || proxy ? {
        startUi: async () => ({ pid: null, built: false }),
        stopUi: async () => {},
        ...extension.lifecycle,
      } : extension.lifecycle,
    });
  }
  const reference = full
    ? (await import(pathToFileURL(path.join(source, "playwright.config.ts")).href)).default as PlaywrightTestConfig
    : undefined;
  const configuredSecrets = rabbitmq || sftp || kafka?.hostSasl || (typeof kafka?.hostTls === "object");
  const protectedRun = Boolean(auth || (full && configuredSecrets)) && !sensitiveArtifacts;
  const safeArtifacts = protectedRun ? { trace: "off", screenshot: "off", video: "off" } as const : {};
  const teardowns = new Set(reference?.projects?.map(({ teardown }) => teardown));
  const outputFile = path.join(runDir, "report.json");
  const use = { ...reference?.use, ...options.config?.use };
  return {
    ...reference,
    ...options.config,
    testDir: path.join(source, "specs"),
    outputDir: path.join(runDir, "results"),
    preserveOutput: protectedRun ? "never" : "always",
    metadata: { ...options.config?.metadata, ...reference?.metadata, qipSource: packageBuild(), qipExternal: { run, suites, full } },
    retries: 0,
    workers: options.config?.workers ?? 4,
    timeout: options.config?.timeout ?? 120_000,
    reporter: protectedRun
      ? [[path.join(source, "portable/external-reporter.ts"), { outputFile, suiteDir: full ? path.join(source, "specs") : undefined }]]
      : options.config?.reporter ?? [["list"], ["json", { outputFile }]],
    use: { ...use, ...safeArtifacts },
    projects: [
      ...(reference?.projects ?? []).map((project) => ({
        ...project,
        testDir: path.resolve(source, project.testDir ?? "specs"),
        metadata: {
          ...project.metadata,
          qipAuthPhase: teardowns.has(project.name) ? "cleanup" : project.metadata?.group === "setup" ? "setup" : "test",
        },
        use: { ...project.use, ...safeArtifacts },
      })),
      ...(full ? ["health" as const] : suites).map((name) => ({
        name,
        testMatch: groups[name].files,
        ...(name === "health" ? { metadata: { stack: false } } : {}),
        fullyParallel: name !== "infrastructure",
        ...(name === "infrastructure" ? { workers: 1 } : {}),
        ...(name === "testing-service" && !proxy ? { grepInvert: /@infra/ } : {}),
        use: {
          baseURL: services["runtime-catalog"] && `${services["runtime-catalog"]}/`,
          ...(name === "ui" ? { ...devices["Desktop Chrome"], baseURL: ui, viewport: { width: 1600, height: 900 } } : {}),
        },
      })),
      ...(options.projects ?? []).map((value) => ({
        ...value, testDir: path.resolve(value.testDir ?? "tests"), use: { ...value.use, ...safeArtifacts },
      })),
    ],
    ...(full ? {
      globalSetup: path.join(source, "env/provision.ts"),
      globalTeardown: path.join(source, "support/fixtures.ts"),
    } : {}),
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
