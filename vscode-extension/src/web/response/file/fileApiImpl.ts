import {FileApi} from './fileApi';
import {ExtensionContext, Uri} from 'vscode';
import * as yaml from 'yaml';
import {LibraryData} from "@netcracker/qip-ui";
import {EMPTY_USER} from "../chainApiUtils";
import {QipFileType} from "../serviceApiUtils";

const vscode = require('vscode');
const RESOURCES_FOLDER = 'resources';

export class VSCodeFileApi implements FileApi {
    context: ExtensionContext;

    constructor(context: ExtensionContext) {
        this.context = context;
    }

    private async getMainChainFileUri(mainFolderUri: Uri): Promise<Uri> {
        if (mainFolderUri) {
            let entries = await readDirectory(mainFolderUri);

            const files = entries
                .filter(([, type]: [string, number]) => type === 1)
                .filter(([name]: [string, number]) => name.endsWith('.chain.qip.yaml'))
                .map(([name]: [string, number]) => name);
            if (files.length !== 1) {
                console.error(`Single *.chain.qip.yaml file not found in the current directory`);
                vscode.window.showWarningMessage("*.chain.qip.yaml file not found in the current directory");
                throw Error("Single *.chain.qip.yaml file not found in the current directory");
            }
            return vscode.Uri.joinPath(mainFolderUri, files[0]);
        }
        throw Error('No main chain file');
    }

    async getMainChain(parameters: any): Promise<any> {
        const mainFolderUri = parameters as Uri;
        const fileUri = await this.getMainChainFileUri(mainFolderUri);
        try {
            const fileContent = await this.readFileContent(fileUri);
            const text = new TextDecoder('utf-8').decode(fileContent);
            const parsed = yaml.parse(text);

            if (parsed && parsed.name) {
                return parsed;
            }
            throw Error('Invalid chain file content');
        } catch (e) {
            console.error(`Chain file ${fileUri} can't be parsed from QIP Extension API`, e);
            throw e;
        }
    }

    async readFile(parameters: any, propertiesFilename: string): Promise<string> {
        const mainFolderUri = parameters as Uri;
        const fileUri = vscode.Uri.joinPath(mainFolderUri, propertiesFilename);
        let fileContent;
        try {
            fileContent = await this.readFileContent(fileUri);
        } catch (error) {
            if (!propertiesFilename.includes(RESOURCES_FOLDER)) {
                return await this.readFile(mainFolderUri, RESOURCES_FOLDER + '/' + propertiesFilename);
            }
            throw error;
        }
        const textFile = new TextDecoder('utf-8').decode(fileContent);
        return textFile;
    }

    async getLibrary(): Promise<LibraryData> {
        const fileUri = vscode.Uri.joinPath(this.context.extensionUri, 'media', 'library.json');
        const content = new TextDecoder('utf-8').decode(await this.readFileContent(fileUri));
        return JSON.parse(content);
    }

    async writePropertyFile(parameters: any, propertyFilename: string, propertyData: string): Promise<void> {
        const mainFolderUri = parameters as Uri;
        const bytes = new TextEncoder().encode(propertyData);
        try {
            await this.writeFile(vscode.Uri.joinPath(mainFolderUri, RESOURCES_FOLDER, propertyFilename), bytes);
            vscode.window.showInformationMessage('Property file has been updated!');
        } catch (err) {
            vscode.window.showErrorMessage('Failed to write file: ' + err);
            throw Error('Failed to write file: ' + err);
        }
    }

    async writeMainChain(parameters: any, chainData: any): Promise<void> {
        const mainFolderUri = parameters as Uri;
        const bytes = new TextEncoder().encode(yaml.stringify(chainData));
        try {
            await this.writeFile(await this.getMainChainFileUri(mainFolderUri), bytes);
            vscode.window.showInformationMessage('Chain has been updated!');
        } catch (err) {
            vscode.window.showErrorMessage('Failed to write file: ' + err);
            throw Error('Failed to write file: ' + err);
        }
    }

    async removeFile(mainFolderUri: Uri, propertyFilename: string): Promise<void> {
        const fileUri = vscode.Uri.joinPath(mainFolderUri, propertyFilename);
        try {
            await this.deleteFile(fileUri);
        } catch (error) {
            console.error("Error deleting property file", fileUri);
        }

        return;
    }


    // Service-related methods
    private async getMainServiceFileUri(mainFolderUri: Uri): Promise<Uri> {
        if (mainFolderUri) {
            let entries = await readDirectory(mainFolderUri);

            if (!entries || !Array.isArray(entries)) {
                console.error(`Failed to read directory contents`);
                throw Error("Failed to read directory contents");
            }

            const files = entries.filter(([, type]: [string, number]) => type === 1)
                .filter(([name]: [string, number]) => name.endsWith('.service.qip.yaml'))
                .map(([name]: [string, number]) => name);
            if (files.length !== 1) {
                console.error(`Single *.service.qip.yaml file not found in the current directory`);
                vscode.window.showWarningMessage("*.service.qip.yaml file not found in the current directory");
                throw Error("Single *.service.qip.yaml file not found in the current directory");
            }
            return vscode.Uri.joinPath(mainFolderUri, files[0]);
        }
        throw Error('No main service file');
    }

