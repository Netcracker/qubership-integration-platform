jest.mock("vscode", () => ({ Uri: class Uri {} }), { virtual: true });
jest.mock("../../response/file/fileApiProvider", () => ({
  fileApi: {},
  setFileApi: jest.fn(),
}));

import { OpenApiSpecificationParser } from "./OpenApiSpecificationParser";

const OPENAPI_30 = JSON.stringify({
  openapi: "3.0.0",
  info: { title: "Test 3.0", version: "1.0.0" },
  paths: {
    "/pets": {
      get: { operationId: "listPets", responses: { "200": { description: "OK" } } },
    },
  },
});

// Mirrors the backend SwaggerSpecificationParser31Test fixture.
const OPENAPI_31 = JSON.stringify({
  openapi: "3.1.0",
  info: { title: "Test", version: "1.0.0" },
  paths: {
    "/things": {
      post: {
        operationId: "createThing",
        requestBody: {
          required: true,
          content: {
            "application/json": {
              schema: { $ref: "#/components/schemas/ThingRequest" },
            },
          },
        },
        responses: {
          "200": {
            description: "OK",
            content: {
              "application/json": {
                schema: { $ref: "#/components/schemas/ThingResponse" },
              },
            },
          },
        },
      },
    },
  },
  components: {
    schemas: {
      ThingRequest: {
        properties: {
          name: { type: "string" },
          labels: {
            type: "array",
            items: { $ref: "#/components/schemas/LabelDTO" },
          },
        },
      },
      ThingResponse: { properties: { id: { type: "string" } } },
      LabelDTO: { properties: { name: { type: "string" } } },
    },
  },
});

// Mirrors the backend SwaggerSpecificationParser32Test fixture.
const OPENAPI_32 = JSON.stringify({
  openapi: "3.2.0",
  info: { title: "Test 3.2", version: "1.0.0" },
  paths: {
    "/things": {
      post: {
        operationId: "createThing",
        requestBody: {
          content: {
            "application/json": {
              schema: { $ref: "#/components/schemas/ThingRequest" },
            },
          },
        },
        responses: { "200": { description: "OK" } },
      },
    },
    "/search": {
      query: {
        operationId: "queryThings",
        responses: { "200": { description: "OK" } },
      },
    },
  },
  components: {
    schemas: {
      ThingRequest: {
        properties: {
          name: { type: "string" },
          count: { type: ["integer", "null"] },
        },
      },
    },
  },
});

const SWAGGER_20 = JSON.stringify({
  swagger: "2.0",
  info: { title: "Legacy", version: "1.0.0" },
  host: "api.example.com",
  basePath: "/v1",
  schemes: ["https"],
  paths: {
    "/users": {
      get: { operationId: "listUsers", responses: { "200": { description: "OK" } } },
    },
  },
});

const OPENAPI_TRACE = JSON.stringify({
  openapi: "3.1.0",
  info: { title: "Trace", version: "1.0.0" },
  paths: {
    "/debug": {
      trace: { operationId: "traceDebug", responses: { "200": { description: "OK" } } },
    },
  },
});

async function operationsFor(content: string, warnings?: string[]) {
  const data = await OpenApiSpecificationParser.parseOpenApiContent(
    content,
    warnings,
  );
  return OpenApiSpecificationParser.createOperationsFromOpenApi(data, "spec-1");
}

describe("OpenApiSpecificationParser", () => {
  it("imports an OpenAPI 3.0 operation", async () => {
    const operations = await operationsFor(OPENAPI_30);
    expect(operations).toHaveLength(1);
    expect(operations[0]).toMatchObject({ name: "listPets", method: "GET", path: "/pets" });
  });

  it("preserves leaf types and inlines $refs into definitions (3.1)", async () => {
    const operations = await operationsFor(OPENAPI_31);
    expect(operations).toHaveLength(1);
    const request = operations[0].requestSchema["application/json"];
    expect(request.properties.name.type).toBe("string");
    expect(request.properties.labels.type).toBe("array");
    expect(request.properties.labels.items.$ref).toBe("#/definitions/LabelDTO");
    expect(request.definitions.LabelDTO.properties.name.type).toBe("string");

    const response =
      operations[0].responseSchemas["200"]["application/json"];
    expect(response.properties.id.type).toBe("string");
  });

  describe("OpenAPI 3.2 bridge", () => {
    it("warns that 3.2 is imported through the 3.1 parser", async () => {
      const warnings: string[] = [];
      await operationsFor(OPENAPI_32, warnings);
      expect(warnings.some((w) => w.includes("3.2"))).toBe(true);
    });

    it("rewrites the version to 3.1.0", async () => {
      const data = await OpenApiSpecificationParser.parseOpenApiContent(
        OPENAPI_32,
      );
      expect((data as any).openapi).toBe("3.1.0");
    });

    it("drops the 3.2-only QUERY operation and keeps path operations", async () => {
      const operations = await operationsFor(OPENAPI_32);
      const names = operations.map((o) => o.name);
      expect(names).toContain("createThing");
      expect(names).not.toContain("queryThings");
    });

    it("preserves multi-type arrays", async () => {
      const operations = await operationsFor(OPENAPI_32);
      const createThing = operations.find((o) => o.name === "createThing");
      const countType =
        createThing.requestSchema["application/json"].properties.count.type;
      expect(Array.isArray(countType)).toBe(true);
      expect(countType).toEqual(expect.arrayContaining(["integer", "null"]));
    });
  });

  it("imports the TRACE method (3.0/3.1)", async () => {
    const operations = await operationsFor(OPENAPI_TRACE);
    expect(operations.map((o) => o.name)).toContain("traceDebug");
  });

  it("converts Swagger 2.0 and imports operations", async () => {
    const operations = await operationsFor(SWAGGER_20);
    expect(operations.map((o) => o.name)).toContain("listUsers");
  });

  describe("extractAddressFromOpenApiData", () => {
    it("builds the address from Swagger host/basePath/schemes", () => {
      expect(
        OpenApiSpecificationParser.extractAddressFromOpenApiData({
          swagger: "2.0",
          host: "api.example.com",
          basePath: "/v1",
          schemes: ["https"],
        } as any),
      ).toBe("https://api.example.com/v1");
    });

    it("reads the first server url for OpenAPI 3.x", () => {
      expect(
        OpenApiSpecificationParser.extractAddressFromOpenApiData({
          openapi: "3.0.0",
          servers: [{ url: "https://api.example.com" }],
        } as any),
      ).toBe("https://api.example.com");
    });

    it("returns null when no address can be derived", () => {
      expect(
        OpenApiSpecificationParser.extractAddressFromOpenApiData({
          openapi: "3.0.0",
        } as any),
      ).toBeNull();
    });
  });

  it("rejects content that is neither OpenAPI nor Swagger", async () => {
    await expect(
      OpenApiSpecificationParser.parseOpenApiContent(
        JSON.stringify({ info: { title: "x", version: "1" } }),
      ),
    ).rejects.toThrow();
  });
});
