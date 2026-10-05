/**
 * What each service says about itself and its dependencies, asserted once so the run can name the
 * thing that is down.
 *
 * Provisioning already waits on health, but waiting is not asserting: it polls until the endpoint
 * answers 200 and then never looks again. So a stack where the catalog is up and Consul is not
 * reaches the report as a dozen unrelated red specs, and the reader reconstructs the cause from
 * whichever one happened to fail first. These cases run early in the `api` project and say it
 * directly.
 *
 * Two shapes, measured:
 *
 * - The three Java services serve Spring Boot's actuator at `/actuator/health`. Both probe groups
 *   are enabled (`management.endpoint.health.probes.enabled: true`), so the document carries
 *   `status` and `groups: ["liveness", "readiness"]`, and each group answers on its own path.
 * - The testing service is Go and Fiber, with one `/health` at the root and no actuator at all. It
 *   answers `{"status":"UP"}` — the same key, not the same endpoint, which is why the two are not
 *   one loop.
 *
 * Readiness is the group that carries a dependency, and each Java service contributes its own to
 * it: `CompiledLibrariesHealthIndicator` reports `REFUSING_TRAFFIC` on the catalog until the
 * compiled-library init has finished, and `DeploymentsHealthIndicator` does the same on the engine
 * until deployments have been collected. A `readiness` that is not UP is therefore a real statement
 * about a dependency, not a restatement of liveness.
 *
 * **What the platform publishes.** Spring's default for `management.endpoint.health.show-details`
 * is `never`, and under it the document carries no `components` map at all — so a per-component
 * assertion reads an empty object and passes whatever the stack is doing.
 * `infrastructure/qip-dev.env` therefore sets `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always`,
 * which is what makes the map, and the per-component paths under it, exist to be read.
 *
 * Measured on the stack that variable configures, the map is smaller than the dependency list
 * suggests. Every service publishes `consul`, `diskSpace`, `livenessState`, `ping`,
 * `readinessState`, `refreshScope` and `ssl`; the catalog adds `compiledLibraries` and the engine
 * adds `deployments`. **Postgres and OpenSearch contribute no indicator** — `/actuator/health/db`
 * and `/actuator/health/opensearch` answer 404 on every service — so Consul is the one shared
 * dependency this reading can name, and the two custom indicators are the only per-service ones.
 */
import { test, expect } from "../../support/fixtures.js";
import type { APIRequestContext } from "@playwright/test";
import type { ServiceRole } from "../../env/index.js";
import { COMPONENT_TAG } from "./constants.js";

/** The three that run Spring Boot. The fourth is Go and has no actuator. */
const ACTUATOR_SERVICES: ServiceRole[] = ["runtime-catalog", "engine", "sessions-management"];

/** Both are enabled explicitly in every service's `application.yml`, so both are a contract. */
const PROBE_GROUPS = ["liveness", "readiness"];

/**
 * The health indicator a service contributes beyond the ones Spring and Consul register.
 *
 * `CompiledLibrariesHealthIndicator` and `DeploymentsHealthIndicator` are the two the readiness
 * group is built on. Sessions management contributes none, and an entry for it would assert a
 * component that has never existed.
 */
const OWN_INDICATOR: Partial<Record<ServiceRole, string>> = {
  "runtime-catalog": "compiledLibraries",
  engine: "deployments",
};

interface HealthDocument {
  status?: string;
  groups?: string[];
  components?: Record<string, { status?: string }>;
}

async function health(request: APIRequestContext, url: string): Promise<HealthDocument> {
  const response = await request.get(url, { failOnStatusCode: false });
  // 503 is the answer that matters here: the endpoint is reachable and the service is refusing
  // traffic, so the body is still a health document and the status alone names the failure.
  expect(response.status(), `${url} — is the stack up?`).toBe(200);
  return (await response.json()) as HealthDocument;
}

