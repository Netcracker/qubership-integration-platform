import {Element, VSCodeMessage, IntegrationSystem, Environment, SpecificationGroup, Specification, FolderItem, FolderItemType} from "./apiTypes";
import {SerializedFile} from "../api-services/importApiTypes";
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
    getCurrentChainId,
    getElements,
    getLibrary,
    getLibraryElementByType,
    getMaskedFields
} from "./chainApiRead";
import {
    getService,
    getCurrentServiceId,
    getEnvironments,
    getApiSpecifications,
    getSpecificationModel,
    getServices,
    getOperationInfo
} from "./serviceApiRead";
import {
    updateService,
    createService,
    updateEnvironment,
    createEnvironment,
    deleteEnvironment,
    updateApiSpecificationGroup,
    updateSpecificationModel,
    deprecateModel,
    deleteSpecificationGroup,
    deleteSpecificationModel
} from "./serviceApiModify";
import { SpecificationImportApiHandler } from "../api-services/SpecificationImportApiHandler";
import { fileApi } from "./file/fileApiProvider";

let lastWebviewPath: string | undefined = undefined;

export async function getApiResponse(message: VSCodeMessage, openedDocumentFolderUri: Uri | undefined, context?: ExtensionContext): Promise<any> {
    const mainFolder: Uri = getChainFolderUri(openedDocumentFolderUri);

    switch (message.type) {
        case 'navigate':
            if (message.payload && message.payload.path) {
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
        case 'createService':
            if (!context) {
                throw new Error('ExtensionContext is required for createService operation');
            }
            return await createService(context, mainFolder, message.payload);
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
        case 'importSpecificationGroup':
            if (!context) {
                throw new Error('ExtensionContext is required for import operations');
            }
            const importHandler = new SpecificationImportApiHandler(context, mainFolder);
            return await importHandler.handleImportSpecificationGroup(message.payload);
        case 'importSpecification':
            if (!context) {
                throw new Error('ExtensionContext is required for import operations');
            }
            const importSpecHandler = new SpecificationImportApiHandler(context, mainFolder);
            return await importSpecHandler.handleImportSpecification(message.payload.specificationGroupId, message.payload.files as SerializedFile[], message.payload.systemId);
        case 'getImportSpecificationResult':
            if (!context) {
                throw new Error('ExtensionContext is required for import operations');
            }
            const resultHandler = new SpecificationImportApiHandler(context, mainFolder);
            return await resultHandler.handleGetImportResult(message.payload.importId);

        // Folder operations
        case 'getRootFolders':
            console.log("Method getRootFolders is not implemented - returning empty array");
            return [];

        // Navigation operations
        case 'navigateToSpecifications': return await getServiceSpecificationsUri(mainFolder, message.payload.groupId);
        case 'navigateToOperations': return await getServiceOperationsUri(mainFolder, message.payload.groupId, message.payload.specId);
    }
}

export async function getChainUri(mainFolderUri: vscode.Uri): Promise<string> {
    const result = `/chains/${await getCurrentChainId(mainFolderUri)}/graph`;
    return result;
}

export async function getServiceUri(mainFolderUri: vscode.Uri): Promise<string> {
    const result = `/services/systems/${await getCurrentServiceId(mainFolderUri)}/parameters`;
    return result;
}

export async function getServiceSpecificationsUri(mainFolderUri: vscode.Uri, groupId: string): Promise<string> {
    const serviceId = await getCurrentServiceId(mainFolderUri);
    const result = `/services/systems/${serviceId}/specificationGroups/${groupId}/specifications`;
    return result;
}

export async function getServiceOperationsUri(mainFolderUri: vscode.Uri, groupId: string, specId: string): Promise<string> {
    const serviceId = await getCurrentServiceId(mainFolderUri);
    const result = `/services/systems/${serviceId}/specificationGroups/${groupId}/specifications/${specId}/operations`;
    return result;
}

export async function getNavigateUri(mainFolderUri: vscode.Uri): Promise<string> {
    try {
        const entries = await fileApi.readDirectory(mainFolderUri);

        const hasChainFile = entries.some(([name]: [string, vscode.FileType]) => name.endsWith('.chain.qip.yaml'));
        const hasServiceFile = entries.some(([name]: [string, vscode.FileType]) => name.endsWith('.service.qip.yaml'));

        if (hasServiceFile) {
            const serviceUri = await getServiceUri(mainFolderUri);
            return serviceUri;
        } else if (hasChainFile) {
            const chainUri = await getChainUri(mainFolderUri);
            return chainUri;
        } else {
            return "/services";
        }
    } catch (e) {
        return "/services";
    }
}

export function getChainFolderUri(openedDocumentFolderUri: Uri | undefined): Uri {
    if (openedDocumentFolderUri) {
        return openedDocumentFolderUri;
    }

    const workspaceFolders = vscode.workspace.workspaceFolders;

    if (workspaceFolders) {
        const firstFolder = workspaceFolders[0].uri;
        return firstFolder;
    }

    vscode.window.showWarningMessage('No workspace folders found.');
    throw Error("No current workfolder found");
}

export function findElementById(
    elements: any[] | undefined,
    elementId: string,
    parentId: string | undefined = undefined
): {
    element: any;
    parentId: string | undefined;
} | undefined {
    if (!elements) {
        return undefined;
    }

    for (const element of elements) {
        if (element.id === elementId) {
            return { element, parentId };
        }

        const found = findElementById(element.children, elementId, element.id);
        if (found) {
            return found;
        }
    }

    return undefined;
}

export const EMPTY_USER = {
    id: "",
    username: ""
};

export const RESOURCES_FOLDER = 'resources';

export function getElementChildren(children: any[] | undefined): any[] {
    const result: Element[] = [];
    if (children?.length) {
        for (const child of children) {
            if (child.children?.length) {
                result.push(...getElementChildren(child.children));
            }
            result.push(child);
        }
    }

    return result;
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
