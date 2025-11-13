import {Uri} from "vscode";
import {IntegrationSystem, SpecificationGroup} from "./servicesTypes";
import {fileApi} from "../response/file/fileApiProvider";
import {getBaseFolder} from "../response/serviceApiUtils";
import {YamlFileUtils} from "./YamlFileUtils";
import {LabelUtils} from "./LabelUtils";
import {ProjectConfigService} from "../services/ProjectConfigService";
import {ContentParser} from './parsers/ContentParser';

const vscode = require('vscode');

/**
 * Service for managing specification groups
 */
export class SpecificationGroupService {
    private readonly mainFolder?: Uri;

    constructor(mainFolder?: Uri) {
        this.mainFolder = mainFolder;
    }

    /**
     * Get specification group by ID
     */
    async getSpecificationGroupById(groupId: string, systemId: string): Promise<SpecificationGroup | null> {
        try {
            const config = ProjectConfigService.getConfig();
            const groupFileUri = await fileApi.findFileById(groupId, config.extensions.specificationGroup);
            const parsed = await ContentParser.parseContentFromFile(groupFileUri);

            return {
                id: parsed.id,
                name: parsed.name,
                description: parsed.description || '',
                parentId: parsed.content?.parentId || parsed.parentId,
                specifications: [],
                synchronization: parsed.content?.synchronization || parsed.synchronization || false
            };
        } catch (error) {
            console.error(`[SpecificationGroupService] Error getting specification group ${groupId}:`, error);
            return null;
        }
    }

    /**
     * Create specification group
     */
    async createSpecificationGroup(
        system: IntegrationSystem,
        name: string,
        protocol?: string
    ): Promise<SpecificationGroup> {
        const groupId = `${system.id}-${name}`;
        const now = Date.now();

        const specificationGroup: SpecificationGroup = {
            id: groupId,
            name: name,
            systemId: system.id, // Store systemId for UI compatibility
            specifications: [],
            synchronization: false,

        };

        if (protocol) {
            system.protocol = protocol;
        }

        return specificationGroup;
    }

    /**
     * Save specification group file
     */
    async saveSpecificationGroupFile(systemId: string, specificationGroup: SpecificationGroup): Promise<void> {
        try {
            const baseFolder = await getBaseFolder(this.mainFolder, vscode.workspace.workspaceFolders?.[0]?.uri);
            if (!baseFolder) {
                throw new Error('No base folder available');
            }

            const config = ProjectConfigService.getConfig();
            const groupFile = Uri.joinPath(baseFolder, `${specificationGroup.id}${config.extensions.specificationGroup}`);

            console.log(`[SpecificationGroupService] Saving specification group file:`, {
                systemId,
                specificationGroupId: specificationGroup.id,
                groupFile: groupFile.fsPath
            });

            const yamlData = {
                id: specificationGroup.id,
                $schema: config.schemaUrls.specificationGroup,
                name: specificationGroup.name,
                content: {
                    synchronization: specificationGroup.synchronization || false,
                    parentId: systemId,
                    labels: specificationGroup.labels ? LabelUtils.fromEntityLabels(specificationGroup.labels) : []
                }
            };

            console.log(`[SpecificationGroupService] YAML content:`, yamlData);
            await YamlFileUtils.saveYamlFile(groupFile, yamlData);
            console.log(`[SpecificationGroupService] Saved specification group file: ${groupFile.fsPath}`);
        } catch (error) {
            console.error(`[SpecificationGroupService] Error saving specification group file:`, {
                error: error instanceof Error ? error.message : String(error),
                stack: error instanceof Error ? error.stack : undefined,
                systemId,
                specificationGroupId: specificationGroup.id,
                mainFolder: this.mainFolder?.fsPath
            });
            throw new Error(`Failed to save specification group file: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

}
