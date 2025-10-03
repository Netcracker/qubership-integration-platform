import {Chain, Dependency, Element, LibraryData, LibraryElement, MaskedField, MaskedFields} from "@netcracker/qip-ui";
import {Uri} from "vscode";
import {ChainCommitRequestAction, EMPTY_USER, findElementById, getElementChildren} from "./chainApiUtils";
import {fileApi} from "./file/fileApiProvider";
import { readDirectory } from "./file/fileApiImpl";
const vscode = require('vscode');


export async function getCurrentChainId(fileUri: Uri): Promise<string> {
    const chain: any = await getMainChain(fileUri);
    console.log('getCurrentChainId', chain.id);
    return chain.id;
}

export async function getMainChain(fileUri: Uri): Promise<any> {
    return await fileApi.getMainChain(fileUri);
}

export async function getChainFileUri(chainId: string): Promise<Uri> {
    return await findChainRecursively(fileApi.getRootDirectory(), chainId);
}

async function findChainRecursively(folderUri: Uri, chainId: string): Promise<Uri> {
    const result: any[] = [];

    await collectChainsRecursively(folderUri, chainId, result);

    if (result.length === 0) {
        throw Error(`Chain with id=${chainId} is not found under the directory ${folderUri}`);
    } else if (result.length > 1) {
        throw Error(`Multiple chains with id=${chainId} found under the directory ${folderUri}`);
    } else {
        return result[0];
    }
}

async function collectChainsRecursively(folderUri: Uri, chainId: string, result: Uri[]): Promise<void> {
    const entries = await readDirectory(folderUri);

    for (const [name, type] of entries) {
        if (type === vscode.FileType.File && name.endsWith('.chain.qip.yaml')) {
            const fileUri = vscode.Uri.joinPath(folderUri, name);
            const chainYaml = await fileApi.parseFile(fileUri);
            if (chainYaml.id === chainId) {
                result.push(fileUri);
            }
        } else if (type === vscode.FileType.Directory) {
            const subFolderUri = vscode.Uri.joinPath(folderUri, name);
            await collectChainsRecursively(subFolderUri, chainId, result);
        }
    }
}

export async function getLibrary(): Promise<LibraryData> {
    return fileApi.getLibrary();
}

export async function getLibraryElementByType(type: string): Promise<LibraryElement> {
    return findLibraryElementByType(await fileApi.getLibrary(), type);
}

function findLibraryElementByType(partialLibraryData: any, type: string): any | null {
    if (typeof partialLibraryData !== 'object' || partialLibraryData === null) {
        return null;
    }
    for (const key of Object.keys(partialLibraryData)) {
        let value = partialLibraryData[key];
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

export function getMaskedField(chain: any, id: string) {
    let maskedField = chain.content.maskedFields?.find((mf: any) => mf.id === id);
    if (!maskedField) {
        console.error(`Masked Field not found`);
        throw Error("Masked Field not found");
    }
    return maskedField;
}

export function parseMaskedField(chain: any, id: string): MaskedField {

    const maskedField = getMaskedField(chain, id);

    return {
        id: maskedField.id,
        name: maskedField.name,
        createdWhen: chain.content.modifiedWhen,
        modifiedWhen: chain.content.modifiedWhen,
        createdBy: {...EMPTY_USER},
        modifiedBy: {...EMPTY_USER},
    };
}

export async function getMaskedFields(fileUri: Uri, chainId: string): Promise<MaskedFields> {
    const chain: any = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const fields: MaskedField[] = [];
    if (chain.content.maskedFields) {
        for (const maskedField of chain.content.maskedFields) {
            fields.push(parseMaskedField(chain, maskedField.id));
        }
    }

    return  {
        fields,
    };
}

export async function getConnections(fileUri: Uri, chainId: string): Promise<Dependency[]> {
    const chain: any = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    return parseDependencies(chain.content.dependencies);
}

export async function getElements(fileUri: Uri, chainId: string): Promise<Element[]> {
    const chain: any = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    return await parseElements(fileUri, chain.content.elements, chain.id);
}

export async function getElement(fileUri: Uri, chainId: string, elementId: string): Promise<Element> {
    const chain: any = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = findElementById(chain.content.elements, elementId);
    if (!element) {
        console.error(`ElementId not found`);
        throw Error("ElementId not found");
    }

    return await parseElement(fileUri, element.element, chain.id, element.parentId);
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

async function parseElement(fileUri: Uri, element: any, chainId: string, parentId: string | undefined = undefined): Promise<Element> {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        if (beforeAfterBlock.type === 'script') {
            beforeAfterBlock['script'] = await fileApi.readFile(fileUri, beforeAfterBlock.propertiesFilename);
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            const properties: any = JSON.parse(await fileApi.readFile(fileUri, beforeAfterBlock.propertiesFilename));
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
            const properties: any = JSON.parse(await fileApi.readFile(fileUri, element.properties.propertiesFilename));
            for (const propertyName of propertyNames) {
                element.properties[propertyName] = properties[propertyName];
            }
        } else {
            element.properties[element.properties.propertiesToExportInSeparateFile] = await fileApi.readFile(fileUri, element.properties.propertiesFilename);
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
            children.push(await parseElement(fileUri, child, chainId, element.id));
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
    } as Element;
}

async function parseElements(fileUri: Uri, elements: any[], chainId: string): Promise<Element[]> {
    const result: Element[] = [];

    if (elements && elements.length) {
        for (const element of elements) {
            const parsedElement = await parseElement(fileUri, element, chainId);
            result.push(parsedElement);
            result.push(...getElementChildren(parsedElement.children));
        }
    }
    return result;
}

export async function getChain(fileUri: Uri, chainId: string): Promise<Chain> {
    const chain: any = await getMainChain(fileUri);
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
        deployments: chain.content.deployments,
        deployAction: chain.content.deployAction
          ? ChainCommitRequestAction[chain.content.deployAction as keyof typeof ChainCommitRequestAction]
          : undefined,
        description: chain.content.description,
        elements: await parseElements(fileUri, chain.content.elements, chain.id),
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
