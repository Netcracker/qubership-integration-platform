import {Element, VSCodeMessage} from "./apiTypes";
import vscode, {ExtensionContext, Uri} from "vscode";
import {
    createConnection,
    createElement,
    deleteConnections,
    deleteElements,
    updateChain,
    updateElement
} from "./chainApiModify";
import {
    getChain,
    getConnections,
    getCurrentChainId,
    getElements,
    getLibrary,
    getLibraryElementByType
} from "./chainApiRead";

export async function getApiResponse(message: VSCodeMessage, context: ExtensionContext, openedDocumentFolderUri: Uri | undefined): Promise<any> {
    const mainFolder: Uri = getChainFolderUri(openedDocumentFolderUri);

    switch (message.type) {
        case 'navigate': return await getChainUri(mainFolder);
        case 'getChain': return await getChain(mainFolder, message.payload);
        case 'getElements': return await getElements(mainFolder, message.payload);
        case 'getConnections': return await getConnections(mainFolder, message.payload);
        case 'getLibrary': return await getLibrary(context);
        case 'getLibraryElementByType': return await getLibraryElementByType(context, message.payload);
        case 'updateElement': return await updateElement(context, mainFolder, message.payload.chainId, message.payload.elementId, message.payload.elementRequest);
        case 'createElement': return await createElement(context, mainFolder, message.payload.chainId, message.payload.elementRequest);
        case 'deleteElements': return await deleteElements(context, mainFolder, message.payload.chainId, message.payload.elementIds);
        case 'createConnection': return await createConnection(context, mainFolder, message.payload.chainId, message.payload.connectionRequest);
        case 'deleteConnections': return await deleteConnections(mainFolder, message.payload.chainId, message.payload.connectionIds);
        case 'updateChain': return await updateChain(mainFolder, message.payload.id, message.payload.chain);
    }
}

export async function getChainUri(mainFolderUri: vscode.Uri): Promise<string> {
    const result = `/chains/${await getCurrentChainId(mainFolderUri)}/graph`;
    console.log('getChainUri', result);
    return result;
}

export function getChainFolderUri(openedDocumentFolderUri: Uri | undefined): Uri {
    if (openedDocumentFolderUri) {
        return openedDocumentFolderUri;
    }

    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (workspaceFolders) {
        return  workspaceFolders[0].uri;
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
