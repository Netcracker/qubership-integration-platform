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
  EventsUpdate,
  FolderItem,
  ImportCommitResponse,
  ImportPreview,
  ImportSpecificationResult,
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
  UsedService,
  Element,
} from "../apiTypes.ts";
import { Api } from "../api.ts";

export const STARTUP_EVENT = "navigate";
export const isVsCode = window.location.protocol === "vscode-webview:";

export class VSCodeExtensionApi implements Api {
  vscode: VSCodeApi<never>;
  responseResolvers: Record<string, MessageResolver> = {};

  constructor() {
    this.vscode = acquireVsCodeApi();

    // Listener for messages FROM extension
    window.addEventListener("message", (event) => {
      const message: VSCodeResponse<never> = <VSCodeResponse<never>>event.data;
      const { requestId, error } = message;

      if (requestId && this.responseResolvers[requestId]) {
        if (error) {
          console.error("Error response from VSExtension", error);
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
    void this.sendMessageToExtension(STARTUP_EVENT);
  }

  sendMessageToExtension = async <T, V>(
    type: string,
    payload?: V,
  ): Promise<VSCodeResponse<T>> => {
    const requestId = crypto.randomUUID();
    const message: VSCodeMessage<V> = { type, requestId, payload };

    // @ts-expect-error since any type is prohibited
    this.vscode.postMessage(message);

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

  getElementsByType(): Promise<ElementWithChainName[]> {
    throw new Error("Method not implemented.");
  }

  getRootFolders(): Promise<FolderItem[]> {
    throw new Error("Method not implemented.");
  }

  getChainsUsedByService(): Promise<BaseEntity[]> {
    throw new Error("Method not implemented.");
  }

  exportSpecifications(): Promise<File> {
    throw new Error("Method not implemented.");
  }

  generateApiSpecification(): Promise<File> {
    throw new Error("Method not implemented.");
  }

  getServices(): Promise<IntegrationSystem[]> {
    throw new Error("Method not implemented.");
  }

  createService(): Promise<IntegrationSystem> {
    throw new Error("Method not implemented.");
  }

  createEnvironment(): Promise<Environment> {
    throw new Error("Method not implemented.");
  }

  updateEnvironment(): Promise<Environment> {
    throw new Error("Method not implemented.");
  }

  deleteEnvironment(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  deleteService(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  importSystems(): Promise<ImportSystemResult[]> {
    throw new Error("Method not implemented.");
  }

  importSpecification(): Promise<ImportSpecificationResult> {
    throw new Error("Method not implemented.");
  }

  getImportSpecificationResult(): Promise<ImportSpecificationResult> {
    throw new Error("Method not implemented.");
  }

  importSpecificationGroup(): Promise<ImportSpecificationResult> {
    throw new Error("Method not implemented.");
  }

  deleteSpecificationGroup(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  getService(): Promise<IntegrationSystem> {
    throw new Error("Method not implemented.");
  }

  updateService(): Promise<IntegrationSystem> {
    throw new Error("Method not implemented.");
  }

  getEnvironments(): Promise<Environment[]> {
    throw new Error("Method not implemented.");
  }

  getApiSpecifications(): Promise<SpecificationGroup[]> {
    throw new Error("Method not implemented.");
  }

  updateApiSpecificationGroup(): Promise<SpecificationGroup> {
    throw new Error("Method not implemented.");
  }

  getSpecificationModel(): Promise<Specification[]> {
    throw new Error("Method not implemented.");
  }

  getSpecificationModelSource(): Promise<string> {
    throw new Error("Method not implemented.");
  }

  updateSpecificationModel(): Promise<Specification> {
    throw new Error("Method not implemented.");
  }

  getOperationInfo(): Promise<OperationInfo> {
    throw new Error("Method not implemented.");
  }

  deprecateModel(): Promise<Specification> {
    throw new Error("Method not implemented.");
  }

  deleteSpecificationModel(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  getDetailedDesignTemplates(): Promise<DetailedDesignTemplate[]> {
    throw new Error("Method not implemented.");
  }

  getDetailedDesignTemplate(): Promise<DetailedDesignTemplate> {
    throw new Error("Method not implemented.");
  }

  createOrUpdateDetailedDesignTemplate(): Promise<DetailedDesignTemplate> {
    throw new Error("Method not implemented.");
  }

  deleteDetailedDesignTemplates(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  getChainDetailedDesign(): Promise<ChainDetailedDesign> {
    throw new Error("Method not implemented.");
  }

  getChainSequenceDiagram(): Promise<ElementsSequenceDiagrams> {
    throw new Error("Method not implemented.");
  }

  getSnapshotSequenceDiagram(): Promise<ElementsSequenceDiagrams> {
    throw new Error("Method not implemented.");
  }

  modifyHttpTriggerProperties(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  getElementTypes(): Promise<ElementFilter[]> {
    throw new Error("Method not implemented.");
  }

  getDeploymentsByEngine(): Promise<ChainDeployment[]> {
    throw new Error("Method not implemented.");
  }

  getEnginesByDomain(): Promise<Engine[]> {
    throw new Error("Method not implemented.");
  }

  loadCatalogActionsLog(): Promise<ActionLogResponse> {
    throw new Error("Method not implemented.");
  }

  loadVariablesManagementActionsLog(): Promise<ActionLogResponse> {
    throw new Error("Method not implemented.");
  }

  exportCatalogActionsLog(): Promise<Blob> {
    throw new Error("Method not implemented.");
  }

  exportVariablesManagementActionsLog(): Promise<Blob> {
    throw new Error("Method not implemented.");
  }

  getChains(): Promise<Chain[]> {
    throw new Error("Method not implemented.");
  }

  createChain(): Promise<Chain> {
    throw new Error("Method not implemented.");
  }

  deleteChain(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  duplicateChain(): Promise<Chain> {
    throw new Error("Method not implemented.");
  }

  copyChain(): Promise<Chain> {
    throw new Error("Method not implemented.");
  }

  moveChain(): Promise<Chain> {
    throw new Error("Method not implemented.");
  }

  exportAllChains(): Promise<File> {
    throw new Error("Method not implemented.");
  }

  exportChains(): Promise<File> {
    throw new Error("Method not implemented.");
  }

  createSnapshot(): Promise<Snapshot> {
    throw new Error("Method not implemented.");
  }

  getSnapshots(): Promise<Snapshot[]> {
    throw new Error("Method not implemented.");
  }

  getSnapshot(): Promise<Snapshot> {
    throw new Error("Method not implemented.");
  }

  deleteSnapshot(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  deleteSnapshots(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  revertToSnapshot(): Promise<Snapshot> {
    throw new Error("Method not implemented.");
  }

  updateSnapshot(): Promise<Snapshot> {
    throw new Error("Method not implemented.");
  }

  getDeployments(): Promise<Deployment[]> {
    throw new Error("Method not implemented.");
  }

  createDeployment(): Promise<Deployment> {
    throw new Error("Method not implemented.");
  }

  deleteDeployment(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  getDomains(): Promise<EngineDomain[]> {
    throw new Error("Method not implemented.");
  }

  getLoggingSettings(): Promise<ChainLoggingSettings> {
    throw new Error("Method not implemented.");
  }

  setLoggingProperties(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  deleteLoggingSettings(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  getMaskedFields(): Promise<MaskedField[]> {
    throw new Error("Method not implemented.");
  }

  createMaskedField(): Promise<MaskedField> {
    throw new Error("Method not implemented.");
  }

  deleteMaskedFields(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  deleteMaskedField(): Promise<void> {
    throw new Error("Method not implemented.");
  }

  updateMaskedField(): Promise<MaskedField> {
    throw new Error("Method not implemented.");
  }

  getSessions(): Promise<SessionSearchResponse> {
    throw new Error("Method not implemented.");
  }
  deleteSessions(): Promise<void> {
    throw new Error("Method not implemented.");
  }
  deleteSessionsByChainId(): Promise<void> {
    throw new Error("Method not implemented.");
  }
  exportSessions(): Promise<File> {
    throw new Error("Method not implemented.");
  }
  importSessions(): Promise<Session[]> {
    throw new Error("Method not implemented.");
  }
  retryFromLastCheckpoint(): Promise<void> {
    throw new Error("Method not implemented.");
  }
  getSession(): Promise<Session> {
    throw new Error("Method not implemented.");
  }
  getCheckpointSessions(): Promise<CheckpointSession[]> {
    throw new Error("Method not implemented.");
  }
  retrySessionFromLastCheckpoint(): Promise<void> {
    throw new Error("Method not implemented.");
  }
  getNestedChains(): Promise<Chain[]> {
    throw new Error("Method not implemented.");
  }
  getServicesUsedByChains(): Promise<UsedService[]> {
    throw new Error("Method not implemented.");
  }
  exportServices(): Promise<File> {
    throw new Error("Method not implemented.");
  }
  getImportPreview(): Promise<ImportPreview> {
    throw new Error("Method not implemented.");
  }
  commitImport(): Promise<ImportCommitResponse> {
    throw new Error("Method not implemented.");
  }
  getImportStatus(): Promise<ImportStatusResponse> {
    throw new Error("Method not implemented.");
  }
  getEvents(): Promise<EventsUpdate> {
    throw new Error("Method not implemented.");
  }
  deleteChains(): Promise<void> {
    throw new Error("Method not implemented.");
  }
  getFolder(): Promise<FolderItem> {
    throw new Error("Method not implemented.");
  }
  getPathToFolder(): Promise<FolderItem[]> {
    throw new Error("Method not implemented.");
  }
  listFolder(): Promise<(FolderItem | ChainItem)[]> {
    throw new Error("Method not implemented.");
  }
  createFolder(): Promise<FolderItem> {
    throw new Error("Method not implemented.");
  }
  updateFolder(): Promise<FolderItem> {
    throw new Error("Method not implemented.");
  }
  deleteFolder(): Promise<void> {
    throw new Error("Method not implemented.");
  }
  deleteFolders(): Promise<void> {
    throw new Error("Method not implemented.");
  }
  moveFolder(): Promise<FolderItem> {
    throw new Error("Method not implemented.");
  }
}

interface VSCodeApi<T> {
  postMessage: (message: VSCodeMessage<T>) => void;
  getState?: () => never;
  setState?: (newState: never) => void;
}

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
