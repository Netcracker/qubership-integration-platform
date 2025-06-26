import { EntityFilterModel } from "../components/table/filter/filter.ts";
import {
  Chain,
  ChainCreationRequest,
  Connection,
  ElementRequest,
  LibraryData,
  Element,
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
} from "./apiTypes.ts";
import { RestApi } from "./rest/restApi.ts";

export interface Api {
  getChains(): Promise<Chain[]>;

  getChain(id: string): Promise<Chain>;

  updateChain(id: string, chain: Partial<Chain>): Promise<Chain>;

  createChain(chain: ChainCreationRequest): Promise<Chain>;

  deleteChain(chainId: string): Promise<void>;

  deleteChains(chainIds: string[]): Promise<void>;

  duplicateChain(chainId: string): Promise<Chain>;

  copyChain(chainId: string, folderId?: string): Promise<Chain>;

  moveChain(chainId: string, folderId?: string): Promise<Chain>;

  exportAllChains(): Promise<File>;

  exportChains(chainIds: string[], exportSubchains: boolean): Promise<File>;

  getLibrary(): Promise<LibraryData>;

  getElements(chainId: string): Promise<Element[]>;

  getElementTypes(): Promise<ElementFilter[]>;

  createElement(
    elementRequest: ElementRequest,
    chainId: string,
  ): Promise<ActionDifference>;

  updateElement(
    elementRequest: PatchElementRequest,
    chainId: string,
    elementId: string,
  ): Promise<ActionDifference>;

  deleteElement(elementId: string, chainId: string): Promise<ActionDifference>;

  getConnections(chainId: string): Promise<Connection[]>;

  createConnection(
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<ActionDifference>;

  deleteConnection(
    connectionId: string,
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

  getLibraryElementByType(type: string): Promise<Element>;

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

  getPathToFolder(folderId: string): Promise<FolderItem[]>;

  listFolder(request: ListFolderRequest): Promise<(FolderItem | ChainItem)[]>;

  createFolder(request: CreateFolderRequest): Promise<FolderItem>;

  updateFolder(
    folderId: string,
    changes: UpdateFolderRequest,
  ): Promise<FolderItem>;

  deleteFolder(folderId: string): Promise<void>;

  deleteFolders(folderIds: string[]): Promise<void>;

  moveFolder(folderId: string, targetFolderId?: string): Promise<FolderItem>;

  filterChains(filters: EntityFilterModel[]): Promise<(FolderItem | ChainItem)[]>;

  getNestedChains(folderId: string): Promise<Chain[]>;

  getServicesUsedByChains(chainIds: string[]): Promise<UsedService[]>;

  exportServices(serviceIds: string[], modelIds: string[]): Promise<File>;

  getImportPreview(file: File): Promise<ImportPreview>;

  commitImport(
    file: File,
    request?: ImportRequest,
    validateByHash?: boolean,
  ): Promise<ImportCommitResponse>;

  getImportStatus(importId: string): Promise<ImportStatusResponse>;

  getEvents(lastEventId: string): Promise<EventsUpdate>;

  getDeploymentsByEngine(domain: string, engineHost: string): Promise<ChainDeployment[]>;

  getEnginesByDomain(domain: string): Promise<Engine[]>;
}

export const api: Api = new RestApi();
