import { mkdir, mkdtemp, readFile, writeFile, rm, readdir, rename } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { execFile } from "node:child_process";
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
const environment = { ...process.env, PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: "1", QIP_PACKAGE_TEST_URL: server.url };

async function run(command, args, cwd = consumer) {
  try {
    return await execute(command, args, { cwd, env: environment, maxBuffer: 32 * 1024 * 1024 });
  } catch (error) {
    error.message += `\n${error.stdout ?? ""}\n${error.stderr ?? ""}`;
    throw error;
  }
}

try {
  const packed = await run(process.execPath, [npm, "pack", "--ignore-scripts", "--json", "--pack-destination", consumer], root);
  const [tarball] = JSON.parse(packed.stdout);
  assert(tarball.files.some((file) => file.path === "dist/portable/index.d.ts"));
  assert(tarball.files.every((file) => !file.path.includes("node_modules") && !file.path.endsWith(".tgz")));
  await writeFile(path.join(consumer, "package.json"), JSON.stringify({ private: true, type: "module" }));
  const playwrightVersion = require("@playwright/test/package.json").version;
  const typescriptVersion = require("typescript/package.json").version;
  await run(process.execPath, [npm, "install", "--ignore-scripts", "--registry=https://registry.npmjs.org", path.join(consumer, tarball.filename), `@playwright/test@${playwrightVersion}`, `typescript@${typescriptVersion}`, "@types/node@22"]);
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
  const cli = path.join(nodeModules, "@playwright/test/cli.js");
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
  target: process.env.CIP_TARGET as 'compose' | 'k8s',
  config: { workers: 4, reporter: [['json', { outputFile: 'full-results.json' }]] },
});
`);
  if (process.argv.includes("--host-smoke")) {
    environment.CIP_TARGET = "compose";
    const host = await run(process.execPath, [
      cli, "test", "--config=full.config.ts", "--project=harness", "--workers=2", "--reporter=line",
      "bootstrap-host.spec.ts", "build-lock.spec.ts", "host-process.spec.ts", "process-group.spec.ts",
    ]);
    process.stdout.write(host.stdout);
  } else {
    await verifyFullSuite(cli, nodeModules);
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
      playwright, "test", "--config=full.config.ts", "--project=harness", "--reporter=line",
      "host-process.spec.ts", "--grep", "the provisioner records and stops its resource sampler",
    ], moved);
    assert.match(result.stdout, /1 passed/);
    console.log("The installed suite reuses its workspace after the consumer directory moves.");
  } finally {
    await rename(moved, consumer);
  }
}

async function verifyFullSuite(cli, nodeModules) {
  await run(process.execPath, ["--input-type=module", "-e", `
import assert from "node:assert/strict";
import { prepareSuite } from "@netcracker/qip-e2e/full";
const roots = await Promise.all([prepareSuite(), prepareSuite()]);
assert.equal(roots[0], roots[1]);
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
  target:'compose', adapter:'./application-adapter.ts',
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
  config:{reporter:[['line']], use:{baseURL: server + '/catalog/'}},
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
import { pathToFileURL } from 'node:url';
const { test, expect } = await import(pathToFileURL(process.env.QIP_E2E_WORKSPACE + '/e2e/support/vscode-guard.ts').href);
test('extends the VS Code fixtures without a product stack', async ({vscode}: {vscode:unknown}) => {
  expect(vscode).toEqual({application:true});
});
`);
  await writeFile(path.join(consumer, 'application-tests', 'sampler.spec.ts'), `
import { test, expect } from '@playwright/test';
import { pathToFileURL } from 'node:url';
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
  environment.E2E_RESOURCES_FILE = path.join(consumer, 'adapter-resources.json');
  environment.E2E_SAMPLES_FILE = path.join(consumer, 'adapter-samples.jsonl');
  await run(process.execPath, [cli, 'test', '--config=application.config.ts', '--project=application']);
  assert.deepEqual((await readFile(events, 'utf8')).trim().split('\n'), ['service','broker','ui','ui-stop','schemas','vscode','vscode-stop']);
  server.assertFullClean();
  console.log('Full suite consumer passed: scoped credentials, owned cleanup, fixture extensions, and lifecycle callbacks.');
}
