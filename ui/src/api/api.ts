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
  FolderResponse,
  FolderUpdateRequest,
  PatchElementRequest
} from "./apiTypes.ts";
import { RestApi } from "./rest/restApi.ts";

export interface Api {
  getChains(): Promise<Chain[]>;

  getChain(id: string): Promise<Chain>;

  updateChain(id: string, chain: Partial<Chain>): Promise<Chain>;

  createChain(chain: ChainCreationRequest): Promise<Chain>;

  deleteChain(chainId: string): Promise<void>;

  duplicateChain(chainId: string): Promise<Chain>;

  copyChain(chainId: string, folderId?: string): Promise<Chain>;

  moveChain(chainId: string, folderId?: string): Promise<Chain>;

  exportChains(chainIds: string[], exportSubchains: boolean): Promise<File>;

  getLibrary(): Promise<LibraryData>;

  getElements(chainId: string): Promise<Element[]>;

  createElement(
    elementRequest: ElementRequest,
    chainId: string,
  ): Promise<ActionDifference>;

  updateElement(elementRequest: PatchElementRequest, chainId: string, elementId: string): Promise<ActionDifference>;

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

  getRootFolder(): Promise<FolderItem[]>;

  getFolder(folderId: string): Promise<FolderResponse>;

  createFolder(request: FolderUpdateRequest): Promise<FolderResponse>;

  updateFolder(
    folderId: string,
    changes: FolderUpdateRequest,
  ): Promise<FolderResponse>;

  deleteFolder(folderId: string): Promise<void>;

  moveFolder(folderId: string, targetFolderId?: string): Promise<FolderResponse>;

  getNestedChains(folderId: string): Promise<Chain[]>;
}

export const api: Api = new RestApi();