    async getMainService(parameters: any): Promise<any> {
        const mainFolderUri = parameters as Uri;
        const fileUri = await this.getMainServiceFileUri(mainFolderUri);
        try {
            const fileContent = await this.readFileContent(fileUri);
            const text = new TextDecoder('utf-8').decode(fileContent);
            const parsed = yaml.parse(text);

            if (parsed && parsed.name) {
                return parsed;
            }
            throw Error('Invalid service file content');
        } catch (e) {
            console.error(`Service file ${fileUri} can't be parsed from QIP Extension API`, e);
            throw e;
        }
    }

    async getService(parameters: any, serviceId: string): Promise<any> {
        const serviceFolderUri = parameters as Uri;
        const serviceFileUri = vscode.Uri.joinPath(serviceFolderUri, `${serviceId}.service.qip.yaml`);
        try {
            const fileContent = await this.readFileContent(serviceFileUri);
            const text = new TextDecoder('utf-8').decode(fileContent);
            const parsed = yaml.parse(text);

            if (parsed && parsed.id === serviceId) {
                return parsed;
            }
            throw Error('Invalid service file content or service ID mismatch');
        } catch (e) {
            console.error(`Service file ${serviceFileUri} can't be parsed from QIP Extension API`, e);
            throw e;
        }
    }

    async writeMainService(parameters: any, serviceData: any): Promise<void> {
        const mainFolderUri = parameters as Uri;
        const fileUri = await this.getMainServiceFileUri(mainFolderUri);
        await this.writeServiceFile(fileUri, serviceData);
    }

    async writeServiceFile(fileUri: Uri, serviceData: any): Promise<void> {
        const yamlString = yaml.stringify(serviceData);
        const bytes = new TextEncoder().encode(yamlString);

        try {
            await this.writeFile(fileUri, bytes);
            vscode.window.showInformationMessage('Service has been updated!');
        } catch (err) {
            console.error('writeServiceFile: Error writing file:', err);
            vscode.window.showErrorMessage('Failed to write file: ' + err);
            throw Error('Failed to write file: ' + err);
        }
    }

    async createServiceDirectory(parameters: any, serviceId: string): Promise<Uri> {
        const mainFolderUri = parameters as Uri;
        const serviceFolderUri = vscode.Uri.joinPath(mainFolderUri, serviceId);
        await createDirectory(serviceFolderUri);
        return serviceFolderUri;
    }


    // Directory operations
    async readDirectory(parameters: any): Promise<[string, number][]> {
        const mainFolderUri = parameters as Uri;
        return await readDirectory(mainFolderUri);
    }

    async createDirectory(parameters: any, dirName: string): Promise<void> {
        const mainFolderUri = parameters as Uri;
        const dirUri = vscode.Uri.joinPath(mainFolderUri, dirName);
        await createDirectory(dirUri);
    }

    async createDirectoryByUri(dirUri: Uri): Promise<void> {
        await createDirectory(dirUri);
    }


    // File operations
    async writeFile(fileUri: Uri, data: Uint8Array): Promise<void> {
        const parentDir = vscode.Uri.joinPath(fileUri, '..');
        await createDirectory(parentDir);
        await vscode.workspace.fs.writeFile(fileUri, data);
    }

    async readFileContent(fileUri: Uri): Promise<Uint8Array> {
        return await vscode.workspace.fs.readFile(fileUri);
    }

    async deleteFile(fileUri: Uri): Promise<void> {
        const fileStat = await vscode.workspace.fs.stat(fileUri);
        if (fileStat.type === vscode.FileType.Directory) {
            const entries = await vscode.workspace.fs.readDirectory(fileUri);
            if (entries.length === 0) {
                await vscode.workspace.fs.delete(fileUri);
            } else {
                throw new Error(`Directory ${fileUri.fsPath} is not empty`);
            }
        } else {
            await vscode.workspace.fs.delete(fileUri);
        }
    }


    async createEmptyChain(createInParentDir: boolean = false): Promise<{ folderUri: Uri, chainId: string } | null> {
        try {
            const workspaceFolders = vscode.workspace.workspaceFolders;
            if (!workspaceFolders) {
                vscode.window.showErrorMessage('Open a workspace folder first');
                return null;
            }
            const arg = await vscode.window.showInputBox({ prompt: 'Enter new chain name' });

            let folderUri = workspaceFolders[0].uri;
            const chainId = crypto.randomUUID();
            const chainName = arg || 'New Chain';
            if (createInParentDir) {
                folderUri = vscode.Uri.joinPath(folderUri, '..');
            }
            folderUri = vscode.Uri.joinPath(folderUri, chainId);

            await createDirectory(folderUri);

            const chainFileUri = vscode.Uri.joinPath(folderUri, `${chainId}.chain.qip.yaml`);
            const chain = {
                $schema: 'http://qubership.org/schemas/product/qip/chain',
                id: chainId,
                name: chainName,
                content: { }
            };
            const bytes = new TextEncoder().encode(yaml.stringify(chain));

            await this.writeFile(chainFileUri, bytes);
            vscode.window.showInformationMessage(`Chain "${chainName}" created with id ${chainId}`);
            return { folderUri, chainId };
        } catch (err) {
            vscode.window.showErrorMessage(`Failed: ${err}`);
            return null;
        }
    }

