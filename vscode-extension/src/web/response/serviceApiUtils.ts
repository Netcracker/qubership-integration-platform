import vscode, {ExtensionContext} from "vscode";
import { fileApi } from "./file/fileApiProvider";
import {getCurrentServiceId} from "./serviceApiRead";
import {createService} from "./serviceApiModify";
import {SpecificationImportApiHandler} from "../api-services/SpecificationImportApiHandler";
import {SerializedFile} from "../api-services/importApiTypes";

export async function getServiceUri(serviceFileUri: vscode.Uri): Promise<string> {
    const result = `/services/systems/${await getCurrentServiceId(serviceFileUri)}/parameters`;
    return result;
}

export async function getServiceSpecificationsUri(serviceFileUri: vscode.Uri, groupId: string): Promise<string> {
    const serviceId = await getCurrentServiceId(serviceFileUri);
    const result = `/services/systems/${serviceId}/specificationGroups/${groupId}/specifications`;
    return result;
}

export async function getServiceOperationsUri(serviceFileUri: vscode.Uri, groupId: string, specId: string): Promise<string> {
    const serviceId = await getCurrentServiceId(serviceFileUri);
    const result = `/services/systems/${serviceId}/specificationGroups/${groupId}/specifications/${specId}/operations`;
    return result;
}

export async function handleImportSpecificationGroup(context: ExtensionContext | undefined, serviceFileUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const importHandler = new SpecificationImportApiHandler(context, serviceFileUri);
    return await importHandler.handleImportSpecificationGroup(payload);
}

export async function handleImportSpecification(context: ExtensionContext | undefined, serviceFileUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const importSpecHandler = new SpecificationImportApiHandler(context, serviceFileUri);
    return await importSpecHandler.handleImportSpecification(payload.specificationGroupId, payload.files as SerializedFile[], payload.systemId);
}

export async function handleGetImportSpecificationResult(context: ExtensionContext | undefined, serviceFileUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const resultHandler = new SpecificationImportApiHandler(context, serviceFileUri);
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
