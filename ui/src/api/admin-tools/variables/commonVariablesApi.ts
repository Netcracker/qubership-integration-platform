import { BaseApi } from "./baseApi.ts";
import { Variable, ApiResponse } from "./types.ts";
import { getFileFromResponse } from "../../../misc/download-utils.ts";

const urlPrefixV1 = `/api/v1/${import.meta.env.VITE_API_APP}/variables-management`;

export class CommonVariablesApi extends BaseApi {
  private serviceName = "Common Variables API";

  async getAll(): Promise<ApiResponse<Variable[]>> {
    return this.wrap(async () => {
      const response = await this.instance.get(
        `${urlPrefixV1}/common-variables`
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
    }, "Failed to fetch common variables", this.serviceName);
  }

  async create(variable: Variable): Promise<ApiResponse<string[]>> {
    return this.createMany({ [variable.key]: variable.value });
  }

  async createMany(variables: Record<string, string>): Promise<ApiResponse<string[]>> {
    return this.wrap(async () => {
      const response = await this.instance.post(
        `${urlPrefixV1}/common-variables`,
        variables
      );
      return response.data;
    }, "Failed to create variables", this.serviceName);
  }

  async update(variable: Variable): Promise<ApiResponse<Variable>> {
    return this.wrap(async () => {
      const response = await this.instance.patch(
        `${urlPrefixV1}/common-variables/${variable.key}`,
        variable.value,
        {
          headers: {
            'Content-Type': 'text/plain'
          }
        }
      );
      return response.data;
    }, "Failed to update variable", this.serviceName);
  }

  async delete(keys: string[]): Promise<boolean> {
    return this.wrapBoolean(async () => {
      const params = new URLSearchParams();
      keys.forEach((key) => params.append("variablesNames", key));
      await this.instance.delete(
        `${urlPrefixV1}/common-variables?${params}`
      );
    }, "Failed to delete variables");
  }

  async exportVariables(keys: string[], asArchive = true): Promise<File> {
      const params = new URLSearchParams();
      keys.forEach((key) => params.append("variablesNames", key));
      params.append("asArchive", String(asArchive));

      const response = await this.instance.get<Blob>(
        `${urlPrefixV1}/common-variables/export?${params}`,
        { responseType: "blob" }
      );

      return getFileFromResponse(response);
  }

  async importVariables(formData: FormData, isPreview: boolean): Promise<ApiResponse<any>> {
    if (!formData) {
      return {
        success: false,
        error: {
          responseBody: {
            serviceName: this.serviceName,
            errorMessage: "No file selected or form data is empty.",
            errorDate: new Date().toISOString(),
          }
        }
      };
    }

    const path = `/api/${isPreview ? "v1" : "v2"}/${import.meta.env.VITE_API_APP}/variables-management/common-variables/${isPreview ? "preview" : "import"}`;
    return this.wrap(async () => {
      const response = await this.instance.post(path, formData, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      return response.data;
    }, `Failed to ${isPreview ? "preview" : "import"}`, this.serviceName);
  }
}

export const commonVariablesApi = new CommonVariablesApi();
