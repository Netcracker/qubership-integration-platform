import {
  migrateAction,
  migrateActions,
  migrateAttributeReference,
  migratePath,
} from "../../../src/mapper/util/migration.ts";
import { Attributes } from "../../../src/mapper/util/attributes.ts";
import {
  AttributeReference,
  ConstantReference,
  DataType,
  MappingAction,
  SchemaKind,
} from "../../../src/mapper/model/model.ts";
import { DataTypes } from "../../../src/mapper/util/types.ts";

describe("migratePath", () => {
  it("should return null when path cannot be restored from source type", () => {
    const fromType: DataType = DataTypes.stringType();
    const toType: DataType = DataTypes.integerType();
    const path = ["nonexistent"];

    const result = migratePath(path, fromType, toType);

    expect(result).toBeNull();
  });

  it("should return null when restored path cannot be resolved in target type", () => {
    const fromType: DataType = DataTypes.objectType({
      id: "schema1",
      attributes: [
        Attributes.buildAttribute("1", "foo", DataTypes.stringType()),
      ],
    });
    const toType: DataType = {
      name: "object",
      schema: { id: "schema2", attributes: [] },
    };
    const path = ["1"];

    const result = migratePath(path, fromType, toType);

    expect(result).toBeNull();
  });

  it("should return migrated path when path exists in both types", () => {
    const fromType = DataTypes.objectType({
      id: "schema1",
      attributes: [
        Attributes.buildAttribute(
          "1",
          "foo",
          DataTypes.objectType({
            id: "schema2",
            attributes: [
              Attributes.buildAttribute("2", "bar", DataTypes.stringType()),
            ],
          }),
        ),
      ],
    });
    const toType = DataTypes.objectType({
      id: "schema3",
      attributes: [
        Attributes.buildAttribute(
          "3",
          "foo",
          DataTypes.objectType({
            id: "schema4",
            attributes: [
              Attributes.buildAttribute("4", "bar", DataTypes.stringType()),
            ],
          }),
        ),
      ],
    });
    const path = ["1", "2"];
    const result = migratePath(path, fromType, toType);

    expect(result).toEqual(["3", "4"]);
  });

  it("should handle empty path", () => {
    const fromType: DataType = DataTypes.stringType();
    const toType: DataType = DataTypes.integerType();

    const result = migratePath([], fromType, toType);

    expect(result).toEqual([]);
  });
});

describe("migrateAttributeReference", () => {
  it("should return null when reference path does not start with prefix", () => {
    const reference: AttributeReference = {
      type: "attribute",
      kind: "property",
      path: ["other", "path"],
    };
    const pathPrefix = ["prefix"];
    const fromType: DataType = DataTypes.stringType();
    const toType: DataType = DataTypes.integerType();

    const result = migrateAttributeReference(
      reference,
      pathPrefix,
      fromType,
      toType,
    );

    expect(result).toBeNull();
  });

  it("should return null when path suffix cannot be migrated", () => {
    const reference: AttributeReference = {
      type: "attribute",
      kind: "property",
      path: ["a", "nonexistent"],
    };

    const fromType = DataTypes.objectType({
      id: "schema1",
      attributes: [
        Attributes.buildAttribute("1", "foo", DataTypes.stringType()),
      ],
    });
    const toType = DataTypes.stringType();

    const pathPrefix = ["a"];

    const result = migrateAttributeReference(
      reference,
      pathPrefix,
      fromType,
      toType,
    );

    expect(result).toBeNull();
  });

  it("should return migrated reference", () => {
    const reference: AttributeReference = {
      type: "attribute",
      kind: "property",
      path: ["1", "2", "3"],
    };
    const pathPrefix = ["1"];
    const fromType = DataTypes.objectType({
      id: "schema1",
      attributes: [
        Attributes.buildAttribute(
          "2",
          "foo",
          DataTypes.objectType({
            id: "schema2",
            attributes: [
              Attributes.buildAttribute("3", "bar", DataTypes.stringType()),
            ],
          }),
        ),
      ],
    });
    const toType = DataTypes.objectType({
      id: "schema3",
      attributes: [
        Attributes.buildAttribute(
          "4",
          "foo",
          DataTypes.objectType({
            id: "schema4",
            attributes: [
              Attributes.buildAttribute("5", "bar", DataTypes.stringType()),
            ],
          }),
        ),
      ],
    });

    const result = migrateAttributeReference(
      reference,
      pathPrefix,
      fromType,
      toType,
    );

    expect(result).toEqual({
      type: "attribute",
      kind: "property",
      path: ["1", "4", "5"],
    });
  });

  it("should handle reference with prefix path only", () => {
    const reference: AttributeReference = {
      type: "attribute",
      kind: "header",
      path: ["prefix"],
    };
    const pathPrefix = ["prefix"];
    const fromType: DataType = DataTypes.objectType({
      id: "s1",
      attributes: [],
    });
    const toType: DataType = DataTypes.objectType({ id: "s2", attributes: [] });

    const result = migrateAttributeReference(
      reference,
      pathPrefix,
      fromType,
      toType,
    );

    expect(result).toEqual({
      type: "attribute",
      kind: "header",
      path: ["prefix"],
    });
  });

  it("should preserve metadata when migrating attribute reference", () => {
    const reference: AttributeReference = {
      type: "attribute",
      kind: "header",
      path: ["prefix"],
      metadata: { foo: "bar" },
    };
    const pathPrefix = ["prefix"];
    const fromType: DataType = DataTypes.objectType({
      id: "s1",
      attributes: [],
    });
    const toType: DataType = DataTypes.objectType({ id: "s2", attributes: [] });

    const result = migrateAttributeReference(
      reference,
      pathPrefix,
      fromType,
      toType,
    );

    expect(result?.metadata).toEqual({ foo: "bar" });
  });
});

