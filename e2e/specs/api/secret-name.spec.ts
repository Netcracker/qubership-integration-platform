/**
 * The one live reading of the secret-name rule the run token is shaped by.
 *
 * `SecretControllerV2` exposes create and template and nothing else, and `SecretService` declares
 * no delete: every secret this suite posts is permanent. So the round trip is covered by a single
 * committed name, posted once and reused — the leak is one object over the repository's lifetime
 * rather than one per run — and the alphabet itself is pinned offline in
 * `specs/schema/run-token.spec.ts`.
 *
 * A name the regex rejects creates nothing, so the failure path is exercised with a run-token name
 * deliberately spelled wrong.
 *
 * Every call goes through the `catalog` client rather than the bare `request` fixture. A call made
 * around the client records nothing against the operation registry, and this file is the only
 * reader of `/v2/secret/{name}` and `/v2/secret/template/{name}` in the suite — so made the other
 * way, those two rows would have no run that could prove them.
 */
import { test, expect } from "../../support/fixtures.js";
import { SECRET_FIXTURE_NAME, runToken } from "../../support/run.js";
import { ensureSecretFixture } from "../../support/secret-fixture.js";

test("the committed fixture name round-trips, and creating it again is not an error", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  // Through the shared helper, which is where the tolerance for the create's race lives: another
  // worker may be posting this same committed name at this same moment, and the check-then-create
  // behind it answers 400 on the interleaving.
  await ensureSecretFixture(catalog);

  // The second create carries no race and is asserted exactly: whoever won above, the name is in
  // the store by now, so `K8sSecretService.createSecret` finds it and answers 200 without writing.
  // Idempotence is what makes reusing one name across every run possible at all.
  const again = await catalog.raw("post", `/v2/secret/${SECRET_FIXTURE_NAME}`);
  expect(again.status(), "creating a secret that is already there is not an error").toBe(200);

  // The template is served as a download named after the secret; its body is the Helm stringData
  // fragment and carries no name, so the round trip is read off the disposition header.
  const template = await catalog.raw("get", `/v2/secret/template/${SECRET_FIXTURE_NAME}`);
  expect(template.status()).toBe(200);
  expect(template.headers()["content-disposition"]).toContain(`${SECRET_FIXTURE_NAME}.yaml`);
  expect(await template.text()).toContain("stringData");
});

test("a name outside the token's alphabet is refused, and nothing is created", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  // The two mistakes a `[A-Za-z0-9]` generator makes, spelled with this run's own token so the
  // failure names something recognizable if it ever stops failing.
  const run = runToken();
  for (const name of [`E2E-${run}`, `9${run}`]) {
    const refused = await catalog.raw("post", `/v2/secret/${name}`);
    expect(refused.status(), `POST /v2/secret/${name}`).toBe(400);
    expect(await refused.text()).toContain("^[a-z]+[-a-z0-9]*$");
    // Nothing to clean up, and that is the assertion: the secret was never created.
    expect((await catalog.raw("get", `/v2/secret/template/${name}`)).status()).not.toBe(200);
  }
});
