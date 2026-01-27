import vscode, { ExtensionContext, Uri } from "vscode";
import {
  changeFolder,
  createConnection,
  createElement,
  createMaskedField,
  deleteConnections,
  deleteElements,
  deleteMaskedFields,
  groupElements,
  ungroupElements,
  transferElement,
  updateChain,
  updateElement,
  updateMaskedField,
} from "./chainApiModify";
import {
  getChain,
  getChainFileUri,
  getConnections,
  getElements,
  getElementsByType,
  getLibrary,
  getLibraryElementByType,
  getMaskedFields,
  findChainByElementId,
} from "./chainApiRead";
import {
  getApiSpecifications,
  getContextService,
  getContextServices,
  getEnvironments,
  getOperationInfo,
  getOperations,
  getService,
  getServices,
  getSpecificationModel,
} from "./serviceApiRead";
import {
  createEnvironment,
  deleteEnvironment,
  deleteSpecificationGroup,
  deleteSpecificationModel,
  deprecateModel,
  updateApiSpecificationGroup,
  updateContextService,
  updateEnvironment,
  updateService,
  updateSpecificationModel,
} from "./serviceApiModify";
import { fileApi } from "./file";
import { getChainUri } from "./chainApiUtils";
import {
  getServiceOperationsUri,
  getServiceSpecificationsUri,
  getServiceUri,
  handleCreateService,
  handleGetImportSpecificationResult,
  handleImportSpecification,
  handleImportSpecificationGroup,
  handleGetSpecApiFiles,
  handleReadSpecificationFileContent,
  QipFileType,
  getContextServiceUri,
} from "./serviceApiUtils";
import {
  VSCodeMessage,
  AppExtensionProps,
  IconOverrides,
} from "@netcracker/qip-ui";
import { getAndClearNavigationStateValue } from "./navigationUtils";

let lastWebviewPath: string | undefined = undefined;

