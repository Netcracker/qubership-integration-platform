import {
  Chain,
  ChainCreationRequest,
  Connection,
  ElementRequest,
  LibraryData,
  Element,
  Snapshot,
  ConnectionRequest,
  ActionDifference, Deployment
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

  createSnapshot(chainId: string): Promise<void>;

  getSnapshots(chainId: string): Promise<Snapshot[]>;

  getLibraryElementByType(type: string): Promise<Element>;

  getDeployments(chainId: string): Promise<Deployment[]>;
}

export const api: Api = new RestApi(); 