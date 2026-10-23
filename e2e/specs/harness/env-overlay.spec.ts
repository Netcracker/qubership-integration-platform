/**
 * The overlay names `Env.ensureOverlay` accepts, the failure a spec sees for a name that is not one
 * of them, and the readiness probes that wait for a broker.
 *
 * No stack: `overlayDefinition` and `OVERLAY_NAMES` are plain lookups over a static table, and the
 * probes run against a local server. `ensureOverlay` calls `overlayDefinition` before it runs a
 * single Compose command (see `env/compose.ts`), so a bad overlay name fails immediately rather
 * than through a deployment that retries against a broker nothing ever started.
 * `specs/schema/env-overlay.spec.ts` reads the table against the compose files.
 */
import http from "node:http";
import net, { type AddressInfo } from "node:net";
import { test, expect } from "@playwright/test";
import { overlayDefinition } from "../../env/compose.js";
import { BROKER_PORT, OVERLAY_NAMES } from "../../env/containers.js";
import { hostReady, httpReady, tcpReady } from "../../env/overlays.js";
import type { Overlay } from "../../env/index.js";
import { withEnv } from "../../support/with-env.js";

test.describe("env overlay", { tag: ["@harness"] }, () => {
  test("the adapter lists the four broker overlays", async () => {
    expect(OVERLAY_NAMES).toEqual(["kafka", "pubsub", "rabbitmq", "sftp"]);
  });

  // The mutation check: point the adapter at a broker name that does not exist and
  // confirm setup fails with that message, rather than a deployment retrying silently until the
  // seed's own poll times out.
  test("an unknown name fails at once, listing the known overlays", async () => {
    expect(() => overlayDefinition("not-a-real-broker")).toThrow(
      'no overlay named "not-a-real-broker". Known overlays: kafka, pubsub, rabbitmq, sftp',
    );
  });

  test("an empty name fails the same way", async () => {
    expect(() => overlayDefinition("")).toThrow(/no overlay named ""/);
  });

  test("the broker port table names exactly the known overlays", async () => {
    expect(Object.keys(BROKER_PORT).sort()).toEqual(OVERLAY_NAMES);
  });

  test.describe("readiness probes", () => {
    /** Runs `body` against an HTTP server on a free local port, and closes the server before returning. */
    async function withLocalServer<T>(body: (port: number) => Promise<T>): Promise<T> {
      const server = http.createServer((_, response) => response.end("ok"));
      await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
      const { port } = server.address() as AddressInfo;
      try {
        return await body(port);
      } finally {
        await new Promise((resolve) => server.close(resolve));
      }
    }

    test("TCP and HTTP report a listener ready and a closed port not", async () => {
      const port = await withLocalServer(async (port) => {
        expect(await tcpReady("127.0.0.1", port)()).toBeNull();
        expect(await httpReady(`http://127.0.0.1:${port}/`)()).toBeNull();
        return port;
      });
      expect(await tcpReady("127.0.0.1", port)()).toMatch(/refused a connection/);
      expect(await httpReady(`http://127.0.0.1:${port}/`)()).toMatch(/did not answer/);
    });

    test("each host probe reads its broker address when it runs", async () => {
      // Created before the overrides are set, so each probe has to read its address when it runs.
      const probes = Object.fromEntries(OVERLAY_NAMES.map((overlay) => [overlay, hostReady(overlay)]));
      const { port, probe } = await withLocalServer(async (port) => {
        const overrides = {
          CIP_KAFKA_URL: `127.0.0.1:${port}`,
          CIP_RABBITMQ_URL: `amqp://guest:guest@127.0.0.1:${port}`,
          CIP_PUBSUB_URL: `http://127.0.0.1:${port}`,
          CIP_SFTP_HOST: "127.0.0.1",
          CIP_SFTP_PORT: String(port),
        };
        const probe = (overlay: Overlay) => withEnv(overrides, () => probes[overlay]());
        expect(await probe("pubsub")).toBeNull();
        expect(await probe("sftp")).toBeNull();
        return { port, probe };
      });
      expect(await probe("kafka")).toContain(`127.0.0.1:${port} did not answer a metadata request`);
      expect(await probe("rabbitmq")).toContain(`127.0.0.1:${port} did not complete an AMQP handshake`);
      expect(await probe("pubsub")).toContain(`http://127.0.0.1:${port}/v1/projects/`);
      expect(await probe("sftp")).toContain(`127.0.0.1:${port} refused a connection`);
    });

    test("RabbitMQ is not ready on a port that drops the connection", async () => {
      // The kind port mapping does this while the NodePort has no ready endpoint (CI run 38735160150).
      const server = net.createServer((socket) => socket.destroy());
      await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
      const { port } = server.address() as AddressInfo;
      try {
        expect(await tcpReady("127.0.0.1", port)()).toBeNull();
        const probe = hostReady("rabbitmq");
        expect(await withEnv({ CIP_RABBITMQ_URL: `amqp://guest:guest@127.0.0.1:${port}` }, () => probe()))
          .toMatch(/did not complete an AMQP handshake .*Socket closed abruptly during opening handshake/);
      } finally {
        await new Promise((resolve) => server.close(resolve));
      }
    });
  });
});
