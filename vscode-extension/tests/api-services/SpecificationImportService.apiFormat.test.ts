// The extension writes only the api format: a `.api.<app>.yaml` model file
// carrying `specificationType`, `specifications[]`, and typed operations. These
// tests run the real SpecificationProcessorService and OpenAPI parser, so the
// assertions cover the actual persisted payload.

import * as yaml from "yaml";
import {
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  stubProjectConfigService,
  buildSystem,
} from "../helpers/mocks";
import { IntegrationSystemType } from "../../src/web/api-services/servicesTypes";

const mockValidateAllowedSystemProtocol = jest.fn();
const mockGetSystemById = jest.fn();
const mockCreateApiGroup = jest.fn();
const mockSaveApiGroupFile = jest.fn();
const mockWriteFile = jest.fn();
const mockDeleteFile = jest.fn();

const API_SCHEMA_URL =
  "http://qubership.org/schemas/product/qip/api.schema.yaml";

jest.mock("vscode", () => createVscodeMock(), { virtual: true });
jest.mock("../../src/web/response", () => ({
  validateAllowedSystemProtocol: mockValidateAllowedSystemProtocol,
}));
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({
    getFileType: jest.fn().mockResolvedValue("SERVICE"),
    writeFile: mockWriteFile,
    deleteFile: mockDeleteFile,
  }),
);
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService({
    extensions: {
      service: ".service.qip.yaml",
      specification: ".specification.qip.yaml",
      api: ".api.qip.yaml",
    },
    schemaUrls: {
      service: "",
      specification: "",
      specificationGroup: "",
      api: API_SCHEMA_URL,
    },
  }),
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
  }));
  ApiGroupService.regenerateGroupApisSafely = jest.fn();
  return { ApiGroupService };
});
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
      failImportSession: jest.fn(),
      getImportSession: jest.fn(),
    }),
  },
}));
jest.mock("../../src/web/api-services/parsers/SoapSpecificationParser", () => ({
  SoapSpecificationParser: { parseWsdlContent: jest.fn() },
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

// The SpecificationProcessorService, its parsers, ContentParser and
// SpecificationTypeDetector are left real on purpose — the point is to validate
// the actual writer output.

import { SpecificationImportService } from "../../src/web/api-services/SpecificationImportService";
import { ApiGroupService } from "../../src/web/api-services/ApiGroupService";

const OPENAPI_CONTENT = JSON.stringify({
  openapi: "3.0.0",
  info: { title: "Foo API", version: "1.0.0" },
  paths: {
    "/foo": {
      get: {
        operationId: "getFoo",
        responses: {
          "200": {
            description: "OK",
            content: {
              "application/json": {
                schema: {
                  type: "object",
                  properties: { id: { type: "string" } },
                },
              },
            },
          },
        },
      },
    },
  },
});

function buildOpenApiSerializedFile(name = "openapi.json") {
  return {
    name,
    size: OPENAPI_CONTENT.length,
    type: "application/json",
    lastModified: Date.now(),
    content: new TextEncoder().encode(OPENAPI_CONTENT).buffer,
  };
}

function findApiFileWrite(): { uri: any; yamlString: string; model: any } {
  const call = mockWriteFile.mock.calls.find(([uri]) =>
    uri.path.endsWith(".api.qip.yaml"),
  );
  if (!call) {
    throw new Error("No .api.qip.yaml file was written");
  }
  const yamlString = new TextDecoder().decode(call[1]);
  return { uri: call[0], yamlString, model: yaml.parse(yamlString) };
}

describe("SpecificationImportService - api format", () => {
  let service: SpecificationImportService;

  beforeEach(() => {
    jest.clearAllMocks();
    service = new SpecificationImportService({
      path: "/fake/service.yaml",
      fsPath: "/fake/service.yaml",
      with: jest.fn().mockReturnThis(),
    } as any);

    mockGetSystemById.mockResolvedValue(
      buildSystem({
        integrationSystemType: IntegrationSystemType.IMPLEMENTED,
        protocol: "HTTP",
      }),
    );
    mockCreateApiGroup.mockResolvedValue({
      id: "grp-1",
      name: "Test Group",
      specifications: [],
      synchronization: false,
    });
  });

  test("writes an api-format model file with typed operations", async () => {
    await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildOpenApiSerializedFile()],
    });

    const { model } = findApiFileWrite();

    expect(model.$schema).toBe(API_SCHEMA_URL);
    expect(model.content.specificationType).toBe("openapi");

    // Sources are listed under `specifications[]` with filePath/isRoot, not the
    // legacy `specificationSources[]`/fileName/mainSource.
    expect(model.content).not.toHaveProperty("specificationSources");
    expect(model.content.specifications).toHaveLength(1);
    expect(model.content.specifications[0]).toMatchObject({
      filePath: "source-sys-1-Test Group-1.0.0/openapi.json",
      isRoot: true,
    });

    const [operation] = model.content.operations;
    expect(operation).toMatchObject({
      name: "getFoo",
      type: "openapi",
      method: "get",
      path: "/foo",
    });
    expect(operation).not.toHaveProperty("requestSchema");
    expect(operation).not.toHaveProperty("responseSchemas");
  });

  test("deletes the pre-rename .specification sibling on write", async () => {
    await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildOpenApiSerializedFile()],
    });

    const deletedLegacy = mockDeleteFile.mock.calls.some(([uri]) =>
      uri.path.endsWith(".specification.qip.yaml"),
    );
    expect(deletedLegacy).toBe(true);
  });

  // The import path is one of the four apis[] writers; without this hook a fresh
  // import leaves the group's derived list stale.
  test("regenerates the group apis[] after import", async () => {
    await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildOpenApiSerializedFile()],
    });

    expect(ApiGroupService.regenerateGroupApisSafely).toHaveBeenCalledWith(
      expect.objectContaining({ path: "/fake/service.yaml" }),
      "grp-1",
    );
  });
});
