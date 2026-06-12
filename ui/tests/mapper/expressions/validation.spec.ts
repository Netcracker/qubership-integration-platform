import {
  referenceIsValid,
  validateExpression,
} from "../../../src/mapper/expressions/validation.ts";
import { AttributeDetail } from "../../../src/mapper/util/schema.ts";
import {
  Constant,
  MessageSchema,
} from "../../../src/mapper/model/model.ts";
import { parse } from "../../../src/mapper/expressions/parser.ts";
import { processReferences } from "../../../src/mapper/expressions/references.ts";
import { ReferenceNode } from "../../../src/mapper/expressions/model.ts";
import { DataTypes } from "../../../src/mapper/util/types.ts";

describe("expression validation", () => {
  const arrayRootSourceSchema: MessageSchema = {
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
  };

  const bodyFieldDetail: AttributeDetail = {
    kind: "body",
    path: [{ id: "a1", name: "field", type: { name: "string" } }],
    definitions: [],
  };

  const constants: Constant[] = [
    {
      id: "c1",
      name: "foo",
      valueSupplier: { kind: "given", value: "bar" },
      type: DataTypes.stringType(),
    },
  ];

  describe("referenceIsValid", () => {
    it("should accept body references with array-root placeholder path", () => {
      const expression = parse("body._.field");
      const references: ReferenceNode[] = [];
      processReferences(expression, (node) => references.push(node));

      expect(
        referenceIsValid(
          references[0],
          [bodyFieldDetail],
          constants,
          arrayRootSourceSchema,
        ),
      ).toBe(true);
    });

    it("should reject unknown body references", () => {
      const expression = parse("body._.missing");
      const references: ReferenceNode[] = [];
      processReferences(expression, (node) => references.push(node));

      expect(
        referenceIsValid(
          references[0],
          [bodyFieldDetail],
          constants,
          arrayRootSourceSchema,
        ),
      ).toBe(false);
    });
  });

  describe("validateExpression", () => {
    it("should not report errors for valid array-root body references", () => {
      const errors: string[] = [];
      validateExpression(
        "body._.field",
        [bodyFieldDetail],
        constants,
        (_location, message) => errors.push(message),
        arrayRootSourceSchema,
      );
      expect(errors).toEqual([]);
    });

    it("should report errors for unknown references", () => {
      const errors: string[] = [];
      validateExpression(
        "body._.missing",
        [bodyFieldDetail],
        constants,
        (_location, message) => errors.push(message),
        arrayRootSourceSchema,
      );
      expect(errors).toHaveLength(1);
      expect(errors[0]).toContain("Unknown attribute");
    });

    it("should report parse errors", () => {
      const errors: string[] = [];
      validateExpression(
        "body +",
        [bodyFieldDetail],
        constants,
        (_location, message) => errors.push(message),
        arrayRootSourceSchema,
      );
      expect(errors.length).toBeGreaterThan(0);
    });
  });
});
