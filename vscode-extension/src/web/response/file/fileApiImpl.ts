import {FileApi} from './fileApi';
import {ExtensionContext, Uri, WorkspaceFolder} from 'vscode';
import * as yaml from 'yaml';
import {Chain, LibraryData} from "@netcracker/qip-ui";
import {EMPTY_USER} from "../chainApiUtils";
import {QipFileType} from "../serviceApiUtils";

const vscode = require('vscode');
const RESOURCES_FOLDER = 'resources';

export class VSCodeFileApi implements FileApi {
    context: ExtensionContext;

    constructor(context: ExtensionContext) {
        this.context = context;
    }

    getRootDirectory(): Uri {
        return vscode.workspace.workspaceFolders[0].uri;
    }

    private async getParentDirectoryUri(uri: Uri): Promise<Uri> {
        try {
            const stat = await vscode.workspace.fs.stat(uri);
            if (stat.type === vscode.FileType.File) {
                const lastSlashIndex = uri.path.lastIndexOf('/');
                const parentPath = lastSlashIndex > 0 ? uri.path.substring(0, lastSlashIndex) : uri.path;
                return uri.with({ path: parentPath });
            }
            return uri;
        } catch (_e) {
            // If stat fails (e.g., file doesn't exist yet), treat uri as a file path and return its parent
            const lastSlashIndex = uri.path.lastIndexOf('/');
            const parentPath = lastSlashIndex > 0 ? uri.path.substring(0, lastSlashIndex) : uri.path;
            return uri.with({ path: parentPath });
        }
    }

    private async getFilesByExtensionInDirectory(directoryUri: Uri, extension: string): Promise<string[]> {
        const entries = await readDirectory(directoryUri);
        return entries
            .filter(([, type]: [string, number]) => type === 1)
            .filter(([name]: [string, number]) => name.endsWith(extension))
            .map(([name]: [string, number]) => name);
    }

    private async getMainChainFileUri(baseUri: Uri): Promise<Uri> {
        if (!baseUri) {
            throw Error('No base uri provided');
        }
        const stat = await vscode.workspace.fs.stat(baseUri);
        if (stat.type === vscode.FileType.File) {
            return baseUri;
        }
        const files = await this.getFilesByExtensionInDirectory(baseUri, '.chain.qip.yaml');
        if (files.length !== 1) {
            console.error(`Single *.chain.qip.yaml file not found in the current directory`);
            vscode.window.showWarningMessage("*.chain.qip.yaml file not found in the current directory");
            throw Error("Single *.chain.qip.yaml file not found in the current directory");
        }
        return vscode.Uri.joinPath(baseUri, files[0]);
    }

    async findAndBuildChainsRecursively(folderUri: Uri, chainBuilder: (chainContent: any) => Partial<Chain> | undefined, result: Partial<Chain>[]): Promise<void> {
        const entries = await readDirectory(folderUri);

        for (const [name, type] of entries) {
            if (type === vscode.FileType.File && name.endsWith('.chain.qip.yaml')) {
                const fileUri = vscode.Uri.joinPath(folderUri, name);

                const chainYaml = await this.parseFile(fileUri);
                const chain = chainBuilder(chainYaml);
                if (chain) {
                    result.push(chain);
                }
            } else if (type === vscode.FileType.Directory) {
                const subFolderUri = vscode.Uri.joinPath(folderUri, name);
                await this.findAndBuildChainsRecursively(subFolderUri, chainBuilder, result);
            }
        }
    }

    async findChainRecursively(folderUri: Uri, chainId: string): Promise<any> {
        const result: any[] = [];

        await this.findChainsRecursively(folderUri, chainId, result);

        if (result.length === 0) {
            throw Error(`Chain with id=${chainId} is not found under the directory ${folderUri}`);
        } else if (result.length > 1) {
            throw Error(`Multiple chains with id=${chainId} found under the directory ${folderUri}`);
        } else {
            return result[0];
        }
    }

    private async findChainsRecursively(folderUri: Uri, chainId: string, result: any[]): Promise<void> {
        const entries = await readDirectory(folderUri);

        for (const [name, type] of entries) {
            if (type === vscode.FileType.File && name.endsWith('.chain.qip.yaml')) {
                const fileUri = vscode.Uri.joinPath(folderUri, name);
                const chainYaml = await this.parseFile(fileUri);
                if (chainYaml.id === chainId) {
                    result.push(chainYaml);
                }
            } else if (type === vscode.FileType.Directory) {
                const subFolderUri = vscode.Uri.joinPath(folderUri, name);
                await this.findChainsRecursively(subFolderUri, chainId, result);
            }
        }
    }

