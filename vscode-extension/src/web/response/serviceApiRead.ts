import {IntegrationSystem, Environment, SpecificationGroup, Specification, SystemOperation, OperationInfo} from "./apiTypes";
import * as yaml from 'yaml';
import {FileType, Uri} from "vscode";
import {EMPTY_USER} from "./chainApiUtils";
import {fileApi} from "./file/fileApiProvider";

const vscode = require('vscode');

export async function getCurrentServiceId(mainFolderUri: Uri): Promise<string> {
    const service: any = await getMainService(mainFolderUri);
    console.log('getCurrentServiceId', service.id);
    return service.id;
}

export async function getMainServiceFileUri(mainFolderUri: Uri) {
    if (mainFolderUri) {
        const serviceFile = await fileApi.getMainService(mainFolderUri);
        if (!serviceFile) {
            console.error(`Single *.service.qip.yaml file not found in the current directory`);
            vscode.window.showWarningMessage("*.service.qip.yaml file not found in the current directory");
            throw Error("Single *.service.qip.yaml file not found in the current directory");
        }
        return vscode.Uri.joinPath(mainFolderUri, `${serviceFile.id}.service.qip.yaml`);
    }
    return undefined;
}

export async function getMainService(mainFolderUri: Uri): Promise<any> {
    return await fileApi.getMainService(mainFolderUri);
}

export async function getService(mainFolderUri: Uri, serviceId: string): Promise<IntegrationSystem> {
    const service: any = await getMainService(mainFolderUri);
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch`);
        throw Error("ServiceId mismatch");
    }
    
    return {
        id: service.id,
        name: service.name,
        description: service.content.description || "",
        createdBy: service.content.createdBy || {...EMPTY_USER},
        modifiedBy: service.content.modifiedBy || {...EMPTY_USER},
        createdWhen: service.content.createdWhen || 0,
        modifiedWhen: service.content.modifiedWhen || 0,
        activeEnvironmentId: service.content.activeEnvironmentId || "",
        integrationSystemType: service.content.integrationSystemType || "EXTERNAL",
        protocol: service.content.protocol || "HTTP",
        extendedProtocol: service.content.extendedProtocol || "",
        specification: service.content.specification || "",
        labels: service.content.labels || []
    };
}

export async function getEnvironments(mainFolderUri: Uri, serviceId: string): Promise<Environment[]> {
    const service: any = await getMainService(mainFolderUri);
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch`);
        throw Error("ServiceId mismatch");
    }

    return parseEnvironments(service.content.environments);
}

function parseEnvironments(environments: any[]): Environment[] {
    const result: Environment[] = [];
    if (environments && environments.length) {
        for (const env of environments) {
            result.push({
                id: env.id,
                name: env.name,
                description: env.description || "",
                createdBy: env.createdBy || {...EMPTY_USER},
                modifiedBy: env.modifiedBy || {...EMPTY_USER},
                createdWhen: env.createdWhen || 0,
                modifiedWhen: env.modifiedWhen || 0,
                address: env.address || "",
                sourceType: env.sourceType || "MANUAL",
                properties: env.properties || {},
                labels: env.labels || []
            });
        }
    }
    return result;
}

