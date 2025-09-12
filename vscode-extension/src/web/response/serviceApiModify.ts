import {
    IntegrationSystem,
    Environment,
    SpecificationGroup,
    Specification,
    SystemRequest,
    EnvironmentRequest,
    IntegrationSystemType
} from "./apiTypes";
import * as yaml from 'yaml';
import {getService, getMainService, getMainServiceFileUri} from "./serviceApiRead";
import {EMPTY_USER} from "./chainApiUtils";
import vscode, {ExtensionContext, Uri} from "vscode";
import {fileApi} from "./file/fileApiProvider";
import { refreshQipExplorer } from "../extension";

export async function updateService(mainFolderUri: Uri, serviceId: string, serviceRequest: Partial<IntegrationSystem>): Promise<IntegrationSystem> {
    console.log('updateService: Starting update for serviceId:', serviceId);
    console.log('updateService: mainFolderUri:', mainFolderUri);
    console.log('updateService: serviceRequest:', serviceRequest);
    
    const service: any = await getMainService(mainFolderUri);
    console.log('updateService: Loaded service:', service);
    
    if (service.id !== serviceId) {
        console.error(`ServiceId mismatch: expected ${serviceId}, got ${service.id}`);
        throw Error("ServiceId mismatch");
    }

    console.log('updateService: Service ID matches, proceeding with update');

    if (serviceRequest.name !== undefined) {
        console.log('updateService: Updating name from', service.name, 'to', serviceRequest.name);
        service.name = serviceRequest.name;
    }
    if (serviceRequest.description !== undefined) {
        console.log('updateService: Updating description from', service.content.description, 'to', serviceRequest.description);
        service.content.description = serviceRequest.description;
    }
    if (serviceRequest.labels !== undefined) {
        console.log('updateService: Updating labels from', service.content.labels, 'to', serviceRequest.labels);
        service.content.labels = serviceRequest.labels;
    }
    if (serviceRequest.integrationSystemType !== undefined) {
        console.log('updateService: Updating integrationSystemType from', service.content.integrationSystemType, 'to', serviceRequest.integrationSystemType);
        service.content.integrationSystemType = serviceRequest.integrationSystemType;
    }
    if (serviceRequest.protocol !== undefined) {
        console.log('updateService: Updating protocol from', service.content.protocol, 'to', serviceRequest.protocol);
        service.content.protocol = serviceRequest.protocol;
    }
    if (serviceRequest.extendedProtocol !== undefined) {
        console.log('updateService: Updating extendedProtocol from', service.content.extendedProtocol, 'to', serviceRequest.extendedProtocol);
        service.content.extendedProtocol = serviceRequest.extendedProtocol;
    }
    if (serviceRequest.specification !== undefined) {
        console.log('updateService: Updating specification from', service.content.specification, 'to', serviceRequest.specification);
        service.content.specification = serviceRequest.specification;
    }
    if (serviceRequest.activeEnvironmentId !== undefined) {
        console.log('updateService: Updating activeEnvironmentId from', service.content.activeEnvironmentId, 'to', serviceRequest.activeEnvironmentId);
        service.content.activeEnvironmentId = serviceRequest.activeEnvironmentId;
    }

    const oldModifiedWhen = service.content.modifiedWhen;
    service.content.modifiedWhen = Date.now();
    service.content.modifiedBy = {...EMPTY_USER};
    console.log('updateService: Updated timestamps - modifiedWhen from', oldModifiedWhen, 'to', service.content.modifiedWhen);

    console.log('updateService: Service object before writing:', service);
    console.log('updateService: Calling writeMainService...');
    
    await writeMainService(mainFolderUri, service);
    console.log('updateService: writeMainService completed successfully');

    console.log('updateService: Calling getService to return updated service...');
    const updatedService = await getService(mainFolderUri, serviceId);
    console.log('updateService: Returning updated service:', updatedService);
    
    return updatedService;
}

