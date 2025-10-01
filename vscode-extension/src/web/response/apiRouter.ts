import vscode, {ExtensionContext, Uri} from "vscode";
import {
    createConnection,
    createElement,
    createMaskedField,
    deleteConnections,
    deleteElements,
    deleteMaskedFields, transferElement,
    updateChain,
    updateElement,
    updateMaskedField
} from "./chainApiModify";
import {
    getChain,
    getConnections,
    getElements,
    getLibrary,
    getLibraryElementByType,
    getMaskedFields
} from "./chainApiRead";
import {
    getApiSpecifications,
    getEnvironments,
    getOperationInfo,
    getService,
    getServices,
    getSpecificationModel
} from "./serviceApiRead";
import {
    createEnvironment,
    deleteEnvironment,
    deleteSpecificationGroup,
    deleteSpecificationModel,
    deprecateModel,
    updateApiSpecificationGroup,
    updateEnvironment,
    updateService,
    updateSpecificationModel
} from "./serviceApiModify";
import {fileApi} from "./file/fileApiProvider";
import {getChainUri,} from "./chainApiUtils";
import {
    getServiceOperationsUri,
    getServiceSpecificationsUri,
    getServiceUri,
    handleCreateService,
    handleGetImportSpecificationResult,
    handleImportSpecification,
    handleImportSpecificationGroup,
    QipFileType
} from "./serviceApiUtils";
import {VSCodeMessage, AppExtensionProps} from "@netcracker/qip-ui";

let lastWebviewPath: string | undefined = undefined;


export async function getApiResponse(message: VSCodeMessage<any>, openedDocumentFolderUri: Uri | undefined, context?: ExtensionContext): Promise<any> {
    
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
        case 'startup': return getExtensionConfiguration();
        case 'navigate':
            if (message.payload?.path) {
                if (lastWebviewPath === message.payload.path) {
                    return;
                }
                lastWebviewPath = message.payload.path;
                const parsedPath = await parseNavigatePath(message.payload.path, fileUri);
                return parsedPath;
            } else {
                return await getNavigateUri(fileUri);
            }
        case 'getChain': return await getChain(fileUri, message.payload);
        case 'getElements': return await getElements(fileUri, message.payload);
        case 'getElementsByType': return [];
        case 'getConnections': return await getConnections(fileUri, message.payload);
        case 'getLibrary': return await getLibrary();
        case 'getLibraryElementByType': return await getLibraryElementByType(message.payload);
        case 'updateElement': return await updateElement(fileUri, message.payload.chainId, message.payload.elementId, message.payload.elementRequest);
        case 'createElement': return await createElement(fileUri, message.payload.chainId, message.payload.elementRequest);
        case 'transferElement': return await transferElement(fileUri, message.payload.chainId, message.payload.transferElementRequest);
        case 'deleteElements': return await deleteElements(fileUri, message.payload.chainId, message.payload.elementIds);
        case 'createConnection': return await createConnection(fileUri, message.payload.chainId, message.payload.connectionRequest);
        case 'deleteConnections': return await deleteConnections(fileUri, message.payload.chainId, message.payload.connectionIds);
        case 'updateChain': return await updateChain(fileUri, message.payload.id, message.payload.chain);
        case 'getMaskedFields': return await getMaskedFields(fileUri, message.payload);
        case 'createMaskedField': return await createMaskedField(fileUri, message.payload.chainId, message.payload.maskedField);
        case 'deleteMaskedFields': return await deleteMaskedFields(fileUri, message.payload.chainId, message.payload.maskedFieldIds);
        case 'updateMaskedField': return await updateMaskedField(fileUri, message.payload.id, message.payload.chainId, message.payload.maskedField);


        // Service operations
        case 'getService': return await getService(fileUri, message.payload);
        case 'getServices': return await getServices(fileUri);
        case 'getEnvironments': return await getEnvironments(fileUri, message.payload);
        case 'getApiSpecifications': return await getApiSpecifications(fileUri, message.payload);
        case 'getSpecificationModel': return await getSpecificationModel(fileUri, message.payload.serviceId, message.payload.groupId);
        case 'getOperationInfo': return await getOperationInfo(fileUri, message.payload);

        // Service modification operations
        case 'updateService': return await updateService(fileUri, message.payload.id, message.payload.service);
        case 'createService': return await handleCreateService(context, fileUri, message.payload);
        case 'updateEnvironment': return await updateEnvironment(fileUri, message.payload.serviceId, message.payload.environmentId, message.payload.environment);
        case 'createEnvironment': return await createEnvironment(fileUri, message.payload.serviceId, message.payload.environment);
        case 'deleteEnvironment': return await deleteEnvironment(fileUri, message.payload.serviceId, message.payload.environmentId);

        // Specification operations
        case 'updateApiSpecificationGroup': return await updateApiSpecificationGroup(fileUri, message.payload.id, message.payload.group);
        case 'updateSpecificationModel': return await updateSpecificationModel(fileUri, message.payload.id, message.payload.model);
        case 'deprecateModel': return await deprecateModel(fileUri, message.payload);
        case 'deleteSpecificationGroup': return await deleteSpecificationGroup(fileUri, message.payload);
        case 'deleteSpecificationModel': return await deleteSpecificationModel(fileUri, message.payload);

        // Specification import operations
        case 'importSpecificationGroup': return await handleImportSpecificationGroup(context, fileUri, message.payload);
        case 'importSpecification': return await handleImportSpecification(context, fileUri, message.payload);
        case 'getImportSpecificationResult': return await handleGetImportSpecificationResult(context, fileUri, message.payload);

        // Navigation operations
        case 'navigateToSpecifications': return await getServiceSpecificationsUri(fileUri, message.payload.groupId);
        case 'navigateToOperations': return await getServiceOperationsUri(fileUri, message.payload.groupId, message.payload.specId);
    }
}

function getExtensionConfiguration(): AppExtensionProps {
    return {
        appName: "qip"
    };
}

export async function getNavigateUri(fileUri: vscode.Uri): Promise<string> {
    try {
        const fileType = await fileApi.getFileType(fileUri);

        switch (fileType) {
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

async function parseNavigatePath(path: string, fileUri: vscode.Uri): Promise<string> {

    if (/^\/services\/systems\/[^/]+\/parameters$/.test(path)) {
      return path;
    }
    if (/^\/services\/systems\/[^/]+\/specificationGroups$/.test(path)) {
        return path;
    }
    if (/^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications$/.test(path)) {
      return path;
    }
    if (/^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications\/[^/]+$/.test(path)) {
      return path;
    }
    if (/^\/services\/systems\/[^/]+\/environments$/.test(path)) {
        return path;
    }
    if (/^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications\/[^/]+\/operations$/.test(path)) {
      return path;
    }
    if (/^\/chains\/[^/]+\/graph$/.test(path)) {
      return path;
    }
    return await getNavigateUri(fileUri);
}
