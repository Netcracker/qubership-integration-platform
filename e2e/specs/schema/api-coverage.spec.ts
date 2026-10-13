/**
 * The API gap detector: the operation registry against the cached inventory of what the services
 * serve.
 *
 * No stack. It reads `registry/operations.cache.json`, which `npm run refresh-operations` writes
 * from a running stack, so a controller that lands in the catalog surfaces here as a row nobody
 * has declared rather than as coverage nobody notices is missing.
 *
 * This reading alone would only prove the registry tracks the cache — the direction that was
 * already safe. `specs/api/api-docs-live.spec.ts` closes the other one.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import {
  cachedOperationKeys,
  entryKey,
  loadCachedOperations,
  matchOperationPath,
  operationRegistry,
  summarizeOperations,
  validateOperationRegistry,
  type OperationEntry,
} from "../../registry/operations.js";

const registryKeys = new Set(operationRegistry.map(entryKey));

test("the operation registry is populated and its own shape holds", { tag: ["@infra", "@tier1"] }, () => {
  // A gap detector that iterates nothing passes for the worst possible reason. The floor is over
  // the four services the platform runs, not over the catalog: 243 + 14 + 6 + 40 = 303 measured on
  // this stack, and a floor under 263 would pass a registry that had quietly lost the testing
  // service again.
  expect(operationRegistry.length).toBeGreaterThan(290);
  expect(validateOperationRegistry(operationRegistry)).toEqual([]);
});

test("the cache holds an inventory for each of the four services", { tag: ["@infra", "@tier1"] }, () => {
  const cache = loadCachedOperations();
  // Four keys, exactly. A refresh run against a stack missing one service writes three, and this
  // is the case that says so before the two below report its rows as endpoints the platform no
  // longer serves.
  expect(Object.keys(cache.services).sort()).toEqual([
    "catalog",
    "engine",
    "sessions-management",
    "testing-service",
  ]);
  // Measured on main: 243 catalog, 6 engine, 14 sessions-management, 40 testing-service. Floors,
  // not counts — the two cases below already pin the cache against the registry operation by
  // operation, and the drift detector for what the services actually serve is
  // `specs/api/api-docs-live.spec.ts`. An exact number here would fail a no-stack gate for an
  // engine controller gaining an endpoint, and read as a truncated cache rather than as the growth
  // it is.
  expect(cache.services.catalog.length).toBeGreaterThan(200);
  expect(cache.services.engine.length).toBeGreaterThan(4);
  expect(cache.services["sessions-management"].length).toBeGreaterThan(10);
  expect(cache.services["testing-service"].length).toBeGreaterThan(30);
});

test("every operation the services serve has a registry row", { tag: ["@infra", "@tier1"] }, () => {
  const missing = cachedOperationKeys().filter((key) => !registryKeys.has(key));

  expect(missing, "operations with no registry row").toEqual([]);
});

test("no registry row names an operation the services no longer serve", { tag: ["@infra", "@tier1"] }, () => {
  const served = new Set(cachedOperationKeys());
  const stale = operationRegistry.map(entryKey).filter((key) => !served.has(key));

  expect(stale, "registry rows the services no longer serve").toEqual([]);
});

test("every gap carries a reason", { tag: ["@infra", "@tier1"] }, () => {
  for (const entry of operationRegistry) {
    if (entry.status !== "not-reached") continue;
    expect(entry.reason?.trim(), `${entryKey(entry)} has no reason`).toBeTruthy();
  }
});

test("every file a reason names is a file that exists", { tag: ["@infra", "@tier1"] }, () => {
  // A gap that says who already calls the endpoint says it by name, because a generic reason over
  // an endpoint a passing spec reaches files finished work as a gap. A name is only worth
  // reading while it resolves: rename or delete the spec and the reason points at nothing, and a
  // reader chasing it concludes the row is stale rather than that the reference is.
  const namesAFile = /[\w./-]+\.ts/g;
  const named = new Set<string>();
  for (const entry of operationRegistry) {
    for (const file of entry.reason?.match(namesAFile) ?? []) named.add(file);
  }

  // A regex that stops matching turns this case into one that iterates nothing and passes. The
  // floor used to be a count of rows: five while a sessions row named `support/fixtures.ts` for the
  // run-token sweep, then four, then none, then one again while `GET /api/v1/mode` named
  // `support/report.ts` as its caller. Covering that row retired the last one, and the number is not
  // worth restoring at any value: a reason naming a file is what the registry writes when a passing
  // spec already reaches a row, and the fix for such a row is a transport, so the honest floor is
  // zero. The anti-vacuity guard is therefore the regex itself, held against a reason written here.
  const probe = "reached by specs/api/error-contract.spec.ts, outside support/catalog.ts";
  expect(probe.match(namesAFile), "the reason regex no longer finds a file name").toEqual([
    "specs/api/error-contract.spec.ts",
    "support/catalog.ts",
  ]);
  for (const file of [...named].sort()) {
    const full = path.resolve(repoRoot(), "e2e", file);
    expect(fs.existsSync(full), `a reason names ${file}, which is not a file`).toBe(true);
  }
});

test("a not-reached entry with no reason is rejected", { tag: ["@infra", "@tier1"] }, () => {
  const orphan: OperationEntry = {
    service: "catalog",
    method: "GET",
    path: "/v1/chains",
    controller: "chain-controller",
    status: "not-reached",
  };

  expect(validateOperationRegistry([orphan])).toEqual([
    "catalog GET /v1/chains: status is not-reached and no reason is given",
  ]);
  expect(validateOperationRegistry([{ ...orphan, reason: "   " }])).toHaveLength(1);
  expect(validateOperationRegistry([{ ...orphan, reason: "no case was written for it" }])).toEqual([]);
});

test("the shape check catches a malformed row rather than only a missing reason", { tag: ["@infra", "@tier1"] }, () => {
  const base: OperationEntry = {
    service: "catalog",
    method: "GET",
    path: "/v1/chains",
    controller: "chain-controller",
    status: "reached",
  };

  expect(validateOperationRegistry([base, base])).toContain(
    "duplicate row: catalog GET /v1/chains",
  );
  expect(validateOperationRegistry([{ ...base, method: "FETCH" }])).toContain(
    "catalog FETCH /v1/chains: FETCH is not an HTTP method",
  );
  expect(validateOperationRegistry([{ ...base, path: "v1/chains" }])).toContain(
    'catalog GET v1/chains: the path does not start with "/"',
  );
});

test("reached and covered are two numbers and are never added together", { tag: ["@infra", "@tier1"] }, () => {
  const rows: OperationEntry[] = [
    { service: "catalog", method: "GET", path: "/a", controller: "c", status: "covered" },
    { service: "catalog", method: "GET", path: "/b", controller: "c", status: "reached" },
    {
      service: "catalog",
      method: "GET",
      path: "/c",
      controller: "c",
      status: "not-reached",
      reason: "nobody has been here",
    },
  ];

  // A contract assertion implies the call, so a covered row counts as reached as well — and the
  // two are still reported apart, because summing them is how a suite claims to have proved a
  // contract it only pinged.
  expect(summarizeOperations(rows)).toEqual({
    total: 3,
    notReached: 1,
    reached: 2,
    covered: 1,
  });
});

test("the summary holds at both ends of a registry's life", { tag: ["@infra", "@tier1"] }, () => {
  // Hand-built rows with hand-computed totals, for the same reason as the case above: read off
  // `operationRegistry` these numbers restate how `summarizeOperations` is written — `total` is the
  // row count, `reached` is what is left after `not-reached`, and `covered` is one of the terms
  // `reached` is summed from — so no edit to the function could make them disagree.
  const seeded: OperationEntry[] = ["/a", "/b", "/c"].map((route) => ({
    service: "catalog",
    method: "GET",
    path: route,
    controller: "c",
    status: "not-reached",
    reason: "nobody has been here",
  }));

  // Where this registry started: every row a gap, and `reached` is a zero rather than a total.
  expect(summarizeOperations(seeded)).toEqual({
    total: 3,
    notReached: 3,
    reached: 0,
    covered: 0,
  });

  // The other end, and the arithmetic worth pinning: three covered rows are three reached rows and
  // not six, because a contract assertion implies the call it is made about.
  const proven = seeded.map((entry) => ({ ...entry, status: "covered" as const, reason: undefined }));
  expect(summarizeOperations(proven)).toEqual({
    total: 3,
    notReached: 0,
    reached: 3,
    covered: 3,
  });

  expect(summarizeOperations([])).toEqual({ total: 0, notReached: 0, reached: 0, covered: 0 });
});

// ---------------------------------------------------------------------------
// Matching a request back to a row
// ---------------------------------------------------------------------------

/**
 * The rows the cases below match against, hand-built for the same reason the summary cases are:
 * read off `operationRegistry` these assertions would restate whatever the registry happens to
 * hold today, and the tie-break is a property of the function rather than of the rows.
 */
