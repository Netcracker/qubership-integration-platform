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

/** The legacy type-less extension plus the three that state a plain type. */
const PLAIN_SERVICE_KEYS: readonly (keyof ServiceExtensions)[] = [
  "service",
  "externalService",
  "internalService",
  "implementedService",
];

function typedEntries(): [IntegrationSystemType, keyof ServiceExtensions][] {
  return Object.entries(EXTENSION_KEY_BY_TYPE) as [
    IntegrationSystemType,
    keyof ServiceExtensions,
  ][];
}

function isServiceType(
  value: string | undefined,
): value is IntegrationSystemType {
  return value !== undefined && value in EXTENSION_KEY_BY_TYPE;
}

function resolveExtensions(
  name: string,
  extensions?: ServiceExtensions,
): ServiceExtensions {
  return extensions ?? getExtensionsForFile(name);
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
  return typedEntries().find(([, key]) => name.endsWith(ext[key]))?.[0];
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
  return [
    extensions.externalService,
    extensions.internalService,
    extensions.implementedService,
    extensions.service,
  ];
}

/** Every extension a service file of any kind can carry, typed names ahead of the legacy one. */
export function allServiceExtensions(extensions: ServiceExtensions): string[] {
  return [
    ...typedEntries().map(([, key]) => extensions[key]),
    extensions.service,
  ];
}

/**
 * The name a service file of this type carries. Only the extension changes: the base name is what
 * the current name already states, so a service keeps the id its folder is named after. The backend
 * finds a converted dotted-id service through that folder name alone
 * (`ExportImportUtils.statesPostfix(File, String)`), and a service it cannot find is missing from an
 * import rather than reported.
 */
export function serviceFileNameForType(
  fileRef: ServiceFileRef,
  type: string | undefined,
  extensions: ServiceExtensions,
): string {
  const name = extractFilename(fileRef);
  const current = allServiceExtensions(extensions).find((extension) =>
    name.endsWith(extension),
  );
  if (!current) {
    return name;
  }
  return `${name.slice(0, -current.length)}${serviceExtensionForType(type, extensions)}`;
}
