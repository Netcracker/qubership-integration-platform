/**
 * Shared mock factories for vscode extension tests.
 */

export function createMinimalVscodeMock() {
  return { Uri: class Uri {}, __esModule: true };
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
        schemaUrls: { service: "", specification: "", specificationGroup: "" },
        extensions: {
          service: ".qip-service.yaml",
          specification: ".spec.yaml",
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
