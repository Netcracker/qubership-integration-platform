import type { RJSFSchema } from "@rjsf/utils";
import { normalizeProtocol } from "../../../../../misc/protocol-utils";

export interface OneOfOption {
  title?: string;
  properties?: {
    integrationOperationProtocolType?: {
      const?: string;
      enum?: string[];
    };
    [key: string]: unknown;
  };
  required?: string[];

  [key: string]: unknown;
}

export const EMPTY_ONEOF_SCHEMA: RJSFSchema = {
  type: "object",
  properties: {},
};

/**
 * Infers the `type` discriminator for the service-call `before` (Prepare
 * Request action) when it's missing from saved data. RJSF's
 * getClosestMatchingOption picks None for data without `type` because Mapper
 * and Scripting require it, which leaves the Action select stuck on "None"
 * on reload even though `mappingDescription` is preserved.
 */
export function inferBeforeType(fd: unknown): string | undefined {
  if (!fd || typeof fd !== "object") return undefined;
  const rec = fd as Record<string, unknown>;
  if (typeof rec.type === "string" && rec.type) return rec.type;
  if (rec.mappingDescription != null) return "mapper-2";
  if (rec.script != null) return "script";
  return undefined;
}

/**
 * Ordered definition of the `before` oneOf options (index matches the
 * option index in the schema). `fields` lists the option-specific keys
 * that need stripping when switching away — RJSF's sanitizeDataForNewSchema
 * doesn't walk `allOf`, so stale fields would otherwise keep the previous
 * option selected via `getClosestMatchingOption`.
 */
export const BEFORE_OPTIONS: ReadonlyArray<{
  type: string | undefined;
  fields: readonly string[];
}> = [
  { type: undefined, fields: [] },
  { type: "mapper-2", fields: ["mappingDescription"] },
  {
    type: "script",
    fields: [
      "script",
      "exportFileExtension",
      "propertiesToExportInSeparateFile",
      "propertiesFilename",
    ],
  },
];

export function findBeforeIndexByType(type: string | undefined): number {
  const idx = BEFORE_OPTIONS.findIndex((opt) => opt.type === type);
  return Math.max(idx, 0);
}

export function protocolMatchesOption(
  option: OneOfOption,
  protocol: string,
): boolean {
  const protoDef = option?.properties?.integrationOperationProtocolType;
  if (!protoDef) return false;
  if (protoDef.const) return normalizeProtocol(protoDef.const) === protocol;
  if (Array.isArray(protoDef.enum))
    return protoDef.enum.some((e) => normalizeProtocol(e) === protocol);
  return false;
}

export function hasNoProtocolConstraint(option: OneOfOption): boolean {
  const protoDef = option?.properties?.integrationOperationProtocolType;
  return !protoDef?.const && !protoDef?.enum;
}
