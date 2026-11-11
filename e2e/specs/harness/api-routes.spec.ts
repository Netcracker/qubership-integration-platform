/**
 * `apiPath`, the helper that turns a service path into the `/api/` path the proxy serves it on.
 *
 * `specs/schema/api-routes.spec.ts` reads the prefix table against `routes.conf`; this file covers
 * the shapes the helper refuses.
 */
import { test, expect } from "@playwright/test";
import { apiPath } from "../../env/api-routes.js";

test.describe("apiPath", () => {
  test("refuses a path shaped for another service", () => {
    expect(() => apiPath("engine", "/v1/live-exchanges")).toThrow(/engine paths/);
    expect(() => apiPath("testing-service", "/v1/swagger/doc.json")).toThrow(/testing-service paths/);
    expect(() => apiPath("sessions-management", "/sessions")).toThrow(/version segment/);
  });
});
