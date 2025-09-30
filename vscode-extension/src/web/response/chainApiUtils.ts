import vscode, {Uri} from "vscode";
import {getCurrentChainId} from "./chainApiRead";
import {Element, User} from "@netcracker/qip-ui";

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

    console.error('No workspace folders found');
    throw Error("No workspace folders found");
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

export function replaceElementPlaceholders(properties: any, chainId: string, elementId: string) {
    for (let property in properties) {
        if (typeof(properties[property]) === 'string') {
            properties[property] = properties[property]
                .replace(ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER, chainId)
                .replace(ChainElementPlaceholders.CREATED_ELEMENT_ID_PLACEHOLDER, elementId);
        }
    }
}

export const EMPTY_USER: User = {
    id: "",
    username: ""
};

//TODO WA for exporting only types in UI
export enum ChainCommitRequestAction {
    NONE = "NONE",
    SNAPSHOT = "SNAPSHOT",
    DEPLOY = "DEPLOY",
}

export enum LibraryElementQuantity {
    ONE = "one",
    ONE_OR_ZERO = "one-or-zero",
    ONE_OR_MANY = "one-or-many",
}

export enum LibraryInputQuantity {
    ONE = "one",
    ANY = "any",
}

export enum ChainElementPlaceholders {
    CREATED_ELEMENT_ID_PLACEHOLDER = "%%{created-element-id-placeholder}",
    CHAIN_ID_PLACEHOLDER = "%%{chain-id-placeholder}",
}
