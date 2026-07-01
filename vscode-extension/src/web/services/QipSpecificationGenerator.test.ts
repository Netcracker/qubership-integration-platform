jest.mock("vscode", () => ({ Uri: class Uri {} }), { virtual: true });

import { QipSpecificationGenerator } from "./QipSpecificationGenerator";

const OPENAPI_30 = {
  openapi: "3.0.0",
  info: { title: "Rich API", version: "2.0.0" },
  servers: [{ url: "https://api.example.com" }],
  paths: {
    "/users/{id}": {
      get: {
        operationId: "getUser",
        tags: ["users"],
        security: [{ apiKey: [] }],
        parameters: [
          { in: "path", name: "id", required: true, schema: { type: "string" } },
          { in: "query", name: "filter", schema: { type: "string" } },
          { in: "header", name: "X-Trace", schema: { type: "string" } },
        ],
        responses: {
          "200": {
            description: "OK",
            content: {
              "application/json": { schema: { $ref: "#/components/schemas/User" } },
            },
          },
          "404": { description: "Not found" },
          default: { description: "Error" },
        },
      },
    },
    "/users": {
      post: {
        operationId: "createUser",
        requestBody: {
          content: {
            "application/json": {
              schema: { $ref: "#/components/schemas/CreateUser" },
            },
            "application/xml": {
              schema: { $ref: "#/components/schemas/CreateUser" },
            },
          },
        },
        responses: { "201": { description: "Created" } },
      },
      put: {
        operationId: "replaceUser",
        requestBody: {
          content: {
            "application/json": {
              schema: {
                type: "object",
                properties: {
                  any: { anyOf: [{ type: "string" }, { type: "number" }] },
                  one: { oneOf: [{ $ref: "#/components/schemas/Address" }] },
                  all: { allOf: [{ $ref: "#/components/schemas/Address" }] },
                  map: {
                    type: "object",
                    additionalProperties: { $ref: "#/components/schemas/Role" },
                  },
                  list: {
                    type: "array",
                    items: { $ref: "#/components/schemas/Role" },
                  },
                },
              },
            },
          },
          responses: { "200": { description: "OK" } },
        },
      },
    },
  },
  components: {
    schemas: {
      User: {
        type: "object",
        properties: {
          id: { type: "string" },
          address: { $ref: "#/components/schemas/Address" },
          roles: { type: "array", items: { $ref: "#/components/schemas/Role" } },
        },
      },
      CreateUser: {
        type: "object",
        properties: { name: { type: "string" } },
      },
      Address: {
        type: "object",
        properties: { city: { type: "string" } },
      },
      Role: { type: "object", properties: { name: { type: "string" } } },
    },
  },
};

const SWAGGER_20 = {
  swagger: "2.0",
  info: { title: "Legacy", version: "1.0.0" },
  host: "legacy.example.com",
  basePath: "/api",
  schemes: ["https"],
  securityDefinitions: {
    apiKey: { type: "apiKey", name: "X-Key", in: "header" },
  },
  paths: {
    "/things": {
      get: {
        operationId: "listThings",
        parameters: [
          { in: "query", name: "limit", type: "integer", format: "int32" },
          { in: "body", name: "body", schema: { $ref: "#/definitions/Thing" } },
        ],
        responses: {
          "200": {
            description: "OK",
            schema: { $ref: "#/definitions/Thing" },
          },
        },
      },
    },
  },
  definitions: {
    Thing: {
      type: "object",
      properties: {
        id: { type: "string" },
        nested: { $ref: "#/definitions/Nested" },
      },
    },
    Nested: { type: "object", properties: { value: { type: "string" } } },
  },
};

