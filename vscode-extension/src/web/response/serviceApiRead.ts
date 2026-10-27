import type {
  BaseEntity,
  ContextSystem,
  Environment,
  IntegrationSystem,
  MCPSystem,
  OperationInfo,
  Specification,
  SpecificationGroup,
  SystemOperation,
} from "@netcracker/qip-ui";
import { IntegrationSystemType } from "../api-services/servicesTypes";
import { Uri } from "vscode";
import * as vscode from "vscode";
import { fileApi } from "./file/fileApiProvider";
import { LabelUtils } from "../api-services/LabelUtils";
import { FILE_EXTENSIONS } from "./file/fileExtensions";
import { ContentParser } from "../api-services/parsers/ContentParser";

export async function getCurrentServiceId(
  serviceFileUri: Uri,
): Promise<string> {
  const service: any = await getMainService(serviceFileUri);
  return service.id;
}

export async function getMainServiceFileUri(serviceFileUri: Uri): Promise<Uri> {
  return serviceFileUri;
}

export async function getMainService(serviceFileUri: Uri): Promise<any> {
  return await fileApi.getMainService(serviceFileUri);
}

export async function getService(
  serviceFileUri: Uri,
  serviceId: string,
): Promise<IntegrationSystem> {
  let actualServiceFileUri = serviceFileUri;
  let service: any = await getMainService(serviceFileUri);
  if (service.id !== serviceId) {
    const ext = FILE_EXTENSIONS;
    actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
    service = await getMainService(actualServiceFileUri);

    if (service.id !== serviceId) {
      console.error(
        `ServiceId mismatch: expected "${serviceId}", got "${service.id}" even after finding file by ID`,
      );
      throw Error(
        `ServiceId mismatch: expected "${serviceId}", got "${service.id}"`,
      );
    }
  }

  // SOAP travels over the HTTP sender, so the catalog reports it as `http`:
  // `OperationProtocol.SOAP` is ("http", "soap") and `SystemMapper` exposes `protocol.value`.
  // Element templates branch on this value and none of them knows `soap`.
  const storedProtocol = (service.content?.protocol || "").toLowerCase();

  return {
    id: service.id,
    name: service.name,
    description: service.content?.description || "",
    activeEnvironmentId: service.content?.activeEnvironmentId || "",
    type: service.content?.integrationSystemType || "",
    internalServiceName: "",
    protocol: storedProtocol === "soap" ? "http" : storedProtocol,
    extendedProtocol: service.content?.extendedProtocol || "",
    specification: service.content?.specification || "",
    labels: LabelUtils.toEntityLabels(service.content?.labels || []),
  };
}

export async function getContextService(
  serviceFileUri: Uri,
  serviceId: string,
): Promise<ContextSystem> {
  // A chain asks for the context services its elements use, which live in their own files.
  const ext = FILE_EXTENSIONS;
  const contextServiceFileUri = serviceFileUri.path.endsWith(ext.contextService)
    ? serviceFileUri
    : await fileApi.findFileById(serviceId, ext.contextService);
  let service = await fileApi.getContextService(
    contextServiceFileUri,
    serviceId,
  );

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
  const ext = FILE_EXTENSIONS;
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
      const service: any = await getMainService(serviceFile);
      result.push(await getContextService(serviceFile, service.id));
    }

    return result;
  }
}

export async function getMcpServices(
  serviceFileUri: Uri,
): Promise<MCPSystem[]> {
  const ext = FILE_EXTENSIONS;
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
      const service: any = await getMainService(serviceFile);
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
  let actualServiceFileUri = serviceFileUri;
  let service: any = await getMainService(serviceFileUri);

  if (service.id !== serviceId) {
    const ext = FILE_EXTENSIONS;
    actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
    service = await getMainService(actualServiceFileUri);

    if (service.id !== serviceId) {
      console.error(
        `ServiceId mismatch: expected "${serviceId}", got "${service.id}"`,
      );
      throw Error("ServiceId mismatch");
    }
  }

  return findEnvironmentById(
    service.content?.environments,
    environmentId,
    serviceId,
  );
}

