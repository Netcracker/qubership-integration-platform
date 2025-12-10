import {
    Environment,
    EnvironmentRequest,
    IntegrationSystem,
    IntegrationSystemType,
    Specification,
    SpecificationGroup,
    SystemRequest
} from "../api-services/servicesTypes";
import * as yaml from 'yaml';
import {getContextService, getMainService, getService} from "./serviceApiRead";
import vscode, {ExtensionContext, Uri} from "vscode";
import {ContentParser} from '../api-services/parsers/ContentParser';
import {getExtensionsForFile} from './file/fileExtensions';
import {fileApi} from "./file/fileApiProvider";
import {refreshQipExplorer} from "../extension";
import {LabelUtils} from "../api-services/LabelUtils";
import {ProjectConfigService} from "../services/ProjectConfigService";
import { ContextSystem } from "@netcracker/qip-ui";

export async function updateContextService(serviceFileUri: Uri, serviceId: string, serviceRequest: Partial<ContextSystem>): Promise<ContextSystem> {
    const service = await fileApi.getContextService(serviceFileUri, serviceId);

    if (!service.content) {
        service.content = {};
    }

    if (serviceRequest.name !== undefined) {
        service.name = serviceRequest.name;
    }
    if (serviceRequest.description !== undefined) {
        service.content.description = serviceRequest.description;
    }

    await writeMainService(serviceFileUri, service);
    const updatedService = await getContextService(serviceFileUri, serviceId);

    return updatedService;
}

export async function updateService(serviceFileUri: Uri, serviceId: string, serviceRequest: Partial<IntegrationSystem>): Promise<IntegrationSystem> {
    const service: any = await getMainService(serviceFileUri);

    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch: expected ${serviceId}, got ${service.id}`);
        throw Error("ServiceId mismatch");
    }

    if (!service.content) {
        service.content = {};
    }

    if (serviceRequest.name !== undefined) {
        service.name = serviceRequest.name;
    }
    if (serviceRequest.description !== undefined) {
        service.content.description = serviceRequest.description;
    }
    if (serviceRequest.labels !== undefined) {
        service.content.labels = LabelUtils.fromEntityLabels(serviceRequest.labels);
    }
    if (serviceRequest.integrationSystemType !== undefined) {
        service.content.integrationSystemType = serviceRequest.integrationSystemType;
    }
    if (serviceRequest.type !== undefined) {
        service.content.integrationSystemType = serviceRequest.type;
    }
    if (serviceRequest.protocol !== undefined) {
        service.content.protocol = serviceRequest.protocol.toUpperCase();
    }
    if (serviceRequest.extendedProtocol !== undefined) {
        service.content.extendedProtocol = serviceRequest.extendedProtocol;
    }
    if (serviceRequest.specification !== undefined) {
        service.content.specification = serviceRequest.specification;
    }
    if (serviceRequest.activeEnvironmentId !== undefined) {
        service.content.activeEnvironmentId = serviceRequest.activeEnvironmentId;
    }

    await writeMainService(serviceFileUri, service);
    const updatedService = await getService(serviceFileUri, serviceId);

    return updatedService;
}

export async function createService(context: ExtensionContext, mainFolderUri: Uri, serviceRequest: SystemRequest): Promise<IntegrationSystem> {
    try {
        const serviceId = crypto.randomUUID();
        const config = ProjectConfigService.getConfig();

        const service = {
            $schema: config.schemaUrls.service,
            id: serviceId,
            name: serviceRequest.name,
            content: {
                description: serviceRequest.description || "",
                activeEnvironmentId: "",
                integrationSystemType: serviceRequest.type || "EXTERNAL",
                protocol: (serviceRequest.protocol || "").toUpperCase(),
                extendedProtocol: serviceRequest.extendedProtocol || "",
                specification: serviceRequest.specification || "",
                environments: [],
                labels: LabelUtils.fromEntityLabels(serviceRequest.labels || []),
                migrations: []
            }
        };

        const serviceFolderUri = vscode.Uri.joinPath(mainFolderUri, serviceId);
        const ext = getExtensionsForFile();
        const serviceFileUri = vscode.Uri.joinPath(serviceFolderUri, `${serviceId}${ext.service}`);
        await fileApi.writeServiceFile(serviceFileUri, service);

        return {
            id: service.id,
            name: service.name,
            description: service.content.description || "",
            activeEnvironmentId: service.content.activeEnvironmentId || "",
            integrationSystemType: service.content.integrationSystemType || "EXTERNAL",
            protocol: service.content.protocol || "",
            extendedProtocol: service.content.extendedProtocol || "",
            specification: service.content.specification || "",
            environments: service.content.environments || [],
            labels: LabelUtils.toEntityLabels(service.content.labels || [])
        };
    } catch (error) {
        console.error('createService: Error creating service:', error);
        throw new Error(`Failed to create service: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
}

