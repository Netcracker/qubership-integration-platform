import { BaseApi } from "./baseApi.ts";
import { Variable, ApiResponse } from "./types.ts";
import { getFileFromResponse } from "../../../misc/download-utils.ts";
import {
  ImportVariablesResult,
  VariableImportPreview,
} from "../../apiTypes.ts";

const urlPrefixV1 = `/api/v1/${import.meta.env.VITE_API_APP}/variables-management`;

export class CommonVariablesApi extends BaseApi {
  private serviceName = "Common Variables API";

  async getAll(): Promise<ApiResponse<Variable[]>> {
    return this.wrap(
      async () => {
        const response = await this.instance.get<Variable[]>(
          `${urlPrefixV1}/common-variables`,
        );

        const rawData = response.data;
        if (rawData && typeof rawData === "object" && !Array.isArray(rawData)) {
          const variables = Object.entries(rawData).map(([key, value]) => ({
            key,
            value: String(value),
          }));
          return variables;
        }

        throw new Error("Unexpected response format");
      },
      "Failed to fetch common variables",
      this.serviceName,
    );
  }

  async create(variable: Variable): Promise<ApiResponse<string[]>> {
    return this.createMany({ [variable.key]: variable.value });
  }

  async createMany(
    variables: Record<string, string>,
  ): Promise<ApiResponse<string[]>> {
    return this.wrap(
      async () => {
        const response = await this.instance.post<string[]>(
          `${urlPrefixV1}/common-variables`,
          variables,
        );
        return response.data;
      },
      "Failed to create variables",
      this.serviceName,
    );
  }

  async update(variable: Variable): Promise<ApiResponse<Variable>> {
    return this.wrap(
      async () => {
        const response = await this.instance.patch<Variable>(
          `${urlPrefixV1}/common-variables/${variable.key}`,
          variable.value,
          {
            headers: {
              "Content-Type": "text/plain",
            },
          },
        );
        return response.data;
      },
      "Failed to update variable",
      this.serviceName,
    );
  }

  async delete(keys: string[]): Promise<boolean> {
    return this.wrapBoolean(async () => {
      const params = new URLSearchParams();
      keys.forEach((key) => params.append("variablesNames", key));
      await this.instance.delete(`${urlPrefixV1}/common-variables?${params}`);
    }, "Failed to delete variables");
  }

  async exportVariables(keys: string[], asArchive = true): Promise<File> {
    const params = new URLSearchParams();
    keys.forEach((key) => params.append("variablesNames", key));
    params.append("asArchive", String(asArchive));

    const response = await this.instance.get<Blob>(
      `${urlPrefixV1}/common-variables/export?${params}`,
      { responseType: "blob" },
    );

    return getFileFromResponse(response);
  }

  async importVariablesPreview(
    formData: FormData,
  ): Promise<ApiResponse<VariableImportPreview[]>> {
    if (!formData) {
      return this.getEmptyFormResponse();
    }
    const path = `/api/v1/${import.meta.env.VITE_API_APP}/variables-management/common-variables/preview`;
    return this.wrap(
      async () => {
        const response = await this.instance.post<VariableImportPreview[]>(
          path,
          formData,
          {
            headers: { "Content-Type": "multipart/form-data" },
          },
        );
        return response.data;
      },
      `Failed to get import variables preview`,
      this.serviceName,
    );
  }

  async importVariables(
    formData: FormData,
  ): Promise<ApiResponse<ImportVariablesResult>> {
    if (!formData) {
      return this.getEmptyFormResponse();
    }

    const path = `/api/v2/${import.meta.env.VITE_API_APP}/variables-management/common-variables/import`;
    return this.wrap(
      async () => {
        const response = await this.instance.post<ImportVariablesResult>(
          path,
          formData,
          {
            headers: { "Content-Type": "multipart/form-data" },
          },
        );
        return response.data;
      },
      `Failed to import`,
      this.serviceName,
    );
  }

  private getEmptyFormResponse<T>(): ApiResponse<T> {
    return {
      success: false,
      error: {
        responseBody: {
          serviceName: this.serviceName,
          errorMessage: "No file selected or form data is empty.",
          errorDate: new Date().toISOString(),
        },
      },
    };
  }
}

export const commonVariablesApi = new CommonVariablesApi();
