import { ExtensionContext, Uri } from "vscode";
import { SpecificationGroup, Specification } from "./servicesTypes";
import { ApiSpecificationType } from "./importApiTypes";
import {
    SoapSpecificationParser,
    ProtoSpecificationParser,
    GraphQLSpecificationParser,
    OpenApiSpecificationParser,
    AsyncApiSpecificationParser
} from "./parsers";
import { ContentParser } from './parsers/ContentParser';

/**
 * Service for processing specification files
 */
export class SpecificationProcessorService {
    constructor(context: ExtensionContext, mainFolder?: Uri) {
        // Constructor parameters are kept for API compatibility but not used internally
    }

    /**
     * Process specification files
     */
    async processSpecificationFiles(
        specificationGroup: SpecificationGroup,
        files: File[],
        systemId?: string
    ): Promise<void> {

        for (const file of files) {
            try {
                await this.processSpecificationFile(file, specificationGroup, systemId, files);
            } catch (error) {
                throw error;
            }
        }

    }

    /**
     * Process single specification file
     */
    private async processSpecificationFile(
        file: File,
        specificationGroup: SpecificationGroup,
        systemId?: string,
        allFiles?: File[]
    ): Promise<void> {
        const fileExtension = this.getFileExtension(file.name);
        const specificationType = await this.detectSpecificationType(file.name, fileExtension);

        if (!specificationType) {
            return;
        }


        // Extract version from file name or content
        const version = await this.extractVersionFromFile(file);

        // Create specification ID in format: {systemId}-{groupName}-{version}
        const specificationId = systemId ? `${systemId}-${specificationGroup.name}-${version}` : crypto.randomUUID();

        // Parse file content and create operations
        const operations = await this.createOperationsFromFile(file, specificationType, specificationId);

        // Create specification object
        const specification: Specification = {
            id: specificationId,
            name: version, // Use version as name, not file name
            description: `Specification for ${file.name}`,
            parentId: specificationGroup.id,
            version: version,
            format: specificationType?.toString() || 'unknown',
            content: '',
            deprecated: false,
            source: file.name,
            operations: operations
        };

        // Add to specification group
        specificationGroup.specifications.push(specification);

    }

    /**
     * Detect specification type from file
     */
    private async detectSpecificationType(fileName: string, fileExtension: string): Promise<ApiSpecificationType | null> {
        try {
            // First check by file name patterns
            if (fileName.includes('asyncapi') || fileName.includes('async')) {
                return ApiSpecificationType.ASYNC;
            } else if (fileName.includes('openapi') || fileName.includes('swagger')) {
                return ApiSpecificationType.HTTP;
            } else if (fileName.includes('graphql') || fileExtension === '.graphql') {
                return ApiSpecificationType.GRAPHQL;
            } else if (fileName.includes('proto') || fileExtension === '.proto') {
                return ApiSpecificationType.GRPC;
            } else if (fileName.includes('wsdl') || fileExtension === '.wsdl' || fileExtension === '.xml') {
                return ApiSpecificationType.SOAP;
            }

            // If file name doesn't give clear indication, check content for JSON/YAML files
            if (fileExtension === '.json' || fileExtension === '.yaml' || fileExtension === '.yml') {
                // For now, default to HTTP for JSON/YAML files without clear naming
                // Content-based detection will be handled in the calling method
                return ApiSpecificationType.HTTP;
            }

            return null;
        } catch (error) {
            return null;
        }
    }

    /**
     * Get file extension
     */
    private getFileExtension(fileName: string): string {
        const lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex !== -1 ? fileName.substring(lastDotIndex) : '';
    }

    /**
     * Detect protocol from specification data
     */
    detectProtocolFromSpecification(specData: any): string | null {
        if (!specData) {
            return null;
        }

        if (specData.type === 'WSDL') {
            return 'soap';
        }

        if (specData.swagger || specData.openapi) {
            return 'http';
        }

        if (specData.asyncapi) {
            const protocol = specData.info?.['x-protocol']?.toLowerCase() ||
                           specData.servers?.main?.protocol?.toLowerCase() ||
                           (specData.servers && Object.keys(specData.servers).length > 0
                               ? (Object.values(specData.servers)[0] as any)?.protocol?.toLowerCase()
                               : null);
            return protocol || null;
        }

        return null;
    }

    /**
     * Extract address from specification data
     */
    extractAddressFromSpecification(specData: any): string | null {
        if (!specData) {
            return null;
        }

        if (specData.type === 'WSDL') {
            return specData.service?.address || 'https://soap.example.com/ws';
        }

        if (specData.swagger && specData.host) {
            const scheme = (specData.schemes || ['https'])[0];
            const basePath = specData.basePath || '';
            return `${scheme}://${specData.host}${basePath}`;
        }

        if (specData.openapi && specData.servers?.length > 0) {
            return specData.servers[0].url;
        }

        if (specData.asyncapi) {
            return AsyncApiSpecificationParser.extractAddressFromAsyncApiData(specData);
        }

        return null;
    }

