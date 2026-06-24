import { Uri } from "vscode";
import { getApiResponse } from "../../../src/web/response/apiRouter";
import * as chainApiModify from "../../../src/web/response/chainApiModify";

const mockChangeFolder = jest.spyOn(chainApiModify, "changeFolder");
const mockWorkspaceFolders = [
  { uri: { path: "/workspace", fsPath: "/workspace" } },
];

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

describe("apiRouter moveChain handler", () => {
  const documentUri = Uri.file("/workspace/chains/chain-1.chain.qip.yaml");

  beforeEach(() => {
    mockChangeFolder.mockReset();
    mockChangeFolder.mockResolvedValue(undefined as never);
  });

  test("dispatches moveChain to changeFolder with chainId and folder", async () => {
    await getApiResponse(
      {
        requestId: "1",
        type: "moveChain",
        payload: { chainId: "chain-1", folder: "a/b" },
      },
      documentUri,
    );

    expect(mockChangeFolder).toHaveBeenCalledWith(documentUri, "chain-1", "a/b");
  });
});