export async function getEnvironments(
  serviceFileUri: Uri,
  serviceId: string,
): Promise<Environment[]> {
  let actualServiceFileUri = serviceFileUri;
  let service: any = await getMainService(serviceFileUri);

  if (service.id !== serviceId) {
    const ext = FILE_EXTENSIONS;
    actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
    service = await getMainService(actualServiceFileUri);

    if (service.id !== serviceId) {
      console.error(
        `ServiceId mismatch: expected "${serviceId}", got "${service.id}"`,
      );
      throw Error("ServiceId mismatch");
    }
  }

  return parseEnvironments(service.content?.environments || [], serviceId);
}

function parseEnvironment(env: any, systemId: string): Environment {
  return {
    id: env.id,
    systemId,
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
  systemId: string,
): Environment {
  if (environments && environments.length) {
    for (const env of environments) {
      if (environmentId === env.id) {
        return parseEnvironment(env, systemId);
      }
    }
  }
  throw new Error(`Unable to find environment with id = ${environmentId}`);
}

function parseEnvironments(
  environments: any[],
  systemId: string,
): Environment[] {
  const result: Environment[] = [];
  if (environments && environments.length) {
    for (const env of environments) {
      result.push(parseEnvironment(env, systemId));
    }
  }
  return result;
}

export async function getApiSpecifications(
  currentFile: Uri,
  serviceId: string,
): Promise<SpecificationGroup[]> {
  const ext = FILE_EXTENSIONS;
  const serviceFileUri = currentFile.path.endsWith(ext.service)
    ? currentFile
    : await fileApi.findFileById(serviceId, ext.service);

  const service: any = await getMainService(serviceFileUri);

  if (service.id !== serviceId) {
    console.error(
      `ServiceId mismatch: expected ${serviceId}, got ${service.id}`,
    );
    throw Error("ServiceId mismatch");
  }

  const specGroupFiles =
    await fileApi.getSpecificationGroupFiles(serviceFileUri);
  const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
  const result: SpecificationGroup[] = [];

  for (const fileName of specGroupFiles) {
    try {
      const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
      const parsed = await fileApi.parseFile(fileUri);

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
          specifications: specifications,
          synchronization: parsed.content.synchronization || false,
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
): Promise<Specification | undefined> {
  const specGroups: SpecificationGroup[] = await getApiSpecifications(
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

export async function getSpecificationModel(
  serviceFileUri: Uri,
  serviceId: string,
  groupId: string,
): Promise<Specification[]> {
  let actualServiceFileUri = serviceFileUri;
  const ext = FILE_EXTENSIONS;

  if (!serviceFileUri.path.endsWith(ext.service)) {
    try {
      actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
    } catch (e) {
      console.warn(
        `Could not find service file for ${serviceId}, using original URI`,
      );
    }
  }

  const specFiles = await fileApi.getSpecificationFiles(actualServiceFileUri);
  const serviceFolderUri = vscode.Uri.joinPath(actualServiceFileUri, "..");
  const result: Specification[] = [];

  for (const fileName of specFiles) {
    try {
      const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
      const parsed = await fileApi.parseFile(fileUri);

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
          deprecated: parsed.content.deprecated || false,
          labels: LabelUtils.toEntityLabels(parsed.content?.labels || []),
          specificationGroupId: parsed.content.parentId,
          source: parsed.content.content || "",
          systemId: serviceId,
          operations: operations,
          chains: chains,
          createdWhen: await fileApi.getFileCreatedWhen(fileUri),
        };
        result.push(spec);
      }
    } catch (e) {
      console.error(`Failed to parse specification file ${fileName}`, e);
    }
  }

  return result;
}

export async function getSpecificationModelSource(
  modelId: string,
): Promise<string> {
  const specFileUri = await fileApi.findFileById(
    modelId,
    FILE_EXTENSIONS.specification,
  );
  const parsed = await fileApi.parseFile(specFileUri);
  const mainSource = parsed.content.specificationSources.find(
    (source: any) => source.mainSource,
  );
  return await fileApi.readFileContent(
    vscode.Uri.joinPath(specFileUri, "..", "resources", mainSource.fileName),
  );
}

export async function getOperations(
  serviceFileUri: Uri,
  modelId: string,
): Promise<SystemOperation[]> {
  const ext = FILE_EXTENSIONS;
  let actualServiceFileUri = serviceFileUri;

  const parts = modelId.split("-");
  if (parts.length >= 5 && !serviceFileUri.path.endsWith(ext.service)) {
    const serviceId = parts.slice(0, 5).join("-");
    try {
      actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
    } catch (e) {
      console.warn(
        `Could not find service file for ${serviceId}, using original URI`,
      );
    }
  }

  if (actualServiceFileUri.path.endsWith(ext.service)) {
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
    const specFileUri = await fileApi.findFileById(modelId, ext.specification);
    try {
      const parsed = await fileApi.parseFile(specFileUri);

      return await parseOperations(parsed.content.operations, parsed.id);
    } catch (e) {
      console.error(`Failed to parse specification file ${specFileUri}`, e);
    }
  }

  return [];
}

export async function getOperationInfo(
  serviceFileUri: Uri,
  operationId: string,
): Promise<OperationInfo> {
  let actualServiceFileUri = serviceFileUri;

  const parts = operationId.split("-");
  if (parts.length >= 5) {
    const serviceId = parts.slice(0, 5).join("-");
    const service: any = await getMainService(serviceFileUri);

    if (service.id !== serviceId) {
      const ext = FILE_EXTENSIONS;
      try {
        actualServiceFileUri = await fileApi.findFileById(
          serviceId,
          ext.service,
        );
      } catch (e) {
        console.warn(
          `Could not find service file for ${serviceId}, using original URI`,
        );
      }
    }
  }

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
          return {
            id: operation.id,
            specification: operation.specification || {},
            requestSchema: operation.requestSchema || {},
            responseSchemas: operation.responseSchemas || {},
          };
        }
      }
    } catch (e) {
      console.error(`Failed to parse specification file ${fileName}`, e);
    }
  }

  throw new Error(`Operation with id ${operationId} not found`);
}

