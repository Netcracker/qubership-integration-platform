import { EMPTY_USER } from "../response/chainApiUtils";

export class QipSpecificationGenerator {
    private static readonly HTTP_METHODS = ['get', 'post', 'put', 'delete', 'patch', 'head', 'options'];
    
    /**
     * Creates QIP specification from OpenAPI 3.0 or Swagger 2.0
     */
    static createQipSpecificationFromOpenApi(openApiSpec: any, fileName: string): any {
        // Determine OpenAPI version and convert Swagger 2.0 to OpenAPI 3.0 if needed
        const isOpenApi3 = openApiSpec.openapi && openApiSpec.openapi.startsWith('3.');
        const isSwagger2 = openApiSpec.swagger && openApiSpec.swagger.startsWith('2.');

        if (!isOpenApi3 && !isSwagger2) {
            throw new Error('Invalid OpenAPI/Swagger specification');
        }

        // Convert Swagger 2.0 to OpenAPI 3.0 for unified processing
        let processedSpec = openApiSpec;
        if (isSwagger2) {
            processedSpec = this.convertSwagger2ToOpenApi3(openApiSpec);
        }

        const operations: any[] = [];
        const specId = this.generateId();
        
        if (processedSpec.paths) {
            for (const [path, pathItem] of Object.entries(processedSpec.paths)) {
                const pathItemObj = pathItem as any;
                
                for (const [method, operation] of Object.entries(pathItemObj)) {
                    if (this.HTTP_METHODS.includes(method.toLowerCase())) {
                        const operationObj = operation as any;
                        const qipOperation = this.createQipOperation(operationObj, method.toUpperCase(), path, processedSpec, specId);
                        operations.push(qipOperation);
                    }
                }
            }
        }

        return {
            $schema: "http://qubership.org/schemas/product/qip/specification",
            id: specId,
            name: openApiSpec.info?.version || '1.0.0',
            content: {
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                deprecated: false,
                version: openApiSpec.info?.version || '1.0.0',
                source: "IMPORTED",
                operations: operations
            },
            specificationSources: [{
                id: this.generateId(),
                name: fileName,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                sourceHash: this.calculateHash(JSON.stringify(openApiSpec)),
                fileName: `resources/source-${specId}/${fileName}`,
                mainSource: true
            }]
        };
    }

    /**
     * Creates QIP operation from OpenAPI operation
     */
    private static createQipOperation(operation: any, method: string, path: string, openApiSpec: any, specId: string): any {
        const operationId = operation.operationId || this.generateOperationId(method, path);
        
        return {
            id: `${specId}-${operationId}`,
            name: operationId,
            createdWhen: Date.now(),
            modifiedWhen: Date.now(),
            createdBy: { id: "", username: "" },
            modifiedBy: { id: "", username: "" },
            method: method,
            path: path,
            specification: this.reorderSpecificationFields(operation),
            requestSchema: this.createRequestSchema(operation, openApiSpec),
            responseSchemas: this.createResponseSchemas(operation, openApiSpec)
        };
    }

    /**
     * Reorders fields in specification object according to backend order
     */
    private static reorderSpecificationFields(operation: any): any {
        const orderedSpec: any = {};
        
        // Field order as in backend
        const fieldOrder = [
            'tags',
            'summary', 
            'security',
            'responses',
            'operationId',
            'requestBody',
            'x-codegen-request-body-name',
            'description',
            'parameters',
            'deprecated'
        ];
        
        // Add fields in correct order
        for (const field of fieldOrder) {
            if (operation[field] !== undefined) {
                if (field === 'parameters') {
                    // Handle parameters specially - wrap in schema
                    orderedSpec[field] = this.processParametersForSpecification(operation[field]);
                } else {
                    orderedSpec[field] = operation[field];
                }
            }
        }
        
        // Add remaining fields not in the list
        for (const key in operation) {
            if (!fieldOrder.includes(key) && operation[key] !== undefined) {
                orderedSpec[key] = operation[key];
            }
        }

        return orderedSpec;
    }

    /**
     * Processes parameters for specification object - wraps in schema
     */
    private static processParametersForSpecification(parameters: any[]): any[] {
        if (!parameters || !Array.isArray(parameters)) {
            return parameters;
        }

        return parameters.map((param: any) => this.processParameter(param));
    }

