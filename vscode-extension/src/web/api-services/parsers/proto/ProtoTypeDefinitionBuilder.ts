import * as protobuf from 'protobufjs';
import type { JsonSchema } from './ProtoTypes';

export class ProtoTypeDefinitionBuilder {
    static build(root: protobuf.Root): Record<string, JsonSchema> {
        const definitions: Record<string, JsonSchema> = {};
        Object.assign(definitions, this.createBuiltinTypes());
        this.collectDefinitions(root, definitions);
        return definitions;
    }

    private static collectDefinitions(
        namespace: protobuf.NamespaceBase,
        definitions: Record<string, JsonSchema>
    ): void {
        const nestedArray = namespace.nestedArray ?? [];
        for (const nested of nestedArray) {
            if (nested instanceof protobuf.Type) {
                const fullName = this.normalizeFullName(nested.fullName);
                definitions[fullName] = this.buildMessageDefinition(nested);
                this.collectDefinitions(nested, definitions);
            } else if (nested instanceof protobuf.Enum) {
                const fullName = this.normalizeFullName(nested.fullName);
                definitions[fullName] = this.buildEnumDefinition(nested);
            } else if (nested instanceof protobuf.Namespace) {
                this.collectDefinitions(nested, definitions);
            }
        }
    }

    private static buildMessageDefinition(message: protobuf.Type): JsonSchema {
        const definition: JsonSchema = {
            type: 'object',
            properties: {},
            additionalProperties: false
        };

        const comment = message.comment;
        if (comment) {
            (definition as Record<string, unknown>).description = comment;
        }

        const fieldsByName = new Map<string, protobuf.Field>();
        message.fieldsArray.forEach((field) => fieldsByName.set(field.name, field));
        message.oneofsArray?.forEach((oneof) => {
            oneof.fieldsArray.forEach((field) => fieldsByName.set(field.name, field));
        });

        const properties: Record<string, JsonSchema> = {};
        const required: string[] = [];

        for (const field of fieldsByName.values()) {
            const jsonName = this.getJsonName(field);
            properties[jsonName] = this.buildFieldSchema(field);
            if (field.required) {
                required.push(jsonName);
            }
        }

        (definition as Record<string, unknown>).properties = properties;
        if (required.length > 0) {
            (definition as Record<string, unknown>).required = required;
        }

        return definition;
    }

    private static buildFieldSchema(field: protobuf.Field): JsonSchema {
        if (field.map) {
            return {
                type: 'object',
                additionalProperties: this.buildTypeNode(field.type, field.resolvedType)
            };
        }

        const node = this.buildTypeNode(field.type, field.resolvedType);

        if (field.repeated) {
            return {
                type: 'array',
                items: node
            };
        }

        return node;
    }

    private static buildTypeNode(typeName: string, resolvedType: protobuf.ReflectionObject | null | undefined): JsonSchema {
        switch (typeName) {
            case 'double':
            case 'float':
            case 'int32':
            case 'int64':
            case 'uint32':
            case 'uint64':
            case 'sint32':
            case 'sint64':
            case 'fixed32':
            case 'fixed64':
            case 'sfixed32':
            case 'sfixed64':
            case 'bytes':
                return this.buildReferenceType(typeName);
            case 'bool':
                return { type: 'boolean' };
            case 'string':
                return { type: 'string' };
            default: {
                if (resolvedType && 'fullName' in resolvedType) {
                    const fullName = this.normalizeFullName((resolvedType as protobuf.ReflectionObject).fullName ?? typeName);
                    return this.buildReferenceType(fullName);
                }
                const normalized = this.normalizeTypeName(typeName);
                return this.buildReferenceType(normalized);
            }
        }
    }

    private static buildEnumDefinition(enumType: protobuf.Enum): JsonSchema {
        const values = enumType.values ? Object.keys(enumType.values) : [];
        const definition: JsonSchema = {
            type: 'string',
            enum: values
        };

        const comment = enumType.comment;
        if (comment) {
            (definition as Record<string, unknown>).description = comment;
        }

        return definition;
    }

    private static buildReferenceType(typeName: string): JsonSchema {
        return {
            $ref: `#/definitions/${typeName}`
        };
    }

    private static createBuiltinTypes(): Record<string, JsonSchema> {
        return {
            float: this.createNumberSchema(),
            double: this.createNumberSchema(),
            int32: this.createIntegerSchema('int32'),
            int64: this.createIntegerSchema('int64'),
            uint32: this.createIntegerSchema('int32'),
            uint64: this.createIntegerSchema('int64'),
            sint32: this.createIntegerSchema('int32'),
            sint64: this.createIntegerSchema('int64'),
            fixed32: this.createIntegerSchema('int32'),
            fixed64: this.createIntegerSchema('int64'),
            sfixed32: this.createIntegerSchema('int32'),
            sfixed64: this.createIntegerSchema('int64'),
            bytes: this.createBytesSchema()
        };
    }

    private static createNumberSchema(): JsonSchema {
        return { type: 'number' };
    }

    private static createIntegerSchema(format: string): JsonSchema {
        return {
            type: 'number',
            format
        };
    }

    private static createBytesSchema(): JsonSchema {
        return {
            type: 'string',
            format: 'bytes'
        };
    }

    private static normalizeFullName(fullName: string): string {
        return fullName.startsWith('.') ? fullName.substring(1) : fullName;
    }

    private static normalizeTypeName(typeName: string): string {
        return typeName.startsWith('.') ? typeName.substring(1) : typeName;
    }

    private static getJsonName(field: protobuf.Field): string {
        const options = field.options as Record<string, unknown> | undefined;
        const optionName = options && typeof options['json_name'] === 'string' ? (options['json_name'] as string) : undefined;
        return optionName ?? field.name;
    }
}

