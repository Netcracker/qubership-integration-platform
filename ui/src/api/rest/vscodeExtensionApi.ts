import {
  ActionDifference,
  ActionLogResponse,
  BaseEntity,
  Chain,
  ChainDeployment,
  ChainDetailedDesign,
  ChainItem,
  ChainLoggingSettings,
  CheckpointSession,
  Connection,
  ConnectionRequest,
  Deployment,
  DetailedDesignTemplate,
  ElementFilter,
  CreateElementRequest,
  ElementsSequenceDiagrams,
  ElementWithChainName,
  Engine,
  EngineDomain,
  Environment,
  EnvironmentRequest,
  EventsUpdate,
  FolderItem,
  ImportCommitResponse,
  ImportPreview,
  ImportSpecificationResult,
  ImportSpecificationGroupRequest,
  SerializedFile,
  ImportStatusResponse,
  ImportSystemResult,
  IntegrationSystem,
  LibraryData,
  LibraryElement,
  MaskedField,
  OperationInfo,
  PatchElementRequest,
  RestApiError,
  Session,
  SessionSearchResponse,
  Snapshot,
  Specification,
  SpecificationGroup,
  SystemRequest,
  UsedService,
  Element,
  MaskedFields, TransferElementRequest,
  SystemOperation,
  SpecApiFile
} from "../apiTypes.ts";
import { Api } from "../api.ts";
import { getAppName } from "../../appConfig.ts";

export const NAVIGATE_EVENT = "navigate";
export const STARTUP_EVENT = "startup";
export const isVsCode = window.location.protocol === "vscode-webview:";

export class VSCodeExtensionApi implements Api {
  vscode: VSCodeApi<never>;
  responseResolvers: Record<string, MessageResolver> = {};

  constructor() {
    this.vscode = acquireVsCodeApi();

    // Listener for messages FROM extension
    window.addEventListener("message", (event: MessageEvent<VSCodeResponse<never>>) => {
      const message: VSCodeResponse<never> = event.data;
      const { requestId, error } = message;

      if (requestId && this.responseResolvers[requestId]) {
        if (error) {
          this.responseResolvers[requestId].reject(
            new RestApiError(
              error.message
                ? error.message
                : "Unknown error happened on VSCode Extension side",
              0,
              undefined,
              error,
            ),
          );
        } else {
          this.responseResolvers[requestId].resolve(message);
        }
        delete this.responseResolvers[requestId];
      }
    });
  }

  sendMessageToExtension = async <T, V>(
    type: string,
    payload?: V,
  ): Promise<VSCodeResponse<T>> => {
    const requestId = crypto.randomUUID();
    const message: VSCodeMessage<V> = { type, requestId, payload };

    this.vscode.postMessage({
      command: getAppName(),
      // @ts-expect-error since any type is prohibited
      data: message
    });

    return new Promise((resolve, reject) => {
      this.responseResolvers[requestId] = { resolve, reject };
    });
  };

  getLibrary = async (): Promise<LibraryData> => {
    return <LibraryData>(
      (await this.sendMessageToExtension("getLibrary")).payload
    );
  };

  getElements = async (chainId: string): Promise<Element[]> => {
    return <Element[]>(
      (await this.sendMessageToExtension("getElements", chainId)).payload
    );
  };

  createElement = async (
    elementRequest: CreateElementRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    return <ActionDifference>(
      await this.sendMessageToExtension("createElement", {
        chainId,
        elementRequest,
      })
    ).payload;
  };

  updateElement = async (
    elementRequest: PatchElementRequest,
    chainId: string,
    elementId: string,
  ): Promise<ActionDifference> => {
    return <ActionDifference>(
      await this.sendMessageToExtension("updateElement", {
        chainId,
        elementId,
        elementRequest,
      })
    ).payload;
  };

  transferElement = async (
    transferElementRequest: TransferElementRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    return <ActionDifference>(
      await this.sendMessageToExtension("transferElement", {
        chainId,
        transferElementRequest
      })
    ).payload;
  };

  deleteElements = async (
    elementIds: string[],
    chainId: string,
  ): Promise<ActionDifference> => {
    return <ActionDifference>(
      await this.sendMessageToExtension("deleteElements", {
        chainId,
        elementIds,
      })
    ).payload;
  };

  getConnections = async (chainId: string): Promise<Connection[]> => {
    return <Connection[]>(
      (await this.sendMessageToExtension("getConnections", chainId)).payload
    );
  };

