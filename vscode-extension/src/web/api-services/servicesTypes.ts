export type BaseEntity = {
  id: string;
  name: string;
  description?: string;
};

export type User = {
  id: string;
  username: string;
};

export type EntityLabel = {
  name: string;
  technical: boolean;
};

export enum IntegrationSystemType {
  EXTERNAL = "EXTERNAL",
  INTERNAL = "INTERNAL",
  IMPLEMENTED = "IMPLEMENTED",
  CONTEXT = "CONTEXT",
  MCP = "MCP",
}

export type IntegrationSystem = BaseEntity & {
  activeEnvironmentId: string;
  integrationSystemType: IntegrationSystemType;
  protocol: string;
  extendedProtocol: string;
  specification: string;
  labels: EntityLabel[];
  environments?: Environment[];
  type?: IntegrationSystemType;
  internalServiceName?: string;
};

export type Environment = BaseEntity & {
  address: string;
  sourceType: string;
  properties: Record<string, string>;
  labels: EntityLabel[];
  systemId?: string;
};

export type ApiGroup = BaseEntity & {
  specifications: Api[];
  synchronization: boolean;
  parentId?: string;
  systemId?: string;
  labels?: EntityLabel[];
};

export type Api = BaseEntity & {
  version: string;
  format?: string;
  content?: string;
  deprecated?: boolean;
  parentId?: string;
  operations?: SystemOperation[];
  systemId?: string;
  specificationGroupId?: string;
  source?: string;
  sourceFiles?: string[];
  protocol?: string;
  metadata?: Record<string, any>;
  labels?: EntityLabel[];
  createdWhen?: number;
  /** Protocol/format of the underlying spec source, e.g. "OpenAPI", "AsyncAPI", "gRPC". */
  specificationType?: string;
  /** Spec format version, e.g. "3.1", "2.6" — distinct from `version` (the API's own version label). */
  specificationVersion?: string;
};

export type SystemRequest = {
  name: string;
  description?: string;
  type: IntegrationSystemType;
  protocol?: string;
  extendedProtocol?: string;
  specification?: string;
  labels?: EntityLabel[];
};

export type EnvironmentRequest = {
  name: string;
  address: string;
  description?: string;
  sourceType?: string;
  properties?: Record<string, string>;
  labels?: EntityLabel[];
  systemId?: string;
  isActive?: boolean;
};

export interface SystemOperation {
  id: string;
  name: string;
  description?: string;
  method: string;
  path: string;
  modelId: string;
  chains: BaseEntity[];
  channel?: string;
  operationType?: string;
  binding?: string;
  protocol?: string;
  rpcMethod?: string;
  summary?: string;
  isDeprecated?: boolean;
  /** Protocol discriminator from the api file's typed operation: openapi, asyncapi, wsdl, graphql, or protobuf. */
  operationKind?: string;
  package?: string;
  service?: string;
  /** Printed field AST that reconstructs a graphql operation's path. */
  sdl?: string;
  /** java_package option that reconstructs a protobuf operation's path when it differs from the proto package. */
  javaPackage?: string;
}

export interface OperationInfo {
  id: string;
  specification: unknown;
  requestSchema: Record<string, unknown>;
  responseSchemas: Record<string, unknown>;
}
