import { ExtensionContext, Uri } from "vscode";
import { Environment, EnvironmentRequest } from "./servicesTypes";
import { EMPTY_USER } from "../response/chainApiUtils";
import { fileApi } from "../response/file/fileApiProvider";
import { getExtensionsForFile } from "../response/file/fileExtensions";
import { SystemService } from "./SystemService";
import { LabelUtils } from "./LabelUtils";
import { EnvironmentDefaultProperties } from "./EnvironmentDefaultProperties";

/**
 * Service for managing environments
 * Handles CRUD operations for environments within systems
 */
export class EnvironmentService {
    private systemService: SystemService;

    constructor(context: ExtensionContext, mainFolder?: Uri) {
        this.systemService = new SystemService(context, mainFolder);
    }

    /**
     * Get all environments for a system
     */
    async getEnvironmentsForSystem(systemId: string): Promise<Environment[]> {
        try {
            const system = await this.systemService.getRawServiceById(systemId);
            if (!system) {
                return [];
            }

            return system.content?.environments || [];
        } catch (error) {
            return [];
        }
    }

    /**
     * Get environment by ID
     */
    async getEnvironmentById(systemId: string, environmentId: string): Promise<Environment | null> {
        try {
            const environments = await this.getEnvironmentsForSystem(systemId);
            return environments.find((env: Environment) => env.id === environmentId) || null;
        } catch (error) {
            return null;
        }
    }

