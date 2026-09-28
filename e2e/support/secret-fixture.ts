/** The committed fixture secret, created on demand by every case that writes a secured variable. */
import { expect } from "@playwright/test";
import type { Catalog } from "./catalog.js";
import { SECRET_FIXTURE_NAME } from "./run.js";

/**
 * Creates the committed fixture secret, tolerating the workers creating it at the same moment.
 *
 * `K8sSecretService.createSecret` checks and then creates, and the store behind it refuses a name
 * that appeared in between: `LocalDevKubeSecretOperator.createSecret` calls `putIfAbsent` and
 * throws `SecretAlreadyExists`, which `GlobalExceptionHandler` maps to **400**. Cases in the `api`
 * and `ui` projects post this name from parallel workers, and the `env` project recreates the
 * catalog at the end of every run, so the in-process map starts empty on every run and the race is
 * live on every run. The conflict counts as success; every other status still fails.
 *
 * One implementation for every caller, because the tolerance is the contract: a second copy that
 * pinned 200 would go red on the interleaving this one exists to absorb.
 */
export async function ensureSecretFixture(catalog: Catalog): Promise<void> {
  const response = await catalog.raw("post", `/v2/secret/${SECRET_FIXTURE_NAME}`);
  if (response.status() === 200) return;
  const body = await response.text();
  expect(response.status(), `POST /v2/secret/${SECRET_FIXTURE_NAME}: ${body}`).toBe(400);
  expect(body, "the only refusal this fixture tolerates is the name already being there").toContain(
    "already exists",
  );
}
