import { AsyncApiSchemaResolver, ResolvedSchema } from "./AsyncApiSchemaResolver";

export interface ResolvedOperationData {
    specification: Record<string, unknown>;
    requestSchemas: Record<string, unknown>;
    responseSchemas: Record<string, unknown>;
}

type AsyncOperationObject = Record<string, any> | undefined;
type AsyncComponents = Record<string, any> | undefined;
type AsyncChannel = Record<string, any> | undefined;

export class AsyncApiOperationResolver {
    private readonly schemaResolver: AsyncApiSchemaResolver;

    constructor(schemaResolver: AsyncApiSchemaResolver = new AsyncApiSchemaResolver()) {
        this.schemaResolver = schemaResolver;
    }

    resolve(
        protocol: string,
        channelName: string,
        operationId: string,
        channel: AsyncChannel,
        operationObject: AsyncOperationObject,
        components: AsyncComponents
    ): ResolvedOperationData {
        if (!operationObject) {
            return this.buildEmptySchemas();
        }

        switch (protocol.toLowerCase()) {
            case "kafka":
            case "kafka-streams":
                return this.resolveKafkaSchemas(channelName, operationId, operationObject, components);
            case "amqp":
            case "rabbit":
            case "rabbitmq":
                return this.resolveAmqpSchemas(channel, operationObject);
            default:
                return this.buildEmptySchemas();
        }
    }

    private resolveKafkaSchemas(
        channelName: string,
        operationId: string,
        operationObject: AsyncOperationObject,
        components: AsyncComponents
    ): ResolvedOperationData {
        const responseSchemas = this.resolveMessage(operationId, operationObject?.message, components);
        return {
            specification: this.buildKafkaSpecification(channelName, operationObject),
            requestSchemas: {},
            responseSchemas,
        };
    }

    private resolveAmqpSchemas(
        channel: AsyncChannel,
        operationObject: AsyncOperationObject
    ): ResolvedOperationData {
        return {
            specification: this.buildAmqpSpecification(channel),
            requestSchemas: {},
            responseSchemas: {},
        };
    }

    private resolveMessage(
        operationId: string,
        message: AsyncOperationObject,
        components: AsyncComponents
    ): Record<string, unknown> {
        if (!message) {
            return {};
        }

        if (message.payload && typeof message.payload === "object") {
            return {
                payload: this.buildPayloadSchema(message.payload),
            };
        }

        if (typeof message.$ref === "string") {
            const resolved = this.resolveRef(message.$ref, components);
            if (resolved) {
                return {
                    [resolved.name || operationId]: resolved.schema,
                };
            }
        }

        const compositeRefs = message.oneOf || message.allOf || message.anyOf;
        if (Array.isArray(compositeRefs)) {
            return this.resolveCompositeRefs(compositeRefs, components);
        }

        return {};
    }

    private resolveCompositeRefs(refs: any[], components: AsyncComponents): Record<string, unknown> {
        const resolvedSchemas: Record<string, unknown> = {};

        refs.forEach((refObject) => {
            if (refObject && typeof refObject.$ref === "string") {
                const resolved = this.resolveRef(refObject.$ref, components);
                if (resolved) {
                    resolvedSchemas[resolved.name] = resolved.schema;
                }
            }
        });

        return resolvedSchemas;
    }

    private resolveRef(ref: string, components: AsyncComponents): ResolvedSchema | null {
        if (!components) {
            return null;
        }
        return this.schemaResolver.resolveRef(ref, components);
    }

    private buildPayloadSchema(payload: Record<string, any>): Record<string, any> {
        const schema: Record<string, any> = {};

        if (payload[TYPE_FIELD_NAME]) {
            schema[TYPE_FIELD_NAME] = payload[TYPE_FIELD_NAME];
        }

        const properties = payload[PROPERTIES_FIELD_NAME];
        if (properties && typeof properties === "object") {
            schema[PROPERTIES_FIELD_NAME] = {};
            Object.entries(properties).forEach(([propertyName, propertyValue]) => {
                schema[PROPERTIES_FIELD_NAME][propertyName] = this.extractPropertySchema(propertyValue);
            });
        }

        return schema;
    }

    private extractPropertySchema(propertyValue: any): Record<string, any> {
        const propertySchema: Record<string, any> = {};

        if (propertyValue && typeof propertyValue === "object") {
            if (propertyValue[TYPE_FIELD_NAME]) {
                propertySchema[TYPE_FIELD_NAME] = propertyValue[TYPE_FIELD_NAME];
            }
            if (propertyValue[FORMAT_FIELD_NAME]) {
                propertySchema[FORMAT_FIELD_NAME] = propertyValue[FORMAT_FIELD_NAME];
            }
        }

        return propertySchema;
    }

    private buildKafkaSpecification(channelName: string, operationObject: AsyncOperationObject): Record<string, unknown> {
        const specification: Record<string, unknown> = {
            topic: channelName,
        };

        const classifier =
            operationObject?.["x-maas-classifier-name"] ??
            operationObject?.maasClassifierName;

        if (typeof classifier === "string" && classifier.trim().length > 0) {
            specification.maasClassifierName = classifier;
        }

        return specification;
    }

    private buildAmqpSpecification(channel: AsyncChannel): Record<string, unknown> {
        if (!channel || typeof channel !== "object") {
            return {};
        }

        const bindings = channel.bindings;
        const specification: Record<string, unknown> = {};

        if (bindings && typeof bindings === "object") {
            const amqpBinding = bindings.amqp;
            if (amqpBinding && typeof amqpBinding === "object") {
                const username = amqpBinding.userId;
                if (typeof username === "string" && username.length > 0) {
                    specification.username = username;
                }

                const queueName = amqpBinding.queue?.name;
                if (typeof queueName === "string" && queueName.length > 0) {
                    specification.queue = queueName;
                }

                const exchangeName = amqpBinding.exchange?.name;
                if (typeof exchangeName === "string" && exchangeName.length > 0) {
                    specification.exchangeName = exchangeName;
                }
            }
        }

        return specification;
    }

    private buildEmptySchemas(): ResolvedOperationData {
        return {
            specification: {},
            requestSchemas: {},
            responseSchemas: {},
        };
    }
}

const TYPE_FIELD_NAME = "type";
const FORMAT_FIELD_NAME = "format";
const PROPERTIES_FIELD_NAME = "properties";

