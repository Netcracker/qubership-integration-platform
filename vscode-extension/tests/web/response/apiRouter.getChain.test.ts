import { Uri } from "vscode";
import { getApiResponse } from "../../../src/web/response/apiRouter";
import * as chainApiRead from "../../../src/web/response/chainApiRead";

const mockGetChain = jest.spyOn(chainApiRead, "getChain");
const mockWorkspaceFolders = [{ uri: { path: "/workspace", fsPath: "/workspace" } }];

jest.mock("vscode", () => {
  const uriModule = jest.requireActual("../../__mocks__/vscode");
  return {
    __esModule: true,
    default: {
      window: {
        showWarningMessage: jest.fn(),
        showErrorMessage: jest.fn(),
        withProgress: jest.fn(),
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
    findFileByNavigationPath: jest.fn(),
    getFileType: jest.fn(),
  },
}));

describe("apiRouter getChain handler", () => {
  const workspaceUri = Uri.file("/workspace");

  beforeEach(() => {
    mockGetChain.mockReset();
    mockGetChain.mockResolvedValue({ id: "chain-1" } as never);
  });

  test("passes explicit chain file path from object payload", async () => {
    const chainFileUri = Uri.file("/workspace/chains/chain-1.chain.qip.yaml");

    await getApiResponse(
      {
        requestId: "1",
        type: "getChain",
        payload: {
          chainId: "chain-1",
          filePath: chainFileUri.toString(),
        },
      },
      workspaceUri,
    );

    expect(mockGetChain).toHaveBeenCalledWith(
      workspaceUri,
      "chain-1",
      expect.objectContaining({ fsPath: chainFileUri.fsPath }),
    );
  });

  test("loads chain by id when payload is a string", async () => {
    await getApiResponse(
      {
        requestId: "1",
        type: "getChain",
        payload: "chain-1",
      },
      workspaceUri,
    );

    expect(mockGetChain).toHaveBeenCalledWith(workspaceUri, "chain-1", workspaceUri);
  });
});
