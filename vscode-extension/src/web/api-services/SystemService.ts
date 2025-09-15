import { ExtensionContext, Uri, FileType } from "vscode";
import { IntegrationSystem } from "../response/apiTypes";
import { fileApi } from "../response/file/fileApiProvider";
import { getMainService } from "../response/serviceApiRead";
import { EMPTY_USER } from "../response/chainApiUtils";

const vscode = require('vscode');

/**
 * Service for managing integration systems
 */
export class SystemService {
    private context: ExtensionContext;
    private mainFolder?: Uri;

    constructor(context: ExtensionContext, mainFolder?: Uri) {
        this.context = context;
        this.mainFolder = mainFolder;
    }

    /**
     * Get system by ID
     */
    async getSystemById(systemId: string): Promise<IntegrationSystem | null> {
        try {
            const baseFolder = this.mainFolder || this.getBaseFolder();
            if (!baseFolder) {
                throw new Error('No base folder available');
            }

            const service = await getMainService(baseFolder);
            if (service.id === systemId) {
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
            console.log(`[SystemService] System with id ${systemId} not found`);
            return null;
        } catch (error) {
            console.error(`[SystemService] Error getting system ${systemId}:`, error);
            return null;
        }
    }

    /**
     * Update system protocol
     */
    async updateSystemProtocol(systemId: string, protocol: string): Promise<void> {
        try {
            const baseFolder = this.mainFolder || this.getBaseFolder();
            if (!baseFolder) {
                throw new Error('No base folder available');
            }

            const service = await getMainService(baseFolder);
            if (service.id === systemId) {
                service.content.protocol = protocol;
                service.content.modifiedWhen = Date.now();
                service.content.modifiedBy = {...EMPTY_USER};
                
                const serviceFileUri = await this.getMainServiceFileUri(baseFolder);
                if (serviceFileUri) {
                    const yaml = require('yaml');
                    const yamlContent = yaml.stringify(service);
                    const bytes = new TextEncoder().encode(yamlContent);
                    await fileApi.writeFile(serviceFileUri, bytes);
                }
            }
        } catch (error) {
            console.error(`[SystemService] Error updating system protocol:`, error);
            throw error;
        }
    }

    /**
     * Get main service file URI
     */
    private async getMainServiceFileUri(mainFolderUri: Uri): Promise<Uri | undefined> {
        if (mainFolderUri) {
            let entries = await vscode.workspace.fs.readDirectory(mainFolderUri);

            if (!entries || !Array.isArray(entries)) {
                console.error(`Failed to read directory contents`);
                throw Error("Failed to read directory contents");
            }

            const files = entries.filter(([, type]: [string, FileType]) => type === 1)
                .filter(([name]: [string, FileType]) => name.endsWith('.service.qip.yaml'))
                .map(([name]: [string, FileType]) => name);
            if (files.length !== 1) {
                console.error(`Single *.service.qip.yaml file not found in the current directory`);
                throw Error("Single *.service.qip.yaml file not found in the current directory");
            }
            return Uri.joinPath(mainFolderUri, files[0]);
        }
        return undefined;
    }

    /**
     * Get base folder
     */
    private getBaseFolder(): Uri | undefined {
        return this.mainFolder || vscode.workspace.workspaceFolders?.[0]?.uri;
    }
}
