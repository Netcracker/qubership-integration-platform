/**
 * The declaration a product spec makes about what it closes in the coverage registry.
 *
 * It is data, not an assertion: `covers("http-trigger", "handleValidationAction", "script")`
 * records intent at the point where the work happens, and `reconcile()` reads it back out of the
 * run's JSON report afterwards. Nothing here can fail a test — a declaration naming a row that
 * does not exist fails the reconciliation instead, where the whole run is visible.
 *
 * It lives apart from `elements.ts` because it imports `@playwright/test`, and the post-run
 * reconciliation loads the registry outside Playwright.
 */
import { test } from "@playwright/test";
import { COVERS_ANNOTATION, coverageKey, type SchemaValue } from "./elements.js";

/**
 * Declare an element family, or one axis value of it, as covered by the calling test.
 *
 *     covers("condition");
 *     covers("http-trigger", "accessControlType", "RBAC");
 */
export function covers(family: string, axisPath?: string, value?: SchemaValue): void {
  test.info().annotations.push({
    type: COVERS_ANNOTATION,
    description: coverageKey(family, axisPath, value),
  });
}
