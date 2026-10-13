/**
 * The host-side probes that tell a broker overlay is serving, shared by the Compose and Kubernetes
 * adapters. Each probe resolves to `null` once the broker answers, or to a description of what is
 * still missing. The addresses they probe come from `env/containers.ts`.
 */
import net from "node:net";
import { Kafka, logLevel } from "kafkajs";
import type { Overlay } from "./index.js";
import { kafkaHostBrokers, pubsubHostUrl, rabbitmqHostUrl, sftpHostAddress } from "./containers.js";

/**
 * How long `ensureOverlay` and `restartOverlay` each poll for one overlay to answer ready, on top
 * of the command that starts or restarts it.
 *
 * Exported so that `specs/brokers-seed/brokers.setup.ts`, which brings up every overlay in
 * sequence, sizes its own `test.setTimeout` on the number the adapters poll with.
 */
export const OVERLAY_READY_TIMEOUT = 180_000;

/**
 * A bare TCP connect, for a broker whose client protocol needs no deeper probe.
 *
 * A successful connect shows only that something listens. The spec's own client then checks that
 * the server works.
 */
export function tcpReady(host: string, port: number): () => Promise<string | null> {
  return () =>
    new Promise((resolve) => {
      const socket = net.createConnection({ host, port });
      const settle = (result: string | null) => {
        socket.destroy();
        resolve(result);
      };
      socket.setTimeout(2_000);
      socket.once("connect", () => settle(null));
      socket.once("timeout", () => settle(`${host}:${port} did not accept a connection`));
      socket.once("error", (cause) => settle(`${host}:${port} refused a connection (${String(cause)})`));
    });
}

/**
 * An HTTP GET that has to complete with a 2xx status, not merely accept a connection.
 *
 * The pub/sub emulator needs this rather than `tcpReady`: measured, its wrapper script opens the
 * listening socket seconds before the JVM inside it serves, so a bare TCP connect succeeds and the
 * next request fails with a connection reset. `ensurePubsubTopology`'s first `PUT` failed with
 * `SocketError: other side closed` against a container `docker ps` already reported as `Up`.
 */
export function httpReady(url: string): () => Promise<string | null> {
  return async () => {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(2_000) });
      return response.ok ? null : `${url} answered HTTP ${response.status}`;
    } catch (cause) {
      return `${url} did not answer (${String(cause)})`;
    }
  };
}

/**
 * A Kafka metadata request, not a bare TCP connect.
 *
 * Measured on Compose: `docker compose restart kafka` opened the published port about 1 s in, but
 * `admin.listTopics()` did not succeed until about 6.5 s in, a gap reproduced twice. A TCP probe
 * would let `ensureOverlay` return while the broker still rejected every request.
 */
export function kafkaReady(brokers: string[]): () => Promise<string | null> {
  return async () => {
    const admin = new Kafka({
      clientId: "e2e-overlay-ready-probe",
      brokers,
      logLevel: logLevel.NOTHING,
      requestTimeout: 2_000,
      retry: { retries: 0 },
    }).admin();
    try {
      await admin.connect();
      await admin.listTopics();
      return null;
    } catch (cause) {
      return `${brokers.join(",")} did not answer a metadata request (${String(cause)})`;
    } finally {
      await admin.disconnect().catch(() => {});
    }
  };
}

/**
 * The probe of `overlay` at its host address, read when the probe runs, so a `CIP_*` override set
 * later still applies. RabbitMQ gets a bare TCP connect; the Compose adapter uses the container's
 * health check for it instead.
 */
export function hostReady(overlay: Overlay): () => Promise<string | null> {
  return () => {
    switch (overlay) {
      case "kafka":
        return kafkaReady([kafkaHostBrokers()])();
      case "rabbitmq": {
        const url = new URL(rabbitmqHostUrl());
        return tcpReady(url.hostname, Number(url.port))();
      }
      case "pubsub":
        return httpReady(`${pubsubHostUrl()}/v1/projects/e2e-overlay-ready-probe/topics`)();
      case "sftp": {
        const { host, port } = sftpHostAddress();
        return tcpReady(host, port)();
      }
    }
  };
}
