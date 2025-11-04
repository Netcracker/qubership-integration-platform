import {IntegrationSystem, Environment, SpecificationGroup, Specification, SystemOperation, OperationInfo, BaseEntity} from "../api-services/servicesTypes";
import {Uri} from "vscode";
import {fileApi} from "./file/fileApiProvider";
import { LabelUtils } from "../api-services/LabelUtils";
import { getExtensionsForUri } from './file/fileExtensions';
import { Chain } from "@netcracker/qip-ui";
import { ContentParser } from "../api-services/parsers/ContentParser";

const vscode = require('vscode');

export async function getCurrentServiceId(serviceFileUri: Uri): Promise<string> {
    const service: any = await getMainService(serviceFileUri);
    return service.id;
}

export async function getMainServiceFileUri(serviceFileUri: Uri): Promise<Uri> {
    return serviceFileUri;
}

export async function getMainService(serviceFileUri: Uri): Promise<any> {
    return await fileApi.getMainService(serviceFileUri);
}

export async function getService(serviceFileUri: Uri, serviceId: string): Promise<IntegrationSystem> {
    let actualServiceFileUri = serviceFileUri;
    let service: any = await getMainService(serviceFileUri);
    if (service.id !== serviceId) {
        const ext = getExtensionsForUri(serviceFileUri);
        actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
        service = await getMainService(actualServiceFileUri);

        if (service.id !== serviceId) {
            console.error(`ServiceId mismatch: expected "${serviceId}", got "${service.id}" even after finding file by ID`);
            throw Error(`ServiceId mismatch: expected "${serviceId}", got "${service.id}"`);
        }
    }

    return {
        id: service.id,
        name: service.name,
        description: service.content?.description || "",
        activeEnvironmentId: service.content?.activeEnvironmentId || "",
        integrationSystemType: service.content?.integrationSystemType || "",
        type: service.content?.integrationSystemType || "",
        protocol: (service.content?.protocol || "").toLowerCase(),
        extendedProtocol: service.content?.extendedProtocol || "",
        specification: service.content?.specification || "",
        environments: service.content?.environments || [],
        labels: LabelUtils.toEntityLabels(service.content?.labels || [])
    };
}

export async function getEnvironments(serviceFileUri: Uri, serviceId: string): Promise<Environment[]> {
    let actualServiceFileUri = serviceFileUri;
    let service: any = await getMainService(serviceFileUri);

    if (service.id !== serviceId) {
        const ext = getExtensionsForUri(serviceFileUri);
        actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
        service = await getMainService(actualServiceFileUri);

        if (service.id !== serviceId) {
            console.error(`ServiceId mismatch: expected "${serviceId}", got "${service.id}"`);
            throw Error("ServiceId mismatch");
        }
    }

    return parseEnvironments(service.content?.environments || []);
}

function parseEnvironments(environments: any[]): Environment[] {
    const result: Environment[] = [];
    if (environments && environments.length) {
        for (const env of environments) {
            result.push({
                id: env.id,
                name: env.name,
                description: env.description || "",
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
    let actualServiceFileUri = serviceFileUri;
    const ext = getExtensionsForUri(serviceFileUri);

    if (!serviceFileUri.path.endsWith(ext.service)) {
        try {
            actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
        } catch (e) {
            console.warn(`Could not find service file for ${serviceId}, using original URI`);
        }
    }

    const specFiles = await fileApi.getSpecificationFiles(actualServiceFileUri);
    const serviceFolderUri = vscode.Uri.joinPath(actualServiceFileUri, '..');
    const result: Specification[] = [];

    for (const fileName of specFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
            const parsed = await fileApi.parseFile(fileUri);

            if (parsed && parsed.content && parsed.content.parentId === groupId) {

                const operations = await parseOperations(parsed.content.operations, parsed.id);
                const chains = await getChainsUsingSpecification(serviceId, parsed.id);

                const spec = {
                    id: parsed.id,
                    name: parsed.name,
                    description: parsed.content.description || "",
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
    let actualServiceFileUri = serviceFileUri;

    const parts = modelId.split('-');
    if (parts.length >= 5 && !serviceFileUri.path.endsWith(ext.service)) {
        const serviceId = parts.slice(0, 5).join('-');
        try {
            actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
        } catch (e) {
            console.warn(`Could not find service file for ${serviceId}, using original URI`);
        }
    }

    if (actualServiceFileUri.path.endsWith(ext.service)) {
        const specFiles = await fileApi.getSpecificationFiles(actualServiceFileUri);
        const serviceFolderUri = vscode.Uri.joinPath(actualServiceFileUri, '..');

        for (const fileName of specFiles) {
            try {
                const specFileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
                const parsed = await fileApi.parseFile(specFileUri);

                if (parsed && parsed.id === modelId) {
                    return await parseOperations(parsed.content.operations, parsed.id);
                }
            } catch (e) {
                console.error(`Failed to parse specification file ${fileName}`, e);
            }
        }
    } else {
        const specFileUri = await fileApi.findFileById(modelId, ext.specification);
        try {
            const parsed = await fileApi.parseFile(specFileUri);

            return await parseOperations(parsed.content.operations, parsed.id);
        } catch (e) {
            console.error(`Failed to parse specification file ${specFileUri}`, e);
        }
    }

    return [];
}

export async function getOperationInfo(serviceFileUri: Uri, operationId: string): Promise<OperationInfo> {
    let actualServiceFileUri = serviceFileUri;

    const parts = operationId.split('-');
    if (parts.length >= 5) {
        const serviceId = parts.slice(0, 5).join('-');
        const service: any = await getMainService(serviceFileUri);

        if (service.id !== serviceId) {
            const ext = getExtensionsForUri(serviceFileUri);
            try {
                actualServiceFileUri = await fileApi.findFileById(serviceId, ext.service);
            } catch (e) {
                console.warn(`Could not find service file for ${serviceId}, using original URI`);
            }
        }
    }

    const specFiles = await fileApi.getSpecificationFiles(actualServiceFileUri);
    const serviceFolderUri = vscode.Uri.joinPath(actualServiceFileUri, '..');

    for (const fileName of specFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
            const parsed = await ContentParser.parseContentFromFile(fileUri);

            if (parsed && parsed.content && parsed.content.operations) {
                const operation = parsed.content.operations.find((op: any) => {
                    return op.id === operationId || operationId.endsWith(`-${op.id}`);
                });
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

async function parseOperations(operations: any[], modelId: string): Promise<SystemOperation[]> {
    const result: SystemOperation[] = [];

    if (operations && Array.isArray(operations)) {
        for (const op of operations) {
            const operation: SystemOperation = {
                id: op.id,
                name: op.name,
                description: op.description || "",
                method: op.method || "",
                path: op.path || "",
                modelId: modelId,
                chains: await getChainsUsingOperation(modelId, op.id),
            };
            result.push(operation);
        }
    }

    return result;
}

async function getChainsUsingOperation(specificationId: string, operationId: string): Promise<BaseEntity[]> {
    const result: BaseEntity[] = [];

    await fileApi.findAndBuildChainsRecursively<BaseEntity>(fileApi.getRootDirectory(), (chainYaml: any): BaseEntity | undefined => {
        if (chainYaml.content.elements) {
            for (const element of chainYaml.content.elements) {
                if (element?.properties?.integrationOperationId === operationId &&
                    element?.properties?.integrationSpecificationId === specificationId) {
                        return {
                            id: chainYaml.id,
                            name: chainYaml.name,
                        };
                    }
            }
        }
        return undefined;

    }, result);

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
