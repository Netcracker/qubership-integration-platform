import axios, { AxiosHeaders } from "axios";
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
  FolderResponse,
  FolderUpdateRequest,
} from "../apiTypes.ts";
import { Api } from "../api.ts";

export class RestApi implements Api {
  instance = axios.create({
    baseURL: import.meta.env.VITE_GATEWAY,
    timeout: 1000,
    headers: { "content-type": "application/json" },
  });

  getChains = async (): Promise<Chain[]> => {
    try {
      const response = await this.instance.get<Chain[]>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getChain = async (id: string): Promise<Chain> => {
    try {
      const response = await this.instance.get<Chain>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${id}`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  updateChain = async (id: string, chain: Partial<Chain>): Promise<Chain> => {
    try {
      const response = await this.instance.put<Chain>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${id}`,
        chain,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createChain = async (chain: ChainCreationRequest): Promise<Chain> => {
    try {
      const response = await this.instance.post<Chain>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains`,
        chain,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteChain = async (chainId: string): Promise<void> => {
    await this.instance.delete<Chain>(
      `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}`,
    );
  };

  getLibrary = async (): Promise<LibraryData> => {
    try {
      const response = await this.instance.get<LibraryData>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/library`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getElements = async (chainId: string): Promise<Element[]> => {
    try {
      const response = await this.instance.get<Element[]>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/elements`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createElement = async (
    elementRequest: ElementRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    try {
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/elements`,
        elementRequest,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  updateElement = async (
    chainId: string,
    elementId: string,
  ): Promise<ActionDifference> => {
    try {
      const response = await this.instance.put(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/elements/${elementId}`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteElement = async (
    elementId: string,
    chainId: string,
  ): Promise<ActionDifference> => {
    try {
      const response = await this.instance.delete(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/elements`,
        {
          params: { elementsIds: elementId }, //TODO send array
        },
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getConnections = async (chainId: string): Promise<Connection[]> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/dependencies`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createConnection = async (
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    try {
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/dependencies`,
        connectionRequest,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteConnection = async (
    connectionId: string,
    chainId: string,
  ): Promise<ActionDifference> => {
    try {
      const response = await this.instance.delete(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/dependencies`,
        {
          params: { dependenciesIds: connectionId }, //TODO send array
        },
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createSnapshot = async (chainId: string): Promise<Snapshot> => {
    try {
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/snapshots`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getSnapshots = async (chainId: string): Promise<Snapshot[]> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/snapshots`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getSnapshot = async (snapshotId: string): Promise<Snapshot> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/chainId/snapshots/${snapshotId}`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteSnapshot = async (snapshotId: string): Promise<void> => {
    try {
      await this.instance.delete(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/chainId/snapshots/${snapshotId}`,
      );
    } catch (err) {
      throw err;
    }
  };

  deleteSnapshots = async (snapshotIds: string[]): Promise<void> => {
    try {
      await this.instance.post(
        `/api/v2/${import.meta.env.VITE_API_APP}/catalog/snapshots/bulk-delete`,
        snapshotIds,
      );
    } catch (err) {
      throw err;
    }
  };

  revertToSnapshot = async (
    chainId: string,
    snapshotId: string,
  ): Promise<Snapshot> => {
    try {
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/snapshots/${snapshotId}/revert`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  updateSnapshot = async (
    snapshotId: string,
    name: string,
    labels: EntityLabel[],
  ): Promise<Snapshot> => {
    try {
      const response = await this.instance.put(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/chainId/snapshots/${snapshotId}`,
        {
          name,
          labels,
        },
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getLibraryElementByType = async (type: string): Promise<Element> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/library/${type}`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getDeployments = async (chainId: string): Promise<Deployment[]> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/deployments`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createDeployment = async (
    chainId: string,
    request: CreateDeploymentRequest,
  ): Promise<Deployment> => {
    try {
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/deployments`,
        request,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteDeployment = async (deploymentId: string): Promise<void> => {
    try {
      // @ts-ignore
      const response = await this.instance.delete(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/chainId/deployments/${deploymentId}`,
      );
    } catch (err) {
      throw err;
    }
  };

  getDomains = async (): Promise<EngineDomain[]> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/domains`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getLoggingSettings = async (
    chainId: string,
  ): Promise<ChainLoggingSettings> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/properties/logging`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  setLoggingProperties = async (
    chainId: string,
    properties: ChainLoggingProperties,
  ): Promise<void> => {
    try {
      // @ts-ignore
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/properties/logging`,
        properties,
      );
    } catch (err) {
      throw err;
    }
  };

  deleteLoggingSettings = async (chainId: string): Promise<void> => {
    try {
      // @ts-ignore
      const response = await this.instance.delete(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/properties/logging`,
      );
    } catch (err) {
      throw err;
    }
  };

  getMaskedFields = async (chainId: string): Promise<MaskedField[]> => {
    try {
      const response = await this.instance.get<MaskedFields>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking`,
      );
      return response.data.fields;
    } catch (err) {
      throw err;
    }
  };

  createMaskedField = async (
    chainId: string,
    maskedField: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    try {
      // @ts-ignore
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking`,
        maskedField,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteMaskedFields = async (
    chainId: string,
    maskedFieldIds: string[],
  ): Promise<void> => {
    try {
      // @ts-ignore
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking/field`,
        maskedFieldIds,
      );
    } catch (err) {
      throw err;
    }
  };

  deleteMaskedField = async (
    chainId: string,
    maskedFieldId: string,
  ): Promise<void> => {
    try {
      // @ts-ignore
      const response = await this.instance.delete(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking/field/${maskedFieldId}`,
      );
    } catch (err) {
      throw err;
    }
  };

  updateMaskedField = async (
    chainId: string,
    maskedFieldId: string,
    changes: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    try {
      // @ts-ignore
      const response = await this.instance.put(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/chains/${chainId}/masking/field/${maskedFieldId}`,
        changes,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getSessions = async (
    chainId: string | undefined,
    filters: SessionFilterAndSearchRequest,
    paginationOptions: PaginationOptions,
  ): Promise<SessionSearchResponse> => {
    try {
      const prefix = `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions`;
      const url = chainId ? `${prefix}/chains/${chainId}` : prefix;
      const params: Record<string, string> = {};
      if (paginationOptions.offset) {
        params["offset"] = paginationOptions.offset.toString(10);
      }
      if (paginationOptions.count) {
        params["count"] = paginationOptions.count.toString(10);
      }
      // @ts-ignore
      const response = await this.instance.post(url, filters, { params });
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteSessions = async (sessionIds: string[]): Promise<void> => {
    try {
      await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions/bulk-delete`,
        sessionIds,
      );
    } catch (err) {
      throw err;
    }
  };

  deleteSessionsByChainId = async (
    chainId: string | undefined,
  ): Promise<void> => {
    try {
      const prefix = `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions`;
      const url = chainId ? `${prefix}/chains/${chainId}` : prefix;
      // @ts-ignore
      const response = await this.instance.delete(url);
    } catch (err) {
      throw err;
    }
  };

  exportSessions = async (sessionIds: string[]): Promise<File> => {
    try {
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions/export`,
        sessionIds,
        { responseType: "blob" },
      );
      const contentDisposition = response.headers?.["content-disposition"];
      const fileName = contentDisposition?.replace("attachment; filename=", "");
      return new File([response.data], fileName, {
        type: response.data.type.toString(),
      });
    } catch (err) {
      throw err;
    }
  };

  importSessions = async (files: File[]): Promise<Session[]> => {
    const formData: FormData = new FormData();
    files.forEach((file) => formData.append("files", file, file.name));
    const headers = new AxiosHeaders();
    headers.set("Content-Type", "multipart/form-data");
    headers.set("accept", "*/*");
    try {
      const response = await this.instance.post(
        `/api/v1/cip/sessions-management/sessions/import`,
        formData,
        { headers },
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  retryFromLastCheckpoint = async (
    chainId: string,
    sessionId: string,
  ): Promise<void> => {
    try {
      // @ts-ignore
      const response = await this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/engine/chains/${chainId}/sessions/${sessionId}/retry`,
        null,
      );
    } catch (err) {
      throw err;
    }
  };

  getSession = async (sessionId: string): Promise<Session> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/sessions-management/sessions/${sessionId}`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getCheckpointSessions = async (
    sessionIds: string[],
  ): Promise<CheckpointSession[]> => {
    try {
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
    } catch (err) {
      throw err;
    }
  };

  retrySessionFromLastCheckpoint = async (
    chainId: string,
    sessionId: string,
  ): Promise<void> => {
    try {
      return this.instance.post(
        `/api/v1/${import.meta.env.VITE_API_APP}/engine/chains/${chainId}/sessions/${sessionId}/retry`,
        null,
      );
    } catch (err) {
      throw err;
    }
  };

  getRootFolder = async (): Promise<FolderItem[]> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/folders`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getFolder = async (folderId: string): Promise<FolderResponse> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/folders/${folderId}`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createFolder = async (
    request: FolderUpdateRequest,
  ): Promise<FolderResponse> => {
    try {
      const response = await this.instance.post<FolderResponse>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/folders`,
        request,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  updateFolder = async (
    folderId: string,
    changes: FolderUpdateRequest,
  ): Promise<FolderResponse> => {
    try {
      const response = await this.instance.put<FolderResponse>(
        `/api/v1/${import.meta.env.VITE_API_APP}/catalog/folders/${folderId}`,
        changes,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };
}
