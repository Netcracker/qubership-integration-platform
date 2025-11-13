import {
    ActionDifference,
    Chain,
    ConnectionRequest,
    CreateElementRequest,
    Dependency,
    Element,
    LibraryElementProperty,
    MaskedField,
    PatchElementRequest,
    TransferElementRequest
} from "@netcracker/qip-ui";
import {
    getChain,
    getDependencyId,
    getElement,
    getLibraryElementByType,
    getMainChain,
    getMaskedField, parseMaskedField
} from "./chainApiRead";
import {
    findElementById,
    getElementChildren,
    LibraryElementQuantity,
    LibraryInputQuantity,
    replaceElementPlaceholders
} from "./chainApiUtils";
import {Uri} from "vscode";
import {fileApi} from "./file";
import {Element as ElementSchema, DataType, Chain as ChainSchema} from "@netcracker/qip-schemas";

export async function updateChain(fileUri: Uri, chainId: string, chainRequest: Partial<Chain>): Promise<Chain> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const labels = chainRequest?.labels?.filter(label => !label.technical).map(label => label.name);

    chain.name = chainRequest.name !== undefined ? chainRequest.name : chain.name;
    chain.content.description = chainRequest.description !== undefined ? chainRequest.description : chain.content.description;
    chain.content.labels = labels !== undefined ? labels : chain.content.labels;
    chain.content.businessDescription = chainRequest.businessDescription !== undefined ? chainRequest.businessDescription : chain.content.businessDescription;
    chain.content.assumptions = chainRequest.assumptions !== undefined ? chainRequest.assumptions : chain.content.assumptions;
    chain.content.outOfScope = chainRequest.outOfScope !== undefined ? chainRequest.outOfScope : chain.content.outOfScope;
    chain.content.deployments = chainRequest.deployments !== undefined ? chainRequest.deployments : chain.content.deployments;
    chain.content.deployAction = chainRequest.deployAction !== undefined ? chainRequest.deployAction : chain.content.deployAction;

    await fileApi.writeMainChain(fileUri, chain);

    return await getChain(fileUri, chainId);
}

async function checkRestrictions(element: ElementSchema, elements:ElementSchema[]) {
    const elementType = element.type as unknown as string;
    const libraryData = await getLibraryElementByType(elementType);
    const parentElementId = findElementById(elements, element.id)?.parentId; // More consistent way instead of parentElementId field

    if (parentElementId) {
        if (!libraryData.allowedInContainers) {
            console.error(`Invalid parent for element`);
            throw Error("Invalid parent for element");
        }

        const parentElement = findElementById(elements, parentElementId)?.element;
        if (parentElement) {
            const libraryParentData = await getLibraryElementByType(parentElement.type as unknown as string);

            if (libraryData.parentRestriction?.length > 0) {
                if (!libraryData.parentRestriction.find(type => type === parentElement.type as unknown as string)) {
                    console.error(`Invalid parent type for element`);
                    throw Error("Invalid parent type for element");
                }
            }

            // Check for allowed children inside parent element
            if (libraryParentData.allowedChildren && Object.keys(libraryParentData.allowedChildren).length > 0) {
                const amount = libraryParentData.allowedChildren[elementType];
                if (!amount) {
                    console.error(`Invalid type for parent element`);
                    throw Error("Invalid type for parent element");
                }

                if (amount === LibraryElementQuantity.ONE || amount === LibraryElementQuantity.ONE_OR_ZERO) {
                    const actualAmount = (parentElement.children as ElementSchema[])?.filter((e: ElementSchema) => e.type === element.type).length;

                    if (actualAmount === undefined || actualAmount > 1 || (actualAmount === 0 && amount === LibraryElementQuantity.ONE)) {
                        console.error(`Incorrect amount of element type for parent element`);
                        throw Error("Incorrect amount of element type for parent element");
                    }
                }
            }
        }
    } else {
        if (libraryData.parentRestriction?.length > 0) {
            console.error(`Invalid parent type for element`);
            throw Error("Invalid parent type for element");
        }
    }

    // Check if element doesn't have enough elements as children (in case of deletion)
    if (libraryData.allowedChildren && Object.keys(libraryData.allowedChildren).length > 0) {
        for (const childType in libraryData.allowedChildren) {
            if (libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE || libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE_OR_MANY) {
                if (!((element.children as ElementSchema[])?.filter((e: ElementSchema) => e.type as unknown as string === childType).length > 0)) {
                    console.error(`Incorrect amount of children elements`);
                    throw Error("Incorrect amount of children elements");
                }
            }
        }
    }
    // Can't check it after element add, i.e. if you add "Try" element it will be always empty
    // if (libraryData.mandatoryInnerElement && !(element.children?.length > 0)) {
    //     console.error(`Incorrect amount of children elements`);
    //     throw Error("Incorrect amount of children elements");
    // }
}

