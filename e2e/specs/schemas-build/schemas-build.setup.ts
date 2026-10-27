/**
 * Builds `schemas/` when its sources are newer than its outputs, before `ui-server` bundles the UI.
 *
 * Collected by `testMatch: /.*\.setup\.ts$/` on the `schemas-build` project, for the reason the
 * `seed` project gives: a setup project that collects zero tests satisfies its dependents.
 */
import { test } from "@playwright/test";
import { ensureSchemas } from "../../support/schemas-build.js";

test("the schemas are built from their sources", { tag: ["@infra"] }, async () => {
  const built = await ensureSchemas();
  console.log(built ? "[schemas-build] built schemas/" : "[schemas-build] schemas/ is current");
});
