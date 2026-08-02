// Model-read tests for the Service -> Group -> API -> Operation chain.
// "API" is a rename of the Specification/SystemModel level (same depth,
// parentId === groupId) — these tests pin that contract plus the additive
// typed operation/model fields mirrored from ui/src/api/apiTypes.ts.

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    Uri: {
      joinPath: jest.fn((_base: any, ...segments: string[]) => ({
        path: segments.join("/"),
        fsPath: segments.join("/"),
      })),
    },
  }),
  { virtual: true },
);

import { QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn(() => ext),
}));

jest.mock("../../../src/web/api-services/LabelUtils", () => ({
  LabelUtils: { toEntityLabels: jest.fn().mockReturnValue([]) },
}));

// Keep parseContent/parseContentWithErrorHandling real: getOperationInfo now
// routes through OperationSchemaExtractor, which pulls in the real per-format
// parsers, and those call the real ContentParser to parse the raw source.
// Only parseContentFromFile (reading the .specification.qip.yaml wrapper) is
// stubbed per test.
jest.mock("../../../src/web/api-services/parsers/ContentParser", () => {
  // Class static methods are non-enumerable, so a `{...actual.ContentParser}`
  // spread silently drops them — bind the two real methods explicitly instead.
  const actual = jest.requireActual(
    "../../../src/web/api-services/parsers/ContentParser",
  ).ContentParser;
  return {
    ContentParser: {
      parseContent: actual.parseContent.bind(actual),
      parseContentWithErrorHandling:
        actual.parseContentWithErrorHandling.bind(actual),
      parseContentFromFile: jest.fn(),
    },
  };
});

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });

const findAndBuildChainsRecursively = jest.fn().mockResolvedValue(undefined);
const getRootDirectory = jest.fn().mockReturnValue({ path: "/root" });
const getMainService = jest.fn();
const getSpecificationGroupFiles = jest.fn();
const getSpecificationFiles = jest.fn();
const parseFile = jest.fn();
const getFileCreatedWhen = jest.fn().mockResolvedValue(1000);
const findFileById = jest.fn();
const readFileContent = jest.fn();

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    getMainService,
    getSpecificationGroupFiles,
    getSpecificationFiles,
    parseFile,
    getFileCreatedWhen,
    findFileById,
    getRootDirectory,
    findAndBuildChainsRecursively,
    readFileContent,
  },
}));

import * as fs from "fs";
import * as path from "path";
import {
  getApiSpecifications,
  getSpecificationModel,
  getOperations,
  getOperationInfo,
} from "../../../src/web/response/serviceApiRead";
import { ContentParser } from "../../../src/web/api-services/parsers/ContentParser";

const SERVICE_ID = "svc-1";
const GROUP_ID = "group-1";
const OTHER_GROUP_ID = "group-2";
const MODEL_ID = "model-1";
const OTHER_MODEL_ID = "model-2";

const serviceFileUri = { path: "service.service.qip.yaml" } as any;

const GROUP_FILE = "group-1.specification-group.qip.yaml";
const MODEL_FILE = "model-1.specification.qip.yaml";
const OTHER_GROUP_MODEL_FILE = "model-2.specification.qip.yaml";

