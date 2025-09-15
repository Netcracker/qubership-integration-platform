export type BaseEntity = {
  id: string;
  name: string;
  description: string;
  createdWhen: number;
  createdBy: User;
  modifiedWhen: number;
  modifiedBy: User;
};

export type FolderItem = BaseEntity & {
  parentId: string;
  itemType: FolderItemType;
  labels: EntityLabel[];
  deployments: Deployment[];
  chainRuntimeProperties: ChainLoggingSettings;
  businessDescription: string;
  assumptions: string;
  outOfScope: string;
  overriddenByChainId: string;
  overriddenByChainName: string;
  overridesChainId: string;
  overridesChainName: string;
};

export enum FolderItemType {
  FOLDER = "FOLDER",
  CHAIN = "CHAIN",
}

export enum QipFileType {
  CHAIN = "CHAIN",
  SERVICE = "SERVICE",
  UNKNOWN = "UNKNOWN"
}

export type FolderUpdateRequest = {
  name: string;
  description: string;
  parentId?: string;
};

export type FolderResponse = BaseEntity & {
  navigationPath: Map<string, string>; // Need to be a Map to preserve key order
  parentId: string;
  items: FolderItem[];
  labels: EntityLabel[];
};

export type Chain = BaseEntity & {
  navigationPath: Map<string, string>; // Need to be a Map to preserve key order
  elements: Element[];
  dependencies: Dependency[];
  deployments: Deployment[];
  deployAction?: ChainCommitRequestAction;
  labels: EntityLabel[];
  defaultSwimlaneId: string;
  reuseSwimlaneId: string;
  parentId?: string;
  currentSnapshot?: BaseEntity;
  unsavedChanges: boolean;
  businessDescription: string;
  assumptions: string;
  outOfScope: string;
  containsDeprecatedContainers: boolean;
  containsDeprecatedElements: boolean;
  containsUnsupportedElements: boolean;
  overriddenByChainId?: string;
  overriddenByChainName?: string;
  overridesChainId?: string;
  overridesChainName?: string;
};

export type Dependency = {
  id: string;
  from: string;
  to: string;
};

export type CreateElementRequest = {
  type: string;
  parentElementId?: string;
};

export type PatchElementRequest = {
  name: string;
  description: string;
  type: string;
  parentElementId?: string;
  properties: {};
};

export type ConnectionRequest = {
  from: string;
  to: string;
};

export type Connection = {
  id: string;
  from: string;
  to: string;
};

export type ChainCreationRequest = {
  name: string;
  labels?: EntityLabel[];
  description?: string;
  businessDescription?: string;
  assumptions?: string;
  outOfScope?: string;
  parentId?: string;
};

export type LibraryData = {
  groups: Group[];
  elements: LibraryElement[];
  childElements: Record<string, LibraryElement>;
};

export type Group = {
  name: string;
  title: string;
  groups: Group[];
  elements: LibraryElement[];
  childElements: Record<string, LibraryElement>;
};

export type Element = BaseEntity & {
  chainId: string;
  type: string;
  parentElementId?: string;
  originalId?: string;
  properties: any;
  children?: Element[];
  swimlaneId?: string;
  mandatoryChecksPassed: boolean;
}

export type LibraryElement = {
  id: string;
  name: string;
  title: string;
  description: string;
  folder: string;
  type: string;
  inputEnabled: boolean;
  inputQuantity: LibraryInputQuantity;
  outputEnabled: boolean;
  container: boolean;
  ordered: boolean;
  allowedInContainers: boolean;
  priorityProperty?: string;
  reuseReferenceProperty?: string;
  mandatoryInnerElement: boolean;
  parentRestriction: string[];
  allowedChildren: Record<string, LibraryElementQuantity>;
  properties: {
    common: LibraryElementProperty[];
    advanced: LibraryElementProperty[];
    hidden: LibraryElementProperty[];
    async: LibraryElementProperty[];
  };
  customTabs: any[];
  deprecated: boolean;
  unsupported: boolean;
  oldStyleContainer: boolean;
  referencedByAnotherElement: boolean;
  designContainerParameters?: {
    endOperations: Operation[];
    children: ChildElement[];
  };
  queryProperties: any[];
  referenceProperties: any[];
};

export enum LibraryElementQuantity {
    ONE = "one",
    ONE_OR_ZERO = "one-or-zero",
    ONE_OR_MANY = "one-or-many",
}

export enum LibraryInputQuantity {
    ONE = "one",
    ANY = "any",
}

