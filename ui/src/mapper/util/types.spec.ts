import { DataTypes } from "./types.ts";
import {
  Attribute,
  DataType,
  Metadata,
  ObjectSchema,
  ObjectType,
  ReferenceType,
  TypeDefinition,
  TypeDefinitionsAware,
} from "../model/model.ts";

function getContext(type: DataType): string {
  return type === undefined ? "undefined" : JSON.stringify(type);
}

describe("Mapper", () => {
  describe("DataTypes", () => {
    describe("nullType", () => {
      it("should return NullType with specified metadata", () => {
        expect(DataTypes.nullType().metadata).not.toBeDefined();
        const metadata = {};
        expect(DataTypes.nullType(metadata).metadata).toBe(metadata);
      });
    });

    describe("stringType", () => {
      it("should return StringType with specified metadata", () => {
        expect(DataTypes.stringType().metadata).not.toBeDefined();
        const metadata = {};
        expect(DataTypes.stringType(metadata).metadata).toBe(metadata);
      });
    });

    describe("integerType", () => {
      it("should return IntegerType with specified metadata", () => {
        expect(DataTypes.integerType().metadata).not.toBeDefined();
        const metadata = {};
        expect(DataTypes.integerType(metadata).metadata).toBe(metadata);
      });
    });

    describe("booleanType", () => {
      it("should return BooleanType with specified metadata", () => {
        expect(DataTypes.booleanType().metadata).not.toBeDefined();
        const metadata = {};
        expect(DataTypes.booleanType(metadata).metadata).toBe(metadata);
      });
    });

    describe("arrayType", () => {
      it("should return ArrayType with specified definitions and metadata", () => {
        const itemType: DataType = { name: "string" };
        const definitions = [];
        const metadata = {};
        const arrayType = DataTypes.arrayType(itemType, definitions, metadata);
        expect(arrayType.itemType).toBe(itemType);
        expect(arrayType.definitions).toBe(definitions);
        expect(arrayType.metadata).toBe(metadata);
      });
    });

    describe("objectType", () => {
      it("should return ObjectType with specified schema, definitions, and metadata", () => {
        const schema = { id: "", attributes: [] };
        const definitions = [];
        const metadata = {};
        const objectType = DataTypes.objectType(schema, definitions, metadata);
        expect(objectType.schema).toBe(schema);
        expect(objectType.definitions).toBe(definitions);
        expect(objectType.metadata).toBe(metadata);
      });
    });

    describe("referenceType", () => {
      it("should return ReferenceType with specified definition ID, definitions, and metadata", () => {
        const definitions = [];
        const metadata = {};
        const referenceType = DataTypes.referenceType(
          "foo",
          definitions,
          metadata,
        );
        expect(referenceType.definitionId).toEqual("foo");
        expect(referenceType.definitions).toBe(definitions);
        expect(referenceType.metadata).toBe(metadata);
      });
    });

    describe("allOfType", () => {
      it("should return AllOfType with specified nested types and metadata", () => {
        const types: DataType[] = [];
        const metadata = {};
        const type = DataTypes.allOfType(types, metadata);
        expect(type.types).toBe(types);
        expect(type.metadata).toBe(metadata);
      });
    });

    describe("anyOfType", () => {
      it("should return AnyOfType with specified nested types and metadata", () => {
        const types: DataType[] = [];
        const metadata = {};
        const type = DataTypes.anyOfType(types, metadata);
        expect(type.types).toBe(types);
        expect(type.metadata).toBe(metadata);
      });
    });

    describe("oneOfType", () => {
      it("should return OneOfType with specified nested types and metadata", () => {
        const types: DataType[] = [];
        const metadata = {};
        const type = DataTypes.oneOfType(types, metadata);
        expect(type.types).toBe(types);
        expect(type.metadata).toBe(metadata);
      });
    });

    describe("isPrimitiveType", () => {
      it("should return true for primitive types", () => {
        const types: DataType[] = [
          { name: "boolean" },
          { name: "number" },
          { name: "string" },
        ];
        types.forEach((type) =>
          expect(DataTypes.isPrimitiveType(type), getContext(type))
            .toBeTruthy(),
        );
      });

      it("should return false for null type", () => {
        expect(DataTypes.isPrimitiveType({ name: "null" })).toBeFalsy();
      });

      it("should return false for complex types", () => {
        const types: DataType[] = [
          { name: "array", itemType: { name: "null" } },
          { name: "object", schema: { id: "", attributes: [] } },
          { name: "reference", definitionId: "" },
          { name: "allOf", types: [] },
          { name: "anyOf", types: [] },
          { name: "oneOf", types: [] },
        ];
        types.forEach((type) =>
          expect(DataTypes.isPrimitiveType(type), getContext(type))
            .toBeFalsy(),
        );
      });

      it("should return false for null and undefined argument values", () => {
        [null, undefined].forEach((type) =>
          expect(DataTypes.isPrimitiveType(type), getContext(type))
            .toBeFalsy(),
        );
      });
    });

    describe("isCompoundType", () => {
      it("should return true for compound types", () => {
        const types: DataType[] = [
          { name: "allOf", types: [] },
          { name: "anyOf", types: [] },
          { name: "oneOf", types: [] },
        ];
        types.forEach((type) =>
          expect(DataTypes.isCompoundType(type), getContext(type))
            .toBeTruthy(),
        );
      });

      it("should return false for types other than allOf, anyOf, and oneOf", () => {
        const types: DataType[] = [
          { name: "boolean" },
          { name: "number" },
          { name: "string" },
          { name: "null" },
          { name: "array", itemType: { name: "null" } },
          { name: "object", schema: { id: "", attributes: [] } },
          { name: "reference", definitionId: "" },
        ];
        types.forEach((type) =>
          expect(DataTypes.isCompoundType(type), getContext(type))
            .toBeFalsy(),
        );
      });

      it("should return false for null and undefined argument values", () => {
        [null, undefined].forEach((type) =>
          expect(DataTypes.isCompoundType(type), getContext(type))
            .toBeFalsy(),
        );
      });
    });

    describe("typeIsDefinitionsAware", () => {
      it("should return true for complex types", () => {
        const types: DataType[] = [
          { name: "array", itemType: { name: "null" } },
          { name: "object", schema: { id: "", attributes: [] } },
          { name: "reference", definitionId: "" },
          { name: "allOf", types: [] },
          { name: "anyOf", types: [] },
          { name: "oneOf", types: [] },
        ];
        types.forEach((type) =>
          expect(DataTypes.typeIsDefinitionsAware(type), getContext(type))
            .toBeTruthy(),
        );
      });

      it("should return false for primitive and null types", () => {
        const types: DataType[] = [
          { name: "boolean" },
          { name: "number" },
          { name: "string" },
          { name: "null" },
        ];
        types.forEach((type) =>
          expect(DataTypes.typeIsDefinitionsAware(type), getContext(type))
            .toBeFalsy(),
        );
      });

      it("should return false for null and undefined argument values", () => {
        [null, undefined].forEach((type) =>
          expect(DataTypes.typeIsDefinitionsAware(type), getContext(type))
            .toBeFalsy(),
        );
      });
    });

    describe("getTypeDefinitions", () => {
      it("should return empty array for complex types with definitions field undefined", () => {
        const types: DataType[] = [
          { name: "array", itemType: { name: "null" } },
          { name: "object", schema: { id: "", attributes: [] } },
          { name: "reference", definitionId: "" },
          { name: "allOf", types: [] },
          { name: "anyOf", types: [] },
          { name: "oneOf", types: [] },
        ];
        types.forEach((type) =>
          expect(DataTypes.getTypeDefinitions(type), getContext(type))
            .toHaveLength(0),
        );
      });

      it('should return array with same elements so as in "definitions" field', () => {
        const types: DataType[] = [
          { name: "array", itemType: { name: "null" } },
          { name: "object", schema: { id: "", attributes: [] } },
          { name: "reference", definitionId: "" },
          { name: "allOf", types: [] },
          { name: "anyOf", types: [] },
          { name: "oneOf", types: [] },
        ];
        const definitions: TypeDefinition[] = [
          { id: "1", name: "a", type: { name: "string" } },
          { id: "2", name: "b", type: { name: "number" } },
        ];
        types
          .map((type) => ({ ...type, definitions }))
          .forEach((type) =>
            expect(DataTypes.getTypeDefinitions(type).sort()).toEqual(definitions.sort()),
          );
      });

      it("should return empty array for primitive and null types", () => {
        const types: DataType[] = [
          { name: "boolean" },
          { name: "number" },
          { name: "string" },
          { name: "null" },
        ];
        types.forEach((type) =>
          expect(DataTypes.getTypeDefinitions(type), getContext(type))
            .toHaveLength(0),
        );
      });

      it("should return empty array for null and undefined argument values", () => {
        [null, undefined].forEach((type) =>
          expect(DataTypes.getTypeDefinitions(type), getContext(type))
            .toHaveLength(0),
        );
      });
    });

    describe("updateMetadata", () => {
      it("should return null when null type value is passed", () => {
        expect(DataTypes.updateMetadata(null, {})).toBeNull();
      });

      it("should return undefined when undefined type value is passed", () => {
        expect(DataTypes.updateMetadata(undefined, {})).toBeUndefined();
      });

      it("should return type with same metadata when null or undefined metadata value is passed", () => {
        [null, undefined].forEach((metadata) => {
          expect(DataTypes.updateMetadata({ name: "null" }, metadata).metadata, getContext(metadata))
            .toBeUndefined();
          const typeMetadata: Metadata = { foo: "bar", baz: {} };
          expect(
            DataTypes.updateMetadata(
              { name: "null", metadata: typeMetadata },
              metadata,
            ).metadata,
            getContext(metadata)
          )
            .toEqual(expect.objectContaining(typeMetadata));
        });
        expect(
          DataTypes.updateMetadata({ name: "null" }, null).metadata,
        ).toBeUndefined();
      });

      it("should add key-value pair from passed metadata to type metadata", () => {
        expect(
          DataTypes.updateMetadata(
            { name: "null", metadata: { foo: "bar" } },
            { baz: 42 },
          ).metadata,
        ).toEqual(expect.objectContaining({ foo: "bar", baz: 42 }));
      });

      it("should rewrite existing values with same keys", () => {
        expect(
          DataTypes.updateMetadata(
            { name: "null", metadata: { foo: "bar", baz: 13 } },
            { baz: 42 },
          ).metadata,
        ).toEqual(expect.objectContaining({ foo: "bar", baz: 42 }));
      });
    });

    describe("updateDefinitions", () => {
      it("should return null when null type value is passed", () => {
        expect(DataTypes.updateDefinitions(null, [])).toBeNull();
      });

      it("should return undefined when undefined type value is passed", () => {
        expect(DataTypes.updateDefinitions(undefined, [])).toBeUndefined();
      });

      it("should return object with passed definitions when definitions in type object not set", () => {
        const type: ReferenceType = { name: "reference", definitionId: "bar" };
        const definitions: TypeDefinition[] = [
          { id: "bar", name: "bar", type: { name: "string" } },
        ];
        const result: DataType = DataTypes.updateDefinitions(type, definitions);
        expect((result as TypeDefinitionsAware).definitions.sort()).toEqual(definitions.sort());
      });

      it("should return object with all definitions from passed type object and definition list", () => {
        const typeDefinitions: TypeDefinition[] = [
          { id: "bar", name: "bar", type: { name: "null" } },
        ];
        const type: ReferenceType = {
          name: "reference",
          definitionId: "bar",
          definitions: typeDefinitions,
        };
        const definitions: TypeDefinition[] = [
          { id: "baz", name: "baz", type: { name: "string" } },
        ];
        const result: DataType = DataTypes.updateDefinitions(type, definitions);
        expect((result as TypeDefinitionsAware).definitions.sort()).toEqual(typeDefinitions.concat(definitions).sort());
      });
    });

    describe("resolveType", () => {
      it("should resolve to null when type is null", () => {
        expect(DataTypes.resolveType(null, [])).toEqual({
          type: null,
          definitions: [],
        });
      });

      it("should resolve to undefined when type is undefined", () => {
        expect(DataTypes.resolveType(undefined, [])).toEqual({
          type: undefined,
          definitions: [],
        });
      });

      it("should resolve to passed type when type is not a reference", () => {
        expect(DataTypes.resolveType({ name: "string" }, [])).toEqual({
          type: { name: "string" },
          definitions: [],
        });
      });

      it("should resolve to undefined value when referenced type not in type definitions list", () => {
        expect(
          DataTypes.resolveType({ name: "reference", definitionId: "foo" }, []),
        ).toEqual({
          type: undefined,
          definitions: [],
        });
      });

      it("should return referenced type (resolved from nested type definitions)", () => {
        expect(
          DataTypes.resolveType(
            {
              name: "reference",
              definitionId: "foo",
              definitions: [
                {
                  type: { name: "number" },
                  name: "MyAwesomeCustomNumberType",
                  id: "foo",
                },
              ],
            },
            [],
          ),
        ).toEqual({
          type: { name: "number" },
          definitions: [
            {
              type: { name: "number" },
              name: "MyAwesomeCustomNumberType",
              id: "foo",
            },
          ],
        });
      });

      it("should return referenced type (resolved from passed type list)", () => {
        expect(
          DataTypes.resolveType(
            {
              name: "reference",
              definitionId: "foo",
            },
            [
              {
                type: { name: "number" },
                name: "MyAwesomeCustomNumberType",
                id: "foo",
              },
            ],
          ),
        ).toEqual({
          type: { name: "number" },
          definitions: [
            {
              type: { name: "number" },
              name: "MyAwesomeCustomNumberType",
              id: "foo",
            },
          ],
        });
      });

      it("should resolve reference chain", () => {
        const definitions: TypeDefinition[] = [
          {
            type: { name: "reference", definitionId: "bar" },
            name: "WeNeedToGoDeeper",
            id: "foo",
          },
          {
            type: { name: "reference", definitionId: "baz" },
            name: "MuchDeeper",
            id: "bar",
          },
          { type: { name: "boolean" }, name: "", id: "baz" },
        ];
        expect(
          DataTypes.resolveType(
            { name: "reference", definitionId: "foo" },
            definitions,
          ),
        ).toEqual({
          type: { name: "boolean" },
          definitions,
        });
      });
    });

    describe("resolveArrayItemType", () => {
      it("should resolve to null when type is null", () => {
        expect(DataTypes.resolveArrayItemType(null, [])).toEqual({
          type: null,
          definitions: [],
        });
      });

      it("should resolve to undefined when type is undefined", () => {
        expect(DataTypes.resolveArrayItemType(undefined, [])).toEqual({
          type: undefined,
          definitions: [],
        });
      });

      it("should resolve to its argument when it is not an array type", () => {
        expect(DataTypes.resolveArrayItemType({ name: "string" }, [])).toEqual({
          type: { name: "string" },
          definitions: [],
        });
      });

      it("should resolve to array item type", () => {
        expect(
          DataTypes.resolveArrayItemType(
            { name: "array", itemType: { name: "number" } },
            [],
          ),
        ).toEqual({
          type: {
            name: "number",
          },
          definitions: [],
        });
      });

      it("should resolve to item type of nested array type", () => {
        expect(
          DataTypes.resolveArrayItemType(
            {
              name: "array",
              itemType: {
                name: "array",
                itemType: { name: "array", itemType: { name: "boolean" } },
              },
            },
            [],
          ),
        ).toEqual({ type: { name: "boolean" }, definitions: [] });
      });

      it("should resolve references in order to get array item type", () => {
        expect(
          DataTypes.resolveArrayItemType(
            {
              name: "array",
              itemType: { name: "reference", definitionId: "foo" },
            },
            [
              {
                type: {
                  name: "array",
                  itemType: {
                    name: "reference",
                    definitionId: "bar",
                    definitions: [
                      { type: { name: "number" }, name: "", id: "bar" },
                    ],
                  },
                },
                name: "",
                id: "foo",
              },
            ],
          ),
        ).toEqual({
          type: { name: "number" },
          definitions: [
            {
              type: {
                name: "array",
                itemType: {
                  name: "reference",
                  definitionId: "bar",
                  definitions: [
                    { type: { name: "number" }, name: "", id: "bar" },
                  ],
                },
              },
              name: "",
              id: "foo",
            },
            { type: { name: "number" }, name: "", id: "bar" },
          ],
        });
      });
    });

    describe("buildTypeName", () => {
      it("should return undefined value when type is undefined or null", () => {
        [undefined, null].forEach((type) =>
          expect(DataTypes.buildTypeName(type, [])).toBeUndefined(),
        );
      });

      it("should return type name if type is either null, string, number, boolean, or object", () => {
        const types: DataType[] = [
          { name: "null" },
          { name: "string" },
          { name: "number" },
          { name: "boolean" },
          { name: "object", schema: { id: "", attributes: [] } },
        ];
        types.forEach((type) =>
          expect(DataTypes.buildTypeName(type, [])).toEqual(type.name),
        );
      });

      it('should return "array of " followed by item type name when type is array', () => {
        expect(
          DataTypes.buildTypeName(
            { name: "array", itemType: { name: "string" } },
            [],
          ),
        ).toEqual("array of string");
      });

      it("should return type definition name when type is reference", () => {
        expect(
          DataTypes.buildTypeName({ name: "reference", definitionId: "foo" }, [
            { id: "foo", name: "bar", type: { name: "string" } },
          ]),
        ).toEqual("bar");
      });

      it("should return type definition ID when type is reference and it could not be resolved", () => {
        expect(
          DataTypes.buildTypeName(
            { name: "reference", definitionId: "foo" },
            [],
          ),
        ).toEqual("foo");
      });

      it('should return "any/all/one of ..." when type is compound type with empty types list', () => {
        ["all", "any", "one"].forEach((name) =>
          expect(
            DataTypes.buildTypeName(
              { name: (name + "Of") as "allOf" | "anyOf" | "oneOf", types: [] },
              [],
            ),
          ).toEqual(`${name} of ...`),
        );
      });

      it('should return "any/all/one of 1 type" when when type is compound type with one nested type', () => {
        ["all", "any", "one"].forEach((name) =>
          expect(
            DataTypes.buildTypeName(
              {
                name: (name + "Of") as "allOf" | "anyOf" | "oneOf",
                types: [{ name: "string" }],
              },
              [],
            ),
          ).toEqual(`${name} of 1 type`),
        );
      });

      it('should return "any/all/one of 2 types" when type is compound type with 2 nested types', () => {
        ["all", "any", "one"].forEach((name) =>
          expect(
            DataTypes.buildTypeName(
              {
                name: (name + "Of") as "anyOf" | "allOf" | "oneOf",
                types: [{ name: "string" }, { name: "null" }],
              },
              [],
            ),
          ).toEqual(`${name} of 2 types`),
        );
      });
    });

    describe("objectSchemasAreSame", () => {
      it("should return true iff schemas has same attributes compared by name and type", () => {
        const schema1: ObjectSchema = {
          id: "1",
          attributes: [
            { id: "2", name: "foo", type: { name: "string" } },
            { id: "3", name: "bar", type: { name: "number" } },
          ],
        };
        const schema2: ObjectSchema = {
          id: "4",
          attributes: [
            { id: "5", name: "bar", type: { name: "number" } },
            { id: "6", name: "foo", type: { name: "string" } },
          ],
        };
        const schema3: ObjectSchema = {
          id: "4",
          attributes: [{ id: "5", name: "bar", type: { name: "number" } }],
        };
        expect(DataTypes.objectSchemasAreSame(schema1, schema1, []), "comparing same schema objects")
          .toBeTruthy();
        expect(DataTypes.objectSchemasAreSame(schema1, schema2, []), "comparing equal schemes")
          .toBeTruthy();
        expect(DataTypes.objectSchemasAreSame(schema1, schema3, []), "comparing different schemes")
          .toBeFalsy();
      });
    });

    describe("mergeTypeDefinitions", () => {
      it("should merge type definition lists", () => {
        expect(DataTypes.mergeTypeDefinitions(
          [
            { id: "1", name: "foo", type: { name: "string" } },
            { id: "2", name: "bar", type: { name: "number" } },
          ],
          [{ id: "3", name: "baz", type: { name: "boolean" } }],
        ).sort()).toEqual([
          { id: "1", name: "foo", type: { name: "string" } },
          { id: "2", name: "bar", type: { name: "number" } },
          { id: "3", name: "baz", type: { name: "boolean" } },
        ].sort());
      });
    });

    describe("same", () => {
      it("should return if types are same object", () => {
        const type: DataType = { name: "string" };
        expect(DataTypes.same(type, type, [])).toBeTruthy();
      });

      it("should return true when comparing undefined or null values", () => {
        [undefined, null].forEach((type) =>
          expect(DataTypes.same(type, type, [])).toBeTruthy(),
        );
      });

      it("should return false if type names are not equal", () => {
        const names = [
          "null",
          "string",
          "number",
          "boolean",
          "reference",
          "object",
          "anyOf",
          "allOf",
          "oneOf",
        ];
        names.forEach((name1) =>
          names
            .filter((name2) => name1 !== name2)
            .forEach((name2) =>
              expect(
                DataTypes.same(
                  { name: name1 } as DataType,
                  { name: name2 } as DataType,
                  [],
                ),
                `${name1} and ${name2}`
              )
                .toBeFalsy(),
            ),
        );
      });

      it("should return true when comparing same primitive types", () => {
        ["null", "string", "number", "boolean"].forEach((name) =>
          expect(DataTypes.same({ name } as DataType, { name } as DataType, []), name)
            .toBeTruthy(),
        );
      });

      it("should not take metadata into account", () => {
        expect(
          DataTypes.same(
            { name: "string" },
            { name: "string", metadata: { foo: "bar" } },
            [],
          ),
        ).toBeTruthy();
      });

      it("should return true for array types when item types are same", () => {
        expect(
          DataTypes.same(
            { name: "array", itemType: { name: "boolean" } },
            { name: "array", itemType: { name: "boolean" } },
            [],
          ),
        ).toBeTruthy();
      });

      it("should return false for array types when item types are not same", () => {
        expect(
          DataTypes.same(
            { name: "array", itemType: { name: "string" } },
            { name: "array", itemType: { name: "number" } },
            [],
          ),
        ).toBeFalsy();
      });

      it("should return true when reference types have same definition ID", () => {
        expect(
          DataTypes.same(
            { name: "reference", definitionId: "foo" },
            { name: "reference", definitionId: "foo" },
            [],
          ),
        ).toBeTruthy();
      });

      it("should resolve references when comparing types", () => {
        expect(
          DataTypes.same(
            { name: "reference", definitionId: "foo" },
            {
              name: "reference",
              definitionId: "bar",
              definitions: [
                { id: "bar", name: "bar-type", type: { name: "string" } },
              ],
            },
            [{ id: "foo", name: "foo-type", type: { name: "string" } }],
          ),
          "should return true when references are resolving to same types",
        )
          .toBeTruthy();
        expect(
          DataTypes.same(
            { name: "reference", definitionId: "foo" },
            {
              name: "reference",
              definitionId: "bar",
              definitions: [
                { id: "bar", name: "bar-type", type: { name: "string" } },
              ],
            },
            [{ id: "foo", name: "foo-type", type: { name: "boolean" } }],
          ),
          "should return false when references are resolving to different types",
        )
          .toBeFalsy();
      });

      it("should compare object types by schemas", () => {
        const schema1: ObjectSchema = {
          id: "1",
          attributes: [
            { id: "2", name: "foo", type: { name: "string" } },
            { id: "3", name: "bar", type: { name: "number" } },
          ],
        };
        const schema2: ObjectSchema = {
          id: "4",
          attributes: [
            { id: "5", name: "bar", type: { name: "number" } },
            { id: "6", name: "foo", type: { name: "string" } },
          ],
        };
        const schema3: ObjectSchema = {
          id: "4",
          attributes: [{ id: "5", name: "bar", type: { name: "number" } }],
        };
        expect(
          DataTypes.same(
            { name: "object", schema: schema1 },
            { name: "object", schema: schema2 },
            [],
          ),
          "should return true for object types with same schemas"
        )
          .toBeTruthy();
        expect(
          DataTypes.same(
            { name: "object", schema: schema1 },
            { name: "object", schema: schema3 },
            [],
          ),
          "should return false for object types with different schemas",
        )
          .toBeFalsy();
      });

      it("should return true when comparing compound types with same sets of subtypes", () => {
        expect(
          DataTypes.same(
            { name: "allOf", types: [{ name: "string" }, { name: "number" }] },
            { name: "allOf", types: [{ name: "number" }, { name: "string" }] },
            [],
          ),
        ).toBeTruthy();
      });

      it("should return false when comparing compound types with different sets of subtypes", () => {
        expect(
          DataTypes.same(
            { name: "allOf", types: [{ name: "string" }, { name: "number" }] },
            { name: "allOf", types: [{ name: "number" }] },
            [],
          ),
        ).toBeFalsy();
      });
    });

    describe("modifyAttributes", () => {
      it("should return null if type is null", () => {
        expect(
          DataTypes.modifyAttributes(null, [], [], (attributes) => attributes),
        ).toBeNull();
      });

      it("should return undefined if type is undefined", () => {
        expect(
          DataTypes.modifyAttributes(
            undefined,
            [],
            [],
            (attributes) => attributes,
          ),
        ).toBeUndefined();
      });

      it("should modify attributes in specified path", () => {
        const type: DataType = {
          name: "object",
          schema: {
            id: "schema1",
            attributes: [
              {
                id: "1",
                name: "attribute1",
                type: {
                  name: "object",
                  schema: {
                    id: "schema2",
                    attributes: [
                      {
                        id: "3",
                        name: "attribute3",
                        type: {
                          name: "object",
                          schema: {
                            id: "schema3",
                            attributes: [
                              {
                                id: "4",
                                name: "attribute4",
                                type: { name: "string" },
                              },
                            ],
                          },
                        },
                      },
                    ],
                  },
                },
              },
              { id: "2", name: "attribute2", type: { name: "string" } },
            ],
          },
        };
        const modifyFn = (attributes: Attribute[]) =>
          attributes.map((attribute) => ({
            ...attribute,
            name: "a_" + attribute.name,
          }));
        // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
        const expectedResult = expect.objectContaining({
          name: "object",
          // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
          schema: expect.objectContaining({
            attributes: [
              {
                id: "1",
                name: "attribute1",
                type: {
                  name: "object",
                  // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
                  schema: expect.objectContaining({
                    attributes: [
                      {
                        id: "3",
                        name: "attribute3",
                        type: {
                          name: "object",
                          // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
                          schema: expect.objectContaining({
                            attributes: [
                              {
                                id: "4",
                                name: "a_attribute4",
                                type: { name: "string" },
                              },
                            ],
                          }),
                        },
                      },
                    ],
                  }),
                },
              },
              { id: "2", name: "attribute2", type: { name: "string" } },
            ],
          }),
        });
        expect(
          DataTypes.modifyAttributes(type, ["1", "3"], [], modifyFn),
        ).toEqual(expectedResult);
      });

      it("should generate new IDs for changed schemas", () => {
        const type: ObjectType = {
          name: "object",
          schema: {
            id: "schema1",
            attributes: [
              {
                id: "1",
                name: "attribute1",
                type: {
                  name: "object",
                  schema: {
                    id: "schema2",
                    attributes: [
                      {
                        id: "3",
                        name: "attribute3",
                        type: {
                          name: "object",
                          schema: {
                            id: "schema3",
                            attributes: [
                              {
                                id: "4",
                                name: "attribute4",
                                type: { name: "string" },
                              },
                            ],
                          },
                        },
                      },
                    ],
                  },
                },
              },
              { id: "2", name: "attribute2", type: { name: "string" } },
            ],
          },
        };
        const modifyFn = (attributes: Attribute[]) =>
          attributes.map((attribute) => ({
            ...attribute,
            name: "a_" + attribute.name,
          }));
        const result = DataTypes.modifyAttributes(
          type,
          ["1", "3"],
          [],
          modifyFn,
        );
        const schema1 = (result as ObjectType).schema;
        expect(schema1.id).not.toEqual("schema1");
        const schema2 = (schema1.attributes[0].type as ObjectType).schema;
        expect(schema2.id).not.toEqual("schema2");
        const schema3 = (schema2.attributes[0].type as ObjectType).schema;
        expect(schema3.id).not.toEqual("schema3");
      });

      it("should resolve references in order to modify attributes", () => {
        const type: DataType = {
          name: "reference",
          definitionId: "foo",
          definitions: [
            {
              id: "foo",
              name: "type1",
              type: {
                name: "object",
                schema: {
                  id: "1",
                  attributes: [
                    { id: "2", name: "a", type: { name: "string" } },
                  ],
                },
              },
            },
          ],
        };
        const modifyFn = (attributes: Attribute[]) =>
          attributes.map((attribute) => ({
            ...attribute,
            name: "attribute_" + attribute.name,
          }));
        // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
        const expectedResult = expect.objectContaining({
          name: "object",
          // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
          schema: expect.objectContaining({
            attributes: [
              { id: "2", name: "attribute_a", type: { name: "string" } },
            ],
          }),
        });
        expect(DataTypes.modifyAttributes(type, [], [], modifyFn)).toEqual(
          expectedResult,
        );
      });

      it("should modify attributes in array item type", () => {
        const type: DataType = {
          name: "array",
          itemType: {
            name: "object",
            schema: {
              id: "schema1",
              attributes: [{ id: "1", name: "a", type: { name: "string" } }],
            },
          },
        };
        const modifyFn = (attributes: Attribute[]) =>
          attributes.map((attribute) => ({
            ...attribute,
            name: "attribute_" + attribute.name,
          }));
        // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
        const expectedResult = expect.objectContaining({
          name: "array",
          itemType: {
            name: "object",
            // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
            schema: expect.objectContaining({
              attributes: [
                { id: "1", name: "attribute_a", type: { name: "string" } },
              ],
            }),
          },
        });
        expect(DataTypes.modifyAttributes(type, [], [], modifyFn)).toEqual(
          expectedResult,
        );
      });

      it("should modify attributes in subtypes of compound type", () => {
        const type: DataType = {
          name: "allOf",
          types: [
            {
              name: "object",
              schema: {
                id: "schema1",
                attributes: [{ id: "1", name: "a", type: { name: "string" } }],
              },
            },
          ],
        };
        const modifyFn = (attributes: Attribute[]) =>
          attributes.map((attribute) => ({
            ...attribute,
            name: "attribute_" + attribute.name,
          }));
        // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
        const expectedResult = expect.objectContaining({
          name: "allOf",
          types: [
            {
              name: "object",
              // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
              schema: expect.objectContaining({
                attributes: [
                  { id: "1", name: "attribute_a", type: { name: "string" } },
                ],
              }),
            },
          ],
        });
        expect(DataTypes.modifyAttributes(type, [], [], modifyFn)).toEqual(
          expectedResult,
        );
      });
    });
  });
});
