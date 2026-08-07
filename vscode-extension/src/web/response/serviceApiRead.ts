import {
  IntegrationSystem,
  Environment,
  ApiGroup,
  Api,
  SystemOperation,
  OperationInfo,
  BaseEntity,
  IntegrationSystemType,
} from "../api-services/servicesTypes";
import { Uri } from "vscode";
import * as vscode from "vscode";
import { fileApi } from "./file/fileApiProvider";
import { RESOURCES_FOLDER } from "./file/fileApiImpl";
import { isSafeResourcePath } from "./file/resourcePath";
import { LabelUtils } from "../api-services/LabelUtils";
import {
  getExtensionsForUri,
  FileExtensionsConfig,
} from "./file/fileExtensions";
import {
  isAnyServiceFile,
  resolveServiceType,
  serviceIdFromFileName,
} from "./file/serviceFileType";
import {
  findServiceFileById,
  findServiceFiles,
  readListedServiceFile,
  UnreadableServiceFileError,
} from "./file/serviceFileLookup";
import {
  noMatchError,
  refuseUnreadableSibling,
  resolveFirstCandidate,
} from "./file/lookupOutcome";
import { Chain, ContextSystem, MCPSystem } from "@netcracker/qip-ui";
import { ContentParser } from "../api-services/parsers/ContentParser";
import { OperationSchemaExtractor } from "../api-services/parsers/OperationSchemaExtractor";
import {
  deriveMethod,
  derivePath,
  isTypedOperation,
} from "../api-services/parsers/deriveTypedMethodPath";
import { getExtendedProtocol, getSpecificationType } from "./serviceApiUtils";

export async function getCurrentServiceId(
  serviceFileUri: Uri,
): Promise<string> {
  const { serviceId } = await readServiceIdentity(serviceFileUri);
  if (!serviceId) {
    throw Error(`No service id in ${serviceFileUri.path}`);
  }
  return serviceId;
}

export async function getMainServiceFileUri(serviceFileUri: Uri): Promise<Uri> {
  return serviceFileUri;
}

export async function getMainService(serviceFileUri: Uri): Promise<any> {
  return await fileApi.getMainService(serviceFileUri);
}

/**
 * The service document, read from the file the id resolves to rather than from whichever uri the
 * caller holds. A conversion leaves the legacy sibling behind whenever the delete fails, and both
 * `getServices` and the explorer list such a service from the typed file. A read that trusted the
 * held uri would show the document that lost that precedence race. A document carrying another id
 * is a failure, not a silent read of the wrong service.
 *
 * The write sites read through this too: the file the id owns is the one every read and both lists
 * already show, so an edit applies to what the user is looking at.
 */
export async function readServiceFile(
  serviceFileUri: Uri,
  serviceId: string,
): Promise<{ fileUri: Uri; service: any }> {
  const fileUri = await resolveServiceFileUri(serviceFileUri, serviceId);
  const service = await getMainService(fileUri);
  if (service?.id !== serviceId) {
    console.error(
      `ServiceId mismatch: expected "${serviceId}", got "${service?.id}" in ${fileUri.path}`,
    );
    throw Error(
      `ServiceId mismatch: expected "${serviceId}", got "${service?.id}"`,
    );
  }
  return { fileUri, service };
}

/**
 * What a read that holds no id learns from the uri: the id the document states, and the document
 * itself when it read. A conversion changes the extension alone, so a path it deleted is recovered
 * through the id the name kept. The document decides over the name, so a hand-authored file whose
 * name and id disagree still reads as the service it states.
 */
async function readServiceIdentity(
  serviceFileUri: Uri,
): Promise<{ serviceId?: string; service?: any }> {
  try {
    const service: any = await getMainService(serviceFileUri);
    return { serviceId: service?.id, service };
  } catch (error) {
    const serviceId = serviceIdFromFileName(
      serviceFileUri,
      getExtensionsForUri(serviceFileUri),
    );
    if (!serviceId) {
      throw error;
    }
    console.warn(
      `Could not read ${serviceFileUri.path}; taking service ${serviceId} from the file name`,
      error,
    );
    return { serviceId };
  }
}