const routing: OperationEntry[] = [
  { service: "sessions-management", method: "GET", path: "/v1/sessions/export", controller: "session-controller", status: "reached" },
  { service: "sessions-management", method: "GET", path: "/v1/sessions/{sessionId}", controller: "session-controller", status: "reached" },
  { service: "sessions-management", method: "DELETE", path: "/v1/sessions/{sessionId}", controller: "session-controller", status: "reached" },
  { service: "catalog", method: "GET", path: "/v1/chains/{chainId}/elements/{elementId}", controller: "element-controller", status: "reached" },
];

test("a concrete request matches the row that serves it", { tag: ["@infra", "@tier1"] }, () => {
  // This is the whole of `reached`: without it every transport call records nothing and the
  // operation half of `npm run reconcile` checks an empty set.
  expect(matchOperationPath("catalog", "get", "/v1/chains/abc/elements/def", routing)).toBe(
    "catalog GET /v1/chains/{chainId}/elements/{elementId}",
  );
  // The verb is part of the row, and two rows share this path.
  expect(matchOperationPath("sessions-management", "DELETE", "/v1/sessions/xyz", routing)).toBe(
    "sessions-management DELETE /v1/sessions/{sessionId}",
  );
  // A query string and a fragment are not part of the path.
  expect(
    matchOperationPath("sessions-management", "GET", "/v1/sessions/xyz?size=5#top", routing),
  ).toBe("sessions-management GET /v1/sessions/{sessionId}");
});

