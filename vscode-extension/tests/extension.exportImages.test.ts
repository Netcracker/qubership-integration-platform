import {
  createVscodeMock,
  stubProjectConfigService,
  buildMockContext,
} from "./helpers/mocks";

const registeredCommands = new Map<string, (...args: unknown[]) => unknown>();

const mockShowErrorMessage = jest.fn();
const mockShowInformationMessage = jest.fn();
const mockShowOpenDialog = jest.fn();
const mockExecuteCommand = jest.fn();
const mockParseFile = jest.fn();
const mockCreateWebviewPanel = jest.fn();
const mockSetPendingExportImagesRequest = jest.fn();
const mockStartExportImagesProgress = jest.fn();
const mockListChainExportTargets = jest.fn();

const mockWebview = {
  options: {} as Record<string, unknown>,
  html: "",
  postMessage: jest.fn(),
  asWebviewUri: jest.fn((uri: unknown) => uri),
  onDidReceiveMessage: jest.fn(() => ({ dispose: jest.fn() })),
};
const mockPanel = {
  webview: mockWebview,
  onDidChangeViewState: jest.fn(() => ({ dispose: jest.fn() })),
  onDidDispose: jest.fn(() => ({ dispose: jest.fn() })),
};

jest.mock(
  "vscode",
  () => {
    const base = createVscodeMock();
    const createUri = (path: string) => ({
      path,
      fsPath: path,
      with: jest.fn().mockReturnThis(),
      toString: jest.fn().mockReturnValue(path),
    });
    return {
      ...base,
      Uri: {
        ...base.Uri,
        file: jest.fn((path: string) => createUri(path)),
        parse: jest.fn((path: string) => createUri(path)),
      },
      window: {
        ...base.window,
        showErrorMessage: mockShowErrorMessage,
        showInformationMessage: mockShowInformationMessage,
        showOpenDialog: mockShowOpenDialog,
        registerCustomEditorProvider: jest.fn(() => ({ dispose: jest.fn() })),
        createWebviewPanel: mockCreateWebviewPanel,
        onDidChangeActiveColorTheme: jest.fn(() => ({ dispose: jest.fn() })),
      },
      commands: {
        registerCommand: jest.fn(
          (id: string, handler: (...args: unknown[]) => unknown) => {
            registeredCommands.set(id, handler);
            return { dispose: jest.fn() };
          },
        ),
        executeCommand: mockExecuteCommand,
      },
      workspace: {
        ...base.workspace,
        fs: {
          ...base.workspace.fs,
          stat: jest.fn().mockResolvedValue({ type: 1 }),
        },
      },
    };
  },
  { virtual: true },
);

jest.mock("../src/web/response/apiRouter", () => ({
  setPendingExportImagesRequest: (...args: unknown[]) =>
    mockSetPendingExportImagesRequest(...args),
  startExportImagesProgress: (...args: unknown[]) =>
    mockStartExportImagesProgress(...args),
  getApiResponse: jest.fn(),
}));

jest.mock("../src/web/response", () => ({
  getApiResponse: jest.fn(),
  listChainExportTargets: (...args: unknown[]) =>
    mockListChainExportTargets(...args),
  schemaToChain: jest.fn(),
  CHAIN_DIFF_PATH: "/chains/diff",
}));

jest.mock("../src/web/response/file", () => ({
  setFileApi: jest.fn(),
  fileApi: {
    parseFile: (...args: unknown[]) => mockParseFile(...args),
  },
}));

jest.mock("../src/web/response/file/fileApiImpl", () => ({
  VSCodeFileApi: jest.fn().mockImplementation(() => ({
    createEmptyChain: jest.fn(),
    createEmptyService: jest.fn(),
    createEmptyContextService: jest.fn(),
  })),
}));