/**
 * The service file a read works from. The uri a caller holds is a hint: the id resolves through the
 * typed-wins lookup, so a uri handed out before a conversion reads neither the document that lost
 * the precedence race nor a path the conversion deleted. An id nothing resolves falls back to the
 * uri, which is how a read that starts from a file of another kind still lands in the folder it came
 * from. The fallback holds only while that uri still points at something: handing back a path that
 * is gone turns the lookup failure into a misleading read error further down.
 */
async function resolveServiceFileUri(
  currentFile: Uri,
  serviceId: string,
): Promise<Uri> {
  try {
    return await findServiceFileById(
      serviceId,
      getExtensionsForUri(currentFile),
    );
  } catch (error) {
    // The uri is no fallback for a file the lookup could not read: it is the sibling that lost the
    // precedence race, so reading it serves a superseded body and saving it destroys the file
    // nobody could read.
    if (
      error instanceof UnreadableServiceFileError ||
      !(await fileExists(currentFile))
    ) {
      throw error;
    }
    console.warn(
      `Could not resolve service ${serviceId} by id; using ${currentFile.path}`,
      error,
    );
    return currentFile;
  }
}

async function fileExists(fileUri: Uri): Promise<boolean> {
  try {
    await fileApi.getFileType(fileUri);
    return true;
  } catch {
    return false;
  }
}

/** The service id a group, api or operation id is prefixed with — a uuid's five parts. */
function serviceIdFromEntityId(entityId: string): string | undefined {
  const parts = entityId.split("-");
  return parts.length >= 5 ? parts.slice(0, 5).join("-") : undefined;
}

export async function getService(
  serviceFileUri: Uri,
  serviceId: string,
): Promise<IntegrationSystem> {
  const { fileUri, service } = await readServiceFile(serviceFileUri, serviceId);

  return toIntegrationSystem(fileUri, service);
}

/** The wire shape of a service document already read from the file that owns it. */
function toIntegrationSystem(fileUri: Uri, service: any): IntegrationSystem {
  const type = resolveServiceType(fileUri, service);

  return {
    id: service.id,
    name: service.name,
    description: service.content?.description || "",
    activeEnvironmentId: service.content?.activeEnvironmentId || "",
    integrationSystemType: type,
    type,
    protocol: (service.content?.protocol || "").toLowerCase(),
    extendedProtocol: getExtendedProtocol(service.content?.protocol),
    specification: getSpecificationType(service.content?.protocol),
    environments: service.content?.environments || [],
    labels: LabelUtils.toEntityLabels(service.content?.labels || []),
  };
}

export async function getContextService(
  serviceFileUri: Uri,
  serviceId: string,
): Promise<ContextSystem> {
  let service = await fileApi.getContextService(serviceFileUri, serviceId);

  return {
    id: service.id,
    name: service.name,
    description: service.content?.description || "",
    type: IntegrationSystemType.CONTEXT,
  };
}

export async function getContextServices(
  serviceFileUri: Uri,
): Promise<ContextSystem[]> {
  const ext = getExtensionsForUri(serviceFileUri);
  if (serviceFileUri.path.endsWith(ext.contextService)) {
    const service: any = await getMainService(serviceFileUri);
    if (!service) {
      return [];
    }

    return [await getContextService(serviceFileUri, service.id)];
  } else {
    const result: ContextSystem[] = [];
    const serviceFiles = await fileApi.findFiles(ext.contextService);
    for (const serviceFile of serviceFiles) {
      const service: any = await readListedServiceFile(serviceFile, ext);
      result.push(await getContextService(serviceFile, service.id));
    }

    return result;
  }
}

export async function getMcpServices(
  serviceFileUri: Uri,
): Promise<MCPSystem[]> {
  const ext = getExtensionsForUri(serviceFileUri);
  if (serviceFileUri.path.endsWith(ext.mcpService)) {
    const service: any = await getMainService(serviceFileUri);
    if (!service) {
      return [];
    }

    return [await getMcpService(serviceFileUri, service.id)];
  } else {
    const result: MCPSystem[] = [];
    const serviceFiles = await fileApi.findFiles(ext.mcpService);
    for (const serviceFile of serviceFiles) {
      const service: any = await readListedServiceFile(serviceFile, ext);
      result.push(await getMcpService(serviceFile, service.id));
    }

    return result;
  }
}

