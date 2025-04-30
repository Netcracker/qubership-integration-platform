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
  EngineDomain, EntityLabel
} from "./apiTypes.ts";
import { RestApi } from "./rest/restApi.ts";

export interface Api {
  getChains(): Promise<Chain[]>;

  getChain(id: string): Promise<Chain>;

  updateChain(id: string, chain: Partial<Chain>): Promise<Chain>;

  createChain(chain: ChainCreationRequest): Promise<Chain>;

  deleteChain(chainId: string): Promise<void>;

  getLibrary(): Promise<LibraryData>;

  getElements(chainId: string): Promise<Element[]>;

  createElement(
    elementRequest: ElementRequest,
    chainId: string,
  ): Promise<ActionDifference>;

  updateElement(
    chainId: string,
    elementId: string,
  ): Promise<ActionDifference>;

  deleteElement(
    elementId: string,
    chainId: string,
  ): Promise<ActionDifference>;

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

  updateSnapshot(snapshotId: string, name: string, labels: EntityLabel[]): Promise<Snapshot>;

  revertToSnapshot(chainId: string, snapshotId: string): Promise<Snapshot>;

  getLibraryElementByType(type: string): Promise<Element>;

  getDeployments(chainId: string): Promise<Deployment[]>;

  createDeployment(chainId: string, request: CreateDeploymentRequest): Promise<Deployment>;

  deleteDeployment(deploymentId: string): Promise<void>;

  getDomains(): Promise<EngineDomain[]>;
}

export const api: Api = new RestApi(); 