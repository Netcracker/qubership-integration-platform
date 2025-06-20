import { BaseApi } from "./baseApi.ts";
import {
  Variable,
  SecretWithVariables,
  ApiResponse,
  SecretResponse,
} from "./types.ts";
import { getFileFromResponse } from "../../../misc/download-utils.ts";

const urlPrefixV2 = `/api/v2/${import.meta.env.VITE_API_APP}/variables-management`;

export class SecuredVariablesApi extends BaseApi {
  private serviceName = "Secured Variables API";

  async getAll(): Promise<ApiResponse<SecretWithVariables[]>> {
    return this.wrap(
      async () => {
        const response = await this.instance.get<SecretResponse[]>(
          `${urlPrefixV2}/secured-variables`,
        );

        return response.data.map(
          ({ secretName, variablesNames, defaultSecret }) => ({
            secretName,
            variables: variablesNames.map((key: string) => ({
              key,
              value: "******",
            })),
            isDefaultSecret: defaultSecret,
          }),
        );
      },
      "Failed to fetch secured variables",
      this.serviceName,
    );
  }

  async getForSecret(secretName: string): Promise<ApiResponse<Variable[]>> {
    return this.wrap(
      async () => {
        const response = await this.instance.get(
          `${urlPrefixV2}/secured-variables/${secretName}`,
        );

        return response.data.map((key: string) => ({
          key,
          value: "******",
        }));
      },
      "Failed to fetch variables for secret",
      this.serviceName,
    );
  }

  async create(
    secretName: string,
    variables: Variable[],
  ): Promise<ApiResponse<Variable[]>> {
    const keyRegex = /^[a-zA-Z0-9_-]+$/;
    for (const v of variables) {
      if (!keyRegex.test(v.key)) {
        return this.fail(this.serviceName, `Invalid key: ${v.key}`);
      }
    }

    return this.wrap(
      async () => {
        await this.instance.post(`${urlPrefixV2}/secured-variables`, {
          secretName,
          variables: Object.fromEntries(variables.map((v) => [v.key, v.value])),
        });
        return variables;
      },
      "Failed to create secured variables",
      this.serviceName,
    );
  }

  async update(
    secretName: string,
    variables: Variable[],
  ): Promise<ApiResponse<Variable[]>> {
    return this.wrap(
      async () => {
        await this.instance.patch(`${urlPrefixV2}/secured-variables`, {
          secretName,
          variables: Object.fromEntries(variables.map((v) => [v.key, v.value])),
        });
        return variables;
      },
      "Failed to update secured variables",
      this.serviceName,
    );
  }

  async delete(
    secretName: string,
    keys: string[],
  ): Promise<ApiResponse<boolean>> {
    return this.wrap(
      async () => {
        const params = new URLSearchParams();
        keys.forEach((key) => params.append("variablesNames", key));
        await this.instance.delete(
          `${urlPrefixV2}/secured-variables/${secretName}?${params}`,
        );
        return true;
      },
      "Failed to delete secured variables",
      this.serviceName,
    );
  }

  async createSecret(secretName: string): Promise<ApiResponse<boolean>> {
    return this.wrap(
      async () => {
        await this.instance.post(`${urlPrefixV2}/secret/${secretName}`);
        return true;
      },
      "Failed to create secret",
      this.serviceName,
    );
  }

  async downloadHelmChart(secretName: string): Promise<File> {
    const response = await this.instance.get<Blob>(
      `${urlPrefixV2}/secret/template/${secretName}`,
      { responseType: "blob" },
    );
    return getFileFromResponse(response);
  }
}

export const securedVariablesApi = new SecuredVariablesApi();
