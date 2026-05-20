/**
 * @jest-environment jsdom
 */

import { getCumulativeStatus } from "../../../src/components/deployment_runtime_states/DeploymentsCumulativeState";

describe("getCumulativeStatus", () => {
  it("returns DRAFT for empty set", () => {
    expect(getCumulativeStatus(new Set())).toBe("DRAFT");
  });

  it("returns single status unchanged", () => {
    expect(getCumulativeStatus(new Set(["DEPLOYED"]))).toBe("DEPLOYED");
    expect(getCumulativeStatus(new Set(["FAILED"]))).toBe("FAILED");
    expect(getCumulativeStatus(new Set(["PROCESSING"]))).toBe("PROCESSING");
    expect(getCumulativeStatus(new Set(["DRAFT"]))).toBe("DRAFT");
  });

  it("returns highest priority status for multiple statuses", () => {
    // Priority: PROCESSING > FAILED > DEPLOYED > DRAFT
    expect(getCumulativeStatus(new Set(["DEPLOYED", "FAILED"]))).toBe("FAILED");
    expect(getCumulativeStatus(new Set(["DEPLOYED", "PROCESSING"]))).toBe(
      "PROCESSING",
    );
    expect(getCumulativeStatus(new Set(["FAILED", "PROCESSING"]))).toBe(
      "PROCESSING",
    );
    expect(getCumulativeStatus(new Set(["DRAFT", "DEPLOYED"]))).toBe(
      "DEPLOYED",
    );
    expect(getCumulativeStatus(new Set(["DRAFT", "FAILED", "DEPLOYED"]))).toBe(
      "FAILED",
    );
  });

  it("ignores unknown status values", () => {
    // Unknown values shouldn't break the function
    expect(getCumulativeStatus(new Set(["UNKNOWN", "DEPLOYED"]))).toBe(
      "DEPLOYED",
    );
    expect(getCumulativeStatus(new Set(["UNKNOWN"]))).toBe("UNKNOWN");
  });
});
