import {
  createVscodeMock,
  stubProjectConfigService,
  buildMockContext,
} from "./helpers/mocks";

let capturedEditorProviders: Record<string, any> = {};
let onDidReceiveMessageCallback: Function | null = null;
let mockStatBehavior: "resolve" | "reject" = "resolve";

const mockPostMessage = jest.fn();
const mockWebview = {
  options: {} as any,
  html: "",
  postMessage: mockPostMessage,
  asWebviewUri: jest.fn((uri: any) => uri),
  onDidReceiveMessage: jest.fn((cb: Function) => {
    onDidReceiveMessageCallback = cb;
    return { dispose: jest.fn() };
  }),
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
    return {
      ...base,
      window: {
        ...base.window,
        registerCustomEditorProvider: jest.fn((id: string, provider: any) => {
          capturedEditorProviders[id] = provider;
          return { dispose: jest.fn() };
        }),
        createWebviewPanel: jest.fn(() => mockPanel),
      },
      commands: {
        registerCommand: jest.fn(() => ({ dispose: jest.fn() })),
        executeCommand: jest.fn(),
      },
      workspace: {
        ...base.workspace,
        fs: {
          ...base.workspace.fs,
          stat: jest.fn(() =>
            mockStatBehavior === "resolve"
              ? Promise.resolve({ type: 1 })
              : Promise.reject(new Error("File not found")),
          ),
        },
      },
    };
  },
  { virtual: true },
);

const mockGetApiResponse = jest.fn();

jest.mock("../src/web/response", () => ({
  getApiResponse: mockGetApiResponse,
}));
jest.mock("../src/web/response/file", () => ({ setFileApi: jest.fn() }));
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

function activateAndGetProvider() {
  activate(buildMockContext());
  return capturedEditorProviders["qip.chainFile.editor"];
}

const validDocument = { uri: { path: "/test.yaml", fsPath: "/test.yaml" } };

async function openEditorAndGetMessageHandler() {
  const provider = activateAndGetProvider();
  await provider.resolveCustomTextEditor(
    validDocument as any,
    mockPanel as any,
    {} as any,
  );
  return onDidReceiveMessageCallback!;
}