jest.mock("../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn().mockReturnValue({
    chain: ".qip-chain.yaml",
    service: ".qip-service.yaml",
    contextService: ".qip-context-service.yaml",
    mcpService: ".qip-mcp-service.yaml",
    specificationGroup: ".qip-sg.yaml",
    specification: ".qip-spec.yaml",
  }),
  getExtensionsForFile: jest.fn().mockReturnValue({
    chain: ".qip-chain.yaml",
    service: ".qip-service.yaml",
  }),
  setCurrentFileContext: jest.fn(),
  extractFilename: jest.fn(),
  initializeContextFromFile: jest.fn(),
}));

jest.mock("../src/web/qipExplorer", () => ({
  QipExplorerProvider: jest.fn().mockImplementation(() => ({
    refresh: jest.fn(),
    getTreeItem: jest.fn(),
    getChildren: jest.fn(),
  })),
}));

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });

jest.mock("../src/web/services/FileCacheService", () => ({
  FileCacheService: {
    getInstance: jest.fn().mockReturnValue({ invalidateByUri: jest.fn() }),
  },
}));

jest.mock("../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService(),
);

jest.mock("../src/web/services/ConfigApiProvider", () => ({
  ConfigApiProvider: { getInstance: jest.fn().mockReturnValue({}) },
}));

jest.mock("../src/web/response/navigationUtils", () => ({
  getAndClearNavigationStateValue: jest.fn(),
  getNavigationStateValue: jest.fn(),
  initNavigationState: jest.fn(),
  updateNavigationStateValue: jest.fn(),
}));

import { activate } from "../src/web/extension";

function getExportImagesCommand() {
  const command = registeredCommands.get("qip.exportImages");
  if (!command) {
    throw new Error("qip.exportImages command was not registered");
  }
  return command;
}

function getExportImagesFromExplorerCommand() {
  const command = registeredCommands.get("qip.exportImagesFromExplorer");
  if (!command) {
    throw new Error("qip.exportImagesFromExplorer command was not registered");
  }
  return command;
}

