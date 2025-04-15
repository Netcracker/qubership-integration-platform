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

const instance = axios.create({
  //TODO move instance inside class
  baseURL: "http://localhost:8090/", //TODO: move to env param
  timeout: 1000,
  headers: { "content-type": "application/json" },
});

//TODO remove second instance after introduction of gateway for services
const instance2 = axios.create({
  //TODO move instance inside class
  baseURL: "http://localhost:8091/", //TODO: move to env param
  timeout: 1000,
  headers: { "content-type": "application/json" },
});

class Api {
  getChains = async (): Promise<Chain[]> => {
    try {
      const response = await instance.get<Chain[]>("/v1/chains");
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createChain = async (chain: ChainCreationRequest): Promise<Chain> => {
    try {
      const response = await instance.post<Chain>("/v1/chains", chain);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  deleteChain = async (chainId: string): Promise<AxiosResponse> => {
    return await instance.delete<Chain>(`/v1/chains/${chainId}`);
  };

  getLibrary = async (): Promise<LibraryData> => {
    try {
      const response = await instance.get<LibraryData>("/v1/library");
      console.log(response);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getElements = async (chainId: string): Promise<Element[]> => {
    try {
      const response = await instance.get<Element[]>(
        `/v1/chains/${chainId}/elements`,
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
    return await instance.post<Element>(
      `/v1/chains/${chainId}/elements`,
      elementRequest,
    );
  };

  updateElement = async (
    chainId: string,
    elementId: string,
  ): Promise<AxiosResponse> => {
    return await instance.put(`/v1/chains/${chainId}/elements/${elementId}`);
  };

  deleteElement = async (
    elementId: string,
    chainId: string,
  ): Promise<AxiosResponse> => {
    return await instance.delete(`/v1/chains/${chainId}/elements`, {
      params: { elementsIds: elementId }, //TODO send array
    });
  };

  getConnections = async (chainId: string): Promise<Connection[]> => {
    try {
      const response = await instance.get(`/v1/chains/${chainId}/dependencies`);
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  createConnection = async (
    connectionRequest: ConnectionRequest,
    chainId: string,
  ): Promise<AxiosResponse> => {
    return await instance.post<Connection>(
      `/v1/chains/${chainId}/dependencies`,
      connectionRequest,
    );
  };

  deleteConnection = async (
    connectionId: string,
    chainId: string,
  ): Promise<AxiosResponse> => {
    return await instance.delete(`/v1/chains/${chainId}/dependencies`, {
      params: { dependenciesIds: connectionId }, //TODO send array
    });
  };

  createSnapshot = async (chainId: string): Promise<AxiosResponse> => {
    return await instance2.post(`/v1/catalog/chains/${chainId}/snapshots`);
  };

  getSnapshots = async (chainId: string): Promise<Snapshot[]> => {
    try {
      const response = await instance2.get(
        `/v1/catalog/chains/${chainId}/snapshots`,
      );
      return response.data;
    } catch (err) {
      throw err;
    }
  };

  getLibraryElementByType = async (type: string): Promise<Element> => {
    try {
      const response = await instance.get(`/v1/library/${type}`);
      return response.data;
    } catch (err) {
      throw err;
    }
  };
}

export const api = new Api();
