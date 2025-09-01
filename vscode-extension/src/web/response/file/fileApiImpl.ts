import {FileApi} from './fileApi';
import {ExtensionContext, FileType, Uri} from 'vscode';
import * as yaml from 'yaml';
import {LibraryData} from "../apiTypes";

const vscode = require('vscode');
const RESOURCES_FOLDER = 'resources';

export class VSCodeFileApi implements FileApi {
    context: ExtensionContext;

    constructor(context: ExtensionContext) {
        this.context = context;
    }

    private async getMainChainFileUri(mainFolderUri: Uri): Promise<Uri> {
        if (mainFolderUri) {
            let entries = await vscode.workspace.fs.readDirectory(mainFolderUri);

            const files = entries
                .filter(([, type]: [string, FileType]) => type === 1)
                .filter(([name]: [string]) => name.endsWith('.chain.qip.yaml'))
                .map(([name]: [string]) => name);
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
            const fileContent = await vscode.workspace.fs.readFile(fileUri);
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
        console.log("read property file", propertiesFilename);
        const fileUri = vscode.Uri.joinPath(mainFolderUri, propertiesFilename);
        console.log("property file uri", fileUri);
        let fileContent;
        try {
            fileContent = await vscode.workspace.fs.readFile(fileUri);
        } catch (error) {
            if (!propertiesFilename.includes(RESOURCES_FOLDER)) {
                return await this.readFile(mainFolderUri, RESOURCES_FOLDER + '/' + propertiesFilename);
            }
            throw error;
        }
        const textFile = new TextDecoder('utf-8').decode(fileContent);
        console.log("property file", textFile);
        return textFile;
    }

    async getLibrary(): Promise<LibraryData> {
        const fileUri = vscode.Uri.joinPath(this.context.extensionUri, 'media', 'library.json');
        const content = new TextDecoder('utf-8').decode(await vscode.workspace.fs.readFile(fileUri));
        return JSON.parse(content);
    }

    async writePropertyFile(parameters: any, propertyFilename: string, propertyData: string): Promise<void> {
        const mainFolderUri = parameters as Uri;
        const bytes = new TextEncoder().encode(propertyData);
        try {
            await vscode.workspace.fs.writeFile(vscode.Uri.joinPath(mainFolderUri, RESOURCES_FOLDER, propertyFilename), bytes);
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
            await vscode.workspace.fs.writeFile(await this.getMainChainFileUri(mainFolderUri), bytes);
            vscode.window.showInformationMessage('Chain has been updated!');
        } catch (err) {
            vscode.window.showErrorMessage('Failed to write file: ' + err);
            throw Error('Failed to write file: ' + err);
        }
    }

    async removeFile(mainFolderUri: Uri, propertyFilename: string): Promise<void> {
        console.log("removing property file", propertyFilename);
        const fileUri = vscode.Uri.joinPath(mainFolderUri, propertyFilename);
        console.log("property file uri", fileUri);
        try {
            await vscode.workspace.fs.delete(fileUri);
        } catch (error) {
            console.log("Error deleting property file", fileUri);
        }

        return;
    }

    async createEmptyChain(createInParentDir: boolean = false) {
        try {
            const workspaceFolders = vscode.workspace.workspaceFolders;
            if (!workspaceFolders) {
                vscode.window.showErrorMessage('Open a workspace folder first');
                return;
            }
            const arg = await vscode.window.showInputBox({prompt: 'Enter new chain name'});

            let folderUri = workspaceFolders[0].uri;
            const chainId = crypto.randomUUID();
            const chainName = arg || 'New Chain';
            if (createInParentDir) {
                folderUri = vscode.Uri.joinPath(folderUri, '..');
            }
            folderUri = vscode.Uri.joinPath(folderUri, chainId);

            // Create the folder
            await vscode.workspace.fs.createDirectory(folderUri);

            // Create template file
            const chainFileUri = vscode.Uri.joinPath(folderUri, `${chainId}.chain.qip.yaml`);
            const chain = {
                $schema: 'http://qubership.org/schemas/product/qip/chain',
                id: chainId,
                name: chainName,
                content: {
                    migrations: "[100, 101]",
                    elements: [],
                    dependencies: [],
                }
            };
            const bytes = new TextEncoder().encode(yaml.stringify(chain));

            await vscode.workspace.fs.writeFile(chainFileUri, bytes);
            vscode.window.showInformationMessage(`Chain "${chainName}" created with id ${chainId}`);
        } catch (err) {
            vscode.window.showErrorMessage(`Failed: ${err}`);
        }
    }
}


