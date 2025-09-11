export type BaseEntity = {
  id: string;
  name: string;
  description: string;
  createdWhen: number;
  createdBy: User;
  modifiedWhen: number;
  modifiedBy: User;
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
  properties: Record<string, unknown>;
};

export type TransferElementRequest = {
    parentId: string | null;
    elements: string[];
    swimlaneId: string | null;
}

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
  properties: never;
  children?: Element[];
  swimlaneId?: string;
  mandatoryChecksPassed: boolean;
}

export type LibraryElement = {
  name: string;
  title: string;
  description: string;
  folder: string;
  colorType: string;
  descriptionFormatter: string;
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
    [PropertyType.COMMON]: LibraryElementProperty[];
    [PropertyType.ADVANCED]: LibraryElementProperty[];
    [PropertyType.HIDDEN]: LibraryElementProperty[];
    [PropertyType.UNKNOWN]: LibraryElementProperty[];
  };
  customTabs: unknown[];
  deprecated: boolean;
  unsupported: boolean;
  oldStyleContainer: boolean;
  referencedByAnotherElement: boolean;
  designContainerParameters?: {
    endOperations: Operation[];
    children: ChildElement[];
  };
  queryProperties: unknown[];
  referenceProperties: unknown[];
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

export type ElementWithChainName  = BaseEntity &  {
  type: string;
  chainId: string
  chainName: string
  arentElementId: string;
  originalId: string;
  properties?: Record<string, unknown>;
  children: ChildElement[];
  swimlaneId: string
  mandatoryChecksPassed: boolean;
}

export interface ElementFilter {
  elementTitle: string;
  elementType: string;
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
  allowedValues: unknown[];
  allowCustomValue: boolean;
  multiple: boolean;
  reference: boolean;
  default?: unknown;
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
  status: DeploymentStatus;
  error: string;
  stacktrace: string;
  suspended: boolean;
};

export enum DeploymentStatus {
  DEPLOYED = "DEPLOYED",
  PROCESSING = "PROCESSING",
  FAILED = "FAILED",
  REMOVED = "REMOVED",
}

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

export type ImportVariablesResult = {
  variables: ImportVariableResult[];
  instructions: ImportInstructionResult[];
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
};

export type Event = {
  id: string;
  time?: number;
  userId?: string;
  objectType: ObjectType;
  data?: EventData;
};

export enum ObjectType {
  DEPLOYMENT = "DEPLOYMENT",
  ENGINE = "ENGINE",
  GENERIC_MESSAGE = "GENERIC_MESSAGE",
}

export type EventData = GenericMessage | EngineUpdate | DeploymentUpdate;

export type GenericMessage = {
  message: string;
  type: GenericMessageType;
  optionalFields: Record<string, string>;
};

export enum GenericMessageType {
  INFO = "INFO",
  WARN = "WARN",
  ERROR = "ERROR",
}

export type EngineUpdate = {
  domainId: string;
  domainName: string;
  actionType: EventActionType;
};

export enum EventActionType {
  ADDED = "ADDED",
  DELETED = "DELETED",
  MODIFIED = "MODIFIED",
  UNKNOWN = "UNKNOWN",
}

export type ErrorResponse = {
  serviceName: string;
  errorMessage: string;
  stackTrace: string;
  errorDate: string;
};

export function isErrorResponse(obj: unknown): obj is ErrorResponse {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "serviceName" in obj &&
    "errorMessage" in obj &&
    "errorDate" in obj &&
    typeof obj.serviceName === "string" &&
    typeof obj.errorMessage === "string" &&
    (!("stackTrace" in obj) || typeof obj.stackTrace === "string") &&
    typeof obj.errorDate === "string"
  );
}

export type DeploymentUpdate = {
  id: string;
  engineHost: string;
  state: RuntimeState;
  snapshotId: string;
  chainId: string;
  chainName: string;
  domain: string;
  createdWhen: number;
  serviceName: string;
  chainStatusCode: string;
};

export class RestApiError extends Error {
  responseCode: number;
  responseBody?: ErrorResponse;
  rawError?: unknown;

  constructor(
    message: string,
    responseCode: number,
    responseBody?: ErrorResponse,
    raw?: unknown,
  ) {
    super(message);
    this.name = "RestApiError";
    this.responseCode = responseCode;
    this.responseBody = responseBody;
    this.rawError = raw;
  }
}