export enum PropertyType {
  COMMON = "common",
  ADVANCED = "advanced",
  HIDDEN = "hidden",
  UNKNOWN = "unknown",
}

export type LibraryElementProperty = {
  name: string;
  title: string;
  description?: string;
  type: string;
  resetValueOnCopy: boolean;
  unique: boolean;
  caseInsensitive: boolean;
  mandatory: boolean;
  autofocus: boolean;
  query: boolean;
  allowedValues: any[];
  allowCustomValue: boolean;
  multiple: boolean;
  reference: boolean;
  default?: any;
  mask?: string;
};

export type Operation = {
  type: string;
  args: string[];
};

export type ChildElement = {
  name: string;
  primaryOperation: Operation;
};

export type Snapshot = BaseEntity & {
  xmlDefinition: string;
  labels: EntityLabel[];
};

export type EntityLabel = {
  name: string;
  technical: boolean;
};

export type ActionDifference = {
  createdElements?: Element[];
  updatedElements?: Element[];
  removedElements?: Element[];
  createdDefaultSwimlaneId?: string;
  createdReuseSwimlaneId?: string;
  createdDependencies?: Connection[];
  removedDependencies?: Connection[];
};

export type RuntimeState = {
  status: string;
  error: string;
  stacktrace: string;
  suspended: boolean;
};

export type RuntimeStates = {
  states: { [key: string]: RuntimeState };
};

export type User = {
  id: string;
  username: string;
};

export type Deployment = {
  id: string;
  chainId: string;
  snapshotId: string;
  name: string;
  domain: string;
  createdWhen: number;
  createdBy: User;
  runtime?: RuntimeStates;
  serviceName: string;
};

export type CreateDeploymentRequest = {
  domain: string;
  snapshotId: string;
  suspended: boolean;
};

export type EngineDomain = {
  id: string;
  name: string;
  replicas: number;
  namespace: string;
  version?: string;
};

export type ChainLoggingSettings = {
  fallbackDefault: ChainLoggingProperties;
  consulDefault?: ChainLoggingProperties;
  custom?: ChainLoggingProperties;
};

export type ChainLoggingProperties = {
  sessionsLoggingLevel: SessionsLoggingLevel;
  logLoggingLevel: LogLoggingLevel;
  logPayloadEnabled?: boolean; //Deprecated since 24.4
  logPayload: LogPayload[];
  dptEventsEnabled: boolean;
  maskingEnabled: boolean;
};

export enum SessionsLoggingLevel {
  OFF = "OFF",
  ERROR = "ERROR",
  INFO = "INFO",
  DEBUG = "DEBUG",
}

export enum LogLoggingLevel {
  ERROR = "ERROR",
  WARN = "WARN",
  INFO = "INFO",
}

export enum LogPayload {
  BODY = "Body",
  HEADERS = "Headers",
  PROPERTIES = "Properties",
}

export type MaskedFields = {
  fields: MaskedField[];
};

export type MaskedField = {
  id: string;
  name: string;
  createdWhen: number;
  createdBy: User;
  modifiedWhen: number;
  modifiedBy: User;
};

export type SessionFilterAndSearchRequest = {
  filterRequestList: SessionFilterRequest[];
  searchString: string;
};

export type SessionFilterRequest = {
  feature: SessionFilterFeature;
  condition: SessionFilterCondition;
  value: string;
};

export enum SessionFilterFeature {
  CHAIN_NAME = "CHAIN_NAME",
  STATUS = "STATUS",
  START_TIME = "START_TIME",
  FINISH_TIME = "FINISH_TIME",
  ENGINE = "ENGINE",
}

export enum SessionFilterCondition {
  IN = "IN",
  NOT_IN = "NOT_IN",
  IS_AFTER = "IS_AFTER",
  IS_BEFORE = "IS_BEFORE",
  IS_WITHIN = "IS_WITHIN",
  CONTAINS = "CONTAINS",
  DOES_NOT_CONTAIN = "DOES_NOT_CONTAIN",
  STARTS_WITH = "STARTS_WITH",
  ENDS_WITH = "ENDS_WITH",
}

export type PaginationOptions = {
  offset?: number;
  count?: number;
};

export type SessionSearchResponse = {
  offset: number;
  sessions: Session[];
};

export type AbstractRunnableElement = {
  started: string;
  finished: string;
  duration: number;
  syncDuration: number;
  executionStatus: ExecutionStatus;
};

