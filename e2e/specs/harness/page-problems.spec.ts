/**
 * The page guard's filter over what it caught: a failed checkpoint lookup of a micro domain the
 * catalog no longer lists, and its console error, are dropped, and every other problem stays.
 */
import { test, expect } from "@playwright/test";
import { MICRO_CHECKPOINTS, withoutDeletedDomains, type PageProblem } from "../../support/page-problems.js";

const LOOKUP = "http://localhost:30080/api/v1/cip/engine/e2e-x-qa/sessions?ids=s1";
const lookup: PageProblem = { route: "/admintools/sessions", message: "GET /api/v1/cip/engine/e2e-x-qa/sessions answered 502", url: LOOKUP, domain: "e2e-x-qa" };
const consoleError: PageProblem = { route: "/admintools/sessions", message: "console.error: Failed to load resource: the server responded with a status of 502 (Bad Gateway)", url: LOOKUP };
const other: PageProblem = { route: "/chains", message: "uncaught TypeError: x is undefined" };

const catalogListing = (...names: string[]) => ({ listDomains: async () => names.map((name) => ({ id: name, name, replicas: 1, namespace: "qip-e2e", type: "MICRO" })) });

test.describe("page problems", () => {
  test("drops a deleted micro domain's lookup and its console error", async () => {
    expect(await withoutDeletedDomains([lookup, consoleError, other], catalogListing("default"))).toEqual([other]);
  });

  test("keeps the lookup of a micro domain the catalog still lists", async () => {
    expect(await withoutDeletedDomains([lookup, consoleError], catalogListing("default", "e2e-x-qa"))).toEqual([lookup, consoleError]);
  });

  test("reads the domain off the micro checkpoint path only", () => {
    expect(MICRO_CHECKPOINTS.exec("/api/v1/cip/engine/e2e-x-qa/sessions")?.[1]).toBe("e2e-x-qa");
    expect(MICRO_CHECKPOINTS.exec("/api/v1/cip/engine/sessions")).toBeNull();
    expect(MICRO_CHECKPOINTS.exec("/api/v1/cip/engine/e2e-x-qa/chains/c1/sessions/s1/retry")).toBeNull();
  });
});