describe("migrateAction", () => {
  const fromType = DataTypes.objectType({
    id: "s1",
    attributes: [
      Attributes.buildAttribute(
        "1",
        "foo",
        DataTypes.objectType({
          id: "s2",
          attributes: [
            Attributes.buildAttribute("2", "bar", DataTypes.stringType()),
            Attributes.buildAttribute("3", "baz", DataTypes.stringType()),
          ],
        }),
      ),
    ],
  });

  const toType = DataTypes.objectType({
    id: "s3",
    attributes: [
      Attributes.buildAttribute(
        "4",
        "foo",
        DataTypes.objectType({
          id: "s4",
          attributes: [
            Attributes.buildAttribute("5", "bar", DataTypes.stringType()),
            Attributes.buildAttribute("6", "baz", DataTypes.stringType()),
          ],
        }),
      ),
    ],
  });

  const action: MappingAction = {
    id: "actionId",
    sources: [
      { type: "attribute", kind: "body", path: ["1", "2"] },
      { type: "attribute", kind: "body", path: ["1", "3"] },
    ],
    target: { type: "attribute", kind: "body", path: ["1", "2"] },
  };

  it("should migrate sources when schema kind is SOURCE", () => {
    const result = migrateAction(
      action,
      SchemaKind.SOURCE,
      [],
      fromType,
      toType,
    );

    expect(result).toEqual({
      id: "actionId",
      sources: [
        { type: "attribute", kind: "body", path: ["4", "5"] },
        { type: "attribute", kind: "body", path: ["4", "6"] },
      ],
      target: { type: "attribute", kind: "body", path: ["1", "2"] },
    });
  });

  it("should migrate target when schema kind is TARGET", () => {
    const result = migrateAction(
      action,
      SchemaKind.TARGET,
      [],
      fromType,
      toType,
    );

    expect(result).toEqual({
      id: "actionId",
      sources: [
        { type: "attribute", kind: "body", path: ["1", "2"] },
        { type: "attribute", kind: "body", path: ["1", "3"] },
      ],
      target: { type: "attribute", kind: "body", path: ["4", "5"] },
    });
  });

  it("should return null when no sources remain after migration", () => {
    const result = migrateAction(
      action,
      SchemaKind.SOURCE,
      [],
      fromType,
      DataTypes.objectType({ id: "", attributes: [] }),
    );

    expect(result).toBeNull();
  });

  it("should return null when target cannot be migrated and schema kind is TARGET", () => {
    const result = migrateAction(
      action,
      SchemaKind.TARGET,
      [],
      fromType,
      DataTypes.objectType({ id: "", attributes: [] }),
    );

    expect(result).toBeNull();
  });

  it("should preserve references to constants as sources when schema kind is SOURCE", () => {
    const constantRef: ConstantReference = {
      type: "constant",
      constantId: "const1",
    };
    const actionWithMixed: MappingAction = {
      ...action,
      sources: [...action.sources, constantRef],
    };

    const result = migrateAction(
      actionWithMixed,
      SchemaKind.SOURCE,
      [],
      fromType,
      toType,
    );

    expect(result).toEqual({
      id: "actionId",
      sources: [
        { type: "attribute", kind: "body", path: ["4", "5"] },
        { type: "attribute", kind: "body", path: ["4", "6"] },
        constantRef,
      ],
      target: { type: "attribute", kind: "body", path: ["1", "2"] },
    });
  });

  it("should preserve metadata when migrating action", () => {
    const actionWithMetadata: MappingAction = {
      ...action,
      metadata: { foo: "bar" },
    };

    const result = migrateAction(
      actionWithMetadata,
      SchemaKind.SOURCE,
      [],
      fromType,
      toType,
    );

    expect(result?.metadata).toEqual({ foo: "bar" });
  });
});