    /**
     * Processes single parameter
     */
    private static processParameter(param: any): any {
        const paramObj: any = {
            in: param.in,
            name: param.name
        };
        
        // If there's a schema, use it, otherwise create from type/format
        if (param.schema) {
            paramObj.schema = param.schema;
        } else if (param.type) {
            paramObj.schema = this.createSchemaFromType(param);
        }
        
        paramObj.required = param.required;
        paramObj.description = param.description;
        
        return paramObj;
    }

    /**
     * Creates schema from type and format
     */
    private static createSchemaFromType(param: any): any {
        const schema: any = {
            type: param.type,
            format: param.format
        };
        
        // Add additional properties to schema if they exist
        const additionalProps = ['minimum', 'maximum', 'minLength', 'maxLength', 'pattern', 'enum'];
        for (const prop of additionalProps) {
            if (param[prop] !== undefined) {
                schema[prop] = param[prop];
            }
        }
        
        return schema;
    }

    /**
     * Creates request schema
     */
    private static createRequestSchema(operation: any, openApiSpec: any): any {
        const requestSchema: any = {};
        
        // Handle parameters (only path, query, header parameters)
        if (operation.parameters && operation.parameters.length > 0) {
            const nonBodyParams = operation.parameters.filter((param: any) => 
                param.in && ['path', 'query', 'header'].includes(param.in)
            );
            if (nonBodyParams.length > 0) {
                requestSchema.parameters = nonBodyParams.map((param: any) => this.processParameter(param));
            }
        }
        
        // Handle requestBody (OpenAPI 3.0)
        if (operation.requestBody && operation.requestBody.content) {
            // Sort content types for consistent order
            const sortedContentTypes = Object.keys(operation.requestBody.content).sort();
            
            for (const contentType of sortedContentTypes) {
                const content = operation.requestBody.content[contentType] as any;
                if (content.schema) {
                    // For requestSchema always do full expansion
                    requestSchema[contentType] = this.expandSchema(content.schema, openApiSpec);
                }
            }
        }
        
        return requestSchema;
    }

    /**
     * Creates response schemas
     */
    private static createResponseSchemas(operation: any, openApiSpec: any): any {
        const responseSchemas: any = {};
        
        if (operation.responses) {
            // Sort status codes for consistent order
            const sortedStatusCodes = Object.keys(operation.responses).sort((a, b) => {
                // First numeric codes, then default
                if (a === 'default') {
                    return 1;
                }
                if (b === 'default') {
                    return -1;
                }
                return parseInt(a) - parseInt(b);
            });
            
            for (const statusCode of sortedStatusCodes) {
                const response = operation.responses[statusCode] as any;
                responseSchemas[statusCode] = {};
                
                if (response.content) {
                    // Sort content types for consistent order
                    const sortedContentTypes = Object.keys(response.content).sort();
                    
                    for (const contentType of sortedContentTypes) {
                        const content = response.content[contentType] as any;
                        if (content.schema) {
                            // For responseSchemas always do full expansion
                            responseSchemas[statusCode][contentType] = this.expandSchema(content.schema, openApiSpec);
                        }
                    }
                }
            }
        }
        
        return responseSchemas;
    }

