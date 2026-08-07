import {
  Environment,
  EnvironmentRequest,
  IntegrationSystem,
  IntegrationSystemType,
  Api,
  ApiGroup,
  SystemRequest,
} from "../api-services/servicesTypes";
import * as yaml from "yaml";
import {
  getContextService,
  getMainService,
  getMcpService,
  getService,
  readServiceFile,
} from "./serviceApiRead";
import vscode, { ExtensionContext, Uri } from "vscode";
import { ContentParser } from "../api-services/parsers/ContentParser";
import { getExtensionsForFile } from "./file/fileExtensions";
import {
  resolveServiceType,
  serviceExtensionForType,
  serviceSchemaUrlForType,
} from "./file/serviceFileType";
import { writeServiceInCurrentFormat } from "./file/serviceFileWrite";
import { fileApi } from "./file/fileApiProvider";
import { resolveApiFiles } from "./file/entityFiles";
import { scanMissRefusal } from "./file/lookupOutcome";
import { isSafeResourcePath } from "./file/resourcePath";
import { refreshQipExplorer } from "../extension";
import { LabelUtils } from "../api-services/LabelUtils";
import { ProjectConfigService } from "../services/ProjectConfigService";
import { ContextSystem, MCPSystem } from "@netcracker/qip-ui";
import {
  getExtendedProtocol,
  getSpecificationType,
  validateAllowedSystemProtocol,
} from "./serviceApiUtils";
import { SERVICE_MIGRATIONS } from "../services/importMigrationVersions";
import { ApiGroupService } from "../api-services/ApiGroupService";

export async function updateContextService(
  serviceFileUri: Uri,
  serviceId: string,
  serviceRequest: Partial<ContextSystem>,
): Promise<ContextSystem> {
  const service = await fileApi.getContextService(serviceFileUri, serviceId);

  if (!service.content) {
    service.content = {};
  }

  if (serviceRequest.name !== undefined) {
    service.name = serviceRequest.name;
  }
  if (serviceRequest.description !== undefined) {
    service.content.description = serviceRequest.description;
  }

  const writtenFileUri = await writeMainService(serviceFileUri, service);
  const updatedService = await getContextService(writtenFileUri, serviceId);

  return updatedService;
}

export async function updateMcpService(
  serviceFileUri: Uri,
  serviceId: string,
  serviceRequest: Partial<MCPSystem>,
): Promise<MCPSystem> {
  // An MCP document claims the MCP migration list. Reading it as a context service stamps the
  // service list on it, and the backend's MCP registry holds version 100 alone — every later
  // version it named would be refused as exported from a newer version.
  const service = await fileApi.getMcpService(serviceFileUri, serviceId);

  if (!service.content) {
    service.content = {};
  }

  if (serviceRequest.name !== undefined) {
    service.name = serviceRequest.name;
  }
  if (serviceRequest.description !== undefined) {
    service.content.description = serviceRequest.description;
  }

  if (serviceRequest.instructions !== undefined) {
    service.content.instructions = serviceRequest.instructions;
  }

  if (serviceRequest.identifier !== undefined) {
    service.content.identifier = serviceRequest.identifier;
  }

  if (serviceRequest.labels !== undefined) {
    service.content.labels = LabelUtils.fromEntityLabels(serviceRequest.labels);
  }

  const writtenFileUri = await writeMainService(serviceFileUri, service);
  const updatedService = await getMcpService(writtenFileUri, serviceId);

  return updatedService;
}

