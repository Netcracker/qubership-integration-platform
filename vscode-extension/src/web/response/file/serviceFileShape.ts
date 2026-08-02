// Shapes a service document the way runtime-catalog exports it, so a file
// written here and a file exported by the backend differ only in content.
//
// Key order follows the field declaration order of the matching export DTO
// (IntegrationSystemContentDto, ContextServiceContentDto, MCPServiceContentDto,
// and the Environment entity), because that is what Jackson emits. Without a
// canonical order the layout depends on the order fields happened to be
// assigned in, so pruning an empty placeholder moves the key to the end of the
// file on the next save.

import { pruneEntity } from "./pruneEmpty";

export type ServiceFileKind = "service" | "contextService" | "mcpService";

const TOP_LEVEL = ["id", "$schema", "name", "metaInfo", "content"];

// Fields the API response derives from the protocol. Older files stored them,
// and a stored copy goes stale the moment the protocol changes, so drop it on
// the way out rather than carrying a value nothing updates.
const DERIVED_CONTENT_KEYS = ["extendedProtocol", "specification"];

const CONTENT_ORDER: Record<ServiceFileKind, string[]> = {
  service: [
    "description",
    "activeEnvironmentId",
    "integrationSystemType",
    "internalServiceName",
    "protocol",
    "environments",
    "labels",
    "migrations",
  ],
  contextService: ["description", "internalServiceName", "migrations"],
  mcpService: [
    "description",
    "identifier",
    "instructions",
    "migrations",
    "labels",
  ],
};

const ENVIRONMENT_ORDER = [
  "id",
  "name",
  "description",
  "address",
  "sourceType",
  "labels",
  "maasInstanceId",
  "properties",
];

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    (Object.getPrototypeOf(value) === Object.prototype ||
      Object.getPrototypeOf(value) === null)
  );
}

/** Known keys first, in the given order; anything else keeps its relative order after them. */
function orderKeys<T>(value: T, order: string[]): T {
  if (!isPlainObject(value)) {
    return value;
  }
  const result: Record<string, unknown> = {};
  for (const key of order) {
    if (key in value) {
      result[key] = value[key];
    }
  }
  for (const [key, entry] of Object.entries(value)) {
    if (!(key in result)) {
      result[key] = entry;
    }
  }
  return result as unknown as T;
}

export function shapeServiceFile(entity: unknown, kind: ServiceFileKind): any {
  const pruned = pruneEntity(entity);
  if (!isPlainObject(pruned)) {
    return pruned;
  }

  if (isPlainObject(pruned.content)) {
    if (kind === "service") {
      DERIVED_CONTENT_KEYS.forEach((key) => {
        delete (pruned.content as Record<string, unknown>)[key];
      });
    }
    const content = orderKeys(pruned.content, CONTENT_ORDER[kind]);
    if (Array.isArray(content.environments)) {
      content.environments = content.environments.map((environment) =>
        orderKeys(environment, ENVIRONMENT_ORDER),
      );
    }
    pruned.content = content;
  }

  return orderKeys(pruned, TOP_LEVEL);
}
