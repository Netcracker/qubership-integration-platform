/**
 * The optional-overlay table `Env.ensureOverlay` reads, and the failure a spec sees for a name that
 * is not in it.
 *
 * No stack: `overlayDefinition` and `overlayNames` are plain lookups over a static table, so this
 * proves the naming and the error text without a broker or even Docker running. `ensureOverlay`
 * itself calls `overlayDefinition` before it runs a single Compose command — see `env/compose.ts` —
 * so this is also what stands behind that ordering: a bad overlay name fails immediately rather than
 * through a deployment that retries against a broker nothing ever started.
 */
import path from "node:path";
import fs from "node:fs";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { overlayDefinition, overlayNames } from "../../env/compose.js";
import { repoRoot } from "../../env/host.js";

test("the adapter knows exactly the four broker overlays the suite starts", { tag: ["@infra", "@tier1"] }, async () => {
  expect(overlayNames()).toEqual(["kafka", "pubsub", "rabbitmq", "sftp"]);
});

for (const name of overlayNames()) {
  test(`overlay ${name} resolves to its own compose file and at least one service`, { tag: ["@infra", "@tier1"] }, async () => {
    const definition = overlayDefinition(name);
    expect(definition.name).toBe(name);
    expect(definition.services.length).toBeGreaterThan(0);
    // `restartOverlay` restarts exactly this one service, so it has to be a service the overlay
    // actually starts — Kafka's overlay is three services, and a `primary` outside that list would
    // fail `docker compose restart` with a name nothing declares.
    expect(definition.services).toContain(definition.primary);
    // The overlay file has to exist beside the base compose file, or `ensureOverlay` would fail on a
    // Compose error that names no overlay and no broker — a worse message than this one.
    const file = path.join(repoRoot(), "infrastructure", definition.file);
    expect(fs.existsSync(file), `${file} does not exist`).toBe(true);

    // `definition.services` and `definition.primary` are checked against the YAML they claim to
    // describe, not only against each other: without this, a service renamed in the compose file
    // (or in this table) produces a `docker compose` error mid-seed instead of a red case here.
    const parsed = yaml.load(fs.readFileSync(file, "utf-8")) as { services?: Record<string, unknown> };
    const declared = Object.keys(parsed.services ?? {}).sort();
    expect(
      [...definition.services].sort(),
      `${definition.file} declares ${JSON.stringify(declared)}`,
    ).toEqual(declared);
  });
}

// The mutation check: point the adapter at a broker name that does not exist and
// confirm setup fails with that message, rather than a deployment retrying silently until the
// seed's own poll times out.
test("an unknown overlay name fails immediately, naming every overlay the adapter knows", { tag: ["@infra", "@tier1"] }, async () => {
  expect(() => overlayDefinition("not-a-real-broker")).toThrow(
    'no overlay named "not-a-real-broker". Known overlays: kafka, pubsub, rabbitmq, sftp',
  );
});

test("the same failure holds for an empty name", { tag: ["@infra", "@tier1"] }, async () => {
  expect(() => overlayDefinition("")).toThrow(/no overlay named ""/);
});
