jest.mock("vscode", () => ({ Uri: class Uri {} }), { virtual: true });
jest.mock("../../response/file/fileApiProvider", () => ({
  fileApi: {},
  setFileApi: jest.fn(),
}));

import * as fs from "fs";
import * as path from "path";
import { OperationSchemaExtractor } from "./OperationSchemaExtractor";
import { OpenApiSpecificationParser } from "./OpenApiSpecificationParser";

const ASYNCAPI_FIXTURES = path.resolve(
  __dirname,
  "../../../../tests/fixtures/asyncapi",
);

function loadAsyncApiFixture(rel: string): string {
  return fs.readFileSync(path.join(ASYNCAPI_FIXTURES, rel), "utf8");
}

const OPENAPI_CONTENT = JSON.stringify({
  openapi: "3.0.0",
  info: { title: "Pets", version: "1.0.0" },
  paths: {
    "/pets": {
      get: {
        operationId: "listPets",
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

const PROTO_CONTENT = `
syntax = "proto3";
package demo.v1;

message PingRequest {
  string message = 1;
}

message PingResponse {
  string reply = 1;
}

service PingService {
  rpc Ping (PingRequest) returns (PingResponse);
}
`;

const WSDL_SNIPPET = `<?xml version="1.0"?>
<definitions xmlns="http://schemas.xmlsoap.org/wsdl/" name="PingService">
</definitions>`;

const GRAPHQL_SNIPPET = `
type Query {
  ping: String
}
`;

describe("OperationSchemaExtractor", () => {
  describe("HTTP (OpenAPI)", () => {
    it("extracts the request/response schemas for a matched operation", async () => {
      const result = await OperationSchemaExtractor.extract(
        OPENAPI_CONTENT,
        undefined,
        "/pets",
        "GET",
      );

      expect(result.requestSchema).toEqual({});
      expect(
        (result.responseSchemas as any)["200"]["application/json"].properties
          .id.type,
      ).toBe("string");
    });

    it("degrades to empty schemas when no operation matches the path/method", async () => {
      const result = await OperationSchemaExtractor.extract(
        OPENAPI_CONTENT,
        undefined,
        "/pets",
        "DELETE",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });

    it("degrades to empty schemas instead of throwing on a corrupt source", async () => {
      const result = await OperationSchemaExtractor.extract(
        '{"openapi": "3.0.0", "paths": {',
        undefined,
        "/pets",
        "GET",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });
  });

  describe("AsyncAPI (Kafka)", () => {
    it("extracts the message payload for a matched channel/method", async () => {
      const content = loadAsyncApiFixture("v2/kafka-v2-inline-oneof.yaml");

      const result = await OperationSchemaExtractor.extract(
        content,
        undefined,
        "chat.presence",
        "publish",
      );

      expect(result.requestSchema).toEqual({});
      expect(Object.keys(result.responseSchemas).sort()).toEqual([
        "payload_0",
        "payload_1",
      ]);
    });

    it("returns the specification slice alongside the schemas", async () => {
      const content = loadAsyncApiFixture("v2/kafka-v2-inline-oneof.yaml");

      const result = await OperationSchemaExtractor.extract(
        content,
        undefined,
        "chat.presence",
        "publish",
      );

      expect(result.specification).toEqual({ topic: "chat.presence" });
    });

    it("degrades to empty schemas when the channel/method is not found", async () => {
      const content = loadAsyncApiFixture("v2/kafka-v2-inline-oneof.yaml");

      const result = await OperationSchemaExtractor.extract(
        content,
        undefined,
        "chat.presence",
        "subscribe",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });
  });

  describe("gRPC (protobuf)", () => {
    it("extracts request/response schemas keyed by java-package.Service + rpc method", async () => {
      const result = await OperationSchemaExtractor.extract(
        PROTO_CONTENT,
        undefined,
        "demo.v1.PingService",
        "Ping",
      );

      expect(result.requestSchema["application/json"]).toBeDefined();
      expect(
        (result.responseSchemas as any)["200"]["application/json"],
      ).toBeDefined();
    });

    it("degrades to empty schemas when the service/method is not found", async () => {
      const result = await OperationSchemaExtractor.extract(
        PROTO_CONTENT,
        undefined,
        "demo.v1.PingService",
        "Pong",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });
  });

  describe("no-schema protocols (SOAP, GraphQL) — null by design", () => {
    it("returns empty schemas for WSDL without attempting a parse", async () => {
      const result = await OperationSchemaExtractor.extract(
        WSDL_SNIPPET,
        undefined,
        "",
        "POST",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });

    it("returns empty schemas for unrecognized/GraphQL content", async () => {
      const result = await OperationSchemaExtractor.extract(
        GRAPHQL_SNIPPET,
        undefined,
        "ping",
        "query",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });
  });

  describe("format hint", () => {
    it("short-circuits to empty schemas when the hint names a no-schema protocol", async () => {
      // Even though the content is valid OpenAPI, a SOAP hint takes priority.
      const result = await OperationSchemaExtractor.extract(
        OPENAPI_CONTENT,
        "SOAP",
        "/pets",
        "GET",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });

    it("routes by hint without needing to sniff the content", async () => {
      const result = await OperationSchemaExtractor.extract(
        OPENAPI_CONTENT,
        "HTTP",
        "/pets",
        "GET",
      );

      expect(
        (result.responseSchemas as any)["200"]["application/json"].properties
          .id.type,
      ).toBe("string");
    });

    it("routes an AsyncAPI hint (KAFKA) to the async parser", async () => {
      const content = loadAsyncApiFixture("v2/kafka-v2-inline-oneof.yaml");

      const result = await OperationSchemaExtractor.extract(
        content,
        "KAFKA",
        "chat.presence",
        "publish",
      );

      expect(Object.keys(result.responseSchemas).sort()).toEqual([
        "payload_0",
        "payload_1",
      ]);
    });

    it("routes a gRPC hint to the proto parser", async () => {
      const result = await OperationSchemaExtractor.extract(
        PROTO_CONTENT,
        "GRPC",
        "demo.v1.PingService",
        "Ping",
      );

      expect(result.requestSchema["application/json"]).toBeDefined();
    });
  });

  describe("degradation after the protocol resolves", () => {
    it("returns empty schemas (never throws) when operation generation throws", async () => {
      const spy = jest
        .spyOn(OpenApiSpecificationParser, "createOperationsFromOpenApi")
        .mockImplementation(() => {
          throw new Error("boom");
        });

      try {
        const result = await OperationSchemaExtractor.extract(
          OPENAPI_CONTENT,
          "HTTP",
          "/pets",
          "GET",
        );

        expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
        expect(spy).toHaveBeenCalled();
      } finally {
        spy.mockRestore();
      }
    });
  });

  describe("missing or oversized source", () => {
    it("returns empty schemas when there is no raw source", async () => {
      const result = await OperationSchemaExtractor.extract(
        null,
        undefined,
        "/pets",
        "GET",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });

    it("returns empty schemas when the source exceeds the size backstop", async () => {
      const oversized = "a".repeat(5_000_001);

      const result = await OperationSchemaExtractor.extract(
        oversized,
        undefined,
        "/pets",
        "GET",
      );

      expect(result).toEqual({ specification: {}, requestSchema: {}, responseSchemas: {} });
    });
  });
});
