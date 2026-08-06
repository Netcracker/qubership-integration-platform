// Importing a specification into an old-format service writes the service protocol first, and that
// write is the one that converts the file. Every step after it resolves the service folder from the
// uri the importer holds, so an importer that keeps the old one dies on a path it has just deleted —
// after the rename, which leaves the import half done. A service with no protocol yet is exactly
// what an older extension version wrote, so this is the first import into any legacy service.

import {
  buildSerializedOpenApiFile,
  buildSystem,
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  stubProjectConfigService,
} from "../helpers/mocks";

function uri(path: string): any {
  const value = {
    path,
    fsPath: path,
    with: ({ path: newPath }: { path: string }) => uri(newPath),
  };
  return value;
}

const LEGACY_URI = uri("/svc/sys-1/sys-1.service.qip.yaml");
const TYPED_URI = uri("/svc/sys-1/sys-1.external-service.qip.yaml");

const mockGetSystemById = jest.fn();
const mockSaveSystem = jest.fn();
const mockCreateApiGroup = jest.fn();
const mockSaveApiGroupFile = jest.fn();
const mockFailImportSession = jest.fn();
const mockGetFileType = jest.fn();
const apiGroupServiceConstructor = jest.fn();

jest.mock("vscode", () => createVscodeMock(), { virtual: true });
jest.mock("yaml", () => ({
  stringify: jest.fn().mockReturnValue(""),
  parse: jest.fn(),
}));
jest.mock("../../src/web/response", () => ({
  validateAllowedSystemProtocol: jest.fn(),
}));
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({
    getFileType: (...args: unknown[]) => mockGetFileType(...args),
  }),
);
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService(),
);
jest.mock("../../src/web/api-services/SystemService", () => ({
  SystemService: jest.fn().mockImplementation(() => ({
    getSystemById: mockGetSystemById,
    saveSystem: mockSaveSystem,
  })),
}));
jest.mock("../../src/web/api-services/ApiGroupService", () => {
  const ApiGroupService: any = jest.fn().mockImplementation((mainFolder) => {
    apiGroupServiceConstructor(mainFolder);
    return {
      createApiGroup: mockCreateApiGroup,
      saveApiGroupFile: mockSaveApiGroupFile,
      getApiGroupById: jest.fn(),
    };
  });
  ApiGroupService.regenerateGroupApisSafely = jest.fn();
  return { ApiGroupService };
});
jest.mock("../../src/web/api-services/SpecificationProcessorService", () => ({
  SpecificationProcessorService: jest.fn().mockImplementation(() => ({
    processSpecificationFiles: jest.fn().mockResolvedValue([]),
    extractEnvironmentCandidates: jest.fn().mockReturnValue([]),
  })),
}));
jest.mock("../../src/web/api-services/EnvironmentService", () => ({
  EnvironmentService: jest.fn().mockImplementation(() => ({
    getEnvironmentsForSystem: jest.fn().mockResolvedValue([]),
    createEnvironment: jest.fn(),
    updateEnvironment: jest.fn(),
  })),
}));
jest.mock("../../src/web/api-services/importProgressTracker", () => ({
  ImportProgressTracker: {
    getInstance: jest.fn().mockReturnValue({
      startImportSession: jest.fn(),
      completeImportSession: jest.fn(),
      failImportSession: mockFailImportSession,
      getImportSession: jest.fn(),
    }),
  },
}));
jest.mock("../../src/web/api-services/parsers/SoapSpecificationParser", () => ({
  SoapSpecificationParser: { parseWsdlContent: jest.fn() },
}));
jest.mock("../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContent: jest.fn(), parseContentFromFile: jest.fn() },
}));
jest.mock("../../src/web/api-services/SpecificationValidator", () => ({
  SpecificationValidator: { validateSpecificationProtocol: jest.fn() },
}));
jest.mock("../../src/web/api-services/pathUtils", () => ({
  normalizePath: jest.fn((p: string) => p),
}));
jest.mock("../../src/web/api-services/EnvironmentDefaultProperties", () => ({
  EnvironmentDefaultProperties: {
    getDefaultProperties: jest.fn().mockReturnValue({}),
  },
}));
jest.mock("../../src/web/services/ProtocolDetectorService", () => ({
  ProtocolDetectorService: {
    extractArchives: jest.fn((files: File[]) => Promise.resolve(files)),
  },
}));
jest.mock("../../src/web/extension", () => ({ refreshQipExplorer: jest.fn() }));

import { SpecificationImportService } from "../../src/web/api-services/SpecificationImportService";

describe("SpecificationImportService – importing into a service the write converts", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // The legacy file is gone by the time anything asks about it, which is how a stale uri fails:
    // `getFileType` catches the missing stat and answers UNKNOWN rather than throwing.
    mockGetFileType.mockImplementation((fileUri: any) =>
      Promise.resolve(fileUri?.path === TYPED_URI.path ? "SERVICE" : "UNKNOWN"),
    );
    mockSaveSystem.mockResolvedValue(TYPED_URI);
    mockGetSystemById.mockResolvedValue(buildSystem({ protocol: "" }));
    mockCreateApiGroup.mockResolvedValue({
      id: "grp-1",
      name: "Test Group",
      specifications: [],
      synchronization: false,
    });
  });

  test("follows the file the protocol write moved the service to", async () => {
    const service = new SpecificationImportService(LEGACY_URI);

    const result = await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildSerializedOpenApiFile()],
    });

    expect(mockSaveSystem).toHaveBeenCalled();
    expect(result.done).toBe(true);
    expect(result.warningMessage).toBeUndefined();
    expect(mockFailImportSession).not.toHaveBeenCalled();
    expect(mockGetFileType).toHaveBeenCalledWith(TYPED_URI);
    // The api group is written into the folder too, so it has to follow the same uri.
    expect(apiGroupServiceConstructor).toHaveBeenLastCalledWith(TYPED_URI);
  });

  test("stays on its file when the write lands where it started", async () => {
    mockSaveSystem.mockResolvedValue(TYPED_URI);
    const service = new SpecificationImportService(TYPED_URI);

    await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildSerializedOpenApiFile()],
    });

    expect(apiGroupServiceConstructor).toHaveBeenCalledTimes(1);
  });
});
