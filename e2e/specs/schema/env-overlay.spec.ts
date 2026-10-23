/**
 * The optional-overlay table `Env.ensureOverlay` reads, against the compose files and the kind port
 * mapping it describes.
 *
 * No stack: a service renamed in a compose file, or a port moved, turns a case here red instead of
 * failing a `docker compose` call mid-seed.
 */
import path from "node:path";
import fs from "node:fs";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { overlayDefinition } from "../../env/compose.js";
import { BROKER_PORT, OVERLAY_NAMES } from "../../env/containers.js";
import { repoRoot } from "../../env/host.js";

test.describe("broker overlays against their compose files", { tag: ["@schema"] }, () => {
  for (const name of OVERLAY_NAMES) {
    test(`${name} lists the services its compose file declares`, async () => {
      const definition = overlayDefinition(name);
      expect(definition.name).toBe(name);
      expect(definition.services.length).toBeGreaterThan(0);
      // `restartOverlay` restarts exactly this one service, so it has to be a service the overlay
      // actually starts: a `primary` outside that list fails `docker compose restart` with a name
      // nothing declares.
      expect(definition.services).toContain(definition.primary);
      // The overlay file has to exist beside the base compose file, or `ensureOverlay` would fail on a
      // Compose error that names no overlay and no broker — a worse message than this one.
      const file = path.join(repoRoot(), "infrastructure", "docker", definition.file);
      expect(fs.existsSync(file), `${file} does not exist`).toBe(true);

      // `definition.services` and `definition.primary` are checked against the YAML they claim to
      // describe, not only against each other: without this, a service renamed in the compose file
      // (or in this table) produces a `docker compose` error mid-seed instead of a red case here.
      const parsed = yaml.load(fs.readFileSync(file, "utf-8")) as { services?: Record<string, unknown> };
      // `akhq` is the one service a file declares and the suite leaves stopped: a Kafka UI for people.
      const declared = Object.keys(parsed.services ?? {}).filter((service) => service !== "akhq").sort();
      expect(
        [...definition.services].sort(),
        `${definition.file} declares ${JSON.stringify(declared)}`,
      ).toEqual(declared);
    });
  }

  test("broker ports match Compose and the kind port mapping", async () => {
    const bootstrap = fs.readFileSync(path.join(repoRoot(), "e2e/k8s/bootstrap-cluster.sh"), "utf-8");
    for (const name of OVERLAY_NAMES) {
      const definition = overlayDefinition(name);
      const file = path.join(repoRoot(), "infrastructure", "docker", definition.file);
      const parsed = yaml.load(fs.readFileSync(file, "utf-8")) as { services: Record<string, { ports?: string[] }> };
      const published = (parsed.services[definition.primary].ports ?? []).map((each) => Number(each.split(":")[0]));
      const port = BROKER_PORT[name];
      expect(published, `the host ports ${definition.file} publishes`).toContain(port.compose);
      expect(bootstrap, `the kind port mapping for ${name}`).toContain(
        `{ containerPort: ${port.k8s}, hostPort: ${port.k8s} }`,
      );
    }
  });
});