    /**
     * Expands schema, resolving references and creating full JSON Schema
     */
    private static expandSchema(schema: any, openApiSpec: any, schemaName?: string): any {
        if (!schema) {
            return {};
        }
        
        // If it's a reference, resolve it and extract schema name
        if (schema.$ref) {
            const resolvedSchema = this.resolveRef(schema.$ref, openApiSpec);
            const refSchemaName = this.extractSchemaNameFromRef(schema.$ref);
            return this.expandSchema(resolvedSchema, openApiSpec, refSchemaName);
        }
        
        // Create full JSON Schema (only for root schemas)
        const expandedSchema = {
            $id: `http://system.catalog/schemas/#/components/schemas/${schemaName || schema.title || 'Schema'}`,
            $schema: "http://json-schema.org/draft-07/schema#",
            ...schema
        };
        
        // Fix required array format
        if (expandedSchema.required && Array.isArray(expandedSchema.required)) {
            expandedSchema.required = expandedSchema.required.map((item: any) => 
                typeof item === 'string' ? `${item}` : item
            );
        }
        
        // Recursively expand nested schemas (without adding $id and $schema)
        if (schema.properties) {
            expandedSchema.properties = {};
            for (const [key, prop] of Object.entries(schema.properties)) {
                expandedSchema.properties[key] = this.expandSchemaNested(prop, openApiSpec);
            }
        }
        
        if (schema.items) {
            expandedSchema.items = this.expandSchemaNested(schema.items, openApiSpec);
        }
        
        if (schema.allOf) {
            expandedSchema.allOf = schema.allOf.map((item: any) => this.expandSchemaNested(item, openApiSpec));
        }
        
        if (schema.anyOf) {
            expandedSchema.anyOf = schema.anyOf.map((item: any) => this.expandSchemaNested(item, openApiSpec));
        }
        
        if (schema.oneOf) {
            expandedSchema.oneOf = schema.oneOf.map((item: any) => this.expandSchemaNested(item, openApiSpec));
        }
        
        // Add definitions only if they are referenced in the schema
        const referencedSchemas = this.findReferencedSchemas(schema, openApiSpec);
        if (referencedSchemas.size > 0) {
            expandedSchema.definitions = {};
            
            // Add definitions for Swagger 2.0
            if (openApiSpec.definitions) {
                for (const [name, def] of Object.entries(openApiSpec.definitions)) {
                    if (referencedSchemas.has(name)) {
                        expandedSchema.definitions[name] = this.expandSchemaNested(def, openApiSpec);
                    }
                }
            }
            
            // Add components/schemas for OpenAPI 3.0
            if (openApiSpec.components && openApiSpec.components.schemas) {
                for (const [name, def] of Object.entries(openApiSpec.components.schemas)) {
                    if (referencedSchemas.has(name)) {
                        expandedSchema.definitions[name] = this.expandSchemaNested(def, openApiSpec);
                    }
                }
            }
        }
        
        return expandedSchema;
    }

    /**
     * Expands nested schemas without adding $id and $schema
     */
    private static expandSchemaNested(schema: any, openApiSpec: any): any {
        if (!schema) {
            return {};
        }
        
        // If it's a reference, resolve it
        if (schema.$ref) {
            const resolvedSchema = this.resolveRef(schema.$ref, openApiSpec);
            return this.expandSchemaNested(resolvedSchema, openApiSpec);
        }
        
        // Create schema without $id and $schema for nested elements
        const expandedSchema = { ...schema };
        
        // Recursively expand nested schemas
        if (schema.properties) {
            expandedSchema.properties = {};
            for (const [key, prop] of Object.entries(schema.properties)) {
                expandedSchema.properties[key] = this.expandSchemaNested(prop, openApiSpec);
            }
        }
        
        if (schema.items) {
            expandedSchema.items = this.expandSchemaNested(schema.items, openApiSpec);
        }
        
        if (schema.allOf) {
            expandedSchema.allOf = schema.allOf.map((item: any) => this.expandSchemaNested(item, openApiSpec));
        }
        
        if (schema.anyOf) {
            expandedSchema.anyOf = schema.anyOf.map((item: any) => this.expandSchemaNested(item, openApiSpec));
        }
        
        if (schema.oneOf) {
            expandedSchema.oneOf = schema.oneOf.map((item: any) => this.expandSchemaNested(item, openApiSpec));
        }
        
        return expandedSchema;
    }

    /**
     * Extracts schema name from reference
     */
    private static extractSchemaNameFromRef(ref: string): string | undefined {
        if (!ref.startsWith('#/')) {
            return undefined;
        }
        
        const path = ref.substring(2).split('/');
        // For components/schemas/SchemaName, return SchemaName
        if (path.length >= 3 && path[0] === 'components' && path[1] === 'schemas') {
            return path[2];
        }
        
        return undefined;
    }

    /**
     * Finds all referenced schemas in a given schema
     */
    private static findReferencedSchemas(schema: any, openApiSpec: any): Set<string> {
        const referenced = new Set<string>();
        
        const findRefs = (obj: any) => {
            if (!obj || typeof obj !== 'object') {
                return;
            }
            
            if (obj.$ref && typeof obj.$ref === 'string') {
                const ref = obj.$ref;
                if (ref.startsWith('#/definitions/')) {
                    const schemaName = ref.replace('#/definitions/', '');
                    referenced.add(schemaName);
                } else if (ref.startsWith('#/components/schemas/')) {
                    const schemaName = ref.replace('#/components/schemas/', '');
                    referenced.add(schemaName);
                }
            }
            
            // Recursively search in all properties
            for (const key in obj) {
                if (obj[key] && typeof obj[key] === 'object') {
                    findRefs(obj[key]);
                }
            }
        };
        
        findRefs(schema);
        return referenced;
    }