export async function updateEnvironment(serviceFileUri: Uri, serviceId: string, environmentId: string, environmentRequest: EnvironmentRequest): Promise<Environment> {
    const service: any = await getMainService(serviceFileUri);
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch`);
        throw Error("ServiceId mismatch");
    }

    if (!service.content) {
        service.content = {};
    }
    if (!service.content.environments) {
        service.content.environments = [];
    }

    const environmentIndex = service.content.environments.findIndex((env: any) => env.id === environmentId);
    if (environmentIndex === -1) {
        console.error(`EnvironmentId not found`);
        throw Error("EnvironmentId not found");
    }

    const environment = service.content.environments[environmentIndex];

    if (environmentRequest.name !== undefined) {
        environment.name = environmentRequest.name;
    }
    if (environmentRequest.description !== undefined) {
        environment.description = environmentRequest.description;
    }
    if (environmentRequest.address !== undefined) {
        environment.address = environmentRequest.address;
    }
    if (environmentRequest.sourceType !== undefined) {
        environment.sourceType = environmentRequest.sourceType;
    }
    if (environmentRequest.properties !== undefined) {
        environment.properties = environmentRequest.properties;
    }
    if (environmentRequest.labels !== undefined) {
        environment.labels = LabelUtils.fromEntityLabels(environmentRequest.labels);
    }

    await writeMainService(serviceFileUri, service);

    return {
        ...environment,
        labels: LabelUtils.toEntityLabels(environment.labels)
    } as Environment;
}

export async function createEnvironment(serviceFileUri: Uri, serviceId: string, environmentRequest: EnvironmentRequest): Promise<Environment> {
    const service: any = await getMainService(serviceFileUri);
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch`);
        throw Error("ServiceId mismatch");
    }

    if (!service.content) {
        service.content = {};
    }
    if (!service.content.environments) {
        service.content.environments = [];
    }

    const environmentId = crypto.randomUUID();
    const environment = {
        id: environmentId,
        name: environmentRequest.name,
        description: environmentRequest.description || "",
        address: environmentRequest.address,
        sourceType: environmentRequest.sourceType || "MANUAL",
        properties: environmentRequest.properties || {},
        labels: LabelUtils.fromEntityLabels(environmentRequest.labels || [])
    };

    service.content.environments.push(environment);
    await writeMainService(serviceFileUri, service);

    return {
        ...environment,
        labels: LabelUtils.toEntityLabels(environment.labels)
    };
}

