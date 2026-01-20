import { EntityFilterModel } from "../components/table/filter/filter.ts";
import {
  Chain,
  ChainCreationRequest,
  Connection,
  CreateElementRequest,
  LibraryData,
  LibraryElement,
  Snapshot,
  ConnectionRequest,
  ActionDifference,
  Deployment,
  CreateDeploymentRequest,
  EngineDomain,
  EntityLabel,
  ChainLoggingSettings,
  ChainLoggingProperties,
  MaskedField,
  SessionFilterAndSearchRequest,
  PaginationOptions,
  SessionSearchResponse,
  Session,
  CheckpointSession,
  FolderItem,
  PatchElementRequest,
  UsedService,
  ImportPreview,
  ImportRequest,
  ImportCommitResponse,
  ImportStatusResponse,
  EventsUpdate,
  ListFolderRequest,
  ChainItem,
  CreateFolderRequest,
  UpdateFolderRequest,
  Engine,
  ChainDeployment,
  ElementFilter,
  ActionLogSearchRequest,
  ActionLogResponse,
  LogExportRequestParams,
  IntegrationSystem,
  SystemRequest,
  EnvironmentRequest,
  Environment,
  SpecificationGroup,
  Specification,
  OperationInfo,
  ImportSystemResult,
  ImportSpecificationResult,
  BaseEntity,
  DetailedDesignTemplate,
  ChainDetailedDesign,
  ElementsSequenceDiagrams,
  DiagramMode,
  ElementWithChainName,
  ApiSpecificationType,
  ApiSpecificationFormat,
  TransferElementRequest,
  Element,
  SystemOperation,
  SpecApiFile,
  LiveExchange,
  ContextSystem,
  IntegrationSystemType,
  DiagnosticValidation,
} from "./apiTypes.ts";
import { RestApi } from "./rest/restApi.ts";
import { isVsCode, VSCodeExtensionApi } from "./rest/vscodeExtensionApi.ts";

export interface Api {
  getChains(): Promise<Chain[]>;

  getChain(id: string): Promise<Chain>;

  findChainByElementId(elementId: string): Promise<Chain>;

  updateChain(id: string, chain: Partial<Chain>): Promise<Chain>;

  createChain(chain: ChainCreationRequest): Promise<Chain>;

  deleteChain(chainId: string): Promise<void>;

  deleteChains(chainIds: string[]): Promise<void>;

  duplicateChain(chainId: string): Promise<Chain>;

  copyChain(chainId: string, folderId?: string): Promise<Chain>;

  moveChain(chainId: string, folder?: string): Promise<Chain>;

  exportAllChains(): Promise<File>;

  exportChains(chainIds: string[], exportSubchains: boolean): Promise<File>;

  getLibrary(): Promise<LibraryData>;

  getElements(chainId: string): Promise<Element[]>;

  getElementTypes(): Promise<ElementFilter[]>;

  getElementsByType(
    chainId: string,
    elementType: string,
  ): Promise<ElementWithChainName[]>;

  createElement(
    elementRequest: CreateElementRequest,
    chainId: string,
  ): Promise<ActionDifference>;

  updateElement(
    elementRequest: PatchElementRequest,
    chainId: string,
    elementId: string,
  ): Promise<ActionDifference>;

  transferElement(
    transferElementRequest: TransferElementRequest,
    chainId: string,
  ): Promise<ActionDifference>;

  deleteElements(
    elementIds: string[],
    chainId: string,
  ): Promise<ActionDifference>;

  getConnections(chainId: string): Promise<Connection[]>;

