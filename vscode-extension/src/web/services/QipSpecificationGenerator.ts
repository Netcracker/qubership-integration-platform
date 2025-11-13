import { ProjectConfigService } from "./ProjectConfigService";
import { AsyncApiOperationResolver } from "../api-services/parsers/async/AsyncApiOperationResolver";
import { ProtoOperationResolver, buildProtoOperationSpecification } from "../api-services/parsers/proto/ProtoOperationResolver";
import type { ProtoData } from "../api-services/parsers/proto/ProtoTypes";
import type { WsdlParseResult } from "../api-services/parsers/soap/WsdlTypes";
import { SoapSpecificationParser } from "../api-services/parsers/SoapSpecificationParser";

export class QipSpecificationGenerator {
    private static readonly HTTP_METHODS = ['get', 'post', 'put', 'delete', 'patch', 'head', 'options'];

    private static buildSpecification(
        specId: string,
        name: string,
        version: string,
        operations: any[],
        fileName: string,
        sourceData: any,
        extraContent: Record<string, any> = {}
    ) {
        const config = ProjectConfigService.getConfig();
        return {
            $schema: config.schemaUrls.specification,
            id: specId,
            name,
            content: {
                deprecated: false,
                version,
                source: "MANUAL",
                operations,
                specificationSources: this.buildSpecificationSources(specId, fileName, sourceData),
                ...extraContent
            }
        };
    }

    private static buildSpecificationSources(specId: string, fileName: string, sourceData: any) {
        return [{
            id: this.generateId(),
            name: fileName,
            sourceHash: this.calculateHash(JSON.stringify(sourceData)),
            fileName: `source-${specId}/${fileName}`,
            mainSource: true
        }];
    }

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

