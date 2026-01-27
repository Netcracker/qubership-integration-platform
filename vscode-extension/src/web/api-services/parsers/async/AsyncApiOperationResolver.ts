import {
  AsyncApiSchemaResolver,
  ResolvedSchema,
} from "./AsyncApiSchemaResolver";

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

  constructor(
    schemaResolver: AsyncApiSchemaResolver = new AsyncApiSchemaResolver(),
  ) {
    this.schemaResolver = schemaResolver;
  }

  resolve(
    protocol: string,
    channelName: string,
    operationId: string,
    channel: AsyncChannel,
    operationObject: AsyncOperationObject,
    components: AsyncComponents,
  ): ResolvedOperationData {
    if (!operationObject) {
      return this.buildEmptySchemas();
    }

    switch (protocol.toLowerCase()) {
      case "kafka":
      case "kafka-streams":
        return this.resolveKafkaSchemas(
          channelName,
          operationId,
          operationObject,
          components,
        );
      case "amqp":
      case "rabbit":
      case "rabbitmq":
        return this.resolveAmqpSchemas(
          channel,
          operationId,
          operationObject,
          components,
        );
      default:
        return this.buildEmptySchemas();
    }
  }

  private resolveKafkaSchemas(
    channelName: string,
    operationId: string,
    operationObject: AsyncOperationObject,
    components: AsyncComponents,
  ): ResolvedOperationData {
    const responseSchemas = this.resolveMessage(
      operationId,
      operationObject?.message,
      components,
    );
    return {
      specification: this.buildKafkaSpecification(channelName, operationObject),
      requestSchemas: {},
      responseSchemas,
    };
  }

  private resolveAmqpSchemas(
    channel: AsyncChannel,
    operationId: string,
    operationObject: AsyncOperationObject,
    components: AsyncComponents,
  ): ResolvedOperationData {
    return {
      specification: this.buildAmqpSpecification(channel),
      requestSchemas: {},
      responseSchemas: this.resolveMessage(
        operationId,
        operationObject?.message,
        components,
      ),
    };
  }

  private resolveMessage(
    operationId: string,
    message: AsyncOperationObject,
    components: AsyncComponents,
  ): Record<string, unknown> {
    if (!message) {
      return {};
    }

    const resolvedSchemas: Record<string, unknown> = {};

    const payloadSchema = this.cloneAndResolveSchemaNode(
      message.payload,
      components,
    );
    if (payloadSchema) {
      resolvedSchemas.payload = payloadSchema;
    }

    const headerSchema = this.cloneAndResolveSchemaNode(
      message.headers,
      components,
    );
    if (headerSchema) {
      resolvedSchemas.headers = headerSchema;
    }

    if (Object.keys(resolvedSchemas).length > 0) {
      return resolvedSchemas;
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

  private resolveCompositeRefs(
    refs: any[],
    components: AsyncComponents,
  ): Record<string, unknown> {
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

  private resolveRef(
    ref: string,
    components: AsyncComponents,
  ): ResolvedSchema | null {
    if (!components) {
      return null;
    }
    return this.schemaResolver.resolveRef(ref, components);
  }

  private cloneAndResolveSchemaNode(
    schemaNode: unknown,
    components: AsyncComponents,
  ): Record<string, unknown> | undefined {
    if (!isPlainObject(schemaNode)) {
      return undefined;
    }

    const cloned = deepClone(schemaNode);
    const resolved = this.resolveInlineRefs(cloned, components);

    return isPlainObject(resolved) ? resolved : undefined;
  }

  private resolveInlineRefs(
    value: unknown,
    components: AsyncComponents,
  ): unknown {
    if (!components) {
      return value;
    }

    if (Array.isArray(value)) {
      return value.map((item) => this.resolveInlineRefs(item, components));
    }

    if (!isPlainObject(value)) {
      return value;
    }

    if (typeof value.$ref === "string") {
      const resolved = this.resolveRef(value.$ref, components);
      if (resolved) {
        return resolved.schema;
      }
    }

    Object.entries(value).forEach(([key, child]) => {
      value[key] = this.resolveInlineRefs(child, components);
    });

    return value;
  }

  private buildKafkaSpecification(
    channelName: string,
    operationObject: AsyncOperationObject,
  ): Record<string, unknown> {
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

  private buildAmqpSpecification(
    channel: AsyncChannel,
  ): Record<string, unknown> {
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

function isPlainObject(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function deepClone<T>(value: T): T {
  if (value === undefined || value === null) {
    return value as T;
  }
  return JSON.parse(JSON.stringify(value));
}
