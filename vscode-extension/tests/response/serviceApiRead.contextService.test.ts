import {
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
} from "../helpers/mocks";

jest.mock("vscode", () => createVscodeMock(), { virtual: true });
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({ findFileById: jest.fn() }),
);
jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest
    .fn()
    .mockReturnValue({ contextService: ".context-service.qip.yaml" }),
}));
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: jest.fn() },
}));
jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });

import { getContextService } from "../../src/web/response/serviceApiRead";
import { fileApi } from "../../src/web/response/file/fileApiProvider";

const chainFileUri = { path: "/project/orders/orders.chain.qip.yaml" } as any;
const contextServiceFileUri = {
  path: "/project/ctx-1/ctx-1.context-service.qip.yaml",
} as any;

// Mirrors VSCodeFileApi.getContextService, which throws unless the file holds the requested service.
function storingContextServiceIn(fileUri: any) {
  (fileApi.getContextService as jest.Mock).mockImplementation(
    async (uri: any, id: string) => {
      if (uri !== fileUri) {
        throw new Error("service ID mismatch");
      }
      return { id, name: "Orders Context", content: {} };
    },
  );
}

describe("getContextService – the file a chain reads it from", () => {
  beforeEach(() => jest.clearAllMocks());

  test("finds the context service file when a chain asks for it", async () => {
    storingContextServiceIn(contextServiceFileUri);
    (fileApi.findFileById as jest.Mock).mockResolvedValue(
      contextServiceFileUri,
    );

    const service = await getContextService(chainFileUri, "ctx-1");

    expect(fileApi.findFileById).toHaveBeenCalledWith(
      "ctx-1",
      ".context-service.qip.yaml",
    );
    expect(service.name).toBe("Orders Context");
  });

  test("reads the open context service file without a lookup", async () => {
    storingContextServiceIn(contextServiceFileUri);

    const service = await getContextService(contextServiceFileUri, "ctx-1");

    expect(fileApi.findFileById).not.toHaveBeenCalled();
    expect(service.name).toBe("Orders Context");
  });
});