export async function getMcpService(
  serviceFileUri: Uri,
  serviceId: string,
): Promise<MCPSystem> {
  const service = await fileApi.getMcpService(serviceFileUri, serviceId);

  return {
    id: service.id,
    name: service.name,
    description: service.content?.description || "",
    instructions: service.content?.instructions || "",
    identifier: service.content?.identifier || "",
    labels: LabelUtils.toEntityLabels(service.content?.labels || []),
  };
}

export async function getEnvironment(
  serviceFileUri: Uri,
  serviceId: string,
  environmentId: string,
): Promise<Environment> {
  const { service } = await readServiceFile(serviceFileUri, serviceId);

  return findEnvironmentById(service.content?.environments, environmentId);
}

export async function getEnvironments(
  serviceFileUri: Uri,
  serviceId: string,
): Promise<Environment[]> {
  const { service } = await readServiceFile(serviceFileUri, serviceId);

  return parseEnvironments(service.content?.environments || []);
}

function parseEnvironment(env: any): Environment {
  return {
    id: env.id,
    name: env.name,
    description: env.description || "",
    address: env.address || "",
    sourceType: env.sourceType || "MANUAL",
    properties: env.properties || {},
    labels: LabelUtils.toEntityLabels(env.labels || []),
  };
}

function findEnvironmentById(
  environments: any[],
  environmentId: string,
): Environment {
  if (environments && environments.length) {
    for (const env of environments) {
      if (environmentId === env.id) {
        return parseEnvironment(env);
      }
    }
  }
  throw new Error(`Unable to find environment with id = ${environmentId}`);
}

function parseEnvironments(environments: any[]): Environment[] {
  const result: Environment[] = [];
  if (environments && environments.length) {
    for (const env of environments) {
      result.push(parseEnvironment(env));
    }
  }
  return result;
}

// Reads the Service → Group level. Each group's `specifications[]` wire field
// holds its APIs (see getSpecificationModel).
export async function getApiSpecifications(
  currentFile: Uri,
  serviceId: string,
): Promise<ApiGroup[]> {
  const { fileUri: serviceFileUri } = await readServiceFile(
    currentFile,
    serviceId,
  );

  const specGroupFiles =
    await fileApi.getSpecificationGroupFiles(serviceFileUri);
  const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
  const result: ApiGroup[] = [];

  // A group may have a file under both extensions. List it once, from the same file
  // ApiGroupService.resolveGroupFile picks, so the tree and the editor never disagree.
  const groupExtension = getExtensionsForUri(serviceFileUri).apiGroup;
  const parsedByGroupId = new Map<string, { fileName: string; parsed: any }>();
  for (const fileName of specGroupFiles) {
    try {
      const parsed = await fileApi.parseFile(
        vscode.Uri.joinPath(serviceFolderUri, fileName),
      );
      if (!parsed?.id) {
        continue;
      }
      const current = parsedByGroupId.get(parsed.id);
      if (!current?.fileName.endsWith(groupExtension)) {
        parsedByGroupId.set(parsed.id, { fileName, parsed });
      }
    } catch (e) {
      console.error(`Failed to parse specification group file ${fileName}`, e);
    }
  }

  for (const { fileName, parsed } of parsedByGroupId.values()) {
    try {
      if (parsed && parsed.content && parsed.content.parentId === serviceId) {
        const specifications = await getSpecificationModel(
          serviceFileUri,
          serviceId,
          parsed.id,
        );
        const chains = await getChainsUsingSpecificationGroup(
          serviceId,
          parsed.id,
        );

        const group = {
          id: parsed.id,
          name: parsed.name,
          description: parsed.content.description || "",
          specifications: specifications,
          synchronization: parsed.content.synchronization || false,
          parentId: parsed.content.parentId,
          labels: LabelUtils.toEntityLabels(parsed.content?.labels || []),
          chains: chains,
          systemId: parsed.content.parentId,
        };
        result.push(group);
      }
    } catch (e) {
      console.error(`Failed to parse specification group file ${fileName}`, e);
    }
  }

  return result;
}