    /**
     * Read file content
     */
    private async readFileContent(file: File): Promise<string | null> {
        try {
            return await file.text();
        } catch (error) {
            return null;
        }
    }

    /**
     * Extract version from file name or content
     */
    private async extractVersionFromFile(file: File): Promise<string> {
        try {
            // First try to extract from file name
            const fileName = file.name;
            const versionMatch = fileName.match(/v?(\d+\.\d+\.\d+)/);
            if (versionMatch) {
                return versionMatch[1];
            }

            // If not found in filename, try to extract from content
            if (file.text) {
                const content = await file.text();

                // Try to parse as JSON
                try {
                    const json = JSON.parse(content);

                    // For Swagger 2.0
                    if (json.swagger && json.info && json.info.version) {
                        return json.info.version;
                    }

                    // For OpenAPI 3.x
                    if (json.openapi && json.info && json.info.version) {
                        return json.info.version;
                    }

                    // For AsyncAPI
                    if (json.asyncapi && json.info && json.info.version) {
                        return json.info.version;
                    }

                } catch (jsonError) {
                    // If not JSON, try as YAML
                    try {
                        const yamlData = ContentParser.parseContent(content);

                        // For AsyncAPI YAML
                        if (yamlData.asyncapi && yamlData.info && yamlData.info.version) {
                            return yamlData.info.version;
                        }

                        // For OpenAPI YAML
                        if (yamlData.openapi && yamlData.info && yamlData.info.version) {
                            return yamlData.info.version;
                        }

                        // For Swagger YAML
                        if (yamlData.swagger && yamlData.info && yamlData.info.version) {
                            return yamlData.info.version;
                        }

                    } catch (yamlError) {
                        console.log('Error parsing file content as both JSON and YAML for version extraction:', { jsonError, yamlError });
                    }
                }
            }

        } catch (error) {
            console.log('Error reading file content for version extraction:', error);
        }

        // Fallback to default version
        return '1.0.0';
    }

    /**
     * Create operations from file based on specification type
     */
    private async createOperationsFromFile(
        file: File,
        specificationType: ApiSpecificationType,
        specificationId: string
    ): Promise<any[]> {
        try {
            const content = await this.readFileContent(file);
            if (!content) {
                return [];
            }

            // If specification type is HTTP but content suggests AsyncAPI, correct it
            let actualSpecificationType = specificationType;
            if (specificationType === ApiSpecificationType.HTTP) {
                try {
                    let parsedContent: any;
                    try {
                        parsedContent = JSON.parse(content);
                    } catch (jsonError) {
                        try {
                            parsedContent = ContentParser.parseContent(content);
                        } catch (yamlError) {
                            // Keep original type if parsing fails
                        }
                    }

                    if (parsedContent && parsedContent.asyncapi) {
                        actualSpecificationType = ApiSpecificationType.ASYNC;
                    }
                } catch (error) {
                }
            }


            switch (actualSpecificationType) {
                case ApiSpecificationType.SOAP:
                    const wsdlData = await SoapSpecificationParser.parseWsdlContent(content);
                    return SoapSpecificationParser.createOperationsFromWsdl(wsdlData, specificationId);

                case ApiSpecificationType.GRPC:
                    const protoData = await ProtoSpecificationParser.parseProtoContent(content);
                    return ProtoSpecificationParser.createOperationsFromProto(protoData, specificationId);

                case ApiSpecificationType.GRAPHQL:
                    const graphqlData = await GraphQLSpecificationParser.parseGraphQLContent(content);
                    return GraphQLSpecificationParser.createOperationsFromGraphQL(graphqlData, specificationId);

                case ApiSpecificationType.HTTP:
                    // For OpenAPI, use OpenApiSpecificationParser directly
                    const openApiData = await OpenApiSpecificationParser.parseOpenApiContent(content);
                    return OpenApiSpecificationParser.createOperationsFromOpenApi(openApiData, specificationId);

                case ApiSpecificationType.ASYNC:
                    const asyncApiData = await AsyncApiSpecificationParser.parseAsyncApiContent(content);
                    return AsyncApiSpecificationParser.createOperationsFromAsyncApi(asyncApiData, specificationId);

                default:
                    return [];
            }
        } catch (error) {
            console.error('[SpecificationProcessorService] Failed to create operations:', error);
            return [];
        }
    }

}
