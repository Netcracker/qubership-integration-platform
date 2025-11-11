import axios, { AxiosHeaders, AxiosInstance } from "axios";
import rateLimit from "axios-rate-limit";
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
  isErrorResponse,
  ElementFilter,
  ActionLogSearchRequest,
  ActionLogResponse,
  LogExportRequestParams,
  IntegrationSystem,
  SystemRequest,
  EnvironmentRequest,
  Environment,
  Specification,
  SpecificationGroup,
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
} from "../apiTypes.ts";
import { Api } from "../api.ts";
import { getFileFromResponse } from "../../misc/download-utils.ts";
import qs from "qs";
import { getAppName } from "../../appConfig.ts";

export class RestApi implements Api {
  instance: AxiosInstance;

  constructor() {
    this.instance = rateLimit(
      axios.create({
        baseURL: import.meta.env.VITE_GATEWAY,
        timeout: 2000,
        headers: { "content-type": "application/json" },
      }),
      {
        maxRequests: 50,
        perMilliseconds: 1000,
      },
    );

    this.instance.interceptors.response.use(
      (response) => response,
      async (error) => {
        let message = "";
        let responseCode = 500;
        let responseBody: ErrorResponse | undefined = undefined;
        if (axios.isAxiosError(error)) {
          responseCode = error.response?.status ?? 500;
          const data: unknown = error.response?.data;
          if (isErrorResponse(data)) {
            responseBody = data;
            message = responseBody?.errorMessage;
          } else if (typeof Blob !== 'undefined' && data instanceof Blob) {
            try {
              const text = await data.text();
              const parsed: unknown = JSON.parse(text);
              if (isErrorResponse(parsed)) {
                responseBody = parsed;
                message = parsed.errorMessage;
              }
            } catch {
              if (!message) {
                message = error.response?.statusText || `HTTP ${responseCode}`;
              }
            }
          }
        }
        return Promise.reject(
          new RestApiError(message, responseCode, responseBody, error),
        );
      },
    );
  }

  getChains = async (): Promise<Chain[]> => {
    const response = await this.instance.get<Chain[]>(
      `/api/v1/${getAppName()}/catalog/chains`,
    );
    return response.data;
  };

  getChain = async (id: string): Promise<Chain> => {
    const response = await this.instance.get<Chain>(
      `/api/v1/${getAppName()}/catalog/chains/${id}`,
    );
    return response.data;
  };

  updateChain = async (id: string, chain: Partial<Chain>): Promise<Chain> => {
    const response = await this.instance.put<Chain>(
      `/api/v1/${getAppName()}/catalog/chains/${id}`,
      chain,
    );
    return response.data;
  };

