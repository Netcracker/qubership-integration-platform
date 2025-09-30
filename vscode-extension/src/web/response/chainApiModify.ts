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
    EMPTY_USER,
    findElementById,
    getElementChildren,
    LibraryElementQuantity,
    LibraryInputQuantity,
    replaceElementPlaceholders
} from "./chainApiUtils";
import {Uri} from "vscode";
import {fileApi} from "./file/fileApiProvider";

export async function updateChain(mainFolderUri: Uri, chainId: string, chainRequest: Partial<Chain>): Promise<Chain> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    chain.name = chainRequest.name !== undefined ? chainRequest.name : chain.name;
    chain.content.description = chainRequest.description !== undefined ? chainRequest.description : chain.content.description;
    chain.content.labels = chainRequest.labels !== undefined ? chainRequest.labels : chain.content.labels;
    chain.content.businessDescription = chainRequest.businessDescription !== undefined ? chainRequest.businessDescription : chain.content.businessDescription;
    chain.content.assumptions = chainRequest.assumptions !== undefined ? chainRequest.assumptions : chain.content.assumptions;
    chain.content.outOfScope = chainRequest.outOfScope !== undefined ? chainRequest.outOfScope : chain.content.outOfScope;
    chain.content.deployments = chainRequest.deployments !== undefined ? chainRequest.deployments : chain.content.deployments;
    chain.content.deployAction = chainRequest.deployAction !== undefined ? chainRequest.deployAction : chain.content.deployAction;

    await fileApi.writeMainChain(mainFolderUri, chain);

    return await getChain(mainFolderUri, chainId);
}

