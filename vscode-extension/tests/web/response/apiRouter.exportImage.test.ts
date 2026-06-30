import { Uri, WebviewPanel } from "vscode";
import {
  getApiResponse,
  setPendingExportImagesRequest,
  startExportImagesProgress,
} from "../../../src/web/response/apiRouter";
import { fileApi } from "../../../src/web/response/file";

const mockShowWarningMessage = jest.fn();
const mockShowErrorMessage = jest.fn();
const mockProgressReport = jest.fn();
const mockWorkspaceFolders = [{ uri: { path: "/workspace", fsPath: "/workspace" } }];

jest.mock("vscode", () => {
  const uriModule = jest.requireActual("../../__mocks__/vscode");
  return {
    __esModule: true,
    default: {
      window: {
        showWarningMessage: (...args: unknown[]) =>
          mockShowWarningMessage(...args),
        showErrorMessage: (...args: unknown[]) => mockShowErrorMessage(...args),
        withProgress: (
          _options: unknown,
          task: (progress: { report: typeof mockProgressReport }) => unknown,
        ) => task({ report: mockProgressReport }),
      },
      workspace: {
        get workspaceFolders() {
          return mockWorkspaceFolders;
        },
      },
      ProgressLocation: { Notification: 1 },
    },
    Uri: uriModule.Uri,
    ProgressLocation: { Notification: 1 },
  };
});

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    writeFile: jest.fn(),
    getRootDirectory: jest.fn(() => Uri.file("/workspace")),
    findFiles: jest.fn(),
    parseFile: jest.fn(),
  },
}));

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn(() => ({ chain: "**/*.chain.qip.yaml" })),
}));

const writeFile = fileApi.writeFile as jest.Mock;
const findFiles = fileApi.findFiles as jest.Mock;
const parseFile = fileApi.parseFile as jest.Mock;

function createPanel(): WebviewPanel {
  return {
    onDidDispose: jest.fn(() => ({ dispose: jest.fn() })),
  } as unknown as WebviewPanel;
}