test("a literal template wins over a parameter that would also match", { tag: ["@infra", "@tier1"] }, () => {
  // `/v1/sessions/export` and `/v1/sessions/{sessionId}` both match a request for
  // `/v1/sessions/export`. The tie-break was asserted only in a comment beside the loop, so the
  // export call was one edit away from being recorded against the session-by-id row instead.
  expect(matchOperationPath("sessions-management", "GET", "/v1/sessions/export", routing)).toBe(
    "sessions-management GET /v1/sessions/export",
  );
  // Order in the array must not decide it either.
  expect(
    matchOperationPath("sessions-management", "GET", "/v1/sessions/export", [...routing].reverse()),
  ).toBe("sessions-management GET /v1/sessions/export");
});

test("a path outside the registry matches nothing rather than the nearest row", { tag: ["@infra", "@tier1"] }, () => {
  // `undefined` is not a failure. `/actuator/health` and every service outside the four documents
  // are no claim about any row, and recording one would invent a problem for the reconciliation.
  expect(matchOperationPath("catalog", "GET", "/actuator/health", routing)).toBeUndefined();
  // A different segment count is a different endpoint, however similar it reads.
  expect(matchOperationPath("sessions-management", "GET", "/v1/sessions", routing)).toBeUndefined();
  expect(
    matchOperationPath("sessions-management", "GET", "/v1/sessions/xyz/elements", routing),
  ).toBeUndefined();
  // The service is part of the key, so the same path on another service is another row.
  expect(matchOperationPath("catalog", "GET", "/v1/sessions/export", routing)).toBeUndefined();
});
