import vscode, {ExtensionContext, Uri} from "vscode";
import {
    createConnection,
    createElement,
    createMaskedField,
    deleteConnections,
    deleteElements,
    deleteMaskedFields,
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
import {getChainFolderUri, getChainUri,} from "./chainApiUtils";
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
    const mainFolder: Uri = getChainFolderUri(openedDocumentFolderUri);

    switch (message.type) {
        case 'startup': return getExtensionConfiguration();
        case 'navigate':
            if (message.payload?.path) {
                if (lastWebviewPath === message.payload.path) {
                    return;
                }
                lastWebviewPath = message.payload.path;
                const parsedPath = await parseNavigatePath(message.payload.path, mainFolder);
                return parsedPath;
            } else {
                return await getNavigateUri(mainFolder);
            }
        case 'getChain': return await getChain(mainFolder, message.payload);
        case 'getElements': return await getElements(mainFolder, message.payload);
        case 'getElementsByType': return [];
        case 'getConnections': return await getConnections(mainFolder, message.payload);
        case 'getLibrary': return await getLibrary();
        case 'getLibraryElementByType': return await getLibraryElementByType(message.payload);
        case 'updateElement': return await updateElement(mainFolder, message.payload.chainId, message.payload.elementId, message.payload.elementRequest);
        case 'createElement': return await createElement(mainFolder, message.payload.chainId, message.payload.elementRequest);
        case 'deleteElements': return await deleteElements(mainFolder, message.payload.chainId, message.payload.elementIds);
        case 'createConnection': return await createConnection(mainFolder, message.payload.chainId, message.payload.connectionRequest);
        case 'deleteConnections': return await deleteConnections(mainFolder, message.payload.chainId, message.payload.connectionIds);
        case 'updateChain': return await updateChain(mainFolder, message.payload.id, message.payload.chain);
        case 'getMaskedFields': return await getMaskedFields(mainFolder, message.payload);
        case 'createMaskedField': return await createMaskedField(mainFolder, message.payload.chainId, message.payload.maskedField);
        case 'deleteMaskedFields': return await deleteMaskedFields(mainFolder, message.payload.chainId, message.payload.maskedFieldIds);
        case 'updateMaskedField': return await updateMaskedField(mainFolder, message.payload.id, message.payload.chainId, message.payload.maskedField);


        // Service operations
        case 'getService': return await getService(mainFolder, message.payload);
        case 'getServices': return await getServices(mainFolder);
        case 'getEnvironments': return await getEnvironments(mainFolder, message.payload);
        case 'getApiSpecifications': return await getApiSpecifications(mainFolder, message.payload);
        case 'getSpecificationModel': return await getSpecificationModel(mainFolder, message.payload.serviceId, message.payload.groupId);
        case 'getOperationInfo': return await getOperationInfo(mainFolder, message.payload);

        // Service modification operations
        case 'updateService': return await updateService(mainFolder, message.payload.id, message.payload.service);
        case 'createService': return await handleCreateService(context, mainFolder, message.payload);
        case 'updateEnvironment': return await updateEnvironment(mainFolder, message.payload.serviceId, message.payload.environmentId, message.payload.environment);
        case 'createEnvironment': return await createEnvironment(mainFolder, message.payload.serviceId, message.payload.environment);
        case 'deleteEnvironment': return await deleteEnvironment(mainFolder, message.payload.serviceId, message.payload.environmentId);

        // Specification operations
        case 'updateApiSpecificationGroup': return await updateApiSpecificationGroup(mainFolder, message.payload.id, message.payload.group);
        case 'updateSpecificationModel': return await updateSpecificationModel(mainFolder, message.payload.id, message.payload.model);
        case 'deprecateModel': return await deprecateModel(mainFolder, message.payload);
        case 'deleteSpecificationGroup': return await deleteSpecificationGroup(mainFolder, message.payload);
        case 'deleteSpecificationModel': return await deleteSpecificationModel(mainFolder, message.payload);

        // Specification import operations
        case 'importSpecificationGroup': return await handleImportSpecificationGroup(context, mainFolder, message.payload);
        case 'importSpecification': return await handleImportSpecification(context, mainFolder, message.payload);
        case 'getImportSpecificationResult': return await handleGetImportSpecificationResult(context, mainFolder, message.payload);

        // Navigation operations
        case 'navigateToSpecifications': return await getServiceSpecificationsUri(mainFolder, message.payload.groupId);
        case 'navigateToOperations': return await getServiceOperationsUri(mainFolder, message.payload.groupId, message.payload.specId);
    }
}

function getExtensionConfiguration(): AppExtensionProps {
    return {
        appName: "qip"
    };
}

export async function getNavigateUri(mainFolderUri: vscode.Uri): Promise<string> {
    try {
        const fileType = await fileApi.getFileType(mainFolderUri);

        switch (fileType) {
            case QipFileType.SERVICE:
                return await getServiceUri(mainFolderUri);
            case QipFileType.CHAIN:
                return await getChainUri(mainFolderUri);
            case QipFileType.UNKNOWN:
            default:
                return "/services";
        }
    } catch (e) {
        return "/services";
    }
}

async function parseNavigatePath(path: string, mainFolderUri: vscode.Uri): Promise<string> {

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
    return await getNavigateUri(mainFolderUri);
}
