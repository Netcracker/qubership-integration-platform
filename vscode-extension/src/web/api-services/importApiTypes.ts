export type ImportSpecificationResult = {
  id: string;
  warningMessage?: string;
  done: boolean;
  specificationGroupId: string;
};

export type SerializedFile = {
  name: string;
  size: number;
  type: string;
  lastModified: number;
  content: ArrayBuffer;
};

export type ImportApiGroupRequest = {
  systemId: string;
  name: string;
  protocol?: string;
  files: SerializedFile[];
};

export enum ApiSpecificationType {
  HTTP = "HTTP",
  SOAP = "SOAP",
  GRAPHQL = "GRAPHQL",
  GRPC = "GRPC",
  ASYNC = "ASYNC",
  AMQP = "AMQP",
  MQTT = "MQTT",
  KAFKA = "KAFKA",
  REDIS = "REDIS",
  NATS = "NATS",
}

// Protocol variants that all map to the AsyncAPI specification type. Single
// source of truth for the async subset — the validator, the importer, and the
// schema extractor read it, so adding a protocol touches only this set.
export const ASYNC_SPECIFICATION_TYPES: ReadonlySet<ApiSpecificationType> =
  new Set([
    ApiSpecificationType.ASYNC,
    ApiSpecificationType.AMQP,
    ApiSpecificationType.MQTT,
    ApiSpecificationType.KAFKA,
    ApiSpecificationType.REDIS,
    ApiSpecificationType.NATS,
  ]);