export async function updateService(
  serviceFileUri: Uri,
  serviceId: string,
  serviceRequest: Partial<IntegrationSystem>,
): Promise<IntegrationSystem> {
  // The uri may name the file a conversion already replaced — the editor tab holds the one it was
  // opened on. `readServiceFile` falls back to the file the id resolves to.
  const { fileUri, service } = await readServiceFile(serviceFileUri, serviceId);

  if (service.id !== serviceId) {
    console.error(
      `ServiceId mismatch: expected ${serviceId}, got ${service.id}`,
    );
    throw Error("ServiceId mismatch");
  }

  if (!service.content) {
    service.content = {};
  }

  if (serviceRequest.name !== undefined) {
    service.name = serviceRequest.name;
  }
  if (serviceRequest.description !== undefined) {
    service.content.description = serviceRequest.description;
  }
  if (serviceRequest.labels !== undefined) {
    service.content.labels = LabelUtils.fromEntityLabels(serviceRequest.labels);
  }
  // The type is set at creation and never again, matching the backend. `type` and
  // `integrationSystemType` in the request are ignored rather than rejected, because the services
  // list posts back whatever it loaded.
  if (serviceRequest.protocol !== undefined) {
    const protocol = serviceRequest.protocol.toUpperCase();
    // The protocol is what has to fit the type now, so validate it against the type the file states.
    validateAllowedSystemProtocol(
      resolveServiceType(fileUri, service),
      protocol,
    );
    service.content.protocol = protocol;
  }
  if (serviceRequest.activeEnvironmentId !== undefined) {
    service.content.activeEnvironmentId = serviceRequest.activeEnvironmentId;
  }

  const writtenFileUri = await writeMainService(fileUri, service);
  const updatedService = await getService(writtenFileUri, serviceId);

  return updatedService;
}

export async function createService(
  context: ExtensionContext,
  mainFolderUri: Uri,
  serviceRequest: SystemRequest,
): Promise<IntegrationSystem> {
  try {
    // `crypto.randomUUID()` is dot-free, which the backend requires of an id it has to state in a
    // file name: it reads the id up to the first dot, so a dotted id names another service.
    const serviceId = crypto.randomUUID();
    const config = ProjectConfigService.getConfig();
    const type = serviceRequest.type || IntegrationSystemType.EXTERNAL;

    // A new service has neither environments nor an active one, so the file
    // carries only what the request supplied. `writeServiceFile` drops the
    // fields that stayed empty. The type is in the name, not in the content.
    const content = {
      description: serviceRequest.description,
      protocol: serviceRequest.protocol?.toUpperCase(),
      labels: LabelUtils.fromEntityLabels(serviceRequest.labels || []),
      migrations: SERVICE_MIGRATIONS,
    };
    const service = {
      $schema: serviceSchemaUrlForType(type, config.schemaUrls),
      id: serviceId,
      name: serviceRequest.name,
      content,
    };

    const serviceFolderUri = vscode.Uri.joinPath(mainFolderUri, serviceId);
    const ext = getExtensionsForFile();
    const serviceFileUri = vscode.Uri.joinPath(
      serviceFolderUri,
      `${serviceId}${serviceExtensionForType(type, ext)}`,
    );
    await fileApi.writeServiceFile(serviceFileUri, service);

    return {
      id: serviceId,
      name: serviceRequest.name,
      description: content.description || "",
      activeEnvironmentId: "",
      integrationSystemType: type,
      protocol: content.protocol || "",
      extendedProtocol: getExtendedProtocol(content.protocol),
      specification: getSpecificationType(content.protocol),
      environments: [],
      labels: LabelUtils.toEntityLabels(content.labels),
    };
  } catch (error) {
    console.error("createService: Error creating service:", error);
    throw new Error(
      `Failed to create service: ${error instanceof Error ? error.message : "Unknown error"}`,
    );
  }
}

export async function updateEnvironment(
  serviceFileUri: Uri,
  serviceId: string,
  environmentId: string,
  environmentRequest: EnvironmentRequest,
): Promise<Environment> {
  const { fileUri, service } = await readServiceFile(serviceFileUri, serviceId);
  if (service.id !== serviceId) {
    console.error(`ServiceId mismatch`);
    throw Error("ServiceId mismatch");
  }

  if (!service.content) {
    service.content = {};
  }
  if (!service.content.environments) {
    service.content.environments = [];
  }

  const environmentIndex = service.content.environments.findIndex(
    (env: any) => env.id === environmentId,
  );
  if (environmentIndex === -1) {
    console.error(`EnvironmentId not found`);
    throw Error("EnvironmentId not found");
  }

  const environment = service.content.environments[environmentIndex];

  if (environmentRequest.name !== undefined) {
    environment.name = environmentRequest.name;
  }
  if (environmentRequest.description !== undefined) {
    environment.description = environmentRequest.description;
  }
  if (environmentRequest.address !== undefined) {
    environment.address = environmentRequest.address;
  }
  if (environmentRequest.sourceType !== undefined) {
    environment.sourceType = environmentRequest.sourceType;
  }
  if (environmentRequest.properties !== undefined) {
    environment.properties = environmentRequest.properties;
  }
  if (environmentRequest.labels !== undefined) {
    environment.labels = LabelUtils.fromEntityLabels(environmentRequest.labels);
  }

  await writeMainService(fileUri, service);

  return {
    ...environment,
    labels: LabelUtils.toEntityLabels(environment.labels),
  } as Environment;
}

