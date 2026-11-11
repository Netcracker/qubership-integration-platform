import path from "node:path";
import { defineConfig } from "@playwright/test";
import base from "./playwright.config.js";
const output = process.env.E2E_HTTP_DIAGNOSTIC_DIR!;
export default defineConfig({
  ...base,
  outputDir: path.join(output, "probe-results"),
  reporter: [["list"], ["json", { outputFile: path.join(output, "probe-report.json") }], ["json", { outputFile: "test-results/report.json" }]],
  projects: [{ name: "api", testDir: "./specs/api", testMatch: "http-folders-diagnostic.spec.ts", fullyParallel: true }],
});