function setUpFixture() {
  getMainService.mockResolvedValue({ id: SERVICE_ID });

  getSpecificationGroupFiles.mockResolvedValue([GROUP_FILE]);
  getSpecificationFiles.mockResolvedValue([MODEL_FILE, OTHER_GROUP_MODEL_FILE]);

  parseFile.mockImplementation(async (uri: { path: string }) => {
    switch (uri.path) {
      case GROUP_FILE:
        return {
          id: GROUP_ID,
          name: "Group One",
          content: {
            parentId: SERVICE_ID,
            description: "group desc",
            synchronization: true,
            labels: [],
          },
        };
      case MODEL_FILE:
        return {
          id: MODEL_ID,
          name: "Model One",
          content: {
            parentId: GROUP_ID,
            description: "api desc",
            version: "1.0",
            format: "OpenAPI",
            content: "raw-content",
            deprecated: false,
            labels: [],
            specificationType: "OpenAPI",
            specificationVersion: "3.1",
            operations: [
              {
                id: "op-1",
                name: "Op One",
                description: "does foo",
                method: "GET",
                path: "/foo",
                operationType: "unary",
                binding: "http",
                rpcMethod: "",
                summary: "does foo",
                isDeprecated: true,
              },
            ],
          },
        };
      case OTHER_GROUP_MODEL_FILE:
        // Belongs to a different group — must not leak into GROUP_ID's models.
        return {
          id: OTHER_MODEL_ID,
          name: "Model Two",
          content: {
            parentId: OTHER_GROUP_ID,
            operations: [],
          },
        };
      default:
        throw new Error(`Unexpected file ${uri.path}`);
    }
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  findAndBuildChainsRecursively.mockResolvedValue(undefined);
  getRootDirectory.mockReturnValue({ path: "/root" });
  getFileCreatedWhen.mockResolvedValue(1000);
  setUpFixture();
});

describe("getApiSpecifications - service -> group -> API -> operation", () => {
  test("reads the full chain and surfaces the typed API/operation fields", async () => {
    const groups = await getApiSpecifications(serviceFileUri, SERVICE_ID);

    expect(groups).toHaveLength(1);
    const [group] = groups;
    expect(group.id).toBe(GROUP_ID);
    expect(group.parentId).toBe(SERVICE_ID);

    expect(group.specifications).toHaveLength(1);
    const [api] = group.specifications;
    expect(api.id).toBe(MODEL_ID);
    expect(api.parentId).toBe(GROUP_ID);
    expect(api.specificationType).toBe("OpenAPI");
    expect(api.specificationVersion).toBe("3.1");

    expect(api.operations).toHaveLength(1);
    const [operation] = api.operations!;
    expect(operation.modelId).toBe(MODEL_ID);
    expect(operation.operationType).toBe("unary");
    expect(operation.binding).toBe("http");
    expect(operation.summary).toBe("does foo");
    expect(operation.isDeprecated).toBe(true);
  });

  test("leaves the typed fields undefined when the raw file does not carry them", async () => {
    parseFile.mockImplementation(async (uri: { path: string }) => {
      if (uri.path === GROUP_FILE) {
        return {
          id: GROUP_ID,
          name: "Group One",
          content: { parentId: SERVICE_ID },
        };
      }
      if (uri.path === MODEL_FILE) {
        return {
          id: MODEL_ID,
          name: "Model One",
          content: {
            parentId: GROUP_ID,
            operations: [{ id: "op-1", name: "Op One", method: "GET", path: "/foo" }],
          },
        };
      }
      return {
        id: OTHER_MODEL_ID,
        name: "Model Two",
        content: { parentId: OTHER_GROUP_ID, operations: [] },
      };
    });

    const [group] = await getApiSpecifications(serviceFileUri, SERVICE_ID);
    const [api] = group.specifications;

    expect(api.specificationType).toBeUndefined();
    expect(api.specificationVersion).toBeUndefined();
    expect(api.operations![0].operationType).toBeUndefined();
    expect(api.operations![0].isDeprecated).toBeUndefined();
  });

  // A project can hold both group extensions for one id (a backend export dropped next to a hand-kept older
  // file, a half-finished migration, a merge). The tree must show one entry, read off the same file
  // ApiGroupService.resolveGroupFile picks.
  test("lists a group once when both group extensions carry the same id", async () => {
    const RENAMED_GROUP_FILE = "group-1.api-group.qip.yaml";
    getSpecificationGroupFiles.mockResolvedValue([
      GROUP_FILE,
      RENAMED_GROUP_FILE,
    ]);
    parseFile.mockImplementation(async (uri: { path: string }) => {
      if (uri.path === GROUP_FILE) {
        return {
          id: GROUP_ID,
          name: "Stale Group",
          content: { parentId: SERVICE_ID },
        };
      }
      if (uri.path === RENAMED_GROUP_FILE) {
        return {
          id: GROUP_ID,
          name: "Current Group",
          content: { parentId: SERVICE_ID },
        };
      }
      return {
        id: OTHER_MODEL_ID,
        name: "Model Two",
        content: { parentId: OTHER_GROUP_ID, operations: [] },
      };
    });

    const groups = await getApiSpecifications(serviceFileUri, SERVICE_ID);

    expect(groups).toHaveLength(1);
    expect(groups[0].name).toBe("Current Group");
  });
});

describe("getSpecificationModel - API level, parentId === groupId", () => {
  test("returns only models whose parentId matches the requested groupId", async () => {
    const models = await getSpecificationModel(
      serviceFileUri,
      SERVICE_ID,
      GROUP_ID,
    );

    expect(models.map((m) => m.id)).toEqual([MODEL_ID]);
  });

  test("returns the sibling group's model when queried by its own groupId", async () => {
    const models = await getSpecificationModel(
      serviceFileUri,
      SERVICE_ID,
      OTHER_GROUP_ID,
    );

    expect(models.map((m) => m.id)).toEqual([OTHER_MODEL_ID]);
  });

  // Same situation as the group level: one id can end up with a file under both extensions, and
  // getSpecificationFiles scans both. The API list must show it once, from the newer `.api.` file.
  test("lists an API once when both extensions carry the same id", async () => {
    const RENAMED_MODEL_FILE = "model-1.api.qip.yaml";
    getSpecificationFiles.mockResolvedValue([MODEL_FILE, RENAMED_MODEL_FILE]);
    parseFile.mockImplementation(async (uri: { path: string }) => {
      if (uri.path === MODEL_FILE) {
        return {
          id: MODEL_ID,
          name: "Stale Model",
          content: { parentId: GROUP_ID, operations: [] },
        };
      }
      if (uri.path === RENAMED_MODEL_FILE) {
        return {
          id: MODEL_ID,
          name: "Current Model",
          content: { parentId: GROUP_ID, operations: [] },
        };
      }
      throw new Error(`Unexpected file ${uri.path}`);
    });

    const models = await getSpecificationModel(
      serviceFileUri,
      SERVICE_ID,
      GROUP_ID,
    );

    expect(models).toHaveLength(1);
    expect(models[0].name).toBe("Current Model");
  });
});

describe("getOperations - operations for a given API id", () => {
  test("reads operations for the API by modelId when given the service file", async () => {
    const operations = await getOperations(serviceFileUri, MODEL_ID);

    expect(operations).toHaveLength(1);
    expect(operations[0]).toMatchObject({
      id: "op-1",
      modelId: MODEL_ID,
      operationType: "unary",
      binding: "http",
      summary: "does foo",
      isDeprecated: true,
    });
  });

  test("resolves the API file directly when not starting from the service file", async () => {
    const modelFileUri = { path: MODEL_FILE } as any;
    findFileById.mockResolvedValue(modelFileUri);
    parseFile.mockResolvedValue({
      id: MODEL_ID,
      content: {
        parentId: GROUP_ID,
        operations: [{ id: "op-1", name: "Op One", method: "GET", path: "/foo" }],
      },
    });

    const nonServiceUri = { path: "some-other-file.yaml" } as any;
    const operations = await getOperations(nonServiceUri, MODEL_ID);

    expect(findFileById).toHaveBeenCalledWith(MODEL_ID, ext.specification);
    expect(parseFile).toHaveBeenCalledWith(modelFileUri);
    expect(operations).toHaveLength(1);
    expect(operations[0].modelId).toBe(MODEL_ID);
  });

  test("falls back to the api extension when the model file is `.api.<app>.yaml`", async () => {
    const apiFileUri = { path: "model-1.api.qip.yaml" } as any;
    findFileById.mockImplementation(
      async (_id: string, extension: string) => {
        if (extension === ext.specification) {
          throw new Error("no .specification file");
        }
        return apiFileUri;
      },
    );
    parseFile.mockResolvedValue({
      id: MODEL_ID,
      content: {
        parentId: GROUP_ID,
        operations: [
          { id: "op-1", name: "Op One", method: "GET", path: "/foo" },
        ],
      },
    });

    const nonServiceUri = { path: "some-other-file.yaml" } as any;
    const operations = await getOperations(nonServiceUri, MODEL_ID);

    expect(findFileById).toHaveBeenCalledWith(MODEL_ID, ext.specification);
    expect(findFileById).toHaveBeenCalledWith(MODEL_ID, ext.api);
    expect(operations).toHaveLength(1);
    expect(operations[0].modelId).toBe(MODEL_ID);
  });
});

// The api format (`.api.<app>.yaml`) discriminates each operation by `type`
// and splits method/path per protocol; the model lists its sources in
// `specifications[]` with `filePath`/`isRoot`. These pin the new read shape.
describe("reads the new api content shape", () => {
  const API_MODEL_FILE = "model-1.api.qip.yaml";

  test("surfaces typed operation fields from an api-format file when reading the model", async () => {
    getSpecificationFiles.mockResolvedValue([API_MODEL_FILE]);
    parseFile.mockImplementation(async (uri: { path: string }) => {
      if (uri.path === API_MODEL_FILE) {
        return {
          id: MODEL_ID,
          name: "Payments API",
          content: {
            parentId: GROUP_ID,
            specificationType: "protobuf",
            specificationVersion: "proto3",
            specifications: [
              { filePath: "source-model-1/payments.proto", isRoot: true },
            ],
            operations: [
              {
                id: "op-openapi",
                name: "Get Foo",
                type: "openapi",
                method: "get",
                path: "/foo",
                summary: "gets foo",
                isDeprecated: false,
              },
              {
                id: "op-async",
                name: "Publish Bar",
                type: "asyncapi",
                method: "publish",
                channel: "bar-topic",
              },
              {
                id: "op-proto",
                name: "Authorize",
                type: "protobuf",
                package: "acme.payments.v1",
                service: "PaymentService",
                rpcMethod: "Authorize",
              },
            ],
          },
        };
      }
      throw new Error(`Unexpected file ${uri.path}`);
    });

    const models = await getSpecificationModel(
      serviceFileUri,
      SERVICE_ID,
      GROUP_ID,
    );

    expect(models).toHaveLength(1);
    const [api] = models;
    expect(api.specificationType).toBe("protobuf");
    expect(api.specificationVersion).toBe("proto3");

    const ops = api.operations!;
    const openapi = ops.find((o) => o.id === "op-openapi")!;
    expect(openapi.operationKind).toBe("openapi");
    // A typed openapi op derives its method uppercase, matching the backend column (the api file stores "get").
    expect(openapi.method).toBe("GET");
    expect(openapi.path).toBe("/foo");
    expect(openapi.summary).toBe("gets foo");
    expect(openapi.isDeprecated).toBe(false);

    const asyncOp = ops.find((o) => o.id === "op-async")!;
    expect(asyncOp.operationKind).toBe("asyncapi");
    expect(asyncOp.channel).toBe("bar-topic");
    expect(asyncOp.method).toBe("publish");

    const proto = ops.find((o) => o.id === "op-proto")!;
    expect(proto.operationKind).toBe("protobuf");
    expect(proto.package).toBe("acme.payments.v1");
    expect(proto.service).toBe("PaymentService");
    expect(proto.rpcMethod).toBe("Authorize");
  });

  // A file written by runtime-catalog carries only the typed fields: asyncapi
  // has no flat `path`; protobuf/graphql/wsdl have neither `method` nor `path`.
  // parseOperations must derive them exactly as the backend derives its
  // columns, or the URL fallback and every element's integrationOperationPath
  // read empty.
  test("derives method/path for backend-shaped non-openapi operations that omit the flat fields", async () => {
    getSpecificationFiles.mockResolvedValue([API_MODEL_FILE]);
    parseFile.mockImplementation(async (uri: { path: string }) => {
      if (uri.path === API_MODEL_FILE) {
        return {
          id: MODEL_ID,
          name: "Mixed API",
          content: {
            parentId: GROUP_ID,
            specificationType: "protobuf",
            operations: [
              // asyncapi: flat `method` present, `path` absent → path = channel.
              {
                id: "op-async",
                type: "asyncapi",
                method: "publish",
                channel: "user/notify",
              },
              // protobuf: no flat method/path → method = rpcMethod, path =
              // (javaPackage ?? package) + "." + service. A backend file carries
              // javaPackage, which here differs from the proto package.
              {
                id: "op-proto",
                type: "protobuf",
                package: "acme.payments.v1",
                service: "PaymentService",
                rpcMethod: "Authorize",
                javaPackage: "com.acme.payments.grpc",
              },
              // graphql: no flat method/path → method = operationType,
              // path = sdl, which a backend file now carries.
              {
                id: "op-graphql",
                type: "graphql",
                operationType: "query",
                sdl: "customer(id: ID!): Customer",
              },
              // wsdl: no flat method/path → method = "POST", path = "".
              {
                id: "op-wsdl",
                type: "wsdl",
                protocol: "SOAP11",
                binding: "HelloBinding",
              },
            ],
          },
        };
      }
      throw new Error(`Unexpected file ${uri.path}`);
    });

    const [api] = await getSpecificationModel(
      serviceFileUri,
      SERVICE_ID,
      GROUP_ID,
    );
    const ops = api.operations!;

    const asyncOp = ops.find((o) => o.id === "op-async")!;
    expect(asyncOp.method).toBe("publish");
    expect(asyncOp.path).toBe("user/notify");

    const proto = ops.find((o) => o.id === "op-proto")!;
    expect(proto.method).toBe("Authorize");
    expect(proto.path).toBe("com.acme.payments.grpc.PaymentService");

    const graphql = ops.find((o) => o.id === "op-graphql")!;
    expect(graphql.method).toBe("query");
    expect(graphql.path).toBe("customer(id: ID!): Customer");

    const wsdl = ops.find((o) => o.id === "op-wsdl")!;
    expect(wsdl.method).toBe("POST");
    expect(wsdl.path).toBe("");
  });
});

// getOperationInfo routes requestSchema/responseSchemas and `specification`
// through OperationSchemaExtractor instead of trusting whatever was
// materialized onto the operation at import time.
describe("getOperationInfo - recomputes schemas from the raw source", () => {
  const OPERATION_ID = "op-1";
  const RAW_SOURCE_FILE_NAME = `source-${MODEL_ID}/openapi.json`;

  const OPENAPI_RAW = JSON.stringify({
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

  function mockSpecificationFile(content: any) {
    (ContentParser.parseContentFromFile as jest.Mock).mockResolvedValue({
      id: MODEL_ID,
      content,
    });
  }

  beforeEach(() => {
    getSpecificationFiles.mockResolvedValue([MODEL_FILE]);
    readFileContent.mockResolvedValue(OPENAPI_RAW);
    mockSpecificationFile({
      parentId: GROUP_ID,
      specificationSources: [
        {
          id: "src-1",
          name: "openapi.json",
          fileName: RAW_SOURCE_FILE_NAME,
          mainSource: true,
        },
      ],
      operations: [
        {
          id: OPERATION_ID,
          name: "getFoo",
          method: "GET",
          path: "/foo",
          specification: { operationId: "getFoo" },
          // Stale schemas left over from import — the extractor must
          // recompute rather than trust these.
          requestSchema: { stale: true },
          responseSchemas: { stale: true },
        },
      ],
    });
  });

  test("recomputes requestSchema/responseSchemas from the raw source", async () => {
    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.id).toBe(OPERATION_ID);
    expect(info.specification).toEqual({ operationId: "getFoo" });
    expect(info.requestSchema).toEqual({});
    expect(
      (info.responseSchemas as any)["200"]["application/json"].properties.id
        .type,
    ).toBe("string");
    expect(readFileContent).toHaveBeenCalledWith(
      expect.objectContaining({
        path: `resources/${RAW_SOURCE_FILE_NAME}`,
      }),
    );
  });

  // Import writes no schema fields at all, not even empty placeholders, so the
  // extractor must rebuild them from the raw source rather than patch what it finds.
  test("recomputes schemas when the persisted operation carries no schema fields at all", async () => {
    mockSpecificationFile({
      parentId: GROUP_ID,
      format: "HTTP",
      specificationSources: [
        {
          id: "src-1",
          name: "openapi.json",
          fileName: RAW_SOURCE_FILE_NAME,
          mainSource: true,
        },
      ],
      operations: [
        {
          id: OPERATION_ID,
          name: "getFoo",
          method: "GET",
          path: "/foo",
          specification: { operationId: "getFoo" },
        },
      ],
    });

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.requestSchema).toEqual({});
    expect(
      (info.responseSchemas as any)["200"]["application/json"].properties.id
        .type,
    ).toBe("string");
  });

  test("resolves the root source from the api-format `specifications[]` (filePath / isRoot)", async () => {
    mockSpecificationFile({
      parentId: GROUP_ID,
      format: "HTTP",
      specifications: [{ filePath: RAW_SOURCE_FILE_NAME, isRoot: true }],
      operations: [
        {
          id: OPERATION_ID,
          name: "getFoo",
          type: "openapi",
          method: "get",
          path: "/foo",
          specification: { operationId: "getFoo" },
        },
      ],
    });

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.requestSchema).toEqual({});
    expect(
      (info.responseSchemas as any)["200"]["application/json"].properties.id
        .type,
    ).toBe("string");
    expect(readFileContent).toHaveBeenCalledWith(
      expect.objectContaining({ path: `resources/${RAW_SOURCE_FILE_NAME}` }),
    );
  });

  // A backend-exported `.api` file carries only the typed fields for
  // non-openapi operations (asyncapi has no flat `path`; protobuf has neither
  // `method` nor `path`). The extractor matches by (path, method), so
  // getOperationInfo must derive them the same way parseOperations does, or
  // AsyncAPI/gRPC operations come back with empty schemas. Flat-openapi cases
  // above never exercised this.
  test("derives channel/method for a backend-shaped typed AsyncAPI operation and recomputes schemas", async () => {
    const kafkaRaw = fs.readFileSync(
      path.resolve(
        __dirname,
        "../../fixtures/asyncapi/v2/kafka-v2-inline-oneof.yaml",
      ),
      "utf8",
    );
    readFileContent.mockResolvedValue(kafkaRaw);
    mockSpecificationFile({
      parentId: GROUP_ID,
      format: "KAFKA",
      specifications: [{ filePath: RAW_SOURCE_FILE_NAME, isRoot: true }],
      operations: [
        {
          id: OPERATION_ID,
          name: "onPresenceUpdate",
          // Backend shape: typed discriminator + channel, no flat `path`.
          type: "asyncapi",
          method: "publish",
          channel: "chat.presence",
          specification: {},
        },
      ],
    });

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.requestSchema).toEqual({});
    expect(Object.keys(info.responseSchemas).sort()).toEqual([
      "payload_0",
      "payload_1",
    ]);
  });

  test("derives service/method for a backend-shaped typed gRPC operation and recomputes schemas", async () => {
    const protoRaw = `
syntax = "proto3";
package demo.v1;

message PingRequest { string message = 1; }
message PingResponse { string reply = 1; }

service PingService {
  rpc Ping (PingRequest) returns (PingResponse);
}
`;
    readFileContent.mockResolvedValue(protoRaw);
    mockSpecificationFile({
      parentId: GROUP_ID,
      format: "GRPC",
      specifications: [{ filePath: RAW_SOURCE_FILE_NAME, isRoot: true }],
      operations: [
        {
          id: OPERATION_ID,
          name: "Ping",
          // Backend shape: typed discriminator + service/rpc, no flat
          // `method`/`path`. path = package.service, method = rpcMethod.
          type: "protobuf",
          package: "demo.v1",
          service: "PingService",
          rpcMethod: "Ping",
          specification: {},
        },
      ],
    });

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.requestSchema["application/json"]).toBeDefined();
    expect(
      (info.responseSchemas as any)["200"]["application/json"],
    ).toBeDefined();
  });

  test("degrades to empty schemas when the raw source cannot be located", async () => {
    mockSpecificationFile({
      parentId: GROUP_ID,
      operations: [
        {
          id: OPERATION_ID,
          name: "getFoo",
          method: "GET",
          path: "/foo",
          specification: {},
        },
      ],
    });

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.requestSchema).toEqual({});
    expect(info.responseSchemas).toEqual({});
  });

  test("degrades to empty schemas (never throws) when the raw source is corrupt", async () => {
    readFileContent.mockResolvedValue("{ not valid json or yaml : [");

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.requestSchema).toEqual({});
    expect(info.responseSchemas).toEqual({});
  });

  test("throws when the operation id is not found in any specification file", async () => {
    getSpecificationFiles.mockResolvedValue([]);

    await expect(
      getOperationInfo(serviceFileUri, "missing-op"),
    ).rejects.toThrow("Operation with id missing-op not found");
  });
});

