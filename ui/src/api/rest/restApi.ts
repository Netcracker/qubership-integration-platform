import axios from "axios";
import type {
  Chain,
  ChainCreationRequest,
  Connection,
  ElementRequest,
  LibraryData,
  Element,
  Snapshot,
  ConnectionRequest,
  ActionDifference,
  Deployment
} from "../apiTypes.ts";
import { Api } from "../api.ts";

export class RestApi implements Api {
  instance = axios.create({
    baseURL: import.meta.env.GATEWAY,
    timeout: 1000,
    headers: { "content-type": "application/json" },
  });

  getChains = async (): Promise<Chain[]> => {
    try {
      const response = await this.instance.get<Chain[]>(
        `/api/v1/${import.meta.env.API_APP}/catalog/chains`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getChain = async (id: string): Promise<Chain> => {
    try {
      const response = await this.instance.get<Chain>(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${id}`)
      return response.data;
    } catch (err) {
      throw err;
    }
  }

  updateChain = async (id: string, chain: Partial<Chain>): Promise<Chain> => {
    try {
      const response = await this.instance.put<Chain>(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${id}`, chain);
      return response.data;
    } catch (err) {
      throw err;
    }
  }

  createChain = async (chain: ChainCreationRequest): Promise<Chain> => {
    try {
      const response = await this.instance.post<Chain>(`/api/v1/${import.meta.env.API_APP}/catalog/chains`, chain);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteChain = async (chainId: string): Promise<void> => {
    await this.instance.delete<Chain>(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}`);
  };

  getLibrary = async (): Promise<LibraryData> => {
    try {
      const response = await this.instance.get<LibraryData>(`/api/v1/${import.meta.env.API_APP}/catalog/library`);
      console.log(response);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getElements = async (chainId: string): Promise<Element[]> => {
    try {
      const response = await this.instance.get<Element[]>(
        `/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/elements`,
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
      `/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/elements`,
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
      const response = await this.instance.put(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/elements/${elementId}`);
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
      const response = await this.instance.delete(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/elements`, {
      params: { elementsIds: elementId }, //TODO send array
    });
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getConnections = async (chainId: string): Promise<Connection[]> => {
    try {
      const response = await this.instance.get(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/dependencies`);
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
        `/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/dependencies`,
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
      const response = await this.instance.delete(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/dependencies`, {
        params: { dependenciesIds: connectionId }, //TODO send array
      });
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createSnapshot = async (chainId: string): Promise<void> => {
    await this.instance.post(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/snapshots`);
  };

  getSnapshots = async (chainId: string): Promise<Snapshot[]> => {
    try {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/snapshots`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getLibraryElementByType = async (type: string): Promise<Element> => {
    try {
      const response = await this.instance.get(`/api/v1/${import.meta.env.API_APP}/catalog/library/${type}`);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getDeployments = async (chainId: string): Promise<Deployment[]> => {
    try {
      const response = await this.instance.get(`/api/v1/${import.meta.env.API_APP}/catalog/chains/${chainId}/deployments`);
      return response.data;
    } catch (err) {
      throw err;
    }
  }
}
