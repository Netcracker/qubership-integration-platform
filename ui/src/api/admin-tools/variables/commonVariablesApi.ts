import { BaseApi } from "./baseApi.ts";
import { Variable, ApiResponse } from "./types.ts";

export class CommonVariablesApi extends BaseApi {
  private serviceName = "Common Variables API";

  async getAll(): Promise<ApiResponse<Variable[]>> {
    return this.wrap(async () => {
      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/common-variables`
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
        `/api/v1/${import.meta.env.VITE_API_APP}/common-variables`,
        variables
      );
      return response.data;
    }, "Failed to create variables", this.serviceName);
  }

  async update(variable: Variable): Promise<ApiResponse<Variable>> {
    return this.wrap(async () => {
      const response = await this.instance.patch(
        `/api/v1/${import.meta.env.VITE_API_APP}/common-variables/${variable.key}`,
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
        `/api/v1/${import.meta.env.VITE_API_APP}/common-variables?${params}`
      );
    }, "Failed to delete variables");
  }

  async exportVariables(keys: string[], asArchive = true): Promise<boolean> {
    return this.wrapBoolean(async () => {
      const params = new URLSearchParams();
      keys.forEach((key) => params.append("variablesNames", key));
      params.append("asArchive", String(asArchive));

      const response = await this.instance.get(
        `/api/v1/${import.meta.env.VITE_API_APP}/common-variables/export?${params}`,
        { responseType: "blob" }
      );

      const blob = new Blob([response.data], {
        type: asArchive ? "application/zip" : "application/x-yaml",
      });

      const url = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = asArchive ? "common-variables.zip" : "common-variables.yaml";
      document.body.appendChild(link);
      link.click();
    }, "Failed to export variables");
  }

  async importVariables(formData: FormData, isPreview: boolean): Promise<any> {
    if (!formData) return;

    const path = `/api/${isPreview ? "v1" : "v2"}/${import.meta.env.VITE_API_APP}/common-variables/${isPreview ? "preview" : "import"}`;
    return this.wrapRaw(async () => {
      const response = await this.instance.post(path, formData, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      return response.data;
    }, `Failed to ${isPreview ? "preview" : "import"}`);
  }
}

export const commonVariablesApi = new CommonVariablesApi();