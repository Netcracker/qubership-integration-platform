/**
 * Shared mock factories for vscode extension tests.
 */

export function createMinimalVscodeMock() {
  return { Uri: class Uri {}, __esModule: true };
}

/** Joins uri segments and resolves `..`, the way `vscode.Uri.joinPath` does. */
export function joinUriPath(base: any, ...segments: string[]) {
  const parts = [
    ...String(base?.path ?? "").split("/"),
    ...segments.flatMap((segment) => segment.split("/")),
  ];
  const resolved: string[] = [];
  for (const part of parts) {
    if (part === "..") {
      resolved.pop();
    } else if (part !== ".") {
      resolved.push(part);
    }
  }
  const path = resolved.join("/");
  return { path, fsPath: path };
}

export function createVscodeMock(overrides: Record<string, any> = {}) {
  return {
    Uri: {
      joinPath: jest.fn((_base: any, ...segments: string[]) => ({
        path: segments.join("/"),
        fsPath: segments.join("/"),
        with: jest.fn().mockReturnThis(),
      })),
      parse: jest.fn((s: string) => ({ path: s, fsPath: s })),
    },
    window: {
      showInformationMessage: jest.fn(),
      showErrorMessage: jest.fn(),
      showWarningMessage: jest.fn(),
      showInputBox: jest.fn(),
      showQuickPick: jest.fn(),
      registerCustomEditorProvider: jest.fn(),
      registerTreeDataProvider: jest.fn(() => ({ dispose: jest.fn() })),
      createWebviewPanel: jest.fn(),
      activeColorTheme: { kind: 2, label: "Dark+" },
      onDidChangeActiveColorTheme: jest.fn(() => ({ dispose: jest.fn() })),
    },
    workspace: {
      getConfiguration: jest.fn().mockReturnValue({
        get: jest.fn((_key: string, defaultVal: any) => defaultVal),
        inspect: jest.fn(() => undefined),
        update: jest.fn().mockResolvedValue(undefined),
      }),
      workspaceFolders: [{ uri: { path: "/workspace", fsPath: "/workspace" } }],
      createFileSystemWatcher: jest.fn(() => ({
        onDidChange: jest.fn(),
        onDidDelete: jest.fn(),
        onDidCreate: jest.fn(),
        dispose: jest.fn(),
      })),
      onDidChangeConfiguration: jest.fn(() => ({ dispose: jest.fn() })),
      fs: {
        stat: jest.fn(),
        readDirectory: jest.fn().mockResolvedValue([]),
        delete: jest.fn(),
      },
      openTextDocument: jest.fn(),
    },
    commands: {
      registerCommand: jest.fn(() => ({ dispose: jest.fn() })),
      executeCommand: jest.fn(),
    },
    ViewColumn: { One: 1 },
    ConfigurationTarget: { Global: 1, Workspace: 2, WorkspaceFolder: 3 },
    ColorThemeKind: {
      Light: 1,
      Dark: 2,
      HighContrast: 3,
      HighContrastLight: 4,
    },
    FileType: { File: 1, Directory: 2 },
    version: "1.90.0",
    ...overrides,
  };
}

export function stubFileApi(extra: Record<string, any> = {}) {
  return {
    fileApi: {
      writeFile: jest.fn(),
      writeMainService: jest.fn(),
      writeServiceFile: jest.fn(),
      getContextService: jest.fn(),
      getSpecificationGroupFiles: jest.fn(),
      getSpecificationFiles: jest.fn(),
      deleteFile: jest.fn(),
      getFileType: jest.fn(),
      fileExists: jest.fn().mockResolvedValue(true),
      parseFile: jest.fn(),
      readFileContent: jest.fn(),
      getSpecApiFiles: jest.fn(),
      ...extra,
    },
  };
}

export function stubLabelUtils() {
  return {
    LabelUtils: {
      fromEntityLabels: jest.fn().mockReturnValue([]),
      toEntityLabels: jest.fn().mockReturnValue([]),
    },
  };
}