export async function updateElement(fileUri: Uri, chainId: string, elementId: string, elementRequest: PatchElementRequest): Promise<ActionDifference> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = findAndRemoveElementById(chain.content.elements as ElementSchema[], elementId);
    if (!element) {
        console.error(`ElementId not found`);
        throw Error("ElementId not found");
    }

    let parentElement = undefined;
    if (elementRequest.parentElementId) {
        parentElement = findElementById(chain.content.elements as ElementSchema[], elementRequest.parentElementId);
        if (!parentElement) {
            console.error(`Parent ElementId not found`);
            throw Error("Parent ElementId not found");
        }
    }

    element.name = elementRequest.name;
    element.description = elementRequest.description;
    (element as any).properties = elementRequest.properties;
    element.parentElementId = elementRequest.parentElementId;
    if (parentElement) {
        if (!(parentElement.element.children as ElementSchema[])?.length) {
            parentElement.element.children = [];
        }
        (parentElement.element.children as ElementSchema[]).push(element);
    } else {
        (chain.content.elements as ElementSchema[]).push(element);
    }

    await checkRestrictions(element, chain.content.elements as ElementSchema[]);

    await writeElementProperties(fileUri, element);
    await fileApi.writeMainChain(fileUri, chain);

    return {
        updatedElements: [
            await getElement(fileUri, chainId, elementId)
        ]
    };
}

export async function transferElement(fileUri: Uri, chainId: string, elementRequest: TransferElementRequest): Promise<ActionDifference> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const chainElements = chain.content.elements as ElementSchema[];
    for (const elementId of elementRequest.elements) {
        const element = findAndRemoveElementById(chainElements, elementId);
        if (!element) {
            console.error(`ElementId not found`);
            throw Error("ElementId not found");
        }

        (chain.content.dependencies as [])?.forEach( (dependency: Dependency) => { // TODO change to dependency schema
            if (dependency.from === elementId || dependency.to === elementId) {
                if (!elementRequest.elements.includes(dependency.from) || !elementRequest.elements.includes(dependency.to)) {
                    console.error(`Element ${elementId} not found has outside dependencies`);
                    throw Error(`Element ${elementId} not found has outside dependencies`);
                }
            }
        });

        let parentElement = undefined;
        if (elementRequest.parentId) {
            parentElement = findElementById(chainElements, elementRequest.parentId);
            if (!parentElement) {
                console.error(`Parent ElementId not found`);
                throw Error("Parent ElementId not found");
            }
        }

        element.parentElementId = elementRequest.parentId || undefined;
        if (parentElement) {
            if (!(parentElement.element.children as ElementSchema[])?.length) {
                parentElement.element.children = [];
            }
            (parentElement.element.children as ElementSchema[]).push(element);
        } else {
            chainElements.push(element);
        }

        await checkRestrictions(element, chainElements);

    }

    await fileApi.writeMainChain(fileUri, chain);

    const updatedElements: Element[] = [];
    for (const elementId of elementRequest.elements) {
        updatedElements.push(await getElement(fileUri, chainId, elementId));
    }

    return {
         updatedElements: updatedElements
    };
}


function getOrCreatePropertyFilename(type: string, propertyNames: string[] | undefined, exportFileExtension: string | undefined, id: string) {
    let prefix: string;
    if (!propertyNames || !exportFileExtension) {
        throw new Error(`Property names and exportFileExtension should be presented`);
    }
    if (type.startsWith('mapper')) {
        prefix = propertyNames.length === 1 ? propertyNames[0] : 'mapper';
    } else {
        prefix = propertyNames.length === 1 ? propertyNames[0] : 'properties';
    }

    return `${prefix}-${id}.${exportFileExtension}`;
}

