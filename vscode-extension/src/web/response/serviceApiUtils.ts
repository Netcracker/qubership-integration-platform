import vscode, {ExtensionContext} from "vscode";
import {fileApi} from "./file/fileApiProvider";
import {getCurrentServiceId} from "./serviceApiRead";
import {createService} from "./serviceApiModify";
import {SpecificationImportApiHandler} from "../api-services/SpecificationImportApiHandler";
import {SerializedFile} from "../api-services/importApiTypes";

export async function getContextServiceUri(serviceFileUri: vscode.Uri): Promise<string> {
    return `/services/context/${await getCurrentServiceId(serviceFileUri)}/parameters`;
}

export async function getServiceUri(serviceFileUri: vscode.Uri): Promise<string> {
    return `/services/systems/${await getCurrentServiceId(serviceFileUri)}/parameters`;
}

export async function getServiceSpecificationsUri(serviceFileUri: vscode.Uri, groupId: string): Promise<string> {
    const serviceId = await getCurrentServiceId(serviceFileUri);
    return `/services/systems/${serviceId}/specificationGroups/${groupId}/specifications`;
}

export async function getServiceOperationsUri(serviceFileUri: vscode.Uri, groupId: string, specId: string): Promise<string> {
    const serviceId = await getCurrentServiceId(serviceFileUri);
    return `/services/systems/${serviceId}/specificationGroups/${groupId}/specifications/${specId}/operations`;
}

export async function handleImportSpecificationGroup(context: ExtensionContext | undefined, serviceFileUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const importHandler = new SpecificationImportApiHandler(serviceFileUri);
    return await importHandler.handleImportSpecificationGroup(payload);
}

export async function handleImportSpecification(context: ExtensionContext | undefined, serviceFileUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const importSpecHandler = new SpecificationImportApiHandler(serviceFileUri);
    return await importSpecHandler.handleImportSpecification(payload.specificationGroupId, payload.files as SerializedFile[], payload.systemId);
}

export async function handleGetImportSpecificationResult(context: ExtensionContext | undefined, serviceFileUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const resultHandler = new SpecificationImportApiHandler(serviceFileUri);
    return await resultHandler.handleGetImportResult(payload.importId);
}

export async function handleCreateService(context: ExtensionContext | undefined, mainFolderUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for createService operation');
    }
    return await createService(context, mainFolderUri, payload);
}

export enum QipFileType {
    CHAIN = "CHAIN",
    SERVICE = "SERVICE",
    CONTEXT_SERVICE = "CONTEXT_SERVICE",
    FOLDER = "FOLDER",
    UNKNOWN = "UNKNOWN"
}

export async function getBaseFolder(fileOrFolder: vscode.Uri | undefined, fallback?: vscode.Uri): Promise<vscode.Uri | undefined> {
    if (fileOrFolder) {
        const type = await fileApi.getFileType(fileOrFolder);
        if (type === QipFileType.SERVICE || type === QipFileType.CHAIN) {
            const lastSlashIndex = fileOrFolder.path.lastIndexOf('/');
            const parentPath = lastSlashIndex > 0 ? fileOrFolder.path.substring(0, lastSlashIndex) : fileOrFolder.path;
            return fileOrFolder.with({ path: parentPath });
        }
        return fileOrFolder;
    }
    return fallback;
}

async function buildSpecApiFile(fileUri: vscode.Uri) {
    const content = await fileApi.parseFile(fileUri);
    if (!content || typeof content !== 'object') {
        return undefined;
    }

    const id = content.id;
    const name = content.name;
    const description = content.description;
    const contentSection = content.content;

    if (!id || !name || !contentSection) {
        return undefined;
    }

    const protocol = contentSection.specificationType || '';
    const specifications = contentSection.specifications;

    if (!Array.isArray(specifications) || specifications.length === 0) {
        return undefined;
    }

    const firstSpec = specifications[0];
    const specificationFilePath = firstSpec.filePath;

    if (!specificationFilePath || typeof specificationFilePath !== 'string') {
        return undefined;
    }

    return {
        id: String(id),
        name: String(name),
        description: description ? String(description) : undefined,
        protocol: String(protocol),
        specificationFilePath: String(specificationFilePath),
        fileUri: fileUri.toString()
    };
}

export async function handleGetSpecApiFiles(): Promise<any> {
    const apiFileUris = await fileApi.getSpecApiFiles();
    const seen = new Set<string>();
    const result: any[] = [];

    for (const fileUri of apiFileUris) {
        try {
            const specFile = await buildSpecApiFile(fileUri);
            if (!specFile) {
                continue;
            }
            const duplicateKey = `${specFile.id}:${specFile.specificationFilePath}`;
            if (seen.has(duplicateKey)) {
                continue;
            }
            seen.add(duplicateKey);
            result.push(specFile);
        } catch (error) {
            console.error(`[handleGetSpecApiFiles] Failed to process ${fileUri.path}`, error);
        }
    }

    return result;
}

export async function handleReadSpecificationFileContent(fileUri: string, specificationFilePath: string): Promise<string> {
    const apiFileUri = vscode.Uri.parse(fileUri);
    const apiFileDir = apiFileUri.with({ path: apiFileUri.path.substring(0, apiFileUri.path.lastIndexOf('/')) });
    const specFileUri = vscode.Uri.joinPath(apiFileDir, specificationFilePath);

    try {
        return await fileApi.readFileContent(specFileUri);
    } catch (error) {
        if (!specificationFilePath.includes('resources/')) {
            const resourcesPath = 'resources/' + specificationFilePath;
            const resourcesFileUri = vscode.Uri.joinPath(apiFileDir, resourcesPath);
            try {
                return await fileApi.readFileContent(resourcesFileUri);
            } catch (resourcesError) {
                throw new Error(`Specification file not found at ${specificationFilePath} or ${resourcesPath}`);
            }
        }
        throw error;
    }
}
