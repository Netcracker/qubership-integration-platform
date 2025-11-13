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

export type SpecificationGroup = BaseEntity & {
  specifications: Specification[];
  synchronization: boolean;
  parentId?: string;
  systemId?: string;
  labels?: EntityLabel[];
};

export type Specification = BaseEntity & {
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
}

export interface OperationInfo {
  id: string;
  specification: unknown;
  requestSchema: Record<string, unknown>;
  responseSchemas: Record<string, unknown>;
}