async function writeElementProperties(fileUri: Uri, element: ElementSchema): Promise<void> {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        const propertiesFilenameId = (beforeAfterBlock.id ? beforeAfterBlock.id + '-' : '') + element.id;
        if (beforeAfterBlock.type === 'script') {
            beforeAfterBlock.propertiesFilename = getOrCreatePropertyFilename(beforeAfterBlock.type, ['script'], 'groovy', propertiesFilenameId);
            await fileApi.writePropertyFile(fileUri, beforeAfterBlock.propertiesFilename, beforeAfterBlock['script']);
            delete beforeAfterBlock['script'];
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            if (beforeAfterBlock.type === 'mapper') {
                console.error("Attempt to save Deprecated element failed as it is not supported");
                throw Error("Deprecated Mapper element is not supported");
            }
            beforeAfterBlock.propertiesFilename = getOrCreatePropertyFilename(beforeAfterBlock.type, ['mappingDescription'], 'json', propertiesFilenameId);
            const property: any = JSON.stringify({mappingDescription: beforeAfterBlock['mappingDescription']}, null, 2);
            await fileApi.writePropertyFile(fileUri, beforeAfterBlock.propertiesFilename, property);
            delete beforeAfterBlock['mappingDescription'];
        }
    }

    const elementType = element.type as unknown as string;
    if ((element.properties as any)?.propertiesToExportInSeparateFile) {
        const elementProperties = element.properties as any;
        const propertyNames: string[] | undefined = elementProperties.propertiesToExportInSeparateFile?.split(',').map(function (item: string) {
            return item.trim();
        });
        elementProperties.propertiesFilename = getOrCreatePropertyFilename(elementType, propertyNames, elementProperties.exportFileExtension, element.id);
        if (elementProperties.exportFileExtension === 'json' && propertyNames) {
            const properties: any = {};
            for (const propertyName of propertyNames) {
                properties[propertyName] = elementProperties[propertyName];
            }
            await fileApi.writePropertyFile(fileUri, elementProperties.propertiesFilename, JSON.stringify(properties, null, 2));
            for (const propertyName of propertyNames) {
                delete elementProperties[propertyName];
            }
        } else {
            await fileApi.writePropertyFile(fileUri, elementProperties.propertiesFilename,
                elementProperties[elementProperties.propertiesToExportInSeparateFile as string] as string);
            delete elementProperties[elementProperties.propertiesToExportInSeparateFile as string];
        }
    }

    if (elementType === 'service-call') {
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
}

async function getDefaultElementByType(chainId: string, elementRequest: CreateElementRequest): Promise<ElementSchema> {
    const elementId = crypto.randomUUID();
    const libraryData = await getLibraryElementByType(elementRequest.type as unknown as string);

    let children: ElementSchema[] | undefined = undefined;
    if (libraryData.allowedChildren && Object.keys(libraryData.allowedChildren).length) {
        children = [];
        for (const childType in libraryData.allowedChildren) {
            if (libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE ||
                libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE_OR_MANY) {
                children.push(await getDefaultElementByType(chainId, {type: childType, parentElementId: elementId}));
            }
        }
    }

    const element: ElementSchema = {
        chainId: chainId,
        description: "",
        id: elementId,
        mandatoryChecksPassed: false,
        name: libraryData.title,
        properties: await getDefaultPropertiesForElement(libraryData.properties),
        type: elementRequest.type as unknown as DataType,
        children: children,
        parentElementId: elementRequest.parentElementId
    };

    if (elementRequest.type === 'checkpoint' || elementRequest.type === 'chain-trigger-2') {
        replaceElementPlaceholders(element.properties, chainId, elementId);
    }

    return element;
}

export async function createElement(mainFolderUri: Uri, chainId: string, elementRequest: CreateElementRequest): Promise<ActionDifference> {
    const chain = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = await getDefaultElementByType(chainId, elementRequest);

    if (!chain.content.elements) {
        chain.content.elements = [];
    }
    const chainElements = chain.content.elements as ElementSchema[];
    if (!insertElement(chainElements, element)) {
        chainElements.push(element);
    }

    await checkRestrictions(element, chainElements);

    await writeElementProperties(mainFolderUri, element);
    await fileApi.writeMainChain(mainFolderUri, chain);

    return {
        createdElements: [
            await getElement(mainFolderUri, chainId, element.id)
        ]
    };
}