export type CreateFolderRequest = {
  id?: string;
  parentId?: string;
  name?: string;
  description?: string;
};

export type UpdateFolderRequest = {
  parentId?: string;
  name?: string;
  description?: string;
};

export type MoveFolderRequest = {
  id: string;
  targetId?: string;
};

export type ListFolderRequest = {
  folderId?: string;
  filters?: FolderFilter[];
  searchString?: string;
};

export type FolderFilter = {
  column: string;
  condition: string;
  value?: string;
};

export type CatalogItem = BaseEntity & {
  parentId?: string;
  itemType: CatalogItemType;
};

export enum CatalogItemType {
  FOLDER = "FOLDER",
  CHAIN = "CHAIN",
}

export type FolderItem = CatalogItem & {};

export type ChainItem = CatalogItem & {
  labels: EntityLabel[];
};

export type ActionLogResponse = {
  actionLogs: ActionLog[];
  recordsAfterRange: number;
};

export type ActionLog = {
  id: string;
  actionTime: number;
  entityType: EntityType;
  entityId?: string;
  entityName?: string;
  parentType?: EntityType;
  parentId?: string;
  parentName?: string;
  operation: LogOperation;
  userId?: string;
  username?: string;
  requestId?: string;
};

export type ActionLogSearchRequest = {
  offsetTime: number;
  rangeTime: number;
  filters?: unknown;
};

export enum EntityType {
  FOLDER = "FOLDER",
  CHAIN = "CHAIN",
  CHAINS = "CHAINS",
  SNAPSHOT = "SNAPSHOT",
  SNAPSHOT_CLEANUP = "SNAPSHOT_CLEANUP",
  DEPLOYMENT = "DEPLOYMENT",
  ELEMENT = "ELEMENT",
  MASKED_FIELD = "MASKED_FIELD",
  CHAIN_RUNTIME_PROPERTIES = "CHAIN_RUNTIME_PROPERTIES",

  // systems catalog specific
  SERVICE_DISCOVERY = "SERVICE_DISCOVERY",
  EXTERNAL_SERVICE = "EXTERNAL_SERVICE",
  INNER_CLOUD_SERVICE = "INNER_CLOUD_SERVICE",
  IMPLEMENTED_SERVICE = "IMPLEMENTED_SERVICE",
  ENVIRONMENT = "ENVIRONMENT",
  SPECIFICATION = "SPECIFICATION",
  SPECIFICATION_GROUP = "SPECIFICATION_GROUP",
  SERVICES = "SERVICES",
  MAAS_KAFKA = "MAAS_KAFKA",
  MAAS_RABBITMQ = "MAAS_RABBITMQ",
  DETAILED_DESIGN_TEMPLATE = "DETAILED_DESIGN_TEMPLATE",

  // variables-management specific
  SECRET = "SECRET",
  SECURED_VARIABLE = "SECURED_VARIABLE",
  COMMON_VARIABLE = "COMMON_VARIABLE",
  IMPORT_INSTRUCTION = "IMPORT_INSTRUCTION",
  IMPORT_INSTRUCTIONS = "IMPORT_INSTRUCTIONS",
}

export enum LogOperation {
  CREATE = "CREATE",
  UPDATE = "UPDATE",
  CREATE_OR_UPDATE = "CREATE_OR_UPDATE",
  DELETE = "DELETE",
  COPY = "COPY",
  MOVE = "MOVE",
  REVERT = "REVERT",
  GROUP = "GROUP",
  UNGROUP = "UNGROUP",
  EXPORT = "EXPORT",
  IMPORT = "IMPORT",
  SCALE = "SCALE",
  EXECUTE = "EXECUTE",
  ACTIVATE = "ACTIVATE",
  DEPRECATE = "DEPRECATE",
}

export type LogExportRequestParams = {
  actionTimeFrom: number;
  actionTimeTo: number;
};

export type Engine = {
  id: string;
  name: string;
  host: string;
  runningStatus: RunningStatus;
  ready: boolean;
  connected: boolean;
  namespace: string;
  domainId?: string;
  domainName?: string;
};

