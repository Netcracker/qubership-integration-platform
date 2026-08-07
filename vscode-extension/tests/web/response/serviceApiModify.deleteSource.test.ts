// Guards the source-file cleanup on API delete. The api format renames
// `specificationSources[]`/`fileName` to `specifications[]`/`filePath`; a missed
// rename makes deleteSpecificationModel skip the guard and orphan the source
// files silently, so both shapes are pinned here.

jest.mock(
  "vscode",
  () => {
    const Uri = {
      joinPath: jest.fn((_base: any, ...segments: string[]) => ({
        path: segments.join("/"),
        fsPath: segments.join("/"),
      })),
    };
    const window = {
      showInformationMessage: jest.fn(),
      showErrorMessage: jest.fn(),
    };
    const api = { Uri, window };
    return { __esModule: true, default: api, ...api };
  },
  { virtual: true },
);

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });

jest.mock("../../../src/web/response/serviceApiRead", () => {
  const getMainService = jest.fn();
  return {
    getContextService: jest.fn(),
    getMainService,
    readServiceFile: async (fileUri: any) => ({
      fileUri,
      service: await getMainService(fileUri),
    }),
    getMcpService: jest.fn(),
    getService: jest.fn(),
  };
});

jest.mock("../../../src/web/extension", () => ({
  refreshQipExplorer: jest.fn(),
}));

jest.mock("../../../src/web/response/file/fileExtensions", () => {
  const { QIP_FILE_EXTENSIONS } = jest.requireActual("../../helpers/mocks");
  return {
    getExtensionsForFile: jest.fn(),
    getExtensionsForUri: jest.fn().mockReturnValue(QIP_FILE_EXTENSIONS),
    extractFilename: (fileRef: any) =>
      (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
      "",
  };
});

jest.mock("../../../src/web/api-services/LabelUtils", () => ({
  LabelUtils: {
    toEntityLabels: jest.fn().mockReturnValue([]),
    fromEntityLabels: jest.fn().mockReturnValue([]),
  },
}));

jest.mock("../../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: { getConfig: jest.fn(), getInstance: jest.fn() },
}));

jest.mock("../../../src/web/response/serviceApiUtils", () => ({
  validateAllowedSystemProtocol: jest.fn(),
}));

const regenerateGroupApisSafely = jest.fn();

jest.mock("../../../src/web/api-services/ApiGroupService", () => ({
  ApiGroupService: { regenerateGroupApisSafely },
}));

const getSpecificationFiles = jest.fn();
const deleteFile = jest.fn();

const parseContentFromFile = jest.fn();

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    getSpecificationFiles,
    deleteFile,
    parseFile: parseContentFromFile,
  },
}));

jest.mock("../../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile },
}));

import { deleteSpecificationModel } from "../../../src/web/response/serviceApiModify";

const MODEL_ID = "model-1";
const SPEC_FILE = "model-1.api.qip.yaml";
const serviceFileUri = { path: "service.service.qip.yaml" } as any;

function mockModelFile(content: any) {
  getSpecificationFiles.mockResolvedValue([SPEC_FILE]);
  parseContentFromFile.mockResolvedValue({
    id: MODEL_ID,
    name: "Payments API",
    content,
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  deleteFile.mockResolvedValue(undefined);
});

describe("deleteSpecificationModel - source cleanup across both formats", () => {
  test("deletes source files listed in the api format `specifications[]` (filePath)", async () => {
    mockModelFile({
      parentId: "group-1",
      specifications: [
        { filePath: "source-model-1/payments.proto", isRoot: true },
      ],
    });

    await deleteSpecificationModel(serviceFileUri, MODEL_ID);

    expect(deleteFile).toHaveBeenCalledWith(
      expect.objectContaining({
        path: "resources/source-model-1/payments.proto",
      }),
    );
  });

  test("still deletes source files listed in the legacy `specificationSources[]` (fileName)", async () => {
    mockModelFile({
      parentId: "group-1",
      specificationSources: [
        { fileName: "source-model-1/openapi.json", mainSource: true },
      ],
    });

    await deleteSpecificationModel(serviceFileUri, MODEL_ID);

    expect(deleteFile).toHaveBeenCalledWith(
      expect.objectContaining({
        path: "resources/source-model-1/openapi.json",
      }),
    );
  });

  test("skips a `specifications[]` source whose filePath escapes resources with `..`, still deletes safe sources", async () => {
    mockModelFile({
      parentId: "group-1",
      specifications: [
        { filePath: "../../other-service/evil.chain.qip.yaml", isRoot: true },
        { filePath: "source-model-1/payments.proto" },
      ],
    });

    await deleteSpecificationModel(serviceFileUri, MODEL_ID);

    expect(deleteFile).not.toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("..") }),
    );
    expect(deleteFile).toHaveBeenCalledWith(
      expect.objectContaining({
        path: "resources/source-model-1/payments.proto",
      }),
    );
  });

  test("skips a legacy `specificationSources[]` source whose fileName escapes resources with `..`", async () => {
    mockModelFile({
      parentId: "group-1",
      specificationSources: [
        { fileName: "../evil.chain.qip.yaml", mainSource: true },
        { fileName: "source-model-1/openapi.json" },
      ],
    });

    await deleteSpecificationModel(serviceFileUri, MODEL_ID);

    expect(deleteFile).not.toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("..") }),
    );
    expect(deleteFile).toHaveBeenCalledWith(
      expect.objectContaining({
        path: "resources/source-model-1/openapi.json",
      }),
    );
  });

  test("deletes the model file even when it carries no sources", async () => {
    mockModelFile({ parentId: "group-1" });

    await deleteSpecificationModel(serviceFileUri, MODEL_ID);

    expect(deleteFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: SPEC_FILE }),
    );
    expect(deleteFile).not.toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("resources/") }),
    );
  });

  test("regenerates the parent group's apis[] after deleting the model", async () => {
    mockModelFile({ parentId: "group-1" });

    await deleteSpecificationModel(serviceFileUri, MODEL_ID);

    expect(regenerateGroupApisSafely).toHaveBeenCalledWith(
      serviceFileUri,
      "group-1",
    );
  });
});