export async function createEnvironment(
  serviceFileUri: Uri,
  serviceId: string,
  environmentRequest: EnvironmentRequest,
): Promise<Environment> {
  const { fileUri, service } = await readServiceFile(serviceFileUri, serviceId);
  if (service.id !== serviceId) {
    console.error(`ServiceId mismatch`);
    throw Error("ServiceId mismatch");
  }

  if (!service.content) {
    service.content = {};
  }
  if (!service.content.environments) {
    service.content.environments = [];
  }

  const environmentId = crypto.randomUUID();
  // `sourceType` is a real default the schema marks required; the other fields
  // go in as given, and the response fills the blanks its type demands.
  const environment = {
    id: environmentId,
    name: environmentRequest.name,
    description: environmentRequest.description,
    address: environmentRequest.address,
    sourceType: environmentRequest.sourceType || "MANUAL",
    properties: environmentRequest.properties,
    labels: LabelUtils.fromEntityLabels(environmentRequest.labels || []),
  };

  service.content.environments.push(environment);
  await writeMainService(fileUri, service);

  return {
    ...environment,
    description: environment.description ?? "",
    properties: environment.properties ?? {},
    labels: LabelUtils.toEntityLabels(environment.labels),
  };
}

export async function deleteEnvironment(
  serviceFileUri: Uri,
  serviceId: string,
  environmentId: string,
): Promise<void> {
  const { fileUri, service } = await readServiceFile(serviceFileUri, serviceId);
  if (service.id !== serviceId) {
    console.error(`ServiceId mismatch`);
    throw Error("ServiceId mismatch");
  }

  if (!service.content) {
    service.content = {};
  }
  if (!service.content.environments) {
    service.content.environments = [];
  }

  const environmentIndex = service.content.environments.findIndex(
    (env: any) => env.id === environmentId,
  );
  if (environmentIndex === -1) {
    console.error(`EnvironmentId not found`);
    throw Error("EnvironmentId not found");
  }

  service.content.environments.splice(environmentIndex, 1);

  if (service.content.activeEnvironmentId === environmentId) {
    service.content.activeEnvironmentId = "";
  }

  await writeMainService(fileUri, service);
}

/** Returns the file the service landed in — a conversion moves it out of the one passed in. */
async function writeMainService(
  serviceFileUri: Uri,
  service: any,
): Promise<Uri> {
  return await writeServiceInCurrentFormat(serviceFileUri, service);
}

export async function updateApiSpecificationGroup(
  serviceFileUri: Uri,
  groupId: string,
  groupRequest: Partial<ApiGroup>,
): Promise<ApiGroup> {
  try {
    const { groupFile, groupInfo } = await getSpecificationFilesByGroup(
      serviceFileUri,
      groupId,
    );

    if (groupRequest.name !== undefined) {
      groupInfo.name = groupRequest.name;
    }
    if (groupRequest.description !== undefined) {
      groupInfo.description = groupRequest.description;
    }
    if ((groupRequest as any).labels !== undefined) {
      if (!groupInfo.content) {
        groupInfo.content = {};
      }
      groupInfo.content.labels = LabelUtils.fromEntityLabels(
        (groupRequest as any).labels,
      );
    }

    if (!groupInfo.content) {
      groupInfo.content = {};
    }

    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
    const groupFileUri = vscode.Uri.joinPath(serviceFolderUri, groupFile);
    const yamlContent = yaml.stringify(groupInfo);
    const bytes = new TextEncoder().encode(yamlContent);
    await fileApi.writeFile(groupFileUri, bytes);

    return {
      ...groupInfo,
      labels: LabelUtils.toEntityLabels(groupInfo.content?.labels || []),
    } as ApiGroup;
  } catch (error) {
    console.error("updateApiSpecificationGroup: Error:", error);
    vscode.window.showErrorMessage(`Failed to update API group: ${error}`);
    throw error;
  }
}