export type Session = AbstractRunnableElement & {
  id: string;
  importedSession: boolean;
  externalSessionCipId: string;
  chainId: string;
  chainName: string;
  domain: string;
  engineAddress: string;
  loggingLevel: SessionsLoggingLevel | string;
  snapshotName: string;
  correlationId: string;
  parentSessionId: string;
  sessionElements?: SessionElement[];
};

export enum ExecutionStatus {
  IN_PROGRESS = "IN_PROGRESS",
  COMPLETED_NORMALLY = "COMPLETED_NORMALLY",
  COMPLETED_WITH_WARNINGS = "COMPLETED_WITH_WARNINGS",
  COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS",
  CANCELLED_OR_UNKNOWN = "CANCELLED_OR_UNKNOWN",
}

export type SessionElement = AbstractRunnableElement & {
  elementId: string;
  sessionId: string;
  chainElementId: string;
  actualElementChainId: string;
  parentElement: string;
  previousElement: string;
  elementName: string;
  camelName: string;
  bodyBefore: string;
  bodyAfter: string;
  headersBefore: Record<string, string>;
  headersAfter: Record<string, string>;
  propertiesBefore: Record<string, SessionElementProperty>;
  propertiesAfter: Record<string, SessionElementProperty>;
  contextBefore: Record<string, string>;
  contextAfter: Record<string, string>;
  children?: SessionElement[];
  exceptionInfo: ExceptionInfoElastic;
};

export type SessionElementProperty = {
  type: string;
  value: string;
};

export type ExceptionInfoElastic = {
  message: string;
  stackTrace: string;
};

export type CheckpointSession = {
  id: string;
  started: string;
  finished: string;
  duration: number;
  executionStatus: ExecutionStatus;
  chainId: string;
  chainName: string;
  engineAddress: string;
  loggingLevel: SessionsLoggingLevel;
  snapshotName: string;
  correlationId: string;
  checkpoints: Checkpoint[];
};

export type Checkpoint = {
  id: string;
  checkpointElementId: string;
  timestamp: string;
};

export type UsedService = {
  systemId: string;
  usedSystemModelIds: string[];
};

export type ImportPreview = {
  errorMessage: string;
  chains: ChainImportPreview[];
  systems: SystemImportPreview[];
  variables: VariableImportPreview[];
  instructions: GeneralImportInstructions;
};

export type ChainImportPreview = {
  id: string;
  name: string;
  usedSystems: string[];
  deployAction: ChainCommitRequestAction;
  deployments: DeploymentExternalEntity[];
  instructionAction: ImportInstructionAction;
  errorMessage: string;
  exists: boolean;
};

export type SystemImportPreview = {
  id: string;
  name: string;
  archiveName: string;
  modified: number;
  status: SystemImportStatus;
  requiredAction: SystemImportAction;
  instructionAction: ImportInstructionAction;
  message: string;
};

export type VariableImportPreview = {
  name: string;
  value: string;
  currentValue: string;
};

export type GeneralImportInstructions = {
  chains: ChainImportInstructions;
  services: ImportInstructions;
  specificationGroups: ImportInstructions;
  specifications: ImportInstructions;
  commonVariables: ImportInstructions;
};

export type ChainImportInstructions = ImportInstructions & {
  override: ImportInstruction[];
};

export type ImportInstructions = {
  delete: ImportInstruction[];
  ignore: ImportInstruction[];
};

export type ImportInstruction = {
  id: string;
  name: string;
  overriddenById: string;
  overriddenByName: string;
  labels: string[];
  modifiedWhen: number;
  preview: boolean;
};

export enum SystemImportStatus {
  CREATED = "CREATED",
  UPDATED = "UPDATED",
  ERROR = "ERROR",
  NO_ACTION = "NO_ACTION",
  IGNORED = "IGNORED",
}

export enum SystemImportAction {
  CREATE = "CREATE",
  UPDATE = "UPDATE",
  ERROR = "ERROR",
}

export enum ChainCommitRequestAction {
  NONE = "NONE",
  SNAPSHOT = "SNAPSHOT",
  DEPLOY = "DEPLOY",
}

export enum ImportInstructionAction {
  DELETE = "DELETE",
  IGNORE = "IGNORE",
  OVERRIDE = "OVERRIDE",
}

export type DeploymentExternalEntity = {
  domain: string;
};

export type ImportRequest = {
  chainCommitRequests: ChainCommitRequest[];
  systemsCommitRequest: SystemsCommitRequest;
  variablesCommitRequest: VariablesCommitRequest;
};

export type ChainCommitRequest = {
  id: string;
  archiveName: string;
  deployAction: ChainCommitRequestAction;
  domains: ImportDomain[];
};

