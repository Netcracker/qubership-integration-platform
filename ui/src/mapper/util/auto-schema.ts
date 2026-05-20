import {
  AttributeImporter,
  SourceFormat,
  SourceType,
} from "./attribute-import.ts";
import type { DataType, MappingDescription } from "../model/model.ts";
import { MappingUtil } from "./mapping.ts";

export type MappingDirection = "request" | "response" | "none";

/**
 * Priority list of media types for selecting the "primary" schema among
 * multiple media types returned for a single request/response.
 */
const MEDIA_TYPE_PRIORITY = ["application/json", "application/xml"];

/**
 * Determines the mapping direction by walking the RJSF field path.
 *
 * Rules (see plan / Constants.ts pathToTabMap):
 *   - `properties.before.mappingDescription`            → "request"  (Prepare Request)
 *   - `properties.after.…mappingDescription` (incl. items) → "response" (Handle Response)
 *   - anything else (handlerContainer, chainFailureHandlerContainer,
 *     plain `properties.mappingDescription` for mapper-2)   → "none"
 */
export function getMappingDirectionFromPath(
  path: ReadonlyArray<string | number> | undefined,
): MappingDirection {
  if (!path || path.length === 0) return "none";
  const tail = path[path.length - 1];
  if (tail !== "mappingDescription") return "none";

  const segments = new Set(path.map(String));
  if (segments.has("before")) return "request";
  if (segments.has("after")) return "response";
  return "none";
}

/**
 * Returns a primary JSON Schema chosen from a `requestSchema` map of the
 * shape `{ [mediaType]: schema, "parameters"?: schema }`.
 *
 * Selection priority:
 *   1. "application/json"
 *   2. "application/xml"
 *   3. First key (excluding the reserved "parameters" entry).
 *
 * Returns `null` when no usable schema can be found.
 */
export function pickPrimaryRequestSchema(
  requestSchema: Record<string, unknown> | undefined | null,
): unknown {
  if (!requestSchema || typeof requestSchema !== "object") return null;
  const entries = Object.entries(requestSchema).filter(
    ([key, value]) => key !== "parameters" && isUsableSchema(value),
  );
  if (entries.length === 0) return null;

  for (const preferred of MEDIA_TYPE_PRIORITY) {
    const hit = entries.find(([key]) => key === preferred);
    if (hit) return hit[1];
  }
  return entries[0][1];
}

/**
 * Returns a primary JSON Schema chosen from a `responseSchemas` map.
 *
 * The map can have two shapes:
 *   - HTTP-like: `{ "200": { "application/json": schema }, "400": {…} }`
 *   - Async-like (Kafka/AMQP): `{ "message": schema }` — single flat level
 *
 * Selection priority:
 *   1. Matching `preferredCode` (exact), if provided
 *   2. "200"
 *   3. First 2xx code
 *   4. First code in insertion order
 * Inside a nested media-type object the same request-priority rules apply.
 */
export function pickPrimaryResponseSchema(
  responseSchemas: Record<string, unknown> | undefined | null,
  preferredCode?: string,
): unknown {
  if (!responseSchemas || typeof responseSchemas !== "object") return null;
  const entries = Object.entries(responseSchemas).filter(([, value]) =>
    isUsableSchema(value),
  );
  if (entries.length === 0) return null;

  const pickEntry = (): [string, unknown] | undefined => {
    if (preferredCode) {
      const exact = entries.find(([code]) => code === preferredCode);
      if (exact) return exact;
    }
    const ok = entries.find(([code]) => code === "200");
    if (ok) return ok;
    const anySuccess = entries.find(([code]) => /^2\d\d$/.test(code));
    if (anySuccess) return anySuccess;
    return entries[0];
  };

  const chosen = pickEntry();
  if (!chosen) return null;
  const value = chosen[1];

  if (isMediaTypeMap(value)) {
    return pickPrimaryRequestSchema(value as Record<string, unknown>);
  }
  return value;
}

/**
 * Walks a JSON Schema and infers the implicit `type` where it's missing.
 *
 * JSON Schema draft-07 allows omitting `type` when other keywords make it
 * unambiguous (e.g. `properties` ⇒ object, `items` ⇒ array). `AttributeImporter`
 * falls back to `NullType` in that case, which shows up as a non-expandable
 * body in the mapper — so we pre-normalize the schema tree before handing it
 * off. Returns a *new* object; does not mutate the input.
 */
const OBJECT_OF_SCHEMAS_KEYS = new Set([
  "properties",
  "patternProperties",
  "definitions",
  "$defs",
  "dependencies",
]);
const SINGLE_SCHEMA_KEYS = new Set([
  "items",
  "additionalItems",
  "additionalProperties",
  "propertyNames",
  "contains",
  "not",
  "if",
  "then",
  "else",
]);
const SCHEMA_ARRAY_KEYS = new Set(["allOf", "anyOf", "oneOf"]);

