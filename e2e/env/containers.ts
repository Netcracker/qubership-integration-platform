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
import type { ServiceRole } from "./index.ts";
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