    /**
     * Resolves schema reference
     */
    private static resolveRef(ref: string, openApiSpec: any): any {
        if (!ref.startsWith('#/')) {
            return {};
        }
        
        const path = ref.substring(2).split('/');
        let current = openApiSpec;
        
        for (const segment of path) {
            if (current && typeof current === 'object' && segment in current) {
                current = current[segment];
            } else {
                return {};
            }
        }
        
        return current || {};
    }

    /**
     * Generates ID
     */
    private static generateId(): string {
        return crypto.randomUUID();
    }

    /**
     * Generates operation ID
     */
    private static generateOperationId(method: string, path: string): string {
        const pathParts = path.split('/').filter(part => part && !part.startsWith('{'));
        const operationName = pathParts.length > 0 ? pathParts[pathParts.length - 1] : 'operation';
        return `${method.toLowerCase()}${operationName.charAt(0).toUpperCase()}${operationName.slice(1)}`;
    }

    /**
     * Creates QIP specification from SOAP/WSDL data
     */
    static createQipSpecificationFromSoap(wsdlData: any, fileName: string): any {
        const operations: any[] = [];
        const specId = this.generateId();
        
        if (wsdlData.portType && wsdlData.portType.operations) {
            for (const operationName of wsdlData.portType.operations) {
                const operation = {
                    id: `${specId}-${operationName}`,
                    name: operationName,
                    createdWhen: Date.now(),
                    modifiedWhen: Date.now(),
                    createdBy: { id: "", username: "" },
                    modifiedBy: { id: "", username: "" },
                    method: 'POST',
                    path: wsdlData.service?.address || '',
                    specification: {
                        summary: `SOAP operation: ${operationName}`,
                        description: `SOAP operation ${operationName} from service ${wsdlData.service?.name || 'Unknown'}`,
                        tags: ['SOAP']
                    },
                    requestSchema: {
                        'application/soap+xml': {
                            type: 'object',
                            properties: {
                                soapEnvelope: {
                                    type: 'object',
                                    description: 'SOAP envelope for the request'
                                }
                            }
                        }
                    },
                    responseSchemas: {
                        '200': {
                            'application/soap+xml': {
                                type: 'object',
                                properties: {
                                    soapEnvelope: {
                                        type: 'object',
                                        description: 'SOAP envelope for the response'
                                    }
                                }
                            }
                        }
                    }
                };
                operations.push(operation);
            }
        }

        return {
            $schema: "http://qubership.org/schemas/product/qip/specification",
            id: specId,
            name: wsdlData.name || '1.0.0',
            content: {
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                deprecated: false,
                version: '1.0.0',
                source: "IMPORTED",
                operations: operations
            },
            specificationSources: [{
                id: this.generateId(),
                name: fileName,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                sourceHash: this.calculateHash(JSON.stringify(wsdlData)),
                fileName: `resources/source-${specId}/${fileName}`,
                mainSource: true
            }]
        };
    }

    /**
     * Creates QIP specification from Proto data
     */
    static createQipSpecificationFromProto(protoData: any, fileName: string): any {
        const operations: any[] = [];
        const specId = this.generateId();
        
        for (const service of protoData.services) {
            for (const method of service.methods) {
                const operation = {
                    id: `${specId}-${service.name}-${method.name}`,
                    name: `${service.name}.${method.name}`,
                    createdWhen: Date.now(),
                    modifiedWhen: Date.now(),
                    createdBy: { id: "", username: "" },
                    modifiedBy: { id: "", username: "" },
                    method: 'POST',
                    path: `/${service.name}/${method.name}`,
                    specification: {
                        summary: method.comment || `gRPC method: ${method.name}`,
                        description: `gRPC method ${method.name} from service ${service.name}. Input: ${method.input}, Output: ${method.output}`,
                        tags: ['gRPC', service.name]
                    },
                    requestSchema: {
                        'application/grpc': {
                            type: 'object',
                            properties: {
                                request: {
                                    type: 'object',
                                    description: `Request message of type ${method.input}`
                                }
                            }
                        }
                    },
                    responseSchemas: {
                        '200': {
                            'application/grpc': {
                                type: 'object',
                                properties: {
                                    response: {
                                        type: 'object',
                                        description: `Response message of type ${method.output}`
                                    }
                                }
                            }
                        }
                    }
                };
                operations.push(operation);
            }
        }

        return {
            $schema: "http://qubership.org/schemas/product/qip/specification",
            id: specId,
            name: protoData.package || '1.0.0',
            content: {
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                deprecated: false,
                version: '1.0.0',
                source: "IMPORTED",
                operations: operations
            },
            specificationSources: [{
                id: this.generateId(),
                name: fileName,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                sourceHash: this.calculateHash(JSON.stringify(protoData)),
                fileName: `resources/source-${specId}/${fileName}`,
                mainSource: true
            }]
        };
    }

