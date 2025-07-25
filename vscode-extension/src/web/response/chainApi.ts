import {VSCodeMessage} from "./apiTypes";
import vscode, {ExtensionContext, Uri} from "vscode";
import {
    createConnection,
    createElement,
    deleteConnection,
    deleteElement,
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
        case 'updateElement': return await updateElement(mainFolder, message.payload.chainId, message.payload.elementId, message.payload.elementRequest);
        case 'createElement': return await createElement(context, mainFolder, message.payload.chainId, message.payload.elementRequest);
        case 'deleteElement': return await deleteElement(mainFolder, message.payload.chainId, message.payload.elementId);
        case 'createConnection': return await createConnection(mainFolder, message.payload.chainId, message.payload.connectionRequest);
        case 'deleteConnection': return await deleteConnection(mainFolder, message.payload.chainId, message.payload.connectionId);
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

export const EMPTY_USER = {
    id: "",
    username: ""
};