function normalizeSchemaProperty(key: string, value: unknown): unknown {
  if (
    OBJECT_OF_SCHEMAS_KEYS.has(key) &&
    value &&
    typeof value === "object" &&
    !Array.isArray(value)
  ) {
    const walked: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      walked[k] = normalizeJsonSchemaTypes(v);
    }
    return walked;
  }
  if (SINGLE_SCHEMA_KEYS.has(key)) {
    return normalizeJsonSchemaTypes(value);
  }
  if (SCHEMA_ARRAY_KEYS.has(key)) {
    return Array.isArray(value) ? value.map(normalizeJsonSchemaTypes) : value;
  }
  return value;
}

function normalizeJsonSchemaTypes(schema: unknown): unknown {
  if (schema === null || typeof schema !== "object") return schema;
  if (Array.isArray(schema)) return schema.map(normalizeJsonSchemaTypes);

  const obj = schema as Record<string, unknown>;
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(obj)) {
    result[key] = normalizeSchemaProperty(key, value);
  }
  const inferredType = inferImplicitType(obj);
  if (inferredType && !("type" in result)) {
    result.type = inferredType;
  }
  return result;
}

function inferImplicitType(schema: Record<string, unknown>): string | null {
  if ("type" in schema) return null;
  if (
    "properties" in schema ||
    "patternProperties" in schema ||
    "additionalProperties" in schema ||
    "required" in schema ||
    "propertyNames" in schema ||
    "minProperties" in schema ||
    "maxProperties" in schema
  ) {
    return "object";
  }
  if (
    "items" in schema ||
    "prefixItems" in schema ||
    "minItems" in schema ||
    "maxItems" in schema ||
    "uniqueItems" in schema ||
    "contains" in schema
  ) {
    return "array";
  }
  return null;
}

/**
 * Ensures a schema root can resolve `$ref` pointers in both
 * `#/definitions/X` and `#/components/schemas/X` shapes by cross-populating
 * the two registries.
 *
 * Backends for OpenAPI-derived schemas sometimes emit pointers in one form
 * while keeping the lookup table under the other key — e.g. the schema
 * root has a `definitions` map but individual `$ref`s still say
 * `#/components/schemas/Foo`. Without this step `AttributeImporter.resolveSchema`
 * walks into `root.components` (undefined) and throws.
 */
function bridgeDefinitionsAndComponents(
  root: Record<string, unknown>,
): Record<string, unknown> {
  const result: Record<string, unknown> = { ...root };
  const definitions = result.definitions as Record<string, unknown> | undefined;
  const components = result.components as
    | { schemas?: Record<string, unknown> }
    | undefined;
  const componentsSchemas = components?.schemas;

  const hasDefinitions =
    definitions &&
    typeof definitions === "object" &&
    !Array.isArray(definitions);
  const hasComponentsSchemas =
    componentsSchemas &&
    typeof componentsSchemas === "object" &&
    !Array.isArray(componentsSchemas);

  if (hasDefinitions && !hasComponentsSchemas) {
    result.components = {
      ...(typeof components === "object" && components !== null
        ? components
        : {}),
      schemas: definitions,
    };
  } else if (!hasDefinitions && hasComponentsSchemas) {
    result.definitions = componentsSchemas;
  } else if (hasDefinitions && hasComponentsSchemas) {
    // Both present — merge so each side sees the union (components wins on
    // name collisions, matching OpenAPI conventions).
    result.components = {
      ...(typeof components === "object" && components !== null
        ? components
        : {}),
      schemas: { ...definitions, ...componentsSchemas },
    };
    result.definitions = { ...componentsSchemas, ...definitions };
  }
  return result;
}

/**
 * Walks a schema and rewrites `$ref` pointers that don't resolve against the
 * given `root` (e.g. the backend emitted a schema referencing
 * `#/components/schemas/Foo` without bundling `Foo` alongside it). Unresolved
 * references are replaced with a minimal permissive object schema so the
 * importer can still produce a useful structure for the rest of the tree.
 */
function sanitizeUnresolvedRefs(
  schema: unknown,
  root: Record<string, unknown>,
): unknown {
  if (schema === null || typeof schema !== "object") return schema;
  if (Array.isArray(schema)) {
    return schema.map((item) => sanitizeUnresolvedRefs(item, root));
  }
  const obj = schema as Record<string, unknown>;
  const ref = obj.$ref;
  if (typeof ref === "string") {
    if (!isRefResolvable(ref, root)) {
      return { type: "object" };
    }
    return obj;
  }

  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(obj)) {
    result[key] = sanitizeUnresolvedRefs(value, root);
  }
  return result;
}

