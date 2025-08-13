import {Chain, Dependency, Element, LibraryData, LibraryElement} from "./apiTypes";
import * as yaml from 'yaml';
import {ExtensionContext, FileType, Uri} from "vscode";
import {EMPTY_USER, findElementById, getElementChildren} from "./chainApi";

const vscode = require('vscode');


export async function getCurrentChainId(mainFolderUri: Uri): Promise<string> {
    const chain: any = await getMainChain(mainFolderUri);
    console.log('getCurrentChainId', chain.id);
    return chain.id;
}

export async function getMainChainFileUri(mainFolderUri: Uri) {
    if (mainFolderUri) {
        let entries = await vscode.workspace.fs.readDirectory(mainFolderUri);

        const files = entries.filter(([, type]: [string, FileType]) => type === 1)
            .filter(([name]: [string]) => name.endsWith('.chain.qip.yaml'))
            .map(([name]: [string]) => name);
        if (files.length !== 1) {
            console.error(`Single *.chain.qip.yaml file not found in the current directory`);
            vscode.window.showWarningMessage("*.chain.qip.yaml file not found in the current directory");
            throw Error("Single *.chain.qip.yaml file not found in the current directory");
        }
        return vscode.Uri.joinPath(mainFolderUri, files[0]);
    }
    return undefined;
}

export async function getMainChain(mainFolderUri: Uri): Promise<any> {
    const fileUri = await getMainChainFileUri(mainFolderUri);
    if (!fileUri) {
        throw Error("No main chain file");
    }

    try {
            console.log('fileUri', fileUri);
            const fileContent = await vscode.workspace.fs.readFile(fileUri);
            const text = new TextDecoder('utf-8').decode(fileContent);
            console.log('text', text);
            const parsed = yaml.parse(text);

            if (parsed && parsed.name) {
                return parsed;
            }
        } catch (e) {
            console.error(`Chain file ${fileUri} can't be parsed from QIP Extension API`, e);
        }

}

export async function getLibrary(context: ExtensionContext): Promise<LibraryData> {
    const fileUri = vscode.Uri.joinPath(context.extensionUri, 'media', 'library.json');
    const content = new TextDecoder('utf-8').decode(await vscode.workspace.fs.readFile(fileUri));
    return JSON.parse(content);
}

export async function getLibraryElementByType(context: ExtensionContext, type: string): Promise<LibraryElement> {
    const fileUri = vscode.Uri.joinPath(context.extensionUri, 'media', 'library.json');
    const content = new TextDecoder('utf-8').decode(await vscode.workspace.fs.readFile(fileUri));
    const libraryObject = JSON.parse(content);
    return findLibraryElementByType(libraryObject, type);
}

function findLibraryElementByType(obj: any, type: string): any | null {
    if (typeof obj !== 'object' || obj === null) {
        return null;
    }
    for (const key of Object.keys(obj)) {
        let value = obj[key];
        if (key === 'childElements' && typeof value === 'object') {
            value = Object.values(value);
        }
        if ((key === 'elements' || key === 'childElements') && Array.isArray(value)) {
            for (const item of value) {
                if (item && typeof item === 'object' && item.name === type) {
                    return item;
                }
            }
        }
        const result = findLibraryElementByType(value, type);
        if (result !== null) {
            return result;
        }
    }
    return null;
}

export async function getConnections(mainFolderUri: Uri, chainId: string): Promise<Dependency[]> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    return parseDependencies(chain.content.dependencies);
}

export async function getElements(mainFolderUri: Uri, chainId: string): Promise<Element[]> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    return await parseElements(mainFolderUri, chain.content.elements, chain.id);
}

export async function getElement(mainFolderUri: Uri, chainId: string, elementId: string): Promise<Element> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = findElementById(chain.content.elements, elementId);
    if (!element) {
        console.error(`ElementId not found`);
        throw Error("ElementId not found");
    }

    return await parseElement(mainFolderUri, element, chain.id);
}

export function getDependencyId(dependency: Dependency) {
    return `${dependency.from}-${dependency.to}`;
}

function parseDependencies(dependencies: any[]): Dependency[] {
    const result: Dependency[] = [];
    if (dependencies && dependencies.length) {
        for (const dependency of dependencies) {
            result.push({
                id: getDependencyId(dependency),
                from: dependency.from,
                to: dependency.to,
            });
        }
    }
    return result;
}

