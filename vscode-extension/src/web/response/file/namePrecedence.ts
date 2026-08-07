// Which of the names an entity can be stored under is the current one, declared once per name set.
//
// Three entities live under two generations of names: a service (`.service.` versus the five typed
// names), an API (`.specification.` versus `.api.`) and an API group (`.specification-group.`
// versus `.api-group.`). Every read has to resolve to the current name and every write has to emit
// it, or a read shows a document the last conversion superseded and the next write puts that
// document over the current file.
//
// The rule was correct by inspection before: a dozen candidate arrays each spelled its own order,
// and one of them (`serviceApiRead.findModelFileById`) had the pair the wrong way round. So the
// order is no longer written down anywhere but here. `NAME_SETS` states which names a write emits
// and which ones are read but never written, and `candidateExtensions` is the only way to build a
// scan order from it — a caller cannot state one that disagrees, because it never states one.
//
// The pair-shaped sets get a stronger type than the service one: `PairedNames` has exactly one
// current and one legacy name, so `currentExtension` and `legacyExtension` answer for an API and a
// group and fail to compile for a service, which has five current names and picks between them by
// type (`serviceFileType.ts:serviceExtensionForType`).

import { FileExtensionsConfig } from "./fileExtensions";
import { IntegrationSystemType } from "../../api-services/servicesTypes";

/** A key of the per-project extension map, and of the `schemaUrls` map that mirrors it. */
export type ExtensionKey = keyof FileExtensionsConfig;

/** The extension keys a service file can carry. `ProjectConfig["extensions"]` satisfies it too. */
export type ServiceExtensionKey =
  | "service"
  | "externalService"
  | "internalService"
  | "implementedService"
  | "contextService"
  | "mcpService";

export type ServiceExtensions = Pick<FileExtensionsConfig, ServiceExtensionKey>;

/** The typed service names alone, so no service type can be mapped onto the legacy name. */
export type TypedServiceExtensionKey = Exclude<ServiceExtensionKey, "service">;

// The `Record` keyed by the enum makes a new service type a compile error until it gets an
// extension, and the value type keeps that extension a typed one.
export const EXTENSION_KEY_BY_TYPE: Record<
  IntegrationSystemType,
  TypedServiceExtensionKey
> = {
  [IntegrationSystemType.EXTERNAL]: "externalService",
  [IntegrationSystemType.INTERNAL]: "internalService",
  [IntegrationSystemType.IMPLEMENTED]: "implementedService",
  [IntegrationSystemType.CONTEXT]: "contextService",
  [IntegrationSystemType.MCP]: "mcpService",
};

/** The three types a plain service document can state. Context and MCP are separate kinds. */
export const PLAIN_SERVICE_TYPES: readonly IntegrationSystemType[] = [
  IntegrationSystemType.EXTERNAL,
  IntegrationSystemType.INTERNAL,
  IntegrationSystemType.IMPLEMENTED,
];

/**
 * The names one entity can be stored under: the ones a write emits, and the ones kept for reading
 * alone. A scan order is `current` followed by `legacy`, and nothing else is a valid scan order.
 */
export type NameSet<K extends ExtensionKey> = {
  readonly current: readonly K[];
  readonly legacy: readonly K[];
};

/** A name set with one name on each side — a rename, rather than a family of names. */
export type PairedNames<K extends ExtensionKey> = NameSet<K> & {
  readonly current: readonly [K];
  readonly legacy: readonly [K];
};

const TYPED_SERVICE_KEYS: readonly TypedServiceExtensionKey[] = Object.values(
  EXTENSION_KEY_BY_TYPE,
);

const LEGACY_SERVICE_KEYS: readonly ServiceExtensionKey[] = ["service"];

/** Every name set in the extension, one entry per pair of name generations. */
export const NAME_SETS = {
  /** Every kind of service file: the five typed names, and the legacy type-less one. */
  service: {
    current: TYPED_SERVICE_KEYS,
    legacy: LEGACY_SERVICE_KEYS,
  },
  /**
   * The plain kinds alone. Context and MCP are separate kinds of document, so a scan for a plain
   * service must not answer with one — same legacy name, a narrower set of current ones.
   */
  plainService: {
    current: PLAIN_SERVICE_TYPES.map((type) => EXTENSION_KEY_BY_TYPE[type]),
    legacy: LEGACY_SERVICE_KEYS,
  },
  /** The model level, renamed from `specification` to `api`. */
  api: {
    current: ["api"],
    legacy: ["specification"],
  },
  /** The group level, renamed alongside it. */
  apiGroup: {
    current: ["apiGroup"],
    legacy: ["specificationGroup"],
  },
} as const satisfies Record<string, NameSet<ExtensionKey>>;

export const SERVICE_NAMES: NameSet<ServiceExtensionKey> = NAME_SETS.service;
export const PLAIN_SERVICE_NAMES: NameSet<ServiceExtensionKey> =
  NAME_SETS.plainService;
export const API_NAMES: PairedNames<"api" | "specification"> = NAME_SETS.api;
export const API_GROUP_NAMES: PairedNames<"apiGroup" | "specificationGroup"> =
  NAME_SETS.apiGroup;

/**
 * The scan order of a name set: every current name, then every legacy one. This is the only
 * candidate order any lookup runs, so a stale name can never outrank the one a write emits.
 */
export function candidateExtensions<K extends ExtensionKey>(
  names: NameSet<K>,
  byKey: Record<K, string>,
): string[] {
  return [...names.current, ...names.legacy].map((key) => byKey[key]);
}

/** The extension a write emits. Also reads a `schemaUrls` map, which carries the same keys. */
export function currentExtension<K extends ExtensionKey>(
  names: PairedNames<K>,
  byKey: Record<K, string>,
): string {
  return byKey[names.current[0]];
}

/** The extension a write never emits, and a read still has to accept. */
export function legacyExtension<K extends ExtensionKey>(
  names: PairedNames<K>,
  byKey: Record<K, string>,
): string {
  return byKey[names.legacy[0]];
}
