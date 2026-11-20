# Run QIP end-to-end tests against your deployment

Install `@netcracker/qip-e2e` from the npm registry configured for your project. If your organization
mirrors the package, use its mirror and the credentials it requires. Use Node.js 22.19 or later and
an ESM test project (`"type": "module"`).

```sh
npm install --save-dev --save-exact @netcracker/qip-e2e@<version> @playwright/test@1.62.1
```

## Check service health

Create `playwright.config.ts` in the project root.

```ts
import { createExternalSuiteConfig } from "@netcracker/qip-e2e/external";

export default await createExternalSuiteConfig({
  services: {
    "runtime-catalog": process.env.CATALOG_URL,
    engine: process.env.ENGINE_URL,
    "sessions-management": process.env.SESSIONS_URL,
  },
});
```

Set the URLs to the service roots. For example, set `ENGINE_URL` to
`https://engine.example.test` for a health endpoint at
`https://engine.example.test/actuator/health`. Omit a service entry to leave it out of the run.
Each entry in `services` needs an HTTP or HTTPS URL.

```sh
npx playwright test --list
npx playwright test --project=health
```

The three entries above produce three GET requests to `/actuator/health`. Each check expects an
`UP` response with both probe groups. Playwright can list the tests while the deployment is
offline. The health project needs Node.js and the service URLs.

## Select more checks

Set `suites` to the projects you want. The default is `["health"]`.

| Project | Configuration | What it exercises |
| --- | --- | --- |
| `health` | At least one entry in `services` | Spring Boot actuator health or `/health` on Testing Service |
| `catalog` | `services["runtime-catalog"]` | Folder and chain checks |
| `runtime` | Catalog, Engine, Sessions Management, and `deployment.domain` | Deploy an HTTP echo chain, call it, and verify its sessions |
| `ui` | Catalog URL, `ui` origin, and Chromium | Find an API-created chain and edit its properties in the running application |
| `testing-service` | Testing Service URL, optional `proxy` | CRUD and matchers for endpoint mocks. Set `proxy` to the QIP nginx proxy to include its mock-path denial check |
| `brokers` | Catalog, Engine, Sessions Management, `deployment.domain`, and `brokers.kafka` or `brokers.rabbitmq` | Deploy a trigger, send a message through the selected broker, and verify its session |
| `infrastructure` | Catalog, Engine, Sessions Management, `deployment.domain`, and a dedicated stand with an infrastructure adapter | Restart the engine and verify that its deployed echo route comes back with the same deployment |

Set `routePrefix` when the engine serves chain routes under a prefix other than `routes`. Use
`"/"` for routes directly under the engine base URL. Set `deployment.domainType` if the domain needs it. For `ui`, install Chromium with
`npx playwright install chromium`. The UI may query Testing Service on startup in development
mode. Configure a production-mode frontend when Testing Service is absent, or expose its mode
endpoint through the running frontend's proxy.

For brokers, `host` is the runner-side Kafka bootstrap address or RabbitMQ AMQP URL. `engine` is
the address that the deployed trigger uses. Supply RabbitMQ's `username` and `password` from the
environment. Kafka uses a plaintext listener. The runner creates unique topics or queues for its
own trigger and deletes that topology when it finishes.

Select `infrastructure` in a separate run. Set
`infrastructure: { adapter: "./qip-infrastructure.ts", dedicatedStand: true }` on a deployment that
the run can restart. Export an adapter with `restart("engine")` and `processEnvelope("engine")`.
The restart promise resolves when the engine is serving again. `processEnvelope` reports the
running process before and after the restart.
Supply `engineLogs(since)` or `logs("engine", since)` to check that the restart logs the run's
deployment without errors. If the adapter provides `resourcePeaks()`, the suite checks that the
engine peak comes from at least two samples.

For mutable projects, use a test deployment and credentials with permission to create and remove
test data. The suite creates its own folders, chains, mocks, sessions, and broker topology as
needed. Each run writes artifacts to `.qip-e2e/runs/<run-id>/`. If a run is interrupted or cleanup
fails, the `owned/` records name resources to inspect and remove. Add `.qip-e2e/` to your ignore file.

## Sign in to a protected deployment

Set `auth: "./qip-auth.ts"` in the config and export an `ExternalAuth` object from that file.
Provide the sections your deployment uses. The runner opens the module when a selected test
needs credentials.

```ts
import type { ExternalAuth } from "@netcracker/qip-e2e/external";
import { loginToApplication, requestApiToken } from "./application-auth.js";

export default {
  api: {
    async getHeaders({ service, parallelIndex }) {
      const { token, expiresAt } = await requestApiToken(service, parallelIndex);
      return { headers: { Authorization: `Bearer ${token}` }, expiresAt };
    },
  },
  ui: {
    async login({ page, baseURL, parallelIndex }) {
      return await loginToApplication(page, baseURL, parallelIndex);
    },
  },
} satisfies ExternalAuth;
```

