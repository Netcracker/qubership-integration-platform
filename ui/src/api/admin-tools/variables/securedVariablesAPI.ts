
import { BaseApi } from "./baseApi.ts";
import { Variable, SecretWithVariables, ApiResponse } from "./types.ts";

export class SecuredVariablesApi extends BaseApi {
  private serviceName = "Secured Variables API";

  async getAll(): Promise<ApiResponse<SecretWithVariables[]>> {
    return this.wrap(async () => {
      const response = await this.instance.get(
        `/api/v2/${import.meta.env.VITE_API_APP}/secured-variables`
      );

      return response.data.map(({ secretName, variablesNames, defaultSecret }: any) => ({
        secretName,
        variables: variablesNames.map((key: string) => ({
          key,
          value: "******",
        })),
        isDefaultSecret: defaultSecret,
      }));
    }, "Failed to fetch secured variables", this.serviceName);
  }

  async getForSecret(secretName: string): Promise<ApiResponse<Variable[]>> {
    return this.wrap(async () => {
      const response = await this.instance.get(
        `/api/v2/${import.meta.env.VITE_API_APP}/secured-variables/${secretName}`
      );

      return response.data.map((key: string) => ({
        key,
        value: "******",
      }));
    }, "Failed to fetch variables for secret", this.serviceName);
  }

  async create(secretName: string, variables: Variable[]): Promise<ApiResponse<Variable[]>> {
    const keyRegex = /^[a-zA-Z0-9_-]+$/;
    for (const v of variables) {
      if (!keyRegex.test(v.key)) {
        return this.fail(this.serviceName, `Invalid key: ${v.key}`);
      }
    }

    return this.wrap(async () => {
      await this.instance.post(
        `/api/v2/${import.meta.env.VITE_API_APP}/secured-variables`,
        {
          secretName,
          variables: Object.fromEntries(variables.map(v => [v.key, v.value])),
        }
      );
      return variables;
    }, "Failed to create secured variables", this.serviceName);
  }

  async update(secretName: string, variables: Variable[]): Promise<ApiResponse<Variable[]>> {
    return this.wrap(async () => {
      await this.instance.patch(
        `/api/v2/${import.meta.env.VITE_API_APP}/secured-variables`,
        {
          secretName,
          variables: Object.fromEntries(variables.map(v => [v.key, v.value])),
        }
      );
      return variables;
    }, "Failed to update secured variables", this.serviceName);
  }

  async delete(secretName: string, keys: string[]): Promise<ApiResponse<boolean>> {
    return this.wrap(async () => {
      const params = new URLSearchParams();
      keys.forEach((key) => params.append("variablesNames", key));
      await this.instance.delete(
        `/api/v2/${import.meta.env.VITE_API_APP}/secured-variables/${secretName}?${params}`
      );
      return true;
    }, "Failed to delete secured variables", this.serviceName);
  }

  async createSecret(secretName: string): Promise<ApiResponse<boolean>> {
    return this.wrap(async () => {
      await this.instance.post(
        `/api/v2/${import.meta.env.VITE_API_APP}/secret/${secretName}`
      );
      return true;
    }, "Failed to create secret", this.serviceName);
  }

  async downloadHelmChart(secretName: string): Promise<void> {
    try {
      const response = await this.instance.get(
        `/api/v2/${import.meta.env.VITE_API_APP}/secret/template/${secretName}`,
        { responseType: "blob" }
      );

      const blob = new Blob([response.data], { type: "application/x-yaml" });
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${secretName}.yaml`;
      document.body.appendChild(link);
      link.click();
      URL.revokeObjectURL(url);
      link.remove();
    } catch (error) {
      console.error("Failed to download Helm chart:", error);
    }
  }
}

export const securedVariablesApi = new SecuredVariablesApi();