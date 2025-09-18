import vscode, { ExtensionContext } from "vscode";
import { getCurrentServiceId } from "./serviceApiRead";
import { createService } from "./serviceApiModify";
import { SpecificationImportApiHandler } from "../api-services/SpecificationImportApiHandler";
import { SerializedFile } from "../api-services/importApiTypes";

export async function getServiceUri(mainFolderUri: vscode.Uri): Promise<string> {
    const result = `/services/systems/${await getCurrentServiceId(mainFolderUri)}/parameters`;
    return result;
}

export async function getServiceSpecificationsUri(mainFolderUri: vscode.Uri, groupId: string): Promise<string> {
    const serviceId = await getCurrentServiceId(mainFolderUri);
    const result = `/services/systems/${serviceId}/specificationGroups/${groupId}/specifications`;
    return result;
}

export async function getServiceOperationsUri(mainFolderUri: vscode.Uri, groupId: string, specId: string): Promise<string> {
    const serviceId = await getCurrentServiceId(mainFolderUri);
    const result = `/services/systems/${serviceId}/specificationGroups/${groupId}/specifications/${specId}/operations`;
    return result;
}

export async function handleImportSpecificationGroup(context: ExtensionContext | undefined, mainFolderUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const importHandler = new SpecificationImportApiHandler(context, mainFolderUri);
    return await importHandler.handleImportSpecificationGroup(payload);
}

export async function handleImportSpecification(context: ExtensionContext | undefined, mainFolderUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const importSpecHandler = new SpecificationImportApiHandler(context, mainFolderUri);
    return await importSpecHandler.handleImportSpecification(payload.specificationGroupId, payload.files as SerializedFile[], payload.systemId);
}

export async function handleGetImportSpecificationResult(context: ExtensionContext | undefined, mainFolderUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for import operations');
    }
    const resultHandler = new SpecificationImportApiHandler(context, mainFolderUri);
    return await resultHandler.handleGetImportResult(payload.importId);
}

export async function handleCreateService(context: ExtensionContext | undefined, mainFolderUri: vscode.Uri, payload: any): Promise<any> {
    if (!context) {
        throw new Error('ExtensionContext is required for createService operation');
    }
    return await createService(context, mainFolderUri, payload);
}