async function checkRestrictions(element: any, elements:any[]) {
    const libraryData = await getLibraryElementByType(element.type);
    const parentElementId = findElementById(elements, element.id)?.parentId; // More consistent way instead of parentElementId field

    if (parentElementId) {
        if (!libraryData.allowedInContainers) {
            console.error(`Invalid parent for element`);
            throw Error("Invalid parent for element");
        }

        const parentElement = findElementById(elements, parentElementId)?.element;
        if (parentElement) {
            const libraryParentData = await getLibraryElementByType(parentElement.type);

            if (libraryData.parentRestriction?.length > 0) {
                if (!libraryData.parentRestriction.find(type => type === parentElement.type)) {
                    console.error(`Invalid parent type for element`);
                    throw Error("Invalid parent type for element");
                }
            }

            // Check for allowed children inside parent element
            if (libraryParentData.allowedChildren && Object.keys(libraryParentData.allowedChildren).length > 0) {
                const amount = libraryParentData.allowedChildren[element.type];
                if (!amount) {
                    console.error(`Invalid type for parent element`);
                    throw Error("Invalid type for parent element");
                }

                if (amount === LibraryElementQuantity.ONE || amount === LibraryElementQuantity.ONE_OR_ZERO) {
                    const actualAmount = parentElement.children?.filter((e: { type: string; }) => e.type === element.type).length;

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
                if (!(element.children?.filter((e: { type: string; }) => e.type === childType).length > 0)) {
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

export async function updateElement(mainFolderUri: Uri, chainId: string, elementId: string, elementRequest: PatchElementRequest): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = findAndRemoveElementById(chain.content.elements, elementId);
    if (!element) {
        console.error(`ElementId not found`);
        throw Error("ElementId not found");
    }

    let parentElement = undefined;
    if (elementRequest.parentElementId) {
        parentElement = findElementById(chain.content.elements, elementRequest.parentElementId);
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
        if (!parentElement.element.children?.length) {
            parentElement.element.children = [];
        }
        parentElement.element.children.push(element);
    } else {
        chain.content.elements.push(element);
    }

    await checkRestrictions(element, chain.content.elements);

    await writeElementProperties(mainFolderUri, element);
    await fileApi.writeMainChain(mainFolderUri, chain);

    return {
        updatedElements: [
            await getElement(mainFolderUri, chainId, elementId)
        ]
    };
}

export async function transferElement(mainFolderUri: Uri, chainId: string, elementRequest: TransferElementRequest): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    for (const elementId of elementRequest.elements) {
        const element = findAndRemoveElementById(chain.content.elements, elementId);
        if (!element) {
            console.error(`ElementId not found`);
            throw Error("ElementId not found");
        }

        chain.content.dependencies?.forEach( (dependency: Dependency) => {
            if (dependency.from === elementId || dependency.to === elementId) {
                if (!elementRequest.elements.includes(dependency.from) || !elementRequest.elements.includes(dependency.to)) {
                    console.error(`Element ${elementId} not found has outside dependencies`);
                    throw Error(`Element ${elementId} not found has outside dependencies`);
                }
            }
        });

        let parentElement = undefined;
        if (elementRequest.parentId) {
            parentElement = findElementById(chain.content.elements, elementRequest.parentId);
            if (!parentElement) {
                console.error(`Parent ElementId not found`);
                throw Error("Parent ElementId not found");
            }
        }

        element.parentElementId = elementRequest.parentId || undefined;
        if (parentElement) {
            if (!parentElement.element.children?.length) {
                parentElement.element.children = [];
            }
            parentElement.element.children.push(element);
        } else {
            chain.content.elements.push(element);
        }

        await checkRestrictions(element, chain.content.elements);

    }

    await fileApi.writeMainChain(mainFolderUri, chain);

    const updatedElements: Element[] = [];
    for (const elementId of elementRequest.elements) {
        updatedElements.push(await getElement(mainFolderUri, chainId, elementId));
    }

    return {
         updatedElements: updatedElements
    };
}


function getOrCreatePropertyFilename(type: string, propertyNames: string[], exportFileExtension: any, id: string) {
    let prefix: string;
    if (type.startsWith('mapper')) {
        prefix = propertyNames.length === 1 ? propertyNames[0] : 'mapper';
    } else {
        prefix = propertyNames.length === 1 ? propertyNames[0] : 'properties';
    }

    return `${prefix}-${id}.${exportFileExtension}`;
}

async function writeElementProperties(mainFolderUri: Uri, element: any): Promise<void> {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        const propertiesFilenameId = (beforeAfterBlock.id ? beforeAfterBlock.id + '-' : '') + element.id;
        if (beforeAfterBlock.type === 'script') {
            beforeAfterBlock.propertiesFilename = getOrCreatePropertyFilename(beforeAfterBlock.type, ['script'], 'groovy', propertiesFilenameId);
            await fileApi.writePropertyFile(mainFolderUri, beforeAfterBlock.propertiesFilename, beforeAfterBlock['script']);
            delete beforeAfterBlock['script'];
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            if (beforeAfterBlock.type === 'mapper') {
                console.error("Attempt to save Deprecated element failed as it is not supported");
                throw Error("Deprecated Mapper element is not supported");
            }
            beforeAfterBlock.propertiesFilename = getOrCreatePropertyFilename(beforeAfterBlock.type, ['mappingDescription'], 'json', propertiesFilenameId);
            const property: any = JSON.stringify({mappingDescription: beforeAfterBlock['mappingDescription']});
            await fileApi.writePropertyFile(mainFolderUri, beforeAfterBlock.propertiesFilename, property);
            delete beforeAfterBlock['mappingDescription'];
        }
    }

    if (element.properties.propertiesToExportInSeparateFile) {
        const propertyNames: string[] = element.properties.propertiesToExportInSeparateFile.split(',').map(function (item: string) {
            return item.trim();
        });
        element.properties.propertiesFilename = getOrCreatePropertyFilename(element.type, propertyNames, element.properties.exportFileExtension, element.id);
        if (element.properties.exportFileExtension === 'json') {
            const properties: any = {};
            for (const propertyName of propertyNames) {
                properties[propertyName] = element.properties[propertyName];
            }
            await fileApi.writePropertyFile(mainFolderUri, element.properties.propertiesFilename, JSON.stringify(properties));
            for (const propertyName of propertyNames) {
                delete element.properties[propertyName];
            }
        } else {
            await fileApi.writePropertyFile(mainFolderUri, element.properties.propertiesFilename, element.properties[element.properties.propertiesToExportInSeparateFile]);
            delete element.properties[element.properties.propertiesToExportInSeparateFile];
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
}

async function getDefaultElementByType(chainId: string, elementRequest: CreateElementRequest) {
    const elementId = crypto.randomUUID();
    const libraryData = await getLibraryElementByType(elementRequest.type);

    let children: Element[] | undefined = undefined;
    if (libraryData.allowedChildren && Object.keys(libraryData.allowedChildren).length) {
        children = [];
        for (const childType in libraryData.allowedChildren) {
            if (libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE ||
                libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE_OR_MANY) {
                children.push(await getDefaultElementByType(chainId, {type: childType, parentElementId: elementId}));
            }
        }
    }

    const element: Element = {
        chainId: chainId,
        createdBy: {...EMPTY_USER},
        createdWhen: 0,
        description: "",
        id: elementId,
        mandatoryChecksPassed: false,
        modifiedBy: {...EMPTY_USER},
        modifiedWhen: 0,
        name: libraryData.title,
        properties: await getDefaultPropertiesForElement(libraryData.properties),
        type: elementRequest.type,
        children: children,
        parentElementId: elementRequest.parentElementId
    } as Element;

    if (element.type === 'checkpoint' || element.type === 'chain-trigger-2') {
        replaceElementPlaceholders(element.properties, chainId, elementId);
    }

    return element;
}

export async function createElement(mainFolderUri: Uri, chainId: string, elementRequest: CreateElementRequest): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = await getDefaultElementByType(chainId, elementRequest);

    if (!chain.content.elements) {
        chain.content.elements = [];
    }
    if (!insertElement(chain.content.elements, element)) {
        chain.content.elements.push(element);
    }

    await checkRestrictions(element, chain.content.elements);

    await writeElementProperties(mainFolderUri, element);
    await fileApi.writeMainChain(mainFolderUri, chain);

    return {
        createdElements: [
            await getElement(mainFolderUri, chainId, element.id)
        ]
    };
}

function insertElement(elements: Element[], newElement: Element): boolean {
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
            element.children.push(newElement);
            return true;
        }

        if (element.children && insertElement(element.children, newElement)) {
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
    elements: Element[] | undefined,
    elementId: string
): Element | undefined {
    if (!elements) {
        return undefined;
    }
    const index = elements.findIndex(e => e.id === elementId);
    if (index !== -1) {
        return elements.splice(index, 1)[0];
    }

    for (const element of elements) {
        const found = findAndRemoveElementById(element.children, elementId);
        if (found) {
            return found;
        }
    }

    return undefined;
}

async function deleteElementsPropertyFiles(mainFolderUri: Uri, removedElements: any[]) {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        if (beforeAfterBlock.type === 'script') {
            beforeAfterBlock['script'] = await fileApi.removeFile(mainFolderUri, beforeAfterBlock.propertiesFilename);
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            await fileApi.removeFile(mainFolderUri, beforeAfterBlock.propertiesFilename);
        }
    }

    for (const element of removedElements) {
        if (element.properties?.propertiesToExportInSeparateFile) {
            await fileApi.removeFile(mainFolderUri, element.properties.propertiesFilename);
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
            await deleteElementsPropertyFiles(mainFolderUri, element.children);
        }
    }
}



export async function deleteElements(mainFolderUri: Uri, chainId: string, elementIds: string[]): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const removedElements: any[] = [];
    for (const elementId of elementIds) {
        const parentElementId = findElementById(chain.content.elements, elementId)?.parentId;
        const element = findAndRemoveElementById(chain.content.elements, elementId);
        if (!element) {
            console.error(`ElementId not found`);
            throw Error("ElementId not found");
        }

        for (const childElement of getElementChildren(element.children)) {
            await deleteDependenciesForElement(childElement.id, chain.content.dependencies);
            removedElements.push(childElement);
        }

        await deleteDependenciesForElement(elementId, chain.content.dependencies);
        removedElements.push(element);

        const parentElement = parentElementId ? findElementById(chain.content.elements, parentElementId)?.element : undefined;
        if (parentElement) {
            await checkRestrictions(parentElement, chain.content.elements);
        }
    }

    await fileApi.writeMainChain(mainFolderUri, chain);
    await deleteElementsPropertyFiles(mainFolderUri, removedElements);

    return {
        removedElements: [...removedElements]
    };
}

async function deleteDependenciesForElement(elementId: string, dependencies: Dependency[]) {
    dependencies?.forEach( (dependency, index) => {
        if (dependency.from === elementId || dependency.to === elementId) {
            dependencies.splice(index,1);
        }
    });
}

export async function createConnection(mainFolderUri: Uri, chainId: string, connectionRequest: ConnectionRequest): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const elementFrom = findElementById(chain.content.elements, connectionRequest.from)?.element;
    if (!elementFrom) {
        console.error(`ElementId from not found`);
        throw Error("ElementId from not found");
    }
    const libraryDataFrom = await getLibraryElementByType(elementFrom.type);
    if (!libraryDataFrom.outputEnabled) {
        console.error(`Element from does not allow output connections`);
        throw Error("Element from does not allow output connections");
    }

    const elementTo = findElementById(chain.content.elements, connectionRequest.to)?.element;
    if (!elementTo) {
        console.error(`ElementId to not found`);
        throw Error("ElementId to not found");
    }
    const libraryDataTo = await getLibraryElementByType(elementTo.type);
    if (!libraryDataTo.inputEnabled) {
        console.error(`Element to does not allow output connections`);
        throw Error("Element to does not allow output connections");
    }
    if (libraryDataTo.inputQuantity === LibraryInputQuantity.ONE && chain.content.dependencies?.find((d: Dependency) => d.to === connectionRequest.to)) {
        console.error(`Element to does not allow another connections`);
        throw Error("Element to does not allow another connections");
    }

    const dependency: Dependency = chain.content.dependencies?.find((dependency: Dependency) =>
        dependency.from === connectionRequest.from && dependency.to === connectionRequest.to);
    if (dependency) {
        console.error(`Connection already exist`);
        throw Error("Connection already exist");
    }
    const newDependency: any = {
        from: connectionRequest.from,
        to: connectionRequest.to,
    };

    if (!chain.content.dependencies) {
        chain.content.dependencies = [];
    }
    chain.content.dependencies.push(newDependency);

    await fileApi.writeMainChain(mainFolderUri, chain);

    // TODO Change to read dependency from file
    newDependency['id'] = getDependencyId(newDependency);
    return {
        createdDependencies: [
            newDependency
        ]
    };
}

export async function deleteConnections(mainFolderUri: Uri, chainId: string, connectionIds: string[]): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const removedConnections: any[] = [];

    for (const connectionId of connectionIds) {
        let dependency: Dependency = chain.content.dependencies?.find((dependency: Dependency) =>
            getDependencyId(dependency) === connectionId);
        if (!dependency) {
            console.error(`Connection not found`);
            throw Error("Connection not found");
        }

        let index = chain.content.dependencies.findIndex((d: Dependency) => d === dependency);
        chain.content.dependencies.splice(index, 1);

        dependency['id'] = getDependencyId(dependency);
        removedConnections.push(dependency);
    }

    await fileApi.writeMainChain(mainFolderUri, chain);

    return {
        removedDependencies: [
            ...removedConnections
        ]
    };
}

export async function deleteMaskedFields(mainFolderUri: Uri, chainId: string, maskedFieldIds: string[]): Promise<void> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    for (const maskedFieldId of maskedFieldIds) {
        let index = chain.content.maskedFields?.findIndex((mf: any) => mf.id === maskedFieldId);
        if (index) {
            chain.content.maskedFields.splice(index, 1);
        }
    }

    await fileApi.writeMainChain(mainFolderUri, chain);
}

export async function updateMaskedField(mainFolderUri: Uri, id: string, chainId: string, changes: Partial<MaskedField>): Promise<MaskedField> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }
    let maskedField = getMaskedField(chain, id);

    maskedField.name = changes.name;

    await fileApi.writeMainChain(mainFolderUri, chain);

    return parseMaskedField(chain, id);
}

export async function createMaskedField(mainFolderUri: Uri, chainId: string, changes: Partial<MaskedField>): Promise<MaskedField> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    if (!chain.content.maskedFields) {
        chain.content.maskedFields = [];
    }

    const id = crypto.randomUUID();;
    chain.content.maskedFields.push({
        id: id,
        name: changes.name,
    });

    await fileApi.writeMainChain(mainFolderUri, chain);

    return parseMaskedField(chain, id);
}
