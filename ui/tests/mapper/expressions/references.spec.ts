import {
  buildAttributeReferenceText,
  buildConstantReferenceText,
  escape,
  getExpressionPathNames,
  processReferences,
} from "../../../src/mapper/expressions/references.ts";
import { AttributeDetail } from "../../../src/mapper/util/schema.ts";
import {
  Constant,
  MessageSchema,
} from "../../../src/mapper/model/model.ts";
import { ReferenceNode } from "../../../src/mapper/expressions/model.ts";
import { parse } from "../../../src/mapper/expressions/parser.ts";
import { DataTypes } from "../../../src/mapper/util/types.ts";

describe("expression references", () => {
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

  const objectRootSourceSchema: MessageSchema = {
    headers: [],
    properties: [],
    body: {
      name: "object",
      schema: {
        id: "schema1",
        attributes: [
          { id: "a1", name: "field", type: { name: "string" } },
        ],
      },
    },
  };

  const bodyFieldDetail: AttributeDetail = {
    kind: "body",
    path: [{ id: "a1", name: "field", type: { name: "string" } }],
    definitions: [],
  };

  describe("escape", () => {
    it("should escape special characters in expression path segments", () => {
      expect(escape("a b")).toEqual("a\\ b");
      expect(escape("plain")).toEqual("plain");
    });
  });

  describe("buildConstantReferenceText", () => {
    it("should build a constant reference with escaped name", () => {
      const constant: Constant = {
        type: DataTypes.integerType(),
        id: "c1",
        name: "my constant",
        valueSupplier: { kind: "given", value: "x" }
      };
      expect(buildConstantReferenceText(constant)).toEqual(
        "constant.my\\ constant",
      );
    });
  });

  describe("getExpressionPathNames", () => {
    it("should return attribute names for non-body kinds", () => {
      expect(
        getExpressionPathNames(
          "header",
          [{ id: "h1", name: "X-Header", type: { name: "string" } }],
          arrayRootSourceSchema,
        ),
      ).toEqual(["X-Header"]);
    });

    it("should prepend an empty segment when body root is an array", () => {
      expect(
        getExpressionPathNames(
          "body",
          bodyFieldDetail.path,
          arrayRootSourceSchema,
        ),
      ).toEqual(["", "field"]);
    });

    it("should not prepend an empty segment when body root is an object", () => {
      expect(
        getExpressionPathNames(
          "body",
          bodyFieldDetail.path,
          objectRootSourceSchema,
        ),
      ).toEqual(["field"]);
    });
  });

  describe("buildAttributeReferenceText", () => {
    it("should build a body reference with array-root placeholder segment", () => {
      expect(
        buildAttributeReferenceText(bodyFieldDetail, arrayRootSourceSchema),
      ).toEqual("body.\\_.field");
    });

    it("should build a body reference without placeholder for object root", () => {
      expect(
        buildAttributeReferenceText(bodyFieldDetail, objectRootSourceSchema),
      ).toEqual("body.field");
    });
  });

  describe("processReferences", () => {
    it("should invoke callback for each reference in an expression", () => {
      const references: ReferenceNode[] = [];
      processReferences(parse("body.field + constant.foo"), (node) =>
        references.push(node),
      );
      expect(references).toHaveLength(2);
      expect(references[0].kind).toEqual("body");
      expect(references[1].kind).toEqual("constant");
    });
  });
});
