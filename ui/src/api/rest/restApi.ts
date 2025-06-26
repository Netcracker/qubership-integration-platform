import axios, { AxiosHeaders, AxiosInstance } from "axios";
import rateLimit from "axios-rate-limit";
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
  MaskedFields,
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
  ErrorResponse,
  RestApiError,
  CreateFolderRequest,
  UpdateFolderRequest,
  MoveFolderRequest,
  ListFolderRequest,
  ChainItem,
  Engine,
  ChainDeployment,
  ElementFilter,
} from "../apiTypes.ts";
import { Api } from "../api.ts";
import { getFileFromResponse } from "../../misc/download-utils.ts";
import { EntityFilterModel } from "../../components/table/filter/filter.ts";

export class RestApi implements Api {
  instance: AxiosInstance;

  constructor() {
    this.instance = rateLimit(
      axios.create({
        baseURL: import.meta.env.VITE_GATEWAY,
        timeout: 1000,
        headers: { "content-type": "application/json" },
      }),
      {
        maxRequests: 50,
        perMilliseconds: 1000,
      },
    );

    this.instance.interceptors.response.use(
      (response) => response,
      (error) => {
        const responseCode = error.response?.status ?? 500;
        const responseBody =
          (error?.response?.data as ErrorResponse) ?? undefined;
        const message = responseBody?.errorMessage || error.message;

        return Promise.reject(
          new RestApiError(message, responseCode, responseBody, error),
        );
      },
    );
  }