    /**
     * Create a new environment
     */
    async createEnvironment(request: EnvironmentRequest): Promise<Environment> {
        try {

            // Get system protocol for default properties
            const { systemId } = request;
            if (!systemId) {
                throw new Error("System id is required to create environment");
            }

            const system = await this.systemService.getRawServiceById(systemId);
            if (!system) {
                throw new Error(`System not found: ${systemId}`);
            }

            const protocol = system.content?.protocol || '';
            const defaultProperties = EnvironmentDefaultProperties.getDefaultProperties(protocol);

            const environment: Environment = {
                id: crypto.randomUUID(),
                name: request.name,
                address: request.address,
                description: request.description || '',
                sourceType: 'MANUAL' as any,
                systemId,
                properties: defaultProperties,
                labels: LabelUtils.toEntityLabels([]),
                createdWhen: Date.now(),
                createdBy: { ...EMPTY_USER},
                modifiedWhen: Date.now(),
                modifiedBy: { ...EMPTY_USER}
            };


            // Initialize environments array if it doesn't exist
            if (!system.content) {
                system.content = {} as any;
            }
            if (!system.content.environments) {
                system.content.environments = [];
            }

            system.content.environments.push(environment);

            // If this is the first environment or marked as active, set it as active
            if (request.isActive || system.content.environments.length === 1) {
                system.content.activeEnvironmentId = environment.id;
            }

            await this.saveSystem(system);
            return environment;
        } catch (error) {
            throw new Error(`Failed to create environment: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Update an existing environment
     */
    async updateEnvironment(
        systemId: string,
        environmentId: string,
        updates: Partial<EnvironmentRequest>
    ): Promise<Environment | null> {
        try {
            const system = await this.systemService.getRawServiceById(systemId);
            if (!system || !system.content?.environments) {
                return null;
            }

            const environmentIndex = system.content.environments?.findIndex((env: Environment) => env.id === environmentId) || -1;
            if (environmentIndex === -1) {
                return null;
            }

            const environment = system.content.environments[environmentIndex];
            const updatedEnvironment: Environment = {
                ...environment,
                ...updates,
                modifiedWhen: Date.now(),
                modifiedBy: {...EMPTY_USER}
            };

            system.content.environments[environmentIndex] = updatedEnvironment;

            // If this environment is marked as active, update the system's active environment
            if (updates.isActive) {
                system.content.activeEnvironmentId = environmentId;
            }

            await this.saveSystem(system);

            return updatedEnvironment;
        } catch (error) {
            throw new Error(`Failed to update environment: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Delete an environment
     */
    async deleteEnvironment(systemId: string, environmentId: string): Promise<boolean> {
        try {
            const system = await this.systemService.getRawServiceById(systemId);
            if (!system || !system.content?.environments) {
                return false;
            }

            const environmentIndex = system.content.environments?.findIndex((env: Environment) => env.id === environmentId) || -1;
            if (environmentIndex === -1) {
                return false;
            }

            const environment = system.content.environments[environmentIndex];
            system.content.environments.splice(environmentIndex, 1);

            // If the deleted environment was active, set another environment as active
            if (system.content.activeEnvironmentId === environmentId) {
                system.content.activeEnvironmentId = system.content.environments.length > 0
                    ? system.content.environments[0].id
                    : '';
            }

            await this.saveSystem(system);

            return true;
        } catch (error) {
            throw new Error(`Failed to delete environment: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Set active environment for a system
     */
    async setActiveEnvironment(systemId: string, environmentId: string): Promise<boolean> {
        try {
            const system = await this.systemService.getRawServiceById(systemId);
            if (!system) {
                return false;
            }

            const environment = await this.getEnvironmentById(systemId, environmentId);
            if (!environment) {
                return false;
            }

            if (!system.content) {
                system.content = {} as any;
            }

            system.content.activeEnvironmentId = environmentId;
            await this.saveSystem(system);

            return true;
        } catch (error) {
            return false;
        }
    }

    /**
     * Get active environment for a system
     */
    async getActiveEnvironment(systemId: string): Promise<Environment | null> {
        try {
            const system = await this.systemService.getRawServiceById(systemId);
            if (!system || !system.content?.activeEnvironmentId) {
                return null;
            }

            return await this.getEnvironmentById(systemId, system.content.activeEnvironmentId);
        } catch (error) {
            return null;
        }
    }

    /**
     * Validate environment data
     */
    validateEnvironmentData(environment: Partial<EnvironmentRequest>): { isValid: boolean; errors: string[] } {
        const errors: string[] = [];

        if (!environment.name || environment.name.trim().length === 0) {
            errors.push('Environment name is required');
        }

        if (!environment.address || environment.address.trim().length === 0) {
            errors.push('Environment address is required');
        }

        if (!environment.systemId || environment.systemId.trim().length === 0) {
            errors.push('System ID is required');
        }

        if (environment.name && environment.name.length > 100) {
            errors.push('Environment name must be less than 100 characters');
        }

        if (environment.address && environment.address.length > 500) {
            errors.push('Environment address must be less than 500 characters');
        }

        if (environment.description && environment.description.length > 1000) {
            errors.push('Environment description must be less than 1000 characters');
        }

        // Validate URL format
        if (environment.address) {
            try {
                new URL(environment.address);
            } catch {
                errors.push('Environment address must be a valid URL');
            }
        }

        return {
            isValid: errors.length === 0,
            errors
        };
    }

    /**
     * Get environment statistics for a system
     */
    async getEnvironmentStatistics(systemId: string): Promise<{
        total: number;
        active: string | null;
        byName: Record<string, number>;
    }> {
        try {
            const environments = await this.getEnvironmentsForSystem(systemId);
            const system = await this.systemService.getRawServiceById(systemId);

            const stats = {
                total: environments.length,
                active: system?.content?.activeEnvironmentId || null,
                byName: {} as Record<string, number>
            };

            for (const environment of environments) {
                const name = environment.name || 'Unnamed';
                stats.byName[name] = (stats.byName[name] || 0) + 1;
            }

            return stats;
        } catch (error) {
            return {
                total: 0,
                active: null,
                byName: {}
            };
        }
    }


    /**
     * Save system data
     */
    private async saveSystem(system: any): Promise<void> {
        try {
            const ext = getExtensionsForFile();
            const serviceFileUri = await fileApi.findFileById(system.id, ext.service);
            await fileApi.writeMainService(serviceFileUri, system);
        } catch (error) {
            throw error;
        }
    }
}
