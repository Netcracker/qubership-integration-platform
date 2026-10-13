/**
 * The Compose container behind each role.
 *
 * `compose.ts` restarts containers and reads their logs, `resources.ts` samples them with
 * `docker stats`, and `support/report.ts` inspects their images. Neither of the first two may import
 * the other: the environment reads `resourcePeaks()` from what the sampler wrote, and the sampler
 * starts before any environment exists. So the table is a leaf module of its own.
 * `support/report.ts` loads it under `node --experimental-strip-types`, so it imports nothing at
 * runtime.
 */
import type { ServiceRole } from "./index.ts";

export const CONTAINER: Record<ServiceRole, string> = {
  "runtime-catalog": "qip-runtime-catalog",
  engine: "qip-engine",
  "sessions-management": "qip-sessions-management",
  "testing-service": "qip-testing-service",
};

export const PROXY_CONTAINER = "ui-proxy";

/** The role a container name belongs to, or `null` for a container the suite does not watch. */
export function roleOfContainer(container: string): ServiceRole | null {
  const found = Object.entries(CONTAINER).find(([, name]) => name === container);
  return found ? (found[0] as ServiceRole) : null;
}
