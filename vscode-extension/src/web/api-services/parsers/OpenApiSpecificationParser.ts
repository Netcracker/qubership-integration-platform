
export interface OpenApiData {
    openapi?: string;
    swagger?: string;
    info: {
        title: string;
        version: string;
        description?: string;
    };
    servers?: Array<{
        url: string;
        description?: string;
        name?: string;
        variables?: Record<string, { default?: string }>;
        protocol?: string;
    }>;
    paths: {
        [path: string]: {
            [method: string]: {
                operationId?: string;
                summary?: string;
                description?: string;
                tags?: string[];
                parameters?: any[];
                requestBody?: any;
                responses?: any;
            };
        };
    };
}

import { ContentParser } from './ContentParser';

export class OpenApiSpecificationParser {

    /**
     * Parse OpenAPI/Swagger content and extract operations
     */
    static async parseOpenApiContent(content: string): Promise<OpenApiData> {
        const specData = ContentParser.parseContentWithErrorHandling(content, 'OpenApiSpecificationParser');

        // Validate that it's an OpenAPI/Swagger spec
        if (!specData.openapi && !specData.swagger) {
            throw new Error('Not a valid OpenAPI or Swagger specification');
        }

        // Basic validation
        this.validateOpenApiSpec(specData);

        return specData as OpenApiData;
    }

    /**
     * Basic validation of OpenAPI/Swagger specification
     */
    private static validateOpenApiSpec(spec: any): void {
        // Check required fields
        if (!spec.info) {
            throw new Error('OpenAPI specification must have an "info" object');
        }

        if (!spec.info.title) {
            throw new Error('OpenAPI specification "info" must have a "title" field');
        }

        if (!spec.info.version) {
            throw new Error('OpenAPI specification "info" must have a "version" field');
        }

        // Check version format
        if (spec.openapi && !spec.openapi.match(/^\d+\.\d+\.\d+$/)) {
            console.warn('[OpenApiSpecificationParser] OpenAPI version format may be invalid:', spec.openapi);
        }

        if (spec.swagger && !spec.swagger.match(/^\d+\.\d+$/)) {
            console.warn('[OpenApiSpecificationParser] Swagger version format may be invalid:', spec.swagger);
        }

        // Check paths
        if (!spec.paths || Object.keys(spec.paths).length === 0) {
            console.warn('[OpenApiSpecificationParser] OpenAPI specification has no paths defined');
        }
    }

    /**
     * Create operations from OpenAPI data using QipSpecificationGenerator
     */
    static createOperationsFromOpenApi(openApiData: OpenApiData, specificationId: string): any[] {
        // Import QipSpecificationGenerator dynamically to avoid circular dependencies
        const { QipSpecificationGenerator } = require('../../services/QipSpecificationGenerator');

        // Create full QIP specification using QipSpecificationGenerator
        const qipSpec = QipSpecificationGenerator.createQipSpecificationFromOpenApi(openApiData, 'specification');

        // Extract operations from the QIP specification
        return qipSpec.content?.operations || [];
    }


    /**
     * Extract address from OpenAPI/Swagger data
     */
    static extractAddressFromOpenApiData(openApiData: OpenApiData): string | null {
        // For Swagger 2.0
        if (openApiData.swagger) {
            const specData = openApiData as any;
            const host = specData.host;
            const basePath = specData.basePath || '';
            const schemes = specData.schemes || ['https'];
            const scheme = schemes[0];

            if (host) {
                const address = `${scheme}://${host}${basePath}`;
                return address;
            }
        }

        // For OpenAPI 3.x
        if (openApiData.openapi) {
            const servers = openApiData.servers;
            if (servers && servers.length > 0) {
                const address = servers[0].url;
                return address;
            }
        }

        return null;
    }
}
