import {
    ActionDifference,
    Chain,
    ConnectionRequest,
    CreateElementRequest,
    Dependency,
    Element,
    LibraryElementProperty,
    LibraryElementQuantity,
    PatchElementRequest
} from "./apiTypes";
import * as yaml from 'yaml';
import {
    getChain,
    getDependencyId,
    getElement,
    getLibraryElementByType,
    getMainChain,
    getMainChainFileUri
} from "./chainApiRead";
import {EMPTY_USER, findElementById, getElementChildren, RESOURCES_FOLDER} from "./chainApi";
import {ExtensionContext, Uri} from "vscode";

const vscode = require('vscode');

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

    await writeMainChain(mainFolderUri, chain);

    return await getChain(mainFolderUri, chainId);
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
    element.properties = elementRequest.properties;
    element.parentElementId = elementRequest.parentElementId;
    if (parentElement) {
        if (!parentElement.children?.length) {
            parentElement.children = [];
        }
        parentElement.children.push(element);
    } else {
        chain.content.elements.push(element);
    }

    await writeElementProperties(mainFolderUri, element);
    await writeMainChain(mainFolderUri, chain);

    return {
        updatedElements: [
            await getElement(mainFolderUri, chainId, elementId)
        ]
    };
}

function getOrCreatePropertyFilename(type: string, propertyNames: string[], exportFileExtension: any, id: string) {
    let prefix: string;
    if (type.startsWith('mapper')) {
        prefix = propertyNames.length === 1 ? propertyNames[0] : 'mapper';
    } else {
        prefix = propertyNames.length === 1 ? propertyNames[0] : 'properties';
    }

    return `resources/${prefix}-${id}.${exportFileExtension}`;
}