    /**
     * Creates QIP specification from GraphQL data
     */
    static createQipSpecificationFromGraphQL(graphqlData: any, fileName: string): any {
        const operations: any[] = [];
        const specId = this.generateId();
        
        // Create operations from queries
        for (const query of graphqlData.queries) {
            const operation = {
                id: `${specId}-query-${query.name}`,
                name: query.name,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                method: 'POST',
                path: '/graphql',
                specification: {
                    summary: `GraphQL Query: ${query.name}`,
                    description: `GraphQL query ${query.name}. Returns: ${query.returnType}${query.arguments ? `, Arguments: ${query.arguments}` : ''}`,
                    tags: ['GraphQL', 'Query']
                },
                requestSchema: {
                    'application/json': {
                        type: 'object',
                        properties: {
                            query: {
                                type: 'string',
                                description: `GraphQL query string for ${query.name}`
                            },
                            variables: {
                                type: 'object',
                                description: 'GraphQL variables'
                            }
                        }
                    }
                },
                responseSchemas: {
                    '200': {
                        'application/json': {
                            type: 'object',
                            properties: {
                                data: {
                                    type: 'object',
                                    description: `Response data of type ${query.returnType}`
                                },
                                errors: {
                                    type: 'array',
                                    items: { type: 'string' },
                                    description: 'GraphQL errors'
                                }
                            }
                        }
                    }
                }
            };
            operations.push(operation);
        }
        
        // Create operations from mutations
        for (const mutation of graphqlData.mutations) {
            const operation = {
                id: `${specId}-mutation-${mutation.name}`,
                name: mutation.name,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                method: 'POST',
                path: '/graphql',
                specification: {
                    summary: `GraphQL Mutation: ${mutation.name}`,
                    description: `GraphQL mutation ${mutation.name}. Returns: ${mutation.returnType}${mutation.arguments ? `, Arguments: ${mutation.arguments}` : ''}`,
                    tags: ['GraphQL', 'Mutation']
                },
                requestSchema: {
                    'application/json': {
                        type: 'object',
                        properties: {
                            query: {
                                type: 'string',
                                description: `GraphQL mutation string for ${mutation.name}`
                            },
                            variables: {
                                type: 'object',
                                description: 'GraphQL variables'
                            }
                        }
                    }
                },
                responseSchemas: {
                    '200': {
                        'application/json': {
                            type: 'object',
                            properties: {
                                data: {
                                    type: 'object',
                                    description: `Response data of type ${mutation.returnType}`
                                },
                                errors: {
                                    type: 'array',
                                    items: { type: 'string' },
                                    description: 'GraphQL errors'
                                }
                            }
                        }
                    }
                }
            };
            operations.push(operation);
        }
        
        // Create operations from subscriptions
        for (const subscription of graphqlData.subscriptions) {
            const operation = {
                id: `${specId}-subscription-${subscription.name}`,
                name: subscription.name,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                method: 'POST',
                path: '/graphql',
                specification: {
                    summary: `GraphQL Subscription: ${subscription.name}`,
                    description: `GraphQL subscription ${subscription.name}. Returns: ${subscription.returnType}${subscription.arguments ? `, Arguments: ${subscription.arguments}` : ''}`,
                    tags: ['GraphQL', 'Subscription']
                },
                requestSchema: {
                    'application/json': {
                        type: 'object',
                        properties: {
                            query: {
                                type: 'string',
                                description: `GraphQL subscription string for ${subscription.name}`
                            },
                            variables: {
                                type: 'object',
                                description: 'GraphQL variables'
                            }
                        }
                    }
                },
                responseSchemas: {
                    '200': {
                        'application/json': {
                            type: 'object',
                            properties: {
                                data: {
                                    type: 'object',
                                    description: `Response data of type ${subscription.returnType}`
                                },
                                errors: {
                                    type: 'array',
                                    items: { type: 'string' },
                                    description: 'GraphQL errors'
                                }
                            }
                        }
                    }
                }
            };
            operations.push(operation);
        }

        return {
            $schema: "http://qubership.org/schemas/product/qip/specification",
            id: specId,
            name: '1.0.0',
            content: {
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                deprecated: false,
                version: '1.0.0',
                source: "IMPORTED",
                operations: operations
            },
            specificationSources: [{
                id: this.generateId(),
                name: fileName,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                sourceHash: this.calculateHash(JSON.stringify(graphqlData)),
                fileName: `resources/source-${specId}/${fileName}`,
                mainSource: true
            }]
        };
    }

