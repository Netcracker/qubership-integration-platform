import {
  DEFAULT_SCHEMA_URLS,
  ProjectConfig,
  ProjectConfigService,
} from "../../services/ProjectConfigService";
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
    // The name every plain-service write emits. The three below are the #553 per-type names: read,
    // never written.
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

/**
 * The loaded project config of the app a file belongs to, or `undefined` when no workspace config
 * answers for it. The one lookup: `extensions` and `schemaUrls` are two members of one config, and
 * resolving them separately let the two answers come from different apps.
 */
function configForApp(appName: string): ProjectConfig | undefined {
  try {
    const configService = ProjectConfigService.getInstance?.();
    if (configService?.isConfigLoaded()) {
      return configService.getConfigByAppName(appName);
    }
  } catch (error) {
    console.error(`Failed to read the config of app ${appName}:`, error);
  }
  return undefined;
}

export function getExtensionsForFile(filename?: string): FileExtensionsConfig {
  const contextFile = filename || currentFileContext;
  if (contextFile) {
    const appName = extractAppNameFromExtension(contextFile);
    const config = configForApp(appName);
    // Spread rather than key by key: `ProjectConfig["extensions"]` is this type minus `appName`,
    // so a new extension key cannot be forgotten in one of the three mappings.
    return config
      ? { appName: config.appName, ...config.extensions }
      : buildDefaultExtensions(appName);
  }
  return getDefaultExtensions();
}

/**
 * The `schemaUrls` of the app a file belongs to, resolved exactly as its extensions are — a
 * document has to be stamped with the schema of its own project, not of whichever app was opened
 * last.
 */
export function getSchemaUrlsForFile(
  filename?: string,
): ProjectConfig["schemaUrls"] {
  const contextFile = filename || currentFileContext;
  return getSchemaUrlsForApp(
    contextFile ? extractAppNameFromExtension(contextFile) : defaultAppName,
  );
}

/**
 * The same, for a caller that already resolved the app name. An app no loaded config answers for
 * falls back to the shipped defaults, the way `buildDefaultExtensions` does: the current app's
 * config carries another project's rehosted urls, and stamping one of those is how a file of one
 * installation ends up pointing at another.
 */
export function getSchemaUrlsForApp(
  appName: string,
): ProjectConfig["schemaUrls"] {
  return configForApp(appName)?.schemaUrls ?? DEFAULT_SCHEMA_URLS;
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