    async getMainChain(parameters: any): Promise<any> {
        const baseUri = parameters as Uri;
        const fileUri = await this.getMainChainFileUri(baseUri);
        try {
            const text = await this.readFileContent(fileUri);
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
        const baseUri = parameters as Uri;
        const baseFolder = await this.getParentDirectoryUri(baseUri);
        const fileUri = vscode.Uri.joinPath(baseFolder, propertiesFilename);
        let fileContent;
        try {
            fileContent = await this.readFileContent(fileUri);
        } catch (error) {
            if (!propertiesFilename.includes(RESOURCES_FOLDER)) {
                return await this.readFile(baseFolder, RESOURCES_FOLDER + '/' + propertiesFilename);
            }
            throw error;
        }
        return fileContent;
    }

    async parseFile(fileUri: Uri): Promise<any> {
        try {
            const content = await this.readFileContent(fileUri);
            const yamlContent = new TextDecoder('utf-8').decode(content);
            return yaml.parse(yamlContent);
        } catch (e) {
            console.error(`Unable to parse file: ${fileUri}`, e);
            throw e;
        }
    }

    async getLibrary(): Promise<LibraryData> {
        const fileUri = vscode.Uri.joinPath(this.context.extensionUri, 'media', 'library.json');
        const content = await this.readFileContent(fileUri);
        return JSON.parse(content);
    }

    async writePropertyFile(parameters: any, propertyFilename: string, propertyData: string): Promise<void> {
        const baseUri = parameters as Uri;
        const baseFolder = await this.getParentDirectoryUri(baseUri);
        const bytes = new TextEncoder().encode(propertyData);
        try {
            await this.writeFile(vscode.Uri.joinPath(baseFolder, RESOURCES_FOLDER, propertyFilename), bytes);
            vscode.window.showInformationMessage('Property file has been updated!');
        } catch (err) {
            vscode.window.showErrorMessage('Failed to write file: ' + err);
            throw Error('Failed to write file: ' + err);
        }
    }

    async writeMainChain(parameters: any, chainData: any): Promise<void> {
        const baseUri = parameters as Uri;
        const bytes = new TextEncoder().encode(yaml.stringify(chainData));
        try {
            await this.writeFile(await this.getMainChainFileUri(baseUri), bytes);
            vscode.window.showInformationMessage('Chain has been updated!');
        } catch (err) {
            vscode.window.showErrorMessage('Failed to write file: ' + err);
            throw Error('Failed to write file: ' + err);
        }
    }

    async removeFile(mainFolderUri: Uri, propertyFilename: string): Promise<void> {
        const baseFolder = await this.getParentDirectoryUri(mainFolderUri);
        const fileUri = vscode.Uri.joinPath(baseFolder, propertyFilename);
        try {
            await this.deleteFile(fileUri);
        } catch (error) {
            console.error("Error deleting property file", fileUri);
        }

        return;
    }


    // Service-related methods
    async getMainService(serviceFileUri: Uri): Promise<any> {
        try {
            const text = await this.readFileContent(serviceFileUri);
            const parsed = yaml.parse(text);

            if (parsed && parsed.name) {
                return parsed;
            }
            throw Error('Invalid service file content');
        } catch (e) {
            console.error(`Service file ${serviceFileUri} can't be parsed from QIP Extension API`, e);
            throw e;
        }
    }

    async getService(serviceFileUri: Uri, serviceId: string): Promise<any> {
        try {
            const text = await this.readFileContent(serviceFileUri);
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

    async writeMainService(serviceFileUri: Uri, serviceData: any): Promise<void> {
        await this.writeServiceFile(serviceFileUri, serviceData);
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
        const parentDir = await this.getParentDirectoryUri(fileUri);
        await createDirectory(parentDir);
        await vscode.workspace.fs.writeFile(fileUri, data);
    }

    async readFileContent(fileUri: Uri): Promise<string> {
        const bytes = await vscode.workspace.fs.readFile(fileUri);
        return new TextDecoder('utf-8').decode(bytes);
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

    async getFileType(fileUri: Uri): Promise<string> {
        try {
            const stat = await vscode.workspace.fs.stat(fileUri);
            if (stat.type === vscode.FileType.File) {
                const name = fileUri.path.split('/').pop() || '';
                if (name.endsWith('.service.qip.yaml')) {
                    return QipFileType.SERVICE;
                }
                if (name.endsWith('.chain.qip.yaml')) {
                    return QipFileType.CHAIN;
                }
                return QipFileType.UNKNOWN;
            }

            // Directory: infer by contents
            const entries = await this.readDirectoryInternal(fileUri);
            const hasChainFile = entries.some(([name]: [string, number]) => name.endsWith('.chain.qip.yaml'));
            const hasServiceFile = entries.some(([name]: [string, number]) => name.endsWith('.service.qip.yaml'));
            if (hasServiceFile) {
                return QipFileType.SERVICE;
            }
            if (hasChainFile) {
                return QipFileType.CHAIN;
            }
            return QipFileType.FOLDER;
        } catch (e) {
            return QipFileType.UNKNOWN;
        }
    }

    private async readDirectoryInternal(mainFolderUri: Uri): Promise<[string, number][]> {
        return await readDirectory(mainFolderUri);
    }

    private async getFilesByExtension(serviceFileUri: Uri, extension: string): Promise<string[]> {
        const serviceFolderUri = await this.getParentDirectoryUri(serviceFileUri);
        return await this.getFilesByExtensionInDirectory(serviceFolderUri, extension);
    }

    async getSpecificationGroupFiles(serviceFileUri: Uri): Promise<string[]> {
        return await this.getFilesByExtension(serviceFileUri, '.specification-group.qip.yaml');
    }

    async getSpecificationFiles(serviceFileUri: Uri): Promise<string[]> {
        return await this.getFilesByExtension(serviceFileUri, '.specification.qip.yaml');
    }

}

export async function readDirectory(mainFolderUri: Uri): Promise<[string, number][]> {
    return await vscode.workspace.fs.readDirectory(mainFolderUri);
}

export async function createDirectory(dirUri: Uri): Promise<void> {
    return await vscode.workspace.fs.createDirectory(dirUri);
}