export async function getLatestApiSpecification(
  currentFile: Uri,
  serviceId: string,
): Promise<Api | undefined> {
  const specGroups: ApiGroup[] = await getApiSpecifications(
    currentFile,
    serviceId,
  );

  let result;
  for (const specGroup of specGroups) {
    for (const spec of specGroup.specifications) {
      if (!result || (spec.createdWhen ?? 0) > (result.createdWhen ?? 0)) {
        result = spec;
      }
    }
  }

  return result;
}

// Reads the API level under a group: files whose parentId === groupId.
export async function getSpecificationModel(
  serviceFileUri: Uri,
  serviceId: string,
  groupId: string,
): Promise<Api[]> {
  const actualServiceFileUri = await resolveServiceFileUri(
    serviceFileUri,
    serviceId,
  );
  const ext = getExtensionsForUri(actualServiceFileUri);

  const specFiles = await fileApi.getSpecificationFiles(actualServiceFileUri);
  const serviceFolderUri = vscode.Uri.joinPath(actualServiceFileUri, "..");
  const result: Api[] = [];

  // An API may have a file under both extensions. List it once, from the newer
  // `.api.` one, the same rule the group level above follows.
  const parsedByApiId = new Map<
    string,
    { fileName: string; fileUri: Uri; parsed: any }
  >();
  for (const fileName of specFiles) {
    try {
      const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
      const parsed = await fileApi.parseFile(fileUri);
      if (!parsed?.id) {
        continue;
      }
      const current = parsedByApiId.get(parsed.id);
      if (!current?.fileName.endsWith(ext.api)) {
        parsedByApiId.set(parsed.id, { fileName, fileUri, parsed });
      }
    } catch (e) {
      console.error(`Failed to parse specification file ${fileName}`, e);
    }
  }

  for (const { fileName, fileUri, parsed } of parsedByApiId.values()) {
    try {
      if (parsed && parsed.content && parsed.content.parentId === groupId) {
        const operations = await parseOperations(
          parsed.content.operations,
          parsed.id,
        );
        const chains = await getChainsUsingSpecification(serviceId, parsed.id);

        const spec = {
          id: parsed.id,
          name: parsed.name,
          description: parsed.content.description || "",
          version: parsed.content.version || "",
          format: parsed.content.format || "",
          content: parsed.content.content || "",
          deprecated: parsed.content.deprecated || false,
          parentId: parsed.content.parentId,
          labels: LabelUtils.toEntityLabels(parsed.content?.labels || []),
          specificationGroupId: parsed.content.parentId,
          source: parsed.content.content || "",
          systemId: serviceId,
          operations: operations,
          chains: chains,
          createdWhen: await fileApi.getFileCreatedWhen(fileUri),
          specificationType: parsed.content.specificationType,
          specificationVersion: parsed.content.specificationVersion,
        };
        result.push(spec);
      }
    } catch (e) {
      console.error(`Failed to parse specification file ${fileName}`, e);
    }
  }

  return result;
}

// Reads operations for an API (`modelId` is the API's id, same as the former
// specification/model id).
export async function getOperations(
  serviceFileUri: Uri,
  modelId: string,
): Promise<SystemOperation[]> {
  const serviceId = serviceIdFromEntityId(modelId);
  const actualServiceFileUri = serviceId
    ? await resolveServiceFileUri(serviceFileUri, serviceId)
    : serviceFileUri;
  const ext = getExtensionsForUri(actualServiceFileUri);

  if (isAnyServiceFile(actualServiceFileUri, ext)) {
    const specFiles = await fileApi.getSpecificationFiles(actualServiceFileUri);
    const serviceFolderUri = vscode.Uri.joinPath(actualServiceFileUri, "..");

    for (const fileName of specFiles) {
      try {
        const specFileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
        const parsed = await fileApi.parseFile(specFileUri);

        if (parsed && parsed.id === modelId) {
          return await parseOperations(parsed.content.operations, parsed.id);
        }
      } catch (e) {
        console.error(`Failed to parse specification file ${fileName}`, e);
      }
    }
  } else {
    const specFileUri = await findModelFileById(ext, modelId);
    try {
      const parsed = await fileApi.parseFile(specFileUri);

      return await parseOperations(parsed.content.operations, parsed.id);
    } catch (e) {
      console.error(`Failed to parse specification file ${specFileUri}`, e);
    }
  }

  return [];
}

