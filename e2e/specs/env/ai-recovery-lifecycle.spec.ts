import { test, expect } from "../../support/fixtures.js";

test.describe("AI recovery lifecycle", { tag: ["@infra"] }, () => {
  test("stops its stores and exports evidence", async ({ env }, testInfo) => {
    test.setTimeout(360_000);
    const report = await env.verifyAiRecoveryLifecycle(
      testInfo.outputPath("recovery-lifecycle"),
    );
    expect(report.privateStorage).toBe(true);
    expect(report.artifactReadAfterStop).toBe(true);
    expect(report.retainedStoresStopped).toBe(true);
    expect(report.sharedUnchanged).toBe(true);
    expect(report.modelRequests).toBe(0);
    await testInfo.attach("recovery lifecycle", {
      body: JSON.stringify(report, null, 2),
      contentType: "application/json",
    });
  });
});