export type ImportDomain = {
  id: string;
  name: string;
  errorMessage?: string;
};

export type SystemsCommitRequest = {
  importMode: ImportMode;
  systemIds: string[];
  deployLabel?: string;
};

export enum ImportMode {
  FULL = "FULL",
  PARTIAL = "PARTIAL",
  NONE = "NONE",
}

export type VariablesCommitRequest = {
  importMode: ImportMode;
  variablesNames: string[];
};

export type ImportCommitResponse = {
  importId: string;
};

export type ImportStatusResponse = {
  result?: ImportResult;
  completion: number;
  done: boolean;
  error?: string;
};

export type ImportResult = {
  chains: ImportChainResult[];
  systems: ImportSystemResult[];
  variables: ImportVariableResult[];
  instructionsResult: ImportInstructionResult[];
};

export type ImportChainResult = {
  id: string;
  name: string;
  status: ImportEntityStatus;
  errorMessage: string;
  deployAction: ChainCommitRequestAction;
  deployments: DeploymentExternalEntity[];
};

export type ImportSystemResult = {
  id: string;
  name: string;
  archiveName: string;
  modified: number;
  status: SystemImportStatus;
  requiredAction: SystemCompareAction;
  instructionAction: ImportInstructionAction;
  message: string;
};

export type ImportVariableResult = {
  name: string;
  value: string;
  status: ImportEntityStatus;
  error: string;
};

export type ImportInstructionResult = {
  id: string;
  name: string;
  entityType: ImportEntityType;
  status: ImportInstructionStatus;
  errorMessage: string;
};

export enum ImportEntityStatus {
  CREATED = "CREATED",
  ERROR = "ERROR",
  UPDATED = "UPDATED",
  IGNORED = "IGNORED",
  SKIPPED = "SKIPPED",
}

export enum SystemCompareAction {
  CREATE = "CREATE",
  UPDATE = "UPDATE",
  ERROR = "ERROR",
}

export enum ImportEntityType {
  CHAIN = "CHAIN",
  SERVICE = "SERVICE",
  SPECIFICATION_GROUP = "SPECIFICATION_GROUP",
  SPECIFICATION = "SPECIFICATION",
  COMMON_VARIABLE = "COMMON_VARIABLE",
}

export enum ImportInstructionStatus {
  DELETED = "DELETED",
  IGNORED = "IGNORED",
  OVERRIDDEN = "OVERRIDDEN",
  ERROR_ON_DELETE = "ERROR_ON_DELETE",
  ERROR_ON_OVERRIDE = "ERROR_ON_OVERRIDE",
  NO_ACTION = "NO_ACTION",
}


export type EventsUpdate = {
    lastEventId: string;
    events: Event[];
}

export type Event = {
    id: string;
    time?: number;
    userId?: string;
    objectType: ObjectType;
    data?: any;
}

export enum ObjectType {
    DEPLOYMENT = 'DEPLOYMENT',
    ENGINE = 'ENGINE',
    GENERIC_MESSAGE = 'GENERIC_MESSAGE',
}

export type ErrorResponse = {
  serviceName: string;
  errorMessage: string;
  stackTrace: string;
  errorDate: string;
}

export class RestApiError extends Error {
    responseCode: number;
    responseBody?: ErrorResponse;
    rawError?: unknown;

    constructor(message: string, responseCode: number, responseBody?: ErrorResponse, raw?: unknown) {
      super(message);
      this.name = "RestApiError";
      this.responseCode = responseCode;
      this.responseBody = responseBody;
      this.rawError = raw;
    }
}

export type VSCodeMessage = {
  type: string;
  requestId: string;
  payload?: any;
};

export type VSCodeResponse = {
  type: string;
  requestId: string;
  payload?: any;
  error?: any;
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
};

export type Environment = BaseEntity & {
  address: string;
  sourceType: string;
  properties: Record<string, string>;
  labels: EntityLabel[];
};

export type SpecificationGroup = BaseEntity & {
  specifications: Specification[];
  synchronization: boolean;
  parentId: string;
  systemId?: string;
};

export type Specification = BaseEntity & {
  version: string;
  format: string;
  content: string;
  deprecated: boolean;
  parentId: string;
  operations?: SystemOperation[];
  systemId?: string;
  specificationGroupId?: string;
  source?: string;
  sourceFiles?: string[];
  protocol?: string;
  metadata?: Record<string, any>;
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
  description?: string;
  address: string;
  sourceType: string;
  properties?: Record<string, string>;
  labels?: EntityLabel[];
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
