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
import http from "node:http";
import type { AddressInfo } from "node:net";
import yaml from "js-yaml";
import { test, expect } from "@playwright/test";
import { overlayDefinition, overlayNames } from "../../env/compose.js";
import { BROKER_PORT } from "../../env/containers.js";
import { hostReady, httpReady, tcpReady } from "../../env/overlays.js";
import { repoRoot } from "../../env/host.js";
import type { Overlay } from "../../env/index.js";
import { withEnv } from "../../support/with-env.js";

test("the adapter knows exactly the four broker overlays the suite starts", { tag: ["@infra", "@tier1"] }, async () => {
  expect(overlayNames()).toEqual(["kafka", "pubsub", "rabbitmq", "sftp"]);
});

for (const name of overlayNames()) {
  test(`overlay ${name} resolves to its own compose file and at least one service`, { tag: ["@infra", "@tier1"] }, async () => {
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

test("the broker port table names exactly the overlays the adapter knows", { tag: ["@infra", "@tier1"] }, async () => {
  expect(Object.keys(BROKER_PORT).sort()).toEqual(overlayNames());
});

test("the broker port table matches the ports Compose publishes and kind maps", { tag: ["@infra", "@tier1"] }, async () => {
  const bootstrap = fs.readFileSync(path.join(repoRoot(), "e2e/k8s/bootstrap-cluster.sh"), "utf-8");
  for (const name of overlayNames()) {
    const definition = overlayDefinition(name);
    const file = path.join(repoRoot(), "infrastructure", definition.file);
    const parsed = yaml.load(fs.readFileSync(file, "utf-8")) as { services: Record<string, { ports?: string[] }> };
    const published = (parsed.services[definition.primary].ports ?? []).map((each) => Number(each.split(":")[0]));
    const port = BROKER_PORT[name as Overlay];
    expect(published, `the host ports ${definition.file} publishes`).toContain(port.compose);
    expect(bootstrap, `the kind port mapping for ${name}`).toContain(
      `{ containerPort: ${port.k8s}, hostPort: ${port.k8s} }`,
    );
  }
});

test("the TCP and HTTP probes report a listener as ready and a closed port as not", { tag: ["@infra", "@tier1"] }, async () => {
  const server = http.createServer((_, response) => response.end("ok"));
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  try {
    expect(await tcpReady("127.0.0.1", port)()).toBeNull();
    expect(await httpReady(`http://127.0.0.1:${port}/`)()).toBeNull();
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
  expect(await tcpReady("127.0.0.1", port)()).toMatch(/refused a connection/);
  expect(await httpReady(`http://127.0.0.1:${port}/`)()).toMatch(/did not answer/);
});

test("each host probe reads its broker's host address, CIP_* overrides included", { tag: ["@infra", "@tier1"] }, async () => {
  const server = http.createServer((_, response) => response.end("ok"));
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  const overrides = {
    CIP_KAFKA_URL: `127.0.0.1:${port}`,
    CIP_RABBITMQ_URL: `amqp://guest:guest@127.0.0.1:${port}`,
    CIP_PUBSUB_URL: `http://127.0.0.1:${port}`,
    CIP_SFTP_HOST: "127.0.0.1",
    CIP_SFTP_PORT: String(port),
  };
  // Created before the overrides are set, so each probe has to read its address when it runs.
  const probes = Object.fromEntries(overlayNames().map((overlay) => [overlay, hostReady(overlay as Overlay)]));
  const probe = (overlay: Overlay) => withEnv(overrides, () => probes[overlay]());
  try {
    expect(await probe("rabbitmq")).toBeNull();
    expect(await probe("pubsub")).toBeNull();
    expect(await probe("sftp")).toBeNull();
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
  expect(await probe("kafka")).toContain(`127.0.0.1:${port} did not answer a metadata request`);
  expect(await probe("rabbitmq")).toContain(`127.0.0.1:${port} refused a connection`);
  expect(await probe("pubsub")).toContain(`http://127.0.0.1:${port}/v1/projects/`);
  expect(await probe("sftp")).toContain(`127.0.0.1:${port} refused a connection`);
});
