import { ExtensionContext, TextDocument, Uri, WebviewPanel } from "vscode";
import { registerChainDiffMessageHandlers } from "../../src/web/chainDiffEditor";

const mockSchemaToChain = jest.fn();
const mockParseContent = jest.fn();
const mockGetApiResponse = jest.fn();
const mockUpdateNavigationStateValue = jest.fn();
const mockOpenDocumentInEditor = jest.fn();

jest.mock("../../src/web/response/chainApiRead", () => ({
  schemaToChain: (...args: unknown[]) => mockSchemaToChain(...args),
}));

jest.mock("../../src/web/api-services", () => ({
  ContentParser: {
    parseContent: (...args: unknown[]) => mockParseContent(...args),
  },
}));

jest.mock("../../src/web/response/apiRouter", () => ({
  getApiResponse: (...args: unknown[]) => mockGetApiResponse(...args),
}));

jest.mock("../../src/web/response/navigationUtils", () => ({
  updateNavigationStateValue: (...args: unknown[]) =>
    mockUpdateNavigationStateValue(...args),
}));

function createDocument(
  path: string,
  text: string,
  scheme = "file",
): TextDocument {
  const uri = {
    path,
    scheme,
    toString: () => `${scheme}://${path}`,
  } as Uri;

  return {
    uri,
    getText: () => text,
  } as TextDocument;
}

function createPanel(): WebviewPanel {
  let messageHandler: ((message: unknown) => Promise<void>) | undefined;

  return {
    webview: {
      postMessage: jest.fn(),
      onDidReceiveMessage: jest.fn((handler) => {
        messageHandler = handler;
        return { dispose: jest.fn() };
      }),
    },
    onDidDispose: jest.fn(() => ({ dispose: jest.fn() })),
    __triggerMessage: async (message: unknown) => {
      if (!messageHandler) {
        throw new Error("Message handler was not registered");
      }
      await messageHandler(message);
    },
  } as unknown as WebviewPanel & {
    __triggerMessage: (message: unknown) => Promise<void>;
  };
}

describe("registerChainDiffMessageHandlers", () => {
  const context = { extensionUri: Uri.file("/extension") } as ExtensionContext;
  const originalChain = { id: "original-chain" };
  const modifiedChain = { id: "modified-chain" };

  beforeEach(() => {
    jest.clearAllMocks();
    mockParseContent.mockImplementation((text: string) => ({ parsed: text }));
    mockSchemaToChain.mockImplementation(async (uri: Uri) => {
      if (uri.path.includes("original")) {
        return originalChain;
      }
      return modifiedChain;
    });
    mockGetApiResponse.mockResolvedValue(Uri.file("/workspace/navigated.yaml"));
    mockUpdateNavigationStateValue.mockResolvedValue(undefined);
    mockOpenDocumentInEditor.mockResolvedValue(undefined);
  });

  test("responds to comparedDocumentsRequest", async () => {
    const panel = createPanel();
    const documents = {
      original: createDocument("/original.chain.qip.yaml", "original"),
      modified: createDocument("/modified.chain.qip.yaml", "modified"),
    };

    registerChainDiffMessageHandlers({
      context,
      panel,
      documents,
      openDocumentInEditor: mockOpenDocumentInEditor,
    });

    await (panel as WebviewPanel & { __triggerMessage: Function }).__triggerMessage({
      command: "apiCall",
      data: {
        requestId: "req-1",
        type: "comparedDocumentsRequest",
      },
    });

    expect(mockParseContent).toHaveBeenCalledTimes(2);
    expect(panel.webview.postMessage).toHaveBeenCalledWith({
      requestId: "req-1",
      type: "comparedDocumentsResponse",
      payload: {
        original: originalChain,
        modified: modifiedChain,
      },
    });
  });
});