export async function getApiSpecifications(mainFolderUri: Uri, serviceId: string): Promise<SpecificationGroup[]> {
    const service: any = await getMainService(mainFolderUri);
    
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch: expected ${serviceId}, got ${service.id}`);
        throw Error("ServiceId mismatch");
    }

    const specGroupFiles = await fileApi.findSpecificationGroupFiles(mainFolderUri);
    const result: SpecificationGroup[] = [];
    
    for (const fileName of specGroupFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(mainFolderUri, fileName);
            const fileContent = await fileApi.readFileContent(fileUri);
            const text = new TextDecoder('utf-8').decode(fileContent);
            const parsed = yaml.parse(text);

            if (parsed && parsed.content && parsed.content.parentId === serviceId) {
                
                const specifications = await getSpecificationModel(mainFolderUri, serviceId, parsed.id);
                
                const group = {
                    id: parsed.id,
                    name: parsed.name,
                    description: parsed.content.description || "",
                    createdBy: parsed.content.createdBy || {...EMPTY_USER},
                    modifiedBy: parsed.content.modifiedBy || {...EMPTY_USER},
                    createdWhen: parsed.content.createdWhen || 0,
                    modifiedWhen: parsed.content.modifiedWhen || 0,
                    specifications: specifications, 
                    synchronization: parsed.content.synchronization || false,
                    parentId: parsed.content.parentId,
                    labels: parsed.labels || [],
                    systemId: parsed.content.parentId
                };
                result.push(group);
            }
        } catch (e) {
            console.error(`Failed to parse specification group file ${fileName}`, e);
        }
    }

    return result;
}

export async function getSpecificationModel(mainFolderUri: Uri, serviceId: string, groupId: string): Promise<Specification[]> {
    const specFiles = await fileApi.findSpecificationFiles(mainFolderUri);
    const result: Specification[] = [];
    
    for (const fileName of specFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(mainFolderUri, fileName);
            const fileContent = await fileApi.readFileContent(fileUri);
            const text = new TextDecoder('utf-8').decode(fileContent);
            const parsed = yaml.parse(text);

            if (parsed && parsed.content && parsed.content.parentId === groupId) {
                
                const operations = parseOperations(parsed.content.operations, parsed.id);
                
                const spec = {
                    id: parsed.id,
                    name: parsed.name,
                    description: parsed.content.description || "",
                    createdBy: parsed.content.createdBy || {...EMPTY_USER},
                    modifiedBy: parsed.content.modifiedBy || {...EMPTY_USER},
                    createdWhen: parsed.content.createdWhen || 0,
                    modifiedWhen: parsed.content.modifiedWhen || 0,
                    version: parsed.content.version || "",
                    format: parsed.content.format || "",
                    content: parsed.content.content || "",
                    deprecated: parsed.content.deprecated || false,
                    parentId: parsed.content.parentId,
                    labels: parsed.labels || [],
                    specificationGroupId: parsed.content.parentId,
                    source: parsed.content.content || "",
                    systemId: serviceId,
                    operations: operations
                };
                result.push(spec);
            }
        } catch (e) {
            console.error(`Failed to parse specification file ${fileName}`, e);
        }
    }

    return result;
}

export async function getOperationInfo(mainFolderUri: Uri, operationId: string): Promise<OperationInfo> {
    const specFiles = await fileApi.findSpecificationFiles(mainFolderUri);

    for (const fileName of specFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(mainFolderUri, fileName);
            const fileContent = await fileApi.readFileContent(fileUri);
            const text = new TextDecoder('utf-8').decode(fileContent);
            const parsed = yaml.parse(text);

            if (parsed && parsed.content && parsed.content.operations) {
                const operation = parsed.content.operations.find((op: any) => op.id === operationId);
                if (operation) {
                    return {
                        id: operation.id,
                        specification: operation.specification || {},
                        requestSchema: operation.requestSchema || {},
                        responseSchemas: operation.responseSchemas || {}
                    };
                }
            }
        } catch (e) {
            console.error(`Failed to parse specification file ${fileName}`, e);
        }
    }

    throw new Error(`Operation with id ${operationId} not found`);
}

function parseOperations(operations: any[], modelId: string): SystemOperation[] {
    const result: SystemOperation[] = [];
    
    if (operations && Array.isArray(operations)) {
        for (const op of operations) {
            const operation = {
                id: op.id,
                name: op.name,
                description: op.description || "",
                method: op.method || "",
                path: op.path || "",
                modelId: modelId,
                chains: [] 
            };
            result.push(operation);
        }
    }
    
    return result;
}

export async function getServices(mainFolderUri: Uri): Promise<IntegrationSystem[]> {

    const service: any = await getMainService(mainFolderUri);
    if (!service) {
        return [];
    }
    
    return [await getService(mainFolderUri, service.id)];
}