        return this.buildSpecification(
            specId,
            openApiSpec.info?.version || '1.0.0',
            openApiSpec.info?.version || '1.0.0',
            operations,
            fileName,
            openApiSpec
        );
    }

    /**
     * Creates QIP operation from OpenAPI operation
     */
    private static createQipOperation(operation: any, method: string, path: string, openApiSpec: any, specId: string): any {
        const operationId = operation.operationId || this.generateOperationId(method, path);

        return {
            id: `${specId}-${operationId}`,
            name: operationId,
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
        } else {
            paramObj.schema = {};
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
     * while collecting referenced definitions for backward compatibility.
     */
    private static expandSchema(
        schema: any,
        openApiSpec: any,
        schemaName?: string,
        visited: Set<string> = new Set(),
        definitions: Record<string, any> = {}
    ): any {
        if (!schema) {
            return {};
        }

        if (schema.$ref) {
            const resolvedSchema = this.resolveRef(schema.$ref, openApiSpec);
            const refSchemaName = this.extractSchemaNameFromRef(schema.$ref);
            const newVisited = new Set(visited);
            newVisited.add(schema.$ref);
            return this.expandSchema(resolvedSchema, openApiSpec, refSchemaName, newVisited, definitions);
        }

        const expandedSchema = this.expandSchemaInternal(
            schema,
            openApiSpec,
            schemaName,
            new Set(visited),
            definitions,
            true
        );

        expandedSchema.definitions = Object.keys(definitions).length > 0 ? definitions : {};

        return expandedSchema;
    }

    private static expandSchemaInternal(
        schema: any,
        openApiSpec: any,
        schemaName: string | undefined,
        visited: Set<string>,
        definitions: Record<string, any>,
        isRoot: boolean
    ): any {
        if (!schema) {
            return {};
        }

        if (schema.$ref) {
            return this.convertRefToDefinition(schema.$ref, openApiSpec, visited, definitions);
        }

        const expanded: any = { ...schema };

        if (isRoot) {
            expanded.$id = `http://system.catalog/schemas/#/components/schemas/${schemaName || schema.title || 'Schema'}`;
            expanded.$schema = "http://json-schema.org/draft-07/schema#";
        }

        if (expanded.required && Array.isArray(expanded.required)) {
            expanded.required = expanded.required.map((item: any) =>
                typeof item === 'string' ? `${item}` : item
            );
        }

        if (schema.properties) {
            expanded.properties = {};
            for (const [key, prop] of Object.entries(schema.properties)) {
                expanded.properties[key] = this.expandProperty(prop, openApiSpec, visited, definitions);
            }
        }

        if (schema.items) {
            expanded.items = this.expandProperty(schema.items, openApiSpec, visited, definitions);
        }

        if (schema.allOf) {
            expanded.allOf = schema.allOf.map((item: any) => this.expandProperty(item, openApiSpec, visited, definitions));
        }

        if (schema.anyOf) {
            expanded.anyOf = schema.anyOf.map((item: any) => this.expandProperty(item, openApiSpec, visited, definitions));
        }

        if (schema.oneOf) {
            expanded.oneOf = schema.oneOf.map((item: any) => this.expandProperty(item, openApiSpec, visited, definitions));
        }

        if (schema.additionalProperties !== undefined) {
            expanded.additionalProperties = this.expandProperty(schema.additionalProperties, openApiSpec, visited, definitions);
        }

        return expanded;
    }

    private static expandProperty(
        prop: any,
        openApiSpec: any,
        visited: Set<string>,
        definitions: Record<string, any>
    ): any {
        if (!prop || typeof prop !== 'object') {
            return prop;
        }

        if (prop.$ref) {
            return this.convertRefToDefinition(prop.$ref, openApiSpec, visited, definitions);
        }

        const expanded: any = { ...prop };

        if (prop.properties) {
            expanded.properties = {};
            for (const [key, value] of Object.entries(prop.properties)) {
                expanded.properties[key] = this.expandProperty(value, openApiSpec, visited, definitions);
            }
        }

        if (prop.items) {
            expanded.items = this.expandProperty(prop.items, openApiSpec, visited, definitions);
        }

        if (prop.allOf) {
            expanded.allOf = prop.allOf.map((item: any) => this.expandProperty(item, openApiSpec, visited, definitions));
        }

        if (prop.anyOf) {
            expanded.anyOf = prop.anyOf.map((item: any) => this.expandProperty(item, openApiSpec, visited, definitions));
        }

        if (prop.oneOf) {
            expanded.oneOf = prop.oneOf.map((item: any) => this.expandProperty(item, openApiSpec, visited, definitions));
        }

        if (prop.additionalProperties !== undefined) {
            expanded.additionalProperties = this.expandProperty(prop.additionalProperties, openApiSpec, visited, definitions);
        }

        return expanded;
    }

    private static convertRefToDefinition(
        ref: string,
        openApiSpec: any,
        visited: Set<string>,
        definitions: Record<string, any>
    ): { $ref: string } {
        const schemaName = this.extractSchemaNameFromRef(ref);
        if (!schemaName) {
            return { $ref: ref };
        }

        if (!definitions[schemaName]) {
            if (visited.has(ref)) {
                return { $ref: `#/definitions/${schemaName}` };
            }

            const newVisited = new Set(visited);
            newVisited.add(ref);

            const isDefinitionRef = ref.startsWith('#/definitions/');
            const resolutionRef = isDefinitionRef ? `#/components/schemas/${schemaName}` : ref;
            const resolvedSchema = this.resolveRef(resolutionRef, openApiSpec);

            if (!resolvedSchema || Object.keys(resolvedSchema).length === 0) {
                return { $ref: `#/definitions/${schemaName}` };
            }

            definitions[schemaName] = this.expandSchemaInternal(
                resolvedSchema,
                openApiSpec,
                schemaName,
                newVisited,
                definitions,
                false
            );
        }

        return { $ref: `#/definitions/${schemaName}` };
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

        if (path.length >= 2 && path[0] === 'definitions') {
            return path[1];
        }

        return undefined;
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
    static createQipSpecificationFromSoap(wsdlData: WsdlParseResult, fileName: string): any {
        const specId = this.generateId();
        const operations = SoapSpecificationParser.createOperationsFromWsdl(wsdlData, specId);
        const specName = wsdlData.serviceNames[0] || fileName;

        return this.buildSpecification(
            specId,
            specName,
            "1.0.0",
            operations,
            fileName,
            wsdlData
        );
    }

    /**
     * Creates QIP specification from Proto data
     */
    static createQipSpecificationFromProto(protoData: ProtoData, fileName: string): any {
        const operations: any[] = [];
        const specId = this.generateId();
        const resolver = new ProtoOperationResolver(protoData);
        const resolvedOperations = resolver.resolve();

        for (const operation of resolvedOperations) {
            const requestSchema = this.cloneSchema(operation.requestSchema);
            const responseSchema = this.cloneSchema(operation.responseSchema);

            operations.push({
                id: `${specId}-${operation.operationId}`,
                name: operation.operationId,
                method: operation.rpcName,
                path: operation.path,
                specification: buildProtoOperationSpecification(operation, requestSchema, responseSchema),
                requestSchema: {
                    "application/json": requestSchema
                },
                responseSchemas: {
                    "200": {
                        "application/json": responseSchema
                    }
                }
            });
        }

        return this.buildSpecification(
            specId,
            protoData.packageName || fileName,
            "1.0.0",
            operations,
            fileName,
            protoData
        );
    }

    private static cloneSchema<T>(schema: T): T {
        return JSON.parse(JSON.stringify(schema));
    }

    /**
     * Creates QIP specification from GraphQL data
     */
    static createQipSpecificationFromGraphQL(graphqlData: any, fileName: string): any {
        const operations: any[] = [];
        const specId = this.generateId();

        if (graphqlData.queries) {
            for (const query of graphqlData.queries) {
                operations.push({
                    id: `${specId}-query-${query.name}`,
                    name: query.name,
                    method: 'query',
                    path: query.name,
                    specification: {
                        operation: query.sdl
                    }
                });
            }
        }

        if (graphqlData.mutations) {
            for (const mutation of graphqlData.mutations) {
                operations.push({
                    id: `${specId}-mutation-${mutation.name}`,
                    name: mutation.name,
                    method: 'mutation',
                    path: mutation.name,
                    specification: {
                        operation: mutation.sdl
                    }
                });
            }
        }

        const schema = graphqlData.schema || '';

        return this.buildSpecification(
            specId,
            '1.0.0',
            '1.0.0',
            operations,
            fileName,
            {
                ...graphqlData,
                schema
            }
        );
    }

    /**
     * Creates QIP specification from AsyncAPI
     */
    static createQipSpecificationFromAsyncApi(asyncApiData: any, fileName: string): any {
        const operations: any[] = [];
        const specId = this.generateId();
        const operationResolver = new AsyncApiOperationResolver();

        if (asyncApiData.channels) {
            Object.entries(asyncApiData.channels).forEach(([channelName, channel]: [string, any]) => {
                const protocol = asyncApiData.info?.['x-protocol'] || 'unknown';
                const buildAsyncOperation = (
                    opType: 'publish' | 'subscribe',
                    channelNameLocal: string,
                    op: any
                ) => {
                    const operationId = op.operationId || `${opType}-${channelNameLocal}`;
                    const resolvedData = operationResolver.resolve(
                        protocol,
                        channelNameLocal,
                        operationId,
                        channel,
                        op,
                        asyncApiData.components
                    );
                    return {
                        id: `${specId}-${operationId}`,
                        name: operationId,
                        method: opType.toUpperCase(),
                        path: channelNameLocal,
                        specification: resolvedData.specification,
                        requestSchema: resolvedData.requestSchemas,
                        responseSchemas: resolvedData.responseSchemas
                    };
                };

                if (channel.publish) {
                    operations.push(buildAsyncOperation('publish', channelName, channel.publish));
                }

                if (channel.subscribe) {
                    operations.push(buildAsyncOperation('subscribe', channelName, channel.subscribe));
                }
            });
        }

        return this.buildSpecification(
            specId,
            asyncApiData.info?.title || fileName,
            asyncApiData.info?.version || "1.0.0",
            operations,
            fileName,
            asyncApiData,
            { parentId: "" }
        );
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