// The model file may be stored as `.specification.<app>.yaml` (today's import
// output) or `.api.<app>.yaml`, the renamed model level. Resolve by id against
// the specification extension first, then fall back to the api one — but not past
// a file the scan could not read that the fallback may be the sibling of, which is
// the pair the `.specification.` → `.api.` conversion leaves behind.
async function findModelFileById(
  ext: FileExtensionsConfig,
  modelId: string,
): Promise<Uri> {
  const names = [ext.specification, ext.api];
  return await resolveFirstCandidate(
    names,
    (extension) => fileApi.findFileById(modelId, extension),
    {
      onUnreadable: (unreadable, resolved) =>
        refuseUnreadableSibling(modelId, resolved, unreadable, names),
      onNoMatch: (failures) =>
        noMatchError(failures, () => {
          const lastError = failures.causes[failures.causes.length - 1];
          return lastError instanceof Error
            ? lastError
            : new Error(`No API file carries id ${modelId}`);
        }),
    },
  );
}

export async function getOperationInfo(
  serviceFileUri: Uri,
  operationId: string,
): Promise<OperationInfo> {
  const serviceId = serviceIdFromEntityId(operationId);
  const actualServiceFileUri = serviceId
    ? await resolveServiceFileUri(serviceFileUri, serviceId)
    : serviceFileUri;

  const specFiles = await fileApi.getSpecificationFiles(actualServiceFileUri);
  const serviceFolderUri = vscode.Uri.joinPath(actualServiceFileUri, "..");

  for (const fileName of specFiles) {
    try {
      const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
      const parsed = await ContentParser.parseContentFromFile(fileUri);

      if (parsed && parsed.content && parsed.content.operations) {
        const operation = parsed.content.operations.find((op: any) => {
          return op.id === operationId || operationId.endsWith(`-${op.id}`);
        });
        if (operation) {
          const {
            specification: derivedSpecification,
            requestSchema,
            responseSchemas,
          } = await extractOperationSchemas(
            serviceFolderUri,
            parsed.content,
            operation,
          );
          return {
            id: operation.id,
            // The stored node wins, even when it is an empty object; derivation
            // only fills a value the file does not carry (backend parity, see
            // ServiceDeserializer.fillMissingOperationSpecifications).
            specification: operation.specification ?? derivedSpecification,
            requestSchema,
            responseSchemas,
          };
        }
      }
    } catch (e) {
      console.error(`Failed to parse specification file ${fileName}`, e);
    }
  }

  throw new Error(`Operation with id ${operationId} not found`);
}

// Recomputes an operation's request/response schemas and its `specification`
// slice on demand from the raw specification source rather than trusting
// whatever was materialized onto the operation at import time (see
// OperationSchemaExtractor). A backend-exported `.api` file no longer carries
// `specification`, so without this the async MaaS classifier and the HTTP
// parameters the UI auto-fills from are lost.
//
// The extractor matches by (path, method). A backend-exported `.api` file
// carries only the typed fields for non-openapi protocols, so path/method must
// be derived the same way parseOperations derives them, or the match — and
// everything it returns — comes back empty for AsyncAPI/gRPC operations.
async function extractOperationSchemas(
  serviceFolderUri: Uri,
  specificationContent: any,
  operation: any,
): Promise<{
  specification: Record<string, unknown>;
  requestSchema: Record<string, unknown>;
  responseSchemas: Record<string, unknown>;
}> {
  try {
    const rawSource = await readMainSpecificationSource(
      serviceFolderUri,
      specificationContent,
    );
    return await OperationSchemaExtractor.extract(
      rawSource,
      specificationContent?.format,
      resolveOperationPath(operation),
      resolveOperationMethod(operation),
    );
  } catch (e) {
    console.warn(`Failed to extract schemas for operation ${operation.id}`, e);
    return { specification: {}, requestSchema: {}, responseSchemas: {} };
  }
}

