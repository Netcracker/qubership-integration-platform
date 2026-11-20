// Shown failing: returning DOWN from the stub fails the UP assertion.
import { ROLES } from "../../env/containers.js";
import { externalSettings } from "../../env/external-settings.js";
import { expectActuatorUp, expectTestingServiceUp } from "../../support/health.js";
import { test } from "../../support/fixtures.js";

test.describe("external health", () => {
  const settings = externalSettings()!;
  for (const role of ROLES) {
    const base = settings.services[role];
    if (!base) continue;
    const check = role === "testing-service" ? expectTestingServiceUp : expectActuatorUp;
    test(`${role} reports healthy`, async ({ request }) => {
      await check(request, base, role);
    });
  }
});