export async function deleteEnvironment(serviceFileUri: Uri, serviceId: string, environmentId: string): Promise<void> {
    const service: any = await getMainService(serviceFileUri);
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch`);
        throw Error("ServiceId mismatch");
    }

    if (!service.content) {
        service.content = {};
    }
    if (!service.content.environments) {
        service.content.environments = [];
    }

    const environmentIndex = service.content.environments.findIndex((env: any) => env.id === environmentId);
    if (environmentIndex === -1) {
        console.error(`EnvironmentId not found`);
        throw Error("EnvironmentId not found");
    }

    service.content.environments.splice(environmentIndex, 1);

    if (service.content.activeEnvironmentId === environmentId) {
        service.content.activeEnvironmentId = "";
    }

    await writeMainService(serviceFileUri, service);
}

async function writeMainService(serviceFileUri: Uri, service: any) {
    await fileApi.writeMainService(serviceFileUri, service);
}

export async function updateApiSpecificationGroup(serviceFileUri: Uri, groupId: string, groupRequest: Partial<SpecificationGroup>): Promise<SpecificationGroup> {
    try {
        const { groupFile, groupInfo } = await getSpecificationFilesByGroup(serviceFileUri, groupId);

        if (groupRequest.name !== undefined) {
            groupInfo.name = groupRequest.name;
        }
        if (groupRequest.description !== undefined) {
            groupInfo.description = groupRequest.description;
        }
        if ((groupRequest as any).labels !== undefined) {
            if (!groupInfo.content) {
                groupInfo.content = {};
            }
            groupInfo.content.labels = LabelUtils.fromEntityLabels((groupRequest as any).labels);
        }

        if (!groupInfo.content) {
            groupInfo.content = {};
        }

        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
        const groupFileUri = vscode.Uri.joinPath(serviceFolderUri, groupFile);
        const yamlContent = yaml.stringify(groupInfo);
        const bytes = new TextEncoder().encode(yamlContent);
        await fileApi.writeFile(groupFileUri, bytes);

        return {
            ...groupInfo,
            labels: LabelUtils.toEntityLabels(groupInfo.content?.labels || [])
        } as SpecificationGroup;

    } catch (error) {
        console.error('updateApiSpecificationGroup: Error:', error);
        vscode.window.showErrorMessage(`Failed to update specification group: ${error}`);
        throw error;
    }
}

export async function updateSpecificationModel(serviceFileUri: Uri, modelId: string, modelRequest: Partial<Specification>): Promise<Specification> {
    try {
        const { specificationFile, specificationInfo } = await findSpecificationFileById(serviceFileUri, modelId);

        if (modelRequest.name !== undefined) {
            specificationInfo.name = modelRequest.name;
        }
        if (modelRequest.description !== undefined) {
            specificationInfo.description = modelRequest.description;
        }
        if ((modelRequest as any).labels !== undefined) {
            if (!specificationInfo.content) {
                specificationInfo.content = {};
            }
            specificationInfo.content.labels = LabelUtils.fromEntityLabels((modelRequest as any).labels);
        }
        if (modelRequest.version !== undefined) {
            if (!specificationInfo.content) {
                specificationInfo.content = {};
            }
            specificationInfo.content.version = modelRequest.version;
        }
        if (modelRequest.format !== undefined) {
            if (!specificationInfo.content) {
                specificationInfo.content = {};
            }
            specificationInfo.content.format = modelRequest.format;
        }
        if (modelRequest.content !== undefined) {
            if (!specificationInfo.content) {
                specificationInfo.content = {};
            }
            specificationInfo.content.content = modelRequest.content;
        }
        if (modelRequest.deprecated !== undefined) {
            if (!specificationInfo.content) {
                specificationInfo.content = {};
            }
            specificationInfo.content.deprecated = modelRequest.deprecated;
        }

        if (!specificationInfo.content) {
            specificationInfo.content = {};
        }

        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
        const specificationFileUri = vscode.Uri.joinPath(serviceFolderUri, specificationFile);
        const yamlContent = yaml.stringify(specificationInfo);
        const bytes = new TextEncoder().encode(yamlContent);
        await fileApi.writeFile(specificationFileUri, bytes);

        return {
            ...specificationInfo,
            labels: LabelUtils.toEntityLabels(specificationInfo.content?.labels || [])
        } as Specification;

    } catch (error) {
        console.error('updateSpecificationModel: Error:', error);
        vscode.window.showErrorMessage(`Failed to update specification: ${error}`);
        throw error;
    }
}

export async function deprecateModel(serviceFileUri: Uri, modelId: string): Promise<Specification> {
    try {
        const { specificationFile, specificationInfo } = await findSpecificationFileById(serviceFileUri, modelId);

        if (!specificationInfo.content) {
            specificationInfo.content = {};
        }
        specificationInfo.content.deprecated = true;

        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
        const specificationFileUri = vscode.Uri.joinPath(serviceFolderUri, specificationFile);
        const yamlContent = yaml.stringify(specificationInfo);
        const bytes = new TextEncoder().encode(yamlContent);
        await fileApi.writeFile(specificationFileUri, bytes);

        vscode.window.showInformationMessage(`Specification "${specificationInfo.name}" has been deprecated successfully!`);

        return specificationInfo as Specification;

    } catch (error) {
        console.error('[deprecateModel] Error:', error);
        vscode.window.showErrorMessage(`Failed to deprecate specification: ${error}`);
        throw error;
    }
}

async function getSpecificationFilesByGroup(serviceFileUri: Uri, groupId: string): Promise<{ groupFile: string, groupInfo: any, specificationFiles: string[] }> {
    const service = await getMainService(serviceFileUri);
    if (!service) {
        throw new Error('Service not found');
    }

    const groupFiles = await fileApi.getSpecificationGroupFiles(serviceFileUri);

    let groupFileToDelete: string | null = null;
    let groupInfo: any = null;

    for (const fileName of groupFiles) {
        try {
            const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
            const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
            const parsed = await ContentParser.parseContentFromFile(fileUri);

            if (parsed.id === groupId) {
                groupFileToDelete = fileName;
                groupInfo = parsed;
                break;
            }
        } catch (error) {
            console.error(`Error reading specification group file ${fileName}:`, error);
        }
    }

    if (!groupFileToDelete || !groupInfo) {
        throw new Error(`Specification group with id ${groupId} not found`);
    }

    const specificationFiles = await fileApi.getSpecificationFiles(serviceFileUri);
    let groupSpecificationFiles: string[] = [];

    for (const fileName of specificationFiles) {
        try {
            const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
            const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
            const parsed = await ContentParser.parseContentFromFile(fileUri);

            if (parsed?.content?.parentId === groupId) {
                groupSpecificationFiles.push(fileName);
            }
        } catch (error) {
            console.error(`Error reading specification file ${fileName}:`, error);
        }
    }

    return {
        groupFile: groupFileToDelete,
        groupInfo,
        specificationFiles: groupSpecificationFiles
    };
}

async function findSpecificationFileById(serviceFileUri: Uri, modelId: string): Promise<{ specificationFile: string, specificationInfo: any }> {
    const specificationFiles = await fileApi.getSpecificationFiles(serviceFileUri);

    let specificationFileToDelete: string | null = null;
    let specificationInfo: any = null;

    for (const fileName of specificationFiles) {
        try {
            const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
            const fileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
            const parsed = await ContentParser.parseContentFromFile(fileUri);

            if (parsed.id === modelId) {
                specificationFileToDelete = fileName;
                specificationInfo = parsed;
                break;
            }
        } catch (error) {
            console.error(`Error reading specification file ${fileName}:`, error);
        }
    }

    if (!specificationFileToDelete || !specificationInfo) {
        throw new Error(`Specification with id ${modelId} not found`);
    }

    return {
        specificationFile: specificationFileToDelete,
        specificationInfo
    };
}

async function deleteSourceFilesFromSpecificationSources(serviceFileUri: Uri, specificationInfo: any): Promise<void> {
    if (!specificationInfo.content?.specificationSources || specificationInfo.content.specificationSources.length === 0) {
        return;
    }

    const foldersToCheck: string[] = [];

    for (const source of specificationInfo.content.specificationSources) {
        try {
            const filePath = source.fileName;
            if (filePath) {
                const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
                const sourceFileUri = vscode.Uri.joinPath(serviceFolderUri, 'resources', filePath);

                try {
                    await fileApi.deleteFile(sourceFileUri);
                    const folderPath = filePath.split('/')[0];
                    if (folderPath && !foldersToCheck.includes(folderPath)) {
                        foldersToCheck.push(folderPath);
                    }
                } catch (error) {
                    if (!(error instanceof Error && error.message.includes('not empty'))) {
                        console.error(`Error deleting source file ${source.name}:`, error);
                    }
                }
            }
        } catch (error) {
            console.error(`Error processing source file ${source.name}:`, error);
        }
    }

    for (const folderName of foldersToCheck) {
        try {
            const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
            const folderUri = vscode.Uri.joinPath(serviceFolderUri, 'resources', folderName);
            await fileApi.deleteFile(folderUri);
        } catch (error) {
            if (!(error instanceof Error && error.message.includes('not empty'))) {
                console.error(`Error checking folder ${folderName}:`, error);
            }
        }
    }
}

export async function deleteSpecificationGroup(serviceFileUri: Uri, groupId: string): Promise<void> {
    try {
        const { groupFile, groupInfo, specificationFiles } = await getSpecificationFilesByGroup(serviceFileUri, groupId);

        for (const specFileName of specificationFiles) {
            try {
                const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
                const fileUri = vscode.Uri.joinPath(serviceFolderUri, specFileName);
                const specInfo = await ContentParser.parseContentFromFile(fileUri);

                await deleteSourceFilesFromSpecificationSources(serviceFileUri, specInfo);
            } catch (error) {
                console.error(`Error processing specification file ${specFileName}:`, error);
            }
        }

        for (const specFileName of specificationFiles) {
            try {
                const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
                const fileUri = vscode.Uri.joinPath(serviceFolderUri, specFileName);
                await fileApi.deleteFile(fileUri);
            } catch (error) {
                console.error(`Error deleting specification file ${specFileName}:`, error);
            }
        }

        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
        const groupFileUri = vscode.Uri.joinPath(serviceFolderUri, groupFile);
        await fileApi.deleteFile(groupFileUri);

        vscode.window.showInformationMessage(`Specification group "${groupInfo.name}" has been deleted successfully!`);

    } catch (error) {
        console.error('deleteSpecificationGroup: Error:', error);
        vscode.window.showErrorMessage(`Failed to delete specification group: ${error}`);
        throw error;
    }
}

export async function deleteSpecificationModel(serviceFileUri: Uri, modelId: string): Promise<void> {
    try {
        const { specificationFile, specificationInfo } = await findSpecificationFileById(serviceFileUri, modelId);

        await deleteSourceFilesFromSpecificationSources(serviceFileUri, specificationInfo);

        const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
        const specificationFileUri = vscode.Uri.joinPath(serviceFolderUri, specificationFile);
        await fileApi.deleteFile(specificationFileUri);

        vscode.window.showInformationMessage(`Specification "${specificationInfo.name}" has been deleted successfully!`);

    } catch (error) {
        console.error('[deleteSpecificationModel] Error:', error);
        vscode.window.showErrorMessage(`Failed to delete specification: ${error}`);
        throw error;
    }
}

export async function createEmptyService() {
    try {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders) {
            vscode.window.showErrorMessage('Open a workspace folder first');
            return;
        }

        const serviceName = await vscode.window.showInputBox({
            prompt: 'Enter new service name',
            placeHolder: 'My Service',
            validateInput: (value) => {
                if (!value || value.trim().length === 0) {
                    return 'Service name cannot be empty';
                }
                if (value.trim().length > 128) {
                    return 'Service name cannot be longer than 128 characters';
                }
                return null;
            }
        });

        if (!serviceName) {
            return;
        }

        const serviceType = await vscode.window.showQuickPick([
            { label: 'External', value: IntegrationSystemType.EXTERNAL, description: 'External service' },
            { label: 'Internal', value: IntegrationSystemType.INTERNAL, description: 'Internal service' },
            { label: 'Implemented', value: IntegrationSystemType.IMPLEMENTED, description: 'Implemented service' }
        ], {
            placeHolder: 'Select service type',
            canPickMany: false
        });

        if (!serviceType) {
            return;
        }

        const serviceDescription = await vscode.window.showInputBox({
            prompt: 'Enter service description (optional)',
            placeHolder: 'Description of the service',
            validateInput: (value) => {
                if (value && value.trim().length > 512) {
                    return 'Description cannot be longer than 512 characters';
                }
                return null;
            }
        });

        const serviceRequest: SystemRequest = {
            name: serviceName.trim(),
            description: serviceDescription?.trim() || "",
            type: serviceType.value,
            protocol: "",
            extendedProtocol: "",
            labels: []
        };

        const service = await createService({} as ExtensionContext, workspaceFolders[0].uri, serviceRequest);

        refreshQipExplorer();

        vscode.window.showInformationMessage(`Service "${serviceName}" created successfully with type ${serviceType.label} in folder ${service.id}`);
        return service;
    } catch (err) {
        vscode.window.showErrorMessage(`Failed to create service: ${err}`);
        throw err;
    }
}
