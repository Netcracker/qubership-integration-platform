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
// order is no longer written down anywhere but here, and the type system is what keeps it that way:
//
//   * a name set carries a brand only `declareNames` can apply, so `NAME_SETS` is the only source
//     of one — a caller cannot hand `candidateExtensions` a set with the generations swapped;
//   * a scan order carries a brand only `candidateExtensions` and `combineCandidates` can apply,
//     and `resolveFirstCandidate` takes nothing else — a caller cannot hand a lookup an order it
//     wrote by hand, however it assembled the array.
//
// Both brands are private symbols, so an accidental bypass does not type-check. A cast, or a spread
// of a declared value, still compiles; both are deliberate, and neither is the accident this exists
// to stop. `tests/web/response/fixtures/precedenceBypass/attempts.ts` spells the bypasses out, and
// the guard fails if any of them starts to compile.
//
// The pair-shaped sets get a stronger type than the service one: `PairedNames` has exactly one
// current and one legacy name, and they are two different names, so `currentExtension` and
// `legacyExtension` answer for an API and a group and fail to compile for a service, which has three
// current names and picks between them by kind (`serviceFileType.ts:serviceExtensionForType`).

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

/**
 * A name set with one name on each side — a rename, rather than a family of names. The two are
 * different names by construction: `L` cannot be `C`.
 */
export type PairedNames<
  C extends ExtensionKey,
  L extends Exclude<ExtensionKey, C>,
> = {
  readonly current: readonly [C];
  readonly legacy: readonly [L];
};

// The brand `NAME_SETS` stamps on its entries. Private, so no expression outside this module has
// the property and no name set can be built anywhere else.
declare const declaredHere: unique symbol;

/** A name set this module declared. */
export type DeclaredNameSet<K extends ExtensionKey> = NameSet<K> & {
  readonly [declaredHere]: never;
};

/** A declared name set of exactly two names, one per generation. */
export type DeclaredPair<
  C extends ExtensionKey,
  L extends Exclude<ExtensionKey, C>,
> = PairedNames<C, L> & { readonly [declaredHere]: never };

function declareNames<const T extends NameSet<ExtensionKey>>(
  names: T,
): T & { readonly [declaredHere]: never } {
  return names as T & { readonly [declaredHere]: never };
}

// The three names the #553 versions wrote. Read, never written: the type moved into `$schema` and
// the three plain kinds went back to sharing `.service.`. A file still wearing one is typed by its
// `$schema` like any other, so these are a name generation and not a type source.
const PER_TYPE_SERVICE_KEYS: readonly TypedServiceExtensionKey[] =
  PLAIN_SERVICE_TYPES.map((type) => EXTENSION_KEY_BY_TYPE[type]);

// The two kinds that are their own kind of document, named that way from the start — derived as the
// complement of the plain ones, so a sixth kind lands in exactly one of the two lists by
// construction rather than by someone remembering to add it here.
const TYPELESS_KIND_KEYS: readonly TypedServiceExtensionKey[] = Object.values(
  EXTENSION_KEY_BY_TYPE,
).filter((key) => !PER_TYPE_SERVICE_KEYS.includes(key));

/** Every name set in the extension, one entry per pair of name generations. */
export const NAME_SETS = {
  /** Every kind of service file: the plain name, the two typeless kinds, and the per-type names. */
  service: declareNames({
    current: [
      "service",
      ...TYPELESS_KIND_KEYS,
    ] as readonly ServiceExtensionKey[],
    legacy: PER_TYPE_SERVICE_KEYS,
  }),
  /**
   * The plain kinds alone. Context and MCP are separate kinds of document, so a scan for a plain
   * service must not answer with one.
   */
  plainService: declareNames({
    current: ["service"] as readonly ServiceExtensionKey[],
    legacy: PER_TYPE_SERVICE_KEYS,
  }),
  /** The model level, renamed from `specification` to `api`. */
  api: declareNames({
    current: ["api"],
    legacy: ["specification"],
  }),
  /** The group level, renamed alongside it. */
  apiGroup: declareNames({
    current: ["apiGroup"],
    legacy: ["specificationGroup"],
  }),
  // The names with one generation. Nothing was renamed to them, so there is no precedence to
  // state; they are declared because a lookup takes a declared order and nothing else.
  /** A chain has one name and always has. */
  chain: declareNames({ current: ["chain"], legacy: [] }),
  /** A context service: its own kind of document, named that way from the start. */
  contextService: declareNames({ current: ["contextService"], legacy: [] }),
  /** An MCP service, likewise. */
  mcpService: declareNames({ current: ["mcpService"], legacy: [] }),
} satisfies Record<string, DeclaredNameSet<ExtensionKey>>;

export const SERVICE_NAMES: DeclaredNameSet<ServiceExtensionKey> =
  NAME_SETS.service;
export const PLAIN_SERVICE_NAMES: DeclaredNameSet<ServiceExtensionKey> =
  NAME_SETS.plainService;
export const API_NAMES: DeclaredPair<"api", "specification"> = NAME_SETS.api;
export const API_GROUP_NAMES: DeclaredPair<"apiGroup", "specificationGroup"> =
  NAME_SETS.apiGroup;

// The brand a scan order carries. Private for the same reason the one above is.
declare const orderedHere: unique symbol;

/**
 * A scan order derived from a declared name set. The two functions below are the only things that
 * produce one, and `resolveFirstCandidate` accepts nothing else, so every lookup runs an order this
 * module states. Transforming one (`[...order]`, `.map`, `.reverse`) yields a plain array, which no
 * lookup takes.
 */
export type CandidateOrder = readonly string[] & {
  readonly [orderedHere]: never;
};

/**
 * The scan order of a name set: every current name, then every legacy one. This is the only
 * candidate order any lookup runs, so a stale name can never outrank the one a write emits.
 */
export function candidateExtensions<K extends ExtensionKey>(
  names: DeclaredNameSet<K>,
  byKey: Record<K, string>,
): CandidateOrder {
  return [...names.current, ...names.legacy].map(
    (key) => byKey[key],
  ) as unknown as CandidateOrder;
}

/**
 * Several declared orders, run one after another — for the one lookup that searches every kind of
 * file for an id. Each set keeps its own order, so no legacy name overtakes the current name of its
 * own set. Combining two sets that share a name is the one way to get that wrong, and nothing does:
 * the sets a caller combines are disjoint.
 */
export function combineCandidates(
  ...orders: readonly CandidateOrder[]
): CandidateOrder {
  return orders.flat() as unknown as CandidateOrder;
}

/** The extension a write emits. Also reads a `schemaUrls` map, which carries the same keys. */
export function currentExtension<
  C extends ExtensionKey,
  L extends Exclude<ExtensionKey, C>,
>(names: DeclaredPair<C, L>, byKey: Record<C | L, string>): string {
  return byKey[names.current[0]];
}

/** The extension a write never emits, and a read still has to accept. */
export function legacyExtension<
  C extends ExtensionKey,
  L extends Exclude<ExtensionKey, C>,
>(names: DeclaredPair<C, L>, byKey: Record<C | L, string>): string {
  return byKey[names.legacy[0]];
}
