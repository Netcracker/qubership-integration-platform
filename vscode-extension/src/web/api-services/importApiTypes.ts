export type ImportSpecificationResult = {
  id: string;
  warningMessage?: string;
  done: boolean;
  specificationGroupId: string;
  createdWhen?: number;
};

export type SerializedFile = {
  name: string;
  size: number;
  type: string;
  lastModified: number;
  content: ArrayBuffer;
};

export type ImportSpecificationGroupRequest = {
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
  NATS = "NATS"
}
