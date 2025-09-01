import {VSCodeMessage} from "./apiTypes";
import {Uri} from "vscode";
import {
    createConnection,
    createElement,
    deleteConnections,
    deleteElements,
    updateChain,
    updateElement
} from "./chainApiModify";
import {getChain, getConnections, getElements, getLibrary, getLibraryElementByType} from "./chainApiRead";
import {getChainFolderUri, getChainUri} from "./chainApiUtils";

export async function getApiResponse(message: VSCodeMessage, openedDocumentFolderUri: Uri | undefined): Promise<any> {
    const mainFolder: Uri = getChainFolderUri(openedDocumentFolderUri);

    switch (message.type) {
        case 'navigate': return await getChainUri(mainFolder);
        case 'getChain': return await getChain(mainFolder, message.payload);
        case 'getElements': return await getElements(mainFolder, message.payload);
        case 'getConnections': return await getConnections(mainFolder, message.payload);
        case 'getLibrary': return await getLibrary();
        case 'getLibraryElementByType': return await getLibraryElementByType(message.payload);
        case 'updateElement': return await updateElement(mainFolder, message.payload.chainId, message.payload.elementId, message.payload.elementRequest);
        case 'createElement': return await createElement(mainFolder, message.payload.chainId, message.payload.elementRequest);
        case 'deleteElements': return await deleteElements(mainFolder, message.payload.chainId, message.payload.elementIds);
        case 'createConnection': return await createConnection(mainFolder, message.payload.chainId, message.payload.connectionRequest);
        case 'deleteConnections': return await deleteConnections(mainFolder, message.payload.chainId, message.payload.connectionIds);
        case 'updateChain': return await updateChain(mainFolder, message.payload.id, message.payload.chain);
    }
}