export enum RunningStatus {
  RUNNING = "RUNNING",
  PENDING = "PENDING",
  FAILED = "FAILED",
  UNKNOWN = "UNKNOWN",
}

export type EngineUpdateResponse = {
  domainId: string;
  domainName: string;
  actionType: EventActionType;
  host?: string;
  name?: string;
};

export type ChainDeployment = {
  id: string;
  chainId: string;
  chainName: string;
  snapshotName: string;
  state: RuntimeState;
};

export type DetailedDesignTemplate = BaseEntity & {
  content?: string;
};

export type ChainDetailedDesign = {
  document: string;
  simpleSeqDiagramMermaid?: string;
  simpleSeqDiagramPlantuml?: string;
  triggerSpecifications?: DesignSpecificationSource[];
};

export type DesignSpecificationSource = {
  serviceName: string;
  specificationName: string;
  specificationId: string;
  fileExtension: string;
  specificationContent: string;
};

export type IntegrationSystem = {
  id: string;
  name: string;
  type: IntegrationSystemType;
  description?: string;
  activeEnvironmentId: string;
  internalServiceName: string;
  protocol: string;
  extendedProtocol: string;
  specification: string;
  labels?: EntityLabel[];
  createdWhen?: string;
  createdBy?: User;
  modifiedWhen?: string;
};

export enum IntegrationSystemType {
  INTERNAL = "INTERNAL",
  EXTERNAL = "EXTERNAL",
  IMPLEMENTED = "IMPLEMENTED",
}

export type SystemRequest = {
  name: string;
  type: IntegrationSystemType;
  description?: string;
  activeEnvironmentId?: string;
  labels?: EntityLabel[];
};

export type Environment = {
  id: string;
  systemId: string;
  name: string;
  description?: string;
  address?: string;
  labels?: EnvironmentLabel[];
  properties?: Record<string, unknown>;
  defaultProperties?: never;
  maasDefaultProperties?: never;
  createdWhen?: number;
  createdBy?: User;
  modifiedWhen?: number;
  modifiedBy?: User;
  sourceType?: EnvironmentSourceType;
  /** @deprecated */
  maasInstanceId?: string;
};

export type EnvironmentRequest = {
  name: string;
  address?: string;
  labels?: EnvironmentLabel[];
  properties?: Record<string, unknown>;
  sourceType?: EnvironmentSourceType;
};

export enum EnvironmentSourceType {
  MANUAL = "MANUAL",
  /** @deprecated */
  MAAS = "MAAS",
  MAAS_BY_CLASSIFIER = "MAAS_BY_CLASSIFIER",
}

export type EnvironmentLabel = {
  name: string;
};

export interface SpecificationGroup {
  id: string;
  name: string;
  systemId: string;
  synchronization: boolean;
  createdWhen?: number;
  createdBy?: User;
  modifiedWhen?: number;
  modifiedBy?: User;
  specifications: Specification[];
  chains?: BaseEntity[];
  labels?: EntityLabel[];
}

export interface Specification {
  id: string;
  name: string;
  specificationGroupId: string;
  deprecated?: boolean;
  version: string;
  source: string;
  systemId: string;
  createdWhen?: number;
  createdBy?: User;
  modifiedWhen?: number;
  modifiedBy?: User;
  chains?: BaseEntity[];
  labels?: EntityLabel[];
  operations?: SystemOperation;
}

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

export type ImportSpecificationResult = {
  id: string;
  description?: string;
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

export type ImportSpecificationGroupRequest = {
  systemId: string;
  name: string;
  protocol?: string;
  files: SerializedFile[];
};

export enum DiagramMode {
  SIMPLE = "SIMPLE",
  FULL = "FULL",
}

export enum DiagramLangType {
  PLANT_UML = "PLANT_UML",
  MERMAID = "MERMAID",
}

export type ElementsSequenceDiagrams = Record<
  DiagramMode,
  ElementsSequenceDiagram
>;

export type ElementsSequenceDiagram = {
  chainId: string;
  snapshotId?: string;
  diagramSources: Record<DiagramLangType, string>;
};

export enum ApiSpecificationType {
  OpenAPI = "OpenAPI",
  AsyncAPI = "AsyncAPI",
}

export enum ApiSpecificationFormat {
  JSON = "JSON",
  YAML = "YAML",
}

