import { ProjectConfigService } from "../../services/ProjectConfigService";
import { Uri } from "vscode";
import * as vscode from "vscode";

export type FileExtensionsConfig = {
  appName: string;
  chain: string;
  service: string;
  externalService: string;
  internalService: string;
  implementedService: string;
  contextService: string;
  mcpService: string;
  specificationGroup: string;
  apiGroup: string;
  specification: string;
  api: string;
};

export function buildDefaultExtensions(appName: string): FileExtensionsConfig {
  return {
    appName,
    chain: `.chain.${appName}.yaml`,
    // The type-less `.service.` name is legacy: read, never written. The three below state the type.
    service: `.service.${appName}.yaml`,
    externalService: `.external-service.${appName}.yaml`,
    internalService: `.internal-service.${appName}.yaml`,
    implementedService: `.implemented-service.${appName}.yaml`,
    contextService: `.context-service.${appName}.yaml`,
    mcpService: `.mcp-service.${appName}.yaml`,
    specificationGroup: `.specification-group.${appName}.yaml`,
    apiGroup: `.api-group.${appName}.yaml`,
    specification: `.specification.${appName}.yaml`,
    api: `.api.${appName}.yaml`,
  };
}

let defaultAppName = "qip";
let memoizedDefaultExtensions: FileExtensionsConfig | null = null;

export function setDefaultAppName(appName: string) {
  defaultAppName = appName;
  memoizedDefaultExtensions = null;
}

export function getDefaultAppName(): string {
  return defaultAppName;
}

export function getDefaultExtensions(): FileExtensionsConfig {
  if (!memoizedDefaultExtensions) {
    memoizedDefaultExtensions = buildDefaultExtensions(defaultAppName);
  }
  return memoizedDefaultExtensions;
}

let currentFileContext: string | null = null;

export function setCurrentFileContext(filename: string | null) {
  currentFileContext = filename;
}

export function getCurrentFileContext(): string | null {
  return currentFileContext;
}

export function extractAppNameFromExtension(filename: string): string {
  const workspaceUri = vscode.workspace.workspaceFolders?.[0]?.uri;

  // Factored alternation, same matches as spelling every type out: the `-service` prefix is
  // optional, and `-group` is optional on both specification and api. `mcp-service` is absent
  // by design — it has never matched here and resolves through the config path instead.
  const filenamePattern =
    /\.((?:context-|external-|internal-|implemented-)?service\d*|chain\d*|(?:specification|api)(?:-group)?\d*)\.([^.]+)\.yaml$/;

  if (workspaceUri) {
    try {
      const configService = ProjectConfigService.getInstance();

      if (!configService.isConfigLoaded()) {
        const match = filename.match(filenamePattern);
        return match ? match[2] : defaultAppName;
      }

      const allConfigs = configService.getAllConfigs();

      for (const config of allConfigs) {
        for (const extension of Object.values(config.extensions)) {
          if (filename.endsWith(extension)) {
            return config.appName;
          }
        }
      }
    } catch (error) {}
  }

  const match = filename.match(filenamePattern);
  return match ? match[2] : defaultAppName;
}

export function getExtensionsForFile(filename?: string): FileExtensionsConfig {
  const contextFile = filename || currentFileContext;
  if (contextFile) {
    const appName = extractAppNameFromExtension(contextFile);

    try {
      const configService = ProjectConfigService.getInstance();

      if (configService.isConfigLoaded()) {
        const allConfigs = configService.getAllConfigs();

        const foundConfig = allConfigs.find((cfg) => cfg.appName === appName);
        if (foundConfig) {
          // Spread rather than key by key: `ProjectConfig["extensions"]` is this type minus
          // `appName`, so a new extension key cannot be forgotten in one of the three mappings.
          return { appName: foundConfig.appName, ...foundConfig.extensions };
        }
      }
    } catch (error) {}

    return buildDefaultExtensions(appName);
  }
  return getDefaultExtensions();
}

export function extractFilename(fileUri: { path: string } | string): string {
  if (typeof fileUri === "string") {
    return fileUri.split("/").pop() || "";
  }
  return fileUri.path.split("/").pop() || "";
}

export function getExtensionsForUri(fileUri?: {
  path: string;
}): FileExtensionsConfig {
  if (fileUri) {
    const filename = extractFilename(fileUri);
    return getExtensionsForFile(filename);
  }
  return getExtensionsForFile();
}

export async function initializeContextFromFile(fileUri: Uri): Promise<void> {
  const filename = extractFilename(fileUri);
  const appName = extractAppNameFromExtension(filename);

  const configService = ProjectConfigService.getInstance();
  const workspaceUri = vscode.workspace.workspaceFolders?.[0]?.uri;

  await configService.setCurrentContext(appName, workspaceUri);
  setCurrentFileContext(filename);

  const config = configService.getCurrentConfig();
  memoizedDefaultExtensions = { appName: config.appName, ...config.extensions };
}

export function getExtensionsFromConfig(): FileExtensionsConfig {
  const config = ProjectConfigService.getConfig();

  return { appName: config.appName, ...config.extensions };
}
