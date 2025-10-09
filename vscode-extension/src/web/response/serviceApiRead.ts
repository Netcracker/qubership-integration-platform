import {IntegrationSystem, Environment, SpecificationGroup, Specification, SystemOperation, OperationInfo} from "../api-services/servicesTypes";
import * as yaml from 'yaml';
import {Uri, WorkspaceFolder} from "vscode";
import {EMPTY_USER} from "./chainApiUtils";
import {fileApi} from "./file/fileApiProvider";
import { LabelUtils } from "../api-services/LabelUtils";
import { getExtensionsForUri } from './file/fileExtensions';
import { Chain } from "@netcracker/qip-ui";
import { ContentParser } from "../api-services/parsers/ContentParser";

const vscode = require('vscode');

export async function getCurrentServiceId(serviceFileUri: Uri): Promise<string> {
    const service: any = await getMainService(serviceFileUri);
    console.log('getCurrentServiceId', service.id);
    return service.id;
}

export async function getMainServiceFileUri(serviceFileUri: Uri): Promise<Uri> {
    return serviceFileUri;
}

export async function getMainService(serviceFileUri: Uri): Promise<any> {
    return await fileApi.getMainService(serviceFileUri);
}

export async function getService(serviceFileUri: Uri, serviceId: string): Promise<IntegrationSystem> {
    const service: any = await getMainService(serviceFileUri);
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
        type: service.content.integrationSystemType || "EXTERNAL",
        protocol: service.content.protocol || "HTTP",
        extendedProtocol: service.content.extendedProtocol || "",
        specification: service.content.specification || "",
        environments: service.content.environments || [],
        labels: LabelUtils.toEntityLabels(service.content.labels || [])
    };
}

export async function getEnvironments(serviceFileUri: Uri, serviceId: string): Promise<Environment[]> {
    const service: any = await getMainService(serviceFileUri);
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
                labels: LabelUtils.toEntityLabels(env.labels || [])
            });
        }
    }
    return result;
}

export async function getApiSpecifications(currentFile: Uri, serviceId: string): Promise<SpecificationGroup[]> {
    const ext = getExtensionsForUri(currentFile);
    const serviceFileUri = currentFile.path.endsWith(ext.service)? currentFile : await fileApi.findFileById(serviceId, ext.service);

    const service: any = await getMainService(serviceFileUri);

    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch: expected ${serviceId}, got ${service.id}`);
        throw Error("ServiceId mismatch");
    }

    const specGroupFiles = await fileApi.getSpecificationGroupFiles(serviceFileUri);
    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
    const result: SpecificationGroup[] = [];

    for (const fileName of specGroupFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
            const parsed = await fileApi.parseFile(fileUri);

            if (parsed && parsed.content && parsed.content.parentId === serviceId) {

                const specifications = await getSpecificationModel(serviceFileUri, serviceId, parsed.id);
                const chains = await getChainsUsingSpecificationGroup(serviceId, parsed.id);

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
                    labels: LabelUtils.toEntityLabels(parsed.content?.labels || []),
                    chains: chains,
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

export async function getSpecificationModel(serviceFileUri: Uri, serviceId: string, groupId: string): Promise<Specification[]> {
    const specFiles = await fileApi.getSpecificationFiles(serviceFileUri);
    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
    const result: Specification[] = [];

    for (const fileName of specFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
            const parsed = await fileApi.parseFile(fileUri);

            if (parsed && parsed.content && parsed.content.parentId === groupId) {

                const operations = parseOperations(parsed.content.operations, parsed.id);
                const chains = await getChainsUsingSpecification(serviceId, parsed.id);

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
                    labels: LabelUtils.toEntityLabels(parsed.content?.labels || []),
                    specificationGroupId: parsed.content.parentId,
                    source: parsed.content.content || "",
                    systemId: serviceId,
                    operations: operations,
                    chains: chains
                };
                result.push(spec);
            }
        } catch (e) {
            console.error(`Failed to parse specification file ${fileName}`, e);
        }
    }

    return result;
}

export async function getOperations(serviceFileUri: Uri, modelId: string): Promise<SystemOperation[]> {
    const ext = getExtensionsForUri(serviceFileUri);
    if (serviceFileUri.path.endsWith(ext.service)) {
        const specFiles = await fileApi.getSpecificationFiles(serviceFileUri);
        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');

        for (const fileName of specFiles) {
            try {
                const specFileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
                const parsed = await fileApi.parseFile(specFileUri);

                if (parsed && parsed.id === modelId) {
                    return parseOperations(parsed.content.operations, parsed.id);
                }
            } catch (e) {
                console.error(`Failed to parse specification file ${fileName}`, e);
            }
        }
    } else {
        const specFileUri = await fileApi.findFileById(modelId, ext.specification);
        try {
            const parsed = await fileApi.parseFile(specFileUri);

            return parseOperations(parsed.content.operations, parsed.id);
        } catch (e) {
            console.error(`Failed to parse specification file ${specFileUri}`, e);
        }
    }

    return [];
}

export async function getOperationInfo(serviceFileUri: Uri, operationId: string): Promise<OperationInfo> {
    const specFiles = await fileApi.getSpecificationFiles(serviceFileUri);
    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');

    for (const fileName of specFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
            const parsed = await ContentParser.parseContentFromFile(fileUri);

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

async function getChainsUsingSpecificationGroup(serviceId: string, groupId: string): Promise<Partial<Chain>[]> {
    const result: Partial<Chain>[] = [];

    await fileApi.findAndBuildChainsRecursively(fileApi.getRootDirectory(), (chainYaml: any): Partial<Chain> | undefined => {
        if (chainYaml.content.elements) {
            for (const element of chainYaml.content.elements) {
                if (element?.properties?.integrationSystemId === serviceId &&
                    element?.properties?.integrationSpecificationGroupId === groupId) {
                        return {id: chainYaml.id, name: chainYaml.name};
                    }
            }
        }
        return undefined;

    }, result);

    return result;
}

async function getChainsUsingSpecification(serviceId: string, specificationId: string): Promise<Partial<Chain>[]> {
    const result: Partial<Chain>[] = [];

    await fileApi.findAndBuildChainsRecursively(fileApi.getRootDirectory(), (chainYaml: any): Partial<Chain> | undefined => {
        if (chainYaml.content.elements) {
            for (const element of chainYaml.content.elements) {
                if (element?.properties?.integrationSystemId === serviceId &&
                    element?.properties?.integrationSpecificationId === specificationId) {
                        return {id: chainYaml.id, name: chainYaml.name};
                    }
            }
        }
        return undefined;

    }, result);

    return result;
}

export async function getServices(serviceFileUri: Uri): Promise<IntegrationSystem[]> {
    const ext = getExtensionsForUri(serviceFileUri);
    if (serviceFileUri.path.endsWith(ext.service)) {
        const service: any = await getMainService(serviceFileUri);
        if (!service) {
            return [];
        }

        return [await getService(serviceFileUri, service.id)];
    } else {
        const result: IntegrationSystem[] = [];
        const serviceFiles = await fileApi.findFiles(ext.service);
        for (const serviceFile of serviceFiles) {
            const service: any = await getMainService(serviceFile);
            result.push(await getService(serviceFile, service.id));
        }

        return result;
    }
}