export function stubProjectConfigService(
  configOverrides: Record<string, any> = {},
) {
  return {
    ProjectConfigService: {
      getConfig: jest.fn().mockReturnValue({
        schemaUrls: {
          ...QIP_SCHEMA_URLS,
          service: "",
          specification: "",
          specificationGroup:
            "http://qubership.org/schemas/product/qip/specification-group.schema.yaml",
          apiGroup:
            "http://qubership.org/schemas/product/qip/api-group.schema.yaml",
          api: "http://qubership.org/schemas/product/qip/api.schema.yaml",
        },
        extensions: {
          service: ".qip-service.yaml",
          specification: ".spec.yaml",
          specificationGroup: ".specification-group.qip.yaml",
          apiGroup: ".api-group.qip.yaml",
          api: ".api.qip.yaml",
        },
        ...configOverrides,
      }),
      getInstance: jest.fn().mockReturnValue({
        setContext: jest.fn(),
        loadWorkspaceConfig: jest.fn().mockResolvedValue(undefined),
        getAllConfigs: jest.fn().mockReturnValue([]),
        buildDefaultConfig: jest.fn().mockReturnValue({
          extensions: {
            chain: ".qip-chain.yaml",
            service: ".qip-service.yaml",
          },
        }),
      }),
    },
    CONFIG_FILENAME: "qip-config.yaml",
  };
}

import type { IntegrationSystem } from "../../src/web/api-services/servicesTypes";
import { IntegrationSystemType } from "../../src/web/api-services/servicesTypes";

export function buildSystem(
  overrides: Partial<IntegrationSystem> = {},
): IntegrationSystem {
  return {
    id: "sys-1",
    name: "Test System",
    activeEnvironmentId: "",
    integrationSystemType: IntegrationSystemType.EXTERNAL,
    protocol: "HTTP",
    extendedProtocol: "",
    specification: "",
    labels: [],
    ...overrides,
  };
}

export function buildServiceRecord(
  id: string,
  contentOverrides: Record<string, any> = {},
) {
  return {
    id,
    content: { protocol: "HTTP", ...contentOverrides },
  };
}

export function buildSerializedOpenApiFile(name = "spec.json") {
  const content = JSON.stringify({
    openapi: "3.0.0",
    info: { title: "Test", version: "1.0" },
    paths: {},
  });
  return {
    name,
    size: content.length,
    type: "application/json",
    lastModified: Date.now(),
    content: new TextEncoder().encode(content).buffer,
  };
}

export function buildMockContext() {
  return {
    extensionUri: { path: "/ext", fsPath: "/ext" },
    extension: { packageJSON: { version: "1.0.0" } },
    subscriptions: [],
  } as any;
}

/** The default `qip` service schema URLs, one per service extension key. */
export const QIP_SCHEMA_URLS = {
  service: "http://qubership.org/schemas/product/qip/service.schema.yaml",
  externalService:
    "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
  internalService:
    "http://qubership.org/schemas/product/qip/internal-service.schema.yaml",
  implementedService:
    "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml",
  contextService:
    "http://qubership.org/schemas/product/qip/context-service.schema.yaml",
  mcpService:
    "http://qubership.org/schemas/product/qip/mcp-service.schema.yaml",
};

/** The default `qip` file extensions, as `getExtensionsForUri` returns them. */
export const QIP_FILE_EXTENSIONS = {
  appName: "qip",
  chain: ".chain.qip.yaml",
  service: ".service.qip.yaml",
  externalService: ".external-service.qip.yaml",
  internalService: ".internal-service.qip.yaml",
  implementedService: ".implemented-service.qip.yaml",
  contextService: ".context-service.qip.yaml",
  mcpService: ".mcp-service.qip.yaml",
  specificationGroup: ".specification-group.qip.yaml",
  apiGroup: ".api-group.qip.yaml",
  specification: ".specification.qip.yaml",
  api: ".api.qip.yaml",
};
