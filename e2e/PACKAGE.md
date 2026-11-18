# Run and extend the full end-to-end suite

`@netcracker/qip-e2e` distributes the complete end-to-end suite: API, runtime, UI, brokers, restarts, global checks, Kubernetes and micro-engine checks, VS Code, AI tooling, schema checks, and harness checks.
The package keeps the original project dependencies, setup, teardown, fixtures, and coverage registries.
The Compose and Kubernetes profiles select the same cases as the source repository.

## Install

Use Node.js 22.19 or later, Git, and an ESM test project (`"type": "module"`).
Configure access to GitHub Packages:

```ini
@netcracker:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${NODE_AUTH_TOKEN}
```

```sh
npm install --save-dev --save-exact @netcracker/qip-e2e@<version> @playwright/test@1.62.1
npx playwright install chromium
```

Install the tools required by the projects you run: Docker Compose or Kubernetes and Helm for managed stacks, Java and Maven for backend builds, and Python for the AI tooling cases.
The package includes the Python sources and dependency manifests.
Optional private recovery fixtures retain their source references; supply those inputs or implement the application's AI recovery lifecycle through its environment adapter.

## Configure the full suite

Create `playwright.config.ts`:

```ts
import { createSuiteConfig } from '@netcracker/qip-e2e/full';

export default await createSuiteConfig({
  target: 'k8s',
  workspaceDir: '.qip-e2e/platform',
  services: {
    'runtime-catalog': process.env.CATALOG_URL!,
    engine: process.env.ENGINE_URL!,
    'sessions-management': process.env.SESSIONS_URL!,
    'testing-service': process.env.TESTING_URL!,
  },
  proxyUrl: process.env.UI_URL!,
  request: {
    extraHTTPHeaders: { Authorization: `Bearer ${process.env.PLATFORM_TOKEN}` },
  },
  config: {
    workers: 4,
    reporter: [['list'], ['html', { open: 'never' }]],
  },
});
```

```sh
npx playwright test --list
npx playwright test
npx playwright test --project=ui
npx playwright test --project=brokers
npx playwright test --project=env
npx playwright test --project=brokers-restart
```

Project selection uses Playwright's dependency graph. Selecting UI brings its seed and server setup; selecting broker checks brings their topology and corpus setup.
The original target-specific and opt-in rules remain in force, including `E2E_AI_RECOVERY` and `E2E_BROKERS`.
Use `--no-deps` only when the required setup and corpus already exist.
The suite's [run guide](README.md) lists every project, tool, environment variable, and teardown rule.

The default target adapters manage the reference Compose stack or Kubernetes installation.
For a consuming application's own deployment, provide an adapter before running projects that change its infrastructure.

## Adapt the environment

Pass an absolute adapter module path to `createSuiteConfig`:

```ts
import { fileURLToPath } from 'node:url';
import { createSuiteConfig } from '@netcracker/qip-e2e/full';

export default await createSuiteConfig({
  target: 'k8s',
  adapter: fileURLToPath(new URL('./application-adapter.ts', import.meta.url)),
  kubernetes: { namespace: 'application-tests', release: 'application' },
});
```

The adapter exports a `SuiteExtension` object. Its extension points are:

| Field | Responsibility |
| --- | --- |
| `target(base)` | Return the target's provisioning, process observer, environment factory, and project configuration |
| `extendTest(base)` | Add or override fixtures used by platform specs |
| `extendVsCodeTest(base)` | Add or override fixtures used by VS Code browser specs |
| `lifecycle.schemas` | Prepare schema outputs |
| `lifecycle.startUi` / `stopUi` | Start or reuse the application's UI and release resources owned by the run |
| `lifecycle.startVsCode` / `stopVsCode` | Prepare and stop the consuming application's VS Code workbench |
| `kubernetes` | Override Kubernetes commands and deployment, container, or microservice naming |

Import the adapter type with `import type { SuiteExtension } from '@netcracker/qip-e2e/full'`.
`target` receives the selected reference adapter. You can reuse its operations and replace those that differ in the application.
Its `createEnv` returns an `Env`: service and route URLs, logs and failure evidence, service restarts and settings, broker setup and restarts, resource readings, and the owned AI recovery lifecycle.
Its `observer` records the application's deployment facts and process/resource baseline.
For remote infrastructure, provide the observer together with the environment operations.
The tests retain their assertions when an adapter changes how these operations execute.

