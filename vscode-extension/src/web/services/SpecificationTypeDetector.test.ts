import { SpecificationTypeDetector } from "./SpecificationTypeDetector";
import { ApiSpecificationType } from "../api-services/importApiTypes";

describe("SpecificationTypeDetector", () => {
  describe("parse", () => {
    it("parses JSON", () => {
      expect(SpecificationTypeDetector.parse('{"a":1}')).toEqual({ a: 1 });
    });
    it("falls back to YAML", () => {
      expect(SpecificationTypeDetector.parse("a: 1")).toEqual({ a: 1 });
    });
    it("returns null on invalid content", () => {
      expect(SpecificationTypeDetector.parse(":\n  - [")).toBeNull();
    });
  });

  describe("type recognition", () => {
    it("recognizes OpenAPI and Swagger", () => {
      expect(
        SpecificationTypeDetector.isOpenApiOrSwagger({ openapi: "3.1.0" }),
      ).toBe(true);
      expect(
        SpecificationTypeDetector.isOpenApiOrSwagger({ swagger: "2.0" }),
      ).toBe(true);
      expect(SpecificationTypeDetector.isOpenApiOrSwagger({})).toBe(false);
    });

    it("recognizes AsyncAPI", () => {
      expect(SpecificationTypeDetector.isAsyncApi({ asyncapi: "3.0.0" })).toBe(
        true,
      );
      expect(SpecificationTypeDetector.isAsyncApi({ asyncapi: 3 })).toBe(false);
    });

    it("detectStructuredType prioritizes AsyncAPI, then HTTP, else null", () => {
      expect(
        SpecificationTypeDetector.detectStructuredType({ asyncapi: "3.0.0" }),
      ).toBe(ApiSpecificationType.ASYNC);
      expect(
        SpecificationTypeDetector.detectStructuredType({ openapi: "3.0.0" }),
      ).toBe(ApiSpecificationType.HTTP);
      expect(SpecificationTypeDetector.detectStructuredType({})).toBeNull();
    });
  });

  describe("extractSpecVersion", () => {
    it("reads info.version for a recognized spec", () => {
      expect(
        SpecificationTypeDetector.extractSpecVersion({
          openapi: "3.0.0",
          info: { version: "2.5.0" },
        }),
      ).toBe("2.5.0");
    });
    it("falls back when no spec marker or version", () => {
      expect(SpecificationTypeDetector.extractSpecVersion({})).toBe("1.0.0");
      expect(
        SpecificationTypeDetector.extractSpecVersion({ openapi: "3.0.0" }),
      ).toBe("1.0.0");
    });
  });

  describe("async protocol", () => {
    it("reads info.x-protocol first", () => {
      expect(
        SpecificationTypeDetector.extractAsyncProtocolName({
          info: { "x-protocol": "kafka" },
          servers: { s: { protocol: "amqp" } },
        }),
      ).toBe("kafka");
    });
    it("reads servers.main before other servers", () => {
      expect(
        SpecificationTypeDetector.extractAsyncProtocolName({
          servers: { main: { protocol: "amqp" }, other: { protocol: "kafka" } },
        }),
      ).toBe("amqp");
    });
    it("falls back to the first server", () => {
      expect(
        SpecificationTypeDetector.extractAsyncProtocolName({
          servers: { only: { protocol: "kafka" } },
        }),
      ).toBe("kafka");
    });
    it("maps names to specification types", () => {
      expect(SpecificationTypeDetector.mapAsyncProtocolToType("kafka")).toBe(
        ApiSpecificationType.KAFKA,
      );
      expect(SpecificationTypeDetector.mapAsyncProtocolToType("RabbitMQ")).toBe(
        ApiSpecificationType.AMQP,
      );
      expect(SpecificationTypeDetector.mapAsyncProtocolToType("unknown")).toBe(
        ApiSpecificationType.ASYNC,
      );
      expect(SpecificationTypeDetector.mapAsyncProtocolToType(undefined)).toBe(
        ApiSpecificationType.ASYNC,
      );
    });
    it("detectAsyncProtocol resolves end-to-end", () => {
      expect(
        SpecificationTypeDetector.detectAsyncProtocol({
          asyncapi: "2.6.0",
          servers: { s: { protocol: "amqp" } },
        }),
      ).toBe(ApiSpecificationType.AMQP);
    });
  });
});
