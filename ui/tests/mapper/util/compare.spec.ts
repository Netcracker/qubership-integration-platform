import {
  compareAttributes,
  compareDataTypes,
  Difference,
  hasBreakingChanges,
  isBreakingChange,
} from "../../../src/mapper/util/compare.ts";
import { DataTypes } from "../../../src/mapper/util/types.ts";
import { ObjectSchema } from "../../../src/mapper/model/model.ts";
import { Attributes } from "../../../src/mapper/util/attributes.ts";

describe("isBreakingChange", () => {
  it("should return false on addition of a field", () => {
    const diff: Difference = {
      path: [],
      first: null,
      second: {
        type: DataTypes.integerType(),
        definitions: [],
      },
    };
    expect(isBreakingChange(diff)).toBeFalsy();
  });

  it("should return false on type change from null type", () => {
    const diff: Difference = {
      path: [],
      first: {
        type: DataTypes.nullType(),
        definitions: [],
      },
      second: {
        type: DataTypes.integerType(),
        definitions: [],
      },
    };
    expect(isBreakingChange(diff)).toBeFalsy();
  });

  it("should return true on type change", () => {
    const diff: Difference = {
      path: [],
      first: {
        type: DataTypes.stringType(),
        definitions: [],
      },
      second: {
        type: DataTypes.integerType(),
        definitions: [],
      },
    };
    expect(isBreakingChange(diff)).toBeTruthy();
  });
});

describe("hasBreakingChanges", () => {
  it("should return false on empty change list", () => {
    expect(hasBreakingChanges([])).toBeFalsy();
  });

  it("should return false when changes contains only field addition", () => {
    const diff: Difference = {
      path: [],
      first: null,
      second: {
        type: DataTypes.integerType(),
        definitions: [],
      },
    };
    expect(hasBreakingChanges([diff])).toBeFalsy();
  });

  it("should return true when has breaking change (for example, type change).", () => {
    const diff: Difference = {
      path: [],
      first: {
        type: DataTypes.stringType(),
        definitions: [],
      },
      second: {
        type: DataTypes.integerType(),
        definitions: [],
      },
    };
    expect(hasBreakingChanges([diff])).toBeTruthy();
  });
});

describe("compareAttributes", () => {
  it("should return empty list when schemas are the same", () => {
    const schema: ObjectSchema = {
      id: "",
      attributes: [],
    };
    expect(compareAttributes(schema, [], schema, [], [])).toEqual([]);
  });

  it("should return empty list when schemas are equal", () => {
    const schema1: ObjectSchema = {
      id: "1",
      attributes: [
        Attributes.buildAttribute("1", "foo", DataTypes.stringType()),
      ],
    };
    const schema2: ObjectSchema = {
      id: "2",
      attributes: [
        Attributes.buildAttribute("1", "foo", DataTypes.stringType()),
      ],
    };
    expect(compareAttributes(schema1, [], schema2, [], [])).toEqual([]);
  });

  it("should handle required flag change", () => {
    const schema1: ObjectSchema = {
      id: "1",
      attributes: [
        Attributes.buildAttribute("1", "foo", DataTypes.stringType()),
      ],
    };
    const schema2: ObjectSchema = {
      id: "2",
      attributes: [
        Attributes.buildAttribute(
          "1",
          "foo",
          DataTypes.stringType(),
          undefined,
          true,
        ),
      ],
    };
    expect(compareAttributes(schema1, [], schema2, [], [])).toEqual([
      {
        path: ["foo"],
        first: {
          type: DataTypes.stringType(),
          definitions: [],
        },
        second: {
          type: DataTypes.stringType(),
          definitions: [],
        },
        details: {
          feature: "required",
          first: "undefined",
          second: "true",
        },
      },
    ]);
  });

  it("should return difference with null first context when field is absent in the first schema", () => {
    const schema1: ObjectSchema = {
      id: "1",
      attributes: [],
    };
    const schema2: ObjectSchema = {
      id: "2",
      attributes: [
        Attributes.buildAttribute("1", "foo", DataTypes.stringType()),
      ],
    };
    expect(compareAttributes(schema1, [], schema2, [], [])).toEqual([
      {
        path: ["foo"],
        first: null,
        second: {
          type: DataTypes.stringType(),
          definitions: [],
        },
      },
    ]);
  });

  it("should return difference with null second context when field is absent in the second schema", () => {
    const schema1: ObjectSchema = {
      id: "1",
      attributes: [
        Attributes.buildAttribute("1", "foo", DataTypes.stringType()),
      ],
    };
    const schema2: ObjectSchema = {
      id: "2",
      attributes: [],
    };
    expect(compareAttributes(schema1, [], schema2, [], [])).toEqual([
      {
        path: ["foo"],
        first: {
          type: DataTypes.stringType(),
          definitions: [],
        },
        second: null,
      },
    ]);
  });
});

