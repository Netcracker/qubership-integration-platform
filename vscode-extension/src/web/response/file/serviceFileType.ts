import {
  extractFilename,
  FileExtensionsConfig,
  getExtensionsForFile,
} from "./fileExtensions";
import { IntegrationSystemType } from "../../api-services/servicesTypes";

/** A `vscode.Uri`, any `{ path }` shape, or a bare file name. */
export type ServiceFileRef = string | { path: string };

/**
 * The extension keys a service file can carry. Both `FileExtensionsConfig` and
 * `ProjectConfig["extensions"]` satisfy it, so either can be passed in.
 */
export type ServiceExtensions = Pick<
  FileExtensionsConfig,
  | "service"
  | "externalService"
  | "internalService"
  | "implementedService"
  | "contextService"
  | "mcpService"
>;

// The `Record` keyed by the enum makes a new service type a compile error until it gets an extension.
const EXTENSION_KEY_BY_TYPE: Record<
  IntegrationSystemType,
  keyof ServiceExtensions
> = {
  [IntegrationSystemType.EXTERNAL]: "externalService",
  [IntegrationSystemType.INTERNAL]: "internalService",
  [IntegrationSystemType.IMPLEMENTED]: "implementedService",
  [IntegrationSystemType.CONTEXT]: "contextService",
  [IntegrationSystemType.MCP]: "mcpService",
};

/**
 * The `schemaUrls` entries that pair with the service extensions, one per kind. `ProjectConfig["schemaUrls"]`
 * satisfies it, so a config object can be passed straight in.
 */
export type ServiceSchemaUrls = Record<keyof ServiceExtensions, string>;

/** The three types a plain service document can state. Context and MCP are separate kinds of document. */
const PLAIN_SERVICE_TYPES: readonly IntegrationSystemType[] = [
  IntegrationSystemType.EXTERNAL,
  IntegrationSystemType.INTERNAL,
  IntegrationSystemType.IMPLEMENTED,
];

/** The three extensions that state a plain type, ahead of the legacy type-less one. */
const PLAIN_SERVICE_KEYS: readonly (keyof ServiceExtensions)[] = [
  ...PLAIN_SERVICE_TYPES.map((type) => EXTENSION_KEY_BY_TYPE[type]),
  "service",
];

const TYPED_ENTRIES = Object.entries(EXTENSION_KEY_BY_TYPE) as [
  IntegrationSystemType,
  keyof ServiceExtensions,
][];

function isServiceType(
  value: string | undefined,
): value is IntegrationSystemType {
  return (
    value !== undefined &&
    Object.prototype.hasOwnProperty.call(EXTENSION_KEY_BY_TYPE, value)
  );
}

/**
 * Whether a plain service document may state this type. A body claiming `CONTEXT` or `MCP` must not
 * decide the name a plain service is written under: the name and `$schema` together are what tell the
 * backend which kind of document it is reading (`ServiceTypeFiles.isContextOrMCPServiceFile`).
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
 * Matching order: the longest extension first. Declaration order settles which *file* wins when a
 * service has several, but which *extension a name carries* has to be the longest match, or a project
 * configuring `externalService: ".svc.yaml"` beside `internalService: ".internal.svc.yaml"` reads every
 * internal file as external.
 */
function byLongestFirst(extensions: string[]): string[] {
  return [...extensions].sort((a, b) => b.length - a.length);
}

/**
 * The type a service file states in its name, or `undefined` for the legacy
 * type-less `.service.` name and for anything that is not a service file.
 *
 * Every match compares the *whole* extension — `.external-service.qip.yaml`, app name
 * included — and `endsWith` anchors it at the end of the name. So a postfix appearing
 * inside an id or an app name cannot shadow a type, and `.external-service.` cannot
 * end-match `.service.`, because the character before `service` is `-` rather than `.`.
 * That is the same rule that has kept `.context-service.` safe.
 */
export function serviceTypeFromUri(
  fileRef: ServiceFileRef,
  extensions?: ServiceExtensions,
): IntegrationSystemType | undefined {
  const name = extractFilename(fileRef);
  const ext = resolveExtensions(name, extensions);
  const longest = byLongestFirst(TYPED_ENTRIES.map(([, key]) => ext[key]));
  const matched = longest.find((extension) => name.endsWith(extension));
  return TYPED_ENTRIES.find(([, key]) => ext[key] === matched)?.[0];
}