describe("apiRouter export image handlers", () => {
  const panel = createPanel();
  const workspaceUri = Uri.file("/workspace");

  const startupPayload = {
    exportConfig: {
      outputDir: "/tmp/export",
      imageFormat: "png" as const,
      targets: [{ chainId: "chain-1", outputName: "chain-1" }],
    },
    targets: [{ chainId: "chain-1", outputName: "chain-1" }],
  };

  beforeEach(() => {
    jest.clearAllMocks();
    writeFile.mockReset();
    findFiles.mockReset();
    parseFile.mockReset();
    mockWorkspaceFolders.length = 0;
    mockWorkspaceFolders.push({
      uri: { path: "/workspace", fsPath: "/workspace" },
    });
  });

  test("startup returns pending exportImages payload once", async () => {
    setPendingExportImagesRequest(panel, startupPayload);

    const first = await getApiResponse(
      { requestId: "1", type: "startup" },
      workspaceUri,
      undefined,
      panel,
    );
    const second = await getApiResponse(
      { requestId: "2", type: "startup" },
      workspaceUri,
      undefined,
      panel,
    );

    expect(first).toEqual({
      appName: "qip",
      operation: "exportImages",
      exportImages: startupPayload,
    });
    expect(second).toEqual({ appName: "qip" });
  });

  test("exportImagesProgress reports progress when export is active", async () => {
    startExportImagesProgress(panel);

    await getApiResponse(
      {
        requestId: "1",
        type: "exportImagesProgress",
        payload: { current: 1, total: 2, fileName: "chain-1.png" },
      },
      workspaceUri,
      undefined,
      panel,
    );

    expect(mockProgressReport).toHaveBeenCalledWith({
      message: "Exporting chain-1.png (1/2)",
      increment: 50,
    });
  });

  test("exportImagesProgress is ignored when export progress is not active", async () => {
    await getApiResponse(
      {
        requestId: "1",
        type: "exportImagesProgress",
        payload: { current: 1, total: 2, fileName: "chain-1.png" },
      },
      workspaceUri,
    );

    expect(mockProgressReport).not.toHaveBeenCalled();
  });

  test("startExportImagesProgress cleans up when panel is disposed", async () => {
    let disposeCallback: (() => void) | undefined;
    const disposablePanel = {
      onDidDispose: jest.fn((callback: () => void) => {
        disposeCallback = callback;
        return { dispose: jest.fn() };
      }),
    } as unknown as WebviewPanel;

    startExportImagesProgress(disposablePanel);
    disposeCallback?.();

    await getApiResponse(
      {
        requestId: "1",
        type: "exportImagesProgress",
        payload: { current: 1, total: 2, fileName: "chain-1.png" },
      },
      workspaceUri,
      undefined,
      disposablePanel,
    );

    expect(mockProgressReport).not.toHaveBeenCalled();
  });

  test("exportImagesComplete resolves active export progress", async () => {
    startExportImagesProgress(panel);

    await getApiResponse(
      { requestId: "1", type: "exportImagesComplete" },
      workspaceUri,
      undefined,
      panel,
    );

    await getApiResponse(
      { requestId: "2", type: "exportImagesProgress", payload: { total: 2 } },
      workspaceUri,
      undefined,
      panel,
    );

    expect(mockProgressReport).not.toHaveBeenCalled();
  });

  test("exportImagesDone resolves active export progress", async () => {
    startExportImagesProgress(panel);

    await getApiResponse(
      { requestId: "1", type: "exportImagesDone" },
      workspaceUri,
      undefined,
      panel,
    );

    expect(mockProgressReport).not.toHaveBeenCalled();
  });

  test.each([
    ["string payload", "layout timeout", "layout timeout"],
    [
      "message payload",
      { message: "Chain is empty" },
      "Chain is empty",
    ],
    [
      "target payload",
      { target: { outputName: "alpha", chainId: "id-1" } },
      'Chain "alpha" export warning',
    ],
    ["fallback payload", {}, "Chain graph export warning"],
  ])(
    "exportImagesItemWarning shows warning for %s",
    async (_label, payload, expected) => {
      await getApiResponse(
        { requestId: "1", type: "exportImagesItemWarning", payload },
        workspaceUri,
        undefined,
        panel,
      );

      expect(mockShowWarningMessage).toHaveBeenCalledWith(expected);
    },
  );

  test.each([
    [
      "chain and error",
      { target: { outputName: "alpha" }, error: "capture failed" },
      'Chain "alpha": capture failed',
    ],
    ["error only", { error: "disk full" }, "disk full"],
    [
      "chain only",
      { target: { chainId: "id-1" } },
      'Chain "id-1" export failed',
    ],
    ["fallback", {}, "Chain graph export failed"],
  ])(
    "exportImagesItemFailed shows error for %s",
    async (_label, payload, expected) => {
      await getApiResponse(
        { requestId: "1", type: "exportImagesItemFailed", payload },
        workspaceUri,
        undefined,
        panel,
      );

      expect(mockShowErrorMessage).toHaveBeenCalledWith(expected);
    },
  );

  test("exportImagesProgress reports increment without file name", async () => {
    startExportImagesProgress(panel);

    await getApiResponse(
      {
        requestId: "1",
        type: "exportImagesProgress",
        payload: { current: 2, total: 4 },
      },
      workspaceUri,
      undefined,
      panel,
    );

    expect(mockProgressReport).toHaveBeenCalledWith({
      message: undefined,
      increment: 25,
    });
  });

  test("throws when workspace folder and opened document are missing", async () => {
    mockWorkspaceFolders.length = 0;

    await expect(
      getApiResponse(
        {
          requestId: "1",
          type: "listChainExportTargets",
        },
        undefined,
      ),
    ).rejects.toThrow("No workspace folder or opened document found");
  });

  test("saveExportedImage writes decoded png bytes through fileApi", async () => {
    await getApiResponse(
      {
        requestId: "1",
        type: "saveExportedImage",
        payload: {
          outputDir: "exports",
          fileName: "chain-1.png",
          contentBase64: "SGVsbG8=",
        },
      },
      workspaceUri,
      undefined,
      panel,
    );

    expect(writeFile).toHaveBeenCalledTimes(1);
    const [, bytes] = writeFile.mock.calls[0] as [Uri, Uint8Array];
    expect(Array.from(bytes)).toEqual([72, 101, 108, 108, 111]);
  });

  test("saveExportedImage keeps svg extension in output file name", async () => {
    await getApiResponse(
      {
        requestId: "1",
        type: "saveExportedImage",
        payload: {
          outputDir: "/tmp/export",
          fileName: "chain-1.svg",
          contentBase64: "SGVsbG8=",
        },
      },
      workspaceUri,
      undefined,
      panel,
    );

    expect(writeFile).toHaveBeenCalledTimes(1);
  });

  test("listChainExportTargets returns sorted targets from fileApi discovery", async () => {
    const first = Uri.file("/workspace/chains/b.chain.qip.yaml");
    const second = Uri.file("/workspace/chains/a.chain.qip.yaml");
    findFiles.mockResolvedValue([first, second]);
    parseFile.mockImplementation(async (uri: Uri) => ({
      id: uri.fsPath.includes("/a.") ? "alpha" : "beta",
    }));

    const targets = await getApiResponse(
      { requestId: "1", type: "listChainExportTargets" },
      workspaceUri,
    );

    expect(targets).toEqual([
      {
        chainId: "alpha",
        filePath: second.toString(),
        outputName: "alpha",
      },
      {
        chainId: "beta",
        filePath: first.toString(),
        outputName: "beta",
      },
    ]);
  });
});
