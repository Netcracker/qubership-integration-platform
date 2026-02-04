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
  SpecApiFile,
  LiveExchange,
  ContextSystem,
  IntegrationSystemType,
  DiagnosticValidation,
  BulkDeploymentRequest,
  BulkDeploymentResult,
  ImportVariablesResult,
  VariableImportPreview,
} from "../apiTypes.ts";
import { Api } from "../api.ts";
import { getFileFromResponse } from "../../misc/download-utils.ts";
import qs from "qs";
import { getAppName, getConfig } from "../../appConfig.ts";
import { EntityFilterModel } from "../../components/table/filter/filter.ts";
import { registerRestAxiosInstance } from "./requestHeadersInterceptor.ts";
import type {
  ApiError,
  ApiResponse,
  SecretResponse,
  SecretWithVariables,
  Variable,
} from "../apiTypes.ts";

export class RestApi implements Api {
  instance: AxiosInstance;

  constructor() {
    const config = getConfig();
    const gateway = config.apiGateway;
    this.instance = rateLimit(
      axios.create({
        baseURL: gateway,
        timeout: 2000,
        headers: { "content-type": "application/json" },
      }),
      {
        maxRequests: 50,
        perMilliseconds: 1000,
      },
    );

    registerRestAxiosInstance(this.instance);

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
          } else if (typeof Blob !== "undefined" && data instanceof Blob) {
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

  private v1 = (): string => `/api/v1/${getAppName()}`;
  private v2 = (): string => `/api/v2/${getAppName()}`;
  private v3 = (): string => `/api/${getAppName()}/v3`;

  private toApiError = (
    serviceName: string,
    message: string,
    errorDate: string,
    stacktrace?: string,
  ): ApiError => {
    return {
      responseBody: {
        serviceName,
        errorMessage: message,
        errorDate,
        stacktrace,
      },
    };
  };

  private wrapApiResponse = async <T>(
    serviceName: string,
    fallbackMessage: string,
    fn: () => Promise<T>,
  ): Promise<ApiResponse<T>> => {
    try {
      const data = await fn();
      return { success: true, data };
    } catch (error) {
      // RestApi converts axios errors into RestApiError with responseBody when possible.
      if (
        error &&
        typeof error === "object" &&
        "name" in error &&
        (error as { name?: unknown }).name === "RestApiError"
      ) {
        const e = error as RestApiError;
        const body = e.responseBody;
        if (body?.serviceName && body?.errorMessage && body?.errorDate) {
          return {
            success: false,
            error: this.toApiError(
              body.serviceName,
              body.errorMessage,
              body.errorDate,
              body.stackTrace,
            ),
          };
        }
        if (e.message) {
          return {
            success: false,
            error: this.toApiError(
              serviceName,
              e.message,
              new Date().toISOString(),
            ),
          };
        }
      }

      return {
        success: false,
        error: this.toApiError(
          serviceName,
          fallbackMessage,
          new Date().toISOString(),
        ),
      };
    }
  };

  private wrapBoolean = async (
    fallbackMessage: string,
    fn: () => Promise<void>,
  ): Promise<boolean> => {
    try {
      await fn();
      return true;
    } catch (error) {
      // keep parity with old BaseApi.wrapBoolean(): no throw
      console.error(fallbackMessage, error);
      return false;
    }
  };

  getChains = async (): Promise<Chain[]> => {
    const response = await this.instance.get<Chain[]>(
      `${this.v1()}/catalog/chains`,
    );
    return response.data;
  };

  // Admin Tools: Variables Management
  getCommonVariables = async (): Promise<ApiResponse<Variable[]>> => {
    const serviceName = "Common Variables API";
    const prefix = `${this.v1()}/variables-management`;

    return this.wrapApiResponse(
      serviceName,
      "Failed to fetch common variables",
      async () => {
        const response = await this.instance.get<unknown>(
          `${prefix}/common-variables`,
        );
        const rawData = response.data;
        if (rawData && typeof rawData === "object" && !Array.isArray(rawData)) {
          return Object.entries(rawData as Record<string, unknown>).map(
            ([key, value]) => ({
              key,
              value: String(value),
            }),
          );
        }
        throw new Error("Unexpected response format");
      },
    );
  };

  createCommonVariable = async (
    variable: Variable,
  ): Promise<ApiResponse<string[]>> => {
    return this.createCommonVariables({ [variable.key]: variable.value });
  };

  private createCommonVariables = async (
    variables: Record<string, string>,
  ): Promise<ApiResponse<string[]>> => {
    const serviceName = "Common Variables API";
    const prefix = `${this.v1()}/variables-management`;

    return this.wrapApiResponse(
      serviceName,
      "Failed to create variables",
      async () => {
        const response = await this.instance.post<string[]>(
          `${prefix}/common-variables`,
          variables,
        );
        return response.data;
      },
    );
  };

  updateCommonVariable = async (
    variable: Variable,
  ): Promise<ApiResponse<Variable>> => {
    const serviceName = "Common Variables API";
    const prefix = `${this.v1()}/variables-management`;

    return this.wrapApiResponse(
      serviceName,
      "Failed to update variable",
      async () => {
        const response = await this.instance.patch<Variable>(
          `${prefix}/common-variables/${variable.key}`,
          variable.value,
          { headers: { "Content-Type": "text/plain" } },
        );
        return response.data;
      },
    );
  };

  deleteCommonVariables = async (keys: string[]): Promise<boolean> => {
    const prefix = `${this.v1()}/variables-management`;
    return this.wrapBoolean("Failed to delete variables", async () => {
      const params = new URLSearchParams();
      keys.forEach((key) => params.append("variablesNames", key));
      await this.instance.delete(`${prefix}/common-variables?${params}`);
    });
  };

  exportVariables = async (keys: string[], asArchive = true): Promise<File> => {
    const prefix = `${this.v1()}/variables-management`;
    const params = new URLSearchParams();
    keys.forEach((key) => params.append("variablesNames", key));
    params.append("asArchive", String(asArchive));

    const response = await this.instance.get<Blob>(
      `${prefix}/common-variables/export?${params}`,
      { responseType: "blob" },
    );
    return getFileFromResponse(response);
  };

  importVariablesPreview = async (
    formData: FormData,
  ): Promise<ApiResponse<VariableImportPreview[]>> => {
    const serviceName = "Common Variables API";
    if (!formData) {
      return {
        success: false,
        error: this.toApiError(
          serviceName,
          "No file selected or form data is empty.",
          new Date().toISOString(),
        ),
      };
    }
    const path = `${this.v1()}/variables-management/common-variables/preview`;
    return this.wrapApiResponse(
      serviceName,
      "Failed to get import variables preview",
      async () => {
        const response = await this.instance.post<VariableImportPreview[]>(
          path,
          formData,
          { headers: { "Content-Type": "multipart/form-data" } },
        );
        return response.data;
      },
    );
  };

  importVariables = async (
    formData: FormData,
  ): Promise<ApiResponse<ImportVariablesResult>> => {
    const serviceName = "Common Variables API";
    if (!formData) {
      return {
        success: false,
        error: this.toApiError(
          serviceName,
          "No file selected or form data is empty.",
          new Date().toISOString(),
        ),
      };
    }
    const path = `${this.v2()}/variables-management/common-variables/import`;
    return this.wrapApiResponse(serviceName, "Failed to import", async () => {
      const response = await this.instance.post<ImportVariablesResult>(
        path,
        formData,
        { headers: { "Content-Type": "multipart/form-data" } },
      );
      return response.data;
    });
  };

  getSecuredVariables = async (): Promise<
    ApiResponse<SecretWithVariables[]>
  > => {
    const serviceName = "Secured Variables API";
    const prefix = `${this.v2()}/variables-management`;

    return this.wrapApiResponse(
      serviceName,
      "Failed to fetch secured variables",
      async () => {
        const response = await this.instance.get<SecretResponse[]>(
          `${prefix}/secured-variables`,
        );
        return response.data.map(
          ({ secretName, variablesNames, defaultSecret }) => ({
            secretName,
            variables: variablesNames.map((key: string) => ({
              key,
              value: "******",
            })),
            isDefaultSecret: defaultSecret,
          }),
        );
      },
    );
  };

  getSecuredVariablesForSecret = async (
    secretName: string,
  ): Promise<ApiResponse<Variable[]>> => {
    const serviceName = "Secured Variables API";
    const prefix = `${this.v2()}/variables-management`;

    return this.wrapApiResponse(
      serviceName,
      "Failed to fetch variables for secret",
      async () => {
        const response = await this.instance.get<string[]>(
          `${prefix}/secured-variables/${secretName}`,
        );
        return response.data.map((key: string) => ({ key, value: "******" }));
      },
    );
  };

  createSecuredVariables = async (
    secretName: string,
    variables: Variable[],
  ): Promise<ApiResponse<Variable[]>> => {
    const serviceName = "Secured Variables API";
    const keyRegex = /^[a-zA-Z0-9_-]+$/;
    for (const v of variables) {
      if (!keyRegex.test(v.key)) {
        return {
          success: false,
          error: this.toApiError(
            serviceName,
            `Invalid key: ${v.key}`,
            new Date().toISOString(),
          ),
        };
      }
    }
    const prefix = `${this.v2()}/variables-management`;
    return this.wrapApiResponse(
      serviceName,
      "Failed to create secured variables",
      async () => {
        await this.instance.post(`${prefix}/secured-variables`, {
          secretName,
          variables: Object.fromEntries(variables.map((v) => [v.key, v.value])),
        });
        return variables;
      },
    );
  };

  updateSecuredVariables = async (
    secretName: string,
    variables: Variable[],
  ): Promise<ApiResponse<Variable[]>> => {
    const serviceName = "Secured Variables API";
    const prefix = `${this.v2()}/variables-management`;
    return this.wrapApiResponse(
      serviceName,
      "Failed to update secured variables",
      async () => {
        await this.instance.patch(`${prefix}/secured-variables`, {
          secretName,
          variables: Object.fromEntries(variables.map((v) => [v.key, v.value])),
        });
        return variables;
      },
    );
  };

  deleteSecuredVariables = async (
    secretName: string,
    keys: string[],
  ): Promise<ApiResponse<boolean>> => {
    const serviceName = "Secured Variables API";
    const prefix = `${this.v2()}/variables-management`;
    return this.wrapApiResponse(
      serviceName,
      "Failed to delete secured variables",
      async () => {
        const params = new URLSearchParams();
        keys.forEach((key) => params.append("variablesNames", key));
        await this.instance.delete(
          `${prefix}/secured-variables/${secretName}?${params}`,
        );
        return true;
      },
    );
  };

  createSecret = async (secretName: string): Promise<ApiResponse<boolean>> => {
    const serviceName = "Secured Variables API";
    const prefix = `${this.v2()}/variables-management`;
    return this.wrapApiResponse(
      serviceName,
      "Failed to create secret",
      async () => {
        await this.instance.post(`${prefix}/secret/${secretName}`);
        return true;
      },
    );
  };

  downloadHelmChart = async (secretName: string): Promise<File> => {
    const prefix = `${this.v2()}/variables-management`;
    const response = await this.instance.get<Blob>(
      `${prefix}/secret/template/${secretName}`,
      { responseType: "blob" },
    );
    return getFileFromResponse(response);
  };

  getChain = async (id: string): Promise<Chain> => {
    const response = await this.instance.get<Chain>(
      `${this.v1()}/catalog/chains/${id}`,
    );
    return response.data;
  };

  findChainByElementId = async (elementId: string): Promise<Chain> => {
    const response = await this.instance.get<Chain>(
      `${this.v1()}/catalog/chains/find-by-element/${elementId}`,
    );
    return response.data;
  };

  updateChain = async (id: string, chain: Partial<Chain>): Promise<Chain> => {
    const response = await this.instance.put<Chain>(
      `${this.v1()}/catalog/chains/${id}`,
      chain,
    );
    return response.data;
  };

  createChain = async (chain: ChainCreationRequest): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `${this.v1()}/catalog/chains`,
      chain,
    );
    return response.data;
  };

  deleteChain = async (chainId: string): Promise<void> => {
    await this.instance.delete<Chain>(`${this.v1()}/catalog/chains/${chainId}`);
  };

  deleteChains = async (chainIds: string[]): Promise<void> => {
    await this.instance.post(
      `${this.v1()}/catalog/chains/bulk-delete`,
      chainIds,
    );
  };

  duplicateChain = async (chainId: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `${this.v1()}/catalog/chains/${chainId}/duplicate`,
    );
    return response.data;
  };

  copyChain = async (chainId: string, folderId?: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `${this.v1()}/catalog/chains/${chainId}/copy`,
      null,
      {
        params: { targetFolderId: folderId },
      },
    );
    return response.data;
  };

  moveChain = async (chainId: string, folder?: string): Promise<Chain> => {
    const response = await this.instance.post<Chain>(
      `${this.v1()}/catalog/chains/${chainId}/move`,
      null,
      {
        params: { targetFolderId: folder },
      },
    );
    return response.data;
  };

  exportAllChains = async (): Promise<File> => {
    const response = await this.instance.get<Blob>(
      `${this.v1()}/catalog/export`,
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
      `${this.v1()}/catalog/export/chains`,
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
      `${this.v1()}/catalog/library`,
    );
    return response.data;
  };

  getElementTypes = async (): Promise<ElementFilter[]> => {
    const response = await this.instance.get<ElementFilter[]>(
      `${this.v1()}/catalog/library/elements/types`,
    );
    return response.data;
  };

  getElements = async (chainId: string): Promise<Element[]> => {
    const response = await this.instance.get<Element[]>(
      `${this.v1()}/catalog/chains/${chainId}/elements`,
    );
    return response.data;
  };

  getElementsByType = async (
    chainId: string,
    elementType: string,
  ): Promise<ElementWithChainName[]> => {
    const response = await this.instance.get<ElementWithChainName[]>(
      `${this.v1()}/catalog/chains/${chainId}/elements/type/${elementType}`,
    );
    return response.data;
  };

  createElement = async (
    elementRequest: CreateElementRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.post<ActionDifference>(
      `${this.v1()}/catalog/chains/${chainId}/elements`,
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
      `${this.v1()}/catalog/chains/${chainId}/elements/${elementId}`,
      elementRequest,
    );
    return response.data;
  };

  transferElement = async (
    transferElementRequest: TransferElementRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.post<ActionDifference>(
      `${this.v1()}/catalog/chains/${chainId}/elements/transfer`,
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
      `${this.v1()}/catalog/chains/${chainId}/elements`,
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
      `${this.v1()}/catalog/chains/${chainId}/elements/properties-modification`,
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
      `${this.v1()}/catalog/chains/${chainId}/dependencies`,
    );
    return response.data;
  };

  createConnection = async (
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    const response = await this.instance.post<ActionDifference>(
      `${this.v1()}/catalog/chains/${chainId}/dependencies`,
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
      `${this.v1()}/catalog/chains/${chainId}/dependencies`,
      {
        params: { dependenciesIds: connectionIdsParam },
      },
    );
    return response.data;
  };

  createSnapshot = async (chainId: string): Promise<Snapshot> => {
    const response = await this.instance.post<Snapshot>(
      `${this.v1()}/catalog/chains/${chainId}/snapshots`,
    );
    return response.data;
  };

  getSnapshots = async (chainId: string): Promise<Snapshot[]> => {
    const response = await this.instance.get<Snapshot[]>(
      `${this.v1()}/catalog/chains/${chainId}/snapshots`,
    );
    return response.data;
  };

  getSnapshot = async (snapshotId: string): Promise<Snapshot> => {
    const response = await this.instance.get<Snapshot>(
      `${this.v1()}/catalog/chains/chainId/snapshots/${snapshotId}`,
    );
    return response.data;
  };

  deleteSnapshot = async (snapshotId: string): Promise<void> => {
    await this.instance.delete(
      `${this.v1()}/catalog/chains/chainId/snapshots/${snapshotId}`,
    );
  };

  deleteSnapshots = async (snapshotIds: string[]): Promise<void> => {
    await this.instance.post(
      `${this.v2()}/catalog/snapshots/bulk-delete`,
      snapshotIds,
    );
  };

  revertToSnapshot = async (
    chainId: string,
    snapshotId: string,
  ): Promise<Snapshot> => {
    const response = await this.instance.post<Snapshot>(
      `${this.v1()}/catalog/chains/${chainId}/snapshots/${snapshotId}/revert`,
    );
    return response.data;
  };

  updateSnapshot = async (
    snapshotId: string,
    name: string,
    labels: EntityLabel[],
  ): Promise<Snapshot> => {
    const response = await this.instance.put<Snapshot>(
      `${this.v1()}/catalog/chains/chainId/snapshots/${snapshotId}`,
      {
        name,
        labels,
      },
    );
    return response.data;
  };

  getLibraryElementByType = async (type: string): Promise<LibraryElement> => {
    const response = await this.instance.get<LibraryElement>(
      `${this.v1()}/catalog/library/${type}`,
    );
    return response.data;
  };

  getDeployments = async (chainId: string): Promise<Deployment[]> => {
    const response = await this.instance.get<Deployment[]>(
      `${this.v1()}/catalog/chains/${chainId}/deployments`,
    );
    return response.data;
  };

  createDeployment = async (
    chainId: string,
    request: CreateDeploymentRequest,
  ): Promise<Deployment> => {
    const response = await this.instance.post<Deployment>(
      `${this.v1()}/catalog/chains/${chainId}/deployments`,
      request,
    );
    return response.data;
  };

  deleteDeployment = async (deploymentId: string): Promise<void> => {
    await this.instance.delete(
      `${this.v1()}/catalog/chains/chainId/deployments/${deploymentId}`,
    );
  };

  getDomains = async (): Promise<EngineDomain[]> => {
    const response = await this.instance.get<EngineDomain[]>(
      `${this.v1()}/catalog/domains`,
    );
    return response.data;
  };

  getLoggingSettings = async (
    chainId: string,
  ): Promise<ChainLoggingSettings> => {
    const response = await this.instance.get<ChainLoggingSettings>(
      `${this.v1()}/catalog/chains/${chainId}/properties/logging`,
    );
    return response.data;
  };

  setLoggingProperties = async (
    chainId: string,
    properties: ChainLoggingProperties,
  ): Promise<void> => {
    await this.instance.post(
      `${this.v1()}/catalog/chains/${chainId}/properties/logging`,
      properties,
    );
  };

  deleteLoggingSettings = async (chainId: string): Promise<void> => {
    await this.instance.delete(
      `${this.v1()}/catalog/chains/${chainId}/properties/logging`,
    );
  };

  getMaskedFields = async (chainId: string): Promise<MaskedField[]> => {
    const response = await this.instance.get<MaskedFields>(
      `${this.v1()}/catalog/chains/${chainId}/masking`,
    );
    return response.data.fields;
  };

  createMaskedField = async (
    chainId: string,
    maskedField: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    const response = await this.instance.post<MaskedField>(
      `${this.v1()}/catalog/chains/${chainId}/masking`,
      maskedField,
    );
    return response.data;
  };

  deleteMaskedFields = async (
    chainId: string,
    maskedFieldIds: string[],
  ): Promise<void> => {
    await this.instance.post(
      `${this.v1()}/catalog/chains/${chainId}/masking/field`,
      maskedFieldIds,
    );
  };

  deleteMaskedField = async (
    chainId: string,
    maskedFieldId: string,
  ): Promise<void> => {
    await this.instance.delete(
      `${this.v1()}/catalog/chains/${chainId}/masking/field/${maskedFieldId}`,
    );
  };

  updateMaskedField = async (
    chainId: string,
    maskedFieldId: string,
    changes: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    const response = await this.instance.put<MaskedField>(
      `${this.v1()}/catalog/chains/${chainId}/masking/field/${maskedFieldId}`,
      changes,
    );
    return response.data;
  };

  getSessions = async (
    chainId: string | undefined,
    filters: SessionFilterAndSearchRequest,
    paginationOptions: PaginationOptions,
  ): Promise<SessionSearchResponse> => {
    const prefix = `${this.v1()}/sessions-management/sessions`;
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
      `${this.v1()}/sessions-management/sessions/bulk-delete`,
      sessionIds,
    );
  };

  deleteSessionsByChainId = async (
    chainId: string | undefined,
  ): Promise<void> => {
    const prefix = `${this.v1()}/sessions-management/sessions`;
    const url = chainId ? `${prefix}/chains/${chainId}` : prefix;
    await this.instance.delete(url);
  };

  exportSessions = async (sessionIds: string[]): Promise<File> => {
    const response = await this.instance.post<Blob>(
      `${this.v1()}/sessions-management/sessions/export`,
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
      `${this.v1()}/sessions-management/sessions/import`,
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
      `${this.v1()}/engine/chains/${chainId}/sessions/${sessionId}/retry`,
      null,
    );
  };

  getSession = async (sessionId: string): Promise<Session> => {
    const response = await this.instance.get<Session>(
      `${this.v1()}/sessions-management/sessions/${sessionId}`,
    );
    return response.data;
  };

  getCheckpointSessions = async (
    sessionIds: string[],
  ): Promise<CheckpointSession[]> => {
    const response = await this.instance.get<CheckpointSession[]>(
      `${this.v1()}/engine/sessions`,
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
      `${this.v1()}/engine/chains/${chainId}/sessions/${sessionId}/retry`,
      null,
    );
  };

  getFolder = async (folderId: string): Promise<FolderItem> => {
    const response = await this.instance.get<FolderItem>(
      `${this.v2()}/catalog/folders/${folderId}`,
    );
    return response.data;
  };

  getRootFolders = async (
    filter: string,
    openedFolderId: string,
  ): Promise<FolderItem[]> => {
    const response = await this.instance.get<FolderItem[]>(
      `${this.v1()}/catalog/folders/`,
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
      `${this.v2()}/catalog/folders/${folderId}/path`,
    );
    return response.data;
  };

  getPathToFolderByName = async (folderName: string): Promise<FolderItem[]> => {
    const response = await this.instance.get<FolderItem[]>(
      `${this.v2()}/catalog/folders/path?name=${folderName}`,
    );
    return response.data;
  };

  createFolder = async (request: CreateFolderRequest): Promise<FolderItem> => {
    const response = await this.instance.post<FolderItem>(
      `${this.v2()}/catalog/folders`,
      request,
    );
    return response.data;
  };

  updateFolder = async (
    folderId: string,
    changes: UpdateFolderRequest,
  ): Promise<FolderItem> => {
    const response = await this.instance.put<FolderItem>(
      `${this.v2()}/catalog/folders/${folderId}`,
      changes,
    );
    return response.data;
  };

  deleteFolder = async (folderId: string): Promise<void> => {
    await this.instance.delete(`${this.v2()}/catalog/folders/${folderId}`);
  };

  deleteFolders = async (folderIds: string[]): Promise<void> => {
    await this.instance.post(
      `${this.v2()}/catalog/folders/bulk-delete`,
      folderIds,
    );
  };

  listFolder = async (
    request: ListFolderRequest,
  ): Promise<(FolderItem | ChainItem)[]> => {
    const response = await this.instance.post<(FolderItem | ChainItem)[]>(
      `${this.v2()}/catalog/folders/list`,
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
      `${this.v2()}/catalog/folders/move`,
      request,
    );
    return response.data;
  };

  getNestedChains = async (folderId: string): Promise<Chain[]> => {
    const response = await this.instance.get<Chain[]>(
      `${this.v1()}/catalog/folders/${folderId}/chains`,
    );
    return response.data;
  };

  getServicesUsedByChains = async (
    chainIds: string[],
  ): Promise<UsedService[]> => {
    const response = await this.instance.get<UsedService[]>(
      `${this.v1()}/catalog/chains/used-systems`,
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
      `${this.v1()}/catalog/chains/systems/${systemId}`,
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
      `${this.v1()}/systems-catalog/export/system`,
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
      `${this.v1()}/systems-catalog/export/specifications`,
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
      `${this.v1()}/catalog/export/api-spec`,
      {
        params: { ...params, externalRoutes, specificationType, format },
        headers: {
          accept: "*/*",
        },
        responseType: "blob",
      },
    );
    const contentType =
      (
        response.headers?.["content-type"] as string | undefined
      )?.toLowerCase() ?? "";
    if (contentType.includes("application/json")) {
      try {
        const text = await response.data.text();
        const parsed = JSON.parse(text) as unknown;
        if (isErrorResponse(parsed)) {
          throw new RestApiError(parsed.errorMessage, response.status, parsed);
        }
      } catch (e) {
        if (e instanceof RestApiError) throw e;
        throw new RestApiError(
          "Failed to generate API specification",
          response.status,
        );
      }
    }
    return getFileFromResponse(response);
  };

  getServices = async (
    modelType: string,
    withSpec: boolean,
  ): Promise<IntegrationSystem[]> => {
    const response = await this.instance.get<IntegrationSystem[]>(
      `${this.v1()}/systems-catalog/systems`,
      {
        params: { modelType, withSpec },
      },
    );
    return response.data;
  };

  createService = async (system: SystemRequest): Promise<IntegrationSystem> => {
    const response = await this.instance.post<IntegrationSystem>(
      `${this.v1()}/systems-catalog/systems`,
      system,
    );
    return response.data;
  };

  createEnvironment = async (
    systemId: string,
    envRequest: EnvironmentRequest,
  ): Promise<Environment> => {
    const response = await this.instance.post<Environment>(
      `${this.v1()}/systems-catalog/systems/${systemId}/environments`,
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
      `${this.v1()}/systems-catalog/systems/${systemId}/environments/${environmentId}`,
      envRequest,
    );
    return response.data;
  };

  deleteEnvironment = async (
    systemId: string,
    environmentId: string,
  ): Promise<void> => {
    await this.instance.delete(
      `${this.v1()}/systems-catalog/systems/${systemId}/environments/${environmentId}`,
    );
  };

  deleteService = async (serviceId: string): Promise<void> => {
    await this.instance.delete(
      `${this.v1()}/systems-catalog/systems/${serviceId}`,
    );
  };

  getImportPreview = async (file: File): Promise<ImportPreview> => {
    const formData: FormData = new FormData();
    formData.append("file", file, file.name);
    const response = await this.instance.post<ImportPreview>(
      `${this.v3()}/import/preview`,
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
      `${this.v3()}/import`,
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
      `${this.v3()}/import/${importId}`,
    );
    return response.data;
  };

  getEvents = async (lastEventId: string): Promise<EventsUpdate> => {
    const response = await this.instance.get<EventsUpdate>(
      `${this.v1()}/catalog/events`,
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
      `${this.v1()}/catalog/domains/${domain}/engines/${engineHost}/deployments`,
    );
    return response.data;
  };

  getEnginesByDomain = async (domain: string): Promise<Engine[]> => {
    const response = await this.instance.get<Engine[]>(
      `${this.v1()}/catalog/domains/${domain}/engines`,
    );
    return response.data;
  };

  loadCatalogActionsLog = async (
    searchRequest: ActionLogSearchRequest,
  ): Promise<ActionLogResponse> => {
    const response = await this.instance.post<ActionLogResponse>(
      `${this.v1()}/catalog/actions-log`,
      searchRequest,
    );
    return response.data;
  };

  exportCatalogActionsLog = async (
    params: LogExportRequestParams,
  ): Promise<Blob> => {
    const response = await this.instance.get<Blob>(
      `${this.v1()}/catalog/actions-log/export`,
      {
        responseType: "blob",
        params,
      },
    );
    return response.data;
  };

  getContextServices = async (): Promise<ContextSystem[]> => {
    const response = await this.instance.get<ContextSystem[]>(
      `${this.v1()}/catalog/context-system`,
    );
    const result = response.data;
    response.data.map(
      (system) => (system.type = IntegrationSystemType.CONTEXT),
    );
    return result;
  };

  getContextService = async (id: string): Promise<ContextSystem> => {
    const response = await this.instance.get<ContextSystem>(
      `${this.v1()}/catalog/context-system/${id}`,
    );
    return response.data;
  };

  createContextService = async (
    system: Pick<ContextSystem, "name" | "description">,
  ): Promise<ContextSystem> => {
    const response = await this.instance.post<ContextSystem>(
      `${this.v1()}/catalog/context-system`,
      system,
    );
    return response.data;
  };

  updateContextService = async (
    id: string,
    data: Partial<ContextSystem>,
  ): Promise<ContextSystem> => {
    const response = await this.instance.put<ContextSystem>(
      `${this.v1()}/catalog/context-system/${id}`,
      data,
    );
    return response.data;
  };

  deleteContextService = async (serviceId: string): Promise<void> => {
    await this.instance.delete(
      `${this.v1()}/catalog/context-system/${serviceId}`,
    );
  };

  exportContextServices = async (serviceIds: string[]): Promise<File> => {
    const formData: FormData = new FormData();
    if (serviceIds?.length) {
      formData.append("systemIds", serviceIds.join(","));
    }
    const response = await this.instance.post<Blob>(
      `${this.v1()}/catalog/context-system/export`,
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

  getService = async (id: string): Promise<IntegrationSystem> => {
    const response = await this.instance.get<IntegrationSystem>(
      `${this.v1()}/systems-catalog/systems/${id}`,
    );
    return response.data;
  };

  updateService = async (
    id: string,
    data: Partial<IntegrationSystem>,
  ): Promise<IntegrationSystem> => {
    const response = await this.instance.put<IntegrationSystem>(
      `${this.v1()}/systems-catalog/systems/${id}`,
      data,
    );
    return response.data;
  };

  getEnvironments = async (systemId: string): Promise<Environment[]> => {
    const response = await this.instance.get<Environment[]>(
      `${this.v1()}/systems-catalog/systems/${systemId}/environments`,
    );
    return response.data;
  };

  getApiSpecifications = async (
    systemId: string,
  ): Promise<SpecificationGroup[]> => {
    const response = await this.instance.get<SpecificationGroup[]>(
      `${this.v1()}/systems-catalog/specificationGroups`,
      {
        params: {
          systemId: systemId,
        },
      },
    );
    return response.data;
  };

  getLatestApiSpecification = async (
    systemId: string,
  ): Promise<Specification> => {
    const response = await this.instance.get<Specification>(
      `${this.v1()}/systems-catalog/models/latest`,
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
      `${this.v1()}/systems-catalog/specificationGroups/${id}`,
      data,
    );
    return response.data;
  };

  deleteSpecificationGroup = async (id: string): Promise<void> => {
    await this.instance.delete<SpecificationGroup>(
      `${this.v1()}/systems-catalog/specificationGroups/${id}`,
    );
  };

  updateSpecificationModel = async (
    id: string,
    data: Partial<Specification>,
  ): Promise<Specification> => {
    const response = await this.instance.patch<Specification>(
      `${this.v1()}/systems-catalog/models/${id}`,
      data,
    );
    return response.data;
  };

  getSpecificationModelSource = async (id: string): Promise<string> => {
    const response = await this.instance.get<string>(
      `${this.v1()}/systems-catalog/models/${id}/source`,
    );
    return response.data;
  };

  deleteSpecificationModel = async (id: string): Promise<void> => {
    await this.instance.delete(`${this.v1()}/systems-catalog/models/${id}`);
  };

  getSpecificationModel = async (
    systemId?: string,
    specificationGroupId?: string,
  ): Promise<Specification[]> => {
    const response = await this.instance.get<Specification[]>(
      `${this.v1()}/systems-catalog/models`,
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
      `${this.v1()}/systems-catalog/models/deprecated`,
      modelId,
      {
        headers: { "Content-Type": "text/plain" },
      },
    );
    return response.data;
  };

  getOperations = async (modelId: string): Promise<SystemOperation[]> => {
    const response = await this.instance.get<SystemOperation[]>(
      `${this.v1()}/systems-catalog/operations`,
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
      `${this.v1()}/systems-catalog/operations/${encodeURIComponent(operationId)}/info`,
    );
    return response.data;
  };

  importSystems = async (
    file: File,
    systemType: IntegrationSystemType,
    systemIds?: string[],
    deployLabel?: string,
    packageName?: string,
    packageVersion?: string,
    packagePartOf?: string,
  ): Promise<ImportSystemResult[]> => {
    const formData = new FormData();
    formData.append("file", file);
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

    const url =
      systemType === IntegrationSystemType.CONTEXT
        ? `${this.v1()}/catalog/context-system/import`
        : `${this.v1()}/systems-catalog/import/system`;
    const response = await this.instance.post<ImportSystemResult[]>(
      url,
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
      `${this.v1()}/systems-catalog/import`,
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
      `${this.v1()}/systems-catalog/specificationGroups/import`,
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
      `${this.v1()}/systems-catalog/import/${importId}`,
    );
    return response.data;
  };

  getDetailedDesignTemplates = async (
    includeContent: boolean,
  ): Promise<DetailedDesignTemplate[]> => {
    const response = await this.instance.get<DetailedDesignTemplate[]>(
      `${this.v1()}/catalog/detailed-design/templates`,
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
      `${this.v1()}/catalog/detailed-design/templates/${templateId}`,
    );
    return response.data;
  };

  createOrUpdateDetailedDesignTemplate = async (
    name: string,
    content: string,
  ): Promise<DetailedDesignTemplate> => {
    const response = await this.instance.put<DetailedDesignTemplate>(
      `${this.v1()}/catalog/detailed-design/templates`,
      { name, content },
    );
    return response.data;
  };

  deleteDetailedDesignTemplates = async (ids: string[]): Promise<void> => {
    await this.instance.delete<void>(
      `${this.v1()}/catalog/detailed-design/templates`,
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
      `${this.v1()}/catalog/detailed-design/chains/${chainId}`,
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
      `${this.v1()}/catalog/design-generator/chains/${chainId}`,
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
      `${this.v1()}/catalog/design-generator/chains/${chainId}/snapshots/${snapshotId}`,
      {
        diagramModes,
      },
    );
    return response.data;
  };

  getSpecApiFiles = (): Promise<SpecApiFile[]> => {
    return Promise.resolve([]);
  };

  readSpecificationFileContent = (): Promise<string> => {
    return Promise.reject(
      new Error(
        "Method readSpecificationFileContent not implemented in RestApi",
      ),
    );
  };

  groupElements = async (
    chainId: string,
    elementIds: string[],
  ): Promise<Element> => {
    const response = await this.instance.post<Element>(
      `${this.v1()}/catalog/chains/${chainId}/elements/groups`,
      elementIds,
    );
    return response.data;
  };

  ungroupElements = async (
    chainId: string,
    groupId: string,
  ): Promise<Element[]> => {
    const response = await this.instance.delete<Element[]>(
      `${this.v1()}/catalog/chains/${chainId}/elements/groups/${groupId}`,
    );
    return response.data;
  };

  getExchanges = async (limit: number): Promise<LiveExchange[]> => {
    const response = await this.instance.get<LiveExchange[]>(
      `${this.v1()}/catalog/live-exchanges`,
      {
        params: {
          limit: limit,
        },
      },
    );
    return response.status === 204 ? [] : response.data;
  };

  terminateExchange = async (
    podIp: string,
    deploymentId: string,
    exchangeId: string,
  ): Promise<void> => {
    await this.instance.delete<void>(
      `${this.v1()}/catalog/live-exchanges/${podIp}/${deploymentId}/${exchangeId}`,
    );
  };

  reconfigure(newGateway: string): void {
    this.instance.defaults.baseURL = newGateway;
  }

  getValidations = async (
    filters: EntityFilterModel[],
    searchString: string,
  ): Promise<DiagnosticValidation[]> => {
    const response = await this.instance.post<DiagnosticValidation[]>(
      `${this.v1()}/catalog/diagnostic/validations`,
      { searchString, filters },
    );
    return response.data;
  };

  getValidation = async (
    validationId: string,
  ): Promise<DiagnosticValidation> => {
    const response = await this.instance.get<DiagnosticValidation>(
      `${this.v1()}/catalog/diagnostic/validations/${validationId}`,
    );
    return response.data;
  };

  runValidations = async (ids: string[]): Promise<void> => {
    await this.instance.patch<void>(
      `${this.v1()}/catalog/diagnostic/validations`,
      undefined,
      {
        params: { validationIds: ids },
      },
    );
  };

  bulkDeploy = async (
    request: BulkDeploymentRequest,
  ): Promise<BulkDeploymentResult[]> => {
    const response = await this.instance.post<BulkDeploymentResult[]>(
      `${this.v1()}/catalog/chains/deployments/bulk`,
      request,
    );
    return response.data;
  };
}