// The main raw source file lives at `<serviceFolder>/resources/<filePath>`.
// The api format lists sources in `specifications[]` with `filePath`/`isRoot`;
// the legacy specification format used `specificationSources[]` with
// `fileName`/`mainSource`. Both formats are read.
async function readMainSpecificationSource(
  serviceFolderUri: Uri,
  specificationContent: any,
): Promise<string | null> {
  const fileName =
    resolveMainSourcePath(specificationContent) ??
    resolveLegacyMainSourcePath(specificationContent);
  // Reject `..` segments so a crafted source path cannot escape the service's
  // resources folder.
  if (!isSafeResourcePath(fileName)) {
    return null;
  }

  const sourceUri = vscode.Uri.joinPath(
    serviceFolderUri,
    RESOURCES_FOLDER,
    fileName,
  );
  return await fileApi.readFileContent(sourceUri);
}

// api format: `specifications[]` with `filePath`, root marked by `isRoot`.
function resolveMainSourcePath(content: any): string | undefined {
  const sources = content?.specifications;
  if (!Array.isArray(sources) || sources.length === 0) {
    return undefined;
  }
  const mainSource = sources.find((s: any) => s.isRoot) ?? sources[0];
  return mainSource?.filePath;
}

/**
 * Reads the legacy specification format: `specificationSources[]` with `fileName`, root
 * marked by `mainSource`. Superseded by the api format's `specifications[]` / `filePath` /
 * `isRoot`, and needed only while both formats are read. Drop it once every file is written
 * in the api format.
 */
function resolveLegacyMainSourcePath(content: any): string | undefined {
  const sources = content?.specificationSources;
  if (!Array.isArray(sources) || sources.length === 0) {
    return undefined;
  }
  const mainSource = sources.find((s: any) => s.mainSource) ?? sources[0];
  return mainSource?.fileName;
}

function nonEmptyString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function resolveOperationMethod(op: any): string {
  // A typed operation derives its method the backend's way (openapi uppercases its lowercase schema value), so a
  // backend `.api` file's raw `method: "get"` must not win over the derived "GET". Legacy ops carry no `type` and
  // keep their flat field.
  return (
    (isTypedOperation(op) ? deriveMethod(op) : undefined) ??
    nonEmptyString(op.method) ??
    ""
  );
}

function resolveOperationPath(op: any): string {
  return (
    nonEmptyString(op.path) ??
    (isTypedOperation(op) ? (derivePath(op) ?? undefined) : undefined) ??
    ""
  );
}

async function parseOperations(
  operations: any[],
  modelId: string,
): Promise<SystemOperation[]> {
  const result: SystemOperation[] = [];

  if (operations && Array.isArray(operations)) {
    for (const op of operations) {
      // A backend-exported `.api` file carries only the typed discriminated
      // fields for non-openapi protocols (asyncapi has no `path`;
      // protobuf/graphql/wsdl have neither `method` nor `path`). Derive the
      // missing ones the same way the backend derives its columns, or the
      // Kafka/AMQP URL fallback and every element's `integrationOperationPath`
      // read empty. Extension-written files fill the flat fields, so the
      // derivation only fires when they are absent.
      const operation: SystemOperation = {
        id: op.id,
        name: op.name,
        description: op.description || "",
        method: resolveOperationMethod(op),
        path: resolveOperationPath(op),
        modelId: modelId,
        chains: await getChainsUsingOperation(modelId, op.id),
        channel: op.channel,
        operationType: op.operationType,
        binding: op.binding,
        protocol: op.protocol,
        rpcMethod: op.rpcMethod,
        summary: op.summary,
        isDeprecated: op.isDeprecated,
        // In the api file each operation is discriminated by `type`; the flat
        // read surface names that field `operationKind` (matches the REST DTO).
        operationKind: op.type,
        package: op.package,
        service: op.service,
        sdl: op.sdl,
        javaPackage: op.javaPackage,
      };
      result.push(operation);
    }
  }

  return result;
}