    /**
     * Creates QIP specification from AsyncAPI
     */
    static createQipSpecificationFromAsyncApi(asyncApiData: any, fileName: string): any {
        const operations: any[] = [];
        const specId = this.generateId();
        
        if (asyncApiData.channels) {
            Object.entries(asyncApiData.channels).forEach(([channelName, channel]: [string, any]) => {
                // Publish operations
                if (channel.publish) {
                    const operationId = channel.publish.operationId || `publish-${channelName}`;
                    const operation = {
                        id: `${specId}-${operationId}`,
                        name: operationId,
                        createdWhen: Date.now(),
                        modifiedWhen: Date.now(),
                        createdBy: {...EMPTY_USER},
                        modifiedBy: {...EMPTY_USER},
                        method: 'PUBLISH',
                        path: channelName,
                        specification: {
                            summary: channel.publish.summary || `${operationId} operation`,
                            operationId: operationId,
                            protocol: asyncApiData.info?.['x-protocol'] || 'unknown',
                            channel: channelName,
                            operation: 'publish',
                            message: channel.publish.message || {}
                        },
                        requestSchema: {
                            $id: `http://system.catalog/schemas/requests/${operationId}`,
                            $ref: `#/definitions/${operationId}Request`,
                            $schema: "http://json-schema.org/draft-07/schema#",
                            definitions: {
                                [`${operationId}Request`]: {
                                    type: "object",
                                    properties: {},
                                    additionalProperties: false
                                }
                            }
                        },
                        responseSchemas: {
                            $id: `http://system.catalog/schemas/responses/${operationId}`,
                            $ref: `#/definitions/${operationId}Response`,
                            $schema: "http://json-schema.org/draft-07/schema#",
                            definitions: {
                                [`${operationId}Response`]: {
                                    type: "object",
                                    properties: {},
                                    additionalProperties: false
                                }
                            }
                        }
                    };
                    operations.push(operation);
                }

                // Subscribe operations
                if (channel.subscribe) {
                    const operationId = channel.subscribe.operationId || `subscribe-${channelName}`;
                    const operation = {
                        id: `${specId}-${operationId}`,
                        name: operationId,
                        createdWhen: Date.now(),
                        modifiedWhen: Date.now(),
                        createdBy: {...EMPTY_USER},
                        modifiedBy: {...EMPTY_USER},
                        method: 'SUBSCRIBE',
                        path: channelName,
                        specification: {
                            summary: channel.subscribe.summary || `${operationId} operation`,
                            operationId: operationId,
                            protocol: asyncApiData.info?.['x-protocol'] || 'unknown',
                            channel: channelName,
                            operation: 'subscribe',
                            message: channel.subscribe.message || {}
                        },
                        requestSchema: {
                            $id: `http://system.catalog/schemas/requests/${operationId}`,
                            $ref: `#/definitions/${operationId}Request`,
                            $schema: "http://json-schema.org/draft-07/schema#",
                            definitions: {
                                [`${operationId}Request`]: {
                                    type: "object",
                                    properties: {},
                                    additionalProperties: false
                                }
                            }
                        },
                        responseSchemas: {
                            $id: `http://system.catalog/schemas/responses/${operationId}`,
                            $ref: `#/definitions/${operationId}Response`,
                            $schema: "http://json-schema.org/draft-07/schema#",
                            definitions: {
                                [`${operationId}Response`]: {
                                    type: "object",
                                    properties: {},
                                    additionalProperties: false
                                }
                            }
                        }
                    };
                    operations.push(operation);
                }
            });
        }

        return {
            $schema: "http://qubership.org/schemas/product/qip/specification",
            id: specId,
            name: asyncApiData.info?.title || fileName,
            content: {
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                deprecated: false,
                version: asyncApiData.info?.version || "1.0.0",
                source: "IMPORTED",
                operations: operations,
                parentId: ""
            },
            specificationSources: [{
                id: this.generateId(),
                name: fileName,
                createdWhen: Date.now(),
                modifiedWhen: Date.now(),
                createdBy: { id: "", username: "" },
                modifiedBy: { id: "", username: "" },
                sourceHash: this.calculateHash(JSON.stringify(asyncApiData)),
                fileName: `resources/source-${specId}/${fileName}`,
                mainSource: true
            }]
        };
    }

