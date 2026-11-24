// Shown failing: returning DOWN from the stub fails the UP assertion.
import { test as base } from "@playwright/test";
import { ROLES } from "../../env/containers.js";
import { externalSettings } from "../../env/external-settings.js";
import { authenticatedRequest } from "../../env/platform-http.js";
import { expectActuatorUp, expectTestingServiceUp } from "../../support/health.js";

const test = base.extend({
  request: async ({ request }, use) => { await use(authenticatedRequest(request)); },
});

const tags = {
  "runtime-catalog": "@catalog",
  engine: "@engine",
  "sessions-management": "@sessions",
  "testing-service": "@testing-service",
};

test.describe("external health", () => {
  const settings = externalSettings()!;
  for (const role of ROLES) {
    const base = settings.services[role];
    if (!base) continue;
    const check = role === "testing-service" ? expectTestingServiceUp : expectActuatorUp;
    test(`${role} reports healthy`, { tag: tags[role] }, async ({ request }) => {
      await check(request, base, role);
    });
  }
});