    async createEmptyService(): Promise<{ folderUri: Uri, serviceId: string } | null> {
        try {
            const workspaceFolders = vscode.workspace.workspaceFolders;
            if (!workspaceFolders) {
                vscode.window.showErrorMessage('Open a workspace folder first');
                return null;
            }

            const serviceName = await vscode.window.showInputBox({
                prompt: 'Enter new service name',
                placeHolder: 'My Service',
                validateInput: (value: string) => {
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
                return null;
            }

            const serviceType = await vscode.window.showQuickPick([
                { label: 'External', value: 'EXTERNAL', description: 'External service' },
                { label: 'Internal', value: 'INTERNAL', description: 'Internal service' },
                { label: 'Implemented', value: 'IMPLEMENTED', description: 'Implemented service' }
            ], {
                placeHolder: 'Select service type',
                canPickMany: false
            });

            if (!serviceType) {
                return null;
            }

            const serviceDescription = await vscode.window.showInputBox({
                prompt: 'Enter service description (optional)',
                placeHolder: 'Description of the service',
                validateInput: (value: string) => {
                    if (value && value.trim().length > 512) {
                        return 'Description cannot be longer than 512 characters';
                    }
                    return null;
                }
            });

            const serviceId = crypto.randomUUID();
            const service = {
                $schema: 'http://qubership.org/schemas/product/qip/service',
                id: serviceId,
                name: serviceName.trim(),
                content: {
                    createdWhen: Date.now(),
                    modifiedWhen: Date.now(),
                    createdBy: { ...EMPTY_USER },
                    modifiedBy: { ...EMPTY_USER },
                    description: serviceDescription?.trim() || "",
                    activeEnvironmentId: "",
                    integrationSystemType: serviceType.value,
                    protocol: "",
                    extendedProtocol: "",
                    specification: "",
                    environments: [],
                    labels: [],
                    migrations: []
                }
            };

            // Create service file (folder will be created automatically)
            const serviceFolderUri = vscode.Uri.joinPath(workspaceFolders[0].uri, serviceId);
            const serviceFileUri = vscode.Uri.joinPath(serviceFolderUri, `${serviceId}.service.qip.yaml`);
            await this.writeServiceFile(serviceFileUri, service);

            vscode.window.showInformationMessage(`Service "${serviceName}" created successfully with type ${serviceType.label} in folder ${serviceId}`);
            return { folderUri: serviceFolderUri, serviceId };
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to create service: ${err}`);
            return null;
        }
    }

    async getFileType(mainFolderUri: Uri): Promise<string> {
        try {
            const entries = await this.readDirectoryInternal(mainFolderUri);

            const hasChainFile = entries.some(([name]: [string, number]) =>
                name.endsWith('.chain.qip.yaml'));
            const hasServiceFile = entries.some(([name]: [string, number]) =>
                name.endsWith('.service.qip.yaml'));

            if (hasServiceFile) {
                return QipFileType.SERVICE;
            } else if (hasChainFile) {
                return QipFileType.CHAIN;
            } else {
                return QipFileType.UNKNOWN;
            }
        } catch (e) {
            return QipFileType.UNKNOWN;
        }
    }

    private async readDirectoryInternal(mainFolderUri: Uri): Promise<[string, number][]> {
        return await readDirectory(mainFolderUri);
    }

    async findSpecificationGroupFiles(mainFolderUri: Uri): Promise<string[]> {
        const entries = await this.readDirectoryInternal(mainFolderUri);
        return entries.filter(([name, type]: [string, number]) => type === 1)
            .filter(([name]: [string, number]) => name.endsWith('.specification-group.qip.yaml'))
            .map(([name]: [string, number]) => name);
    }

    async findSpecificationFiles(mainFolderUri: Uri): Promise<string[]> {
        const entries = await this.readDirectoryInternal(mainFolderUri);
        return entries.filter(([name, type]: [string, number]) => type === 1)
            .filter(([name]: [string, number]) => name.endsWith('.specification.qip.yaml'))
            .map(([name]: [string, number]) => name);
    }

}

export async function readDirectory(mainFolderUri: Uri): Promise<[string, number][]> {
    return await vscode.workspace.fs.readDirectory(mainFolderUri);
}

export async function createDirectory(dirUri: Uri): Promise<void> {
    return await vscode.workspace.fs.createDirectory(dirUri);
}