export async function createService(context: ExtensionContext, mainFolderUri: Uri, serviceRequest: SystemRequest): Promise<IntegrationSystem> {
    console.log('createService: Starting service creation');
    console.log('createService: mainFolderUri:', mainFolderUri);
    console.log('createService: serviceRequest:', serviceRequest);
    
    try {
        const serviceId = crypto.randomUUID();
        console.log('createService: Generated serviceId:', serviceId);

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

        console.log('createService: Created service object:', service);

        // Create service folder with serviceId as name
        const serviceFolderUri = vscode.Uri.joinPath(mainFolderUri, serviceId);
        await vscode.workspace.fs.createDirectory(serviceFolderUri);
        console.log('createService: Created service folder:', serviceFolderUri);

        // Create service file inside the service folder
        const serviceFileUri = vscode.Uri.joinPath(serviceFolderUri, `${serviceId}.service.qip.yaml`);
        console.log('createService: Service file URI:', serviceFileUri);
        
        await writeServiceFile(serviceFileUri, service);
        console.log('createService: Service file written successfully');

        // Convert the created service object to IntegrationSystem format
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
        
        console.log('createService: Service created successfully:', result);
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

    return {
        id: environment.id,
        name: environment.name,
        description: environment.description || "",
        createdBy: environment.createdBy || {...EMPTY_USER},
        modifiedBy: environment.modifiedBy || {...EMPTY_USER},
        createdWhen: environment.createdWhen || 0,
        modifiedWhen: environment.modifiedWhen || 0,
        address: environment.address || "",
        sourceType: environment.sourceType || "MANUAL",
        properties: environment.properties || {},
        labels: environment.labels || []
    };
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
    console.log('writeMainService: Starting write for mainFolderUri:', mainFolderUri);
    console.log('writeMainService: Calling fileApi.writeMainService...');
    await fileApi.writeMainService(mainFolderUri, service);
    console.log('writeMainService: fileApi.writeMainService completed successfully');
}

async function writeServiceFile(fileUri: vscode.Uri, service: any) {
    console.log('writeServiceFile: Starting write for fileUri:', fileUri);
    console.log('writeServiceFile: Calling fileApi.writeServiceFile...');
    await fileApi.writeServiceFile(fileUri, service);
    console.log('writeServiceFile: fileApi.writeServiceFile completed successfully');
}

export async function updateApiSpecificationGroup(mainFolderUri: Uri, groupId: string, groupRequest: Partial<SpecificationGroup>): Promise<SpecificationGroup> {
    console.log('updateApiSpecificationGroup: Not implemented yet');
    throw new Error("updateApiSpecificationGroup not implemented yet");
}

export async function updateSpecificationModel(mainFolderUri: Uri, modelId: string, modelRequest: Partial<Specification>): Promise<Specification> {
    console.log('updateSpecificationModel: Not implemented yet');
    throw new Error("updateSpecificationModel not implemented yet");
}

export async function deprecateModel(mainFolderUri: Uri, modelId: string): Promise<Specification> {
    console.log('[deprecateModel] Starting deprecation for modelId:', modelId);
    
    try {
        const { specificationFile, specificationInfo } = await findSpecificationFileById(mainFolderUri, modelId);
        
        console.log(`[deprecateModel] Found specification file: ${specificationFile}`);
        console.log(`[deprecateModel] Current deprecated status: ${specificationInfo.content?.deprecated || false}`);

        // Update the deprecated flag
        if (!specificationInfo.content) {
            specificationInfo.content = {};
        }
        specificationInfo.content.deprecated = true;
        specificationInfo.content.modifiedWhen = Date.now();
        specificationInfo.content.modifiedBy = EMPTY_USER;

        // Write the updated specification back to file
        const specificationFileUri = vscode.Uri.joinPath(mainFolderUri, specificationFile);
        const yamlContent = yaml.stringify(specificationInfo);
        const bytes = new TextEncoder().encode(yamlContent);
        await fileApi.writeFile(specificationFileUri, bytes);
        
        console.log(`[deprecateModel] Successfully deprecated specification: ${specificationInfo.name}`);
        vscode.window.showInformationMessage(`Specification "${specificationInfo.name}" has been deprecated successfully!`);

        // Return the updated specification
        return {
            id: specificationInfo.id,
            name: specificationInfo.name,
            description: specificationInfo.description || '',
            version: specificationInfo.content.version || '1.0.0',
            format: specificationInfo.content.format || 'yaml',
            content: specificationInfo.content.content || '',
            deprecated: true,
            parentId: specificationInfo.content.parentId || '',
            operations: specificationInfo.content.operations || [],
            systemId: specificationInfo.content.systemId,
            specificationGroupId: specificationInfo.content.parentId,
            source: specificationInfo.content.source || 'IMPORTED',
            sourceFiles: specificationInfo.specificationSources?.map((s: any) => s.fileName) || [],
            protocol: specificationInfo.content.protocol,
            metadata: specificationInfo.content.metadata || {},
            createdWhen: specificationInfo.content.createdWhen || Date.now(),
            modifiedWhen: specificationInfo.content.modifiedWhen || Date.now(),
            createdBy: specificationInfo.content.createdBy || EMPTY_USER,
            modifiedBy: specificationInfo.content.modifiedBy || EMPTY_USER
        };
        
    } catch (error) {
        console.error('[deprecateModel] Error:', error);
        vscode.window.showErrorMessage(`Failed to deprecate specification: ${error}`);
        throw error;
    }
}

async function findSpecificationFilesByGroup(mainFolderUri: Uri, groupId: string): Promise<{ groupFile: string, groupInfo: any, specificationFiles: string[] }> {
    console.log(`[findSpecificationFilesByGroup] Searching for group: ${groupId}`);

    const service = await getMainService(mainFolderUri);
    if (!service) {
        throw new Error('Service not found');
    }

    const entries = await fileApi.readDirectory(mainFolderUri);
    const groupFiles = entries.filter(([name, type]: [string, vscode.FileType]) => type === 1)
        .filter(([name]: [string, vscode.FileType]) => name.endsWith('.specification-group.qip.yaml'))
        .map(([name]: [string, vscode.FileType]) => name);

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

    console.log(`[findSpecificationFilesByGroup] Found group file: ${groupFileToDelete}`);

    const specificationFiles = entries.filter(([name, type]: [string, vscode.FileType]) => type === 1)
        .filter(([name]: [string, vscode.FileType]) => name.endsWith('.specification.qip.yaml'))
        .map(([name]: [string, vscode.FileType]) => name);

    const groupName = groupInfo.name;
    const systemId = groupInfo.content.parentId;

    const groupSpecificationFiles = specificationFiles.filter(fileName => 
        fileName.startsWith(`${systemId}-${groupName}-`)
    );

    console.log(`[findSpecificationFilesByGroup] Found ${groupSpecificationFiles.length} specification files for group`);

    return {
        groupFile: groupFileToDelete,
        groupInfo,
        specificationFiles: groupSpecificationFiles
    };
}

async function findSpecificationFileById(mainFolderUri: Uri, modelId: string): Promise<{ specificationFile: string, specificationInfo: any }> {
    console.log(`[findSpecificationFileById] Searching for specification: ${modelId}`);
    
    const entries = await fileApi.readDirectory(mainFolderUri);
    const specificationFiles = entries.filter(([name, type]: [string, vscode.FileType]) => type === 1)
        .filter(([name]: [string, vscode.FileType]) => name.endsWith('.specification.qip.yaml'))
        .map(([name]: [string, vscode.FileType]) => name);

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

    console.log(`[findSpecificationFileById] Found specification file: ${specificationFileToDelete}`);

    return {
        specificationFile: specificationFileToDelete,
        specificationInfo
    };
}

async function deleteSourceFilesFromSpecificationSources(mainFolderUri: Uri, specificationInfo: any): Promise<void> {
    if (!specificationInfo.specificationSources || specificationInfo.specificationSources.length === 0) {
        console.log('[deleteSourceFilesFromSpecificationSources] No specificationSources found');
        return;
    }

    console.log(`[deleteSourceFilesFromSpecificationSources] Found ${specificationInfo.specificationSources.length} source files to delete`);
    console.log('[deleteSourceFilesFromSpecificationSources] specificationSources:', JSON.stringify(specificationInfo.specificationSources, null, 2));

    const filesToDelete: string[] = [];
    const foldersToCheck: string[] = [];

    for (const source of specificationInfo.specificationSources) {
        try {
            console.log(`[deleteSourceFilesFromSpecificationSources] Processing source: ${source.name}, fileName: ${source.fileName}`);
            
            const filePath = source.fileName;
            if (filePath && filePath.startsWith('resources/')) {
                const relativePath = filePath.replace('resources/', '');
                const sourceFileUri = vscode.Uri.joinPath(mainFolderUri, 'resources', relativePath);
                
                console.log(`[deleteSourceFilesFromSpecificationSources] Full file path: ${sourceFileUri.fsPath}`);

                try {
                    const fileStat = await fileApi.getFileStat(sourceFileUri);
                    console.log(`[deleteSourceFilesFromSpecificationSources] File stat for ${source.name}:`, fileStat);
                    
                    if (fileStat.type === vscode.FileType.File) {
                        filesToDelete.push(sourceFileUri.fsPath);
                        console.log(`[deleteSourceFilesFromSpecificationSources] Added file for deletion: ${source.name}`);
                    } else {
                        console.log(`[deleteSourceFilesFromSpecificationSources] File ${source.name} is not a regular file, type:`, fileStat.type);
                    }
                } catch (error) {
                    console.log(`[deleteSourceFilesFromSpecificationSources] Source file ${source.name} not found:`, error);
                }

                const folderPath = relativePath.split('/')[0]; 
                if (folderPath && !foldersToCheck.includes(folderPath)) {
                    foldersToCheck.push(folderPath);
                }
            } else {
                console.log(`[deleteSourceFilesFromSpecificationSources] File path ${filePath} does not start with 'resources/'`);
            }
        } catch (error) {
            console.error(`[deleteSourceFilesFromSpecificationSources] Error processing source file ${source.name}:`, error);
        }
    }

    console.log(`[deleteSourceFilesFromSpecificationSources] Deleting ${filesToDelete.length} files...`);
    for (const filePath of filesToDelete) {
        try {
            const fileUri = vscode.Uri.file(filePath);
            await fileApi.deleteFile(fileUri);
            console.log(`[deleteSourceFilesFromSpecificationSources] Successfully deleted file: ${filePath}`);
        } catch (error) {
            console.error(`[deleteSourceFilesFromSpecificationSources] Error deleting file ${filePath}:`, error);
        }
    }

    console.log(`[deleteSourceFilesFromSpecificationSources] Checking ${foldersToCheck.length} folders for cleanup...`);
    for (const folderName of foldersToCheck) {
        try {
            const folderUri = vscode.Uri.joinPath(mainFolderUri, 'resources', folderName);
            const folderStat = await fileApi.getFileStat(folderUri);
            
            if (folderStat.type === vscode.FileType.Directory) {
                const folderEntries = await fileApi.readDirectory(folderUri);
                if (folderEntries.length === 0) {
                    await fileApi.deleteFile(folderUri);
                    console.log(`[deleteSourceFilesFromSpecificationSources] Deleted empty folder: ${folderName}`);
                } else {
                    console.log(`[deleteSourceFilesFromSpecificationSources] Folder ${folderName} not empty (${folderEntries.length} items), keeping it`);
                }
            }
        } catch (error) {
            console.log(`[deleteSourceFilesFromSpecificationSources] Error checking folder ${folderName}:`, error);
        }
    }
}

export async function deleteSpecificationGroup(mainFolderUri: Uri, groupId: string): Promise<void> {
    console.log('[deleteSpecificationGroup] Starting deletion for groupId:', groupId);
    
    try {
        
        const { groupFile, groupInfo, specificationFiles } = await findSpecificationFilesByGroup(mainFolderUri, groupId);
        
        console.log(`[deleteSpecificationGroup] Will delete group file: ${groupFile}`);
        console.log(`[deleteSpecificationGroup] Will delete ${specificationFiles.length} specification files`);

        for (const specFileName of specificationFiles) {
            try {
                console.log(`[deleteSpecificationGroup] Processing specification file: ${specFileName}`);
                
                const fileUri = vscode.Uri.joinPath(mainFolderUri, specFileName);
                const fileContent = await fileApi.readFileContent(fileUri);
                const text = new TextDecoder().decode(fileContent);
                const specInfo = yaml.parse(text);

                await deleteSourceFilesFromSpecificationSources(mainFolderUri, specInfo);
                
                console.log(`[deleteSpecificationGroup] Processed specification: ${specFileName}`);
            } catch (error) {
                console.error(`[deleteSpecificationGroup] Error processing specification file ${specFileName}:`, error);
            }
        }

        console.log(`[deleteSpecificationGroup] Deleting ${specificationFiles.length} specification files...`);
        for (const specFileName of specificationFiles) {
            try {
                const fileUri = vscode.Uri.joinPath(mainFolderUri, specFileName);
                await fileApi.deleteFile(fileUri);
                console.log(`[deleteSpecificationGroup] Deleted specification file: ${specFileName}`);
            } catch (error) {
                console.error(`[deleteSpecificationGroup] Error deleting specification file ${specFileName}:`, error);
            }
        }

        const groupFileUri = vscode.Uri.joinPath(mainFolderUri, groupFile);
        await fileApi.deleteFile(groupFileUri);
        console.log(`[deleteSpecificationGroup] Deleted group file: ${groupFile}`);

        vscode.window.showInformationMessage(`Specification group "${groupInfo.name}" has been deleted successfully!`);
        
    } catch (error) {
        console.error('deleteSpecificationGroup: Error:', error);
        vscode.window.showErrorMessage(`Failed to delete specification group: ${error}`);
        throw error;
    }
}

export async function deleteSpecificationModel(mainFolderUri: Uri, modelId: string): Promise<void> {
    console.log('[deleteSpecificationModel] Starting deletion for modelId:', modelId);
    
    try {
        
        const { specificationFile, specificationInfo } = await findSpecificationFileById(mainFolderUri, modelId);
        
        console.log(`[deleteSpecificationModel] Will delete specification file: ${specificationFile}`);

        await deleteSourceFilesFromSpecificationSources(mainFolderUri, specificationInfo);

        const specificationFileUri = vscode.Uri.joinPath(mainFolderUri, specificationFile);
        await fileApi.deleteFile(specificationFileUri);
        console.log(`[deleteSpecificationModel] Deleted specification file: ${specificationFile}`);

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
        
        // Refresh QIP Explorer to show the new service
        try {
            refreshQipExplorer();
        } catch (error) {
            console.log('Could not refresh QIP Explorer:', error);
        }
        
        vscode.window.showInformationMessage(`Service "${serviceName}" created successfully with type ${serviceType.label} in folder ${service.id}`);
        return service;
    } catch (err) {
        vscode.window.showErrorMessage(`Failed to create service: ${err}`);
        throw err;
    }
}
