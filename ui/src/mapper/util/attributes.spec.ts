import { Attributes } from "./attributes.ts";
import {
  Attribute,
  DataType,
  ObjectSchema,
  TypeDefinition,
} from "../model/model.ts";

describe("Mapper", () => {
  describe("Attributes", () => {
    describe("buildAttribute", () => {
      it("should build an attribute from specified arguments", () => {
        expect(
          Attributes.buildAttribute(
            "foo",
            "bar",
            { name: "number" },
            "42",
            true,
          ),
        ).toEqual({
          id: "foo",
          name: "bar",
          type: { name: "number" },
          defaultValue: "42",
          required: true,
        });
      });
    });

    describe("wrapType", () => {
      it("should return an attribute that has the specified type", () => {
        expect(Attributes.wrapType({ name: "number" })).toEqual({
          id: "",
          name: "",
          type: { name: "number" },
          defaultValue: undefined,
          required: undefined,
        });
      });
    });

    describe("extractTypeDefinitions", () => {
      it("should return an empty list when the attributes list is undefined or null", () => {
        ([undefined, null] as unknown as Attribute[]).forEach(
          (attributes: Attribute[]) => {
            expect(
              Attributes.extractTypeDefinitions(attributes),
              String(attributes as unknown as undefined | null),
            ).toEqual([]);
          },
        );
      });

      it("should return an empty list when the attributes list is empty", () => {
        expect(Attributes.extractTypeDefinitions([])).toEqual([]);
      });

      it("should return type definitions collected from attributes types", () => {
        const attributes: Attribute[] = [
          { id: "a1", name: "a1", type: { name: "string" } },
          {
            id: "a1",
            name: "a1",
            type: {
              name: "object",
              schema: { id: "", attributes: [] },
              definitions: [
                { id: "d1", name: "d1", type: { name: "string" } },
                { id: "d2", name: "d2", type: { name: "number" } },
              ],
            },
          },
          { id: "a1", name: "a1", type: undefined },
          {
            id: "a1",
            name: "a1",
            type: {
              name: "object",
              schema: { id: "", attributes: [] },
              definitions: [
                { id: "d3", name: "d3", type: { name: "boolean" } },
              ],
            },
          },
        ];
        expect(Attributes.extractTypeDefinitions(attributes)).toEqual([
          { id: "d1", name: "d1", type: { name: "string" } },
          { id: "d2", name: "d2", type: { name: "number" } },
          { id: "d3", name: "d3", type: { name: "boolean" } },
        ]);
      });
    });

    describe("resolveAttributeType", () => {
      it("should return undefined value when attribute is undefined or null", () => {
        [undefined, null].forEach((attribute: Attribute) => {
          expect(
            Attributes.resolveAttributeType(attribute, []),
          ).toBeUndefined();
        });
      });

      it("should return undefined value when attribute type is undefined", () => {
        expect(
          Attributes.resolveAttributeType(
            { id: "foo", name: "bar", type: undefined },
            [],
          ),
        ).toBeUndefined();
      });

      it("should return an resolved attribute type", () => {
        const typeDefinitions: TypeDefinition[] = [
          { id: "foo", name: "bar", type: { name: "number" } },
        ];
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: { name: "reference", definitionId: "foo" },
        };
        expect(
          Attributes.resolveAttributeType(attribute, typeDefinitions),
        ).toEqual({ name: "number" });
      });

      it("should return an array item type if attributes type is an array type", () => {
        expect(
          Attributes.resolveAttributeType(
            {
              id: "foo",
              name: "bar",
              type: { name: "array", itemType: { name: "string" } },
            },
            [],
          ),
        ).toEqual({ name: "string" });
      });
    });

    describe("resolveAttributeSchema", () => {
      it("should return null value when attribute is undefined or null", () => {
        [undefined, null].forEach((attribute: Attribute) => {
          expect(Attributes.resolveAttributeSchema(attribute, [])).toBeNull();
        });
      });

      it("should return null value when attribute type is undefined", () => {
        expect(
          Attributes.resolveAttributeSchema(
            { id: "foo", name: "bar", type: undefined },
            [],
          ),
        ).toBeNull();
      });

      it("should return null value when resolved attribute type is not ObjectType", () => {
        expect(
          Attributes.resolveAttributeSchema(
            { id: "foo", name: "bar", type: { name: "string" } },
            [],
          ),
        ).toBeNull();
      });

      it("should return a schema for resolved attribute type when type is ObjectType", () => {
        const schema: ObjectSchema = {
          id: "schema-id",
          attributes: [{ id: "a", name: "a", type: { name: "string" } }],
        };
        const typeDefinitions: TypeDefinition[] = [
          { id: "foo", name: "bar", type: { name: "object", schema } },
        ];
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: { name: "reference", definitionId: "foo" },
        };
        expect(
          Attributes.resolveAttributeSchema(attribute, typeDefinitions),
        ).toEqual(schema);
      });

      it("should return a schema for array item type when attribute type is an array type", () => {
        const schema: ObjectSchema = {
          id: "schema-id",
          attributes: [{ id: "a", name: "a", type: { name: "string" } }],
        };
        const typeDefinitions: TypeDefinition[] = [
          { id: "foo", name: "bar", type: { name: "object", schema } },
        ];
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: {
            name: "array",
            itemType: { name: "reference", definitionId: "foo" },
          },
        };
        expect(
          Attributes.resolveAttributeSchema(attribute, typeDefinitions),
        ).toEqual(schema);
      });
    });

    describe("getChildAttributes", () => {
      it("should return an empty list when the attribute is undefined or null", () => {
        [undefined, null].forEach((attribute: Attribute) => {
          expect(Attributes.getChildAttributes(attribute, [])).toEqual([]);
        });
      });

      it("should return an empty list when the resolved attribute type is not ObjectType", () => {
        const typeDefinitions: TypeDefinition[] = [
          { id: "foo", name: "bar", type: { name: "string" } },
        ];
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: { name: "reference", definitionId: "foo" },
        };
        expect(
          Attributes.getChildAttributes(attribute, typeDefinitions),
        ).toEqual([]);
      });

      it("should return attributes from an attribute schema when the resolved attribute type is ObjectType", () => {
        const schema: ObjectSchema = {
          id: "schema-id",
          attributes: [
            { id: "a", name: "a", type: { name: "string" } },
            { id: "b", name: "b", type: { name: "number" } },
          ],
        };
        const typeDefinitions: TypeDefinition[] = [
          { id: "foo", name: "bar", type: { name: "object", schema } },
        ];
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: { name: "reference", definitionId: "foo" },
        };
        expect(
          Attributes.getChildAttributes(attribute, typeDefinitions),
        ).toEqual(schema.attributes);
      });

      it("should return attributes from all nested types schemas when the resolved attribute type is a compound type", () => {
        ["anyOf", "oneOf", "allOf"].forEach((typeName) => {
          const schema1: ObjectSchema = {
            id: "schema1-id",
            attributes: [
              { id: "a", name: "a", type: { name: "string" } },
              { id: "b", name: "b", type: { name: "number" } },
            ],
          };
          const schema2: ObjectSchema = {
            id: "schema2-id",
            attributes: [{ id: "c", name: "c", type: { name: "string" } }],
          };
          const attribute: Attribute = {
            id: "baz",
            name: "qux",
            type: {
              name: typeName,
              types: [
                { name: "object", schema: schema1 },
                { name: "string" },
                { name: "object", schema: schema2 },
              ],
            } as DataType,
          };
          expect(
            Attributes.getChildAttributes(attribute, []),
            typeName,
          ).toEqual([...schema1.attributes, ...schema2.attributes]);
        });
      });

      it("should return attributes from array item type schema when the resolved attribute type is an array type", () => {
        const schema: ObjectSchema = {
          id: "schema-id",
          attributes: [
            { id: "a", name: "a", type: { name: "string" } },
            { id: "b", name: "b", type: { name: "number" } },
          ],
        };
        const typeDefinitions: TypeDefinition[] = [
          { id: "foo", name: "bar", type: { name: "object", schema } },
        ];
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: {
            name: "array",
            itemType: { name: "reference", definitionId: "foo" },
          },
        };
        expect(
          Attributes.getChildAttributes(attribute, typeDefinitions),
        ).toEqual(schema.attributes);
      });
    });

    describe("restorePath", () => {
      it("should return null when attribute is null or undefined", () => {
        [undefined, null].forEach((attribute: Attribute) => {
          expect(Attributes.restorePath(attribute, ["foo"])).toBeNull();
        });
      });

      it("should return empty list when the path is empty", () => {
        const attribute: Attribute = {
          id: "foo",
          name: "bar",
          type: {
            name: "object",
            schema: {
              id: "schema1-id",
              attributes: [
                { id: "a", name: "a", type: { name: "string" } },
                { id: "b", name: "b", type: { name: "number" } },
              ],
            },
          },
        };
        expect(Attributes.restorePath(attribute, [])).toEqual([]);
      });

      it("should return attribute list that corresponds to a path", () => {
        const leafAttribute: Attribute = {
          id: "c",
          name: "c",
          type: { name: "string" },
        };
        const nestedAttribute: Attribute = {
          id: "b",
          name: "b",
          type: {
            name: "object",
            schema: {
              id: "schema2-id",
              attributes: [leafAttribute],
            },
          },
        };
        const attribute: Attribute = {
          id: "foo",
          name: "bar",
          type: {
            name: "object",
            schema: {
              id: "schema1-id",
              attributes: [
                { id: "a", name: "a", type: { name: "string" } },
                nestedAttribute,
              ],
            },
          },
        };
        expect(Attributes.restorePath(attribute, ["b", "c"])).toEqual([
          nestedAttribute,
          leafAttribute,
        ]);
      });

      it("should return null when the path not exists", () => {
        const leafAttribute: Attribute = {
          id: "c",
          name: "c",
          type: { name: "string" },
        };
        const nestedAttribute: Attribute = {
          id: "b",
          name: "b",
          type: {
            name: "object",
            schema: {
              id: "schema2-id",
              attributes: [leafAttribute],
            },
          },
        };
        const attribute: Attribute = {
          id: "foo",
          name: "bar",
          type: {
            name: "object",
            schema: {
              id: "schema1-id",
              attributes: [
                { id: "a", name: "a", type: { name: "string" } },
                nestedAttribute,
              ],
            },
          },
        };
        expect(Attributes.restorePath(attribute, ["b", "d"])).toBeNull();
      });
    });

    describe("pathExists", () => {
      it("should return false when attribute is null or undefined", () => {
        [undefined, null].forEach((attribute: Attribute) => {
          expect(Attributes.pathExists(attribute, ["foo"])).toBeFalsy();
        });
      });

      it("should return true when specified path exists", () => {
        const nestedAttribute: Attribute = {
          id: "b",
          name: "b",
          type: {
            name: "object",
            schema: {
              id: "schema2-id",
              attributes: [{ id: "c", name: "c", type: { name: "string" } }],
            },
          },
        };
        const attribute: Attribute = {
          id: "foo",
          name: "bar",
          type: {
            name: "object",
            schema: {
              id: "schema1-id",
              attributes: [
                { id: "a", name: "a", type: { name: "string" } },
                nestedAttribute,
              ],
            },
          },
        };
        expect(Attributes.pathExists(attribute, ["b", "c"])).toBeTruthy();
      });

      it("should return true when specified path does not exists", () => {
        const nestedAttribute: Attribute = {
          id: "b",
          name: "b",
          type: {
            name: "object",
            schema: {
              id: "schema2-id",
              attributes: [{ id: "c", name: "c", type: { name: "string" } }],
            },
          },
        };
        const attribute: Attribute = {
          id: "foo",
          name: "bar",
          type: {
            name: "object",
            schema: {
              id: "schema1-id",
              attributes: [
                { id: "a", name: "a", type: { name: "string" } },
                nestedAttribute,
              ],
            },
          },
        };
        expect(Attributes.pathExists(attribute, ["b", "d"])).toBeFalsy();
      });
    });

    describe("attributeSchemaPresentsInPath", () => {
      it("should return false when the attribute is undefined or null", () => {
        [undefined, null].forEach((attribute: Attribute) => {
          expect(
            Attributes.attributeSchemaPresentsInPath(
              attribute,
              [{ id: "foo", name: "bar", type: { name: "string" } }],
              [],
            ),
          ).toBeFalsy();
        });
      });

      it("should return false when the resolved attribute type is not ObjectType", () => {
        const typeDefinitions: TypeDefinition[] = [
          { id: "foo", name: "bar", type: { name: "string" } },
        ];
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: { name: "reference", definitionId: "foo" },
        };
        expect(
          Attributes.attributeSchemaPresentsInPath(
            attribute,
            [{ id: "foo", name: "bar", type: { name: "string" } }],
            typeDefinitions,
          ),
        ).toBeFalsy();
      });

      it("should return false when the path is empty", () => {
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: { name: "object", schema: { id: "foo", attributes: [] } },
        };
        expect(
          Attributes.attributeSchemaPresentsInPath(attribute, [], []),
        ).toBeFalsy();
      });

      it("should return false when the attribute schema is not match any attribute schema in path", () => {
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: { name: "object", schema: { id: "foo", attributes: [] } },
        };
        const path: Attribute[] = [
          {
            id: "foo",
            name: "bar",
            type: { name: "object", schema: { id: "fix", attributes: [] } },
          },
          { id: "a", name: "b", type: { name: "string" } },
        ];
        expect(
          Attributes.attributeSchemaPresentsInPath(attribute, path, []),
        ).toBeFalsy();
      });

      it("should return true when the attribute schema matches to schema of some attribute in path", () => {
        const typeDefinitions: TypeDefinition[] = [
          {
            id: "foo",
            name: "foo",
            type: { name: "object", schema: { id: "foo", attributes: [] } },
          },
        ];
        const attribute: Attribute = {
          id: "baz",
          name: "qux",
          type: { name: "reference", definitionId: "foo" },
        };
        const path: Attribute[] = [
          {
            id: "foo",
            name: "bar",
            type: { name: "object", schema: { id: "foo", attributes: [] } },
          },
          { id: "a", name: "b", type: { name: "string" } },
        ];
        expect(
          Attributes.attributeSchemaPresentsInPath(
            attribute,
            path,
            typeDefinitions,
          ),
        ).toBeTruthy();
      });
    });

    describe("walk", () => {
      const attribute1: Attribute = {
        id: "foo",
        name: "foo",
        type: { name: "string" },
      };
      const attribute2: Attribute = {
        id: "bar",
        name: "bar",
        type: { name: "number" },
      };
      const attribute3: Attribute = {
        id: "baz",
        name: "baz",
        type: {
          name: "object",
          schema: { id: "schema1", attributes: [attribute1, attribute2] },
        },
      };
      const attribute4: Attribute = {
        id: "biz",
        name: "biz",
        type: {
          name: "object",
          schema: { id: "schema2", attributes: [attribute3] },
        },
      };

      it("should call specified function for all attributes", () => {
        const walkFn = jest.fn();
        Attributes.walk(attribute4, walkFn, []);
        expect(walkFn).toHaveBeenCalledTimes(4);
        expect(walkFn).toHaveBeenCalledWith(attribute4, [attribute4]);
        expect(walkFn).toHaveBeenCalledWith(attribute3, [
          attribute4,
          attribute3,
        ]);
        expect(walkFn).toHaveBeenCalledWith(attribute2, [
          attribute4,
          attribute3,
          attribute2,
        ]);
        expect(walkFn).toHaveBeenCalledWith(attribute1, [
          attribute4,
          attribute3,
          attribute1,
        ]);
      });

      it("should return a function result when it truthy", () => {
        const walkFn = jest.fn((a: Attribute, p: Attribute[]) => [a, p]);
        expect(Attributes.walk(attribute4, walkFn, [])).toEqual([
          attribute4,
          [attribute4],
        ]);
        expect(walkFn.mock.calls).toEqual([[attribute4, [attribute4]]]);
      });

      it("should not walk inside a schema that already present in the path", () => {
        const attribute = { id: "a", name: "a", type: attribute3.type };
        const walkFn = jest.fn();
        Attributes.walk(attribute4, walkFn, [attribute]);
        expect(walkFn).toHaveBeenCalledTimes(2);
        expect(walkFn).toHaveBeenCalledWith(attribute4, [
          attribute,
          attribute4,
        ]);
        expect(walkFn).toHaveBeenCalledWith(attribute3, [
          attribute,
          attribute4,
          attribute3,
        ]);
      });
    });
  });
});
