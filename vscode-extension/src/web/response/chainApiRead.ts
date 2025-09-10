import {Chain, Dependency, Element, LibraryData, LibraryElement, MaskedField, MaskedFields} from "./apiTypes";
import {Uri} from "vscode";
import {EMPTY_USER, findElementById, getElementChildren} from "./chainApiUtils";
import {fileApi} from "./file/fileApiProvider";


export async function getCurrentChainId(mainFolderUri: Uri): Promise<string> {
    const chain: any = await getMainChain(mainFolderUri);
    console.log('getCurrentChainId', chain.id);
    return chain.id;
}

export async function getMainChain(mainFolderUri: Uri): Promise<any> {
    return await fileApi.getMainChain(mainFolderUri);
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

export async function getMaskedFields(mainFolderUri: Uri, chainId: string): Promise<MaskedFields> {
    const chain: any = await getMainChain(mainFolderUri);
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

    return await parseElement(mainFolderUri, element.element, chain.id, element.parentId);
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

async function parseElement(mainFolderUri: Uri, element: any, chainId: string, parentId: string | undefined = undefined): Promise<Element> {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        if (beforeAfterBlock.type === 'script') {
            beforeAfterBlock['script'] = await fileApi.readFile(mainFolderUri, beforeAfterBlock.propertiesFilename);
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            const properties: any = JSON.parse(await fileApi.readFile(mainFolderUri, beforeAfterBlock.propertiesFilename));
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
            const properties: any = JSON.parse(await fileApi.readFile(mainFolderUri, element.properties.propertiesFilename));
            for (const propertyName of propertyNames) {
                element.properties[propertyName] = properties[propertyName];
            }
        } else {
            element.properties[element.properties.propertiesToExportInSeparateFile] = await fileApi.readFile(mainFolderUri, element.properties.propertiesFilename);
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