export async function updateSpecificationModel(
  serviceFileUri: Uri,
  modelId: string,
  modelRequest: Partial<Api>,
): Promise<Api> {
  try {
    const { specificationFile, specificationInfo } =
      await findSpecificationFileById(serviceFileUri, modelId);

    if (modelRequest.name !== undefined) {
      specificationInfo.name = modelRequest.name;
    }
    if (modelRequest.description !== undefined) {
      specificationInfo.description = modelRequest.description;
    }
    if ((modelRequest as any).labels !== undefined) {
      if (!specificationInfo.content) {
        specificationInfo.content = {};
      }
      specificationInfo.content.labels = LabelUtils.fromEntityLabels(
        (modelRequest as any).labels,
      );
    }
    if (modelRequest.version !== undefined) {
      if (!specificationInfo.content) {
        specificationInfo.content = {};
      }
      specificationInfo.content.version = modelRequest.version;
    }
    if (modelRequest.format !== undefined) {
      if (!specificationInfo.content) {
        specificationInfo.content = {};
      }
      specificationInfo.content.format = modelRequest.format;
    }
    if (modelRequest.content !== undefined) {
      if (!specificationInfo.content) {
        specificationInfo.content = {};
      }
      specificationInfo.content.content = modelRequest.content;
    }
    if (modelRequest.deprecated !== undefined) {
      if (!specificationInfo.content) {
        specificationInfo.content = {};
      }
      specificationInfo.content.deprecated = modelRequest.deprecated;
    }

    if (!specificationInfo.content) {
      specificationInfo.content = {};
    }

    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
    const specificationFileUri = vscode.Uri.joinPath(
      serviceFolderUri,
      specificationFile,
    );
    const yamlContent = yaml.stringify(specificationInfo);
    const bytes = new TextEncoder().encode(yamlContent);
    await fileApi.writeFile(specificationFileUri, bytes);

    await ApiGroupService.regenerateGroupApisSafely(
      serviceFileUri,
      specificationInfo.content?.parentId,
    );

    return {
      ...specificationInfo,
      labels: LabelUtils.toEntityLabels(
        specificationInfo.content?.labels || [],
      ),
    } as Api;
  } catch (error) {
    console.error("updateSpecificationModel: Error:", error);
    vscode.window.showErrorMessage(`Failed to update specification: ${error}`);
    throw error;
  }
}

export async function deprecateModel(
  serviceFileUri: Uri,
  modelId: string,
): Promise<Api> {
  try {
    const { specificationFile, specificationInfo } =
      await findSpecificationFileById(serviceFileUri, modelId);

    if (!specificationInfo.content) {
      specificationInfo.content = {};
    }
    specificationInfo.content.deprecated = true;

    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
    const specificationFileUri = vscode.Uri.joinPath(
      serviceFolderUri,
      specificationFile,
    );
    const yamlContent = yaml.stringify(specificationInfo);
    const bytes = new TextEncoder().encode(yamlContent);
    await fileApi.writeFile(specificationFileUri, bytes);

    await ApiGroupService.regenerateGroupApisSafely(
      serviceFileUri,
      specificationInfo.content?.parentId,
    );

    vscode.window.showInformationMessage(
      `API "${specificationInfo.name}" has been deprecated successfully!`,
    );

    return specificationInfo as Api;
  } catch (error) {
    console.error("[deprecateModel] Error:", error);
    vscode.window.showErrorMessage(
      `Failed to deprecate specification: ${error}`,
    );
    throw error;
  }
}

async function getSpecificationFilesByGroup(
  serviceFileUri: Uri,
  groupId: string,
): Promise<{
  groupFile: string;
  duplicateGroupFiles: string[];
  groupInfo: any;
  specificationFiles: string[];
}> {
  const service = await getMainService(serviceFileUri);
  if (!service) {
    throw new Error("Service not found");
  }

  const resolved = await ApiGroupService.resolveGroupFile(
    serviceFileUri,
    groupId,
  );
  if (!resolved) {
    throw new Error(`API group with id ${groupId} not found`);
  }

  const specificationFiles =
    await fileApi.getSpecificationFiles(serviceFileUri);
  let groupSpecificationFiles: string[] = [];

  for (const fileName of specificationFiles) {
    try {
      const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
      const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
      const parsed = await ContentParser.parseContentFromFile(fileUri);

      if (parsed?.content?.parentId === groupId) {
        groupSpecificationFiles.push(fileName);
      }
    } catch (error) {
      console.error(`Error reading specification file ${fileName}:`, error);
    }
  }

  return {
    groupFile: resolved.fileName,
    duplicateGroupFiles: resolved.duplicates,
    groupInfo: resolved.info,
    specificationFiles: groupSpecificationFiles,
  };
}