async function readFile(mainFolderUri: Uri, propertiesFilename: string): Promise<string> {
    console.log("read property file", propertiesFilename);
    const fileUri = vscode.Uri.joinPath(mainFolderUri, propertiesFilename);
    console.log("property file uri", fileUri);
    const fileContent = await vscode.workspace.fs.readFile(fileUri);
    const textFile = new TextDecoder('utf-8').decode(fileContent);
    console.log("property file", textFile);
    return textFile;
}

async function parseElement(mainFolderUri: Uri, element: any, chainId: string, parentId: string | undefined = undefined): Promise<Element> {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        if (beforeAfterBlock.type === 'script') {
            beforeAfterBlock['script'] = await readFile(mainFolderUri, beforeAfterBlock.propertiesFilename);
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            const properties: any = JSON.parse(await readFile(mainFolderUri, beforeAfterBlock.propertiesFilename));
            for (const key in properties) {
                beforeAfterBlock[key] = properties[key];
            }
        }
    }

    if (element.properties?.propertiesToExportInSeparateFile) {
        if (element.properties.exportFileExtension === 'json') {
            const propertyNames: string[] = element.properties.propertiesToExportInSeparateFile.split(',').map(function (item: string) {
                return item.trim();
            });
            const properties: any = JSON.parse(await readFile(mainFolderUri, element.properties.propertiesFilename));
            for (const propertyName of propertyNames) {
                element.properties[propertyName] = properties[propertyName];
            }
        } else {
            element.properties[element.properties.propertiesToExportInSeparateFile] = await readFile(mainFolderUri, element.properties.propertiesFilename);
        }
    }

    if (element.type === 'service-call') {
        if (Array.isArray((element.properties.after))) {
            for (const afterBlock of element.properties.after) {
                await handleServiceCallProperty(afterBlock);
            }
        }
        if (element.properties.before) {
            await handleServiceCallProperty(element.properties.before);
        }
    }

    let children: Element[] | undefined = undefined;
    if (element.children?.length) {
        children = [];
        for (const child of element.children) {
            children.push(await parseElement(mainFolderUri, child, chainId, element.id));
        }
    }

    return {
        id: element.id,
        name: element.name,
        type: element.type,
        properties: element.properties,
        mandatoryChecksPassed: true,
        createdBy: {...EMPTY_USER},
        modifiedBy: {...EMPTY_USER},
        createdWhen: element.modifiedWhen,
        modifiedWhen: element.modifiedWhen,
        chainId: chainId,
        description: element.description,
        parentElementId: parentId,
        children: children,
    };
}

async function parseElements(mainFolderUri: Uri, elements: any[], chainId: string): Promise<Element[]> {
    const result: Element[] = [];

    if (elements && elements.length) {
        for (const element of elements) {
            const parsedElement = await parseElement(mainFolderUri, element, chainId);
            result.push(parsedElement);
            result.push(...getElementChildren(parsedElement.children));
        }
    }
    return result;
}

export async function getChain(mainFolderUri: Uri, chainId: string): Promise<Chain> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }
    return {
        assumptions: chain.content.assumptions,
        businessDescription: chain.content.businessDescription,
        containsDeprecatedContainers: false,
        containsDeprecatedElements: false,
        containsUnsupportedElements: false,
        createdBy: {...EMPTY_USER},
        createdWhen: chain.content.modifiedWhen,
        currentSnapshot: undefined,
        defaultSwimlaneId: chain.content.defaultSwimlaneId,
        dependencies: parseDependencies(chain.content.dependencies),
        deployments: [],
        description: chain.content.description,
        elements: await parseElements(mainFolderUri, chain.content.elements, chain.id),
        id: chain.id,
        labels: chain.content.labels ? chain.content.labels : [],
        modifiedBy: {...EMPTY_USER},
        modifiedWhen: chain.content.modifiedWhen,
        name: chain.name,
        navigationPath: new Map<string, string>([[chain.id, chain.name]]),
        outOfScope: chain.content.outOfScope,
        reuseSwimlaneId: chain.content.reuseSwimlaneId,
        unsavedChanges: false
    };
}