export async function getApiResponse(
  message: VSCodeMessage<any>,
  openedDocumentFolderUri: Uri | undefined,
  context?: ExtensionContext,
): Promise<any> {
  let fileUri: Uri;
  if (openedDocumentFolderUri) {
    fileUri = openedDocumentFolderUri;
  } else {
    const workspaceUri = vscode.workspace.workspaceFolders?.[0]?.uri;
    if (!workspaceUri) {
      throw new Error("No workspace folder or opened document found");
    }
    fileUri = workspaceUri;
  }

  switch (message.type) {
    case "startup":
      return getExtensionConfiguration();
    case "navigate":
      if (message.payload?.path) {
        if (lastWebviewPath === message.payload.path) {
          return;
        }
        lastWebviewPath = message.payload.path;
        const parsedPath = await parseNavigatePath(
          message.payload.path,
          fileUri,
        );
        return parsedPath;
      }

      const pathToNavigateFromContext =
        context && (await getAndClearNavigationStateValue(context, fileUri));

      if (pathToNavigateFromContext) {
        return await parseNavigatePath(pathToNavigateFromContext, fileUri);
      } else {
        return await getNavigateUri(fileUri);
      }
    case "navigateInNewTab":
      return await fileApi.findFileByNavigationPath(message.payload);
    case "getChain":
      return await getChain(fileUri, message.payload);
    case "openChainInNewTab":
      return await getChainFileUri(message.payload);
    case "getElements":
      return await getElements(fileUri, message.payload);
    case "getElementsByType":
      return await getElementsByType(
        fileUri,
        message.payload.chainId,
        message.payload.elementType,
      );
    case "getConnections":
      return await getConnections(fileUri, message.payload);
    case "getLibrary":
      return await getLibrary();
    case "getLibraryElementByType":
      return await getLibraryElementByType(message.payload);
    case "updateElement":
      return await updateElement(
        fileUri,
        message.payload.chainId,
        message.payload.elementId,
        message.payload.elementRequest,
      );
    case "createElement":
      return await createElement(
        fileUri,
        message.payload.chainId,
        message.payload.elementRequest,
      );
    case "transferElement":
      return await transferElement(
        fileUri,
        message.payload.chainId,
        message.payload.transferElementRequest,
      );
    case "deleteElements":
      return await deleteElements(
        fileUri,
        message.payload.chainId,
        message.payload.elementIds,
      );
    case "createConnection":
      return await createConnection(
        fileUri,
        message.payload.chainId,
        message.payload.connectionRequest,
      );
    case "deleteConnections":
      return await deleteConnections(
        fileUri,
        message.payload.chainId,
        message.payload.connectionIds,
      );
    case "updateChain":
      return await updateChain(
        fileUri,
        message.payload.id,
        message.payload.chain,
      );
    case "getMaskedFields":
      return await getMaskedFields(fileUri, message.payload);
    case "createMaskedField":
      return await createMaskedField(
        fileUri,
        message.payload.chainId,
        message.payload.maskedField,
      );
    case "deleteMaskedFields":
      return await deleteMaskedFields(
        fileUri,
        message.payload.chainId,
        message.payload.maskedFieldIds,
      );
    case "updateMaskedField":
      return await updateMaskedField(
        fileUri,
        message.payload.id,
        message.payload.chainId,
        message.payload.maskedField,
      );
    case "moveChain":
      return await changeFolder(
        fileUri,
        message.payload.chainId,
        message.payload.folder,
      );
    case "groupElements":
      return await groupElements(
        fileUri,
        message.payload.chainId,
        message.payload.elementIds,
      );
    case "ungroupElements":
      return await ungroupElements(
        fileUri,
        message.payload.chainId,
        message.payload.groupId,
      );
    case "findChainByElementId":
      return await findChainByElementId(message.payload);

    // Context service operations
    case "getContextService":
      return await getContextService(fileUri, message.payload);
    case "getContextServices":
      return await getContextServices(fileUri);
    case "updateContextService":
      return await updateContextService(
        fileUri,
        message.payload.id,
        message.payload.service,
      );

    // Service operations
    case "getService":
      return await getService(fileUri, message.payload);
    case "getServices":
      return await getServices(fileUri);
    case "getEnvironments":
      return await getEnvironments(fileUri, message.payload);
    case "getApiSpecifications":
      return await getApiSpecifications(fileUri, message.payload);
    case "getSpecificationModel":
      return await getSpecificationModel(
        fileUri,
        message.payload.serviceId,
        message.payload.groupId,
      );
    case "getOperations":
      return await getOperations(fileUri, message.payload);
    case "getOperationInfo":
      return await getOperationInfo(fileUri, message.payload);

    // Service modification operations
    case "updateService":
      return await updateService(
        fileUri,
        message.payload.id,
        message.payload.service,
      );
    case "createService":
      return await handleCreateService(context, fileUri, message.payload);
    case "updateEnvironment":
      return await updateEnvironment(
        fileUri,
        message.payload.serviceId,
        message.payload.environmentId,
        message.payload.environment,
      );
    case "createEnvironment":
      return await createEnvironment(
        fileUri,
        message.payload.serviceId,
        message.payload.environment,
      );
    case "deleteEnvironment":
      return await deleteEnvironment(
        fileUri,
        message.payload.serviceId,
        message.payload.environmentId,
      );

    // Specification operations
    case "updateApiSpecificationGroup":
      return await updateApiSpecificationGroup(
        fileUri,
        message.payload.id,
        message.payload.group,
      );
    case "updateSpecificationModel":
      return await updateSpecificationModel(
        fileUri,
        message.payload.id,
        message.payload.model,
      );
    case "deprecateModel":
      return await deprecateModel(fileUri, message.payload);
    case "deleteSpecificationGroup":
      return await deleteSpecificationGroup(fileUri, message.payload);
    case "deleteSpecificationModel":
      return await deleteSpecificationModel(fileUri, message.payload);

    // Specification import operations
    case "importSpecificationGroup":
      return await handleImportSpecificationGroup(
        context,
        fileUri,
        message.payload,
      );
    case "importSpecification":
      return await handleImportSpecification(context, fileUri, message.payload);
    case "getImportSpecificationResult":
      return await handleGetImportSpecificationResult(
        context,
        fileUri,
        message.payload,
      );

    // Navigation operations
    case "navigateToSpecifications":
      return await getServiceSpecificationsUri(
        fileUri,
        message.payload.groupId,
      );
    case "navigateToOperations":
      return await getServiceOperationsUri(
        fileUri,
        message.payload.groupId,
        message.payload.specId,
      );

    // API contract files
    case "getSpecApiFiles":
      return await handleGetSpecApiFiles();
    case "readSpecificationFileContent":
      return await handleReadSpecificationFileContent(
        message.payload.fileUri,
        message.payload.specificationFilePath,
      );
  }
}

function getExtensionConfiguration(): AppExtensionProps {
  return {
    appName: "qip",
  };
}

export async function getNavigateUri(fileUri: vscode.Uri): Promise<string> {
  try {
    const fileType = await fileApi.getFileType(fileUri);

    switch (fileType) {
      case QipFileType.CONTEXT_SERVICE:
        return await getContextServiceUri(fileUri);
      case QipFileType.SERVICE:
        return await getServiceUri(fileUri);
      case QipFileType.CHAIN:
        return await getChainUri(fileUri);
      case QipFileType.UNKNOWN:
      default:
        return "/services";
    }
  } catch (e) {
    return "/services";
  }
}

export const SERVICE_ROUTES: RegExp[] = [
  /^\/services\/systems\/[^/]+\/parameters$/,
  /^\/services\/systems\/[^/]+\/specificationGroups$/,
  /^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications$/,
  /^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications\/[^/]+$/,
  /^\/services\/systems\/[^/]+\/environments$/,
  /^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications\/[^/]+\/operations$/,
  /^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications\/[^/]+\/operations\/[^/]+$/,
];

export const CONTEXT_SERVICE_ROUTES: RegExp[] = [
  /^\/services\/context\/[^/]+\/parameters$/,
];

export const CHAIN_ROUTES: RegExp[] = [/^\/chains\/[^/]+\/graph$/];

export const ROUTES: RegExp[] = [...SERVICE_ROUTES, ...CHAIN_ROUTES];

async function parseNavigatePath(
  path: string,
  fileUri: vscode.Uri,
): Promise<string> {
  let result: string | undefined = undefined;
  for (const regexp of ROUTES) {
    if (regexp.test(path)) {
      result = path;
      break;
    }
  }

  return result ? result : await getNavigateUri(fileUri);
}
