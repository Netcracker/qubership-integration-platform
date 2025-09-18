import {
    IntegrationSystem,
    Environment,
    SpecificationGroup,
    Specification,
    SystemRequest,
    EnvironmentRequest,
    IntegrationSystemType
} from "@netcracker/qip-ui";
import * as yaml from 'yaml';
import {getService, getMainService} from "./serviceApiRead";
import {EMPTY_USER} from "./chainApiUtils";
import vscode, {ExtensionContext, Uri} from "vscode";
import {fileApi} from "./file/fileApiProvider";
import { refreshQipExplorer } from "../extension";

export async function updateService(mainFolderUri: Uri, serviceId: string, serviceRequest: Partial<IntegrationSystem>): Promise<IntegrationSystem> {
    const service: any = await getMainService(mainFolderUri);

    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch: expected ${serviceId}, got ${service.id}`);
        throw Error("ServiceId mismatch");
    }

    if (serviceRequest.name !== undefined) {
        service.name = serviceRequest.name;
    }
    if (serviceRequest.description !== undefined) {
        service.content.description = serviceRequest.description;
    }
    if (serviceRequest.labels !== undefined) {
        service.content.labels = serviceRequest.labels;
    }
    if (serviceRequest.integrationSystemType !== undefined) {
        service.content.integrationSystemType = serviceRequest.integrationSystemType;
    }
    if (serviceRequest.protocol !== undefined) {
        service.content.protocol = serviceRequest.protocol;
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

    service.content.modifiedWhen = Date.now();
    service.content.modifiedBy = {...EMPTY_USER};

    await writeMainService(mainFolderUri, service);
    const updatedService = await getService(mainFolderUri, serviceId);

    return updatedService;
}

export async function createService(context: ExtensionContext, mainFolderUri: Uri, serviceRequest: SystemRequest): Promise<IntegrationSystem> {
    try {
        const serviceId = crypto.randomUUID();

        const service = {
            $schema: 'http://qubership.org/schemas/product/qip/service',
            id: serviceId,
            name: serviceRequest.name,
            content: {
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: {...EMPTY_USER},
                modifiedBy: {...EMPTY_USER},
                description: serviceRequest.description || "",
                activeEnvironmentId: "",
                integrationSystemType: serviceRequest.type || "EXTERNAL",
                protocol: serviceRequest.protocol || "",
                extendedProtocol: serviceRequest.extendedProtocol || "",
                specification: serviceRequest.specification || "",
                environments: [],
                labels: serviceRequest.labels || [],
                migrations: []
            }
        };

        const serviceFolderUri = vscode.Uri.joinPath(mainFolderUri, serviceId);
        const serviceFileUri = vscode.Uri.joinPath(serviceFolderUri, `${serviceId}.service.qip.yaml`);
        await fileApi.writeServiceFile(serviceFileUri, service);

        const result: IntegrationSystem = {
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
            environments: service.content.environments || [],
            labels: service.content.labels || []
        };

        return result;
    } catch (error) {
        console.error('createService: Error creating service:', error);
        throw new Error(`Failed to create service: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
}

export async function updateEnvironment(mainFolderUri: Uri, serviceId: string, environmentId: string, environmentRequest: EnvironmentRequest): Promise<Environment> {
    const service: any = await getMainService(mainFolderUri);
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch`);
        throw Error("ServiceId mismatch");
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
        environment.labels = environmentRequest.labels;
    }

    environment.modifiedWhen = Date.now();
    environment.modifiedBy = {...EMPTY_USER};

    await writeMainService(mainFolderUri, service);

    return environment as Environment;
}

export async function createEnvironment(mainFolderUri: Uri, serviceId: string, environmentRequest: EnvironmentRequest): Promise<Environment> {
    const service: any = await getMainService(mainFolderUri);
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch`);
        throw Error("ServiceId mismatch");
    }

    const environmentId = crypto.randomUUID();
    const environment = {
        id: environmentId,
        name: environmentRequest.name,
        description: environmentRequest.description || "",
        createdWhen: Date.now(),
        modifiedWhen: Date.now(),
        createdBy: {...EMPTY_USER},
        modifiedBy: {...EMPTY_USER},
        address: environmentRequest.address,
        sourceType: environmentRequest.sourceType || "MANUAL",
        properties: environmentRequest.properties || {},
        labels: environmentRequest.labels || []
    };

    service.content.environments.push(environment);
    await writeMainService(mainFolderUri, service);

    return environment;
}