/**
 * The file an API id owns, for a write. It is the file every read of that API already shows — the
 * `.api.` one where both names exist — rather than whichever name the directory listed first, and a
 * sibling the scan could not read refuses instead of handing the write to the other name.
 *
 * `duplicates` are the same-id files under the other name. A write ignores them, because the file
 * the reads answer from is the one an edit belongs in; a delete removes them, or the API comes back
 * from the sibling on the next read.
 */
async function findSpecificationFileById(
  serviceFileUri: Uri,
  modelId: string,
): Promise<{
  specificationFile: string;
  specificationInfo: any;
  duplicates: { fileName: string; specificationInfo: any }[];
}> {
  const apiFiles = await resolveApiFiles(serviceFileUri);
  const resolved = apiFiles.byId.get(modelId);

  if (!resolved) {
    throw (
      scanMissRefusal(modelId, apiFiles.unreadable, "API ") ??
      new Error(`API with id ${modelId} not found`)
    );
  }

  return {
    specificationFile: resolved.fileName,
    specificationInfo: resolved.parsed,
    duplicates: resolved.duplicates.map((duplicate) => ({
      fileName: duplicate.fileName,
      specificationInfo: duplicate.parsed,
    })),
  };
}

async function deleteSourceFilesFromSpecificationSources(
  serviceFileUri: Uri,
  specificationInfo: any,
): Promise<void> {
  // The api format renames `specificationSources[]` to `specifications[]` and
  // `fileName` to `filePath`. Read both, or an api-format file's source files
  // are never deleted — a silent leak.
  const sources =
    specificationInfo.content?.specifications ??
    specificationInfo.content?.specificationSources;
  if (!Array.isArray(sources) || sources.length === 0) {
    return;
  }

  const foldersToCheck: string[] = [];

  for (const source of sources) {
    try {
      const filePath = source.filePath ?? source.fileName;
      if (filePath && !isSafeResourcePath(filePath)) {
        // A `..` segment could delete a file outside the service's resources
        // folder. Skip; do not echo the offending path.
        console.warn(
          "Skipped an API source file with an unsafe path during delete.",
        );
        continue;
      }
      if (filePath) {
        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
        const sourceFileUri = vscode.Uri.joinPath(
          serviceFolderUri,
          "resources",
          filePath,
        );

        try {
          await fileApi.deleteFile(sourceFileUri);
          const folderPath = filePath.split("/")[0];
          if (folderPath && !foldersToCheck.includes(folderPath)) {
            foldersToCheck.push(folderPath);
          }
        } catch (error) {
          if (
            !(error instanceof Error && error.message.includes("not empty"))
          ) {
            console.error(`Error deleting source file ${filePath}:`, error);
          }
        }
      }
    } catch (error) {
      console.error(
        `Error processing source file ${source.filePath ?? source.fileName}:`,
        error,
      );
    }
  }

  for (const folderName of foldersToCheck) {
    try {
      const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
      const folderUri = vscode.Uri.joinPath(
        serviceFolderUri,
        "resources",
        folderName,
      );
      await fileApi.deleteFile(folderUri);
    } catch (error) {
      if (!(error instanceof Error && error.message.includes("not empty"))) {
        console.error(`Error checking folder ${folderName}:`, error);
      }
    }
  }
}

