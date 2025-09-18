import { ExtensionContext, Uri } from "vscode";
import { IntegrationSystem, IntegrationSystemType, SystemRequest, Environment } from "@netcracker/qip-ui";
import { fileApi } from "../response/file/fileApiProvider";
import { EMPTY_USER } from "../response/chainApiUtils";

export class SystemManagementService {
    private context: ExtensionContext;
    private mainFolder?: Uri;

    constructor(context: ExtensionContext, mainFolder?: Uri) {
        this.context = context;
        this.mainFolder = mainFolder;
    }

    /**
     * Get all integration systems
     */
    async getAllSystems(): Promise<IntegrationSystem[]> {
        try {
            const baseFolder = this.mainFolder || this.context.extensionUri;
            const systemsFileUri = Uri.joinPath(baseFolder, 'systems.yaml');
            const content = await fileApi.readFileContent(systemsFileUri);
            const yamlContent = new TextDecoder().decode(content);
            const yaml = require('yaml');
            const systemsData = yaml.parse(yamlContent);
            return systemsData.systems || [];
        } catch (error) {
            return [];
        }
    }

    /**
     * Get system by ID
     */
    async getSystemById(systemId: string): Promise<IntegrationSystem | null> {
        try {
            const systems = await this.getAllSystems();
            return systems.find(system => system.id === systemId) || null;
        } catch (error) {
            console.error(`Failed to get system ${systemId}:`, error);
            return null;
        }
    }

