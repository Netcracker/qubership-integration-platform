import {
  AsyncApiChannel,
  AsyncApiOperationObject,
} from "../parserTypes";
import { AsyncApiSchemaResolver } from "./AsyncApiSchemaResolver";
import { deepClone } from "./asyncJsonUtils";

const MESSAGES_PREFIX = "#/components/messages/";
const PAYLOAD_FIELD_NAME = "payload";

export interface ResolvedOperationMessages {
  requestSchema: Record<string, unknown>;
  responseSchemas: Record<string, unknown>;
}

/**
 * Protocol-specific AsyncAPI resolver. Mirrors the backend
 * `AsyncApiSpecificationResolver` contract (Kafka / AMQP implementations).
 */
export interface AsyncApiSpecificationResolver {
  getOperationObjects(channel: AsyncApiChannel): AsyncApiOperationObject[];
  getSpecificationJsonNode(
    channelName: string,
    channel: AsyncApiChannel,
    operationObject: AsyncApiOperationObject,
  ): Record<string, unknown>;
  getMethod(
    channel: AsyncApiChannel,
    operationObject: AsyncApiOperationObject,
  ): string;
  setUpOperationMessages(
    operationObject: AsyncApiOperationObject,
    components: unknown,
  ): ResolvedOperationMessages;
}

/**
 * Shared message-schema resolution. Port of the backend
 * `AbstractAsyncApiSpecificationResolver`: async operations always carry an empty
 * request schema and place the message payload(s) under `responseSchemas`.
 */
abstract class AbstractAsyncApiResolver
  implements AsyncApiSpecificationResolver
{
  protected readonly schemaResolver: AsyncApiSchemaResolver;

  constructor(schemaResolver: AsyncApiSchemaResolver = new AsyncApiSchemaResolver()) {
    this.schemaResolver = schemaResolver;
  }

  abstract getOperationObjects(channel: AsyncApiChannel): AsyncApiOperationObject[];
  abstract getSpecificationJsonNode(
    channelName: string,
    channel: AsyncApiChannel,
    operationObject: AsyncApiOperationObject,
  ): Record<string, unknown>;
  abstract getMethod(
    channel: AsyncApiChannel,
    operationObject: AsyncApiOperationObject,
  ): string;

  setUpOperationMessages(
    operationObject: AsyncApiOperationObject,
    components: unknown,
  ): ResolvedOperationMessages {
    return {
      requestSchema: {},
      responseSchemas: this.getMessageSchema(operationObject, components),
    };
  }

  protected getMessageSchema(
    operationObject: AsyncApiOperationObject,
    components: unknown,
  ): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    const message = operationObject?.message;
    if (!message) {
      return result;
    }

    if (message.payload != null) {
      // Clone so the emitted schema does not alias the parsed document (the
      // backend clones via valueToTree); two operations sharing a channel
      // message must not see each other's downstream mutations.
      result[PAYLOAD_FIELD_NAME] = deepClone(message.payload);
      return result;
    }

    if (message.$ref) {
      const { key, schema } = this.getRefNode(message.$ref, components);
      result[key] = schema;
      return result;
    }

    if (message.oneOf) {
      return this.getRefsMessageNode(message.oneOf, components);
    }
    if (message.allOf) {
      return this.getRefsMessageNode(message.allOf, components);
    }
    if (message.anyOf) {
      return this.getRefsMessageNode(message.anyOf, components);
    }

    return result;
  }

  private getRefsMessageNode(
    refs: Array<Record<string, any>>,
    components: unknown,
  ): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    for (let i = 0; i < refs.length; i++) {
      const refNode = refs[i] ?? {};
      if (refNode.$ref) {
        const { key, schema } = this.getRefNode(refNode.$ref, components);
        result[key] = schema;
      } else if (refNode.payload !== undefined) {
        let key = PAYLOAD_FIELD_NAME;
        if (refNode.name) {
          key = refNode.name;
        } else if (refs.length > 1) {
          key = `${PAYLOAD_FIELD_NAME}_${i}`;
        }
        result[key] = deepClone(refNode.payload);
      }
    }
    return result;
  }

  private getRefNode(
    ref: string,
    components: unknown,
  ): { key: string; schema: unknown } {
    const refName = ref.replace(MESSAGES_PREFIX, "");
    const resolved = components
      ? this.schemaResolver.resolveRef(ref, components as Record<string, any>)
      : null;
    return { key: refName, schema: resolved ? resolved.schema : {} };
  }
}