/**
 * The type of a service that has already been parsed: the file name states it, and
 * `content.integrationSystemType` is the fallback for the legacy type-less name. Empty when
 * neither source carries one, so a broken file reads as untyped rather than throwing.
 */
export function resolveServiceType(
  fileRef: ServiceFileRef,
  service: { content?: { integrationSystemType?: string } } | undefined,
  extensions?: ServiceExtensions,
): IntegrationSystemType {
  return (serviceTypeFromUri(fileRef, extensions) ??
    service?.content?.integrationSystemType ??
    "") as IntegrationSystemType;
}

/** Whether the file is a plain service file, of either the legacy or a typed name. */
export function isAnyServiceFile(
  fileRef: ServiceFileRef,
  extensions?: ServiceExtensions,
): boolean {
  const name = extractFilename(fileRef);
  const ext = resolveExtensions(name, extensions);
  return PLAIN_SERVICE_KEYS.some((key) => name.endsWith(ext[key]));
}

/**
 * The extension to write a service of this type under. A type that is absent or
 * unknown falls back to the legacy name, which states its type in the body instead.
 */
export function serviceExtensionForType(
  type: string | undefined,
  extensions: ServiceExtensions,
): string {
  return isServiceType(type)
    ? extensions[EXTENSION_KEY_BY_TYPE[type]]
    : extensions.service;
}

/**
 * The schema URL to stamp on a service of this type. Same fallback as `serviceExtensionForType`:
 * an absent or unknown type gets the legacy service schema, which is the one that requires the
 * type in the body.
 */
export function serviceSchemaUrlForType(
  type: string | undefined,
  schemaUrls: ServiceSchemaUrls,
): string {
  return isServiceType(type)
    ? schemaUrls[EXTENSION_KEY_BY_TYPE[type]]
    : schemaUrls.service;
}

/** Every extension a plain service file can carry, in write-preference order. */
export function plainServiceExtensions(
  extensions: ServiceExtensions,
): string[] {
  return PLAIN_SERVICE_KEYS.map((key) => extensions[key]);
}

/** Every extension a service file of any kind can carry, typed names ahead of the legacy one. */
export function allServiceExtensions(extensions: ServiceExtensions): string[] {
  return [
    ...TYPED_ENTRIES.map(([, key]) => extensions[key]),
    extensions.service,
  ];
}

/** The id a service file name states, and the extension carrying it, or nothing for a non-service name. */
function splitServiceFileName(
  name: string,
  extensions: ServiceExtensions,
): { id: string; extension: string } | undefined {
  const extension = byLongestFirst(allServiceExtensions(extensions)).find(
    (candidate) => name.endsWith(candidate),
  );
  return extension
    ? { id: name.slice(0, -extension.length), extension }
    : undefined;
}

/**
 * Whether the backend can read a type off this name. It reads the id up to the first dot and the
 * postfix in the segment right after it (`ExportImportUtils.statesPostfix`), so a typed name is
 * readable only when the id is one dot-free segment — the rule `fitsCurrentFormatFileName` states.
 */
export function fileNameStatesType(
  fileRef: ServiceFileRef,
  extensions?: ServiceExtensions,
): boolean {
  const name = extractFilename(fileRef);
  const ext = resolveExtensions(name, extensions);
  const split = splitServiceFileName(name, ext);
  return (
    split !== undefined &&
    split.extension !== ext.service &&
    !split.id.includes(".")
  );
}

/**
 * The name a service file of this type carries. Only the extension changes: the base name is what
 * the current name already states, so a service keeps the id its folder is named after.
 *
 * A dotted id keeps the name it has. A typed name built from one states another id — the backend
 * reads the id up to the first dot — so it resolves no type at all, while discovery still finds the
 * file through the folder (`ExportImportUtils.statesPostfix(File, String)`). Such a service stays in
 * the legacy format, where the type is in the body, rather than becoming a file the backend refuses.
 */
export function serviceFileNameForType(
  fileRef: ServiceFileRef,
  type: string | undefined,
  extensions: ServiceExtensions,
): string {
  const name = extractFilename(fileRef);
  const split = splitServiceFileName(name, extensions);
  if (!split || split.id.includes(".")) {
    return name;
  }
  return `${split.id}${serviceExtensionForType(type, extensions)}`;
}
