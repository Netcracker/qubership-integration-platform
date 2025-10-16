import { EMPTY_USER } from "../../response/chainApiUtils";

export interface ProtoData {
    type: 'PROTO';
    package: string;
    services: ProtoService[];
    messages: ProtoMessage[];
    imports: string[];
}

export interface ProtoService {
    name: string;
    methods: ProtoMethod[];
}

export interface ProtoMethod {
    name: string;
    input: string;
    output: string;
    comment: string;
}

export interface ProtoMessage {
    name: string;
    fields: ProtoField[];
}

export interface ProtoField {
    repeated: boolean;
    type: string;
    name: string;
    number: number;
}

export class ProtoSpecificationParser {

    /**
     * Parse Proto content and extract services and methods
     */
    static async parseProtoContent(content: string): Promise<ProtoData> {

        const protoData: ProtoData = {
            type: 'PROTO',
            package: '',
            services: [],
            messages: [],
            imports: []
        };

        // Extract package
        const packageMatch = content.match(/package\s+([a-zA-Z_][a-zA-Z0-9_.]*);/);
        if (packageMatch) {
            protoData.package = packageMatch[1];
        }

        // Extract service definitions
        const serviceMatches = content.matchAll(/service\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*{([\s\S]*?)}/g);
        for (const match of serviceMatches) {
            const serviceName = match[1];
            const serviceContent = match[2];

            const service: ProtoService = {
                name: serviceName,
                methods: []
            };

            // Extract methods from service with comments
            const methodMatches = serviceContent.matchAll(/rpc\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(([^)]*\))\s*returns\s*\(([^)]*\))/g);
            for (const methodMatch of methodMatches) {
                const methodName = methodMatch[1];
                const inputType = methodMatch[2].trim();
                const outputType = methodMatch[3].trim();

                // Clean up type names
                const cleanInputType = inputType.replace(/[()]/g, '').trim();
                const cleanOutputType = outputType.replace(/[()]/g, '').trim();

                // Extract comment before method
                const methodStartIndex = methodMatch.index!;
                const beforeMethod = serviceContent.substring(0, methodStartIndex);

                // Find comment directly before this RPC method
                const lines = beforeMethod.split('\n');
                let comment = '';

                // Find last comment before method
                for (let i = lines.length - 1; i >= 0; i--) {
                    const line = lines[i].trim();
                    if (line.startsWith('//')) {
                        comment = line.replace(/\/\/\s*/, '').trim();
                        break;
                    } else if (line.startsWith('/*') && line.endsWith('*/')) {
                        // Single line multi-line comment
                        comment = line.replace(/\/\*\s*|\s*\*\//g, '').trim();
                        break;
                    } else if (line !== '' && !line.startsWith('rpc') && !line.startsWith('*')) {
                        // If we encounter non-empty line that is not comment and not RPC, break search
                        break;
                    }
                }

                service.methods.push({
                    name: methodName,
                    input: cleanInputType,
                    output: cleanOutputType,
                    comment: comment
                });
            }

            protoData.services.push(service);
        }

        // Extract message definitions
        const messageMatches = content.matchAll(/message\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*{([\s\S]*?)}/g);
        for (const match of messageMatches) {
            const messageName = match[1];
            const messageContent = match[2];

            const message: ProtoMessage = {
                name: messageName,
                fields: []
            };

            // Extract fields from message
            const fieldMatches = messageContent.matchAll(/(?:repeated\s+)?([a-zA-Z_][a-zA-Z0-9_.]*)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(\d+);/g);
            for (const fieldMatch of fieldMatches) {
                message.fields.push({
                    repeated: fieldMatch[0].includes('repeated'),
                    type: fieldMatch[1],
                    name: fieldMatch[2],
                    number: parseInt(fieldMatch[3])
                });
            }

            protoData.messages.push(message);
        }

        // Extract import statements
        const importMatches = content.matchAll(/import\s+"([^"]+)";/g);
        for (const match of importMatches) {
            protoData.imports.push(match[1]);
        }

        return protoData;
    }

    /**
     * Create operations from Proto data
     */
    static createOperationsFromProto(protoData: ProtoData, specificationId: string): any[] {
        const operations: any[] = [];


        for (const service of protoData.services) {
            for (const method of service.methods) {
                const operation = {
                    id: `${specificationId}-${method.name}`,
                    name: method.name,
                    createdWhen: Date.now(),
                    modifiedWhen: Date.now(),
                    createdBy: {...EMPTY_USER},
                    modifiedBy: {...EMPTY_USER},
                    method: 'post',
                    path: `/${protoData.package}.${service.name}/${method.name}`,
                    specification: {
                        summary: method.comment || `${method.name} operation`,
                        description: `gRPC operation ${method.name} in service ${service.name}`,
                        operationId: `${protoData.package}.${service.name}.${method.name}`,
                        tags: [
                            {
                                name: service.name,
                                description: `Operations for ${service.name} service`
                            },
                            {
                                name: protoData.package,
                                description: `Operations in ${protoData.package} package`
                            }
                        ],
                        requestBody: {
                            content: {
                                "application/json": {
                                    "$id": `http://system.catalog/schemas/requests/${protoData.package}.${service.name}.${method.name}`,
                                    "$ref": `#/definitions/${protoData.package}.${method.input}`,
                                    "$schema": "http://json-schema.org/draft-07/schema#",
                                    definitions: {
                                        [`${protoData.package}.${method.input}`]: {
                                            type: "object",
                                            properties: {},
                                            additionalProperties: false
                                        }
                                    }
                                }
                            }
                        },
                        responses: {
                            "200": {
                                content: {
                                    "application/json": {
                                        "$id": `http://system.catalog/schemas/responses/${protoData.package}.${service.name}.${method.name}`,
                                        "$ref": `#/definitions/${protoData.package}.${method.output}`,
                                        "$schema": "http://json-schema.org/draft-07/schema#",
                                        definitions: {
                                            [`${protoData.package}.${method.output}`]: {
                                                type: "object",
                                                properties: {},
                                                additionalProperties: false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    requestSchema: {
                        "application/json": {
                            "$id": `http://system.catalog/schemas/requests/${protoData.package}.${service.name}.${method.name}`,
                            "$ref": `#/definitions/${protoData.package}.${method.input}`,
                            "$schema": "http://json-schema.org/draft-07/schema#",
                            definitions: {
                                [`${protoData.package}.${method.input}`]: {
                                    type: "object",
                                    properties: {},
                                    additionalProperties: false
                                }
                            }
                        }
                    },
                    responseSchemas: {
                        "200": {
                            "application/json": {
                                "$id": `http://system.catalog/schemas/responses/${protoData.package}.${service.name}.${method.name}`,
                                "$ref": `#/definitions/${protoData.package}.${method.output}`,
                                "$schema": "http://json-schema.org/draft-07/schema#",
                                definitions: {
                                    [`${protoData.package}.${method.output}`]: {
                                        type: "object",
                                        properties: {},
                                        additionalProperties: false
                                    }
                                }
                            }
                        }
                    }
                };

                operations.push(operation);
            }
        }

        return operations;
    }
}
