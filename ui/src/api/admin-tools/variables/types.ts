export interface ApiError {
  responseBody: {
    serviceName: string;
    errorMessage: string;
    errorDate: string;
    stacktrace?: string;
  }
}

export interface ApiResponse<T> {
  success: boolean;
  error?: ApiError;
  data?: T;
}

export type Variable = {
  key: string;
  value: string;
}

export type SecretWithVariables = {
  secretName: string;
  variables: Variable[];
  isDefaultSecret: boolean;
};

export interface IVariablesApi {
  getCommonVariables(): Promise<ApiResponse<Variable[]>>;
  createCommonVariable(variable: Variable): Promise<ApiResponse<string[]>>;
  updateCommonVariable(variable: Variable): Promise<ApiResponse<Variable>>;
  deleteCommonVariables(keys: string[]): Promise<boolean>;
  exportVariables(keys: string[], asArchive?: boolean): Promise<File>;
  importVariables(formData: FormData, isPreview: boolean): Promise<any>;
  getSecuredVariables(): Promise<ApiResponse<SecretWithVariables[]>>;
  getSecuredVariablesForSecret(secretName: string): Promise<ApiResponse<Variable[]>>;
  createSecuredVariables(secretName: string, variables: Variable[]): Promise<ApiResponse<Variable[]>>;
  updateSecuredVariables(secretName: string, variables: Variable[]): Promise<ApiResponse<Variable[]>>;
  deleteSecuredVariables(secretName: string, keys: string[]): Promise<ApiResponse<boolean>>;
  createSecret(secretName: string): Promise<ApiResponse<boolean>>;
  downloadHelmChart(secretName: string): Promise<File>;
}
