import { EMPTY_USER } from "../response/chainApiUtils";

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
                createdWhen: now,
                modifiedWhen: now,
                createdBy: { ...EMPTY_USER },
                modifiedBy: { ...EMPTY_USER },
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
            if (!service.content.createdWhen) service.content.createdWhen = now;
            if (!service.content.modifiedWhen) service.content.modifiedWhen = now;
            if (!service.content.createdBy) service.content.createdBy = { ...EMPTY_USER };
            if (!service.content.modifiedBy) service.content.modifiedBy = { ...EMPTY_USER };
            if (service.content.description === undefined) service.content.description = "";
            if (service.content.activeEnvironmentId === undefined) service.content.activeEnvironmentId = "";
            if (service.content.integrationSystemType === undefined) service.content.integrationSystemType = "";
            if (service.content.protocol === undefined) service.content.protocol = "";
            if (service.content.extendedProtocol === undefined) service.content.extendedProtocol = "";
            if (service.content.specification === undefined) service.content.specification = "";
            if (!service.content.environments) service.content.environments = [];
            if (!service.content.labels) service.content.labels = [];
            if (!service.content.migrations) service.content.migrations = [];
        }

        return service;
    }
}

