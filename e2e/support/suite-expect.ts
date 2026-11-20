import { createRequire } from "node:module";
import path from "node:path";

export function suiteExpect(): typeof import("@playwright/test").expect {
  // The full suite runs with the checkout's Playwright.
  const checkout = process.env.QIP_E2E_WORKSPACE;
  const requireRunner = createRequire(checkout ? path.join(checkout, "e2e/package.json") : import.meta.url);
  return (requireRunner("@playwright/test") as typeof import("@playwright/test")).expect;
}
