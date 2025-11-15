import { SerializedFile, ApiSpecificationType } from "./importApiTypes";
import { FileConversionService } from "../services/FileConversionService";
import { ProtoSpecificationParser } from "./parsers/ProtoSpecificationParser";
import { ProtoOperationResolver } from "./parsers/proto/ProtoOperationResolver";
import type { ProtoData, ResolvedProtoOperation } from "./parsers/proto/ProtoTypes";

export interface ParsedSpecification {
    id: string;
    name: string;
    type: ApiSpecificationType;
    version?: string;
    description?: string;
    operations: ParsedOperation[];
    metadata: Record<string, any>;
    errors: string[];
}

export interface ParsedOperation {
    id: string;
    name: string;
    method: string;
    path?: string;
    description?: string;
    parameters: ParsedParameter[];
    responses: ParsedResponse[];
    tags?: string[];
    requestSchema?: Record<string, unknown>;
    responseSchemas?: Record<string, unknown>;
    requestStream?: boolean;
    responseStream?: boolean;
    rpcType?: 'unary' | 'client_streaming' | 'server_streaming' | 'bidirectional';
    metadata?: Record<string, unknown>;
}

export interface ParsedParameter {
    name: string;
    type: string;
    required: boolean;
    description?: string;
    location: 'query' | 'path' | 'header' | 'body';
}

export interface ParsedResponse {
    statusCode: string;
    description?: string;
    contentType?: string;
    schema?: any;
}

/**
 * Service for parsing various specification formats
 * Handles OpenAPI, AsyncAPI, GraphQL, gRPC, SOAP specifications
 */
export class SpecificationParserService {
    
    /**
     * Parse specification from SerializedFile
     */
    async parseSpecification(file: SerializedFile): Promise<ParsedSpecification> {
        try {
            const fileObj = FileConversionService.serializedFileToFile(file);
            const content = await this.readFileContent(fileObj);
            const type = this.detectSpecificationType(file);

            switch (type) {
                case ApiSpecificationType.HTTP:
                    return await this.parseOpenApiSpecification(content, file);
                case ApiSpecificationType.ASYNC:
                    return await this.parseAsyncApiSpecification(content, file);
                case ApiSpecificationType.GRAPHQL:
                    return await this.parseGraphQLSpecification(content, file);
                case ApiSpecificationType.GRPC:
                    return await this.parseGrpcSpecification(content, file);
                case ApiSpecificationType.SOAP:
                    return await this.parseSoapSpecification(content, file);
                default:
                    throw new Error(`Unsupported specification type: ${type}`);
            }
        } catch (error) {
            return this.createErrorSpecification(file, error instanceof Error ? error.message : 'Unknown error');
        }
    }

    /**
     * Parse multiple specifications
     */
    async parseSpecifications(files: SerializedFile[]): Promise<ParsedSpecification[]> {
        const results: ParsedSpecification[] = [];
        
        for (const file of files) {
            try {
                const parsed = await this.parseSpecification(file);
                results.push(parsed);
            } catch (error) {
                console.error(`Failed to parse specification ${file.name}:`, error);
                results.push(this.createErrorSpecification(file, error instanceof Error ? error.message : 'Unknown error'));
            }
        }

        return results;
    }

    /**
     * Detect specification type from file
     */
    private detectSpecificationType(file: SerializedFile): ApiSpecificationType {
        const extension = FileConversionService.getFileExtension(file.name).toLowerCase();
        
        switch (extension) {
            case '.wsdl':
            case '.xsd':
                return ApiSpecificationType.SOAP;
            case '.proto':
                return ApiSpecificationType.GRPC;
            case '.graphql':
            case '.gql':
                return ApiSpecificationType.GRAPHQL;
            case '.yaml':
            case '.yml':
            case '.json':
                // Need to check content to determine if it's OpenAPI or AsyncAPI
                return ApiSpecificationType.HTTP; // Default, will be refined by content analysis
            default:
                return ApiSpecificationType.HTTP;
        }
    }

