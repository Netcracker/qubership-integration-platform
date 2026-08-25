import {
  extractFilename,
  getExtensionsForFile,
  getSchemaUrlsForFile,
} from "./fileExtensions";
import { IntegrationSystemType } from "../../api-services/servicesTypes";
import {
  CandidateOrder,
  candidateExtensions,
  EXTENSION_KEY_BY_TYPE,
  NAME_SETS,
  PLAIN_SERVICE_TYPES,
  ServiceExtensionKey,
  ServiceExtensions,
  TypedServiceExtensionKey,
} from "./namePrecedence";

/** A `vscode.Uri`, any `{ path }` shape, or a bare file name. */
export type ServiceFileRef = string | { path: string };

export type { ServiceExtensions } from "./namePrecedence";

/** The `schemaUrls` entries that pair with the service extensions, one per kind. */
export type ServiceSchemaUrls = Record<keyof ServiceExtensions, string>;

const TYPED_ENTRIES = Object.entries(EXTENSION_KEY_BY_TYPE) as [
  IntegrationSystemType,
  TypedServiceExtensionKey,
][];

const ALL_SERVICE_EXTENSION_KEYS: readonly ServiceExtensionKey[] = [
  ...NAME_SETS.service.current,
  ...NAME_SETS.service.legacy,
];

/**
 * The extension key a name carries: the longest matching extension, so a project configuring
 * `service: ".svc.yaml"` beside `externalService: ".external.svc.yaml"` cannot read a per-type
 * name as the current one.
 */
function carriedServiceExtensionKey(
  name: string,
  extensions: ServiceExtensions,
): ServiceExtensionKey | undefined {
  let carried: ServiceExtensionKey | undefined;
  for (const key of ALL_SERVICE_EXTENSION_KEYS) {
    const extension = extensions[key];
    if (
      name.endsWith(extension) &&
      (carried === undefined || extension.length > extensions[carried].length)
    ) {
      carried = key;
    }
  }
  return carried;
}

/** The extension a service file name carries, or nothing for a non-service name. */
export function carriedServiceExtension(
  fileRef: ServiceFileRef,
  extensions?: ServiceExtensions,
): string | undefined {
  const name = extractFilename(fileRef);
  const ext = resolveExtensions(name, extensions);
  const key = carriedServiceExtensionKey(name, ext);
  return key === undefined ? undefined : ext[key];
}

/**
 * The schema's own file name per kind — the second matching layer, and the one that has to agree
 * with Runtime Catalog's `ServiceTypeFiles.SCHEMA_FILE_STEMS`. Keyed by the extension key, so a new
 * kind is a compile error until it gets a stem.
 */
const SCHEMA_FILE_STEMS: Record<TypedServiceExtensionKey, string> = {
  externalService: "external-service",
  internalService: "internal-service",
  implementedService: "implemented-service",
  contextService: "context-service",
  mcpService: "mcp-service",
};

function isServiceType(
  value: string | undefined,
): value is IntegrationSystemType {
  return (
    value !== undefined &&
    Object.prototype.hasOwnProperty.call(EXTENSION_KEY_BY_TYPE, value)
  );
}

/**
 * Whether a plain service document may state this type. A body claiming `CONTEXT` or `MCP` must not decide
 * the name a plain service is written under: the name and `$schema` together are what tell the backend
 * which kind of document it is reading (`ServiceTypeFiles.isContextOrMCPServiceFile`).
 */
export function isPlainServiceType(
  value: string | undefined,
): value is IntegrationSystemType {
  return isServiceType(value) && PLAIN_SERVICE_TYPES.includes(value);
}

function resolveExtensions(
  name: string,
  extensions?: ServiceExtensions,
): ServiceExtensions {
  return extensions ?? getExtensionsForFile(name);
}

/**
 * The longest extension first: which *extension a name carries* has to be the longest match, or a
 * project configuring `externalService: ".svc.yaml"` beside `internalService: ".internal.svc.yaml"`
 * reads every internal file as external.
 */