// A backend-exported `.api` file no longer carries the per-operation
// `specification` object; the backend re-derives it on import and so must the
// extension, or the qip-ui auto-fill that reads it (maas.classifier.name, the
// Kafka topic and AMQP exchange/queue, the HTTP path/query parameters) goes
// silently empty. The value the file does carry stays authoritative.
describe("getOperationInfo - derives `specification` when the api file omits it", () => {
  const OPERATION_ID = "op-1";
  const RAW_SOURCE_FILE_NAME = `source-${MODEL_ID}/asyncapi.yaml`;

  // The shared conformance corpus is the parity oracle for the derived value:
  // `sendCreateOrder` there pins topic + maasClassifierName for this channel.
  const KAFKA_CORPUS_SOURCE = fs.readFileSync(
    path.resolve(
      __dirname,
      "../../../../schemas/src/test/resources/conformance/asyncapi30-kafka-comprehensive/source.input.yaml",
    ),
    "utf8",
  );

  const DERIVED_SPECIFICATION = {
    topic: "orders.commands",
    maasClassifierName: "order-commands",
  };

  function mockKafkaApiFile(operationOverrides: Record<string, unknown> = {}) {
    (ContentParser.parseContentFromFile as jest.Mock).mockResolvedValue({
      id: MODEL_ID,
      content: {
        parentId: GROUP_ID,
        format: "KAFKA",
        specifications: [{ filePath: RAW_SOURCE_FILE_NAME, isRoot: true }],
        operations: [
          {
            id: OPERATION_ID,
            name: "sendCreateOrder",
            // Backend shape: typed discriminator + channel, no flat `path`.
            type: "asyncapi",
            method: "send",
            channel: "orders.commands",
            ...operationOverrides,
          },
        ],
      },
    });
  }

  beforeEach(() => {
    getSpecificationFiles.mockResolvedValue([MODEL_FILE]);
    readFileContent.mockResolvedValue(KAFKA_CORPUS_SOURCE);
    mockKafkaApiFile();
  });

  test("derives the specification, maasClassifierName included, when the operation carries none", async () => {
    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.specification).toEqual(DERIVED_SPECIFICATION);
  });

  test("keeps the specification the file carries instead of the derived one", async () => {
    const storedSpecification = {
      topic: "orders.commands",
      maasClassifierName: "hand-edited",
    };
    mockKafkaApiFile({ specification: storedSpecification });

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.specification).toEqual(storedSpecification);
  });

  // `{}` is a value the file carries, not an absent one — the backend guard is
  // "fill only when null", so an empty object must not be overwritten either.
  test("keeps an empty specification the file carries", async () => {
    mockKafkaApiFile({ specification: {} });

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.specification).toEqual({});
  });

  test("leaves the specification empty (never throws) when the raw source is corrupt", async () => {
    readFileContent.mockResolvedValue("asyncapi: 3.0.0\nchannels: [");

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.specification).toEqual({});
    expect(info.requestSchema).toEqual({});
    expect(info.responseSchemas).toEqual({});
  });

  test("leaves the specification empty when the raw source is missing", async () => {
    readFileContent.mockRejectedValue(new Error("ENOENT"));

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.specification).toEqual({});
    expect(info.requestSchema).toEqual({});
    expect(info.responseSchemas).toEqual({});
  });

  test("leaves the specification empty when the operation does not match the source", async () => {
    mockKafkaApiFile({ channel: "orders.unknown" });

    const info = await getOperationInfo(serviceFileUri, OPERATION_ID);

    expect(info.specification).toEqual({});
  });
});