export async function deleteSpecificationGroup(
  serviceFileUri: Uri,
  groupId: string,
): Promise<void> {
  try {
    const { groupFile, duplicateGroupFiles, groupInfo, specificationFiles } =
      await getSpecificationFilesByGroup(serviceFileUri, groupId);

    for (const specFileName of specificationFiles) {
      try {
        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
        const fileUri = vscode.Uri.joinPath(serviceFolderUri, specFileName);
        const specInfo = await ContentParser.parseContentFromFile(fileUri);

        await deleteSourceFilesFromSpecificationSources(
          serviceFileUri,
          specInfo,
        );
      } catch (error) {
        console.error(
          `Error processing specification file ${specFileName}:`,
          error,
        );
      }
    }

    for (const specFileName of specificationFiles) {
      try {
        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
        const fileUri = vscode.Uri.joinPath(serviceFolderUri, specFileName);
        await fileApi.deleteFile(fileUri);
      } catch (error) {
        console.error(
          `Error deleting specification file ${specFileName}:`,
          error,
        );
      }
    }

    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
    // Delete every file that carries this group id, not only the resolved one. A leftover sibling under the
    // other group extension would resurrect the group on the next read, with its APIs already gone.
    for (const fileName of [groupFile, ...duplicateGroupFiles]) {
      await fileApi.deleteFile(vscode.Uri.joinPath(serviceFolderUri, fileName));
    }

    vscode.window.showInformationMessage(
      `API group "${groupInfo.name}" has been deleted successfully!`,
    );
  } catch (error) {
    console.error("deleteSpecificationGroup: Error:", error);
    vscode.window.showErrorMessage(`Failed to delete API group: ${error}`);
    throw error;
  }
}

export async function deleteSpecificationModel(
  serviceFileUri: Uri,
  modelId: string,
): Promise<void> {
  try {
    const { specificationFile, specificationInfo, duplicates } =
      await findSpecificationFileById(serviceFileUri, modelId);
    const parentId = specificationInfo.content?.parentId;

    // Every file that carries this API id, not only the resolved one — the same rule the group
    // delete follows. A leftover sibling under the other API extension resurrects the API on the
    // next read, with its source files and its place in the group already gone. The sources come
    // from each file: a superseded sibling may name sources the current one no longer does.
    const deleted = [
      { fileName: specificationFile, specificationInfo },
      ...duplicates,
    ];
    for (const file of deleted) {
      await deleteSourceFilesFromSpecificationSources(
        serviceFileUri,
        file.specificationInfo,
      );
    }

    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
    for (const file of deleted) {
      await fileApi.deleteFile(
        vscode.Uri.joinPath(serviceFolderUri, file.fileName),
      );
    }

    // The deleted API's file is gone, so rescanning drops its id from apis[].
    await ApiGroupService.regenerateGroupApisSafely(serviceFileUri, parentId);

    vscode.window.showInformationMessage(
      `API "${specificationInfo.name}" has been deleted successfully!`,
    );
  } catch (error) {
    console.error("[deleteSpecificationModel] Error:", error);
    vscode.window.showErrorMessage(`Failed to delete specification: ${error}`);
    throw error;
  }
}

export async function createEmptyService() {
  try {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders) {
      vscode.window.showErrorMessage("Open a workspace folder first");
      return;
    }

    const serviceName = await vscode.window.showInputBox({
      prompt: "Enter new service name",
      placeHolder: "My Service",
      validateInput: (value) => {
        if (!value || value.trim().length === 0) {
          return "Service name cannot be empty";
        }
        if (value.trim().length > 128) {
          return "Service name cannot be longer than 128 characters";
        }
        return null;
      },
    });

    if (!serviceName) {
      return;
    }

    const serviceType = await vscode.window.showQuickPick(
      [
        {
          label: "External",
          value: IntegrationSystemType.EXTERNAL,
          description: "External service",
        },
        {
          label: "Internal",
          value: IntegrationSystemType.INTERNAL,
          description: "Internal service",
        },
        {
          label: "Implemented",
          value: IntegrationSystemType.IMPLEMENTED,
          description: "Implemented service",
        },
      ],
      {
        placeHolder: "Select service type",
        canPickMany: false,
      },
    );

    if (!serviceType) {
      return;
    }

    const serviceDescription = await vscode.window.showInputBox({
      prompt: "Enter service description (optional)",
      placeHolder: "Description of the service",
      validateInput: (value) => {
        if (value && value.trim().length > 512) {
          return "Description cannot be longer than 512 characters";
        }
        return null;
      },
    });

    const serviceRequest: SystemRequest = {
      name: serviceName.trim(),
      description: serviceDescription?.trim() || "",
      type: serviceType.value,
      protocol: "",
      labels: [],
    };

    const service = await createService(
      {} as ExtensionContext,
      workspaceFolders[0].uri,
      serviceRequest,
    );

    refreshQipExplorer();

    vscode.window.showInformationMessage(
      `Service "${serviceName}" created successfully with type ${serviceType.label} in folder ${service.id}`,
    );
    return service;
  } catch (err) {
    vscode.window.showErrorMessage(`Failed to create service: ${err}`);
    throw err;
  }
}
