import {
    ActionDifference, Chain,
    ConnectionRequest,
    Dependency,
    Element,
    ElementRequest, LibraryElementProperty,
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
import {EMPTY_USER} from "./chainApi";
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

    const element = chain.content.elements?.find((element: { id: string; }) => element.id === elementId);
    if (!element) {
        console.error(`ElementId not found`);
        throw Error("ElementId not found");
    }

    element.name = elementRequest.name;
    // TODO parentElementId -> child
    element.description = elementRequest.description;
    element.properties = elementRequest.properties;

    await writeElementProperties(mainFolderUri, element);
    await writeMainChain(mainFolderUri, chain);

    return {
        updatedElements: [
            await getElement(mainFolderUri, chainId, elementId)
        ]
    };
}

async function writeElementProperties(mainFolderUri: Uri, element: any): Promise<void> {
    async function handleServiceCallProperty(beforeAfterBlock: any) {
        if (beforeAfterBlock.type === 'script') {
            await writePropertyFile(mainFolderUri, beforeAfterBlock.propertiesFilename, beforeAfterBlock['script']);
            delete beforeAfterBlock['script'];
        } else if (beforeAfterBlock.type?.startsWith('mapper')) {
            if (beforeAfterBlock.type === 'mapper') {
                console.error("Attempt to save Deprecated element failed as it is not supported");
                throw Error("Deprecated Mapper element is not supported");
            }

            const property: any = JSON.stringify({mappingDescription: beforeAfterBlock['mappingDescription']});
            await writePropertyFile(mainFolderUri, beforeAfterBlock.propertiesFilename, property);
            delete beforeAfterBlock['mappingDescription'];
        }
    }

    if (element.properties.propertiesToExportInSeparateFile) {
        if (element.properties.exportFileExtension === 'json') {
            const propertyNames: string[] = element.properties.propertiesToExportInSeparateFile.split(',').map(function (item: string) {
                return item.trim();
            });
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

export async function createElement(context: ExtensionContext, mainFolderUri: Uri, chainId: string, elementRequest: ElementRequest): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const elementId = crypto.randomUUID();
    const libraryData = await getLibraryElementByType(context, elementRequest.type);


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
        type: elementRequest.type
    };
    // TODO all todos from update element applicable to this method too (create mutual method for saving entity?)

    chain.content.elements.push(element);
    await writeMainChain(mainFolderUri, chain);

    return {
        createdElements: [
            await getElement(mainFolderUri, chainId, elementId)
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

export async function deleteElement(mainFolderUri: Uri, chainId: string, elementId: string): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    const element = chain.content.elements?.find((element: Element) => element.id === elementId);
    if (!element) {
        console.error(`ElementId not found`);
        throw Error("ElementId not found");
    }

    let index = chain.content.elements.findIndex((e: Element) => e === element);
    chain.content.elements.splice(index, 1);
    await deleteDependenciesForElement(elementId, chain.content.dependencies);

    await writeMainChain(mainFolderUri, chain);

    return {
        removedElements: [
            element
        ]
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

    const elementFrom = chain.content.elements?.find((element: Element) => element.id === connectionRequest.from);
    if (!elementFrom) {
        console.error(`ElementId from not found`);
        throw Error("ElementId from not found");
    }
    const elementTo = chain.content.elements?.find((element: Element) => element.id === connectionRequest.to);
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

export async function deleteConnection(mainFolderUri: Uri, chainId: string, connectionId: string): Promise<ActionDifference> {
    const chain: any = await getMainChain(mainFolderUri);
    if (chain.id !== chainId) {
        console.error(`ChainId mismatch`);
        throw Error("ChainId mismatch");
    }

    let dependency: Dependency = chain.content.dependencies?.find((dependency: Dependency) =>
        getDependencyId(dependency) === connectionId);
    if (!dependency) {
        console.error(`Connection not found`);
        throw Error("Connection not found");
    }

    let index = chain.content.dependencies.findIndex((d: Dependency) => d === dependency);
    chain.content.dependencies.splice(index, 1);

    await writeMainChain(mainFolderUri, chain);

    return {
        removedDependencies: [
            dependency
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