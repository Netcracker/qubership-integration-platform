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

  protected fail(
    serviceName: string,
    message: string,
    stacktrace?: string,
  ): ApiResponse<never> {
    return {
      success: false,
      error: {
        responseBody: {
          serviceName,
          errorMessage: message,
          errorDate: new Date().toISOString(),
          stacktrace,
        },
      },
    };
  }

  protected async wrap<T>(
    fn: () => Promise<T>,
    fallbackMessage: string,
    serviceName: string,
  ): Promise<ApiResponse<T>> {
    try {
      const data = await fn();
      return { success: true, data };
    } catch (error) {
      console.error(fallbackMessage, error);
      if (axios.isAxiosError(error) && error.response?.data) {
        const backendError = error.response.data;
        if (backendError?.serviceName && backendError?.errorMessage) {
          return {
            success: false,
            error: { responseBody: backendError as ApiError["responseBody"] },
          };
        }
        if (typeof error.response.data === "string") {
          return this.fail(serviceName, error.response.data);
        }
      }
      return this.fail(serviceName, fallbackMessage);
    }
  }

  protected async wrapBoolean(
    fn: () => Promise<void>,
    fallbackMessage: string,
  ): Promise<boolean> {
    try {
      await fn();
      return true;
    } catch (error) {
      console.error(fallbackMessage, error);
      return false;
    }
  }
}