async function getChainsUsingOperation(
  specificationId: string,
  operationId: string,
): Promise<BaseEntity[]> {
  const result: BaseEntity[] = [];

  await fileApi.findAndBuildChainsRecursively<BaseEntity>(
    fileApi.getRootDirectory(),
    (chainYaml: any): BaseEntity | undefined => {
      if (chainYaml.content.elements) {
        for (const element of chainYaml.content.elements) {
          if (
            element?.properties?.integrationOperationId === operationId &&
            element?.properties?.integrationSpecificationId === specificationId
          ) {
            return {
              id: chainYaml.id,
              name: chainYaml.name,
            };
          }
        }
      }
      return undefined;
    },
    result,
  );

  return result;
}

async function getChainsUsingSpecificationGroup(
  serviceId: string,
  groupId: string,
): Promise<Partial<Chain>[]> {
  const result: Partial<Chain>[] = [];

  await fileApi.findAndBuildChainsRecursively(
    fileApi.getRootDirectory(),
    (chainYaml: any): Partial<Chain> | undefined => {
      if (chainYaml.content.elements) {
        for (const element of chainYaml.content.elements) {
          if (
            element?.properties?.integrationSystemId === serviceId &&
            element?.properties?.integrationSpecificationGroupId === groupId
          ) {
            return { id: chainYaml.id, name: chainYaml.name };
          }
        }
      }
      return undefined;
    },
    result,
  );

  return result;
}

async function getChainsUsingSpecification(
  serviceId: string,
  specificationId: string,
): Promise<Partial<Chain>[]> {
  const result: Partial<Chain>[] = [];

  await fileApi.findAndBuildChainsRecursively(
    fileApi.getRootDirectory(),
    (chainYaml: any): Partial<Chain> | undefined => {
      if (chainYaml.content.elements) {
        for (const element of chainYaml.content.elements) {
          if (
            element?.properties?.integrationSystemId === serviceId &&
            element?.properties?.integrationSpecificationId === specificationId
          ) {
            return { id: chainYaml.id, name: chainYaml.name };
          }
        }
      }
      return undefined;
    },
    result,
  );

  return result;
}

export async function getServices(
  serviceFileUri: Uri,
): Promise<IntegrationSystem[]> {
  const ext = getExtensionsForUri(serviceFileUri);
  if (isAnyServiceFile(serviceFileUri, ext)) {
    // The id decides the file here too. The document already in hand answers for it whenever the id
    // resolves back to the same path, which is every service that has one file.
    const { serviceId, service } = await readServiceIdentity(serviceFileUri);
    if (!serviceId) {
      return [];
    }
    const fileUri = await resolveServiceFileUri(serviceFileUri, serviceId);
    return [
      toIntegrationSystem(
        fileUri,
        fileUri.path === serviceFileUri.path && service
          ? service
          : await getMainService(fileUri),
      ),
    ];
  }

  // A converted service keeps its legacy sibling until the delete lands, so list each id once,
  // from the file findServiceFileById would resolve — the rule ApiGroupService.resolveGroupFile
  // applies to a group. findServiceFiles yields the typed names first, so first seen wins, and the
  // document in hand is already the winning one: resolving each id again would rescan per service.
  // A listed file that cannot be read fails the listing by name rather than dropping out of it,
  // which would let the very sibling it outranks be listed in its place.
  const listedIds = new Set<string>();
  const result: IntegrationSystem[] = [];
  for (const serviceFile of await findServiceFiles(ext)) {
    const service: any = await readListedServiceFile(serviceFile, ext);
    if (!service?.id || listedIds.has(service.id)) {
      continue;
    }
    listedIds.add(service.id);
    result.push(toIntegrationSystem(serviceFile, service));
  }

  return result;
}