describe("QipSpecificationGenerator", () => {
  it("builds operations from a rich OpenAPI 3.0 document", () => {
    const spec = QipSpecificationGenerator.createQipSpecificationFromOpenApi(
      OPENAPI_30,
      "spec",
      "spec-1",
    );
    const operations = spec.content.operations;
    const names = operations.map((o: any) => o.name);
    expect(names).toEqual(
      expect.arrayContaining(["getUser", "createUser", "replaceUser"]),
    );

    const getUser = operations.find((o: any) => o.name === "getUser");
    expect(getUser.method).toBe("GET");
    expect(getUser.path).toBe("/users/{id}");
    // Non-body params land in requestSchema.parameters.
    expect(Array.isArray(getUser.requestSchema.parameters)).toBe(true);
    // Response $ref expands with inlined definitions.
    const response = getUser.responseSchemas["200"]["application/json"];
    expect(response.properties.address.$ref).toBe("#/definitions/Address");
    expect(response.definitions.Address).toBeDefined();
    expect(response.definitions.Role).toBeDefined();

    const createUser = operations.find((o: any) => o.name === "createUser");
    expect(createUser.method).toBe("POST");
    expect(createUser.requestSchema["application/json"]).toBeDefined();
    expect(createUser.requestSchema["application/xml"]).toBeDefined();
  });

  it("converts Swagger 2.0 to OpenAPI 3.0 and resolves definitions", () => {
    const spec = QipSpecificationGenerator.createQipSpecificationFromOpenApi(
      SWAGGER_20,
      "spec",
      "spec-2",
    );
    const operations = spec.content.operations;
    expect(operations).toHaveLength(1);
    const op = operations[0];
    expect(op.name).toBe("listThings");
    const response = op.responseSchemas["200"]["application/json"];
    expect(response.properties.nested.$ref).toBe("#/definitions/Nested");
    expect(response.definitions.Nested).toBeDefined();
  });

  it("converts Swagger 2.0 form-data parameters and generates a missing operationId", () => {
    const spec = QipSpecificationGenerator.createQipSpecificationFromOpenApi(
      {
        swagger: "2.0",
        info: { title: "Forms", version: "1.0.0" },
        paths: {
          "/upload": {
            post: {
              // no operationId -> generated from method + path
              consumes: ["multipart/form-data"],
              parameters: [
                { in: "formData", name: "file", type: "file", required: true },
                {
                  in: "formData",
                  name: "tags",
                  type: "array",
                  items: { type: "string" },
                  collectionFormat: "csv",
                  enum: ["a", "b"],
                },
                { in: "formData", name: "count", type: "integer", minimum: 1, maximum: 10 },
                { in: "query", name: "dryRun", type: "boolean" },
              ],
              responses: { "200": { description: "OK" }, default: { description: "err" } },
            },
          },
        },
      },
      "spec",
      "spec-form",
    );
    const op = spec.content.operations[0];
    expect(op.name).toBe("postUpload");
    const form = op.requestSchema["multipart/form-data"];
    expect(form.properties.file.format).toBe("binary");
    expect(form.properties.tags.type).toBe("array");
    expect(form.properties.count.minimum).toBe(1);
  });

  it("expands root-level allOf and additionalProperties and a typed parameter without a schema", () => {
    const spec = QipSpecificationGenerator.createQipSpecificationFromOpenApi(
      {
        openapi: "3.0.0",
        info: { title: "Edge", version: "1.0.0" },
        paths: {
          "/poly": {
            get: {
              operationId: "getPoly",
              parameters: [{ in: "query", name: "q", type: "string" }],
              responses: {
                "200": {
                  description: "OK",
                  content: {
                    "application/json": {
                      schema: {
                        allOf: [{ $ref: "#/components/schemas/Base" }],
                        additionalProperties: { type: "string" },
                      },
                    },
                  },
                },
              },
            },
          },
        },
        components: {
          schemas: { Base: { type: "object", properties: { id: { type: "string" } } } },
        },
      },
      "spec",
      "spec-edge",
    );
    const op = spec.content.operations[0];
    const response = op.responseSchemas["200"]["application/json"];
    expect(response.allOf[0].$ref).toBe("#/definitions/Base");
    expect(response.definitions.Base).toBeDefined();
  });

  it("rejects content that is neither OpenAPI 3.x nor Swagger 2.0", () => {
    expect(() =>
      QipSpecificationGenerator.createQipSpecificationFromOpenApi(
        { info: { version: "1.0.0" } },
        "spec",
        "spec-3",
      ),
    ).toThrow();
  });
});