async function parseOperations(
  operations: any[],
  modelId: string,
): Promise<SystemOperation[]> {
  const result: SystemOperation[] = [];

  if (operations && Array.isArray(operations)) {
    for (const op of operations) {
      const operation: SystemOperation = {
        id: op.id,
        name: op.name,
        description: op.description || "",
        method: op.method || "",
        path: op.path || "",
        modelId: modelId,
        chains: await getChainsUsingOperation(modelId, op.id),
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
              description: chainYaml.content.description,
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
): Promise<BaseEntity[]> {
  const result: BaseEntity[] = [];

  await fileApi.findAndBuildChainsRecursively(
    fileApi.getRootDirectory(),
    (chainYaml: any): BaseEntity | undefined => {
      if (chainYaml.content.elements) {
        for (const element of chainYaml.content.elements) {
          if (
            element?.properties?.integrationSystemId === serviceId &&
            element?.properties?.integrationSpecificationGroupId === groupId
          ) {
            return {
              id: chainYaml.id,
              name: chainYaml.name,
              description: chainYaml.content.description,
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

async function getChainsUsingSpecification(
  serviceId: string,
  specificationId: string,
): Promise<BaseEntity[]> {
  const result: BaseEntity[] = [];

  await fileApi.findAndBuildChainsRecursively(
    fileApi.getRootDirectory(),
    (chainYaml: any): BaseEntity | undefined => {
      if (chainYaml.content.elements) {
        for (const element of chainYaml.content.elements) {
          if (
            element?.properties?.integrationSystemId === serviceId &&
            element?.properties?.integrationSpecificationId === specificationId
          ) {
            return {
              id: chainYaml.id,
              name: chainYaml.name,
              description: chainYaml.content.description,
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

export async function getServices(
  serviceFileUri: Uri,
): Promise<IntegrationSystem[]> {
  const ext = FILE_EXTENSIONS;
  if (serviceFileUri.path.endsWith(ext.service)) {
    const service: any = await getMainService(serviceFileUri);
    if (!service) {
      return [];
    }

    return [await getService(serviceFileUri, service.id)];
  } else {
    const result: IntegrationSystem[] = [];
    const serviceFiles = await fileApi.findFiles(ext.service);
    for (const serviceFile of serviceFiles) {
      const service: any = await getMainService(serviceFile);
      result.push(await getService(serviceFile, service.id));
    }

    return result;
  }
}