describe("compareDataTypes", () => {
  it("should return empty list when types are equal", () => {
    expect(
      compareDataTypes(
        { type: DataTypes.integerType(), definitions: [] },
        { type: DataTypes.integerType(), definitions: [] },
        [],
      ),
    ).toEqual([]);
  });

  it("should return difference when types aren't equal", () => {
    expect(
      compareDataTypes(
        { type: DataTypes.stringType(), definitions: [] },
        { type: DataTypes.integerType(), definitions: [] },
        ["foo", "bar"],
      ),
    ).toEqual([
      {
        path: ["foo", "bar"],
        first: {
          type: DataTypes.stringType(),
          definitions: [],
        },
        second: { type: DataTypes.integerType(), definitions: [] },
        details: {
          feature: "type",
          first: "string",
          second: "number",
        },
      },
    ]);
  });

  it("should resolve types", () => {
    expect(
      compareDataTypes(
        {
          type: DataTypes.referenceType("42"),
          definitions: [
            { id: "42", name: "customString", type: DataTypes.stringType() },
          ],
        },
        { type: DataTypes.integerType(), definitions: [] },
        ["foo", "bar"],
      ),
    ).toEqual([
      {
        path: ["foo", "bar"],
        first: {
          type: DataTypes.stringType(),
          definitions: [
            { id: "42", name: "customString", type: DataTypes.stringType() },
          ],
        },
        second: { type: DataTypes.integerType(), definitions: [] },
        details: {
          feature: "type",
          first: "string",
          second: "number",
        },
      },
    ]);
  });

  it("should compare array item types", () => {
    expect(
      compareDataTypes(
        { type: DataTypes.arrayType(DataTypes.stringType()), definitions: [] },
        { type: DataTypes.arrayType(DataTypes.integerType()), definitions: [] },
        ["foo", "bar"],
      ),
    ).toEqual([
      {
        path: ["foo", "bar"],
        first: {
          type: DataTypes.stringType(),
          definitions: [],
        },
        second: { type: DataTypes.integerType(), definitions: [] },
        details: {
          feature: "type",
          first: "string",
          second: "number",
        },
      },
    ]);
  });

  it("should compare objects' attributes", () => {
    const schema1: ObjectSchema = {
      id: "1",
      attributes: [
        Attributes.buildAttribute("1", "foo", DataTypes.stringType()),
      ],
    };
    const schema2: ObjectSchema = {
      id: "2",
      attributes: [],
    };
    const type1 = DataTypes.objectType(schema1);
    const type2 = DataTypes.objectType(schema2);
    expect(
      compareDataTypes(
        {
          type: type1,
          definitions: [],
        },
        { type: type2, definitions: [] },
        [],
      ),
    ).toEqual([
      {
        path: ["foo"],
        first: {
          type: DataTypes.stringType(),
          definitions: [],
        },
        second: null,
      },
    ]);
  });

  it("should return type difference when comparing different compound types", () => {
    const type1 = DataTypes.allOfType([DataTypes.stringType()]);
    const type2 = DataTypes.anyOfType([DataTypes.stringType()]);
    expect(
      compareDataTypes(
        {
          type: type1,
          definitions: [],
        },
        { type: type2, definitions: [] },
        [],
      ),
    ).toEqual([
      {
        path: [],
        first: {
          type: type1,
          definitions: [],
        },
        second: {
          type: type2,
          definitions: [],
        },
        details: {
          feature: "type",
          first: "all of 1 type",
          second: "any of 1 type",
        },
      },
    ]);
  });

  it("should return empty list when comparing equal compound types", () => {
    const type1 = DataTypes.allOfType([DataTypes.stringType()]);
    const type2 = DataTypes.allOfType([DataTypes.stringType()]);
    expect(
      compareDataTypes(
        {
          type: type1,
          definitions: [],
        },
        { type: type2, definitions: [] },
        [],
      ),
    ).toEqual([]);
  });
});