function isRefResolvable(ref: string, root: Record<string, unknown>): boolean {
  if (!ref.startsWith("#/")) return true; // external refs — let the importer decide
  const segments = ref.substring(2).split("/");
  let current: unknown = root;
  for (const segment of segments) {
    if (
      current === null ||
      current === undefined ||
      typeof current !== "object"
    ) {
      return false;
    }
    current = (current as Record<string, unknown>)[segment];
  }
  return current !== undefined && current !== null;
}

/**
 * Attempts to convert an unknown schema value into a `DataType` via
 * `AttributeImporter`. Returns `null` if the schema is missing, not JSON-like,
 * or cannot be parsed (e.g. protobuf / GraphQL SDL not yet routed through a
 * proper converter).
 *
 * Normalizations applied before parsing:
 *   1. Inferred `type` (e.g. `type: "object"` for schemas that only have
 *      `properties`) — otherwise `AttributeImporter` collapses them to `NullType`.
 *   2. Bridged `definitions` ↔ `components.schemas` so `$ref` pointers resolve
 *      regardless of which registry key the backend emitted.
 *   3. Unresolved `$ref`s (backend returned incomplete type graph) are
 *      replaced with `{type: "object"}` — the rest of the schema still
 *      imports cleanly and the user gets at least a partial structure.
 */
export function tryBuildDataTypeFromSchema(schema: unknown): DataType | null {
  if (!isUsableSchema(schema)) return null;
  try {
    let normalized = normalizeJsonSchemaTypes(schema);
    if (
      normalized &&
      typeof normalized === "object" &&
      !Array.isArray(normalized)
    ) {
      normalized = bridgeDefinitionsAndComponents(
        normalized as Record<string, unknown>,
      );
      normalized = sanitizeUnresolvedRefs(
        normalized,
        normalized as Record<string, unknown>,
      );
    }
    const text =
      typeof normalized === "string" ? normalized : JSON.stringify(normalized);
    if (!text) return null;
    return AttributeImporter.importDataType(
      text,
      SourceFormat.JSON,
      SourceType.SCHEMA,
    );
  } catch (error) {
    console.warn(
      "[auto-schema] Failed to build DataType from operation schema:",
      error,
    );
    return null;
  }
}

/**
 * Applies a DataType to the `body` of the corresponding side of the mapping:
 *   - direction === "request"  ⇒ target.body (what user constructs to send)
 *   - direction === "response" ⇒ source.body (what user receives and maps from)
 *
 * Never mutates the input; always returns a new `MappingDescription`.
 * Also trims `actions` that reference the replaced body (keeps headers/properties
 * actions untouched because only `body` is touched).
 */
export function applySchemaToMapping(
  mapping: MappingDescription | undefined | null,
  direction: "request" | "response",
  dataType: DataType,
): MappingDescription {
  const base = mapping ?? MappingUtil.emptyMapping();
  const sideKey = direction === "request" ? "target" : "source";
  const side = base[sideKey] ?? MappingUtil.emptyMessageSchema();

  const updated: MappingDescription = {
    ...base,
    [sideKey]: {
      ...side,
      body: dataType,
    },
  };

  // Remove actions that reference body on the side we just replaced,
  // since the underlying structure no longer matches.
  const filteredActions = updated.actions.filter((action) => {
    const targetIsBody = action.target?.kind === "body";
    const anySourceIsBody = action.sources.some(
      (src) => "kind" in src && src.kind === "body",
    );
    if (direction === "request" && targetIsBody) return false;
    if (direction === "response" && anySourceIsBody) return false;
    return true;
  });

  return filteredActions.length === updated.actions.length
    ? updated
    : { ...updated, actions: filteredActions };
}

/**
 * Returns `true` when the body on the side that would be overwritten by
 * `applySchemaToMapping(direction, …)` is already populated (non-null).
 * Used to decide whether to show a confirm before overwriting user's work.
 */
export function hasExistingBodyForDirection(
  mapping: MappingDescription | undefined | null,
  direction: "request" | "response",
): boolean {
  if (!mapping) return false;
  const sideKey = direction === "request" ? "target" : "source";
  const body = mapping[sideKey]?.body;
  return body !== null && body !== undefined;
}

// ─── internal helpers ────────────────────────────────────────────────────────

function isUsableSchema(value: unknown): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value === "string") return value.trim().length > 0;
  if (typeof value !== "object") return false;
  return Object.keys(value as Record<string, unknown>).length > 0;
}

/**
 * Checks if an object looks like a `{ [mediaType]: schema }` map used inside
 * HTTP responseSchemas (e.g. `{ "application/json": {...} }`).
 */
function isMediaTypeMap(value: unknown): boolean {
  if (!value || typeof value !== "object") return false;
  const keys = Object.keys(value as Record<string, unknown>);
  if (keys.length === 0) return false;
  return keys.every((key) => key.includes("/"));
}
