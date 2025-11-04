/**
 * Utility for normalizing service objects
 * Ensures that service.content always exists with valid default values
 */
export class ServiceNormalizer {
    /**
     * Normalizes a service object by ensuring content field exists with all required properties
     * If content is missing or empty, creates a default content object
     */
    static normalizeService(service: any): any {
        if (!service) {
            return service;
        }

        if (!service.content || typeof service.content !== 'object') {
            const now = Date.now();
            service.content = {
                description: "",
                activeEnvironmentId: "",
                integrationSystemType: "",
                protocol: "",
                extendedProtocol: "",
                specification: "",
                environments: [],
                labels: [],
                migrations: []
            };
        } else {
            const now = Date.now();
            if (service.content.description === undefined) service.content.description = "";
            if (service.content.activeEnvironmentId === undefined) service.content.activeEnvironmentId = "";
            if (service.content.integrationSystemType === undefined) service.content.integrationSystemType = "";
            if (service.content.protocol === undefined) service.content.protocol = "";
            if (service.content.extendedProtocol === undefined) service.content.extendedProtocol = "";
            if (service.content.specification === undefined) service.content.specification = "";
            if (!service.content.environments) service.content.environments = [];
            if (!service.content.labels) service.content.labels = [];
            if (!service.content.migrations) service.content.migrations = [];

            // Normalize environments
            if (service.content.environments && Array.isArray(service.content.environments)) {
                service.content.environments = service.content.environments.map((env: any) => {
                    if (!env.properties) {
                        env.properties = {};
                    }
                    if (!env.labels) {
                        env.labels = [];
                    }
                    if (env.sourceType === undefined) {
                        env.sourceType = "MANUAL";
                    }
                    if (env.description === undefined) {
                        env.description = "";
                    }
                    if (env.address === undefined) {
                        env.address = "";
                    }
                    return env;
                });
            }
        }

        return service;
    }
}