    /**
     * Converts Swagger 2.0 to OpenAPI 3.0
     */
    private static convertSwagger2ToOpenApi3(swagger2Spec: any): any {
        const openApi3Spec = {
            openapi: '3.0.0',
            info: swagger2Spec.info || {},
            servers: this.createServersFromSwagger2(swagger2Spec),
            paths: {},
            components: {
                schemas: {},
                securitySchemes: this.convertSecurityDefinitions(swagger2Spec.securityDefinitions)
            }
        };

        // Convert paths and operations
        if (swagger2Spec.paths) {
            for (const [path, pathItem] of Object.entries(swagger2Spec.paths)) {
                const openApiPathItem: any = {};
                
                for (const [method, operation] of Object.entries(pathItem as any)) {
                    if (this.HTTP_METHODS.includes(method.toLowerCase())) {
                        openApiPathItem[method] = this.convertSwagger2Operation(operation as any, swagger2Spec);
                    }
                }
                
                (openApi3Spec.paths as any)[path] = openApiPathItem;
            }
        }

        // Convert definitions to components/schemas
        if (swagger2Spec.definitions) {
            for (const [name, definition] of Object.entries(swagger2Spec.definitions)) {
                const def = JSON.parse(JSON.stringify(definition)); // Deep copy
                this.convertRefsInSchema(def);
                (openApi3Spec.components.schemas as any)[name] = def;
            }
        }

        return openApi3Spec;
    }

    /**
     * Creates servers array from Swagger 2.0 host/schemes/basePath
     */
    private static createServersFromSwagger2(swagger2Spec: any): any[] {
        if (!swagger2Spec.host) {
            return [];
        }

        const scheme = swagger2Spec.schemes?.[0] || 'https';
        const basePath = swagger2Spec.basePath || '';
        const url = `${scheme}://${swagger2Spec.host}${basePath}`;

        return [{ url }];
    }

    /**
     * Converts Swagger 2.0 operation to OpenAPI 3.0
     */
    private static convertSwagger2Operation(operation: any, swagger2Spec: any): any {
        const openApiOperation: any = {
            ...operation,
            responses: {}
        };

        // Convert responses
        if (operation.responses) {
            for (const [statusCode, response] of Object.entries(operation.responses)) {
                const openApiResponse: any = {
                    description: (response as any).description || ''
                };

                if ((response as any).schema) {
                    const schema = (response as any).schema;
                    // Convert #/definitions to #/components/schemas for OpenAPI 3.0
                    if (schema.$ref && schema.$ref.startsWith('#/definitions/')) {
                        schema.$ref = schema.$ref.replace('#/definitions/', '#/components/schemas/');
                    }
                    openApiResponse.content = {
                        'application/json': {
                            schema: schema
                        }
                    };
                }

                openApiOperation.responses[statusCode] = openApiResponse;
            }
        }

        // Convert parameters to requestBody
        if (operation.parameters) {
            this.convertParametersToRequestBody(operation, openApiOperation);
        }

        return openApiOperation;
    }

    /**
     * Converts Swagger 2.0 parameters to OpenAPI 3.0 requestBody
     */
    private static convertParametersToRequestBody(operation: any, openApiOperation: any): void {
        const bodyParams = operation.parameters.filter((p: any) => p.in === 'body');
        const formParams = operation.parameters.filter((p: any) => p.in === 'formData');
        const nonBodyParams = operation.parameters.filter((p: any) => 
            p.in !== 'body' && p.in !== 'formData'
        );
        
        if (bodyParams.length > 0) {
            this.convertBodyParameters(bodyParams, openApiOperation);
        } else if (formParams.length > 0) {
            this.convertFormParameters(formParams, openApiOperation);
        }
        
        // Keep only non-body parameters
        openApiOperation.parameters = nonBodyParams;
    }