describe("migrateActions", () => {
  const action1: MappingAction = {
    id: "action1",
    sources: [
      {
        type: "attribute",
        kind: "property",
        path: ["1", "2"],
      },
    ],
    target: {
      type: "attribute",
      kind: "property",
      path: ["1", "2"],
    },
  };

  const action2: MappingAction = {
    id: "action2",
    sources: [
      {
        type: "attribute",
        kind: "property",
        path: ["1", "3"],
      },
    ],
    target: {
      type: "attribute",
      kind: "property",
      path: ["1", "3"],
    },
  };

  const fromType = DataTypes.objectType({
    id: "s",
    attributes: [
      Attributes.buildAttribute(
        "1",
        "foo",
        DataTypes.objectType({
          id: "s2",
          attributes: [
            Attributes.buildAttribute("2", "bar", DataTypes.stringType()),
            Attributes.buildAttribute("3", "baz", DataTypes.stringType()),
          ],
        }),
      ),
    ],
  });

  const toType = DataTypes.objectType({
    id: "s3",
    attributes: [
      Attributes.buildAttribute(
        "4",
        "foo",
        DataTypes.objectType({
          id: "s4",
          attributes: [
            Attributes.buildAttribute("5", "bar", DataTypes.stringType()),
            Attributes.buildAttribute("6", "baz", DataTypes.stringType()),
          ],
        }),
      ),
    ],
  });

  it("should return unchanged actions when from type is null", () => {
    const result = migrateActions(
      [action1, action2],
      SchemaKind.SOURCE,
      ["prefix"],
      null,
      toType,
    );

    expect(result).toEqual([action1, action2]);
  });

  it("should return unchanged actions when to type is null", () => {
    const result = migrateActions(
      [action1, action2],
      SchemaKind.SOURCE,
      ["prefix"],
      fromType,
      null,
    );

    expect(result).toEqual([action1, action2]);
  });

  it("should migrate all actions when both from and to types are provided", () => {
    const result = migrateActions(
      [action1, action2],
      SchemaKind.SOURCE,
      [],
      fromType,
      toType,
    );

    expect(result).toEqual([
      {
        id: "action1",
        sources: [
          {
            type: "attribute",
            kind: "property",
            path: ["4", "5"],
          },
        ],
        target: {
          type: "attribute",
          kind: "property",
          path: ["1", "2"],
        },
      },
      {
        id: "action2",
        sources: [
          {
            type: "attribute",
            kind: "property",
            path: ["4", "6"],
          },
        ],
        target: {
          type: "attribute",
          kind: "property",
          path: ["1", "3"],
        },
      },
    ]);
  });

  it("should keep original action when migration fails", () => {
    const result = migrateActions(
      [action1, action2],
      SchemaKind.SOURCE,
      ["nonexistent"],
      fromType,
      toType,
    );
    expect(result).toEqual([action1, action2]);
  });

  it("should handle empty actions list", () => {
    const result = migrateActions([], SchemaKind.SOURCE, [], fromType, toType);

    expect(result).toEqual([]);
  });
});