Implement the two imported functions with your application's login flow and test accounts.
Keep credentials in environment variables or your CI secret store. Wait for an authenticated page
before returning from `login`. If another login invalidates the current session, assign one test
account per `parallelIndex` and coordinate account usage across concurrent runs. The context
also supplies the run ID.

For API tokens, return `expiresAt` as Unix time in milliseconds. The transport refreshes expiring
credentials before the next request and shares an in-progress refresh within a worker and phase.
Use `phase` to choose credentials for setup, test requests, or cleanup. Without an expiry, the
transport calls the provider for each request. Static credentials can use `api.headers`, keyed by
service. The transport scopes credentials to configured service and QIP proxy paths and returns
redirects and authorization failures to the caller. For Helm proxy routes that use custom
Service names, configure the exact `/e2e/svc/<service>` base URL for each role in `services`.

For the browser, return `{ expiresAt }` from `login` when the session has a known expiry, or supply
`ui: { storageState: "./playwright/.auth/user.json" }`. Paths to storage state resolve from the auth
module. The runner reuses a login within its worker and renews expired state before applying it to the next
test context. The login context inherits the project's browser options.

With `auth` set, the default report includes case outcomes and sanitized authorization diagnostics.
The runner discards per-test artifacts, including Playwright error-context snapshots. Set `sensitiveArtifacts: true` to use ordinary Playwright
reporters and capture options when debugging credentials. Handle those artifacts as secrets. Keep
provider logs free of credentials and keep storage state outside report directories and source control.

## Add application tests

Add projects to the external configuration. Leave the built-in projects in `suites`.

```ts
projects: [{ name: "application", testDir: "./application-tests" }],
```

A spec can load the configured API fixtures.

```ts
import { loadExternalFixtures } from "@netcracker/qip-e2e/external";

const { test, expect } = await loadExternalFixtures();

test("reads the catalog health endpoint", async ({ env, request }) => {
  const response = await request.get(`${env.url("runtime-catalog")}/actuator/health`);
  expect(response.ok()).toBe(true);
});
```

Use `catalog`, `engine`, `sessions`, and `testingService` with their corresponding
`services` entries. The `folder` fixture creates an owned folder only when a test requests it. For a browser
project, set `ui` and import `loadExternalUiFixtures` to use the configured UI login and page guard.
Supply the Catalog URL for projects that request the `catalog` or `folder` fixture.
The root export supports standalone fixtures configured through `use.qip`. Import case
registrations from `@netcracker/qip-e2e/suites` and page objects from `@netcracker/qip-e2e/pages`.

## Run the full suite

The package carries the scenario and fixture bundle. The full suite also needs the corresponding
platform checkout for source checks, provisioning, and builds. It runs every original project,
including the full UI and broker suites, global checks, restarts, Kubernetes and Micro Engine,
VS Code, and the AI projects. Use a dedicated environment for a full run.

Prepare the checkout's dependencies as described in the [repository run guide](README.md).
In a consumer config, select the checkout explicitly.

```ts
import { createSuiteConfig } from "@netcracker/qip-e2e/full";

export default await createSuiteConfig({
  target: "compose",
  sourceRoot: "../qubership-integration-platform",
});
```

Run with the checkout's Playwright installation so it loads one copy of the runner.

```sh
node ../qubership-integration-platform/e2e/node_modules/@playwright/test/cli.js test --list
```

For additional full-suite projects, import their `test` and `expect` through `loadSuiteFixtures()`.
Any imports of Playwright at runtime in the consumer config, adapters, or tests must resolve to
the same runner installation as the checkout. Page objects from `@netcracker/qip-e2e/pages`
work with the checkout's runner. Reports identify the npm package build and checkout
revisions. Setup, teardown, and default reports use the checkout's `e2e/` directory.

The `/full` entry still supports `SuiteExtension` adapters, service and proxy URL overrides, static
request headers, and Kubernetes overrides. Select the optional AI recovery case on Compose with
`E2E_AI_RECOVERY=1` and provide the private fixture or lifecycle adapter described in the
[repository run guide](README.md#flags). The full mode uses its original reference
project graph, including the setup projects on selective runs.

## Verify a package build

Run from `e2e/`.

```sh
npm ci
npm run check-types
npm pack
npm run test:package -- --tarball=./netcracker-qip-e2e-<version>.tgz
```

The package test installs that tarball in a clean project and runs cases against local HTTP test
services. It also verifies full-suite collection against the checkout for both reference targets.
The continuous integration release and dev workflows publish the tarball they tested.