    /**
     * Create a new integration system
     */
    async createSystem(request: SystemRequest): Promise<IntegrationSystem> {
        try {
            const system: IntegrationSystem = {
                id: crypto.randomUUID(),
                name: request.name,
                description: request.description || '',
                createdWhen: Date.now(),
                createdBy: {...EMPTY_USER},
                modifiedWhen: Date.now(),
                modifiedBy: {...EMPTY_USER},
                activeEnvironmentId: '',
                integrationSystemType: request.type,
                protocol: request.protocol || '',
                extendedProtocol: request.extendedProtocol || '',
                specification: request.specification || '',
                labels: request.labels || []
            };

            const systems = await this.getAllSystems();
            systems.push(system);
            await this.saveSystems(systems);

            return system;
        } catch (error) {
            console.error('Failed to create system:', error);
            throw new Error(`Failed to create system: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Update an existing system
     */
    async updateSystem(systemId: string, updates: Partial<SystemRequest>): Promise<IntegrationSystem | null> {
        try {
            const systems = await this.getAllSystems();
            const systemIndex = systems.findIndex(system => system.id === systemId);

            if (systemIndex === -1) {
                console.warn(`System not found: ${systemId}`);
                return null;
            }

            const system = systems[systemIndex];
            const updatedSystem: IntegrationSystem = {
                ...system,
                ...updates,
                modifiedWhen: Date.now(),
                modifiedBy: {...EMPTY_USER}
            };

            systems[systemIndex] = updatedSystem;
            await this.saveSystems(systems);

            return updatedSystem;
        } catch (error) {
            console.error(`Failed to update system ${systemId}:`, error);
            throw new Error(`Failed to update system: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Delete a system
     */
    async deleteSystem(systemId: string): Promise<boolean> {
        try {
            const systems = await this.getAllSystems();
            const systemIndex = systems.findIndex(system => system.id === systemId);

            if (systemIndex === -1) {
                console.warn(`System not found: ${systemId}`);
                return false;
            }

            systems.splice(systemIndex, 1);
            await this.saveSystems(systems);

            return true;
        } catch (error) {
            console.error(`Failed to delete system ${systemId}:`, error);
            throw new Error(`Failed to delete system: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Set active environment for a system
     */
    async setActiveEnvironment(systemId: string, environmentId: string): Promise<boolean> {
        try {
            const system = await this.getSystemById(systemId);
            if (!system) {
                console.warn(`System not found: ${systemId}`);
                return false;
            }

            const systems = await this.getAllSystems();
            const systemIndex = systems.findIndex(system => system.id === systemId);

            if (systemIndex !== -1) {
                systems[systemIndex].activeEnvironmentId = environmentId;
                await this.saveSystems(systems);
            }

            return systemIndex !== -1;
        } catch (error) {
            console.log(`Failed to set active environment for system ${systemId}:`, error);
            return false;
        }
    }

    /**
     * Get systems by protocol
     */
    async getSystemsByProtocol(protocol: string): Promise<IntegrationSystem[]> {
        try {
            const systems = await this.getAllSystems();
            return systems.filter(system =>
                system.protocol.toLowerCase() === protocol.toLowerCase()
            );
        } catch (error) {
            console.error(`Failed to get systems by protocol ${protocol}:`, error);
            return [];
        }
    }

    /**
     * Get systems by type
     */
    async getSystemsByType(type: IntegrationSystemType): Promise<IntegrationSystem[]> {
        try {
            const systems = await this.getAllSystems();
            return systems.filter(system => system.integrationSystemType === type);
        } catch (error) {
            console.error(`Failed to get systems by type ${type}:`, error);
            return [];
        }
    }

    /**
     * Search systems by name or description
     */
    async searchSystems(query: string): Promise<IntegrationSystem[]> {
        try {
            const systems = await this.getAllSystems();
            const lowerQuery = query.toLowerCase();
            return systems.filter(system =>
                system.name.toLowerCase().includes(lowerQuery) ||
                system.description.toLowerCase().includes(lowerQuery)
            );
        } catch (error) {
            console.error(`Failed to search systems with query "${query}":`, error);
            return [];
        }
    }

    /**
     * Get system statistics
     */
    async getSystemStatistics(): Promise<{
        total: number;
        byType: Record<IntegrationSystemType, number>;
        byProtocol: Record<string, number>;
    }> {
        try {
            const systems = await this.getAllSystems();
            const stats = {
                total: systems.length,
                byType: {
                    [IntegrationSystemType.EXTERNAL]: 0,
                    [IntegrationSystemType.INTERNAL]: 0,
                    [IntegrationSystemType.IMPLEMENTED]: 0
                },
                byProtocol: {} as Record<string, number>
            };

            for (const system of systems) {
                // Count by type
                stats.byType[system.integrationSystemType]++;

                // Count by protocol
                const protocol = system.protocol || 'Unknown';
                stats.byProtocol[protocol] = (stats.byProtocol[protocol] || 0) + 1;
            }

            return stats;
        } catch (error) {
            console.error('Failed to get system statistics:', error);
            return {
                total: 0,
                byType: {
                    [IntegrationSystemType.EXTERNAL]: 0,
                    [IntegrationSystemType.INTERNAL]: 0,
                    [IntegrationSystemType.IMPLEMENTED]: 0
                },
                byProtocol: {}
            };
        }
    }

    /**
     * Validate system data
     */
    validateSystemData(system: Partial<IntegrationSystem>): { isValid: boolean; errors: string[] } {
        const errors: string[] = [];

        if (!system.name || system.name.trim().length === 0) {
            errors.push('System name is required');
        }

        if (!system.integrationSystemType) {
            errors.push('System type is required');
        }

        if (system.name && system.name.length > 100) {
            errors.push('System name must be less than 100 characters');
        }

        if (system.description && system.description.length > 500) {
            errors.push('System description must be less than 500 characters');
        }

        return {
            isValid: errors.length === 0,
            errors
        };
    }

    /**
     * Save systems to file
     */
    private async saveSystems(systems: IntegrationSystem[]): Promise<void> {
        try {
            const baseFolder = this.mainFolder || this.context.extensionUri;
            const systemsData = {
                systems,
                lastUpdated: Date.now()
            };

            const systemsFileUri = Uri.joinPath(baseFolder, 'systems.yaml');
            const yaml = require('yaml');
            const yamlContent = yaml.stringify(systemsData);
            const bytes = new TextEncoder().encode(yamlContent);
            await fileApi.writeFile(systemsFileUri, bytes);
        } catch (error) {
            console.error('Failed to save systems:', error);
            throw error;
        }
    }
}
