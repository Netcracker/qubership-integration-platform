import { ExtensionContext, Uri } from "vscode";
import { SpecificationGroup, IntegrationSystem } from "./servicesTypes";
import { EMPTY_USER } from "../response/chainApiUtils";
import { fileApi } from "../response/file/fileApiProvider";
import { getBaseFolder } from "../response/serviceApiUtils";
import { YamlFileUtils } from "./YamlFileUtils";

const vscode = require('vscode');

/**
 * Service for managing specification groups
 */
export class SpecificationGroupService {
    private context: ExtensionContext;
    private mainFolder?: Uri;

    constructor(context: ExtensionContext, mainFolder?: Uri) {
        this.context = context;
        this.mainFolder = mainFolder;
    }

    /**
     * Get specification group by ID
     */
    async getSpecificationGroupById(groupId: string, systemId: string): Promise<SpecificationGroup | null> {
        try {
            const baseFolder = await getBaseFolder(this.mainFolder, vscode.workspace.workspaceFolders?.[0]?.uri);
            if (!baseFolder) {
                throw new Error('No base folder available');
            }

            // Look for specification group file in root directory
            const groupFile = Uri.joinPath(baseFolder, `${groupId}.specification-group.qip.yaml`);

            try {
                const content = await fileApi.readFileContent(groupFile);
                const yaml = require('yaml');
                const parsed = yaml.parse(content);

                const specificationGroup: SpecificationGroup = {
                    id: parsed.id,
                    name: parsed.name,
                    description: parsed.description || '',
                    parentId: parsed.content?.parentId || parsed.parentId,
                    createdWhen: parsed.content?.createdWhen || parsed.createdWhen,
                    createdBy: parsed.content?.createdBy || parsed.createdBy,
                    modifiedWhen: parsed.content?.modifiedWhen || parsed.modifiedWhen,
                    modifiedBy: parsed.content?.modifiedBy || parsed.modifiedBy,
                    specifications: [],
                    synchronization: parsed.content?.synchronization || parsed.synchronization || false
                };
                return specificationGroup;
            } catch (error) {
                console.error(`[SpecificationGroupService] Specification group not found: ${groupId}`);
                return null;
            }
        } catch (error) {
            console.error(`[SpecificationGroupService] Error getting specification group:`, error);
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
            createdWhen: now,
            createdBy: {...EMPTY_USER},
            modifiedWhen: now,
            modifiedBy: {...EMPTY_USER},
            specifications: [],
            synchronization: false
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
            console.log(`[SpecificationGroupService] Saving specification group file:`, {
                systemId,
                specificationGroupId: specificationGroup.id,
                mainFolder: this.mainFolder?.fsPath,
                baseFolder: baseFolder?.fsPath
            });

            if (!baseFolder) {
                throw new Error('No base folder available');
            }

            // Save specification group file in root directory
            const groupFile = Uri.joinPath(baseFolder, `${specificationGroup.id}.specification-group.qip.yaml`);
            console.log(`[SpecificationGroupService] Group file path:`, groupFile.fsPath);

            const yamlData = {
                $schema: "http://qubership.org/schemas/product/qip/specification-group",
                id: specificationGroup.id,
                name: specificationGroup.name,
                content: {
                    createdWhen: specificationGroup.createdWhen,
                    modifiedWhen: specificationGroup.modifiedWhen,
                    createdBy: specificationGroup.createdBy,
                    modifiedBy: specificationGroup.modifiedBy,
                    synchronization: specificationGroup.synchronization || false,
                    parentId: systemId
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