  createChain = async (chain: ChainCreationRequest): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `/api/v1/${getAppName()}/catalog/chains`,
      chain,
    );
    return response.data;
  };

  deleteChain = async (chainId: string): Promise<void> => {
    await this.instance.delete<Chain>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}`,
    );
  };

  deleteChains = async (chainIds: string[]): Promise<void> => {
    await this.instance.post(
      `/api/v1/${getAppName()}/catalog/chains/bulk-delete`,
      chainIds,
    );
  };

  duplicateChain = async (chainId: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/duplicate`,
    );
    return response.data;
  };

  copyChain = async (chainId: string, folderId?: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/copy`,
      null,
      {
        params: { targetFolderId: folderId },
      },
    );
    return response.data;
  };

  moveChain = async (chainId: string, folder?: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/move`,
      null,
      {
        params: { targetFolderId: folder },
      },
    );
    return response.data;
  };

  exportAllChains = async (): Promise<File> => {
    const response = await this.instance.get<Blob>(
      `/api/v1/${getAppName()}/catalog/export`,
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
      `/api/v1/${getAppName()}/catalog/export/chains`,
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
      `/api/v1/${getAppName()}/catalog/library`,
    );
    return response.data;
  };

  getElementTypes = async (): Promise<ElementFilter[]> => {
    const response = await this.instance.get<ElementFilter[]>(
      `/api/v1/${getAppName()}/catalog/library/elements/types`,
    );
    return response.data;
  };

  getElements = async (chainId: string): Promise<Element[]> => {
    const response = await this.instance.get<Element[]>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/elements`,
    );
    return response.data;
  };

  getElementsByType = async (
    chainId: string,
    elementType: string,
  ): Promise<ElementWithChainName[]> => {
    const response = await this.instance.get<ElementWithChainName[]>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/elements/type/${elementType}`,
    );
    return response.data;
  };

  createElement = async (
    elementRequest: CreateElementRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.post<ActionDifference>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/elements`,
      elementRequest,
    );
    return response.data;
  };

  updateElement = async (
    elementRequest: PatchElementRequest,
    chainId: string,
    elementId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.patch<ActionDifference>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/elements/${elementId}`,
      elementRequest,
    );
    return response.data;
  };

  transferElement = async (
    transferElementRequest: TransferElementRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.post<ActionDifference>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/elements/transfer`,
      transferElementRequest,
    );
    return response.data;
  };

  deleteElements = async (
    elementIds: string[],
    chainId: string,
  ): Promise<ActionDifference> => {
    const elementsIdsParam = elementIds.join(",");
    const response = await this.instance.delete<ActionDifference>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/elements`,
      {
        params: { elementsIds: elementsIdsParam },
      },
    );
    return response.data;
  };

  modifyHttpTriggerProperties = async (
    chainId: string,
    specificationGroupId: string,
    httpTriggerIds: string[],
  ): Promise<void> => {
    await this.instance.put(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/elements/properties-modification`,
      {},
      {
        params: {
          specificationGroupId: specificationGroupId,
          httpTriggerIds: httpTriggerIds,
        },
        paramsSerializer: (params) =>
          qs.stringify(params, { arrayFormat: "repeat" }),
      },
    );
  };

  getConnections = async (chainId: string): Promise<Connection[]> => {
    const response = await this.instance.get<Connection[]>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/dependencies`,
    );
    return response.data;
  };

  createConnection = async (
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.post<ActionDifference>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/dependencies`,
      connectionRequest,
    );
    return response.data;
  };

  deleteConnections = async (
    connectionIds: string[],
    chainId: string,
  ): Promise<ActionDifference> => {
    const connectionIdsParam = connectionIds.join(",");
    const response = await this.instance.delete<ActionDifference>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/dependencies`,
      {
        params: { dependenciesIds: connectionIdsParam },
      },
    );
    return response.data;
  };

  createSnapshot = async (chainId: string): Promise<Snapshot> => {
    const response = await this.instance.post<Snapshot>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/snapshots`,
    );
    return response.data;
  };

  getSnapshots = async (chainId: string): Promise<Snapshot[]> => {
    const response = await this.instance.get<Snapshot[]>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/snapshots`,
    );
    return response.data;
  };

  getSnapshot = async (snapshotId: string): Promise<Snapshot> => {
    const response = await this.instance.get<Snapshot>(
      `/api/v1/${getAppName()}/catalog/chains/chainId/snapshots/${snapshotId}`,
    );
    return response.data;
  };

  deleteSnapshot = async (snapshotId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${getAppName()}/catalog/chains/chainId/snapshots/${snapshotId}`,
    );
  };

  deleteSnapshots = async (snapshotIds: string[]): Promise<void> => {
    await this.instance.post(
      `/api/v2/${getAppName()}/catalog/snapshots/bulk-delete`,
      snapshotIds,
    );
  };

  revertToSnapshot = async (
    chainId: string,
    snapshotId: string,
  ): Promise<Snapshot> => {
    const response = await this.instance.post<Snapshot>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/snapshots/${snapshotId}/revert`,
    );
    return response.data;
  };

  updateSnapshot = async (
    snapshotId: string,
    name: string,
    labels: EntityLabel[],
  ): Promise<Snapshot> => {
    const response = await this.instance.put<Snapshot>(
      `/api/v1/${getAppName()}/catalog/chains/chainId/snapshots/${snapshotId}`,
      {
        name,
        labels,
      },
    );
    return response.data;
  };

  getLibraryElementByType = async (type: string): Promise<LibraryElement> => {
    const response = await this.instance.get<LibraryElement>(
      `/api/v1/${getAppName()}/catalog/library/${type}`,
    );
    return response.data;
  };

  getDeployments = async (chainId: string): Promise<Deployment[]> => {
    const response = await this.instance.get<Deployment[]>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/deployments`,
    );
    return response.data;
  };

  createDeployment = async (
    chainId: string,
    request: CreateDeploymentRequest,
  ): Promise<Deployment> => {
    const response = await this.instance.post<Deployment>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/deployments`,
      request,
    );
    return response.data;
  };

  deleteDeployment = async (deploymentId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${getAppName()}/catalog/chains/chainId/deployments/${deploymentId}`,
    );
  };

  getDomains = async (): Promise<EngineDomain[]> => {
    const response = await this.instance.get<EngineDomain[]>(
      `/api/v1/${getAppName()}/catalog/domains`,
    );
    return response.data;
  };

  getLoggingSettings = async (
    chainId: string,
  ): Promise<ChainLoggingSettings> => {
    const response = await this.instance.get<ChainLoggingSettings>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/properties/logging`,
    );
    return response.data;
  };

  setLoggingProperties = async (
    chainId: string,
    properties: ChainLoggingProperties,
  ): Promise<void> => {
    await this.instance.post(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/properties/logging`,
      properties,
    );
  };

  deleteLoggingSettings = async (chainId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/properties/logging`,
    );
  };

  getMaskedFields = async (chainId: string): Promise<MaskedField[]> => {
    const response = await this.instance.get<MaskedFields>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/masking`,
    );
    return response.data.fields;
  };

  createMaskedField = async (
    chainId: string,
    maskedField: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    const response = await this.instance.post<MaskedField>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/masking`,
      maskedField,
    );
    return response.data;
  };

  deleteMaskedFields = async (
    chainId: string,
    maskedFieldIds: string[],
  ): Promise<void> => {
    await this.instance.post(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/masking/field`,
      maskedFieldIds,
    );
  };

  deleteMaskedField = async (
    chainId: string,
    maskedFieldId: string,
  ): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/masking/field/${maskedFieldId}`,
    );
  };

  updateMaskedField = async (
    chainId: string,
    maskedFieldId: string,
    changes: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    const response = await this.instance.put<MaskedField>(
      `/api/v1/${getAppName()}/catalog/chains/${chainId}/masking/field/${maskedFieldId}`,
      changes,
    );
    return response.data;
  };

  getSessions = async (
    chainId: string | undefined,
    filters: SessionFilterAndSearchRequest,
    paginationOptions: PaginationOptions,
  ): Promise<SessionSearchResponse> => {
    const prefix = `/api/v1/${getAppName()}/sessions-management/sessions`;
    const url = chainId ? `${prefix}/chains/${chainId}` : prefix;
    const params: Record<string, string> = {};
    if (paginationOptions.offset) {
      params["offset"] = paginationOptions.offset.toString(10);
    }
    if (paginationOptions.count) {
      params["count"] = paginationOptions.count.toString(10);
    }
    const response = await this.instance.post<SessionSearchResponse>(
      url,
      filters,
      { params },
    );
    return response.data;
  };

  deleteSessions = async (sessionIds: string[]): Promise<void> => {
    await this.instance.post(
      `/api/v1/${getAppName()}/sessions-management/sessions/bulk-delete`,
      sessionIds,
    );
  };

  deleteSessionsByChainId = async (
    chainId: string | undefined,
  ): Promise<void> => {
    const prefix = `/api/v1/${getAppName()}/sessions-management/sessions`;
    const url = chainId ? `${prefix}/chains/${chainId}` : prefix;
    await this.instance.delete(url);
  };

  exportSessions = async (sessionIds: string[]): Promise<File> => {
    const response = await this.instance.post<Blob>(
      `/api/v1/${getAppName()}/sessions-management/sessions/export`,
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
    const response = await this.instance.post<Session[]>(
      `/api/v1/${getAppName()}/sessions-management/sessions/import`,
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
      `/api/v1/${getAppName()}/engine/chains/${chainId}/sessions/${sessionId}/retry`,
      null,
    );
  };

  getSession = async (sessionId: string): Promise<Session> => {
    const response = await this.instance.get<Session>(
      `/api/v1/${getAppName()}/sessions-management/sessions/${sessionId}`,
    );
    return response.data;
  };

  getCheckpointSessions = async (
    sessionIds: string[],
  ): Promise<CheckpointSession[]> => {
    const response = await this.instance.get<CheckpointSession[]>(
      `/api/v1/${getAppName()}/engine/sessions`,
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
      `/api/v1/${getAppName()}/engine/chains/${chainId}/sessions/${sessionId}/retry`,
      null,
    );
  };

  getFolder = async (folderId: string): Promise<FolderItem> => {
    const response = await this.instance.get<FolderItem>(
      `/api/v2/${getAppName()}/catalog/folders/${folderId}`,
    );
    return response.data;
  };

  getRootFolders = async (
    filter: string,
    openedFolderId: string,
  ): Promise<FolderItem[]> => {
    const response = await this.instance.get<FolderItem[]>(
      `/api/v1/${getAppName()}/catalog/folders/`,
      {
        params: {
          filter: filter,
          openedFolderId: openedFolderId,
        },
      },
    );
    return response.data;
  };

  getPathToFolder = async (folderId: string): Promise<FolderItem[]> => {
    const response = await this.instance.get<FolderItem[]>(
      `/api/v2/${getAppName()}/catalog/folders/${folderId}/path`,
    );
    return response.data;
  };

  getPathToFolderByName = async (folderName: string): Promise<FolderItem[]> => {
    const response = await this.instance.get<FolderItem[]>(
      `/api/v2/${getAppName()}/catalog/folders/path?name=${folderName}`,
    );
    return response.data;
  };

  createFolder = async (request: CreateFolderRequest): Promise<FolderItem> => {
    const response = await this.instance.post<FolderItem>(
      `/api/v2/${getAppName()}/catalog/folders`,
      request,
    );
    return response.data;
  };

  updateFolder = async (
    folderId: string,
    changes: UpdateFolderRequest,
  ): Promise<FolderItem> => {
    const response = await this.instance.put<FolderItem>(
      `/api/v2/${getAppName()}/catalog/folders/${folderId}`,
      changes,
    );
    return response.data;
  };

  deleteFolder = async (folderId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v2/${getAppName()}/catalog/folders/${folderId}`,
    );
  };

  deleteFolders = async (folderIds: string[]): Promise<void> => {
    await this.instance.post(
      `/api/v2/${getAppName()}/catalog/folders/bulk-delete`,
      folderIds,
    );
  };

  listFolder = async (
    request: ListFolderRequest,
  ): Promise<(FolderItem | ChainItem)[]> => {
    const response = await this.instance.post<(FolderItem | ChainItem)[]>(
      `/api/v2/${getAppName()}/catalog/folders/list`,
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
      `/api/v2/${getAppName()}/catalog/folders/move`,
      request,
    );
    return response.data;
  };

  getNestedChains = async (folderId: string): Promise<Chain[]> => {
    const response = await this.instance.get<Chain[]>(
      `/api/v1/${getAppName()}/catalog/folders/${folderId}/chains`,
    );
    return response.data;
  };

  getServicesUsedByChains = async (
    chainIds: string[],
  ): Promise<UsedService[]> => {
    const response = await this.instance.get<UsedService[]>(
      `/api/v1/${getAppName()}/catalog/chains/used-systems`,
      {
        params: { chainIds },
        paramsSerializer: {
          indexes: null,
        },
      },
    );
    return response.data;
  };

  getChainsUsedByService = async (systemId: string): Promise<BaseEntity[]> => {
    const response = await this.instance.get<BaseEntity[]>(
      `/api/v1/${getAppName()}/catalog/chains/systems/${systemId}`,
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
      `/api/v1/${getAppName()}/systems-catalog/export/system`,
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

  exportSpecifications = async (
    specificationIds: string[],
    specificationGroupId: string[],
  ): Promise<File> => {
    const params: Record<string, string> = {};
    if (specificationIds?.length) {
      params["specificationIds"] = specificationIds.join(",");
    }
    if (specificationGroupId?.length) {
      params["specificationGroupId"] = specificationGroupId.join(",");
    }
    const response = await this.instance.get<Blob>(
      `/api/v1/${getAppName()}/systems-catalog/export/specifications`,
      {
        params,
        headers: {
          accept: "*/*",
        },
        responseType: "blob",
      },
    );
    return getFileFromResponse(response);
  };

  generateApiSpecification = async (
    deploymentIds: string[],
    snapshotIds: string[],
    chainIds: string[],
    httpTriggerIds: string[],
    externalRoutes: boolean,
    specificationType: ApiSpecificationType,
    format: ApiSpecificationFormat,
  ): Promise<File> => {
    const params: Record<string, string> = {};
    if (deploymentIds?.length) {
      params["deploymentIds"] = deploymentIds.join(",");
    }
    if (snapshotIds?.length) {
      params["snapshotIds"] = snapshotIds.join(",");
    }
    if (chainIds?.length) {
      params["chainIds"] = chainIds.join(",");
    }
    if (httpTriggerIds?.length) {
      params["httpTriggerIds"] = httpTriggerIds.join(",");
    }

    const response = await this.instance.get<Blob>(
      `/api/v1/${getAppName()}/catalog/export/api-spec`,
      {
        params: { ...params, externalRoutes, specificationType, format },
        headers: {
          accept: "*/*",
        },
        responseType: "blob",
      },
    );
    const contentType = (response.headers?.["content-type"] as string | undefined)?.toLowerCase() ?? "";
    if (contentType.includes("application/json")) {
      try {
        const text = await response.data.text();
        const parsed = JSON.parse(text) as unknown;
        if (isErrorResponse(parsed)) {
          throw new RestApiError(parsed.errorMessage, response.status, parsed);
        }
      } catch (e) {
        if (e instanceof RestApiError) throw e;
        throw new RestApiError("Failed to generate API specification", response.status);
      }
    }
    return getFileFromResponse(response);
  };

  getServices = async (
    modelType: string,
    withSpec: boolean,
  ): Promise<IntegrationSystem[]> => {
    const response = await this.instance.get<IntegrationSystem[]>(
      `/api/v1/${getAppName()}/systems-catalog/systems`,
      {
        params: { modelType, withSpec },
      },
    );
    return response.data;
  };

  createService = async (system: SystemRequest): Promise<IntegrationSystem> => {
    const response = await this.instance.post<IntegrationSystem>(
      `/api/v1/${getAppName()}/systems-catalog/systems`,
      system,
    );
    return response.data;
  };

  createEnvironment = async (
    systemId: string,
    envRequest: EnvironmentRequest,
  ): Promise<Environment> => {
    const response = await this.instance.post<Environment>(
      `/api/v1/${getAppName()}/systems-catalog/systems/${systemId}/environments`,
      envRequest,
    );
    return response.data;
  };

  updateEnvironment = async (
    systemId: string,
    environmentId: string,
    envRequest: EnvironmentRequest,
  ): Promise<Environment> => {
    const response = await this.instance.put<Environment>(
      `/api/v1/${getAppName()}/systems-catalog/systems/${systemId}/environments/${environmentId}`,
      envRequest,
    );
    return response.data;
  };

  deleteEnvironment = async (
    systemId: string,
    environmentId: string,
  ): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${getAppName()}/systems-catalog/systems/${systemId}/environments/${environmentId}`,
    );
  };

  deleteService = async (serviceId: string): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${getAppName()}/systems-catalog/systems/${serviceId}`,
    );
  };

  getImportPreview = async (file: File): Promise<ImportPreview> => {
    const formData: FormData = new FormData();
    formData.append("file", file, file.name);
    const response = await this.instance.post<ImportPreview>(
      `/api/${getAppName()}/v3/import/preview`,
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
    const response = await this.instance.post<ImportCommitResponse>(
      `/api/${getAppName()}/v3/import`,
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
      `/api/${getAppName()}/v3/import/${importId}`,
    );
    return response.data;
  };

  getEvents = async (lastEventId: string): Promise<EventsUpdate> => {
    const response = await this.instance.get<EventsUpdate>(
      `/api/v1/${getAppName()}/catalog/events`,
      {
        params: {
          lastEventId: lastEventId,
        },
      },
    );
    return response.data;
  };

  getDeploymentsByEngine = async (
    domain: string,
    engineHost: string,
  ): Promise<ChainDeployment[]> => {
    const response = await this.instance.get<ChainDeployment[]>(
      `/api/v1/${getAppName()}/catalog/domains/${domain}/engines/${engineHost}/deployments`,
    );
    return response.data;
  };

  getEnginesByDomain = async (domain: string): Promise<Engine[]> => {
    const response = await this.instance.get<Engine[]>(
      `/api/v1/${getAppName()}/catalog/domains/${domain}/engines`,
    );
    return response.data;
  };

  loadCatalogActionsLog = async (
    searchRequest: ActionLogSearchRequest,
  ): Promise<ActionLogResponse> => {
    return await this.loadActionsLog("catalog", searchRequest);
  };

  loadVariablesManagementActionsLog = async (
    searchRequest: ActionLogSearchRequest,
  ): Promise<ActionLogResponse> => {
    return await this.loadActionsLog("variables-management", searchRequest);
  };

  loadActionsLog = async (
    serviceName: string,
    searchRequest: ActionLogSearchRequest,
  ): Promise<ActionLogResponse> => {
    const response = await this.instance.post<ActionLogResponse>(
      `/api/v1/${getAppName()}/${serviceName}/actions-log`,
      searchRequest,
    );
    return response.data;
  };

  exportCatalogActionsLog = async (
    params: LogExportRequestParams,
  ): Promise<Blob> => {
    return await this.exportActionLog("catalog", params);
  };

  exportVariablesManagementActionsLog = async (
    params: LogExportRequestParams,
  ): Promise<Blob> => {
    return await this.exportActionLog("variables-management", params);
  };

  exportActionLog = async (
    serviceName: string,
    params: LogExportRequestParams,
  ): Promise<Blob> => {
    const response = await this.instance.get<Blob>(
      `/api/v1/${getAppName()}/${serviceName}/actions-log/export`,
      {
        responseType: "blob",
        params: params,
      },
    );
    return response.data;
  };

  getService = async (id: string): Promise<IntegrationSystem> => {
    const response = await this.instance.get<IntegrationSystem>(
      `/api/v1/${getAppName()}/systems-catalog/systems/${id}`,
    );
    return response.data;
  };

  updateService = async (
    id: string,
    data: Partial<IntegrationSystem>,
  ): Promise<IntegrationSystem> => {
    const response = await this.instance.put<IntegrationSystem>(
      `/api/v1/${getAppName()}/systems-catalog/systems/${id}`,
      data,
    );
    return response.data;
  };

  getEnvironments = async (systemId: string): Promise<Environment[]> => {
    const response = await this.instance.get<Environment[]>(
      `/api/v1/${getAppName()}/systems-catalog/systems/${systemId}/environments`,
    );
    return response.data;
  };

  getApiSpecifications = async (
    systemId: string,
  ): Promise<SpecificationGroup[]> => {
    const response = await this.instance.get<SpecificationGroup[]>(
      `/api/v1/${getAppName()}/systems-catalog/specificationGroups`,
      {
        params: {
          systemId: systemId,
        },
      },
    );
    return response.data;
  };

  updateApiSpecificationGroup = async (
    id: string,
    data: Partial<SpecificationGroup>,
  ): Promise<SpecificationGroup> => {
    const response = await this.instance.patch<SpecificationGroup>(
      `/api/v1/${getAppName()}/systems-catalog/specificationGroups/${id}`,
      data,
    );
    return response.data;
  };

  deleteSpecificationGroup = async (id: string): Promise<void> => {
    await this.instance.delete<SpecificationGroup>(
      `/api/v1/${getAppName()}/systems-catalog/specificationGroups/${id}`,
    );
  };

  updateSpecificationModel = async (
    id: string,
    data: Partial<Specification>,
  ): Promise<Specification> => {
    const response = await this.instance.patch<Specification>(
      `/api/v1/${getAppName()}/systems-catalog/models/${id}`,
      data,
    );
    return response.data;
  };

  getSpecificationModelSource = async (id: string): Promise<string> => {
    const response = await this.instance.get<string>(
      `/api/v1/${getAppName()}/systems-catalog/models/${id}/source`,
    );
    return response.data;
  };

  deleteSpecificationModel = async (id: string): Promise<void> => {
    await this.instance.delete(
      `/api/v1/${getAppName()}/systems-catalog/models/${id}`,
    );
  };

  getSpecificationModel = async (
    systemId?: string,
    specificationGroupId?: string,
  ): Promise<Specification[]> => {
    const response = await this.instance.get<Specification[]>(
      `/api/v1/${getAppName()}/systems-catalog/models`,
      {
        params: {
          systemId: systemId,
          specificationGroupId: specificationGroupId,
        },
      },
    );
    return response.data;
  };

  deprecateModel = async (modelId: string): Promise<Specification> => {
    const response = await this.instance.post<Specification>(
      `/api/v1/${getAppName()}/systems-catalog/models/deprecated`,
      modelId,
      {
        headers: { "Content-Type": "text/plain" },
      },
    );
    return response.data;
  };

  getOperations = async (modelId: string): Promise<SystemOperation[]> => {
    const response = await this.instance.get<SystemOperation[]>(
      `/api/v1/${getAppName()}/systems-catalog/operations`,
      {
        params: {
          modelId,
        },
      },
    );
    return response.data;
  };

  getOperationInfo = async (operationId: string): Promise<OperationInfo> => {
    const response = await this.instance.get<OperationInfo>(
      `/api/v1/${getAppName()}/systems-catalog/operations/${encodeURIComponent(operationId)}/info`,
    );
    return response.data;
  };

  importSystems = async (
    file: File,
    systemIds?: string[],
    deployLabel?: string,
    packageName?: string,
    packageVersion?: string,
    packagePartOf?: string,
  ): Promise<ImportSystemResult[]> => {
    const formData = new FormData();
    formData.append("files", file);
    if (systemIds && systemIds.length > 0) {
      for (const id of systemIds) {
        formData.append("systemIds", id);
      }
    }
    if (deployLabel) {
      formData.append("deployLabel", deployLabel);
    }
    const headers: Record<string, string> = {};
    if (packageName) headers["X-SR-PACKAGE-NAME"] = packageName;
    if (packageVersion) headers["X-SR-PACKAGE-VERSION"] = packageVersion;
    if (packagePartOf) headers["X-SR-PACKAGE-PART-OF"] = packagePartOf;
    const response = await this.instance.post<ImportSystemResult[]>(
      `/api/v1/${getAppName()}/systems-catalog/import/system`,
      formData,
      {
        headers: {
          ...headers,
          "Content-Type": "multipart/form-data",
        },
      },
    );
    return response.data;
  };

  importSpecification = async (
    specificationGroupId: string,
    files: File[],
  ): Promise<ImportSpecificationResult> => {
    const formData = new FormData();
    files.forEach((file) => formData.append("files", file));
    const params: Record<string, string> = {
      specificationGroupId: specificationGroupId,
    };
    const response = await this.instance.post<ImportSpecificationResult>(
      `/api/v1/${getAppName()}/systems-catalog/import`,
      formData,
      {
        params,
        headers: { "Content-Type": "multipart/form-data" },
      },
    );
    return response.data;
  };

  importSpecificationGroup = async (
    systemId: string,
    name: string,
    files: File[],
    protocol?: string,
  ): Promise<ImportSpecificationResult> => {
    const formData: FormData = new FormData();
    files.forEach((file) => formData.append("files", file, file.name));
    const params: Record<string, string> = {
      systemId: systemId,
      name: name,
    };
    if (protocol) params.protocol = protocol;
    const response = await this.instance.post<ImportSpecificationResult>(
      `/api/v1/${getAppName()}/systems-catalog/specificationGroups/import`,
      formData,
      {
        params,
        headers: { "Content-Type": "multipart/form-data" },
      },
    );
    return response.data;
  };

  getImportSpecificationResult = async (
    importId: string,
  ): Promise<ImportSpecificationResult> => {
    const response = await this.instance.get<ImportSpecificationResult>(
      `/api/v1/${getAppName()}/systems-catalog/import/${importId}`,
    );
    return response.data;
  };

  getDetailedDesignTemplates = async (
    includeContent: boolean,
  ): Promise<DetailedDesignTemplate[]> => {
    const response = await this.instance.get<DetailedDesignTemplate[]>(
      `/api/v1/${getAppName()}/catalog/detailed-design/templates`,
      {
        params: {
          includeContent,
        },
      },
    );
    return response.data;
  };

  getDetailedDesignTemplate = async (
    templateId: string,
  ): Promise<DetailedDesignTemplate> => {
    const response = await this.instance.get<DetailedDesignTemplate>(
      `/api/v1/${getAppName()}/catalog/detailed-design/templates/${templateId}`,
    );
    return response.data;
  };

  createOrUpdateDetailedDesignTemplate = async (
    name: string,
    content: string,
  ): Promise<DetailedDesignTemplate> => {
    const response = await this.instance.put<DetailedDesignTemplate>(
      `/api/v1/${getAppName()}/catalog/detailed-design/templates`,
      { name, content },
    );
    return response.data;
  };

  deleteDetailedDesignTemplates = async (ids: string[]): Promise<void> => {
    await this.instance.delete<void>(
      `/api/v1/${getAppName()}/catalog/detailed-design/templates`,
      {
        params: {
          ids,
        },
      },
    );
  };

  getChainDetailedDesign = async (
    chainId: string,
    templateId: string,
  ): Promise<ChainDetailedDesign> => {
    const response = await this.instance.get<ChainDetailedDesign>(
      `/api/v1/${getAppName()}/catalog/detailed-design/chains/${chainId}`,
      {
        params: {
          templateId,
        },
      },
    );
    return response.data;
  };

  getChainSequenceDiagram = async (
    chainId: string,
    diagramModes: DiagramMode[],
  ): Promise<ElementsSequenceDiagrams> => {
    const response = await this.instance.post<ElementsSequenceDiagrams>(
      `/api/v1/${getAppName()}/catalog/design-generator/chains/${chainId}`,
      {
        diagramModes,
      },
    );
    return response.data;
  };

  getSnapshotSequenceDiagram = async (
    chainId: string,
    snapshotId: string,
    diagramModes: DiagramMode[],
  ): Promise<ElementsSequenceDiagrams> => {
    const response = await this.instance.post<ElementsSequenceDiagrams>(
      `/api/v1/${getAppName()}/catalog/design-generator/chains/${chainId}/snapshots/${snapshotId}`,
      {
        diagramModes,
      },
    );
    return response.data;
  };
}