async function writeElementProperties(mainFolderUri: Uri, element: any): Promise<void> {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        const propertiesFilenameId = (beforeAfterBlock.id ? beforeAfterBlock.id + '-' : '') + element.id;
        if (beforeAfterBlock.type === 'script') {
            beforeAfterBlock.propertiesFilename = getOrCreatePropertyFilename(beforeAfterBlock.type, ['script'], 'groovy', propertiesFilenameId);
            await writePropertyFile(mainFolderUri, beforeAfterBlock.propertiesFilename, beforeAfterBlock['script']);
            delete beforeAfterBlock['script'];
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            if (beforeAfterBlock.type === 'mapper') {
                console.error("Attempt to save Deprecated element failed as it is not supported");
                throw Error("Deprecated Mapper element is not supported");
            }
            beforeAfterBlock.propertiesFilename = getOrCreatePropertyFilename(beforeAfterBlock.type, ['mappingDescription'], 'json', propertiesFilenameId);
            const property: any = JSON.stringify({mappingDescription: beforeAfterBlock['mappingDescription']});
            await writePropertyFile(mainFolderUri, beforeAfterBlock.propertiesFilename, property);
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
            await writePropertyFile(mainFolderUri, element.properties.propertiesFilename, JSON.stringify(properties));
            for (const propertyName of propertyNames) {
                delete element.properties[propertyName];
            }
        } else {
            await writePropertyFile(mainFolderUri, element.properties.propertiesFilename, element.properties[element.properties.propertiesToExportInSeparateFile]);
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

async function writePropertyFile(mainFolderUri: Uri, fileName: string, data: string) {
    const bytes = new TextEncoder().encode(data);
    try {
        await vscode.workspace.fs.writeFile(vscode.Uri.joinPath(mainFolderUri, fileName), bytes);
        vscode.window.showInformationMessage('Property file has been updated!');
    } catch (err) {
        vscode.window.showErrorMessage('Failed to write file: ' + err);
        throw Error('Failed to write file: ' + err);
    }
}

async function writeMainChain(mainFolderUri: Uri, chain: any) {
    const bytes = new TextEncoder().encode(yaml.stringify(chain));
    try {
        await vscode.workspace.fs.writeFile(await getMainChainFileUri(mainFolderUri), bytes);
        vscode.window.showInformationMessage('Chain has been updated!');
    } catch (err) {
        vscode.window.showErrorMessage('Failed to write file: ' + err);
        throw Error('Failed to write file: ' + err);
    }
}

async function getDefaultElementByType(context: ExtensionContext, chainId: string, elementRequest: CreateElementRequest) {
    const elementId = crypto.randomUUID();
    const libraryData = await getLibraryElementByType(context, elementRequest.type);

    let children: Element[] | undefined = undefined;
    if (libraryData.allowedChildren && Object.keys(libraryData.allowedChildren).length) {
        children = [];
        for (const childType in libraryData.allowedChildren) {
            if (libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE ||
                libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE_OR_MANY) {
                children.push(await getDefaultElementByType(context, chainId, {type: childType, parentElementId: elementId}));
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
    };

    return element;
}

export async function createElement(context: ExtensionContext, mainFolderUri: Uri, chainId: string, elementRequest: CreateElementRequest): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = await getDefaultElementByType(context, chainId, elementRequest);

    chain.content.elements.push(element);

    await writeElementProperties(mainFolderUri, element);
    await writeMainChain(mainFolderUri, chain);

    return {
        createdElements: [
            await getElement(mainFolderUri, chainId, element.id)
        ]
    };
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
            result[property.name] = property.default;
        }
    }
    return result;
}

function findAndRemoveElementById(
    elements: Element[] | undefined,
    elementId: string
): Element | undefined {
    if (!elements) return undefined;

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
            beforeAfterBlock['script'] = await removeFile(mainFolderUri, beforeAfterBlock.propertiesFilename);
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            await removeFile(mainFolderUri, beforeAfterBlock.propertiesFilename);
        }
    }

    for (const element of removedElements) {
        if (element.properties?.propertiesToExportInSeparateFile) {
            await removeFile(mainFolderUri, element.properties.propertiesFilename);
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

async function removeFile(mainFolderUri: Uri, propertiesFilename: string): Promise<void> {
    console.log("removing property file", propertiesFilename);
    const fileUri = vscode.Uri.joinPath(mainFolderUri, propertiesFilename);
    console.log("property file uri", fileUri);
    try {
        await vscode.workspace.fs.delete(fileUri);
    } catch (error) {
        console.log("Error deleting property file", fileUri);
    }

    return;
}

export async function deleteElements(mainFolderUri: Uri, chainId: string, elementIds: string[]): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const removedElements: any[] = [];
    for (const elementId of elementIds) {
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
    }

    await writeMainChain(mainFolderUri, chain);
    await deleteElementsPropertyFiles(mainFolderUri, removedElements);

    return {
        removedElements: [...removedElements]
    };
}

async function deleteDependenciesForElement(elementId: string, dependencies: Dependency[]) {
    dependencies.forEach( (dependency, index) => {
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

    const elementFrom = findElementById(chain.content.elements, connectionRequest.from);
    if (!elementFrom) {
        console.error(`ElementId from not found`);
        throw Error("ElementId from not found");
    }
    const elementTo = findElementById(chain.content.elements, connectionRequest.to);
    if (!elementTo) {
        console.error(`ElementId to not found`);
        throw Error("ElementId to not found");
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

    chain.content.dependencies.push(newDependency);

    await writeMainChain(mainFolderUri, chain);

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

        removedConnections.push(dependency);
    }

    await writeMainChain(mainFolderUri, chain);

    return {
        removedDependencies: [
            ...removedConnections
        ]
    };
}

export async function createEmptyChain(isInParentDir: boolean = false) {
    try {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders) {
            vscode.window.showErrorMessage('Open a workspace folder first');
            return;
        }
        const arg = await vscode.window.showInputBox({prompt: 'Enter new chain name'});

        let folderUri = workspaceFolders[0].uri;
        const chainId = crypto.randomUUID();
        const chainName = arg || 'New Chain';
        if (isInParentDir) {
            folderUri = vscode.Uri.joinPath(folderUri, '..');
        }
        folderUri = vscode.Uri.joinPath(folderUri, chainId);

        // Create the folder
        await vscode.workspace.fs.createDirectory(folderUri);

        // Create template file
        const chainFileUri = vscode.Uri.joinPath(folderUri, `${chainId}.chain.qip.yaml`);
        const chain = {
            $schema: 'http://qubership.org/schemas/product/qip/chain',
            id: chainId,
            name: chainName,
            content: {
                migrations: "[100, 101]",
                elements: [],
                dependencies: [],
            }
        };
        const bytes = new TextEncoder().encode(yaml.stringify(chain));

        await vscode.workspace.fs.writeFile(chainFileUri, bytes);
        vscode.window.showInformationMessage(`Chain "${chainName}" created with id ${chainId}`);
    } catch (err) {
        vscode.window.showErrorMessage(`Failed: ${err}`);
    }
}
