import axios, { AxiosResponse } from "axios";
import type {
  Chain,
  ChainCreationRequest,
  Connection,
  ElementRequest,
  LibraryData,
  Element,
  Snapshot,
  ConnectionRequest,
} from "./apiTypes.ts";

class Api {
  instance = axios.create({
    baseURL: import.meta.env.VITE_GATEWAY,
    timeout: 1000,
    headers: { "content-type": "application/json" },
  });
  
  getChains = async (): Promise<Chain[]> => {
    try {
      const response = await this.instance.get<Chain[]>(
        `/api/v1/${import.meta.env.VITE_APP}/catalog/chains`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createChain = async (chain: ChainCreationRequest): Promise<Chain> => {
    try {
      const response = await this.instance.post<Chain>(`/api/v1/${import.meta.env.VITE_APP}/catalog/chains`, chain);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteChain = async (chainId: string): Promise<AxiosResponse> => {
    return await this.instance.delete<Chain>(`/api/v1/${import.meta.env.VITE_APP}/catalog/chains/${chainId}`);
  };

  getLibrary = async (): Promise<LibraryData> => {
    try {
      const response = await this.instance.get<LibraryData>(`/api/v1/${import.meta.env.VITE_APP}/catalog/library`);
      console.log(response);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getElements = async (chainId: string): Promise<Element[]> => {
    try {
      const response = await this.instance.get<Element[]>(
        `/api/v1/${import.meta.env.VITE_APP}/catalog/chains/${chainId}/elements`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createElement = async (
    elementRequest: ElementRequest,
    chainId: string,
  ): Promise<AxiosResponse> => {
    return await this.instance.post<Element>(
      `/api/v1/${import.meta.env.VITE_APP}/catalog/chains/${chainId}/elements`,
      elementRequest,
    );
  };

  updateElement = async (
    chainId: string,
    elementId: string,
  ): Promise<AxiosResponse> => {
    return await this.instance.put(`/api/v1/${import.meta.env.VITE_APP}/catalog/chains/${chainId}/elements/${elementId}`);
  };

  deleteElement = async (
    elementId: string,
    chainId: string,
  ): Promise<AxiosResponse> => {
    return await this.instance.delete(`/api/v1/${import.meta.env.VITE_APP}/catalog/chains/${chainId}/elements`, {
      params: { elementsIds: elementId }, //TODO send array
    });
  };

  getConnections = async (chainId: string): Promise<Connection[]> => {
    try {
      const response = await this.instance.get(`/api/v1/${import.meta.env.VITE_APP}/catalog/chains/${chainId}/dependencies`);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createConnection = async (
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<AxiosResponse> => {
    return await this.instance.post<Connection>(
      `/api/v1/${import.meta.env.VITE_APP}/catalog/chains/${chainId}/dependencies`,
      connectionRequest,
    );
  };

  deleteConnection = async (
    connectionId: string,
    chainId: string,
  ): Promise<AxiosResponse> => {
    return await this.instance.delete(`/api/v1/qip/catalog/chains/${chainId}/dependencies`, {
      params: { dependenciesIds: connectionId }, //TODO send array
    });
  };

  createSnapshot = async (chainId: string): Promise<AxiosResponse> => {
    return await this.instance.post(`/api/v1/qip/catalog/chains/${chainId}/snapshots`);
  };

  getSnapshots = async (chainId: string): Promise<Snapshot[]> => {
    try {
      const response = await this.instance.get(
        `/api/v1/qip/catalog/chains/${chainId}/snapshots`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getLibraryElementByType = async (type: string): Promise<Element> => {
    try {
      const response = await this.instance.get(`/api/v1/qip/catalog/library/${type}`);
      return response.data;
    } catch (err) {
      throw err;
    }
  };
}

export const api = new Api();
