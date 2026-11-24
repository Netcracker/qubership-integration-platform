import { mkdir, mkdtemp, readFile, writeFile, rm, readdir, rename, realpath } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import JSZip from "jszip";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { execFile, spawnSync } from "node:child_process";
import { promisify } from "node:util";
import assert from "node:assert/strict";
import { packageServer } from "./package-server.mjs";
import { eachTest } from "../support/run-report.mjs";

const execute = promisify(execFile);
const require = createRequire(import.meta.url);
const root = fileURLToPath(new URL("../", import.meta.url));
const consumer = await mkdtemp(path.join(tmpdir(), "qip-e2e-consumer-"));
const server = await packageServer();
const npm = process.env.npm_execpath;
if (!npm) throw new Error("Run this check with npm run test:package.");
const sourceRoot = path.resolve(root, "..");
const checkoutCli = require.resolve("@playwright/test/cli");
const tarballOption = process.argv.find((arg) => arg.startsWith("--tarball="))?.slice("--tarball=".length);
const environment = { ...process.env, PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: "1", QIP_PACKAGE_TEST_URL: server.url };

async function run(command, args, cwd = consumer, env = environment) {
  try {
    return await execute(command, args, { cwd, env, maxBuffer: 32 * 1024 * 1024 });
  } catch (error) {
    error.message += `\n${error.stdout ?? ""}\n${error.stderr ?? ""}`;
    throw error;
  }
}

