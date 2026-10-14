import { Uri } from "vscode";
import {
  getEditorViewTypeForUri,
  openDocumentInEditor,
} from "../../src/web/editorViewTypes";

const mockExecuteCommand = jest.fn();

jest.mock("vscode", () => {
  const uriModule = jest.requireActual("../__mocks__/vscode");
  return {
    commands: {
      executeCommand: (...args: unknown[]) => mockExecuteCommand(...args),
    },
    Uri: uriModule.Uri,
  };
});

jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn(() => ({
    chain: ".chain.cip.yaml",
    service: ".service.cip.yaml",
    contextService: ".context-service.cip.yaml",
    mcpService: ".mcp-service.cip.yaml",
  })),
}));

describe("editorViewTypes", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockExecuteCommand.mockResolvedValue(undefined);
  });

  describe("getEditorViewTypeForUri", () => {
    test("returns chain editor for chain files", () => {
      const uri = Uri.file("/workspace/chains/chain-1.chain.cip.yaml");

      expect(getEditorViewTypeForUri(uri)).toBe("qip.chainFile.editor");
    });

    test("throws when no editor matches the file extension", () => {
      const uri = Uri.file("/workspace/readme.txt");

      expect(() => getEditorViewTypeForUri(uri)).toThrow(
        "Unable to find an editor for document",
      );
    });
  });

  describe("openDocumentInEditor", () => {
    test("opens document with the matching custom editor", async () => {
      const uri = Uri.file("/workspace/chains/chain-1.chain.cip.yaml");

      await openDocumentInEditor(uri);

      expect(mockExecuteCommand).toHaveBeenCalledWith(
        "vscode.openWith",
        uri,
        "qip.chainFile.editor",
      );
    });
  });
});