    /**
     * Converts body parameters to requestBody
     */
    private static convertBodyParameters(bodyParams: any[], openApiOperation: any): void {
        const bodySchema = bodyParams[0].schema;
        // Convert #/definitions to #/components/schemas for OpenAPI 3.0
        if (bodySchema && bodySchema.$ref && bodySchema.$ref.startsWith('#/definitions/')) {
            bodySchema.$ref = bodySchema.$ref.replace('#/definitions/', '#/components/schemas/');
        }
        
        openApiOperation.requestBody = {
            content: {
                'application/json': {
                    schema: bodySchema
                },
                'application/xml': {
                    schema: bodySchema
                }
            },
            required: bodyParams[0].required || false,
            description: bodyParams[0].description || "Request body"
        };
        
        // Add x-codegen-request-body-name
        openApiOperation['x-codegen-request-body-name'] = 'body';
    }

    /**
     * Converts form parameters to requestBody
     */
    private static convertFormParameters(formParams: any[], openApiOperation: any): void {
        const formSchema = {
            type: 'object',
            properties: {},
            required: []
        };
        
        formParams.forEach((param: any) => {
            const propSchema: any = {
                type: param.type === 'file' ? 'string' : param.type,
                description: param.description
            };
            
            // Add format for file types
            if (param.type === 'file') {
                propSchema.format = 'binary';
            } else if (param.format) {
                propSchema.format = param.format;
            }
            
            // Handle array types
            if (param.type === 'array') {
                propSchema.type = 'array';
                propSchema.items = {
                    type: param.items?.type || 'string'
                };
                if (param.collectionFormat) {
                    propSchema.collectionFormat = param.collectionFormat;
                }
            }
            
            // Handle enum values
            if (param.enum && param.enum.length > 0) {
                propSchema.enum = param.enum;
            }
            
            // Handle minimum/maximum values
            if (param.minimum !== undefined) {
                propSchema.minimum = param.minimum;
            }
            if (param.maximum !== undefined) {
                propSchema.maximum = param.maximum;
            }
            
            (formSchema.properties as any)[param.name] = propSchema;
            
            if (param.required) {
                (formSchema.required as any[]).push(param.name);
            }
        });
        
        // Determine content type based on file parameters presence
        const hasFileParams = formParams.some((param: any) => param.type === 'file');
        const contentType = hasFileParams ? 'multipart/form-data' : 'application/x-www-form-urlencoded';
        
        // Remove empty required array
        if (formSchema.required.length === 0) {
            delete (formSchema as any).required;
        }
        
        openApiOperation.requestBody = {
            content: {
                [contentType]: {
                    schema: formSchema
                }
            },
            required: formParams.some((param: any) => param.required),
            description: "Form data"
        };
    }

    /**
     * Converts $ref links in schema
     */
    private static convertRefsInSchema(schema: any): void {
        if (!schema || typeof schema !== 'object') {
            return;
        }
        
        // Convert #/definitions to #/components/schemas for OpenAPI 3.0
        if (schema.$ref && schema.$ref.startsWith('#/definitions/')) {
            schema.$ref = schema.$ref.replace('#/definitions/', '#/components/schemas/');
        }
        
        // Convert #/parameters to #/components/parameters for OpenAPI 3.0
        if (schema.$ref && schema.$ref.startsWith('#/parameters/')) {
            schema.$ref = schema.$ref.replace('#/parameters/', '#/components/parameters/');
        }
        
        // Convert #/responses to #/components/responses for OpenAPI 3.0
        if (schema.$ref && schema.$ref.startsWith('#/responses/')) {
            schema.$ref = schema.$ref.replace('#/responses/', '#/components/responses/');
        }
        
        // Recursively process nested objects
        for (const key in schema) {
            if (schema[key] && typeof schema[key] === 'object') {
                this.convertRefsInSchema(schema[key]);
            }
        }
    }

    /**
     * Converts security definitions
     */
    private static convertSecurityDefinitions(securityDefinitions: any): any {
        if (!securityDefinitions) {
            return {};
        }
        
        const securitySchemes: any = {};
        for (const [name, scheme] of Object.entries(securityDefinitions)) {
            const schemeObj = scheme as any;
            securitySchemes[name] = {
                type: schemeObj.type,
                scheme: schemeObj.scheme,
                bearerFormat: schemeObj.bearerFormat,
                name: schemeObj.name,
                in: schemeObj.in
            };
        }
        return securitySchemes;
    }

    /**
     * Calculates string hash
     */
    private static calculateHash(str: string): string {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            const char = str.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash; // Convert to 32bit integer
        }
        return Math.abs(hash).toString(16);
    }
}