try {
  const packed = await run(process.execPath, [
    npm, "pack", ...(tarballOption ? [path.resolve(tarballOption)] : []),
    "--ignore-scripts", "--json", "--pack-destination", consumer,
  ], root);
  const [tarball] = JSON.parse(packed.stdout);
  const installFile = tarballOption ? path.resolve(tarballOption) : path.join(consumer, tarball.filename);
  if (tarballOption) {
    assert.deepEqual(await readFile(path.join(consumer, tarball.filename)), await readFile(installFile));
  }
  assert(tarball.files.some((file) => file.path === "dist/portable/external.d.ts"));
  assert(tarball.files.every((file) => !file.path.includes("node_modules") && !file.path.endsWith(".tgz") && !file.path.includes("snapshot")));
  await writeFile(path.join(consumer, "package.json"), JSON.stringify({ private: true, type: "module" }));
  const playwrightVersion = require("@playwright/test/package.json").version;
  const typescriptVersion = require("typescript/package.json").version;
  await run(process.execPath, [npm, "install", "--ignore-scripts", "--registry=https://registry.npmjs.org", installFile, `@playwright/test@${playwrightVersion}`, `typescript@${typescriptVersion}`, "@types/node@22"]);
  await writeFile(path.join(consumer, "playwright.config.ts"), `
import { defineConfig } from '@playwright/test';
import type { QipOptions } from '@netcracker/qip-e2e';
const endpoint = (role: string) => ({
  url: process.env.QIP_PACKAGE_TEST_URL + '/' + role,
  extraHTTPHeaders: { Authorization: 'Bearer package-test', 'x-service': role },
});
export default defineConfig<{}, QipOptions>({
  testDir: './tests', workers: 2, retries: 0, timeout: 15000,
  use: { qip: { catalog: endpoint('catalog'), engine: endpoint('engine'), sessions: endpoint('sessions'),
    deployment: { domain: 'test-domain' }, deploymentTimeout: 2000 } },
});
`);
  await writeFile(path.join(consumer, "tsconfig.json"), JSON.stringify({ compilerOptions: {
    module: "NodeNext", moduleResolution: "NodeNext", target: "ES2022", strict: true, noEmit: true,
  }, include: ["*.ts", "tests", "application-tests"] }));
  await mkdir(path.join(consumer, "tests"));
  await writeFile(path.join(consumer, "tests", "echo.spec.ts"), `
import { test as base, expect, type EchoChain } from '@netcracker/qip-e2e';
import { registerHttpEchoTests } from '@netcracker/qip-e2e/suites';
const test = base.extend<{ consumerExtension: string }>({
  consumerExtension: ['consumer', { option: true }],
  echo: async ({ echo, consumerExtension }, use) => {
    await use({ ...echo, marker: consumerExtension, markerHeader: 'consumer-marker' } satisfies EchoChain);
  },
});
registerHttpEchoTests(test);
test('a consumer adds its own scenario', async ({ catalog, echo, consumerExtension }) => {
  expect((await catalog.getChain(echo.id)).name).toContain('portable-echo');
  expect(echo.marker).toBe(consumerExtension);
});
`);
  await writeFile(path.join(consumer, "catalog-types.ts"), `
import { test } from '@netcracker/qip-e2e';
import { registerChainTests, registerFolderTests } from '@netcracker/qip-e2e/suites';
registerChainTests(test);
registerFolderTests(test);
`);
  const nodeModules = path.join(consumer, "node_modules");
  await run(process.execPath, [path.join(nodeModules, "typescript/bin/tsc")]);
  const packageRoot = path.join(nodeModules, "@netcracker/qip-e2e");
  const before = await readdir(packageRoot, { recursive: true });
  const suite = await JSZip.loadAsync(await readFile(path.join(packageRoot, "suite.zip")));
  assert(suite.file("e2e/specs/api/health.spec.ts"));
  assert(suite.file("e2e/specs/brokers/broker-restart.spec.ts"));
  assert(suite.file("e2e/specs/env/ai-recovery-lifecycle.spec.ts"));
  assert(suite.file("e2e/fixtures/services/service-roundtrip.zip"));
  assert(Object.keys(suite.files).every((file) => !file.endsWith(".java") && !file.startsWith(".git") && !file.includes("node_modules")));
  const cli = path.join(nodeModules, "@playwright/test/cli.js");
  await verifyExternal(cli, nodeModules);
  await verifyExternalDefault(cli);
  await verifyWorkspaceFailure();
  if (process.argv.includes("--browser")) await verifyBrowserAuth(cli);
  if (!process.argv.includes("--host-smoke")) {
    await verifySharedWorkspace(cli, installFile, playwrightVersion);
    await verifyExternalReference(cli);
    await verifyExternalBrokers(cli);
  }
  const passed = await run(process.execPath, [cli, "test", "--reporter=line"]);
  process.stdout.write(passed.stdout);
  server.assertClean();
  server.corrupt();
  let failed;
  try { await run(process.execPath, [cli, "test", "--grep", "answers on its deployed route", "--reporter=line"]); }
  catch (error) { failed = error; }
  assert(failed, "The packaged check accepted a corrupted echo response");
  assert.match(failed.stdout, /wrong/);
  server.assertClean();
  assert.deepEqual(await readdir(packageRoot, { recursive: true }), before);
  const manifest = JSON.parse(await readFile(path.join(packageRoot, "package.json"), "utf8"));
  assert.equal(manifest.dependencies?.["@playwright/test"], undefined);
  console.log("Tarball consumer passed: types, shared checks, fixture extension, authorization, cleanup, and a rejected corrupted response.");

  await writeFile(path.join(consumer, "full.config.ts"), `
import { createSuiteConfig } from '@netcracker/qip-e2e/full';
export default await createSuiteConfig({
  target: process.env.CIP_TARGET as 'compose' | 'k8s', sourceRoot: ${JSON.stringify(sourceRoot)},
  config: { workers: 4, outputDir: './full-output', reporter: [['json', { outputFile: 'full-results.json' }]] },
});
`);
  environment.CIP_TARGET = "compose";
  await assert.rejects(run(process.execPath, [cli, "test", "--config=full.config.ts", "--list"]), /with the checkout's Playwright/);
  if (process.argv.includes("--host-smoke")) {
    environment.CIP_TARGET = "compose";
    const host = await run(process.execPath, [
      checkoutCli, "test", "--config=full.config.ts", "--project=harness", "--workers=2", "--reporter=line",
      "bootstrap-host.spec.ts", "build-lock.spec.ts", "host-process.spec.ts", "process-group.spec.ts", "process-order.spec.ts",
    ]);
    process.stdout.write(host.stdout);
  } else {
    await verifyFullSuite(checkoutCli, nodeModules);
  }
  await verifyRelocation();
  assert.deepEqual(await readdir(packageRoot, { recursive: true }), before);
} finally {
  await server.close();
  await rm(consumer, { recursive: true, force: true });
}

async function verifyRelocation() {
  const moved = `${consumer}-moved`;
  await rename(consumer, moved);
  try {
    const playwright = path.join(moved, "node_modules/@playwright/test/cli.js");
    const result = await run(process.execPath, [
      playwright, "test", "--config=external.config.ts", "--project=health", "--reporter=line",
    ], moved);
    assert.match(result.stdout, /3 passed/);
    console.log("The installed external suite runs after the consumer directory moves.");
  } finally {
    await rename(moved, consumer);
  }
}

async function verifyExternalDefault(cli) {
  await writeFile(path.join(consumer, "external-default.config.ts"), `
import { createExternalSuiteConfig } from "@netcracker/qip-e2e/external";
const base = process.env.QIP_PACKAGE_TEST_URL;
export default await createExternalSuiteConfig({
  services: { engine: base + "/engine" },
  workspaceDir: "./default-workspace",
  target: process.env.CIP_TARGET === "k8s" ? "k8s" : "compose",
  sourceRoot: process.env.QIP_PACKAGE_SOURCE_ROOT,
  auth: "./default-auth.ts",
});
`);
  await writeFile(path.join(consumer, "default-auth.ts"), `
export default { api: { headers: { engine: { Authorization: "Bearer package-test" } } } };
`);
  const spec = [cli, "test", "--config=external-default.config.ts"];
  const listing = JSON.parse((await run(process.execPath, [...spec, "--list", "--reporter=json"])).stdout);
  assert(listing.config.projects.some(({name}) => name === "runtime"), "The default external config must include runtime.");
  assert(listing.config.projects.some(({name}) => name === "seed"), "The default external config must include seed.");
  assert(listing.config.projects.some(({name}) => name === "global"), "The default external config must include global.");
  const before = server.platformRequests();
  const healthBefore = server.healthRequests();
  await assert.rejects(run(process.execPath, spec), /External project "[^"]+" requires sourceRoot/);
  await assert.rejects(run(process.execPath, [...spec, "--project=API*"]), /requires dedicatedStand/);
  assert.equal(server.platformRequests(), before);
  assert.deepEqual(server.healthRequests(), healthBefore);
  const healthEnvironment = Object.fromEntries(Object.entries(environment).filter(([name]) => name.toLowerCase() !== "path"));
  healthEnvironment.PATH = path.dirname(process.execPath);
  healthEnvironment.E2E_RUN = "kept01";
  const result = await run(process.execPath, [...spec, "--project=Health"], consumer, healthEnvironment);
  assert.match(result.stdout, /External suite passed/);
  assert.equal(server.platformRequests(), before);
  assert.deepEqual(server.healthRequests(), [...healthBefore, "GET /engine/actuator/health"].sort());
  console.log("The default external graph includes the reference projects and can run health on its own.");
}

async function verifyExternalBrokers(cli) {
  const file = "external-brokers.config.ts";
  await writeFile(path.join(consumer, file), `
import { createExternalSuiteConfig } from "@netcracker/qip-e2e/external";
const base = process.env.QIP_PACKAGE_TEST_URL!;
export default await createExternalSuiteConfig({
  services: { "runtime-catalog": base + "/catalog", engine: base + "/engine",
    "sessions-management": base + "/sessions", "testing-service": base + "/testing" },
  proxy: base, dedicatedStand: true, adapter: "./broker-stand.ts", auth: "./reference-auth.ts",
  sensitiveArtifacts: process.env.QIP_TEST_BROKER_RESTART === "1",
  brokers: {
    kafka: { host: "runner-kafka:9092", engine: "engine-kafka:9092", listeners: { tls13: "engine-kafka:9097" } },
    rabbitmq: { host: "amqp://runner-rabbitmq:5672/e2e-vhost", engine: "engine-rabbitmq:5672", username: "e2e", password: "package-broker-secret" },
    pubsub: { host: base, projectId: "e2e-package" },
    sftp: { host: { host: "runner-sftp", port: 2222 }, engine: "engine-sftp:22", username: "e2e", password: "package-sftp-secret" },
  },
  projects: [{ name: "broker-adapter", testDir: "./broker-adapter-tests", metadata: { stack: false } }],
});
`);
  await writeFile(path.join(consumer, "broker-stand.ts"), `
import type { SuiteExtension } from "@netcracker/qip-e2e/external";
export default {
  target: (base) => ({
    ...base,
    createEnv(kind, state) {
      return Object.assign(base.createEnv(kind, state), {
        async ensureOverlay() { throw new Error("Unexpected infrastructure setup"); },
        async removeOverlay() { throw new Error("Unexpected infrastructure cleanup"); },
        async restartOverlay() { throw new Error("Unexpected default restart"); },
      });
    },
  }),
  kubernetes: { microService: (domain) => "application-engine-" + domain },
  brokers: process.env.QIP_TEST_BROKER_RESTART ? {
    async restart(broker) { throw new Error("Consumer restarts " + broker); },
  } : undefined,
} satisfies SuiteExtension;
`);
  const command = [cli, "test", "--config=" + file];
  const healthBefore = server.healthRequests();
  await assert.rejects(run(process.execPath, [...command, "--project=brokers-restart", "--no-deps"]), /requires adapter.brokers.restart/);
  assert.deepEqual(server.healthRequests(), healthBefore);
  await mkdir(path.join(consumer, "broker-adapter-tests"));
  await writeFile(path.join(consumer, "broker-adapter-tests", "fixtures.spec.ts"), String.raw`
import { test, expect } from "@playwright/test";
import { pathToFileURL } from "node:url";
import path from "node:path";
const root = process.env.QIP_E2E_EXTERNAL_TEST_ROOT!;
const load = (file: string) => import(pathToFileURL(path.join(root, file)).href);
test("uses external broker addresses in classic and micro fixtures", async () => {
  const { configureBrokerFixture, configureBrokerSpecification } = await load("fixtures/external-brokers.ts");
  const { renderFixture, microCopy, readFixtureDocument, readSpecificationFixture, BROKERS_FIXTURE_DIR } = await load("fixtures/templating.ts");
  const inline = readSpecificationFixture("async-api-kafka-inline-ref.yaml");
  expect(configureBrokerSpecification(inline.buffer.toString("utf8"))).toContain("url: engine-kafka:9092");
  const { brokerServiceTrees, brokerCorpusOf } = await load("support/brokers.ts");
  const { kafkaHostBrokers, pubsubProjectId, sftpHostConnection } = await load("env/containers.ts");
  const copy = (name: string, micro = false) => {
    const tree = renderFixture(BROKERS_FIXTURE_DIR, name, "package");
    return configureBrokerFixture(name, micro ? microCopy(name, tree) : tree);
  };
  const fixture = (name: string, micro = false) => readFixtureDocument(name, copy(name, micro)).source;
  expect(kafkaHostBrokers()).toBe("runner-kafka:9092");
  expect(sftpHostConnection()).toMatchObject({ host: "runner-sftp", port: 2222 });
  expect(pubsubProjectId()).toBe("e2e-package");
  expect(fixture("kafka-trigger-basic", true)).toContain("engine-kafka:9092");
  expect(fixture("kafka-trigger-basic", true)).toContain("-micro");
  expect(fixture("kafka-trigger-tlsv1-2-refused.yaml")).toContain("engine-kafka:9097");
  expect(fixture("kafka-trigger-security-ssl-tls")).toContain("kafka:29095");
  expect(fixture("rabbitmq-trigger-basic")).toContain("vhost: e2e-vhost");
  expect(fixture("pubsub-trigger-basic")).toContain("projectId: e2e-package");
  expect(fixture("sftp-upload-basic")).toContain("engine-sftp:22/upload/package");
  const service = brokerServiceTrees("package").find(([name]: [string]) => name === "async-api-amqp");
  const amqp = [...configureBrokerFixture(...service).values()].join("\n");
  expect(amqp.match(/engine-rabbitmq:5672/g)).toHaveLength(2);
  expect(amqp).toContain("vhost: e2e-vhost");
  const corpus = brokerCorpusOf("package", [["rabbitmq-trigger-basic", copy("rabbitmq-trigger-basic")]], []);
  expect(JSON.stringify(corpus)).not.toContain("package-broker-secret");
});
test("authenticates only the registered engine proxy paths", async () => {
  const { K8sEnv } = await load("env/k8s.ts");
  const { gatewayUrl } = await load("support/kube.ts");
  const { authenticatedRequest } = await load("env/platform-http.ts");
  const request = authenticatedRequest({
    get: async (_url: string, options?: { headers?: Record<string, string> }) => options?.headers?.["x-service"],
  });
  const engine = new K8sEnv({ engineKind: "micro", microDomain: () => "test-domain" });
  expect(await request.get(engine.chainUrl("echo"))).toBe("engine");
  expect(await request.get(gatewayUrl("/routes/echo"))).toBe("engine");
  const base = process.env.QIP_PACKAGE_TEST_URL;
  expect(await request.get(base + "/e2e/svc/application-engine-other/routes/echo")).toBeUndefined();
  expect(await request.get(base + "/e2e/gateway-other/routes/echo")).toBeUndefined();
});
test("requires every service to refresh the API inventory", async () => {
  const { operationServices } = await load("registry/operations.ts");
  const { externalSettings, setExternalSettings } = await load("env/external-settings.ts");
  const settings = externalSettings();
  try {
    setExternalSettings({ ...settings, services: { engine: settings.services.engine } });
    expect(operationServices(["engine"]).map(({ service }: { service: string }) => service)).toEqual(["engine"]);
    expect(() => operationServices()).toThrow('Set services["runtime-catalog"]');
  } finally {
    setExternalSettings(settings);
  }
});
test("uses the consumer restart operation and keeps broker instances running", async () => {
  const { targetSetup } = await load("env/target-setup.ts");
  const env = targetSetup().createEnv("classic");
  await env.ensureOverlay("pubsub");
  await env.removeOverlay("pubsub");
  await expect(env.restartOverlay("kafka")).rejects.toThrow("Consumer restarts kafka");
});
`);
  await run(process.execPath, [...command, "--project=broker-adapter"], consumer, { ...environment, QIP_TEST_BROKER_RESTART: "1" });
  console.log("The installed package configures external broker fixtures and checks restart support before setup.");
}

async function verifyExternalReference(cli) {
  const events = path.join(consumer, "reference-auth-events.txt");
  const workspace = path.join(consumer, "reference-workspace");
  await writeFile(path.join(consumer, "external-reference.config.ts"), `
import { createExternalSuiteConfig } from "@netcracker/qip-e2e/external";
const base = process.env.QIP_PACKAGE_TEST_URL!;
const config = await createExternalSuiteConfig({
  services: { "runtime-catalog": base + "/catalog", engine: base + "/engine",
    "sessions-management": base + "/sessions", "testing-service": base + "/testing" },
  workspaceDir: ${JSON.stringify(workspace)}, proxy: base,
  dedicatedStand: true, adapter: "./reference-stand.ts", auth: "./reference-auth.ts",
  projects: [{ name: "reference-application", testDir: "./reference-tests" }],
  brokers: { pubsub: { host: base + "/unavailable-pubsub", projectId: "e2e-package" } },
});
if (process.env.QIP_PACKAGE_BROKER_OUTAGE) {
  const project = config.projects!.find(({ name }) => name === "brokers-seed")!;
  Object.assign(project, { testDir: "./broker-outage", dependencies: [], teardown: undefined });
}
export default config;
`);
  await mkdir(path.join(consumer, "reference-tests"));
  await writeFile(path.join(consumer, "reference-tests", "folders.spec.ts"), `
import { loadExternalFixtures } from "@netcracker/qip-e2e/external";
import path from "node:path";
import { pathToFileURL } from "node:url";
const { test, expect } = await loadExternalFixtures();
test("lists folders in the reference application", { tag: ["@catalog"] }, async ({ env, request, run }) => {
  const response = await request.get(env.url("runtime-catalog") + "/v1/folders");
  expect(response.ok()).toBe(true);
  if (process.env.QIP_PACKAGE_RUN_STATE) {
    const root = process.env.QIP_E2E_EXTERNAL_TEST_ROOT!;
    const { readCorpusState, writeCorpusState } = await import(pathToFileURL(path.join(root, "support/corpus.ts")).href);
    if (process.env.QIP_PACKAGE_RUN_STATE === "keep") writeCorpusState({ run, chains: [], services: [] });
    else expect(readCorpusState().run).toBe("kept01");
  }
});
`);
  await mkdir(path.join(consumer, "broker-outage"));
  await writeFile(path.join(consumer, "broker-outage", "broker.setup.ts"), `
import path from "node:path";
import { pathToFileURL } from "node:url";
const root = process.env.QIP_E2E_EXTERNAL_TEST_ROOT!;
const { test } = await import(pathToFileURL(path.join(root, "support/fixtures.ts")).href);
test("reports an unavailable broker from its setup project", async ({ env }) => {
  await env.ensureOverlay("pubsub");
});
`);
  await writeFile(path.join(consumer, "reference-stand.ts"), `
import type { SuiteExtension } from "@netcracker/qip-e2e/full";
export default {
  target: (base) => ({
    ...base,
    async provision() { throw new Error("The provisioner ran against an existing deployment."); },
    observer: {
      async baseline() { return []; },
      startSampler() {},
      async header(about) {
        return { ...about, startedAt: new Date().toISOString(), commit: { sha: null, branch: null, dirty: null }, services: [] };
      },
    },
  }),
} satisfies SuiteExtension;
`);
  await writeFile(path.join(consumer, "reference-auth.ts"), `
import { appendFileSync } from "node:fs";
import type { ExternalAuth } from "@netcracker/qip-e2e/external";
const names = { "runtime-catalog": "catalog", engine: "engine", "sessions-management": "sessions", "testing-service": "testing" };
export default {
  api: { getHeaders({ service, phase }) {
    appendFileSync(${JSON.stringify(events)}, service + " " + phase + "\\n");
    return { headers: {
      Authorization: service === "sessions-management" && phase === "cleanup" ? "Bearer package-sessions" : "Bearer package-test",
      "x-service": names[service],
      "x-e2e-phase": phase,
    } };
  } },
} satisfies ExternalAuth;
`);
  await run(process.execPath, [
    cli, "test", "--config=external-reference.config.ts", "--project=api", "--no-deps",
    "specs/api/health.spec.ts", "--grep", "reports UP and declares both probe groups", "--workers=1",
  ]);
  await run(process.execPath, [cli, "test", "--config=external-reference.config.ts", "--project=reference-application"]);
  await run(process.execPath, [cli, "test", "--config=external-reference.config.ts", "--project=ui-server", "--no-deps"]);
  const runs = path.join(workspace, "runs");
  const reports = await Promise.all((await readdir(runs)).map(async (runId) =>
    JSON.parse(await readFile(path.join(runs, runId, "report.json"), "utf8"))));
  const results = [], reached = [];
  for (const report of reports) {
    assert.equal(report.config.metadata.target, "compose");
    eachTest(report, (_spec, test) => {
      results.push(test.status);
      reached.push(...test.annotations.filter(({ type }) => type === "reached").map(({ description }) => description));
    });
  }
  assert.deepEqual(results, Array(5).fill("expected"));
  assert(reached.some((operation) => operation.includes("/v1/folders")));
  assert.match((await readFile(events, "utf8")), /runtime-catalog setup/);
  assert.match((await readFile(events, "utf8")), /engine test/);
  assert.match((await readFile(events, "utf8")), /runtime-catalog cleanup/);
  const config = [cli, "test", "--config=external-reference.config.ts"];
  const application = [...config, "--project=reference-application"];
  const before = server.platformRequests();
  server.setHealth("DOWN");
  try {
    await assert.rejects(run(process.execPath, application), /expected status UP/);
    assert.equal(server.platformRequests(), before);
  } finally {
    server.setHealth("UP");
  }

  const preserved = { ...environment, E2E_RUN: "kept01", E2E_SWEEP: "never", E2E_KEEP: "1" };
  await run(process.execPath, application, consumer, { ...preserved, QIP_PACKAGE_RUN_STATE: "keep" });
  await run(process.execPath, [...application, "--no-deps"], consumer, { ...preserved, QIP_PACKAGE_RUN_STATE: "resume" });
  const recovered = await run(process.execPath, application);
  assert.match(recovered.stdout, /kept01/);
  server.assertClean();

  await assert.rejects(run(process.execPath, [...application, "--project=brokers-seed"], consumer, {
    ...environment, QIP_PACKAGE_BROKER_OUTAGE: "1",
  }), (error) => {
    assert.match(error.stderr, /Cannot connect to the external pubsub broker/);
    return true;
  });
  const afterOutage = await Promise.all((await readdir(runs)).map(async (runId) =>
    JSON.parse(await readFile(path.join(runs, runId, "report.json"), "utf8"))));
  const partial = afterOutage.find((report) => report.cases.some(({ broker }) => broker));
  assert(partial?.cases.some(({ title, status }) => title.includes("reference-application") && status === "passed"));
  console.log("The reference API checks readiness and credentials, recovers kept state, and isolates broker failures.");
}

async function verifyFullSuite(cli, nodeModules) {
  await run(process.execPath, ["--input-type=module", "-e", `
import assert from "node:assert/strict";
import { prepareSuite } from "@netcracker/qip-e2e/full";
await assert.rejects(prepareSuite(), /Set sourceRoot/);
assert.equal(await prepareSuite({ sourceRoot: ${JSON.stringify(sourceRoot)} }), ${JSON.stringify(sourceRoot)});
`]);

  function cases(report) {
    const rows = [];
    eachTest(report, (spec, test, title) => rows.push(`${test.projectName} ${spec.file} ${title}`));
    return rows.sort();
  }
  environment.E2E_AI_RECOVERY = '1';
  for (const target of ['compose', 'k8s']) {
    environment.CIP_TARGET = target;
    const original = await run(process.execPath, [require.resolve('@playwright/test/cli'), 'test', '--list', '--reporter=json'], root);
    const installed = await run(process.execPath, [cli, 'test', '--config=full.config.ts', '--list', '--reporter=json']);
    const external = await run(process.execPath, [
      path.join(nodeModules, "@playwright/test/cli.js"), "test", "--config=external-default.config.ts", "--list", "--reporter=json",
    ]);
    const allExternal = JSON.parse(external.stdout);
    const originalCases = cases(JSON.parse(original.stdout));
    assert.deepEqual(cases(JSON.parse(installed.stdout)), cases(JSON.parse(original.stdout)), `The ${target} package lost or changed suite cases`);
    assert.deepEqual(cases(allExternal).filter((entry) => !entry.startsWith("health ")), originalCases);
    const edges = (report) => report.config.projects.filter(({ name }) => name !== "health").map(({ name, metadata }) =>
      [name, metadata.dependencies ?? []]);
    assert.deepEqual(edges(allExternal), edges(JSON.parse(original.stdout)));
    console.log(`Full ${target} suite: ${originalCases.length} cases and project dependencies match both package modes.`);
  }
  environment.CIP_TARGET = 'compose';
  const checkedSources = await run(process.execPath, [
    path.join(nodeModules, "@playwright/test/cli.js"), "test", "--config=external-default.config.ts",
    "--project=schema", "--project=harness", "--reporter=line",
  ], consumer, { ...environment, QIP_PACKAGE_SOURCE_ROOT: sourceRoot });
  assert.match(checkedSources.stdout, /\d+ passed/);
  console.log("The external profile ran the reference schema and harness projects from the consumer.");
  try {
    await run(process.execPath, [cli, 'test', '--config=full.config.ts', '--project=schema', '--project=harness']);
  } catch {
    const report = JSON.parse(await readFile(path.join(consumer, 'full-results.json'), 'utf8'));
    const failures = [];
    eachTest(report, (spec, test, title) => {
      if (test.status === 'unexpected') {
        failures.push({ file: spec.file, title, errors: test.results.flatMap(result => result.errors ?? []) });
      }
    });
    throw new Error(JSON.stringify(failures, null, 2));
  }
  const full = JSON.parse(await readFile(path.join(consumer, 'full-results.json'), 'utf8'));
  assert.equal(full.stats.unexpected, 0);
  console.log(`Installed full suite: ${full.stats.expected} schema/harness cases passed.`);

  const events = path.join(consumer, 'adapter-events.txt');
  await mkdir(path.join(consumer, 'application-tests'));
  await writeFile(path.join(consumer, 'application-adapter.ts'), `
import fs from 'node:fs';
import type { SuiteExtension } from '@netcracker/qip-e2e/full';
import { VsCodePage, MessageRecorder } from '@netcracker/qip-e2e/pages';
const event = (name: string) => fs.appendFileSync(${JSON.stringify(events)}, name + '\\n');
export default {
  target: base => ({
    ...base,
    async provision(mode) {
      return { mode, durationMs:0, built:[], rebuilt:[], recreated:[], started:[], untouched:[],
        published:[], staleSupport:[], proxyReloaded:false, proxyConfigChanged:false };
    },
    observer: {
      async baseline() { return []; },
      startSampler() {},
      async header(about) {
        return { ...about, startedAt:new Date().toISOString(), commit:{sha:null, branch:null, dirty:null}, services:[] };
      },
    },
    createEnv(kind, state) {
    return Object.assign(Object.create(base.createEnv(kind, state)), {
      async restartWith() { event('service'); },
      async restartOverlay() { event('broker'); },
    });
  } }),
  extendTest: base => base.extend({
    folder: async ({folder}, use) => { await use({ ...folder, name: folder.name + '-adapted' }); },
    diagnostics: async ({}, use) => { await use(); },
  }),
  extendVsCodeTest: base => base.extend({
    offsiteAnswers: async ({}, use) => { await use(); },
    messages: async ({}, use) => { await use(new MessageRecorder()); },
    vscodeGuard: async ({}, use) => { await use(); },
    vscode: async ({}, use) => { await use(Object.assign(Object.create(VsCodePage.prototype), {application:true})); },
  }),
  kubernetes: {
    async kubectl(args) {
      if (args[0] !== 'top') throw new Error('Expected a resource sample');
      return 'cip-engine-v1-deployment-pod cip-engine 12m 20Mi';
    },
  },
  lifecycle: {
    schemas: async () => { event('schemas'); return false; },
    startUi: async () => { event('ui'); return { pid: null, built: false }; },
    stopUi: async () => { event('ui-stop'); },
    startVsCode: async () => { event('vscode'); return { pid: 42, built: [] }; },
    stopVsCode: async () => { event('vscode-stop'); },
  },
} satisfies SuiteExtension;
`);
  await writeFile(path.join(consumer, 'application.config.ts'), `
import { createSuiteConfig } from '@netcracker/qip-e2e/full';
const server = process.env.QIP_PACKAGE_TEST_URL;
export default await createSuiteConfig({
  target:'compose', sourceRoot: ${JSON.stringify(sourceRoot)}, adapter:'./application-adapter.ts',
  services: {
    'runtime-catalog': server + '/catalog',
    'engine': server + '/engine',
    'sessions-management': server + '/sessions',
    'testing-service': server + '/testing',
  },
  request: { extraHTTPHeaders: { Authorization:'Bearer package-test' } },
  serviceRequests: {
    'runtime-catalog': { extraHTTPHeaders: { 'x-service':'catalog' } },
    'engine': { extraHTTPHeaders: { 'x-service':'engine' } },
    'sessions-management': { extraHTTPHeaders: { authorization:'Bearer package-sessions', 'x-service':'sessions' } },
    'testing-service': { extraHTTPHeaders: { 'x-service':'testing' } },
  },
  config:{reporter:[['line']], outputDir:'./application-output', use:{baseURL: server + '/catalog/'}},
  projects:[{name:'application', testDir:'./application-tests'}],
});
`);
  await writeFile(path.join(consumer, 'application-tests', 'extension.spec.ts'), `
import { loadSuiteFixtures } from '@netcracker/qip-e2e/full';
import { pathToFileURL } from 'node:url';
const { test, expect } = await loadSuiteFixtures();
test('uses the application adapter and extended fixtures', async ({ env, run, folder, request }) => {
  expect(folder.name).toMatch(/^e2e-[a-z0-9]+-w[0-9]+-adapted$/);
  const listing = await request.get('v1/folders');
  expect(listing.ok()).toBe(true);
  expect((await listing.json()).some((item: { id: string }) => item.id === folder.id)).toBe(true);
  const server = process.env.QIP_PACKAGE_TEST_URL;
  const unrelated = await request.get(server + '/unrelated');
  expect(await unrelated.json()).toEqual({ authorized:false });
  const session = await request.post(server + '/sessions/fixture', {
    data: { chainId: 'e2e-' + run, chainName: 'e2e-' + run + '-application' },
  });
  expect(session.status()).toBe(204);
  await env.restartWith('engine', { example:'value' });
  await env.restartOverlay('kafka');
  const support = process.env.QIP_E2E_WORKSPACE + '/e2e/support/';
  const ui = await import(pathToFileURL(support + 'ui-server.ts').href);
  await ui.ensureUiServer(); await ui.stopUiServer();
  const schemas = await import(pathToFileURL(support + 'schemas-build.ts').href);
  await schemas.ensureSchemas();
  const vscode = await import(pathToFileURL(support + 'vscode-server.ts').href);
  await vscode.ensureVsCodeServer(); await vscode.stopVsCodeServer();
});
`);
  await writeFile(path.join(consumer, 'application-tests', 'vscode.spec.ts'), `
import { VsCodePage } from '@netcracker/qip-e2e/pages';
import { pathToFileURL } from 'node:url';
const { test, expect } = await import(pathToFileURL(process.env.QIP_E2E_WORKSPACE + '/e2e/support/vscode-guard.ts').href);
test('extends the VS Code fixtures without a product stack', async ({vscode}: {vscode:unknown}) => {
  expect(vscode).toEqual({application:true});
  const noOp = async () => {};
  const editor = {
    page: { keyboard: { press: noOp }, evaluate: async () => "the document" },
    lines: () => ({ click: noOp }),
  } as unknown as VsCodePage;
  expect(await VsCodePage.prototype.readText.call(editor, (text) => text === "the document")).toBe("the document");
});
`);
  await writeFile(path.join(consumer, 'application-tests', 'sampler.spec.ts'), `
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
const { test, expect } = createRequire(process.env.QIP_E2E_WORKSPACE + '/e2e/package.json')('@playwright/test');
const env = process.env.QIP_E2E_WORKSPACE + '/e2e/env/';
test('samples Kubernetes through the installed suite adapter', async () => {
  const { targetSetup } = await import(pathToFileURL(env + 'target-setup.ts').href);
  const { readK8sPeaks } = await import(pathToFileURL(env + 'k8s-observer.ts').href);
  const { stopResourceSampler } = await import(pathToFileURL(env + 'resources.ts').href);
  const stop = targetSetup('k8s').observer.startSampler('application-sampler', []);
  try {
    await expect.poll(() => readK8sPeaks()).toEqual([
      { role: 'engine', memoryBytes: 20 * 1024 * 1024, cpuPercent: 1.2, samples: 1 },
    ]);
  } finally {
    stop?.();
    stopResourceSampler(undefined, 'application-sampler');
  }
});
`);
  await writeFile(path.join(consumer, 'page-imports.ts'), `
import { ChainsPage, ChainGraphPage, VsCodePage } from '@netcracker/qip-e2e/pages';
export const pages = [ChainsPage, ChainGraphPage, VsCodePage];
`);
  await run(process.execPath, [path.join(nodeModules, 'typescript/bin/tsc')]);
  server.restore();
  environment.E2E_RUN_MANIFEST = path.join(consumer, 'adapter-runs.json');
  environment.E2E_RESOURCES_FILE = path.join(consumer, 'adapter-resources.json');
  environment.E2E_SAMPLES_FILE = path.join(consumer, 'adapter-samples.jsonl');
  await run(process.execPath, [cli, 'test', '--config=application.config.ts', '--project=application']);
  assert.deepEqual((await readFile(events, 'utf8')).trim().split('\n'), ['service','broker','ui','ui-stop','schemas','vscode','vscode-stop']);
  server.assertFullClean();
  console.log('Full suite consumer passed: scoped credentials, owned cleanup, fixture extensions, and lifecycle callbacks.');
}

async function verifyExternal(cli, nodeModules) {
  await writeFile(path.join(consumer, "external.config.ts"), `
import { createExternalSuiteConfig, type ExternalSuite, type ExternalSuiteOptions } from '@netcracker/qip-e2e/external';
const base = process.env.QIP_PACKAGE_TEST_URL!;
const suite = (process.env.QIP_PACKAGE_SUITE ?? 'health') as ExternalSuite;
const gateway = process.env.QIP_PACKAGE_GATEWAY;
const options = {
  services: {
    'runtime-catalog': base + '/catalog',
    engine: process.env.INVALID_EXTERNAL_URL ? undefined : base + (gateway ? '/api/engine' : '/engine'),
    'sessions-management': base + '/sessions',
    ...(suite === 'testing-service' ? { 'testing-service': base + '/testing' } : {}),
  },
  suites: [suite],
  ui: gateway || suite === 'testing-service' ? base : undefined,
  proxy: process.env.QIP_PACKAGE_PROXY ? base : undefined,
  workspaceDir: process.env.QIP_PACKAGE_SHARED_WORKSPACE,
  routePrefix: process.env.QIP_PACKAGE_ROUTE_PREFIX,
  auth: process.env.EXTERNAL_AUTH ? './auth.ts' : undefined,
  deployment: { domain: 'test-domain' },
  infrastructure: suite === 'infrastructure' ? { adapter: './infrastructure.ts', dedicatedStand: true } : undefined,
} satisfies ExternalSuiteOptions;
export default await createExternalSuiteConfig(options);
`);
  await writeFile(path.join(consumer, "infrastructure.ts"), `
import type { ExternalInfrastructure } from '@netcracker/qip-e2e/external';
let restarts = 0;
export default {
  async restart() { restarts++; },
  async processEnvelope(role) { return { role, restarts, startedAt: String(restarts), metaspace: null }; },
  async logs() { return await (await fetch(process.env.QIP_PACKAGE_TEST_URL + '/infrastructure/log')).json(); },
  async resourcePeaks() {
    return [{ role: 'engine', memoryBytes: 100, cpuPercent: 0, samples: process.env.QIP_BAD_PEAK ? 1 : 2 }];
  },
} satisfies ExternalInfrastructure;
`);
  await writeFile(path.join(consumer, "auth.ts"), `
import { appendFileSync } from "node:fs";
import type { ExternalAuth } from '@netcracker/qip-e2e/external';
if (process.env.NO_AUTH_IMPORT) throw new Error("Auth imported during collection");
const names = { 'runtime-catalog': 'catalog', engine: 'engine', 'sessions-management': 'sessions', 'testing-service': 'testing' };
export default {
  api: {
    getHeaders({ service, phase }) {
      if (process.env.FAIL_AUTH) throw new Error("secret-auth-sentinel");
      const events = process.env.QIP_AUTH_EVENTS;
      if (events) appendFileSync(events, service + " " + phase + "\\n");
      return { headers: { Authorization: 'Bearer package-test', 'x-service': names[service] },
        expiresAt: Date.now() + 60000 };

    },
  },
} satisfies ExternalAuth;
`);
  await run(process.execPath, [path.join(nodeModules, "typescript/bin/tsc")]);
  const spec = [cli, "test", "--config=external.config.ts"];
  const baseline = server.platformRequests();
  const gitFreeEnvironment = Object.fromEntries(
    Object.entries(environment).filter(([name]) => name.toLowerCase() !== "path"),
  );
  gitFreeEnvironment.PATH = path.dirname(process.execPath);
  assert.equal(spawnSync("git", ["--version"], { cwd: consumer, env: gitFreeEnvironment }).error?.code, "ENOENT");
  const listing = await run(process.execPath, [...spec, "--list"], consumer, {
    ...gitFreeEnvironment, EXTERNAL_AUTH: "1", NO_AUTH_IMPORT: "1",
  });
  assert.equal(listing.stdout.split("\n").filter((line) => line.includes("reports healthy")).length, 3);
  assert.deepEqual(server.healthRequests(), []);
  assert.equal(server.platformRequests(), baseline);
  const health = await run(process.execPath, [...spec, "--reporter=line"], consumer, gitFreeEnvironment);
  assert.match(health.stdout, /3 passed/);
  assert.deepEqual(server.healthRequests(), [
    "GET /catalog/actuator/health", "GET /engine/actuator/health", "GET /sessions/actuator/health",
  ]);
  assert.equal(server.platformRequests(), baseline);
  environment.INVALID_EXTERNAL_URL = "1";
  await assert.rejects(run(process.execPath, [...spec, "--list"]), /Set services\["engine"\]/);
  delete environment.INVALID_EXTERNAL_URL;

  server.setHealth("DOWN");
  await assert.rejects(run(process.execPath, [...spec, "--reporter=line"]), /Expected: "UP"/);
  server.setHealth("UP");

  environment.EXTERNAL_AUTH = "1";
  environment.FAIL_AUTH = "1";
  await assert.rejects(run(process.execPath, spec), (error) => {
    assert.match(error.stdout + error.stderr, /API authorization failed for engine during test/);
    assert.doesNotMatch(error.stdout + error.stderr, /secret-auth-sentinel/);
    return true;
  });
  delete environment.FAIL_AUTH;
  environment.QIP_PACKAGE_GATEWAY = "1";
  await run(process.execPath, spec);
  delete environment.QIP_PACKAGE_GATEWAY;

  environment.QIP_PACKAGE_SUITE = "testing-service";
  assert.doesNotMatch((await run(process.execPath, [...spec, "--list"])).stdout, /nginx blocks the mock call path/);
  environment.QIP_PACKAGE_PROXY = "1";
  assert.match((await run(process.execPath, [...spec, "--list"])).stdout, /nginx blocks the mock call path/);
  delete environment.QIP_PACKAGE_PROXY;
  delete environment.QIP_PACKAGE_SUITE;
  const runRoot = path.join(consumer, ".qip-e2e/runs");
  for (const file of await readdir(runRoot, { recursive: true, withFileTypes: true })) {
    if (!file.isFile()) continue;
    assert.doesNotMatch(await readFile(path.join(file.parentPath, file.name), "utf8"), /secret-auth-sentinel/);
    assert(!file.name.endsWith(".zip"), "Protected runs have tracing off by default");
  }

  if (!process.argv.includes("--host-smoke")) {
    environment.QIP_PACKAGE_SUITE = "catalog";
    const catalog = await run(process.execPath, [
      ...spec, "--grep", "bulk-delete removes the named chains|migrate replaces a deprecated choice", "--workers=1",
    ]);
    assert.match(catalog.stdout, /External suite passed/);

    environment.QIP_PACKAGE_SUITE = "runtime";
    const events = path.join(consumer, "auth-events.txt");
    environment.QIP_AUTH_EVENTS = events;
    const args = [...spec, "--grep", "answers on its deployed route", "--workers=1"];
    await Promise.all([
      run(process.execPath, args),
      run(process.execPath, args, consumer, { ...environment, QIP_PACKAGE_ROUTE_PREFIX: "/" }),
    ]);
    server.assertClean();
    const refreshed = (await readFile(events, "utf8")).trim().split("\n");
    assert(refreshed.includes("runtime-catalog setup"));
    assert(refreshed.includes("engine setup"));
    assert(refreshed.includes("sessions-management setup"));
    assert(refreshed.includes("runtime-catalog cleanup"));
    assert(refreshed.includes("engine cleanup"));
    assert(refreshed.includes("sessions-management cleanup"));
    delete environment.QIP_AUTH_EVENTS;
    assert((await readdir(runRoot, { recursive: true })).every((file) => !file.includes(`${path.sep}owned${path.sep}`)));

    server.dropFirstWarmup();
    await run(process.execPath, args);
    server.assertClean();
    server.denyRuntime(true);
    try {
      await assert.rejects(run(process.execPath, args), (error) => {
        const output = error.stdout + error.stderr;
        assert.match(output, /API authorization failed for engine during test \(HTTP 401\)/);
        assert.match(output, /API authorization failed for runtime-catalog during cleanup \(HTTP 403\)/);
        assert.match(output, /Cleanup failed for chain [0-9a-f-]+/);
        assert.doesNotMatch(output, /secret-(?:engine|cleanup)-response/);
        return true;
      });
    } finally {
      server.denyRuntime(false);
    }
    server.assertClean();

    environment.QIP_PACKAGE_SUITE = "infrastructure";
    const infrastructure = [...spec, "--reporter=line"];
    await run(process.execPath, infrastructure);
    server.setRestartLog("empty");
    try {
      await assert.rejects(run(process.execPath, infrastructure), /Engine log entries for deployment/);
      server.setRestartLog("error");
      await assert.rejects(run(process.execPath, infrastructure), /Engine log errors for deployment/);
    } finally {
      server.setRestartLog("info");
    }
    await assert.rejects(run(process.execPath, infrastructure, consumer, { ...environment, QIP_BAD_PEAK: "1" }),
      /Expected: >= 2/);
    server.assertClean();
    delete environment.QIP_PACKAGE_SUITE;
  }
  delete environment.EXTERNAL_AUTH;
  console.log("External tarball passed first-run health without Git, exact reads, failed health, and protected authorization.");
}

async function verifyWorkspaceFailure() {
  await run(process.execPath, ["--input-type=module", "-e", `
import assert from "node:assert/strict";
import fs from "node:fs";
import { createExternalSuiteConfig } from "@netcracker/qip-e2e/external";

const options = { services: { engine: "http://localhost:8090" }, suites: ["health"], workspaceDir: "workspace-failure" };
const prepareError = Object.assign(new Error("Cannot create a dependency link."), { code: "EACCES" });
const cleanupError = Object.assign(new Error("Cannot remove the temporary directory."), { code: "EPERM" });
const { symlinkSync, rmSync } = fs;
fs.symlinkSync = () => { throw prepareError; };
fs.rmSync = () => { throw cleanupError; };
try {
  await assert.rejects(createExternalSuiteConfig(options), (error) => {
    assert(error instanceof AggregateError);
    assert.equal(error.cause, prepareError);
    assert.deepEqual(error.errors, [prepareError, cleanupError]);
    assert.match(error.message, /Cannot create a dependency link/);
    assert.match(error.message, /Cannot remove the temporary directory/);
    return true;
  });
} finally {
  Object.assign(fs, { symlinkSync, rmSync });
}
const config = await createExternalSuiteConfig(options);
assert(fs.existsSync(config.testDir));
`]);
}

async function verifyBrowserAuth(cli) {
  await writeFile(path.join(consumer, "browser.config.ts"), `
import { createExternalSuiteConfig } from '@netcracker/qip-e2e/external';
const url = process.env.QIP_PACKAGE_TEST_URL!;
export default await createExternalSuiteConfig({
  services: { engine: url + '/engine' },
  suites: ['health'], ui: url, auth: './browser-auth.ts',
  config: { workers: 1, use: { extraHTTPHeaders: { 'x-qip-ui': 'configured' } } },
  projects: [{ name: 'browser-application', testDir: './browser-tests', use: { baseURL: url } }],
});
`);
  await writeFile(path.join(consumer, "browser-auth.ts"), `
import type { ExternalAuth } from '@netcracker/qip-e2e/external';
export default {
  ui: {
    async login({ page }) {
      if (process.env.FAIL_UI_AUTH) throw new Error("secret-ui-sentinel");
      const response = await page.goto('/login');
      if (!response?.ok()) throw new Error('The login did not receive the configured header.');
      await page.getByText('Signed in').waitFor();
    },
  },
} satisfies ExternalAuth;
`);
  await mkdir(path.join(consumer, "browser-tests"));
  await writeFile(path.join(consumer, "browser-tests", "cookie.spec.ts"), `
import { loadExternalUiFixtures } from '@netcracker/qip-e2e/external';
const { test, expect } = await loadExternalUiFixtures();
for (const title of ['uses the sign-in state', 'reuses the sign-in state']) {
  test(title, async ({ page }) => {
    await page.goto(process.env.FAIL_UI_CASE ? '/browser-application?failed=1' : '/browser-application');
    await expect(page.getByRole('main')).toHaveText('Authorized', { timeout: 1000 });
    if (process.env.FAIL_UI_RESPONSE) await page.evaluate(async () => { await fetch('/api/browser-denied'); });
  });
}
`);
  const args = [cli, "test", "--config=browser.config.ts", "--project=browser-application"];
  const passed = await run(process.execPath, args);
  assert.match(passed.stdout, /External suite passed\./);
  assert.equal(server.browserLogins(), 1);
  environment.FAIL_UI_AUTH = "1";
  await assert.rejects(run(process.execPath, args), (error) => {
    assert.match(error.stdout + error.stderr, /UI authorization failed during login/);
    assert.doesNotMatch(error.stdout + error.stderr, /secret-ui-sentinel/);
    return true;
  });
  delete environment.FAIL_UI_AUTH;
  environment.FAIL_UI_CASE = "1";
  await assert.rejects(run(process.execPath, args), (error) => {
    assert.match(error.stdout + error.stderr, /uses the sign-in state: failed/);
    assert.doesNotMatch(error.stdout + error.stderr, /secret-ui-page-sentinel/);
    return true;
  });
  delete environment.FAIL_UI_CASE;
  environment.FAIL_UI_RESPONSE = "1";
  await assert.rejects(run(process.execPath, args), (error) => {
    assert.match(error.stdout + error.stderr, /UI authorization failed during test \(HTTP 401\)/);
    assert.doesNotMatch(error.stdout + error.stderr, /secret-ui-response-sentinel/);
    return true;
  });
  delete environment.FAIL_UI_RESPONSE;
  const runRoot = path.join(consumer, ".qip-e2e/runs");
  for (const file of await readdir(runRoot, { recursive: true, withFileTypes: true })) {
    if (!file.isFile()) continue;
    assert.doesNotMatch(await readFile(path.join(file.parentPath, file.name), "utf8"), /secret-ui-(?:sentinel|page-sentinel|response-sentinel)|qip-login/);
    assert(!file.name.endsWith(".zip"), "Protected browser runs have tracing off by default");
  }
  console.log("External browser fixtures reuse the login state and sanitize a failed login.");
}

async function verifySharedWorkspace(cli, tarball, playwrightVersion) {
  const sibling = await mkdtemp(path.join(tmpdir(), "qip-e2e-sibling-"));
  try {
    await writeFile(path.join(sibling, "package.json"), JSON.stringify({ private: true, type: "module" }));
    await writeFile(path.join(sibling, "external.config.ts"), await readFile(path.join(consumer, "external.config.ts")));
    await run(process.execPath, [npm, "install", "--ignore-scripts", "--registry=https://registry.npmjs.org", tarball, `@playwright/test@${playwrightVersion}`], sibling);
    const shared = { ...environment, QIP_PACKAGE_SHARED_WORKSPACE: path.join(consumer, "shared-workspace") };
    const list = async (runner, cwd) => {
      const result = await run(process.execPath, [runner, "test", "--config=external.config.ts", "--list", "--reporter=json"], cwd, shared);
      return JSON.parse(result.stdout).config.projects[0].testDir;
    };
    const first = await list(cli, consumer);
    const second = await list(path.join(sibling, "node_modules/@playwright/test/cli.js"), sibling);
    assert.notEqual(first, second, "Separate Playwright installations must have separate cached suites.");
    for (const [suiteDir, cwd] of [[first, consumer], [second, sibling]]) {
      assert.equal(await realpath(path.resolve(suiteDir, "../node_modules/@playwright/test")), await realpath(path.join(cwd, "node_modules/@playwright/test")));
    }
  } finally {
    await rm(sibling, { recursive: true, force: true });
  }
}