describe("extension.ts", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    capturedEditorProviders = {};
    onDidReceiveMessageCallback = null;
    mockStatBehavior = "resolve";
    mockWebview.html = "";
  });

  describe("resolveCustomTextEditor - rejects invalid parameters", () => {
    let provider: any;
    beforeEach(() => {
      provider = activateAndGetProvider();
    });

    test("throws when document.uri is falsy", async () => {
      await expect(
        provider.resolveCustomTextEditor(
          { uri: null } as any,
          mockPanel as any,
          {} as any,
        ),
      ).rejects.toThrow("Invalid parameters for resolveCustomTextEditor");
    });

    test("throws when panel is null", async () => {
      await expect(
        provider.resolveCustomTextEditor(
          validDocument as any,
          null as any,
          {} as any,
        ),
      ).rejects.toThrow("Invalid parameters for resolveCustomTextEditor");
    });
  });

  describe("enrichWebview - onDidReceiveMessage error handling", () => {
    let handleMessage: Function;

    beforeEach(async () => {
      handleMessage = await openEditorAndGetMessageHandler();
    });

    test("populates response.error when getApiResponse throws an Error", async () => {
      const testError = new Error("Something went wrong");
      testError.name = "TestError";
      mockGetApiResponse.mockRejectedValue(testError);

      await handleMessage({
        command: "apiCall",
        data: { requestId: "req-1", type: "getChain", payload: {} },
      });

      expect(mockPostMessage).toHaveBeenCalledWith(
        expect.objectContaining({
          requestId: "req-1",
          type: "getChain",
          error: { message: "Something went wrong", name: "TestError" },
        }),
      );
    });

    test("does not set response.error for non-Error throws", async () => {
      mockGetApiResponse.mockRejectedValue("string error");

      await handleMessage({
        command: "apiCall",
        data: { requestId: "req-2", type: "getChain", payload: {} },
      });

      const calls = mockPostMessage.mock.calls;
      const posted = calls[calls.length - 1][0];
      expect(posted.requestId).toBe("req-2");
      expect(posted.error).toBeUndefined();
    });
  });

  describe("getWebviewContent - importMapScript", () => {
    test("omits importmap when bundled JS file exists", async () => {
      mockStatBehavior = "resolve";
      const provider = activateAndGetProvider();

      await provider.resolveCustomTextEditor(
        validDocument as any,
        mockPanel as any,
        {} as any,
      );

      expect(mockWebview.html).toContain("<!DOCTYPE html>");
      expect(mockWebview.html).not.toContain("importmap");
    });

    test("includes React importmap when bundled JS file is missing", async () => {
      mockStatBehavior = "reject";
      const provider = activateAndGetProvider();

      await provider.resolveCustomTextEditor(
        validDocument as any,
        mockPanel as any,
        {} as any,
      );

      expect(mockWebview.html).toContain("importmap");
      expect(mockWebview.html).toContain("esm.sh/react@18.3.1");
    });
  });

  describe("useDefaultDiffView toggle", () => {
    const DIFF_ASSOCIATIONS_SETTING = "workbench.diffEditorAssociations";
    const CHAIN_GLOB = "*.chain.qip.yaml";

    const flushAsync = () => new Promise((resolve) => setImmediate(resolve));

    /**
     * Points the vscode configuration mock at mutable state so tests can flip
     * the toggle between activation and a configuration-change event.
     */
    function configureDiffToggleMocks(state: {
      useDefaultDiffView: boolean;
      globalAssociations?: Record<string, string>;
    }) {
      const vscode = require("vscode");
      const update = jest.fn().mockResolvedValue(undefined);
      vscode.workspace.getConfiguration.mockImplementation(
        (section?: string) => {
          if (section === "qipExtension") {
            return {
              get: jest.fn((_key: string, defaultVal: any) =>
                _key === "useDefaultDiffView"
                  ? state.useDefaultDiffView
                  : defaultVal,
              ),
            };
          }
          return {
            get: jest.fn((_key: string, defaultVal: any) => defaultVal),
            inspect: jest.fn(() => ({
              globalValue: state.globalAssociations,
            })),
            update,
          };
        },
      );
      return update;
    }

    function fireConfigurationChange(affectedSetting: string) {
      const vscode = require("vscode");
      const event = {
        affectsConfiguration: (section: string) => section === affectedSetting,
      };
      for (const call of vscode.workspace.onDidChangeConfiguration.mock
        .calls) {
        call[0](event);
      }
    }

    test("activation writes a 'default' association when the toggle is on", async () => {
      const update = configureDiffToggleMocks({ useDefaultDiffView: true });

      activate(buildMockContext());
      await flushAsync();

      expect(update).toHaveBeenCalledWith(
        DIFF_ASSOCIATIONS_SETTING,
        { [CHAIN_GLOB]: "default" },
        require("vscode").ConfigurationTarget.Global,
      );
    });

    test("activation removes the association when the toggle is off", async () => {
      const update = configureDiffToggleMocks({
        useDefaultDiffView: false,
        globalAssociations: { [CHAIN_GLOB]: "default" },
      });

      activate(buildMockContext());
      await flushAsync();

      expect(update).toHaveBeenCalledWith(
        DIFF_ASSOCIATIONS_SETTING,
        undefined,
        require("vscode").ConfigurationTarget.Global,
      );
    });

    test("keeps unrelated associations when removing the chain entry", async () => {
      const update = configureDiffToggleMocks({
        useDefaultDiffView: false,
        globalAssociations: {
          [CHAIN_GLOB]: "default",
          "*.md": "vscode.markdown.preview.editor",
        },
      });

      activate(buildMockContext());
      await flushAsync();

      expect(update).toHaveBeenCalledWith(
        DIFF_ASSOCIATIONS_SETTING,
        { "*.md": "vscode.markdown.preview.editor" },
        require("vscode").ConfigurationTarget.Global,
      );
    });

    test("does not touch settings when already in sync", async () => {
      const update = configureDiffToggleMocks({ useDefaultDiffView: false });

      activate(buildMockContext());
      await flushAsync();

      expect(update).not.toHaveBeenCalled();
    });

    test("re-syncs when the toggle changes after activation", async () => {
      const state = { useDefaultDiffView: false };
      const update = configureDiffToggleMocks(state);

      activate(buildMockContext());
      await flushAsync();
      expect(update).not.toHaveBeenCalled();

      state.useDefaultDiffView = true;
      fireConfigurationChange("qipExtension.useDefaultDiffView");
      await flushAsync();

      expect(update).toHaveBeenCalledWith(
        DIFF_ASSOCIATIONS_SETTING,
        { [CHAIN_GLOB]: "default" },
        require("vscode").ConfigurationTarget.Global,
      );
    });

    test("ignores unrelated configuration changes", async () => {
      const update = configureDiffToggleMocks({ useDefaultDiffView: false });

      activate(buildMockContext());
      await flushAsync();

      fireConfigurationChange("editor.fontSize");
      await flushAsync();

      expect(update).not.toHaveBeenCalled();
    });
  });

  describe("resolveCustomTextEditorInlineDiff", () => {
    test("registers chain diff handlers and enriches diff webview", async () => {
      const provider = activateAndGetProvider();
      const diffDocuments = {
        original: {
          uri: { path: "/original.chain.qip.yaml" },
          getText: () => "original",
        },
        modified: {
          uri: { path: "/modified.chain.qip.yaml" },
          getText: () => "modified",
        },
      };

      await provider.resolveCustomTextEditorInlineDiff(
        diffDocuments as any,
        mockPanel as any,
        {} as any,
      );

      expect(mockWebview.onDidReceiveMessage).toHaveBeenCalled();
      expect(mockPanel.onDidDispose).toHaveBeenCalled();
      expect(mockWebview.html).toContain("<!DOCTYPE html>");
    });

    test("throws when panel is missing", async () => {
      const provider = activateAndGetProvider();

      await expect(
        provider.resolveCustomTextEditorInlineDiff(
          { original: validDocument, modified: validDocument } as any,
          null as any,
          {} as any,
        ),
      ).rejects.toThrow("Invalid parameters for resolveCustomEditorInlineDiff");
    });
  });
});
