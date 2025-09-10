import { ExtensionContext, Uri } from "vscode";
import { SpecificationGroup, Specification } from "../response/apiTypes";
import { ApiSpecificationType } from "./importApiTypes";
import { SerializedFile } from "./importApiTypes";
import { 
    SoapSpecificationParser, 
    ProtoSpecificationParser, 
    GraphQLSpecificationParser, 
    OpenApiSpecificationParser,
    AsyncApiSpecificationParser
} from "./parsers";
import { EMPTY_USER } from "../response/chainApi";

const vscode = require('vscode');

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
            createdWhen: Date.now(),
            createdBy: {...EMPTY_USER},
            modifiedWhen: Date.now(),
            modifiedBy: {...EMPTY_USER},
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
     * Detect AsyncAPI protocol from files
     */
    async detectAsyncApiProtocol(files: File[]): Promise<string | null> {
        try {
            for (const file of files) {
                if (file.name.includes('asyncapi') || file.name.endsWith('.yaml') || file.name.endsWith('.yml') || file.name.endsWith('.json')) {
                    const content = await this.readFileContent(file);
                    if (content) {
                        const protocol = this.extractAddressFromSwaggerData(content);
                        if (protocol) {
                            return protocol;
                        }
                    }
                }
            }
            return null;
        } catch (error) {
            return null;
        }
    }

    /**
     * Read file content
     */
    private async readFileContent(file: File): Promise<string | null> {
        try {
            return new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = (e) => {
                    resolve(e.target?.result as string || null);
                };
                reader.onerror = () => reject(new Error('Failed to read file'));
                reader.readAsText(file);
            });
        } catch (error) {
            return null;
        }
    }

    /**
     * Extract address from swagger data
     */
    private extractAddressFromSwaggerData(specData: any): string | null {
        
        // For SOAP/WSDL files
        if (specData.type === 'WSDL') {
            if (specData.service && specData.service.address) {
                const address = specData.service.address;
                return address;
            } else {
                const address = 'https://soap.example.com/ws';
                return address;
            }
        }
        
        // For Swagger 2.0
        if (specData.swagger) {
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
        if (specData.openapi) {
            const servers = specData.servers;
            if (servers && servers.length > 0) {
                const address = servers[0].url;
                return address;
            }
        }
        
        // For AsyncAPI
        if (specData.asyncapi) {
            // Check servers first (priority over x-protocol)
            const servers = specData.servers;
            if (servers && Object.keys(servers).length > 0) {
                const firstServerKey = Object.keys(servers)[0];
                const server = servers[firstServerKey];
                if (server.url) {
                    const address = server.url;
                    return address;
                }
            }
            
            // Check x-protocol if no servers
            if (specData['x-protocol']) {
                const protocol = specData['x-protocol'];
                return protocol;
            }
        }
        
        return null;
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
                        const yaml = require('yaml');
                        const yamlData = yaml.parse(content);
                        
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
                            const yaml = require('yaml');
                            parsedContent = yaml.parse(content);
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
            return [];
        }
    }

}
