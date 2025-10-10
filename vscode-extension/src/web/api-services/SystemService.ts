import { ExtensionContext, Uri } from "vscode";
import { IntegrationSystem } from "./servicesTypes";
import { fileApi } from "../response/file/fileApiProvider";
import { getMainService } from "../response/serviceApiRead";
import { EMPTY_USER } from "../response/chainApiUtils";
import { getBaseFolder } from "../response/serviceApiUtils";
import { getExtensionsForFile } from "../response/file/fileExtensions";
import { LabelUtils } from "./LabelUtils";

const vscode = require('vscode');

/**
 * Service for managing integration systems
 * Provides functionality for reading and managing systems from files
 */
export class SystemService {
    private context: ExtensionContext;
    private mainFolder?: Uri;

    constructor(context: ExtensionContext, mainFolder?: Uri) {
        this.context = context;
        this.mainFolder = mainFolder;
    }

    /**
     * Get system by ID from service file
     */
    async getSystemById(systemId: string): Promise<IntegrationSystem | null> {
        try {
            const baseFolder = await this.getBaseFolderUri();
            const ext = getExtensionsForFile();
            const serviceFileUri = Uri.joinPath(baseFolder, `${systemId}${ext.service}`);
            const service = await getMainService(serviceFileUri);
            if (service.id === systemId) {
                return {
                    id: service.id,
                    name: service.name,
                    description: service.content?.description || "",
                    createdBy: service.content?.createdBy || {...EMPTY_USER},
                    modifiedBy: service.content?.modifiedBy || {...EMPTY_USER},
                    createdWhen: service.content?.createdWhen || 0,
                    modifiedWhen: service.content?.modifiedWhen || 0,
                    activeEnvironmentId: service.content?.activeEnvironmentId || "",
                    integrationSystemType: service.content?.integrationSystemType || "",
                    protocol: service.content?.protocol || "",
                    extendedProtocol: service.content?.extendedProtocol || "",
                    specification: service.content?.specification || "",
                    labels: LabelUtils.toEntityLabels(service.content?.labels || [])
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
     * Get raw service object by ID (with content structure)
     */
    async getRawServiceById(systemId: string): Promise<any | null> {
        try {
            const baseFolder = await this.getBaseFolderUri();
            const serviceFileUri = Uri.joinPath(baseFolder, `${systemId}${getExtensionsForFile().service}`);
            const service = await getMainService(serviceFileUri);
            if (service && service.id === systemId) {
                return service;
            }
            return null;
        } catch (error) {
            console.error(`[SystemService] Error getting raw service ${systemId}:`, error);
            return null;
        }
    }

    /**
     * Get base folder with standardized logic
     */
    async getBaseFolderUri(): Promise<Uri> {
        const baseFolder = await getBaseFolder(this.mainFolder, vscode.workspace.workspaceFolders?.[0]?.uri);
        if (!baseFolder) {
            throw new Error('No base folder available');
        }
        return baseFolder;
    }

    /**
     * Get base folder with extension context (for services that need it)
     */
    async getBaseFolderWithContext(): Promise<Uri> {
        const baseFolder = await getBaseFolder(this.mainFolder, this.context.extensionUri);
        if (!baseFolder) {
            throw new Error('No base folder available');
        }
        return baseFolder;
    }

    /**
     * Save system to file
     */
    async saveSystem(system: IntegrationSystem): Promise<void> {
        try {
            const baseFolder = await this.getBaseFolderUri();
            const ext = getExtensionsForFile();
            const serviceFileUri = Uri.joinPath(baseFolder, `${system.id}${ext.service}`);
            
            const service = await fileApi.getMainService(serviceFileUri);
            
            if (!service.content) {
                service.content = {};
            }
            
            service.content.protocol = system.protocol;
            service.content.extendedProtocol = system.extendedProtocol;
            service.content.specification = system.specification;
            service.content.modifiedWhen = Date.now();
            service.content.modifiedBy = { ...EMPTY_USER };
            
            await fileApi.writeMainService(serviceFileUri, service);
            console.log(`[SystemService] Saved system ${system.id}, protocol: ${system.protocol}`);
        } catch (error) {
            console.error(`[SystemService] Failed to save system ${system.id}:`, error);
            throw error;
        }
    }
}
