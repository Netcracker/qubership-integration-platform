import { TransformationExpressionVerifier } from "../../../src/mapper/verification/transformations.ts";
import { TransformationParameterDetail } from "../../../src/mapper/verification/base.ts";
import {
  MappingAction,
  MappingDescription,
} from "../../../src/mapper/model/model.ts";
import { AttributeDetail } from "../../../src/mapper/util/schema.ts";
import { MappingActions } from "../../../src/mapper/util/actions.ts";

jest.mock("../../../src/mapper/util/actions.ts", () => ({
  MappingActions: {
    getSourcesDetail: jest.fn(),
  },
}));

describe("TransformationExpressionVerifier", () => {
  const arrayRootMapping: MappingDescription = {
    source: {
      headers: [],
      properties: [],
      body: {
        name: "array",
        itemType: {
          name: "object",
          schema: {
            id: "schema1",
            attributes: [
              { id: "a1", name: "field", type: { name: "string" } },
            ],
          },
        },
      },
    },
    target: {
      headers: [],
      properties: [],
      body: {
        name: "object",
        schema: {
          id: "target-schema",
          attributes: [],
        },
      },
    },
    constants: [],
    actions: [],
  };

  const bodyFieldSource: AttributeDetail = {
    kind: "body",
    path: [{ id: "a1", name: "field", type: { name: "string" } }],
    definitions: [],
  };

  const action: MappingAction = {
    id: "action1",
    sources: [{ type: "constant", kind: "body", path: ["a1"] }],
    target: {
      kind: "body",
      path: [],
      type: "constant"},
    transformation: {
      name: "expression",
      parameters: ["body._.field"],
    },
  };

  const entity: TransformationParameterDetail = {
    action,
    mapping: arrayRootMapping,
    parameterValue: "body._.field",
    parameterIndex: 0,
  };

  beforeEach(() => {
    jest.mocked(MappingActions.getSourcesDetail).mockReturnValue([
      bodyFieldSource,
    ]);
  });

  it("should validate expressions using the mapping source schema", () => {
    expect(new TransformationExpressionVerifier().verify(entity)).toEqual([]);
  });

  it("should return errors for invalid references in expressions", () => {

    const invalidEntity: TransformationParameterDetail = {
      ...entity,
      parameterValue: "body._.missing",
    };

    const errors = new TransformationExpressionVerifier().verify(invalidEntity);
    expect(errors).toHaveLength(1);
    expect(errors[0].message).toContain("Unknown attribute");
  });
});