`services` supplies service API roots; each client appends its endpoint paths.
`proxyUrl` supplies the application's UI and API proxy.
Use `request.extraHTTPHeaders` for headers shared by the platform APIs and `serviceRequests` to override them for a service.
These options accept headers only. They apply to API clients, raw platform calls, setup, and cleanup.
For private certificate authorities, use Node's trusted CA configuration for native HTTP calls.
For browser sign-in, configure Playwright with `config.use` or a fixture override.
The broker client's existing `CIP_KAFKA_URL`, `CIP_RABBITMQ_URL`, `CIP_PUBSUB_URL`, `CIP_SFTP_HOST`, and `CIP_SFTP_PORT` options remain available.
Provision broker addresses reachable from both the runner and the deployed chains, as the reference fixtures require.

## Add application tests

Put additional projects in `projects`. Their `testDir` resolves from the consuming project's working directory:

```ts
export default await createSuiteConfig({
  target: 'k8s',
  projects: [{
    name: 'application',
    testDir: './application-tests',
    dependencies: ['seed'],
  }],
});
```

An application spec can use the full suite's configured fixtures:

```ts
import { loadSuiteFixtures } from '@netcracker/qip-e2e/full';

const { test, expect } = await loadSuiteFixtures();

test('creates an application chain', async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(`e2e-${run}-application`, folder.id);
  expect((await catalog.getChain(chain.id)).id).toBe(chain.id);
});
```

Use `extendTest` in the adapter to override fixtures inside the distributed specs as well.
Import the shared page objects from `@netcracker/qip-e2e/pages`.
Existing clients, chain data, schema samples, frozen archives, and registries remain available in the prepared suite workspace.
The root package export and `/suites` also retain the lightweight registration API for the chain, folder, and HTTP-echo checks.

## Workspace and source snapshot

The package contains a compressed snapshot of the tracked source tree.
Source checks read sibling module sources, migrations, documentation, extension goldens, shell scripts, and CI configuration, so these inputs travel with the tests.
A ZIP preserves tracked resources that npm would otherwise exclude through nested ignore rules.
The snapshot records file bytes, executable modes, symlink references, package version, source revision, and a content digest.

`createSuiteConfig` extracts it into a separate writable workspace and links the installed test dependencies there.
Generated chains, corpus state, logs, and other run files belong to that workspace.
It initializes a local Git index for the checks that inspect tracked files and checkout filters; the original source revision remains in snapshot metadata.
Reuse the workspace to rerun a kept corpus. Choose another workspace when upgrading the package.
Add `.qip-e2e/` to the consuming repository's `.gitignore`.
Default reports and test artifacts are written under the prepared workspace's `e2e/` directory.
`sourceRoot` selects an existing full source checkout instead of extracting the snapshot.

For the reference UI and VS Code builds, install frontend dependencies in the prepared workspace:

```ts
import { prepareSuite } from '@netcracker/qip-e2e/full';
console.log(await prepareSuite({ workspaceDir: '.qip-e2e/platform' }));
```

```sh
npm --prefix .qip-e2e/platform ci
```

For an application's already-built frontend, supply the lifecycle operations through its adapter.
Install credentials in the consuming environment or CI secret store.
The full suite includes global mutation and restart cases, so use a dedicated test deployment.

## Build, validate, and publish

From `e2e/`:

```sh
npm ci
npm run check-types
npm run build
npm run test:package
npm pack
```

The package check installs the tarball into an external project, compares every collected case with the source suite for both targets, and runs the installed schema/harness projects.
It also tests API authentication, custom fixtures, owned cleanup, a consumer's scenario, and rejection of a corrupted runtime response.

`e2e-release.yaml` uses the shared npm release workflow and the module's own lockfile.
`release-all.yaml` includes the package; its tag is `e2e-vX.Y.Z`.
`snapshot-publish-npm.yaml` publishes branch builds under the `dev` distribution tag.
Pin package and runner versions in the consuming application's lockfile and validate the application when upgrading them.
