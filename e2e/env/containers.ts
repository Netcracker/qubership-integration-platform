/**
 * The roles, and where each one answers on its own port.
 *
 * Six places used to spell the same four ports out: the environment's `url`, the run header, the
 * catalog client, the session lookup, the operation registry, and `playwright.config.ts`. A port
 * that moves has to move in all of them, and nothing fails if one is missed. This module is the
 * table the others read. It imports only `env/target.ts`, so reading it costs no Docker and no
 * sampler, and `support/report.ts` and `registry/operations.ts` load it under
 * `node --experimental-strip-types`, which is why the specifier names the `.ts` file.
 *
 * The Compose container behind each role is in `env/compose-containers.ts`.
 */
import type { Overlay, ServiceRole } from "./index.ts";
import { target, type Target } from "./target.ts";

/** The roles, in the order every report prints them. */
export const ROLES: readonly ServiceRole[] = [
  "runtime-catalog",
  "engine",
  "sessions-management",
  "testing-service",
];

/**
 * The host port of each role per target, and the variable that points the suite elsewhere.
 *
 * On Kubernetes every host-side address is a fixed NodePort, so a kind or k3d cluster can map it
 * when the cluster is created.
 */
const SERVICE: Record<ServiceRole, { port: Record<Target, number>; urlEnv: string }> = {
  "runtime-catalog": { port: { compose: 8091, k8s: 30091 }, urlEnv: "CIP_CATALOG_URL" },
  engine: { port: { compose: 8092, k8s: 30092 }, urlEnv: "CIP_ENGINE_URL" },
  "sessions-management": { port: { compose: 8093, k8s: 30093 }, urlEnv: "CIP_SESSIONS_URL" },
  "testing-service": { port: { compose: 8095, k8s: 30095 }, urlEnv: "CIP_TESTING_SERVICE_URL" },
};

const PROXY_PORT: Record<Target, number> = { compose: 8080, k8s: 30080 };

/** The host port of one role on `on`, which the Helm install sets as the Service's node port. */
export function hostPort(role: ServiceRole, on: Target = target()): number {
  return SERVICE[role].port[on];
}

/** The host port of the proxy on `on`. */
export function proxyPort(on: Target = target()): number {
  return PROXY_PORT[on];
}

/** Base URL of one role, direct rather than through the proxy. */
export function serviceUrl(role: ServiceRole): string {
  return process.env[SERVICE[role].urlEnv] ?? `http://localhost:${hostPort(role)}`;
}

/**
 * The nginx front door: the `/api/` surface, the page a browser opens, and the only thing the proxy
 * reload polls. Here rather than in an adapter, so `playwright.config.ts` can set the `ui` project's
 * `baseURL` without loading one.
 */
export function proxyUrl(): string {
  return process.env.CIP_PROXY_URL ?? `http://localhost:${proxyPort()}`;
}

/** The broker overlays, sorted for a stable error message. */
export const OVERLAY_NAMES: readonly Overlay[] = ["kafka", "pubsub", "rabbitmq", "sftp"];

/**
 * The host port of each broker per target, for the suite's own clients. The chain fixtures reach
 * the same brokers by their bare in-cluster names, such as `kafka:29092`.
 *
 * On Kubernetes each port is the node port of the broker's `<name>-host` Service, which
 * `e2e/k8s/bootstrap-cluster.sh` maps when it creates the kind cluster.
 */
export const BROKER_PORT: Record<Overlay, Record<Target, number>> = {
  kafka: { compose: 9092, k8s: 30992 },
  rabbitmq: { compose: 5672, k8s: 30672 },
  pubsub: { compose: 8085, k8s: 30085 },
  sftp: { compose: 2222, k8s: 30022 },
};

/** The Kafka bootstrap address on the host, or `CIP_KAFKA_URL` when set. */
export function kafkaHostBrokers(): string {
  return process.env.CIP_KAFKA_URL ?? `localhost:${BROKER_PORT.kafka[target()]}`;
}

/** The AMQP URL of RabbitMQ on the host, or `CIP_RABBITMQ_URL` when set. */
export function rabbitmqHostUrl(): string {
  return process.env.CIP_RABBITMQ_URL ?? `amqp://guest:guest@localhost:${BROKER_PORT.rabbitmq[target()]}`;
}

/** The base URL of the pub/sub emulator on the host, or `CIP_PUBSUB_URL` when set. */
export function pubsubHostUrl(): string {
  return process.env.CIP_PUBSUB_URL ?? `http://localhost:${BROKER_PORT.pubsub[target()]}`;
}

/** The SFTP server on the host; `CIP_SFTP_HOST` and `CIP_SFTP_PORT` override each half. */
export function sftpHostAddress(): { host: string; port: number } {
  return {
    host: process.env.CIP_SFTP_HOST ?? "localhost",
    port: Number(process.env.CIP_SFTP_PORT ?? BROKER_PORT.sftp[target()]),
  };
}
