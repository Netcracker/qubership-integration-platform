import { commonVariablesApi } from "./commonVariablesApi";
import { securedVariablesApi } from "./securedVariablesAPI.ts";
import { Variable, SecretWithVariables, ApiResponse, IVariablesApi } from "./types.ts";

export class VariablesApi implements IVariablesApi {
  async getCommonVariables(): Promise<ApiResponse<Variable[]>> {
    return commonVariablesApi.getAll();
  }

  async createCommonVariable(
    variable: Variable
  ): Promise<ApiResponse<string[]>> {
    return commonVariablesApi.create(variable);
  }

  async updateCommonVariable(
    variable: Variable
  ): Promise<ApiResponse<Variable>> {
    return commonVariablesApi.update(variable);
  }

  async deleteCommonVariables(keys: string[]): Promise<boolean> {
    return commonVariablesApi.delete(keys);
  }

  async exportVariables(
    keys: string[],
    asArchive: boolean = true
  ): Promise<boolean> {
    return commonVariablesApi.exportVariables(keys, asArchive);
  }

  async importVariables(formData: FormData, isPreview: boolean): Promise<any> {
    return commonVariablesApi.importVariables(formData, isPreview);
  }

  async getSecuredVariables(): Promise<
    ApiResponse<SecretWithVariables[]>
  > {
    return securedVariablesApi.getAll();
  }

  async getSecuredVariablesForSecret(
    secretName: string
  ): Promise<ApiResponse<Variable[]>> {
    return securedVariablesApi.getForSecret(secretName);
  }

  async createSecuredVariables(
    secretName: string,
    variables: Variable[]
  ): Promise<ApiResponse<Variable[]>> {
    return securedVariablesApi.create(secretName, variables);
  }

  async updateSecuredVariables(
    secretName: string,
    variables: Variable[]
  ): Promise<ApiResponse<Variable[]>> {
    return securedVariablesApi.update(secretName, variables);
  }

  async deleteSecuredVariables(
    secretName: string,
    keys: string[]
  ): Promise<ApiResponse<boolean>> {
    return securedVariablesApi.delete(secretName, keys);
  }

  async createSecret(secretName: string): Promise<ApiResponse<boolean>> {
    return securedVariablesApi.createSecret(secretName);
  }

  async downloadHelmChart(
    secretName: string
  ): Promise<void> {
    return securedVariablesApi.downloadHelmChart(secretName);
  }
}

export const variablesApi = new VariablesApi(); 