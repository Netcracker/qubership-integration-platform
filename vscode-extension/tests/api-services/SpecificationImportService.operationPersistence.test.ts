// Import must not materialize requestSchema/responseSchemas onto operations, matching
// the backend, which no longer stores those columns. These tests run the real
// SpecificationProcessorService and OpenAPI parser, so the assertions cover the payload
// import actually generates rather than a hand-written fixture.

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

jest.mock("vscode", () => createVscodeMock(), { virtual: true });
jest.mock("../../src/web/response", () => ({
  validateAllowedSystemProtocol: mockValidateAllowedSystemProtocol,
}));
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({
    getFileType: jest.fn().mockResolvedValue("SERVICE"),
    writeFile: mockWriteFile,
  }),
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

// SpecificationProcessorService, its parsers, ContentParser and
// SpecificationTypeDetector are intentionally left real: the assertions need
// the actual operation payload the OpenAPI parser produces.

import { SpecificationImportService } from "../../src/web/api-services/SpecificationImportService";

const OPENAPI_CONTENT = JSON.stringify({
  openapi: "3.0.0",
  info: { title: "Foo API", version: "1.0.0" },
  paths: {
    "/foo": {
      get: {
        operationId: "getFoo",
        summary: "Get a foo",
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

function decodeWrittenYaml(bytes: Uint8Array): any {
  return yaml.parse(new TextDecoder().decode(bytes));
}

describe("SpecificationImportService - operation persistence", () => {
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

  test("writes structural operation fields without materialized schemas", async () => {
    await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildOpenApiSerializedFile()],
    });

    const specWrite = mockWriteFile.mock.calls
      .map(([, bytes]) => decodeWrittenYaml(bytes))
      .find((decoded) => Array.isArray(decoded?.content?.operations));

    expect(specWrite).toBeDefined();
    const [operation] = specWrite.content.operations;

    // Structural fields survive the import as a typed api operation. openapi
    // methods are lowercased to match the api-operation schema enum.
    expect(operation).toMatchObject({
      name: "getFoo",
      type: "openapi",
      method: "get",
      path: "/foo",
      // Lifted out of the raw operation, the way the backend exports it.
      summary: "Get a foo",
    });

    // The file ships the whole source, so the per-operation slice is redundant
    // for a type the extractor can rebuild — as in the backend's export.
    expect(operation).not.toHaveProperty("specification");

    // The schemas the parser computed in memory are not persisted.
    expect(operation).not.toHaveProperty("requestSchema");
    expect(operation).not.toHaveProperty("responseSchemas");

    // Protocol hint is persisted so getOperationInfo need not sniff.
    expect(specWrite.content.format).toBe("HTTP");
  });

  // Key order follows SystemModelContentDto's field declarations, which is what
  // runtime-catalog's export emits. Without it the same model exported from the
  // backend and written here differ by a diff made of moved lines.
  test("writes api content in the backend's key order", async () => {
    await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [buildOpenApiSerializedFile()],
    });

    const specWrite = mockWriteFile.mock.calls
      .map(([, bytes]) => decodeWrittenYaml(bytes))
      .find((decoded) => Array.isArray(decoded?.content?.operations));

    expect(Object.keys(specWrite)).toEqual([
      "id",
      "$schema",
      "name",
      "content",
    ]);
    expect(Object.keys(specWrite.content)).toEqual([
      "deprecated",
      "version",
      "specificationType",
      "source",
      "format",
      "operations",
      "parentId",
      "specifications",
    ]);
    // An empty label list is a key the backend never writes.
    expect(specWrite.content).not.toHaveProperty("labels");
    expect(specWrite.content.specifications[0]).toMatchObject({
      name: "openapi.json",
      isRoot: true,
    });
  });

  // The read-side rebuild parses the root source alone, so a spec split across
  // files cannot be reconstructed and has to keep its per-operation slice.
  test("keeps the operation slice when the spec has more than one source", async () => {
    await service.importSpecificationGroup({
      systemId: "sys-1",
      name: "Test Group",
      files: [
        buildOpenApiSerializedFile(),
        buildOpenApiSerializedFile("shared.json"),
      ],
    });

    const specWrite = mockWriteFile.mock.calls
      .map(([, bytes]) => decodeWrittenYaml(bytes))
      .find((decoded) => Array.isArray(decoded?.content?.operations));

    expect(specWrite.content.specifications.length).toBeGreaterThan(1);
    expect(specWrite.content.operations[0].specification).toBeDefined();
  });
});
