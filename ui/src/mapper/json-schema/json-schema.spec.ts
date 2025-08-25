import { DataType, ObjectSchema, TypeDefinition } from "../model/model.ts";
import { exportAsJsonSchema } from "./json-schema.ts";

describe("Mapper", () => {
  describe("exportAsJsonSchema", () => {
    it("should return schema that contains metadata for type", () => {
      const type: DataType = {
        name: "string",
        metadata: { description: "foo", examples: ["bar", "baz"] },
      };
      expect(exportAsJsonSchema(type, [])).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        type: "string",
        description: "foo",
        examples: ["bar", "baz"],
      });
    });

    it("should return proper schema for string type", () => {
      expect(exportAsJsonSchema({ name: "string" }, [])).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        type: "string",
      });
    });

    it("should return proper schema for number type", () => {
      expect(exportAsJsonSchema({ name: "number" }, [])).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        type: "number",
      });
    });

    it("should return proper schema for boolean type", () => {
      expect(exportAsJsonSchema({ name: "boolean" }, [])).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        type: "boolean",
      });
    });

    it("should return proper schema for null type", () => {
      expect(exportAsJsonSchema({ name: "null" }, [])).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        type: "null",
      });
    });

    it("should return proper schema for reference type", () => {
      const definitions: TypeDefinition[] = [
        { id: "foo", name: "bar", type: { name: "string" } },
      ];
      const type: DataType = {
        name: "reference",
        definitionId: "foo",
        definitions,
      };
      expect(
        exportAsJsonSchema(type, [
          { id: "baz", name: "biz", type: { name: "number" } },
        ]),
      ).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        $ref: "#/definitions/bar",
        definitions: {
          bar: { type: "string" },
          biz: { type: "number" },
        },
      });
    });

    it("should return proper schema for array type", () => {
      const type: DataType = { name: "array", itemType: { name: "string" } };
      expect(exportAsJsonSchema(type, [])).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        type: "array",
        items: { type: "string" },
      });
    });

    it("should return proper schema for compound type", () => {
      ["allOf" as const, "anyOf" as const, "oneOf" as const].forEach((name) => {
        const type: DataType = {
          name,
          types: [{ name: "string" }, { name: "boolean" }],
        };
        expect(exportAsJsonSchema(type, [])).toEqual({
          $schema: "https://json-schema.org/draft/2019-09/schema",
          [name]: [{ type: "string" }, { type: "boolean" }],
        });
      });
    });

    it("should return proper schema for object type", () => {
      const schema: ObjectSchema = {
        id: "foo",
        attributes: [
          { id: "bar", name: "bar", type: { name: "string" }, required: true },
          {
            id: "baz",
            name: "baz",
            type: { name: "number" },
            defaultValue: "42",
          },
        ],
      };
      const type: DataType = { name: "object", schema };
      expect(exportAsJsonSchema(type, [])).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        type: "object",
        properties: {
          bar: { type: "string" },
          baz: { type: "number", default: "42" },
        },
        required: ["bar"],
      });
    });

    it("should return schema with properly located and referenced definitions", () => {
      const schema: ObjectSchema = {
        id: "baz",
        attributes: [
          {
            id: "fiz",
            name: "qux",
            type: {
              name: "reference",
              definitionId: "a",
              definitions: [
                {
                  id: "a",
                  name: "a",
                  type: { name: "reference", definitionId: "bar" },
                },
              ],
            },
          },
        ],
      };
      const type: DataType = {
        name: "reference",
        definitionId: "foo",
        definitions: [
          {
            id: "foo",
            name: "bar",
            type: {
              name: "object",
              schema,
              definitions: [
                { id: "bar", name: "baz", type: { name: "string" } },
              ],
            },
          },
        ],
      };
      const definitions: TypeDefinition[] = [
        { id: "b", name: "bool", type: { name: "boolean" } },
      ];
      expect(exportAsJsonSchema(type, definitions)).toEqual({
        $schema: "https://json-schema.org/draft/2019-09/schema",
        $ref: "#/definitions/bar",
        definitions: {
          bar: {
            type: "object",
            properties: {
              qux: {
                $ref: "#/definitions/bar/properties/qux/definitions/a",
                definitions: {
                  a: {
                    $ref: "#/definitions/bar/definitions/baz",
                  },
                },
              },
            },
            definitions: {
              baz: { type: "string" },
            },
            required: [],
          },
          bool: { type: "boolean" },
        },
      });
    });
  });
});