  getChains = async (): Promise<Chain[]> => {
    const response = await this.instance.get<Chain[]>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains`,
    );
    return response.data;
  };

  getChain = async (id: string): Promise<Chain> => {
    const response = await this.instance.get<Chain>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${id}`,
    );
    return response.data;
  };

  updateChain = async (id: string, chain: Partial<Chain>): Promise<Chain> => {
    const response = await this.instance.put<Chain>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${id}`,
      chain,
    );
    return response.data;
  };

  createChain = async (chain: ChainCreationRequest): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains`,
      chain,
    );
    return response.data;
  };

  deleteChain = async (chainId: string): Promise<void> => {
    await this.instance.delete<Chain>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}`,
    );
  };

  deleteChains = async (chainIds: string[]): Promise<void> => {
    await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/bulk-delete`,
      chainIds,
    );
  };

  duplicateChain = async (chainId: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/duplicate`,
    );
    return response.data;
  };

  copyChain = async (chainId: string, folderId?: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/copy`,
      null,
      {
        params: { targetFolderId: folderId },
      },
    );
    return response.data;
  };

  moveChain = async (chainId: string, folderId?: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/move`,
      null,
      {
        params: { targetFolderId: folderId },
      },
    );
    return response.data;
  };

  exportAllChains = async (): Promise<File> => {
    const response = await this.instance.get<Blob>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/export`,
      {
        responseType: "blob",
      },
    );
    return getFileFromResponse(response);
  };

  exportChains = async (
    chainIds: string[],
    exportSubchains: boolean,
  ): Promise<File> => {
    const response = await this.instance.get<Blob>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/export/chains`,
      {
        params: { chainIds, exportWithSubChains: exportSubchains },
        paramsSerializer: {
          indexes: null,
        },
        responseType: "blob",
      },
    );
    return getFileFromResponse(response);
  };

  getLibrary = async (): Promise<LibraryData> => {
    const response = await this.instance.get<LibraryData>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/library`,
    );
    return response.data;
  };

  getElements = async (chainId: string): Promise<Element[]> => {
    const response = await this.instance.get<Element[]>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/elements`,
    );
    return response.data;
  };

  getElementTypes = async (): Promise<ElementFilter[]> => {
    const response = await this.instance.get<ElementFilter[]>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/library/elements/types`,
    );
    return response.data;
  }

  createElement = async (
    elementRequest: ElementRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/elements`,
      elementRequest,
    );
    return response.data;
  };

  updateElement = async (
    elementRequest: PatchElementRequest,
    chainId: string,
    elementId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.patch(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/elements/${elementId}`,
      elementRequest,
    );
    return response.data;
  };

  deleteElement = async (
    elementId: string,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.delete(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/elements`,
      {
        params: { elementsIds: elementId }, //TODO send array
      },
    );
    return response.data;
  };

  getConnections = async (chainId: string): Promise<Connection[]> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/dependencies`,
    );
    return response.data;
  };

  createConnection = async (
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/dependencies`,
      connectionRequest,
    );
    return response.data;
  };

  deleteConnection = async (
    connectionId: string,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.delete(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/dependencies`,
      {
        params: { dependenciesIds: connectionId }, //TODO send array
      },
    );
    return response.data;
  };

  createSnapshot = async (chainId: string): Promise<Snapshot> => {
    const response = await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/snapshots`,
    );
    return response.data;
  };

  getSnapshots = async (chainId: string): Promise<Snapshot[]> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/snapshots`,
    );
    return response.data;
  };

  getSnapshot = async (snapshotId: string): Promise<Snapshot> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/chainId/snapshots/${snapshotId}`,
    );
    return response.data;
  };

  deleteSnapshot = async (snapshotId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/chainId/snapshots/${snapshotId}`,
    );
  };

  deleteSnapshots = async (snapshotIds: string[]): Promise<void> => {
    await this.instance.post(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/snapshots/bulk-delete`,
      snapshotIds,
    );
  };

  revertToSnapshot = async (
    chainId: string,
    snapshotId: string,
  ): Promise<Snapshot> => {
    const response = await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/snapshots/${snapshotId}/revert`,
    );
    return response.data;
  };

  updateSnapshot = async (
    snapshotId: string,
    name: string,
    labels: EntityLabel[],
  ): Promise<Snapshot> => {
    const response = await this.instance.put(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/chainId/snapshots/${snapshotId}`,
      {
        name,
        labels,
      },
    );
    return response.data;
  };

  getLibraryElementByType = async (type: string): Promise<Element> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/library/${type}`,
    );
    return response.data;
  };

  getDeployments = async (chainId: string): Promise<Deployment[]> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/deployments`,
    );
    return response.data;
  };

  createDeployment = async (
    chainId: string,
    request: CreateDeploymentRequest,
  ): Promise<Deployment> => {
    const response = await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/deployments`,
      request,
    );
    return response.data;
  };

  deleteDeployment = async (deploymentId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/chainId/deployments/${deploymentId}`,
    );
  };

  getDomains = async (): Promise<EngineDomain[]> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/domains`,
    );
    return response.data;
  };

  getLoggingSettings = async (
    chainId: string,
  ): Promise<ChainLoggingSettings> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/properties/logging`,
    );
    return response.data;
  };

  setLoggingProperties = async (
    chainId: string,
    properties: ChainLoggingProperties,
  ): Promise<void> => {
    await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/properties/logging`,
      properties,
    );
  };

  deleteLoggingSettings = async (chainId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/properties/logging`,
    );
  };

  getMaskedFields = async (chainId: string): Promise<MaskedField[]> => {
    const response = await this.instance.get<MaskedFields>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking`,
    );
    return response.data.fields;
  };

  createMaskedField = async (
    chainId: string,
    maskedField: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    const response = await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking`,
      maskedField,
    );
    return response.data;
  };

  deleteMaskedFields = async (
    chainId: string,
    maskedFieldIds: string[],
  ): Promise<void> => {
    await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking/field`,
      maskedFieldIds,
    );
  };

  deleteMaskedField = async (
    chainId: string,
    maskedFieldId: string,
  ): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking/field/${maskedFieldId}`,
    );
  };

  updateMaskedField = async (
    chainId: string,
    maskedFieldId: string,
    changes: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    const response = await this.instance.put(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking/field/${maskedFieldId}`,
      changes,
    );
    return response.data;
  };

  getSessions = async (
    chainId: string | undefined,
    filters: SessionFilterAndSearchRequest,
    paginationOptions: PaginationOptions,
  ): Promise<SessionSearchResponse> => {
    const prefix = `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions`;
    const url = chainId ? `${prefix}/chains/${chainId}` : prefix;
    const params: Record<string, string> = {};
    if (paginationOptions.offset) {
      params["offset"] = paginationOptions.offset.toString(10);
    }
    if (paginationOptions.count) {
      params["count"] = paginationOptions.count.toString(10);
    }
    const response = await this.instance.post(url, filters, { params });
    return response.data;
  };

  deleteSessions = async (sessionIds: string[]): Promise<void> => {
    await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions/bulk-delete`,
      sessionIds,
    );
  };

  deleteSessionsByChainId = async (
    chainId: string | undefined,
  ): Promise<void> => {
    const prefix = `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions`;
    const url = chainId ? `${prefix}/chains/${chainId}` : prefix;
    await this.instance.delete(url);
  };

  exportSessions = async (sessionIds: string[]): Promise<File> => {
    const response = await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions/export`,
      sessionIds,
      { responseType: "blob" },
    );
    return getFileFromResponse(response);
  };

  importSessions = async (files: File[]): Promise<Session[]> => {
    const formData: FormData = new FormData();
    files.forEach((file) => formData.append("files", file, file.name));
    const headers = new AxiosHeaders();
    headers.set("Content-Type", "multipart/form-data");
    headers.set("accept", "*/*");
    const response = await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions/import`,
      formData,
      { headers },
    );
    return response.data;
  };

  retryFromLastCheckpoint = async (
    chainId: string,
    sessionId: string,
  ): Promise<void> => {
    await this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/engine/chains/${chainId}/sessions/${sessionId}/retry`,
      null,
    );
  };

  getSession = async (sessionId: string): Promise<Session> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions/${sessionId}`,
    );
    return response.data;
  };

  getCheckpointSessions = async (
    sessionIds: string[],
  ): Promise<CheckpointSession[]> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/engine/sessions`,
      {
        params: { ids: sessionIds },
        paramsSerializer: {
          indexes: null,
        },
      },
    );
    return response.data;
  };

  retrySessionFromLastCheckpoint = async (
    chainId: string,
    sessionId: string,
  ): Promise<void> => {
    return this.instance.post(
      `/api/v1/${import.meta.env.VITE_API_APP}/engine/chains/${chainId}/sessions/${sessionId}/retry`,
      null,
    );
  };

  getFolder = async (folderId: string): Promise<FolderItem> => {
    const response = await this.instance.get(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/folders/${folderId}`,
    );
    return response.data;
  };

  getPathToFolder = async (folderId: string): Promise<FolderItem[]> => {
    const response = await this.instance.get(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/folders/${folderId}/path`,
    );
    return response.data;
  };

  createFolder = async (request: CreateFolderRequest): Promise<FolderItem> => {
    const response = await this.instance.post<FolderItem>(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/folders`,
      request,
    );
    return response.data;
  };

  updateFolder = async (
    folderId: string,
    changes: UpdateFolderRequest,
  ): Promise<FolderItem> => {
    const response = await this.instance.put<FolderItem>(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/folders/${folderId}`,
      changes,
    );
    return response.data;
  };

  deleteFolder = async (folderId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/folders/${folderId}`,
    );
  };

  deleteFolders = async (folderIds: string[]): Promise<void> => {
    await this.instance.post(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/folders/bulk-delete`,
      folderIds,
    );
  };

  listFolder = async (
    request: ListFolderRequest,
  ): Promise<(FolderItem | ChainItem)[]> => {
    const response = await this.instance.post<(FolderItem | ChainItem)[]>(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/folders/list`,
      request,
    );
    return response.data;
  };

  moveFolder = async (
    folderId: string,
    targetFolderId?: string,
  ): Promise<FolderItem> => {
    const request: MoveFolderRequest = {
      id: folderId,
      targetId: targetFolderId,
    };
    const response = await this.instance.post<FolderItem>(
      `/api/v2/${import.meta.env.VITE_API_APP}/catalog/folders/move`,
      request,
    );
    return response.data;
  };

  filterChains = async (request: EntityFilterModel[]): Promise<(FolderItem | ChainItem)[]> => {
    const response = await this.instance.post<(FolderItem | ChainItem)[]>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/folders/filter`,
      request,
    );

    return response.data;
  }

  getNestedChains = async (folderId: string): Promise<Chain[]> => {
    const response = await this.instance.get<Chain[]>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/folders/${folderId}/chains`,
    );
    return response.data;
  };

  getServicesUsedByChains = async (
    chainIds: string[],
  ): Promise<UsedService[]> => {
    const response = await this.instance.get<UsedService[]>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/used-systems`,
      {
        params: { chainIds },
        paramsSerializer: {
          indexes: null,
        },
      },
    );
    return response.data;
  };

  exportServices = async (
    serviceIds: string[],
    modelIds: string[],
  ): Promise<File> => {
    const formData: FormData = new FormData();
    if (serviceIds?.length) {
      formData.append("systemIds", serviceIds.join(","));
    }
    if (modelIds?.length) {
      formData.append("usedSystemModelIds", modelIds.join(","));
    }
    const response = await this.instance.post<Blob>(
      `/api/v1/${import.meta.env.VITE_API_APP}/systems-catalog/export/system`,
      formData,
      {
        headers: {
          "Content-Type": "multipart/form-data",
          accept: "*/*",
        },
        responseType: "blob",
      },
    );
    return getFileFromResponse(response);
  };

  getImportPreview = async (file: File): Promise<ImportPreview> => {
    const formData: FormData = new FormData();
    formData.append("file", file, file.name);
    const response = await this.instance.post(
      `/api/${import.meta.env.VITE_API_APP}/v3/import/preview`,
      formData,
      {
        headers: {
          "Content-Type": "multipart/form-data",
          accept: "*/*",
        },
      },
    );
    return response.data;
  };

  commitImport = async (
    file: File,
    request?: ImportRequest,
    validateByHash?: boolean,
  ): Promise<ImportCommitResponse> => {
    const formData: FormData = new FormData();
    formData.append("file", file, file.name);
    if (request) {
      formData.append("importRequest", JSON.stringify(request));
    }
    if (validateByHash !== undefined) {
      formData.append("validateByHash", validateByHash.toString());
    }
    const response = await this.instance.post(
      `/api/${import.meta.env.VITE_API_APP}/v3/import`,
      formData,
      {
        headers: {
          "Content-Type": "multipart/form-data",
          accept: "*/*",
        },
      },
    );
    return response.data;
  };

  getImportStatus = async (importId: string): Promise<ImportStatusResponse> => {
    const response = await this.instance.get<ImportStatusResponse>(
      `/api/${import.meta.env.VITE_API_APP}/v3/import/${importId}`,
    );
    return response.data;
  };

  getEvents = async (lastEventId: string): Promise<EventsUpdate> => {
    const response = await this.instance.get(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/events`,
      {
        params: {
          lastEventId: lastEventId,
        },
      },
    );
    return response.data;
  };

  getDeploymentsByEngine = async (domain: string, engineHost: string): Promise<ChainDeployment[]> => {
    const response = await this.instance.get<ChainDeployment[]>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/domains/${domain}/engines/${engineHost}/deployments`
    );
    return response.data;
  };

  getEnginesByDomain = async (domain: string): Promise<Engine[]> => {
    const response = await this.instance.get<Engine[]>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/domains/${domain}/engines`
    );
    return response.data;
  };
}