  createConnection = async (
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<ActionDifference> => {
    return <ActionDifference>(
      await this.sendMessageToExtension("createConnection", {
        chainId,
        connectionRequest,
      })
    ).payload;
  };

  deleteConnections = async (
    connectionIds: string[],
    chainId: string,
  ): Promise<ActionDifference> => {
    return <ActionDifference>(
      await this.sendMessageToExtension("deleteConnections", {
        chainId,
        connectionIds,
      })
    ).payload;
  };

  getLibraryElementByType = async (type: string): Promise<LibraryElement> => {
    return <LibraryElement>(
      (await this.sendMessageToExtension("getLibraryElementByType", type))
        .payload
    );
  };

  getChain = async (id: string): Promise<Chain> => {
    return <Chain>(await this.sendMessageToExtension("getChain", id)).payload;
  };

  updateChain = async (id: string, chain: Partial<Chain>): Promise<Chain> => {
    return <Chain>(
      (await this.sendMessageToExtension("updateChain", { id, chain })).payload
    );
  };

  getMaskedFields = async (chainId: string): Promise<MaskedField[]> => {
    return (
      (<MaskedFields>(await this.sendMessageToExtension("getMaskedFields", chainId)).payload)?.fields
    );
  };

  createMaskedField = async (
    chainId: string,
    maskedField: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    return <MaskedField>(
      await this.sendMessageToExtension("createMaskedField", {
        chainId,
        maskedField,
      })
    ).payload;
  };

  deleteMaskedFields = async (
    chainId: string,
    maskedFieldIds: string[],
  ): Promise<void> => {
    await this.sendMessageToExtension("deleteMaskedFields", {
      chainId,
      maskedFieldIds,
    });
  };

  deleteMaskedField = async (
    chainId: string,
    maskedFieldId: string,
  ): Promise<void> => {
    await this.deleteMaskedFields(chainId, [maskedFieldId]);
  };

  updateMaskedField = async (
    chainId: string,
    id: string,
    maskedField: Partial<Omit<MaskedField, "id">>,
  ): Promise<MaskedField> => {
    return <MaskedField>(
      await this.sendMessageToExtension("updateMaskedField", {
        id,
        chainId,
        maskedField,
      })
    ).payload;
  };

  getElementsByType = async (): Promise<ElementWithChainName[]> => {
    return <ElementWithChainName[]>(
      (await this.sendMessageToExtension("getElementsByType")).payload
    );
  }

  getRootFolders = (): Promise<FolderItem[]> => {
    return Promise.resolve([]);
  };

  getChainsUsedByService(): Promise<BaseEntity[]> {
    return Promise.resolve([]);
  }

  exportSpecifications(): Promise<File> {
    throw new Error("Method exportSpecifications not implemented.");
  }

  generateApiSpecification(): Promise<File> {
    throw new Error("Method generateApiSpecification not implemented.");
  }

  getServices = async (): Promise<IntegrationSystem[]> => {
    return <IntegrationSystem[]>(
      (await this.sendMessageToExtension("getServices")).payload
    );
  };

  createService = async (request: SystemRequest): Promise<IntegrationSystem> => {
    return <IntegrationSystem>(
      (await this.sendMessageToExtension("createService", request)).payload
    );
  };

  createEnvironment = async (systemId: string, request: EnvironmentRequest): Promise<Environment> => {
    return <Environment>(
      (await this.sendMessageToExtension("createEnvironment", { serviceId: systemId, environment: request })).payload
    );
  };

  updateEnvironment = async (systemId: string, environmentId: string, request: EnvironmentRequest): Promise<Environment> => {
    return <Environment>(
      (await this.sendMessageToExtension("updateEnvironment", { serviceId: systemId, environmentId, environment: request })).payload
    );
  };

  deleteEnvironment = async (systemId: string, environmentId: string): Promise<void> => {
    await this.sendMessageToExtension("deleteEnvironment", { serviceId: systemId, environmentId });
  };

  deleteService(): Promise<void> {
    throw new Error("Method deleteService not implemented.");
  }

  importSystems = async (
    file: File,
    systemIds?: string[],
    deployLabel?: string,
    packageName?: string,
    packageVersion?: string,
    packagePartOf?: string,
  ): Promise<ImportSystemResult[]> => {

     const response = await this.sendMessageToExtension("importSystems", {
        file,
        systemIds,
        deployLabel,
        packageName,
        packageVersion,
        packagePartOf
     });
    return response.payload as ImportSystemResult[];
  };

  importSpecification = async (specificationGroupId: string, files: File[], systemId: string): Promise<ImportSpecificationResult> => {
    const serializedFiles: SerializedFile[] = await Promise.all(files.map(async (file) => ({
      name: file.name,
      size: file.size,
      type: file.type,
      lastModified: file.lastModified,
      content: await file.arrayBuffer()
    })));

    return <ImportSpecificationResult>(
      (await this.sendMessageToExtension("importSpecification", { specificationGroupId, files: serializedFiles, systemId })).payload
    );
  };

  getImportSpecificationResult = async (importId: string): Promise<ImportSpecificationResult> => {
    return <ImportSpecificationResult>(
      (await this.sendMessageToExtension("getImportSpecificationResult", { importId })).payload
    );
  };

  importSpecificationGroup = async (systemId: string, name: string, files: File[], protocol?: string): Promise<ImportSpecificationResult> => {
    const serializedFiles: SerializedFile[] = await Promise.all(files.map(async (file) => {
      const serializedFile = {
        name: file.name,
        size: file.size,
        type: file.type,
        lastModified: file.lastModified,
        content: await file.arrayBuffer()
      };
      return serializedFile;
    }));

    const request: ImportSpecificationGroupRequest = { systemId, name, files: serializedFiles, protocol };
    return <ImportSpecificationResult>(
      (await this.sendMessageToExtension("importSpecificationGroup", request)).payload
    );
  };

  deleteSpecificationGroup = async (groupId: string): Promise<void> => {
    await this.sendMessageToExtension("deleteSpecificationGroup", groupId);
  };

  getService = async (systemId: string): Promise<IntegrationSystem> => {
    const response = await this.sendMessageToExtension("getService", systemId);
    return <IntegrationSystem>response.payload;
  };

  updateService = async (systemId: string, service: Partial<IntegrationSystem>): Promise<IntegrationSystem> => {
    return <IntegrationSystem>(
      (await this.sendMessageToExtension("updateService", { id: systemId, service })).payload
    );
  };

  getEnvironments = async (systemId: string): Promise<Environment[]> => {
    const result = <Environment[]>(
      (await this.sendMessageToExtension("getEnvironments", systemId)).payload
    );
    return result;
  };

  getApiSpecifications = async (systemId: string): Promise<SpecificationGroup[]> => {
    return <SpecificationGroup[]>(
      (await this.sendMessageToExtension("getApiSpecifications", systemId)).payload
    );
  };

  updateApiSpecificationGroup = async (groupId: string, group: Partial<SpecificationGroup>): Promise<SpecificationGroup> => {
    return <SpecificationGroup>(
      (await this.sendMessageToExtension("updateApiSpecificationGroup", { id: groupId, group })).payload
    );
  };

  getSpecificationModel = async (systemId: string, groupId: string): Promise<Specification[]> => {
    return <Specification[]>(
      (await this.sendMessageToExtension("getSpecificationModel", { serviceId: systemId, groupId })).payload
    );
  };

  getSpecificationModelSource = async (id: string): Promise<string> => {
    return <string>(
      (await this.sendMessageToExtension("getSpecificationModelSource", id)).payload
    );
  };

  updateSpecificationModel = async (modelId: string, model: Partial<Specification>): Promise<Specification> => {
    return <Specification>(
      (await this.sendMessageToExtension("updateSpecificationModel", { id: modelId, model })).payload
    );
  };

  getOperations = async (modelId: string): Promise<SystemOperation[]> => {
    return <SystemOperation[]>(
      (await this.sendMessageToExtension("getOperations", modelId)).payload
    );
  }

  getOperationInfo = async (operationId: string): Promise<OperationInfo> => {
    return <OperationInfo>(
      (await this.sendMessageToExtension("getOperationInfo", operationId)).payload
    );
  };

  openChainInNewTab = async (chainId: string): Promise<void> => {
    await this.sendMessageToExtension("openChainInNewTab", chainId);
  }

  navigateInNewTab = async (path: string): Promise<void> => {
    await this.sendMessageToExtension("navigateInNewTab", path);
  }

  navigateToSpecifications = async (groupId: string): Promise<string> => {
    return <string>(
      (await this.sendMessageToExtension("navigateToSpecifications", { groupId })).payload
    );
  };

  navigateToOperations = async (groupId: string, specId: string): Promise<string> => {
    return <string>(
      (await this.sendMessageToExtension("navigateToOperations", { groupId, specId })).payload
    );
  };

  deprecateModel = async (modelId: string): Promise<Specification> => {
    return <Specification>(
      (await this.sendMessageToExtension("deprecateModel", modelId)).payload
    );
  };

  deleteSpecificationModel = async (modelId: string): Promise<void> => {
    await this.sendMessageToExtension("deleteSpecificationModel", modelId);
  };

  moveChain = async (chainId: string, folder?: string): Promise<Chain> => {
    return <Chain>(
      (await this.sendMessageToExtension("moveChain", { chainId, folder })).payload
    );
  }

  getDetailedDesignTemplates(): Promise<DetailedDesignTemplate[]> {
    throw new Error("Method getDetailedDesignTemplates not implemented.");
  }

  getDetailedDesignTemplate(): Promise<DetailedDesignTemplate> {
    throw new Error("Method getDetailedDesignTemplate not implemented.");
  }

  createOrUpdateDetailedDesignTemplate(): Promise<DetailedDesignTemplate> {
    throw new Error("Method createOrUpdateDetailedDesignTemplate not implemented.");
  }

  deleteDetailedDesignTemplates(): Promise<void> {
    throw new Error("Method deleteDetailedDesignTemplates not implemented.");
  }

  getChainDetailedDesign(): Promise<ChainDetailedDesign> {
    throw new Error("Method getChainDetailedDesign not implemented.");
  }

  getChainSequenceDiagram(): Promise<ElementsSequenceDiagrams> {
    throw new Error("Method getChainSequenceDiagram not implemented.");
  }

  getSnapshotSequenceDiagram(): Promise<ElementsSequenceDiagrams> {
    throw new Error("Method getSnapshotSequenceDiagram not implemented.");
  }

  modifyHttpTriggerProperties(): Promise<void> {
    throw new Error("Method modifyHttpTriggerProperties not implemented.");
  }

  getElementTypes(): Promise<ElementFilter[]> {
    throw new Error("Method getElementTypes not implemented.");
  }

  getDeploymentsByEngine(): Promise<ChainDeployment[]> {
    throw new Error("Method getDeploymentsByEngine not implemented.");
  }

  getEnginesByDomain(): Promise<Engine[]> {
    throw new Error("Method getEnginesByDomain not implemented.");
  }

  loadCatalogActionsLog(): Promise<ActionLogResponse> {
    throw new Error("Method loadCatalogActionsLog not implemented.");
  }

  loadVariablesManagementActionsLog(): Promise<ActionLogResponse> {
    throw new Error("Method loadVariablesManagementActionsLog not implemented.");
  }

  exportCatalogActionsLog(): Promise<Blob> {
    throw new Error("Method exportCatalogActionsLog not implemented.");
  }

  exportVariablesManagementActionsLog(): Promise<Blob> {
    throw new Error("Method exportVariablesManagementActionsLog not implemented.");
  }

  getChains(): Promise<Chain[]> {
    throw new Error("Method getChains not implemented.");
  }

  createChain(): Promise<Chain> {
    throw new Error("Method createChain not implemented.");
  }

  deleteChain(): Promise<void> {
    throw new Error("Method deleteChain not implemented.");
  }

  duplicateChain(): Promise<Chain> {
    throw new Error("Method duplicateChain not implemented.");
  }

  copyChain(): Promise<Chain> {
    throw new Error("Method copyChain not implemented.");
  }

  exportAllChains(): Promise<File> {
    throw new Error("Method exportAllChains not implemented.");
  }

  exportChains(): Promise<File> {
    throw new Error("Method exportChains not implemented.");
  }

  createSnapshot(): Promise<Snapshot> {
    throw new Error("Method createSnapshot not implemented.");
  }

  getSnapshots(): Promise<Snapshot[]> {
    throw new Error("Method getSnapshots not implemented.");
  }

  getSnapshot(): Promise<Snapshot> {
    throw new Error("Method getSnapshot not implemented.");
  }

  deleteSnapshot(): Promise<void> {
    throw new Error("Method deleteSnapshot not implemented.");
  }

  deleteSnapshots(): Promise<void> {
    throw new Error("Method deleteSnapshots not implemented.");
  }

  revertToSnapshot(): Promise<Snapshot> {
    throw new Error("Method revertToSnapshot not implemented.");
  }

  updateSnapshot(): Promise<Snapshot> {
    throw new Error("Method updateSnapshot not implemented.");
  }

  getDeployments(): Promise<Deployment[]> {
    throw new Error("Method getDeployments not implemented.");
  }

  createDeployment(): Promise<Deployment> {
    throw new Error("Method createDeployment not implemented.");
  }

  deleteDeployment(): Promise<void> {
    throw new Error("Method deleteDeployment not implemented.");
  }

  getDomains(): Promise<EngineDomain[]> {
    throw new Error("Method getDomains not implemented.");
  }

  getLoggingSettings(): Promise<ChainLoggingSettings> {
    throw new Error("Method getLoggingSettings not implemented.");
  }

  setLoggingProperties(): Promise<void> {
    throw new Error("Method setLoggingProperties not implemented.");
  }

  deleteLoggingSettings(): Promise<void> {
    throw new Error("Method deleteLoggingSettings not implemented.");
  }

  getSessions(): Promise<SessionSearchResponse> {
    throw new Error("Method getSessions not implemented.");
  }
  deleteSessions(): Promise<void> {
    throw new Error("Method deleteSessions not implemented.");
  }
  deleteSessionsByChainId(): Promise<void> {
    throw new Error("Method deleteSessionsByChainId not implemented.");
  }
  exportSessions(): Promise<File> {
    throw new Error("Method exportSessions not implemented.");
  }
  importSessions(): Promise<Session[]> {
    throw new Error("Method importSessions not implemented.");
  }
  retryFromLastCheckpoint(): Promise<void> {
    throw new Error("Method retryFromLastCheckpoint not implemented.");
  }
  getSession(): Promise<Session> {
    throw new Error("Method getSession not implemented.");
  }
  getCheckpointSessions(): Promise<CheckpointSession[]> {
    throw new Error("Method getCheckpointSessions not implemented.");
  }
  retrySessionFromLastCheckpoint(): Promise<void> {
    throw new Error("Method retrySessionFromLastCheckpoint not implemented.");
  }
  getNestedChains(): Promise<Chain[]> {
    throw new Error("Method getNestedChains not implemented.");
  }
  getServicesUsedByChains(): Promise<UsedService[]> {
    throw new Error("Method getServicesUsedByChains not implemented.");
  }
  exportServices(): Promise<File> {
    throw new Error("Method exportServices not implemented.");
  }
  getImportPreview(): Promise<ImportPreview> {
    throw new Error("Method getImportPreview not implemented.");
  }
  commitImport(): Promise<ImportCommitResponse> {
    throw new Error("Method commitImport not implemented.");
  }
  getImportStatus(): Promise<ImportStatusResponse> {
    throw new Error("Method getImportStatus not implemented.");
  }
  getEvents(): Promise<EventsUpdate> {
    throw new Error("Method getEvents not implemented.");
  }
  deleteChains(): Promise<void> {
    throw new Error("Method deleteChains not implemented.");
  }
  getFolder(): Promise<FolderItem> {
    throw new Error("Method getFolder not implemented.");
  }
  getPathToFolder(): Promise<FolderItem[]> {
    throw new Error("Method getPathToFolder not implemented.");
  }
  getPathToFolderByName(): Promise<FolderItem[]> {
    throw new Error("Method getPathToFolder not implemented.");
  }
  listFolder(): Promise<(FolderItem | ChainItem)[]> {
    throw new Error("Method listFolder not implemented.");
  }
  createFolder(): Promise<FolderItem> {
    throw new Error("Method createFolder not implemented.");
  }
  updateFolder(): Promise<FolderItem> {
    throw new Error("Method updateFolder not implemented.");
  }
  deleteFolder(): Promise<void> {
    throw new Error("Method deleteFolder not implemented.");
  }
  deleteFolders(): Promise<void> {
    throw new Error("Method deleteFolders not implemented.");
  }
  moveFolder(): Promise<FolderItem> {
    throw new Error("Method moveFolder not implemented.");
  }

  getSpecApiFiles = async (): Promise<SpecApiFile[]> => {
    return <SpecApiFile[]>(
      (await this.sendMessageToExtension("getSpecApiFiles")).payload
    );
  };

  readSpecificationFileContent = async (fileUri: string, specificationFilePath: string): Promise<string> => {
    return <string>(
      (await this.sendMessageToExtension("readSpecificationFileContent", { fileUri, specificationFilePath })).payload
    );
  };
}

interface VSCodeApi<T> {
  postMessage: (message: VSCodeMessageWrapper<T>) => void;
  getState?: () => never;
  setState?: (newState: never) => void;
}

export type VSCodeMessageWrapper<T> = {
  command: string;
  data: VSCodeMessage<T>;
};

export type VSCodeMessage<T> = {
  type: string;
  requestId: string;
  payload?: T;
};

export type VSCodeResponse<T> = {
  type: string;
  requestId: string;
  payload?: T;
  error?: Error;
};

type MessageResolver = {
  resolve: (value: VSCodeResponse<never>) => void;
  reject: (value: Error) => void;
};

declare function acquireVsCodeApi<T>(): VSCodeApi<T>;