function insertElement(elements: ElementSchema[], newElement: ElementSchema): boolean {
    if (!newElement.parentElementId) {
        // no parent, add to root
        elements.push(newElement);
        return true;
    }

    for (const element of elements) {
        if (element.id === newElement.parentElementId) {
            if (!element.children) {
                element.children = [];
            }
            (element.children as ElementSchema[]).push(newElement);
            return true;
        }

        if (element.children && insertElement((element.children as ElementSchema[]), newElement)) {
            return true; // inserted in nested children
        }
    }

    return false; // parent not found
}

function getDefaultPropertiesForElement(libraryProperties: any): any {

    let properties: any = {};
    for (const propertyType in libraryProperties) {
        properties = {
            ...properties,
            ...getDefaultTypedProperties(libraryProperties[propertyType])
        };
    }
    return properties;
}

function getDefaultTypedProperties(propertiesData: LibraryElementProperty[]): any {
    const result: any = {};
    for (const property of propertiesData) {
        if (property.default) {
            let defaultValue: any = String(property.default);
            switch (property.type) {
                case 'boolean':
                    defaultValue = defaultValue === 'true';
                    break;
                case 'number':
                    defaultValue = parseFloat(defaultValue);
                    break;
            }
            result[property.name] = defaultValue;
        }
    }
    return result;
}

function findAndRemoveElementById(
    elements: ElementSchema[] | undefined,
    elementId: string
): ElementSchema | undefined {
    if (!elements) {
        return undefined;
    }
    const index = elements.findIndex(e => e.id === elementId);
    if (index !== -1) {
        return elements.splice(index, 1)[0];
    }

    for (const element of elements) {
        const found = findAndRemoveElementById(element.children as ElementSchema[], elementId);
        if (found) {
            return found;
        }
    }

    return undefined;
}

async function deleteElementsPropertyFiles(fileUri: Uri, removedElements: any[]) {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        if (beforeAfterBlock.type === 'script') {
            beforeAfterBlock['script'] = await fileApi.removeFile(fileUri, beforeAfterBlock.propertiesFilename);
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            await fileApi.removeFile(fileUri, beforeAfterBlock.propertiesFilename);
        }
    }

    for (const element of removedElements) {
        if (element.properties?.propertiesToExportInSeparateFile) {
            await fileApi.removeFile(fileUri, element.properties.propertiesFilename);
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

        if (element.children?.length) {
            await deleteElementsPropertyFiles(fileUri, element.children);
        }
    }
}



export async function deleteElements(fileUri: Uri, chainId: string, elementIds: string[]): Promise<ActionDifference> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const removedElements: any[] = [];
    const chainElements = chain.content.elements as ElementSchema[];
    for (const elementId of elementIds) {
        const parentElementId = findElementById(chainElements, elementId)?.parentId;
        const element = findAndRemoveElementById(chainElements, elementId);
        if (!element) {
            console.error(`ElementId not found`);
            throw Error("ElementId not found");
        }

        for (const childElement of getElementChildren(element.children as ElementSchema[])) {
            await deleteDependenciesForElement(childElement.id, chain.content.dependencies as Dependency[]); // TODO change to dependency schema
            removedElements.push(childElement);
        }

        await deleteDependenciesForElement(elementId, chain.content.dependencies as Dependency[]); // TODO change to dependency schema
        removedElements.push(element);

        const parentElement = parentElementId ? findElementById(chainElements, parentElementId)?.element : undefined;
        if (parentElement) {
            await checkRestrictions(parentElement, chainElements);
        }
    }

    await fileApi.writeMainChain(fileUri, chain);
    await deleteElementsPropertyFiles(fileUri, removedElements);

    return {
        removedElements: [...removedElements]
    };
}

async function deleteDependenciesForElement(elementId: string, dependencies: Dependency[]) { // TODO change to dependency schema
    dependencies?.forEach( (dependency, index) => {
        if (dependency.from === elementId || dependency.to === elementId) {
            dependencies.splice(index,1);
        }
    });
}