for (const role of ACTUATOR_SERVICES) {
  test(`${role} reports UP and declares both probe groups`, { tag: [COMPONENT_TAG[role], "@tier1"] }, async ({ request, env }) => {
    const document = await health(request, `${env.url(role)}/actuator/health`);

    expect(document.status).toBe("UP");
    expect([...(document.groups ?? [])].sort()).toEqual(PROBE_GROUPS);
  });

  test(`${role} names no component as down`, { tag: [COMPONENT_TAG[role], "@tier1"] }, async ({ request, env }) => {
    const document = await health(request, `${env.url(role)}/actuator/health`);

    // The gate on the reading below rather than a restatement of it: an absent map makes `down`
    // empty whatever the service is doing, so a bare `toEqual([])` would pass on a stack whose
    // containers predate the env file and never saw the variable.
    const components = Object.keys(document.components ?? {});
    expect(
      components,
      `${role} publishes no components: the container is older than ` +
        `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS in infrastructure/qip-dev.env, and recreating it ` +
        `is what picks the variable up`,
    ).not.toEqual([]);

    // Consul is the one shared dependency with an indicator, and the custom one is what carries
    // this service's own readiness. Both are named, because a map that has lost either still
    // satisfies "nothing is down".
    expect(components, `${role} stopped reporting Consul`).toContain("consul");
    const own = OWN_INDICATOR[role];
    if (own) expect(components, `${role} stopped reporting ${own}`).toContain(own);

    // Names the component rather than asserting a count, so the failure reads "consul" and not
    // "expected 4 to be 5".
    const down = Object.entries(document.components ?? {})
      .filter(([, component]) => component.status !== "UP")
      .map(([name]) => name);
    expect(down, `${role} reports these components as not UP`).toEqual([]);
  });

  test(`${role} answers both probe groups`, { tag: [COMPONENT_TAG[role], "@tier1"] }, async ({ request, env }) => {
    for (const group of PROBE_GROUPS) {
      const document = await health(request, `${env.url(role)}/actuator/health/${group}`);
      expect(document.status, `${role} ${group}`).toBe("UP");
    }
  });
}

test("the testing service answers its own /health", { tag: [COMPONENT_TAG["testing-service"], "@tier1"] }, async ({ request, env }) => {
  const document = await health(request, `${env.url("testing-service")}/health`);
  expect(document.status).toBe("UP");

  // Not an actuator, and worth pinning: a spec that assumed the Spring path would poll a 404
  // forever and report the service down while it was serving.
  const actuator = await request.get(`${env.url("testing-service")}/actuator/health`, {
    failOnStatusCode: false,
  });
  expect(actuator.status()).toBe(404);
});

/**
 * The one other thing the Go service says about itself, asserted here rather than only read.
 *
 * `support/report.ts` reads `GET /api/v1/mode` in `globalSetup` for every run's stack header, and
 * `noteReached()` is silent there — `test.info()` throws outside a test. So for as long as the run
 * header was the row's only caller, its status could be checked in neither direction, and the
 * operation registry carried it as its one sanctioned "reached but unrecordable" exception. This
 * case is what retires the exception: one call through the recording transport, asserting the
 * document the header renders.
 *
 * `production` is a hint for the front end and nothing more: `service_mode_controller.go` reports the
 * flag and every endpoint answers the same in either mode. That is by design, and PR #775
 * (issue #774) is where the endpoint's own description was corrected to say so. The value is pinned
 * and not only its type, because an installation that names no
 * mode is a **production** one — `Config.ProductionMode` reads an unset flag as `true` — so `false`
 * is a positive statement about `PRODUCTION_MODE: "false"` on `qip-testing-service` in
 * `infrastructure/docker-compose.yml` rather than the default it would fall back to.
 */
test("the testing service reports the installation mode", { tag: [COMPONENT_TAG["testing-service"], "@tier1"] }, async ({ testingService }) => {
  const response = await testingService.raw("get", "/api/v1/mode");

  expect(response.status()).toBe(200);
  expect(response.headers()["content-type"]).toContain("application/json");

  const body = (await response.json()) as Record<string, unknown>;
  // The whole key set rather than one field: the stack header renders this document, and a field
  // added to it is a contract change rather than a detail.
  expect(Object.keys(body).sort()).toEqual(["production"]);
  expect(body.production, "the compose stack passes PRODUCTION_MODE=false").toBe(false);
});
