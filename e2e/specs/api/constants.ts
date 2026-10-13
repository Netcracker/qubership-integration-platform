/**
 * What the specs in this project agree on: the component tag a service carries.
 *
 * It belongs to no one spec. A case that broadcasts all four component tags makes
 * `--grep @engine` select catalog-only cases and files every row of `cases.md` under all four
 * tables, which is the documented purpose of the tag.
 */
import type { ServiceRole } from "../../env/index.js";
import type { ServiceName } from "../../registry/operations.js";

/** The tag a case carries when it exercises one service, keyed by the role Compose runs it under. */
export const COMPONENT_TAG: Record<ServiceRole, string> = {
  "runtime-catalog": "@catalog",
  engine: "@engine",
  "sessions-management": "@sessions",
  "testing-service": "@testing-service",
};

/**
 * The same tags keyed by the operation registry's own name for a service, which calls the catalog
 * `catalog` where Compose calls it `runtime-catalog`.
 */
export const OPERATION_SERVICE_TAG: Record<ServiceName, string> = {
  catalog: COMPONENT_TAG["runtime-catalog"],
  engine: COMPONENT_TAG.engine,
  "sessions-management": COMPONENT_TAG["sessions-management"],
  "testing-service": COMPONENT_TAG["testing-service"],
};