function getMaasClassifier(
  operationObject: AsyncApiOperationObject,
): string | undefined {
  const classifier =
    operationObject?.maasClassifierName ??
    operationObject?.["x-maas-classifier-name"];
  return typeof classifier === "string" && classifier.length > 0
    ? classifier
    : undefined;
}

/** Kafka resolver. Port of the backend `KafkaSpecificationResolver`. */
export class KafkaSpecificationResolver extends AbstractAsyncApiResolver {
  getOperationObjects(channel: AsyncApiChannel): AsyncApiOperationObject[] {
    const operationObjects: AsyncApiOperationObject[] = [];
    if (channel.publish) {
      operationObjects.push(channel.publish);
    }
    if (channel.subscribe) {
      operationObjects.push(channel.subscribe);
    }
    return operationObjects;
  }

  getSpecificationJsonNode(
    channelName: string,
    _channel: AsyncApiChannel,
    operationObject: AsyncApiOperationObject,
  ): Record<string, unknown> {
    const specification: Record<string, unknown> = { topic: channelName };
    const classifier = getMaasClassifier(operationObject);
    if (classifier !== undefined) {
      specification.maasClassifierName = classifier;
    }
    return specification;
  }

  getMethod(
    channel: AsyncApiChannel,
    operationObject: AsyncApiOperationObject,
  ): string {
    if (operationObject.action) {
      return operationObject.action;
    }
    return channel.publish && channel.publish === operationObject
      ? "publish"
      : "subscribe";
  }
}

/** AMQP resolver. Port of the backend `AMQPSpecificationResolver`. */
export class AmqpSpecificationResolver extends AbstractAsyncApiResolver {
  private static readonly DEFAULT_SUMMARY = "AMQP operation";

  getOperationObjects(channel: AsyncApiChannel): AsyncApiOperationObject[] {
    const operationObjects: AsyncApiOperationObject[] = [];
    if (channel.publish) {
      operationObjects.push(channel.publish);
    }
    if (channel.subscribe) {
      operationObjects.push(channel.subscribe);
    }
    if (operationObjects.length === 0) {
      return [{ summary: AmqpSpecificationResolver.DEFAULT_SUMMARY }];
    }
    return operationObjects;
  }

  getSpecificationJsonNode(
    _channelName: string,
    channel: AsyncApiChannel,
    _operationObject: AsyncApiOperationObject,
  ): Record<string, unknown> {
    const specification: Record<string, unknown> = {};
    const amqp = channel?.bindings?.amqp;
    if (amqp && typeof amqp === "object") {
      if (amqp.userId != null) {
        specification.username = amqp.userId;
      }
      const queueName = amqp.queue?.name;
      if (queueName != null) {
        specification.queue = queueName;
      }
      const exchangeName = amqp.exchange?.name;
      if (exchangeName != null) {
        specification.exchangeName = exchangeName;
      }
    }
    return specification;
  }

  getMethod(
    channel: AsyncApiChannel,
    operationObject: AsyncApiOperationObject,
  ): string {
    if (operationObject.action) {
      return operationObject.action;
    }
    // AMQP inverts publish/subscribe semantics relative to Kafka.
    return channel.publish && channel.publish === operationObject
      ? "subscribe"
      : "publish";
  }
}

/**
 * Selects the resolver for an async protocol. Only kafka and amqp are supported,
 * matching the backend (OperationProtocol.KAFKA / AMQP).
 */
export function selectAsyncResolver(
  protocol: string,
): AsyncApiSpecificationResolver | null {
  switch ((protocol ?? "").toLowerCase()) {
    case "kafka":
    case "kafka-streams":
      return new KafkaSpecificationResolver();
    case "amqp":
    case "rabbit":
    case "rabbitmq":
      return new AmqpSpecificationResolver();
    default:
      return null;
  }
}