export async function deleteEnvironment(mainFolderUri: Uri, serviceId: string, environmentId: string): Promise<void> {
    const service: any = await getMainService(mainFolderUri);
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch`);
        throw Error("ServiceId mismatch");
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

    await writeMainService(mainFolderUri, service);
}

async function writeMainService(mainFolderUri: Uri, service: any) {
    await fileApi.writeMainService(mainFolderUri, service);
}

export async function updateApiSpecificationGroup(mainFolderUri: Uri, groupId: string, groupRequest: Partial<SpecificationGroup>): Promise<SpecificationGroup> {
    try {
        const { groupFile, groupInfo } = await findSpecificationFilesByGroup(mainFolderUri, groupId);

        if (groupRequest.name !== undefined) {
            groupInfo.name = groupRequest.name;
        }
        if (groupRequest.description !== undefined) {
            groupInfo.description = groupRequest.description;
        }
        if ((groupRequest as any).labels !== undefined) {
            groupInfo.labels = (groupRequest as any).labels;
        }

        groupInfo.content.modifiedWhen = Date.now();
        groupInfo.content.modifiedBy = EMPTY_USER;

        const groupFileUri = vscode.Uri.joinPath(mainFolderUri, groupFile);
        const yamlContent = yaml.stringify(groupInfo);
        const bytes = new TextEncoder().encode(yamlContent);
        await fileApi.writeFile(groupFileUri, bytes);

        return groupInfo as SpecificationGroup;

    } catch (error) {
        console.error('updateApiSpecificationGroup: Error:', error);
        vscode.window.showErrorMessage(`Failed to update specification group: ${error}`);
        throw error;
    }
}

export async function updateSpecificationModel(mainFolderUri: Uri, modelId: string, modelRequest: Partial<Specification>): Promise<Specification> {
    try {
        const { specificationFile, specificationInfo } = await findSpecificationFileById(mainFolderUri, modelId);

        if (modelRequest.name !== undefined) {
            specificationInfo.name = modelRequest.name;
        }
        if (modelRequest.description !== undefined) {
            specificationInfo.description = modelRequest.description;
        }
        if ((modelRequest as any).labels !== undefined) {
            specificationInfo.labels = (modelRequest as any).labels;
        }
        if (modelRequest.version !== undefined) {
            specificationInfo.content.version = modelRequest.version;
        }
        if (modelRequest.format !== undefined) {
            specificationInfo.content.format = modelRequest.format;
        }
        if (modelRequest.content !== undefined) {
            specificationInfo.content.content = modelRequest.content;
        }
        if (modelRequest.deprecated !== undefined) {
            specificationInfo.content.deprecated = modelRequest.deprecated;
        }

        specificationInfo.content.modifiedWhen = Date.now();
        specificationInfo.content.modifiedBy = EMPTY_USER;

        const specificationFileUri = vscode.Uri.joinPath(mainFolderUri, specificationFile);
        const yamlContent = yaml.stringify(specificationInfo);
        const bytes = new TextEncoder().encode(yamlContent);
        await fileApi.writeFile(specificationFileUri, bytes);

        return specificationInfo as Specification;

    } catch (error) {
        console.error('updateSpecificationModel: Error:', error);
        vscode.window.showErrorMessage(`Failed to update specification: ${error}`);
        throw error;
    }
}

export async function deprecateModel(mainFolderUri: Uri, modelId: string): Promise<Specification> {
    try {
        const { specificationFile, specificationInfo } = await findSpecificationFileById(mainFolderUri, modelId);

        if (!specificationInfo.content) {
            specificationInfo.content = {};
        }
        specificationInfo.content.deprecated = true;
        specificationInfo.content.modifiedWhen = Date.now();
        specificationInfo.content.modifiedBy = EMPTY_USER;

        const specificationFileUri = vscode.Uri.joinPath(mainFolderUri, specificationFile);
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

async function findSpecificationFilesByGroup(mainFolderUri: Uri, groupId: string): Promise<{ groupFile: string, groupInfo: any, specificationFiles: string[] }> {
    const service = await getMainService(mainFolderUri);
    if (!service) {
        throw new Error('Service not found');
    }

    const groupFiles = await fileApi.findSpecificationGroupFiles(mainFolderUri);

    let groupFileToDelete: string | null = null;
    let groupInfo: any = null;

    for (const fileName of groupFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(mainFolderUri, fileName);
            const fileContent = await fileApi.readFileContent(fileUri);
            const text = new TextDecoder().decode(fileContent);
            const parsed = yaml.parse(text);

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

    const specificationFiles = await fileApi.findSpecificationFiles(mainFolderUri);

    const groupName = groupInfo.name;
    const systemId = groupInfo.content.parentId;

    const groupSpecificationFiles = specificationFiles.filter(fileName =>
        fileName.startsWith(`${systemId}-${groupName}-`)
    );

    return {
        groupFile: groupFileToDelete,
        groupInfo,
        specificationFiles: groupSpecificationFiles
    };
}

async function findSpecificationFileById(mainFolderUri: Uri, modelId: string): Promise<{ specificationFile: string, specificationInfo: any }> {
    const specificationFiles = await fileApi.findSpecificationFiles(mainFolderUri);

    let specificationFileToDelete: string | null = null;
    let specificationInfo: any = null;

    for (const fileName of specificationFiles) {
        try {
            const fileUri = vscode.Uri.joinPath(mainFolderUri, fileName);
            const fileContent = await fileApi.readFileContent(fileUri);
            const text = new TextDecoder().decode(fileContent);
            const parsed = yaml.parse(text);

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

async function deleteSourceFilesFromSpecificationSources(mainFolderUri: Uri, specificationInfo: any): Promise<void> {
    if (!specificationInfo.specificationSources || specificationInfo.specificationSources.length === 0) {
        return;
    }

    const foldersToCheck: string[] = [];

    for (const source of specificationInfo.specificationSources) {
        try {
            const filePath = source.fileName;
            if (filePath && filePath.startsWith('resources/')) {
                const relativePath = filePath.replace('resources/', '');
                const sourceFileUri = vscode.Uri.joinPath(mainFolderUri, 'resources', relativePath);

                try {
                    await fileApi.deleteFile(sourceFileUri);
                    const folderPath = relativePath.split('/')[0];
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
            const folderUri = vscode.Uri.joinPath(mainFolderUri, 'resources', folderName);
            await fileApi.deleteFile(folderUri);
        } catch (error) {
            if (!(error instanceof Error && error.message.includes('not empty'))) {
                console.error(`Error checking folder ${folderName}:`, error);
            }
        }
    }
}

export async function deleteSpecificationGroup(mainFolderUri: Uri, groupId: string): Promise<void> {
    try {
        const { groupFile, groupInfo, specificationFiles } = await findSpecificationFilesByGroup(mainFolderUri, groupId);

        for (const specFileName of specificationFiles) {
            try {
                const fileUri = vscode.Uri.joinPath(mainFolderUri, specFileName);
                const fileContent = await fileApi.readFileContent(fileUri);
                const text = new TextDecoder().decode(fileContent);
                const specInfo = yaml.parse(text);

                await deleteSourceFilesFromSpecificationSources(mainFolderUri, specInfo);
            } catch (error) {
                console.error(`Error processing specification file ${specFileName}:`, error);
            }
        }

        for (const specFileName of specificationFiles) {
            try {
                const fileUri = vscode.Uri.joinPath(mainFolderUri, specFileName);
                await fileApi.deleteFile(fileUri);
            } catch (error) {
                console.error(`Error deleting specification file ${specFileName}:`, error);
            }
        }

        const groupFileUri = vscode.Uri.joinPath(mainFolderUri, groupFile);
        await fileApi.deleteFile(groupFileUri);

        vscode.window.showInformationMessage(`Specification group "${groupInfo.name}" has been deleted successfully!`);

    } catch (error) {
        console.error('deleteSpecificationGroup: Error:', error);
        vscode.window.showErrorMessage(`Failed to delete specification group: ${error}`);
        throw error;
    }
}

export async function deleteSpecificationModel(mainFolderUri: Uri, modelId: string): Promise<void> {
    try {
        const { specificationFile, specificationInfo } = await findSpecificationFileById(mainFolderUri, modelId);

        await deleteSourceFilesFromSpecificationSources(mainFolderUri, specificationInfo);

        const specificationFileUri = vscode.Uri.joinPath(mainFolderUri, specificationFile);
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
