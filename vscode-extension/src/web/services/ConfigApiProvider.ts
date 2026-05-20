import { Uri } from "vscode";
import { ProjectConfig, ProjectConfigService } from "./ProjectConfigService";

export interface ConfigApi {
  loadConfigFromPath(configUri: Uri): Promise<void>;
  registerConfig(
    appName: string,
    configData: {
      extensions?: {
        chain?: string;
        service?: string;
        specificationGroup?: string;
        specification?: string;
      };
      schemaUrls?: {
        service?: string;
        chain?: string;
        specification?: string;
        specificationGroup?: string;
      };
    },
  ): void;
  unregisterConfig(appName: string): void;
  getConfig(appName: string): ProjectConfig | undefined;
}

export class ConfigApiProvider {
  private static instance: ConfigApiProvider;

  private constructor() {}

  static getInstance(): ConfigApi {
    if (!ConfigApiProvider.instance) {
      ConfigApiProvider.instance = new ConfigApiProvider();
    }
    return ConfigApiProvider.instance;
  }

  async loadConfigFromPath(configUri: Uri): Promise<void> {
    const service = ProjectConfigService.getInstance();
    await service.loadConfigFromUri(configUri);
  }

  registerConfig(
    appName: string,
    configData: {
      extensions?: {
        chain?: string;
        service?: string;
        specificationGroup?: string;
        specification?: string;
      };
      schemaUrls?: {
        service?: string;
        chain?: string;
        specification?: string;
        specificationGroup?: string;
      };
    },
  ): void {
    const service = ProjectConfigService.getInstance();
    service.registerExternalConfig(appName, configData);
  }

  unregisterConfig(appName: string): void {
    const service = ProjectConfigService.getInstance();
    service.unregisterExternalConfig(appName);
  }

  getConfig(appName: string): ProjectConfig | undefined {
    const service = ProjectConfigService.getInstance();
    return service.getConfigByAppName(appName);
  }
}