export async function createConnection(fileUri: Uri, chainId: string, connectionRequest: ConnectionRequest): Promise<ActionDifference> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    if (!chain.content.dependencies) {
        chain.content.dependencies = [];
    }
    const chainDependencies = chain.content.dependencies as Dependency[]; // TODO change to dependency schema
    const chainElements = chain.content.elements as ElementSchema[];

    const elementFrom = findElementById(chainElements, connectionRequest.from)?.element;
    if (!elementFrom) {
        console.error(`ElementId from not found`);
        throw Error("ElementId from not found");
    }
    const libraryDataFrom = await getLibraryElementByType(elementFrom.type as unknown as string);
    if (!libraryDataFrom.outputEnabled) {
        console.error(`Element from does not allow output connections`);
        throw Error("Element from does not allow output connections");
    }

    const elementTo = findElementById(chainElements, connectionRequest.to)?.element;
    if (!elementTo) {
        console.error(`ElementId to not found`);
        throw Error("ElementId to not found");
    }
    const libraryDataTo = await getLibraryElementByType(elementTo.type as unknown as string);
    if (!libraryDataTo.inputEnabled) {
        console.error(`Element to does not allow output connections`);
        throw Error("Element to does not allow output connections");
    }
    if (libraryDataTo.inputQuantity === LibraryInputQuantity.ONE && chainDependencies?.find((d: Dependency) => d.to === connectionRequest.to)) {
        console.error(`Element to does not allow another connections`);
        throw Error("Element to does not allow another connections");
    }

    const dependency: Dependency | undefined = chainDependencies?.find((dependency: Dependency) =>
        dependency.from === connectionRequest.from && dependency.to === connectionRequest.to);
    if (dependency) {
        console.error(`Connection already exist`);
        throw Error("Connection already exist");
    }
    const newDependency: any = {
        from: connectionRequest.from,
        to: connectionRequest.to,
    };

    chainDependencies.push(newDependency);

    await fileApi.writeMainChain(fileUri, chain);

    // TODO Change to read dependency from file
    newDependency['id'] = getDependencyId(newDependency);
    return {
        createdDependencies: [
            newDependency
        ]
    };
}

export async function deleteConnections(fileUri: Uri, chainId: string, connectionIds: string[]): Promise<ActionDifference> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const removedConnections: any[] = [];

    for (const connectionId of connectionIds) {
        let dependency: Dependency | undefined = (chain.content.dependencies as Dependency[])?.find((dependency: Dependency) => // TODO change to dependency schema
            getDependencyId(dependency) === connectionId);
        if (!dependency) {
            console.error(`Connection not found`);
            throw Error("Connection not found");
        }

        let index = (chain.content.dependencies as Dependency[]).findIndex((d: Dependency) => d === dependency); // TODO change to dependency schema
        (chain.content.dependencies as Dependency[]).splice(index, 1);

        dependency['id'] = getDependencyId(dependency);
        removedConnections.push(dependency);
    }

    await fileApi.writeMainChain(fileUri, chain);

    return {
        removedDependencies: [
            ...removedConnections
        ]
    };
}

export async function deleteMaskedFields(fileUri: Uri, chainId: string, maskedFieldIds: string[]): Promise<void> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    // TODO change to maskedfield type
    for (const maskedFieldId of maskedFieldIds) {
        let index = (chain.content.maskedFields as [])?.findIndex((mf: any) => mf.id === maskedFieldId);
        if (index) {
            (chain.content.maskedFields as []).splice(index, 1);
        }
    }

    await fileApi.writeMainChain(fileUri, chain);
}

export async function updateMaskedField(fileUri: Uri, id: string, chainId: string, changes: Partial<MaskedField>): Promise<MaskedField> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }
    let maskedField = getMaskedField(chain, id);

    maskedField.name = changes.name;

    await fileApi.writeMainChain(fileUri, chain);

    return parseMaskedField(chain, id);
}

export async function createMaskedField(fileUri: Uri, chainId: string, changes: Partial<MaskedField>): Promise<MaskedField> {
    const chain = await getMainChain(fileUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    if (!chain.content.maskedFields) {
        chain.content.maskedFields = [];
    }

    const id = crypto.randomUUID();
    // @ts-ignore Will be removed when DependencySchema will be introduced
    chain.content.maskedFields.push({
        id: id,
        name: changes.name,
    });

    await fileApi.writeMainChain(fileUri, chain);

    return parseMaskedField(chain, id);
}