  createConnection(
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<ActionDifference>;

  deleteConnections(
    connectionIds: string[],
    chainId: string,
  ): Promise<ActionDifference>;

  createSnapshot(chainId: string): Promise<Snapshot>;

  getSnapshots(chainId: string): Promise<Snapshot[]>;

  getSnapshot(snapshotId: string): Promise<Snapshot>;

  deleteSnapshot(snapshotId: string): Promise<void>;

  deleteSnapshots(snapshotIds: string[]): Promise<void>;

  updateSnapshot(
    snapshotId: string,
    name: string,
    labels: EntityLabel[],
  ): Promise<Snapshot>;

  revertToSnapshot(chainId: string, snapshotId: string): Promise<Snapshot>;

  getLibraryElementByType(type: string): Promise<LibraryElement>;

  getDeployments(chainId: string): Promise<Deployment[]>;

  createDeployment(
    chainId: string,
    request: CreateDeploymentRequest,
  ): Promise<Deployment>;

  deleteDeployment(deploymentId: string): Promise<void>;

  getDomains(): Promise<EngineDomain[]>;

  getLoggingSettings(chainId: string): Promise<ChainLoggingSettings>;

  setLoggingProperties(
    chainId: string,
    properties: ChainLoggingProperties,
  ): Promise<void>;

  deleteLoggingSettings(chainId: string): Promise<void>;

  getMaskedFields(chainId: string): Promise<MaskedField[]>;

  createMaskedField(
    chainId: string,
    maskedField: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField>;

  deleteMaskedFields(chainId: string, maskedFieldIds: string[]): Promise<void>;

  deleteMaskedField(chainId: string, maskedFieldId: string): Promise<void>;

  updateMaskedField(
    chainId: string,
    maskedFieldId: string,
    changes: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField>;

  getSessions(
    chainId: string | undefined,
    filters: SessionFilterAndSearchRequest,
    paginationOptions: PaginationOptions,
  ): Promise<SessionSearchResponse>;

  deleteSessions(sessionIds: string[]): Promise<void>;

  deleteSessionsByChainId(chainId: string | undefined): Promise<void>;

  exportSessions(sessionIds: string[]): Promise<File>;

  importSessions(files: File[]): Promise<Session[]>;

  retryFromLastCheckpoint(chainId: string, sessionId: string): Promise<void>;

  getSession(sessionId: string): Promise<Session>;

  getCheckpointSessions(sessionIds: string[]): Promise<CheckpointSession[]>;

  retrySessionFromLastCheckpoint(
    chainId: string,
    sessionId: string,
  ): Promise<void>;

  getFolder(folderId: string): Promise<FolderItem>;

  getRootFolders(filter: string, openedFolderId: string): Promise<FolderItem[]>;

  getPathToFolder(folderId: string): Promise<FolderItem[]>;

  getPathToFolderByName(folderName: string): Promise<FolderItem[]>;

  listFolder(request: ListFolderRequest): Promise<(FolderItem | ChainItem)[]>;

  createFolder(request: CreateFolderRequest): Promise<FolderItem>;

  updateFolder(
    folderId: string,
    changes: UpdateFolderRequest,
  ): Promise<FolderItem>;

  deleteFolder(folderId: string): Promise<void>;

  deleteFolders(folderIds: string[]): Promise<void>;

  moveFolder(folderId: string, targetFolderId?: string): Promise<FolderItem>;

  getNestedChains(folderId: string): Promise<Chain[]>;

  getServicesUsedByChains(chainIds: string[]): Promise<UsedService[]>;

  getChainsUsedByService(systemId: string): Promise<BaseEntity[]>;

  exportServices(serviceIds: string[], modelIds: string[]): Promise<File>;

  exportSpecifications(
    specificationIds: string[],
    specificationGroupId: string[],
  ): Promise<File>;

  getSpecificationModelSource(id: string): Promise<string>;

  generateApiSpecification(
    deploymentIds: string[],
    snapshotIds: string[],
    chainIds: string[],
    httpTriggerIds: string[],
    externalRoutes: boolean,
    specificationType: ApiSpecificationType,
    format: ApiSpecificationFormat,
  ): Promise<File>;

  getImportPreview(file: File): Promise<ImportPreview>;

  commitImport(
    file: File,
    request?: ImportRequest,
    validateByHash?: boolean,
  ): Promise<ImportCommitResponse>;

  getImportStatus(importId: string): Promise<ImportStatusResponse>;

  getEvents(lastEventId: string): Promise<EventsUpdate>;

  getDeploymentsByEngine(
    domain: string,
    engineHost: string,
  ): Promise<ChainDeployment[]>;

  getEnginesByDomain(domain: string): Promise<Engine[]>;

  loadCatalogActionsLog(
    searchRequest: ActionLogSearchRequest,
  ): Promise<ActionLogResponse>;

  loadVariablesManagementActionsLog(
    searchRequest: ActionLogSearchRequest,
  ): Promise<ActionLogResponse>;

  exportCatalogActionsLog(params: LogExportRequestParams): Promise<Blob>;

  exportVariablesManagementActionsLog(
    params: LogExportRequestParams,
  ): Promise<Blob>;

  getServices(
    modelType: string,
    withSpec: boolean,
  ): Promise<IntegrationSystem[]>;

  createService(system: SystemRequest): Promise<IntegrationSystem>;

  createEnvironment(
    systemId: string,
    envRequest: EnvironmentRequest,
  ): Promise<Environment>;

  updateEnvironment(
    systemId: string,
    environmentId: string,
    envRequest: EnvironmentRequest,
  ): Promise<Environment>;

  deleteEnvironment(systemId: string, environmentId: string): Promise<void>;

  deleteService(serviceId: string): Promise<void>;

  importSystems(
    file: File,
    systemType: IntegrationSystemType,
    systemIds?: string[],
    deployLabel?: string,
    packageName?: string,
    packageVersion?: string,
    packagePartOf?: string,
  ): Promise<ImportSystemResult[]>;

  importSpecification(
    specificationGroupId: string,
    files: File[],
    systemId: string,
  ): Promise<ImportSpecificationResult>;

  getImportSpecificationResult(
    importId: string,
  ): Promise<ImportSpecificationResult>;

  importSpecificationGroup(
    systemId: string,
    name: string,
    files: File[],
    protocol?: string,
  ): Promise<ImportSpecificationResult>;

  deleteSpecificationGroup(id: string): Promise<void>;

  getService(id: string): Promise<IntegrationSystem>;

  updateService(
    id: string,
    data: Partial<IntegrationSystem>,
  ): Promise<IntegrationSystem>;

  getContextServices(): Promise<ContextSystem[]>;

  getContextService(id: string): Promise<ContextSystem>;

  createContextService(
    system: Pick<ContextSystem, "name" | "description">,
  ): Promise<ContextSystem>;

  updateContextService(
    id: string,
    data: Partial<ContextSystem>,
  ): Promise<ContextSystem>;

  deleteContextService(serviceId: string): Promise<void>;

  exportContextServices(serviceIds: string[]): Promise<File>;

  getEnvironments(systemId: string): Promise<Environment[]>;

  getApiSpecifications(systemId: string): Promise<SpecificationGroup[]>;

  updateApiSpecificationGroup(
    id: string,
    data: Partial<SpecificationGroup>,
  ): Promise<SpecificationGroup>;

  getSpecificationModel(
    systemId?: string,
    specificationGroupId?: string,
  ): Promise<Specification[]>;

  updateSpecificationModel(
    id: string,
    data: Partial<Specification>,
  ): Promise<Specification>;

  getOperations(modelId: string): Promise<SystemOperation[]>;

  getOperationInfo(operationId: string): Promise<OperationInfo>;

  deprecateModel(modelId: string): Promise<Specification>;

  deleteSpecificationModel(id: string): Promise<void>;

  getDetailedDesignTemplates(
    includeContent: boolean,
  ): Promise<DetailedDesignTemplate[]>;

  getDetailedDesignTemplate(
    templateId: string,
  ): Promise<DetailedDesignTemplate>;

  createOrUpdateDetailedDesignTemplate(
    name: string,
    content: string,
  ): Promise<DetailedDesignTemplate>;

  deleteDetailedDesignTemplates(ids: string[]): Promise<void>;

  getChainDetailedDesign(
    chainId: string,
    templateId: string,
  ): Promise<ChainDetailedDesign>;

  getChainSequenceDiagram(
    chainId: string,
    diagramModes: DiagramMode[],
  ): Promise<ElementsSequenceDiagrams>;

  getSnapshotSequenceDiagram(
    chainId: string,
    snapshotId: string,
    diagramModes: DiagramMode[],
  ): Promise<ElementsSequenceDiagrams>;

  modifyHttpTriggerProperties(
    chainId: string,
    specificationGroupId: string,
    httpTriggerIds: string[],
  ): Promise<void>;

  getSpecApiFiles(): Promise<SpecApiFile[]>;

  readSpecificationFileContent(
    fileUri: string,
    specificationFilePath: string,
  ): Promise<string>;

  groupElements(chainId: string, elementIds: string[]): Promise<Element>;

  ungroupElements(chainId: string, groupId: string): Promise<Element[]>;

  getExchanges(limit: number): Promise<LiveExchange[]>;

  terminateExchange(
    podIp: string,
    deploymentId: string,
    exchangeId: string,
  ): Promise<void>;

  getValidations(
    filters: EntityFilterModel[],
    searchString: string,
  ): Promise<DiagnosticValidation[]>;

  getValidation(
    validationId: string,
  ): Promise<DiagnosticValidation>;

  runValidations(ids: string[]): Promise<void>;
}

export const api: Api = isVsCode ? new VSCodeExtensionApi() : new RestApi();
