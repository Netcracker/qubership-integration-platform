import axios, { AxiosInstance } from "axios";
import { ApiResponse, ApiError } from "./types.ts";

export abstract class BaseApi {
  protected readonly instance: AxiosInstance;

  constructor() {
    this.instance = axios.create({
      baseURL: import.meta.env.VITE_GATEWAY,
      timeout: 1000,
      headers: { "Content-Type": "application/json" },
    });
  }

  protected fail(serviceName: string, message: string): ApiResponse<any> {
    return {
      success: false,
      error: {
        serviceName,
        errorMessage: message,
        errorDate: new Date().toISOString(),
      },
    };
  }

  protected wrap<T>(fn: () => Promise<T>, fallbackMessage: string, serviceName: string): Promise<ApiResponse<T>> {
    return fn().then((data) => ({ success: true, data })).catch((error) => {
      console.error(fallbackMessage, error);
      if (axios.isAxiosError(error) && error.response?.data) {
        const backendError = error.response.data as ApiError;
        if (backendError?.serviceName && backendError?.errorMessage) {
          return { success: false, error: backendError };
        }
        if (typeof error.response.data === "string") {
          return this.fail(serviceName, error.response.data);
        }
      }
      return this.fail(serviceName, fallbackMessage);
    });
  }

  protected wrapBoolean(fn: () => Promise<void>, fallbackMessage: string): Promise<boolean> {
    return fn().then(() => true).catch((error) => {
      console.error(fallbackMessage, error);
      return false;
    });
  }

  protected wrapRaw<T>(fn: () => Promise<T>, fallbackMessage: string): Promise<T | undefined> {
    return fn().catch((error) => {
      console.error(fallbackMessage, error);
      return undefined;
    });
  }
}