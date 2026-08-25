import { Uri } from "vscode";
import { ProjectConfig, ProjectConfigService } from "./ProjectConfigService";

// Derived from ProjectConfig so a new extension or schema URL reaches embedders along with the config service.
// Keeping the two shapes in sync by hand is what left `apiGroup` out of this type after the rename.
export interface ExternalConfigData {
  extensions?: Partial<ProjectConfig["extensions"]>;
  schemaUrls?: Partial<ProjectConfig["schemaUrls"]>;
}

export interface ConfigApi {
  loadConfigFromPath(configUri: Uri): Promise<void>;
  registerConfig(appName: string, configData: ExternalConfigData): void;
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

  registerConfig(appName: string, configData: ExternalConfigData): void {
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
