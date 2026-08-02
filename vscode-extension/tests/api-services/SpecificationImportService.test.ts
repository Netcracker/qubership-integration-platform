import {
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  stubProjectConfigService,
  buildSystem,
  buildSerializedOpenApiFile,
} from "../helpers/mocks";

const mockValidateAllowedSystemProtocol = jest.fn();
const mockGetSystemById = jest.fn();
const mockCreateApiGroup = jest.fn();
const mockSaveApiGroupFile = jest.fn();
const mockGetApiGroupById = jest.fn();
const mockFailImportSession = jest.fn();

jest.mock("vscode", () => createVscodeMock(), { virtual: true });
jest.mock("yaml", () => ({
  stringify: jest.fn().mockReturnValue(""),
  parse: jest.fn(),
}));
jest.mock("../../src/web/response", () => ({
  validateAllowedSystemProtocol: mockValidateAllowedSystemProtocol,
}));
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({ getFileType: jest.fn().mockResolvedValue("SERVICE") }),
);
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService(),
);

jest.mock("../../src/web/api-services/SystemService", () => ({
  SystemService: jest.fn().mockImplementation(() => ({
    getSystemById: mockGetSystemById,
    saveSystem: jest.fn(),
  })),
}));
jest.mock("../../src/web/api-services/ApiGroupService", () => {
  const ApiGroupService: any = jest.fn().mockImplementation(() => ({
    createApiGroup: mockCreateApiGroup,
    saveApiGroupFile: mockSaveApiGroupFile,
    getApiGroupById: mockGetApiGroupById,
  }));
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

import { SpecificationImportService } from "../../src/web/api-services/SpecificationImportService";
import { IntegrationSystemType } from "../../src/web/api-services/servicesTypes";

describe("SpecificationImportService – validateAllowedSystemProtocol in runImport", () => {
  let service: SpecificationImportService;

  beforeEach(() => {
    jest.clearAllMocks();
    service = new SpecificationImportService({
      path: "/fake/service.yaml",
    } as any);
  });

  test("calls validateAllowedSystemProtocol with system type and detected protocol", async () => {
    mockGetSystemById.mockResolvedValue(
      buildSystem({ integrationSystemType: IntegrationSystemType.IMPLEMENTED }),
    );
    mockCreateApiGroup.mockResolvedValue({
      id: "grp-1",
      name: "Test Group",
      specifications: [],
      synchronization: false,
    });

    await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildSerializedOpenApiFile()],
    });

    expect(mockValidateAllowedSystemProtocol).toHaveBeenCalledWith(
      IntegrationSystemType.IMPLEMENTED,
      expect.any(String),
    );
  });

  test("returns warningMessage (graceful failure) when validation throws", async () => {
    mockGetSystemById.mockResolvedValue(
      buildSystem({
        integrationSystemType: IntegrationSystemType.IMPLEMENTED,
        protocol: "GRPC",
      }),
    );
    mockValidateAllowedSystemProtocol.mockImplementation(() => {
      throw new Error(
        "Specification type is not allowed for implemented system: HTTP",
      );
    });

    const result = await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildSerializedOpenApiFile()],
    });

    expect(result.done).toBe(true);
    expect(result.warningMessage).toBeDefined();
    expect(mockFailImportSession).toHaveBeenCalled();
  });
});
