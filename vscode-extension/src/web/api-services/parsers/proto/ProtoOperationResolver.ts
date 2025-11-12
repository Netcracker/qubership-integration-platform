import { ProtoData, ProtoService, ResolvedProtoOperation, JsonSchema } from './ProtoTypes';

export class ProtoOperationResolver {
    private readonly schemaBuilder: ProtoSchemaBuilder;

    constructor(private readonly protoData: ProtoData) {
        this.schemaBuilder = new ProtoSchemaBuilder(protoData.typeDefinitions);
    }

    resolve(): ResolvedProtoOperation[] {
        const operations: ResolvedProtoOperation[] = [];
        const javaPackage = this.protoData.javaPackage ?? this.protoData.packageName;

        for (const service of this.protoData.services) {
            const path = buildFullyQualifiedName(javaPackage, service.name);
            for (const method of service.methods) {
                const operationId = method.operationId;
                const requestSchema = this.schemaBuilder.buildSchema(
                    method.requestType,
                    'requests',
                    this.protoData.packageName,
                    operationId
                );
                const responseSchema = this.schemaBuilder.buildSchema(
                    method.responseType,
                    'responses',
                    this.protoData.packageName,
                    operationId
                );

                operations.push({
                    operationId,
                    rpcName: method.name,
                    path,
                    summary: method.comment,
                    requestSchema,
                    responseSchema
                });
            }
        }

        return operations;
    }
}

export function buildProtoOperationSpecification(
    operation: ResolvedProtoOperation,
    requestSchema: JsonSchema,
    responseSchema: JsonSchema
): Record<string, unknown> {
    const specification: Record<string, unknown> = {
        operationId: operation.operationId
    };

    if (operation.summary) {
        specification.summary = operation.summary;
    }

    specification.responses = {
        '200': {
            content: {
                'application/json': {
                    schema: cloneSchema(responseSchema)
                }
            }
        }
    };

    specification.requestBody = {
        content: {
            'application/json': {
                schema: cloneSchema(requestSchema)
            }
        }
    };

    return specification;
}

class ProtoSchemaBuilder {
    constructor(private readonly typeDefinitions: Record<string, JsonSchema>) {}

    buildSchema(typeName: string, kind: 'requests' | 'responses', packageName: string, operationId: string): JsonSchema {
        const fullyQualifiedTypeName = ensureFullyQualified(typeName, packageName);
        const schema: Record<string, unknown> = {
            $id: `http://system.catalog/schemas/${kind}/${buildFullyQualifiedName(packageName, operationId)}`,
            $schema: 'http://json-schema.org/draft-07/schema#',
            $ref: `#/definitions/${fullyQualifiedTypeName}`,
            definitions: {}
        };

        const definitions: Record<string, JsonSchema> = {};
        this.collectRelatedTypes(definitions, fullyQualifiedTypeName);
        schema.definitions = definitions;

        return schema as JsonSchema;
    }

    private collectRelatedTypes(target: Record<string, JsonSchema>, typeName: string): void {
        if (target[typeName]) {
            return;
        }

        const definition = this.typeDefinitions[typeName];
        if (!definition) {
            return;
        }

        target[typeName] = cloneSchema(definition);
        for (const referencedType of getReferencedTypeNames(definition)) {
            this.collectRelatedTypes(target, referencedType);
        }
    }
}

function getReferencedTypeNames(schema: JsonSchema | undefined): string[] {
    if (!schema || typeof schema !== 'object') {
        return [];
    }

    const record = schema as Record<string, unknown>;
    if ('$ref' in record && typeof record['$ref'] === 'string') {
        const reference = record['$ref'] as string;
        return [reference.substring(reference.lastIndexOf('/') + 1)];
    }

    if ('type' in record) {
        const typeValue = record.type;

        if (typeValue === 'object') {
            const properties = record.properties as Record<string, JsonSchema> | undefined;
            const additional = record.additionalProperties as JsonSchema | undefined;

            const referenced: string[] = [];

            if (properties) {
                for (const value of Object.values(properties)) {
                    referenced.push(...getReferencedTypeNames(value));
                }
            }

            if (additional) {
                referenced.push(...getReferencedTypeNames(additional));
            }

            return referenced;
        }

        if (typeValue === 'array') {
            const items = record.items as JsonSchema | undefined;
            return getReferencedTypeNames(items);
        }
    }

    return [];
}

function buildFullyQualifiedName(packageName: string | undefined, name: string): string {
    if (!packageName) {
        return name;
    }
    return `${packageName}.${name}`;
}

function ensureFullyQualified(typeName: string, defaultPackage: string): string {
    if (!typeName) {
        return typeName;
    }
    if (typeName.startsWith('.')) {
        return typeName.substring(1);
    }
    if (typeName.includes('.')) {
        return typeName;
    }
    return defaultPackage ? `${defaultPackage}.${typeName}` : typeName;
}

function cloneSchema<T extends JsonSchema>(schema: T): T {
    return JSON.parse(JSON.stringify(schema));
}