describe("extension export image commands", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    registeredCommands.clear();
    mockCreateWebviewPanel.mockReturnValue(mockPanel);
    mockParseFile.mockResolvedValue({ id: "chain-1" });
    mockListChainExportTargets.mockResolvedValue([
      {
        chainId: "chain-1",
        filePath: "file:///workspace/chains/chain-1.chain.qip.yaml",
        outputName: "chain-1",
      },
    ]);
    activate(buildMockContext());
  });

  test("qip.exportImages shows error when outputDir is missing", async () => {
    await getExportImagesCommand()("file:///workspace/chains/chain-1.chain.qip.yaml");

    expect(mockShowErrorMessage).toHaveBeenCalledWith("outputDir is required");
    expect(mockCreateWebviewPanel).not.toHaveBeenCalled();
  });

  test("qip.exportImages shows error when no chain files are provided", async () => {
    await getExportImagesCommand()(undefined, { outputDir: "/tmp/export" });

    expect(mockShowErrorMessage).toHaveBeenCalledWith("No chain files to export");
    expect(mockCreateWebviewPanel).not.toHaveBeenCalled();
  });

  test("qip.exportImages opens export webview for explicit file paths", async () => {
    await getExportImagesCommand()(undefined, {
      outputDir: "/tmp/export",
      filePaths: ["file:///workspace/chains/chain-1.chain.qip.yaml"],
    });

    expect(mockParseFile).toHaveBeenCalled();
    expect(mockSetPendingExportImagesRequest).toHaveBeenCalledWith(
      mockPanel,
      expect.objectContaining({
        exportConfig: expect.objectContaining({
          outputDir: "/tmp/export",
          imageFormat: "png",
        }),
        targets: [
          expect.objectContaining({
            chainId: "chain-1",
            outputName: "chain-1",
          }),
        ],
      }),
    );
    expect(mockStartExportImagesProgress).toHaveBeenCalledWith(mockPanel);
    expect(mockCreateWebviewPanel).toHaveBeenCalledWith(
      "qipWebView",
      "QIP Export Images",
      expect.anything(),
      expect.objectContaining({ enableScripts: true }),
    );
  });

  test("qip.exportImages uses filePath argument when filePaths are omitted", async () => {
    await getExportImagesCommand()(
      "file:///workspace/chains/chain-1.chain.qip.yaml",
      { outputDir: "/tmp/export" },
    );

    expect(mockParseFile).toHaveBeenCalled();
    expect(mockSetPendingExportImagesRequest).toHaveBeenCalled();
  });

  test("qip.exportImages resolves relative file paths", async () => {
    await getExportImagesCommand()(undefined, {
      outputDir: "/tmp/export",
      filePaths: ["/workspace/chains/chain-1.chain.qip.yaml"],
    });

    expect(mockParseFile).toHaveBeenCalled();
    expect(mockSetPendingExportImagesRequest).toHaveBeenCalled();
  });

  test("qip.exportImages accepts Uri filePath argument", async () => {
    const fileUri = {
      toString: () => "file:///workspace/chains/chain-1.chain.qip.yaml",
    };

    await getExportImagesCommand()(fileUri, { outputDir: "/tmp/export" });

    expect(mockParseFile).toHaveBeenCalled();
    expect(mockSetPendingExportImagesRequest).toHaveBeenCalled();
  });

  test("qip.exportImages shows error when file path is empty", async () => {
    await getExportImagesCommand()(undefined, {
      outputDir: "/tmp/export",
      filePaths: [""],
    });

    expect(mockShowErrorMessage).toHaveBeenCalledWith(
      "Failed to export QIP chain images: Error: Export file path is required",
    );
  });

  test("qip.exportImages shows error when chain id cannot be resolved", async () => {
    mockParseFile.mockResolvedValueOnce({ name: "no-id" });

    await getExportImagesCommand()(undefined, {
      outputDir: "/tmp/export",
      filePaths: ["/workspace/chains/chain-1.chain.qip.yaml"],
    });

    expect(mockShowErrorMessage).toHaveBeenCalledWith(
      expect.stringContaining("Unable to determine chain id"),
    );
  });

  test("qip.exportImages shows error when handler throws", async () => {
    mockParseFile.mockRejectedValueOnce(new Error("parse failed"));

    await getExportImagesCommand()(undefined, {
      outputDir: "/tmp/export",
      filePaths: ["file:///workspace/chains/chain-1.chain.qip.yaml"],
    });

    expect(mockShowErrorMessage).toHaveBeenCalledWith(
      "Failed to export QIP chain images: Error: parse failed",
    );
  });

  test("qip.exportImagesFromExplorer returns when folder dialog is cancelled", async () => {
    mockShowOpenDialog.mockResolvedValue(undefined);

    await getExportImagesFromExplorerCommand()();

    expect(mockExecuteCommand).not.toHaveBeenCalled();
  });

  test("qip.exportImagesFromExplorer shows info when workspace has no chains", async () => {
    mockShowOpenDialog.mockResolvedValue([
      { toString: () => "file:///tmp/export" },
    ]);
    mockListChainExportTargets.mockResolvedValueOnce([]);

    await getExportImagesFromExplorerCommand()();

    expect(mockShowInformationMessage).toHaveBeenCalledWith(
      "No chain files were found in the workspace.",
    );
    expect(mockExecuteCommand).not.toHaveBeenCalled();
  });

  test("qip.exportImagesFromExplorer delegates to qip.exportImages with discovered targets", async () => {
    mockShowOpenDialog.mockResolvedValue([
      { toString: () => "file:///tmp/export" },
    ]);

    await getExportImagesFromExplorerCommand()();

    expect(mockExecuteCommand).toHaveBeenCalledWith(
      "qip.exportImages",
      undefined,
      {
        outputDir: "file:///tmp/export",
        filePaths: ["file:///workspace/chains/chain-1.chain.qip.yaml"],
      },
    );
  });

  test("qip.exportImagesFromExplorer shows error when discovery fails", async () => {
    mockShowOpenDialog.mockResolvedValue([
      { toString: () => "file:///tmp/export" },
    ]);
    mockListChainExportTargets.mockRejectedValueOnce(new Error("scan failed"));

    await getExportImagesFromExplorerCommand()();

    expect(mockShowErrorMessage).toHaveBeenCalledWith(
      "QIP export from explorer failed: Error: scan failed",
    );
  });
});
