import {
  applySchemaToMapping,
  getMappingDirectionFromPath,
  hasExistingBodyForDirection,
  pickPrimaryRequestSchema,
  pickPrimaryResponseSchema,
  tryBuildDataTypeFromSchema,
} from "../../../src/mapper/util/auto-schema.ts";
import { MappingUtil } from "../../../src/mapper/util/mapping.ts";
import { DataTypes } from "../../../src/mapper/util/types.ts";
import type {
  MappingAction,
  MappingDescription,
  ObjectType,
} from "../../../src/mapper/model/model.ts";

describe("auto-schema", () => {
  describe("getMappingDirectionFromPath", () => {
    it("returns 'none' for empty path", () => {
      expect(getMappingDirectionFromPath([])).toBe("none");
      expect(getMappingDirectionFromPath(undefined)).toBe("none");
    });

    it("returns 'none' when last segment is not mappingDescription", () => {
      expect(
        getMappingDirectionFromPath(["properties", "before", "script"]),
      ).toBe("none");
    });

    it("returns 'request' for before.mappingDescription (service-call Prepare Request)", () => {
      expect(
        getMappingDirectionFromPath([
          "properties",
          "before",
          "mappingDescription",
        ]),
      ).toBe("request");
    });

    it("returns 'response' for after.items.mappingDescription (service-call Handle Response)", () => {
      expect(
        getMappingDirectionFromPath([
          "properties",
          "after",
          0,
          "mappingDescription",
        ]),
      ).toBe("response");
    });

    it("returns 'none' for handlerContainer.mappingDescription (http-trigger)", () => {
      expect(
        getMappingDirectionFromPath([
          "properties",
          "handlerContainer",
          "mappingDescription",
        ]),
      ).toBe("none");
    });

    it("returns 'none' for chainFailureHandlerContainer.mappingDescription", () => {
      expect(
        getMappingDirectionFromPath([
          "properties",
          "chainFailureHandlerContainer",
          "mappingDescription",
        ]),
      ).toBe("none");
    });

    it("returns 'none' for plain properties.mappingDescription (mapper-2)", () => {
      expect(
        getMappingDirectionFromPath(["properties", "mappingDescription"]),
      ).toBe("none");
    });

    it("tolerates numeric indexes in array paths", () => {
      expect(
        getMappingDirectionFromPath([
          "properties",
          "after",
          5,
          "items",
          "mappingDescription",
        ]),
      ).toBe("response");
    });
  });

  describe("pickPrimaryRequestSchema", () => {
    const jsonSchema = {
      type: "object",
      properties: { name: { type: "string" } },
    };
    const xmlSchema = {
      type: "object",
      properties: { Name: { type: "string" } },
    };

    it("returns null for undefined / null / empty inputs", () => {
      expect(pickPrimaryRequestSchema(undefined)).toBeNull();
      expect(pickPrimaryRequestSchema(null)).toBeNull();
      expect(pickPrimaryRequestSchema({})).toBeNull();
    });

    it("prefers application/json over other media types", () => {
      const result = pickPrimaryRequestSchema({
        "application/xml": xmlSchema,
        "application/json": jsonSchema,
      });
      expect(result).toEqual(jsonSchema);
    });

    it("falls back to application/xml when application/json is missing", () => {
      const result = pickPrimaryRequestSchema({
        "application/xml": xmlSchema,
        "text/plain": { type: "string" },
      });
      expect(result).toEqual(xmlSchema);
    });

    it("falls back to first entry when no known preferred media type is present", () => {
      const result = pickPrimaryRequestSchema({
        "text/plain": { type: "string" },
      });
      expect(result).toEqual({ type: "string" });
    });

    it("skips the reserved 'parameters' key", () => {
      const result = pickPrimaryRequestSchema({
        parameters: { type: "object" },
        "application/json": jsonSchema,
      });
      expect(result).toEqual(jsonSchema);
    });

    it("returns null when only 'parameters' is present", () => {
      expect(
        pickPrimaryRequestSchema({ parameters: { type: "object" } }),
      ).toBeNull();
    });

    it("ignores entries with empty schema objects", () => {
      const result = pickPrimaryRequestSchema({
        "application/json": {},
        "application/xml": xmlSchema,
      });
      expect(result).toEqual(xmlSchema);
    });
  });

  describe("pickPrimaryResponseSchema", () => {
    const ok = { type: "object", properties: { id: { type: "string" } } };
    const created = {
      type: "object",
      properties: { created: { type: "boolean" } },
    };
    const err = { type: "object", properties: { error: { type: "string" } } };

    it("returns null for empty / missing input", () => {
      expect(pickPrimaryResponseSchema(undefined)).toBeNull();
      expect(pickPrimaryResponseSchema(null)).toBeNull();
      expect(pickPrimaryResponseSchema({})).toBeNull();
    });

    it("prefers exact preferredCode when provided", () => {
      const result = pickPrimaryResponseSchema(
        {
          "200": { "application/json": ok },
          "404": { "application/json": err },
        },
        "404",
      );
      expect(result).toEqual(err);
    });

    it("prefers status 200 over other 2xx when no preferredCode", () => {
      const result = pickPrimaryResponseSchema({
        "201": { "application/json": created },
        "200": { "application/json": ok },
      });
      expect(result).toEqual(ok);
    });

    it("falls back to any 2xx when 200 missing", () => {
      const result = pickPrimaryResponseSchema({
        "500": { "application/json": err },
        "201": { "application/json": created },
      });
      expect(result).toEqual(created);
    });

    it("falls back to first entry when no success codes", () => {
      const result = pickPrimaryResponseSchema({
        "500": { "application/json": err },
      });
      expect(result).toEqual(err);
    });

    it("applies media-type priority inside the nested map", () => {
      const result = pickPrimaryResponseSchema({
        "200": {
          "application/xml": { type: "object", properties: {} },
          "application/json": ok,
        },
      });
      expect(result).toEqual(ok);
    });

    it("returns flat schema for async-style responseSchemas", () => {
      // Kafka/AMQP: `{ "message": { type: "object", … } }` — one level deep,
      // no media-type map.
      const result = pickPrimaryResponseSchema({
        message: ok,
      });
      expect(result).toEqual(ok);
    });
  });

  describe("tryBuildDataTypeFromSchema", () => {
    it("returns null for missing / empty schema", () => {
      expect(tryBuildDataTypeFromSchema(undefined)).toBeNull();
      expect(tryBuildDataTypeFromSchema(null)).toBeNull();
      expect(tryBuildDataTypeFromSchema({})).toBeNull();
      expect(tryBuildDataTypeFromSchema("")).toBeNull();
    });

    it("converts a simple JSON Schema object into an ObjectType", () => {
      const dataType = tryBuildDataTypeFromSchema({
        type: "object",
        properties: {
          id: { type: "string" },
          age: { type: "integer" },
        },
        required: ["id"],
      });
      expect(dataType).not.toBeNull();
      expect(dataType?.name).toBe("object");
      const object = dataType as ObjectType;
      const names = object.schema.attributes.map((a) => a.name).sort();
      expect(names).toEqual(["age", "id"]);
      const idAttr = object.schema.attributes.find((a) => a.name === "id");
      expect(idAttr?.required).toBe(true);
    });

    it("accepts string input already serialized to JSON", () => {
      const dataType = tryBuildDataTypeFromSchema(
        JSON.stringify({ type: "string" }),
      );
      expect(dataType?.name).toBe("string");
    });

    it("returns null (without throwing) for invalid schema-like objects", () => {
      // Simulate unsupported JSON-schema constructs (importDataType throws).
      const warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
      const dataType = tryBuildDataTypeFromSchema({
        type: "any",
      });
      expect(dataType).toBeNull();
      expect(warnSpy).toHaveBeenCalled();
      warnSpy.mockRestore();
    });

    it("infers implicit type=object for schema with properties but no type field", () => {
      // Real-world case: backend-emitted OpenAPI schemas often omit `type`
      // on object nodes that have `properties`. Without normalization the
      // importer produces `NullType`, which shows an empty body in the mapper.
      const dataType = tryBuildDataTypeFromSchema({
        $schema: "http://json-schema.org/draft-07/schema#",
        properties: {
          id: { type: "string" },
          count: { type: "integer" },
        },
        required: ["id"],
      });
      expect(dataType).not.toBeNull();
      expect(dataType?.name).toBe("object");
      const object = dataType as ObjectType;
      const names = object.schema.attributes.map((a) => a.name).sort();
      expect(names).toEqual(["count", "id"]);
    });

    it("infers implicit type=object recursively inside nested properties", () => {
      const dataType = tryBuildDataTypeFromSchema({
        properties: {
          user: {
            properties: {
              name: { type: "string" },
            },
          },
        },
      });
      expect(dataType?.name).toBe("object");
      const userProp = (dataType as ObjectType).schema.attributes.find(
        (a) => a.name === "user",
      );
      expect(userProp?.type.name).toBe("object");
    });

    it("infers implicit type=array for schema with items but no type field", () => {
      const dataType = tryBuildDataTypeFromSchema({
        items: { type: "string" },
      });
      expect(dataType?.name).toBe("array");
    });

    it("resolves $ref pointing to #/components/schemas/… via definitions bridge", () => {
      // Real-world backend shape: references use OpenAPI paths,
      // but the registry lives under `definitions`.
      const dataType = tryBuildDataTypeFromSchema({
        properties: {
          author: { $ref: "#/components/schemas/User" },
        },
        definitions: {
          User: {
            type: "object",
            properties: {
              name: { type: "string" },
            },
          },
        },
      });
      expect(dataType?.name).toBe("object");
    });

    it("replaces unresolved $refs with a permissive object so the rest imports cleanly", () => {
      const warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
      // Unresolved $ref (e.g. backend didn't bundle `Unknown`) should not
      // derail the whole import — sibling properties still come through.
      const dataType = tryBuildDataTypeFromSchema({
        properties: {
          first: { type: "string" },
          broken: { $ref: "#/components/schemas/Unknown" },
        },
      });
      expect(dataType?.name).toBe("object");
      const obj = dataType as ObjectType;
      const names = obj.schema.attributes.map((a) => a.name).sort();
      expect(names).toEqual(["broken", "first"]);
      warnSpy.mockRestore();
    });
  });

  describe("applySchemaToMapping", () => {
    const dataType = DataTypes.stringType();

    it("sets target.body for 'request' direction", () => {
      const next = applySchemaToMapping(
        MappingUtil.emptyMapping(),
        "request",
        dataType,
      );
      expect(next.target.body).toEqual(dataType);
      expect(next.source.body).toBeNull();
    });

    it("sets source.body for 'response' direction", () => {
      const next = applySchemaToMapping(
        MappingUtil.emptyMapping(),
        "response",
        dataType,
      );
      expect(next.source.body).toEqual(dataType);
      expect(next.target.body).toBeNull();
    });

    it("returns a new object and does not mutate the input", () => {
      const input = MappingUtil.emptyMapping();
      const snapshot = JSON.parse(JSON.stringify(input)) as MappingDescription;
      const next = applySchemaToMapping(input, "request", dataType);
      expect(next).not.toBe(input);
      expect(input).toEqual(snapshot);
    });

    it("drops actions that target the replaced body (request)", () => {
      const base: MappingDescription = {
        ...MappingUtil.emptyMapping(),
        actions: [
          {
            id: "a1",
            sources: [
              {
                type: "attribute",
                kind: "property",
                path: ["foo"],
              },
            ],
            target: { type: "attribute", kind: "body", path: ["x"] },
          } as unknown as MappingAction,
          {
            id: "a2",
            sources: [
              {
                type: "attribute",
                kind: "property",
                path: ["bar"],
              },
            ],
            target: { type: "attribute", kind: "property", path: ["baz"] },
          } as unknown as MappingAction,
        ],
      };
      const next = applySchemaToMapping(base, "request", dataType);
      expect(next.actions.map((a) => a.id)).toEqual(["a2"]);
    });

    it("drops actions whose source references body on response direction", () => {
      const base: MappingDescription = {
        ...MappingUtil.emptyMapping(),
        actions: [
          {
            id: "a1",
            sources: [{ type: "attribute", kind: "body", path: ["foo"] }],
            target: { type: "attribute", kind: "property", path: ["x"] },
          } as unknown as MappingAction,
          {
            id: "a2",
            sources: [{ type: "attribute", kind: "header", path: ["bar"] }],
            target: { type: "attribute", kind: "property", path: ["y"] },
          } as unknown as MappingAction,
        ],
      };
      const next = applySchemaToMapping(base, "response", dataType);
      expect(next.actions.map((a) => a.id)).toEqual(["a2"]);
    });

    it("falls back to empty mapping for null/undefined input", () => {
      const next = applySchemaToMapping(null, "request", dataType);
      expect(next.target.body).toEqual(dataType);
      expect(next.source.body).toBeNull();
    });
  });

  describe("hasExistingBodyForDirection", () => {
    it("returns false for missing mapping", () => {
      expect(hasExistingBodyForDirection(null, "request")).toBe(false);
      expect(hasExistingBodyForDirection(undefined, "response")).toBe(false);
    });

    it("returns false when the relevant side body is null", () => {
      expect(
        hasExistingBodyForDirection(MappingUtil.emptyMapping(), "request"),
      ).toBe(false);
      expect(
        hasExistingBodyForDirection(MappingUtil.emptyMapping(), "response"),
      ).toBe(false);
    });

    it("returns true when target.body is populated (request direction)", () => {
      const mapping: MappingDescription = {
        ...MappingUtil.emptyMapping(),
        target: {
          ...MappingUtil.emptyMessageSchema(),
          body: DataTypes.stringType(),
        },
      };
      expect(hasExistingBodyForDirection(mapping, "request")).toBe(true);
      expect(hasExistingBodyForDirection(mapping, "response")).toBe(false);
    });

    it("returns true when source.body is populated (response direction)", () => {
      const mapping: MappingDescription = {
        ...MappingUtil.emptyMapping(),
        source: {
          ...MappingUtil.emptyMessageSchema(),
          body: DataTypes.stringType(),
        },
      };
      expect(hasExistingBodyForDirection(mapping, "response")).toBe(true);
      expect(hasExistingBodyForDirection(mapping, "request")).toBe(false);
    });
  });
});