function byLongestFirst(extensions: readonly string[]): string[] {
  return [...extensions].sort((a, b) => b.length - a.length);
}

/**
 * The schema's own file name with every extension off — the part a rehost of the schema registry or
 * a truncated URI leaves alone.
 */
function schemaFileStem(schemaUrl: string): string {
  // Fragment and query off first: a `/` inside them would shift the last segment, and a JSON
  // pointer (`#/defs/external-service`) must not read as the schema's own file name.
  const path = schemaUrl.split(/[#?]/, 1)[0];
  const lastSegment = path.slice(path.lastIndexOf("/") + 1);
  const extension = lastSegment.indexOf(".");
  return extension < 0 ? lastSegment : lastSegment.slice(0, extension);
}

/**
 * The type a `$schema` states, matched in two layers. The configured URL is tried first, so a
 * project that rehosts its schemas types its own files; failing that, the schema's own file name
 * decides, which is what types a document written by an installation configured differently. A
 * project that renames the schema file itself resolves no type, and the caller says so rather than
 * guessing. Runtime Catalog resolves it the same way (`ServiceTypeFiles.typeFromSchemaUri`), and
 * `schemas/src/test/resources/naming` is where the rule is declared for both.
 */
export function serviceTypeFromSchema(
  schemaUrl: string | undefined,
  schemaUrls: ServiceSchemaUrls,
): IntegrationSystemType | undefined {
  // YAML can hand a mapping or a number here; a broken `$schema` reads as untyped, not a throw.
  if (typeof schemaUrl !== "string" || !schemaUrl) {
    return undefined;
  }
  return (
    firstTypeSpelled(schemaUrls, schemaUrl) ??
    firstTypeSpelled(SCHEMA_FILE_STEMS, schemaFileStem(schemaUrl))
  );
}

/** The kind a spelling map states {@code value} for, whichever layer the map is. */
function firstTypeSpelled(
  spellings: Record<TypedServiceExtensionKey, string>,
  value: string,
): IntegrationSystemType | undefined {
  return TYPED_ENTRIES.find(([, key]) => spellings[key] === value)?.[0];
}

/**
 * The type of a service that has already been parsed: `$schema` states it, and
 * `content.integrationSystemType` is the fallback for a pre-#553 document, whose `$schema` is the
 * plain service one. `undefined` when neither source carries a known type, so a broken file reads
 * as untyped rather than throwing.
 *
 * The **file name** is not a source. It stated a type for the length of #553 alone, and such a file
 * carries the matching `$schema` anyway.
 */
export function resolveServiceType(
  fileRef: ServiceFileRef,
  service:
    | { $schema?: string; content?: { integrationSystemType?: string } }
    | undefined,
  schemaUrls?: ServiceSchemaUrls,
): IntegrationSystemType | undefined {
  const fromSchema = serviceTypeFromSchema(
    service?.$schema,
    schemaUrls ?? getSchemaUrlsForFile(extractFilename(fileRef)),
  );
  if (fromSchema !== undefined) {
    return fromSchema;
  }
  const fromBody = service?.content?.integrationSystemType;
  return isServiceType(fromBody) ? fromBody : undefined;
}

/** Whether the file is a plain service file, of either the legacy or a typed name. */
export function isAnyServiceFile(
  fileRef: ServiceFileRef,
  extensions?: ServiceExtensions,
): boolean {
  const name = extractFilename(fileRef);
  const ext = resolveExtensions(name, extensions);
  return plainServiceExtensions(ext).some((extension) =>
    name.endsWith(extension),
  );
}

/** Whether the file is a service file of any kind, the context and MCP names included. */
export function isServiceFileOfAnyKind(
  fileRef: ServiceFileRef,
  extensions?: ServiceExtensions,
): boolean {
  const name = extractFilename(fileRef);
  const ext = resolveExtensions(name, extensions);
  return allServiceExtensions(ext).some((extension) =>
    name.endsWith(extension),
  );
}

/**
 * The extension to write a service of this type under. The three plain types share one name and
 * state their type in `$schema`; the two typeless kinds are their own kind of document and keep
 * their own name. An absent or unknown type falls back to the plain name.
 */
export function serviceExtensionForType(
  type: string | undefined,
  extensions: ServiceExtensions,
): string {
  return isServiceType(type) && !isPlainServiceType(type)
    ? extensions[EXTENSION_KEY_BY_TYPE[type]]
    : extensions.service;
}

/** The schema URL to stamp on a service of this type. Same fallback as `serviceExtensionForType`. */
export function serviceSchemaUrlForType(
  type: string | undefined,
  schemaUrls: ServiceSchemaUrls,
): string {
  return isServiceType(type)
    ? schemaUrls[EXTENSION_KEY_BY_TYPE[type]]
    : schemaUrls.service;
}

/** Every extension a plain service file can carry, the current name ahead of the per-type ones. */
export function plainServiceExtensions(
  extensions: ServiceExtensions,
): CandidateOrder {
  return candidateExtensions(NAME_SETS.plainService, extensions);
}

/** Every extension a service file of any kind can carry, current names ahead of the legacy ones. */
export function allServiceExtensions(
  extensions: ServiceExtensions,
): CandidateOrder {
  return candidateExtensions(NAME_SETS.service, extensions);
}

/**
 * Whether the name is one a write emits today, rather than a per-type name left over from #553.
 * A half-converted service has both files on disk, and this is which of the two the tree lists.
 */
export function isCurrentFormatServiceName(
  fileRef: ServiceFileRef,
  extensions?: ServiceExtensions,
): boolean {
  const name = extractFilename(fileRef);
  const ext = resolveExtensions(name, extensions);
  const carried = carriedServiceExtensionKey(name, ext);
  return carried !== undefined && NAME_SETS.service.current.includes(carried);
}

/** The id a service file name states, or nothing for a name no service extension matches. */
function splitServiceFileName(
  name: string,
  extensions: ServiceExtensions,
): string | undefined {
  const extension = byLongestFirst(allServiceExtensions(extensions)).find(
    (candidate) => name.endsWith(candidate),
  );
  return extension ? name.slice(0, -extension.length) : undefined;
}

/**
 * The id a service file name states, or `undefined` for a name no service extension matches. A
 * conversion changes the extension alone, so a read that holds no id recovers a deleted path
 * through the id the name kept.
 */
export function serviceIdFromFileName(
  fileRef: ServiceFileRef,
  extensions?: ServiceExtensions,
): string | undefined {
  const name = extractFilename(fileRef);
  return (
    splitServiceFileName(name, resolveExtensions(name, extensions)) || undefined
  );
}

/**
 * The name a service file of this type carries. Only the extension changes, so a service keeps the
 * id its folder is named after.
 *
 * Two shapes are left exactly as they are. A **dotted id**, because the backend reads the id up to
 * the first dot, so any name built from one states another id. And a file that resolves **no
 * type**: a context or MCP document carrying no `$schema` would otherwise be renamed to the plain
 * name and its original deleted, which destroys the only file the backend reads as that kind.
 */
export function serviceFileNameForType(
  fileRef: ServiceFileRef,
  type: string | undefined,
  extensions: ServiceExtensions,
): string {
  const name = extractFilename(fileRef);
  const id = splitServiceFileName(name, extensions);
  if (id === undefined || id.includes(".") || !isServiceType(type)) {
    return name;
  }
  // A non-plain type never moves a file into its family. The backend claims a context or MCP
  // document by exact URI plus its own name, so promoting a plain-named file on a stem match
  // hands it a file it refuses; such a document is written where it is.
  if (
    !isPlainServiceType(type) &&
    carriedServiceExtensionKey(name, extensions) !== EXTENSION_KEY_BY_TYPE[type]
  ) {
    return name;
  }
  return `${id}${serviceExtensionForType(type, extensions)}`;
}
