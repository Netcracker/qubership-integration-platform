// What this file owns: the control flow of `runImport` around its collaborators, over stubs of all
// of them. One case is left, and it asserts that `validateAllowedSystemProtocol` is called with the
// system type and the detected protocol — an interaction only a stub can observe.
//
// `returns warningMessage (graceful failure) when validation throws` used to sit beside it and is
// gone: `tests/web/import/failures.test.ts` pins that refusal over the fake filesystem with the
// real validator, naming the exact message, the untouched tree and the dialog text, so the stubbed
// pair asserted less about more. A case about what the user is told, or about what lands on disk,
// belongs in `tests/web/import/`.
import {
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  buildSystem,
  buildSerializedOpenApiFile,
} from "../helpers/mocks";

const mockValidateAllowedSystemProtocol = jest.fn();
const mockGetSystemById = jest.fn();
const mockCreateSpecificationGroup = jest.fn();
const mockSaveSpecificationGroupFile = jest.fn();
const mockGetSpecificationGroupById = jest.fn();
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
jest.mock("../../src/web/api-services/SystemService", () => ({
  SystemService: jest.fn().mockImplementation(() => ({
    getSystemById: mockGetSystemById,
    saveSystem: jest.fn(),
  })),
}));
jest.mock("../../src/web/api-services/SpecificationGroupService", () => ({
  SpecificationGroupService: jest.fn().mockImplementation(() => ({
    createSpecificationGroup: mockCreateSpecificationGroup,
    saveSpecificationGroupFile: mockSaveSpecificationGroupFile,
    getSpecificationGroupById: mockGetSpecificationGroupById,
  })),
}));
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
    mockCreateSpecificationGroup.mockResolvedValue({
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
});
