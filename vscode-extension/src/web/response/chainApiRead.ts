import {
    Chain,
    Dependency,
    Element,
    EntityLabel,
    LibraryData,
    LibraryElement,
    MaskedField,
    MaskedFields
} from "@netcracker/qip-ui";
import {
    Chain as ChainSchema,
    Element as ElementSchema
} from "@netcracker/qip-schemas";
import {Uri} from "vscode";
import {
    ChainCommitRequestAction,
    EMPTY_USER,
    findElementById,
    getParsedElementChildren
} from "./chainApiUtils";
import {fileApi} from "./file";
import { getExtensionsForUri } from './file/fileExtensions';


export async function getCurrentChainId(fileUri: Uri): Promise<string> {
    const chain = await getMainChain(fileUri);
    console.log('getCurrentChainId', chain.id);
    return chain.id;
}

export async function getMainChain(fileUri: Uri): Promise<ChainSchema> {
    return await fileApi.getMainChain(fileUri);
}

export async function getChainFileUri(chainId: string, currentFileUri?: Uri): Promise<Uri> {
    const extensions = getExtensionsForUri(currentFileUri);
    return await fileApi.findFileById(chainId, extensions.chain);
}

export async function getLibrary(): Promise<LibraryData> {
    return fileApi.getLibrary();
}

export async function getLibraryElementByType(type: string): Promise<LibraryElement> {
    const result = findLibraryElementByType(await fileApi.getLibrary(), type);
    if (result === null) {
        throw Error(`Library element not found: ${type}`);
    }
    return result;
}

function findLibraryElementByType(partialLibraryData: any, type: string): LibraryElement | null {
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

export function getMaskedField(chain: any, id: string) { // TODO Replace to MaskedFieldSchema type
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
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const fields: MaskedField[] = [];
    if (chain.content.maskedFields) {
        for (const maskedField of chain.content.maskedFields as any[]) { // TODO Replace to MaskedFieldSchema type
            fields.push(parseMaskedField(chain, maskedField.id));
        }
    }

    return  {
        fields,
    };
}

export async function getConnections(fileUri: Uri, chainId: string): Promise<Dependency[]> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    return parseDependencies(chain.content.dependencies as any[]); // TODO Replace to MaskedFieldSchema type
}

export async function getElements(fileUri: Uri, chainId: string): Promise<Element[]> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    return await parseElements(fileUri, chain.content.elements as ElementSchema[], chain.id);
}

export async function getElement(fileUri: Uri, chainId: string, elementId: string): Promise<Element> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = findElementById(chain.content.elements as ElementSchema[], elementId);
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

async function parseElement(fileUri: Uri, element: ElementSchema, chainId: string, parentId: string | undefined = undefined): Promise<Element> {
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

    if ((element.properties as any)?.propertiesToExportInSeparateFile) {
        const elementProperties = element.properties as any;
        if (elementProperties.exportFileExtension === 'json') {
            const propertyNames: string[] | undefined = elementProperties.propertiesToExportInSeparateFile?.split(',').map(function (item: string) {
                return item.trim();
            });
            const properties: any = JSON.parse(await fileApi.readFile(fileUri, elementProperties.propertiesFilename as string));
            if (propertyNames) {
                for (const propertyName of propertyNames) {
                    elementProperties[propertyName] = properties[propertyName];
                }
            }
        } else {
            elementProperties[elementProperties.propertiesToExportInSeparateFile as string] = await fileApi.readFile(fileUri, elementProperties.propertiesFilename as string);
        }
    }

    if (element.type as unknown as string === 'service-call') {
        const elementProperties = element.properties as any; // WA before fix of schemas compilation missing service call properties
        if (Array.isArray((elementProperties.after))) {
            for (const afterBlock of elementProperties.after) {
                await handleServiceCallProperty(afterBlock);
            }
        }
        if (elementProperties.before) {
            await handleServiceCallProperty(elementProperties.before);
        }
    }

    let children: Element[] | undefined = undefined;
    if ((element.children as ElementSchema[])?.length) {
        children = [];
        for (const child of element.children as ElementSchema[]) {
            children.push(await parseElement(fileUri, child, chainId, element.id));
        }
    }

    return {
        id: element.id,
        name: element.name,
        type: element.type as unknown as string,
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

async function parseElements(fileUri: Uri, elements: ElementSchema[], chainId: string): Promise<Element[]> {
    const result: Element[] = [];

    if (elements && elements.length) {
        for (const element of elements) {
            const parsedElement = await parseElement(fileUri, element, chainId);
            result.push(parsedElement);
            result.push(...getParsedElementChildren(parsedElement.children));
        }
    }
    return result;
}

export async function getChain(fileUri: Uri, chainId: string): Promise<Chain> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const labels: EntityLabel[] = chain.content.labels ? chain.content.labels.map(label => ({name: label, technical: false})) : [];

    console.log("READING NAVIGATE PATH:", chain.content);

    const navigationPath = new Map<string, string>();
    let currentFolder = chain.content.folder;
    while (currentFolder) {
        console.log("CURRENT FOLDER:", currentFolder);
        navigationPath.set(currentFolder.name, currentFolder.name);
        currentFolder = currentFolder.subfolder;
    }
    navigationPath.set(chain.id, chain.name);

    console.log("NAVIGATE PATH:", navigationPath);

    return {
        assumptions: chain.content.assumptions as string,
        businessDescription: chain.content.businessDescription as string,
        containsDeprecatedContainers: false,
        containsDeprecatedElements: false,
        containsUnsupportedElements: false,
        createdBy: {...EMPTY_USER},
        createdWhen: chain.content.modifiedWhen as number,
        currentSnapshot: undefined,
        defaultSwimlaneId: chain.content.defaultSwimlaneId as string,
        dependencies: parseDependencies(chain.content.dependencies as any[]),
        deployments: chain.content.deployments as any[],
        deployAction: chain.content.deployAction
          ? ChainCommitRequestAction[chain.content.deployAction as keyof typeof ChainCommitRequestAction]
          : undefined,
        description: chain.content.description as string,
        elements: await parseElements(fileUri, chain.content.elements as ElementSchema[], chain.id),
        id: chain.id,
        labels: labels,
        modifiedBy: {...EMPTY_USER},
        modifiedWhen: chain.content.modifiedWhen as number,
        name: chain.name,
        navigationPath: new Map<string, string>(navigationPath),
        outOfScope: chain.content.outOfScope as string,
        reuseSwimlaneId: chain.content.reuseSwimlaneId  as string,
        unsavedChanges: false
    };
}
