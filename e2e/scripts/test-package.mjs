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
  await verifyWorkspaceFailure();
  if (process.argv.includes("--browser")) await verifyBrowserAuth(cli);
  if (!process.argv.includes("--host-smoke")) await verifySharedWorkspace(cli, installFile, playwrightVersion);
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
    assert.deepEqual(cases(JSON.parse(installed.stdout)), cases(JSON.parse(original.stdout)), `The ${target} package lost or changed suite cases`);
    console.log(`Full ${target} suite: ${cases(JSON.parse(installed.stdout)).length} cases match the source suite.`);
  }
  environment.CIP_TARGET = 'compose';
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

const options = { services: { engine: "http://localhost:8090" }, workspaceDir: "workspace-failure" };
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
