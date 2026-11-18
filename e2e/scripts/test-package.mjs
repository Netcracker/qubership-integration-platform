import { mkdtemp, readFile, writeFile, rm, readdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import assert from "node:assert/strict";
import { packageServer } from "./package-server.mjs";

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
  const { mkdir } = await import("node:fs/promises");
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
  function cases(report) {
    const rows = [];
    function visit(suite, parents) {
      for (const spec of suite.specs ?? []) for (const test of spec.tests) {
        rows.push(`${test.projectName} ${spec.file} ${[...parents, spec.title].join(' > ')}`);
      }
      for (const child of suite.suites ?? []) visit(child, [...parents, child.title]);
    }
    for (const suite of report.suites) visit(suite, []);
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
    function readFailures(suite) {
      for (const spec of suite.specs ?? []) for (const test of spec.tests) if (test.status === 'unexpected') {
        failures.push({ file: spec.file, title: spec.title, errors: test.results.flatMap(result => result.errors ?? []) });
      }
      for (const child of suite.suites ?? []) readFailures(child);
    }
    for (const suite of report.suites) readFailures(suite);
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
  target: base => ({ ...base, createEnv(kind, state) {
    return Object.assign(Object.create(base.createEnv(kind, state)), {
      async restartWith() { event('service'); },
      async restartOverlay() { event('broker'); },
    });
  } }),
  extendTest: base => base.extend({
    run: 'pkg123',
    folder: async ({}, use) => { await use({id:'application', name:'application'}); },
    diagnostics: async ({}, use) => { await use(); },
  }),
  extendVsCodeTest: base => base.extend({
    offsiteAnswers: async ({}, use) => { await use(); },
    messages: async ({}, use) => { await use(new MessageRecorder()); },
    vscodeGuard: async ({}, use) => { await use(); },
    vscode: async ({}, use) => { await use(Object.assign(Object.create(VsCodePage.prototype), {application:true})); },
  }),
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
export default await createSuiteConfig({ target:'compose', adapter:'./application-adapter.ts',
  config:{reporter:[['line']]},
  projects:[{name:'application', testDir:'./application-tests', metadata:{stack:false}}],
});
`);
  await writeFile(path.join(consumer, 'application-tests', 'extension.spec.ts'), `
import { loadSuiteFixtures } from '@netcracker/qip-e2e/full';
import { pathToFileURL } from 'node:url';
const { test, expect } = await loadSuiteFixtures();
test('uses the application adapter and extended fixtures', async ({ env, run, folder }) => {
  expect(run).toBe('pkg123');
  expect(folder.id).toBe('application');
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
  await writeFile(path.join(consumer, 'page-imports.ts'), `
import { ChainsPage, ChainGraphPage, VsCodePage } from '@netcracker/qip-e2e/pages';
export const pages = [ChainsPage, ChainGraphPage, VsCodePage];
`);
  await run(process.execPath, [path.join(nodeModules, 'typescript/bin/tsc')]);
  await run(process.execPath, [cli, 'test', '--config=application.config.ts', '--project=application']);
  assert.deepEqual((await readFile(events, 'utf8')).trim().split('\n'), ['service','broker','ui','ui-stop','schemas','vscode','vscode-stop']);
  console.log('Full suite consumer extension passed: fixtures, service and broker restarts, UI, schemas, and VS Code lifecycle.');
  assert.deepEqual(await readdir(packageRoot, { recursive: true }), before);
} finally {
  await server.close();
  await rm(consumer, { recursive: true, force: true });
}
