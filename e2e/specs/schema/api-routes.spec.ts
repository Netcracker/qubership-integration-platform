/**
 * The prefix table against the nginx config it was derived from, without a stack.
 *
 * The live reading in `specs/api/api-prefixes.spec.ts` is the one that matters, and this is the
 * cheap half of the same guard: it fails when `routes.conf` is edited, naming the row whose cited
 * `location` moved, rather than leaving a spec somewhere else to fail on a 404 it will read as the
 * platform's answer.
 *
 * The Compose config is the only one this can read. The Helm chart embeds an independently
 * maintained copy in `charts/ui/templates/config.yaml`, with an extra engine-domain location and a
 * Helm-gated testing-service block, and nothing enforces the correspondence between the two.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { API_ROUTES, NOT_PROXIED, UNCLAIMED_API_PATH, apiPath } from "../../env/api-routes.js";
import { repoRoot } from "../../env/host.js";

const CONFIG = path.resolve(repoRoot(), "infrastructure/nginx/routes.conf");
const lines = fs.readFileSync(CONFIG, "utf-8").split("\n");

/** One `location ~` or `location ~*` block, as nginx will try it. */
interface RegexLocation {
  line: number;
  pattern: string;
  caseInsensitive: boolean;
}

/**
 * The regex `location` blocks, in file order — which is match order.
 *
 * nginx tries regex locations top to bottom and takes the first that matches, so the order this
 * array is built in is the order the proxy resolves a path in. Prefix locations lose to all of
 * them, which is why `location /api/` at the bottom of the file is not collected here: it is the
 * rule that claims whatever no regex did, and `UNCLAIMED_API_PATH` is what pins it.
 */
const regexLocations: RegexLocation[] = lines.flatMap((line, index) => {
  const found = /^\s*location\s+(~\*?)\s+"?([^"\s{]+)"?\s*\{/.exec(line);
  return found ? [{ line: index + 1, pattern: found[2], caseInsensitive: found[1] === "~*" }] : [];
});

/** The block that serves `apiPath`, by the rule nginx itself follows. */
function servingLocation(apiPath: string): RegexLocation | undefined {
  return regexLocations.find((location) =>
    new RegExp(location.pattern, location.caseInsensitive ? "i" : "").test(apiPath),
  );
}

test("the table is populated and the config still declares locations", { tag: ["@infra", "@tier1"] }, () => {
  // Every case below is a loop over one of these two, and a loop over nothing passes.
  expect(API_ROUTES.length).toBeGreaterThan(0);
  expect(regexLocations.length).toBeGreaterThan(0);
});

test("every row cites the location that actually serves its path", { tag: ["@infra", "@tier1"] }, () => {
  // Not "line N holds some location": every block in this file is nine lines long, so the cited
  // lines are nine apart whatever the config says, and a reordered or replaced block leaves each
  // row describing a different rule with the case still green. What is asserted instead is the
  // rule nginx would pick — the first regex location matching the row's own path — which no edit
  // to the config can satisfy by coincidence.
  for (const route of API_ROUTES) {
    const serving = servingLocation(route.api);
    expect(
      serving?.line,
      `${route.api} is served by ${CONFIG}:${serving?.line ?? "no location"} ` +
        `(${serving?.pattern ?? "-"}), and the row cites line ${route.line}`,
    ).toBe(route.line);
  }
});

test("the table covers every /api/ rule the config declares", { tag: ["@infra", "@tier1"] }, () => {
  // The other direction. Without it a `location` added to `routes.conf` is untested in silence:
  // every other case here and in `specs/api/api-prefixes.spec.ts` is a loop over the table, so a
  // rule nobody wrote a row for is a rule nobody checks.
  const cited = new Set(API_ROUTES.map((route) => route.line));
  const uncited = regexLocations
    .filter((location) => location.pattern.startsWith("^/api/"))
    .filter((location) => !cited.has(location.line))
    .map((location) => `${CONFIG}:${location.line} ${location.pattern}`);

  expect(uncited, "/api/ locations with no row in API_ROUTES").toEqual([]);
});

test("the two irregular shapes are the ones the config still produces", { tag: ["@infra", "@tier1"] }, () => {
  // The greedy match on the **last** `/catalog/`, which doubles the segment.
  expect(lines[45]).toContain('location ~ "^/api/(v\\d+)/.*/catalog/"');
  const doubled = API_ROUTES.find((route) => route.line === 46)!;
  expect(doubled.api).toContain("/catalog/catalog/");
  expect(doubled.service).toBe("/v1/catalog/runtime-deployments");

  // `v2/snapshots` intercepted and rewritten to `/v2/catalog/snapshots`, which the catch-all is not.
  expect(lines[63]).toContain('location ~ "^/api/.*/v2/snapshots"');
  expect(lines[65]).toContain("/v2/catalog/$1");
  const snapshots = API_ROUTES.find((route) => route.line === 64)!;
  expect(snapshots.service).toContain("/v2/catalog/snapshots");
});

test("the catch-all is the general form and the UI's form is not", { tag: ["@infra", "@tier1"] }, () => {
  // The catch-all strips one segment and proxies the rest to the catalog, which is why
  // `/api/qip` + a service path reaches all of it.
  expect(lines[144]).toContain("location ~ ^/api/[^/]+/(v\\d+)/");
  expect(apiPath("runtime-catalog", "/v1/systems")).toBe("/api/qip/v1/systems");
  expect(apiPath("runtime-catalog", "/v1/catalog/runtime-deployments")).toBe(
    "/api/qip/v1/catalog/runtime-deployments",
  );

  // The form the UI builds lists six families and no more; `systems` is not among them.
  expect(lines[36]).toContain("chains|detailed-design|design-generator|library|folders|cr");
  expect(NOT_PROXIED.map((each) => each.api)).toContain("/api/v1/qip/catalog/systems");
});

test("each non-catalog service is addressed through its own segment", { tag: ["@infra", "@tier1"] }, () => {
  expect(apiPath("sessions-management", "/v1/sessions")).toBe(
    "/api/v1/qip/sessions-management/sessions",
  );
  expect(apiPath("engine", "/v1/engine/live-exchanges")).toBe("/api/v1/qip/engine/live-exchanges");
  expect(apiPath("testing-service", "/api/v1/swagger/doc.json")).toBe(
    "/api/v1/qip/testing-service/swagger/doc.json",
  );
  // The rewrites these rules perform are exactly what the table's shapes encode.
  expect(lines[110]).toContain("/sessions-management/(.*)$");
  expect(lines[119]).toContain("/$1/engine/$2");
  expect(lines[137]).toContain("/api/$1/$2");
});

test("a path shaped for the wrong service is refused rather than silently mis-addressed", { tag: ["@infra", "@tier1"] }, () => {
  expect(() => apiPath("engine", "/v1/live-exchanges")).toThrow(/engine paths/);
  expect(() => apiPath("testing-service", "/v1/swagger/doc.json")).toThrow(/testing-service paths/);
  expect(() => apiPath("sessions-management", "/sessions")).toThrow(/version segment/);
});

test("the unclaimed /api/ path is claimed by a rule rather than by the SPA", { tag: ["@infra", "@tier1"] }, () => {
  // A prefix location loses to every regex above it and wins only when none matched. Without it
  // `/api/v1/nonsense` falls through and answers 200 text/html.
  expect(lines[157]).toContain("location /api/");
  expect(lines[158]).toContain("return 404");
  expect(UNCLAIMED_API_PATH.startsWith("/api/")).toBe(true);
});
