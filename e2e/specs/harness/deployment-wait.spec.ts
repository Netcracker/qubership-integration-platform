import { test, expect } from "@playwright/test";
import type { Catalog, RuntimeDeployment } from "../../support/catalog.js";
import { waitForDeployed } from "../../support/corpus.js";

const chains = [
  { id: "ready", name: "ready-chain", contextPath: "" },
  { id: "pending", name: "pending-chain", contextPath: "" },
];

function delayedCatalog(): Catalog {
  let calls = 0;
  return {
    async runtimeDeployments() {
      calls += 1;
      return {
        ready: [{ status: "DEPLOYED" }],
        pending: [{ status: calls > 1 ? "DEPLOYED" : "PROCESSING" }],
      } as unknown as Record<string, RuntimeDeployment[]>;
    },
  } as Catalog;
}

test.describe("deployment wait", () => {
  test("uses the caller's budget and names the pending chain", async () => {
    await expect(waitForDeployed(delayedCatalog(), chains, 50)).rejects.toThrow("pending-chain (PROCESSING)");
  });

  test("waits for every chain with the default budget", async () => {
    await waitForDeployed(delayedCatalog(), chains);
  });
});