    /**
     * Read file content
     */
    private async readFileContent(file: File): Promise<string> {
        const arrayBuffer = await file.arrayBuffer();
        const decoder = new TextDecoder('utf-8');
        return decoder.decode(arrayBuffer);
    }

    /**
     * Parse OpenAPI specification
     */
    private async parseOpenApiSpecification(content: string, file: SerializedFile): Promise<ParsedSpecification> {
        try {
            const spec = JSON.parse(content);
            const operations: ParsedOperation[] = [];

            // Extract operations from paths
            if (spec.paths) {
                for (const [path, pathItem] of Object.entries(spec.paths)) {
                    if (typeof pathItem === 'object' && pathItem !== null) {
                        for (const [method, operation] of Object.entries(pathItem)) {
                            if (typeof operation === 'object' && operation !== null && 
                                ['get', 'post', 'put', 'delete', 'patch', 'head', 'options'].includes(method.toLowerCase())) {
                                
                                const op = operation as any;
                                operations.push({
                                    id: op.operationId || `${method.toUpperCase()}_${path.replace(/[^a-zA-Z0-9]/g, '_')}`,
                                    name: op.summary || op.operationId || `${method.toUpperCase()} ${path}`,
                                    method: method.toUpperCase(),
                                    path: path,
                                    description: op.description,
                                    parameters: this.parseOpenApiParameters(op.parameters || []),
                                    responses: this.parseOpenApiResponses(op.responses || {}),
                                    tags: op.tags
                                });
                            }
                        }
                    }
                }
            }

            return {
                id: crypto.randomUUID(),
                name: spec.info?.title || FileConversionService.getFileNameWithoutExtension(file.name),
                type: ApiSpecificationType.HTTP,
                version: spec.info?.version,
                description: spec.info?.description,
                operations,
                metadata: {
                    openapi: spec.openapi,
                    info: spec.info,
                    servers: spec.servers
                },
                errors: []
            };
        } catch (error) {
            throw new Error(`Failed to parse OpenAPI specification: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Parse AsyncAPI specification
     */
    private async parseAsyncApiSpecification(content: string, file: SerializedFile): Promise<ParsedSpecification> {
        try {
            const spec = JSON.parse(content);
            const operations: ParsedOperation[] = [];

            // Extract operations from channels
            if (spec.channels) {
                for (const [channelName, channel] of Object.entries(spec.channels)) {
                    if (typeof channel === 'object' && channel !== null) {
                        const ch = channel as any;
                        
                        // Subscribe operation
                        if (ch.subscribe) {
                            operations.push({
                                id: `subscribe_${channelName}`,
                                name: ch.subscribe.summary || `Subscribe to ${channelName}`,
                                method: 'subscribe',
                                path: channelName,
                                description: ch.subscribe.description,
                                parameters: this.parseAsyncApiParameters(ch.subscribe.parameters || {}),
                                responses: this.parseAsyncApiResponses(ch.subscribe.message),
                                tags: ['asyncapi', 'subscribe']
                            });
                        }

                        // Publish operation
                        if (ch.publish) {
                            operations.push({
                                id: `publish_${channelName}`,
                                name: ch.publish.summary || `Publish to ${channelName}`,
                                method: 'publish',
                                path: channelName,
                                description: ch.publish.description,
                                parameters: this.parseAsyncApiParameters(ch.publish.parameters || {}),
                                responses: this.parseAsyncApiResponses(ch.publish.message),
                                tags: ['asyncapi', 'publish']
                            });
                        }
                    }
                }
            }

            return {
                id: crypto.randomUUID(),
                name: spec.info?.title || FileConversionService.getFileNameWithoutExtension(file.name),
                type: ApiSpecificationType.ASYNC,
                version: spec.info?.version,
                description: spec.info?.description,
                operations,
                metadata: {
                    asyncapi: spec.asyncapi,
                    info: spec.info,
                    servers: spec.servers
                },
                errors: []
            };
        } catch (error) {
            throw new Error(`Failed to parse AsyncAPI specification: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Parse GraphQL specification
     */
    private async parseGraphQLSpecification(content: string, file: SerializedFile): Promise<ParsedSpecification> {
        try {
            const operations: ParsedOperation[] = [];
            const lines = content.split('\n');
            let currentOperation: any = null;

            for (const line of lines) {
                const trimmed = line.trim();
                
                // Detect query
                if (trimmed.startsWith('query ')) {
                    const name = trimmed.split(' ')[1]?.split('(')[0] || 'Query';
                    operations.push({
                        id: `query_${name}`,
                        name: name,
                        method: 'query',
                        description: 'GraphQL query operation',
                        parameters: [],
                        responses: [],
                        tags: ['graphql', 'query']
                    });
                }
                // Detect mutation
                else if (trimmed.startsWith('mutation ')) {
                    const name = trimmed.split(' ')[1]?.split('(')[0] || 'Mutation';
                    operations.push({
                        id: `mutation_${name}`,
                        name: name,
                        method: 'mutation',
                        description: 'GraphQL mutation operation',
                        parameters: [],
                        responses: [],
                        tags: ['graphql', 'mutation']
                    });
                }
                // Detect subscription
                else if (trimmed.startsWith('subscription ')) {
                    const name = trimmed.split(' ')[1]?.split('(')[0] || 'Subscription';
                    operations.push({
                        id: `subscription_${name}`,
                        name: name,
                        method: 'subscription',
                        description: 'GraphQL subscription operation',
                        parameters: [],
                        responses: [],
                        tags: ['graphql', 'subscription']
                    });
                }
            }

            return {
                id: crypto.randomUUID(),
                name: FileConversionService.getFileNameWithoutExtension(file.name),
                type: ApiSpecificationType.GRAPHQL,
                operations,
                metadata: {
                    content: content.substring(0, 1000) // Store first 1000 chars for reference
                },
                errors: []
            };
        } catch (error) {
            throw new Error(`Failed to parse GraphQL specification: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Parse gRPC specification
     */
    private async parseGrpcSpecification(content: string, file: SerializedFile): Promise<ParsedSpecification> {
        try {
            const protoData = await ProtoSpecificationParser.parseProtoContent(content);
            const resolver = new ProtoOperationResolver(protoData);
            const resolvedOperations = resolver.resolve();
            if (resolvedOperations.length === 0) {
                throw new Error('No RPC methods found in gRPC specification');
            }
            const operations = resolvedOperations.map(operation =>
                this.buildParsedGrpcOperation(operation, protoData)
            );

            return {
                id: crypto.randomUUID(),
                name: FileConversionService.getFileNameWithoutExtension(file.name),
                type: ApiSpecificationType.GRPC,
                operations,
                metadata: {
                    packageName: protoData.packageName,
                    javaPackage: protoData.javaPackage,
                    serviceCount: protoData.services.length,
                    services: protoData.services.map(service => ({
                        name: service.name,
                        qualifiedName: service.qualifiedName,
                        methodCount: service.methods.length
                    }))
                },
                errors: []
            };
        } catch (error) {
            throw new Error(`Failed to parse gRPC specification: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    private buildParsedGrpcOperation(operation: ResolvedProtoOperation, protoData: ProtoData): ParsedOperation {
        const requestSchema = this.cloneJson(operation.requestSchema);
        const responseSchema = this.cloneJson(operation.responseSchema);

        const tags: string[] = ['grpc'];
        if (operation.serviceName) {
            tags.push(operation.serviceName.toLowerCase());
        }

        return {
            id: `rpc_${operation.operationId}`,
            name: operation.operationId,
            method: operation.rpcName,
            path: operation.path,
            description: operation.summary,
            parameters: [],
            responses: [
                {
                    statusCode: '200',
                    description: 'gRPC response',
                    contentType: 'application/json',
                    schema: responseSchema
                }
            ],
            tags,
            requestSchema,
            responseSchemas: {
                '200': responseSchema
            },
            requestStream: operation.requestStream,
            responseStream: operation.responseStream,
            rpcType: this.resolveGrpcCallType(operation.requestStream, operation.responseStream),
            metadata: {
                serviceName: operation.serviceName,
                packageName: protoData.packageName,
                javaPackage: protoData.javaPackage,
                requestType: operation.requestType,
                responseType: operation.responseType
            }
        };
    }

    private resolveGrpcCallType(requestStream: boolean, responseStream: boolean): ParsedOperation['rpcType'] {
        if (requestStream && responseStream) {
            return 'bidirectional';
        }
        if (requestStream) {
            return 'client_streaming';
        }
        if (responseStream) {
            return 'server_streaming';
        }
        return 'unary';
    }

    private cloneJson<T>(value: T): T {
        return JSON.parse(JSON.stringify(value));
    }

    /**
     * Parse SOAP specification
     */
    private async parseSoapSpecification(content: string, file: SerializedFile): Promise<ParsedSpecification> {
        try {
            const operations: ParsedOperation[] = [];
            
            // Simple XML parsing for WSDL
            const portTypeMatch = content.match(/<portType[^>]*name="([^"]*)"[^>]*>(.*?)<\/portType>/s);
            if (portTypeMatch) {
                const serviceName = portTypeMatch[1];
                const portTypeContent = portTypeMatch[2];
                
                const operationMatches = portTypeContent.match(/<operation[^>]*name="([^"]*)"[^>]*>/g);
                if (operationMatches) {
                    for (const operationMatch of operationMatches) {
                        const nameMatch = operationMatch.match(/name="([^"]*)"/);
                        if (nameMatch) {
                            const operationName = nameMatch[1];
                            operations.push({
                                id: `soap_${operationName}`,
                                name: operationName,
                                method: 'soap',
                                description: `SOAP operation in ${serviceName}`,
                                parameters: [],
                                responses: [],
                                tags: ['soap', serviceName.toLowerCase()]
                            });
                        }
                    }
                }
            }

            return {
                id: crypto.randomUUID(),
                name: FileConversionService.getFileNameWithoutExtension(file.name),
                type: ApiSpecificationType.SOAP,
                operations,
                metadata: {
                    content: content.substring(0, 1000) // Store first 1000 chars for reference
                },
                errors: []
            };
        } catch (error) {
            throw new Error(`Failed to parse SOAP specification: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Parse OpenAPI parameters
     */
    private parseOpenApiParameters(parameters: any[]): ParsedParameter[] {
        return parameters.map(param => ({
            name: param.name,
            type: param.schema?.type || 'string',
            required: param.required || false,
            description: param.description,
            location: param.in as 'query' | 'path' | 'header' | 'body'
        }));
    }

    /**
     * Parse OpenAPI responses
     */
    private parseOpenApiResponses(responses: any): ParsedResponse[] {
        return Object.entries(responses).map(([statusCode, response]) => ({
            statusCode,
            description: (response as any).description,
            contentType: (response as any).content ? Object.keys((response as any).content)[0] : undefined,
            schema: (response as any).content ? Object.values((response as any).content)[0] : undefined
        }));
    }

    /**
     * Parse AsyncAPI parameters
     */
    private parseAsyncApiParameters(parameters: any): ParsedParameter[] {
        return Object.entries(parameters).map(([name, param]) => ({
            name,
            type: (param as any).schema?.type || 'string',
            required: (param as any).required || false,
            description: (param as any).description,
            location: 'query' as const
        }));
    }

    /**
     * Parse AsyncAPI responses
     */
    private parseAsyncApiResponses(message: any): ParsedResponse[] {
        if (!message) {return [];}
        
        return [{
            statusCode: '200',
            description: message.description,
            contentType: message.contentType,
            schema: message.payload
        }];
    }

    /**
     * Create error specification
     */
    private createErrorSpecification(file: SerializedFile, error: string): ParsedSpecification {
        return {
            id: crypto.randomUUID(),
            name: FileConversionService.getFileNameWithoutExtension(file.name),
            type: ApiSpecificationType.HTTP,
            operations: [],
            metadata: {},
            errors: [error]
        };
    }
}
