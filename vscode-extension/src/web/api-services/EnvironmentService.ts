import { Environment, EnvironmentRequest } from "./servicesTypes";
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

    constructor() {
        this.systemService = new SystemService();
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

            const requestedSourceType = request.sourceType || 'MANUAL';
            const requestedProperties = request.properties;
            const mergedProperties = requestedProperties
                ? { ...defaultProperties, ...requestedProperties }
                : defaultProperties;

            const environment: Environment = {
                id: crypto.randomUUID(),
                name: request.name,
                address: request.address,
                description: request.description || '',
                sourceType: requestedSourceType as any,
                systemId,
                properties: mergedProperties,
                labels: LabelUtils.toEntityLabels([]),
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
