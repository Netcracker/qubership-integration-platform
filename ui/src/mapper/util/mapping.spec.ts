import { MappingUtil } from "./mapping.ts";
import {
  AttributeReference,
  Constant,
  ConstantReference,
  MappingDescription,
  MessageSchema,
} from "../model/model.ts";

describe("Mapper", () => {
  describe("MappingUtil", () => {
    describe("generateUUID", () => {
      it("should return some non-empty string", () => {
        expect(MappingUtil.generateUUID()).toMatch(/^[0-9a-fA-F]+$/);
      });
    });

    describe("emptyMapping", () => {
      it("should return mapping description with empty source and target schemas, constant and action lists", () => {
        expect(MappingUtil.emptyMapping()).toEqual({
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [],
          actions: [],
        });
      });
    });

    describe("emptyObjectSchema", () => {
      it("should return schema with empty attribute list", () => {
        expect(MappingUtil.emptyObjectSchema().attributes).toEqual([]);
      });

      it("should return schema with random ID", () => {
        expect(MappingUtil.emptyObjectSchema().id).toMatch(/^[0-9a-fA-F]+$/);
      });
    });

    describe("isEmpty", () => {
      it("should return true when schema is undefined or null", () => {
        ([undefined, null] as unknown as MessageSchema[]).forEach((schema: MessageSchema) => {
          expect(MappingUtil.isEmpty(schema), String(schema as unknown))
            .toBeTruthy();
        });
      });

      it("should return false when schema has properties", () => {
        const schema: MessageSchema = {
          properties: [{ id: "foo", name: "bar", type: { name: "string" } }],
          headers: [],
          body: null,
        };
        expect(MappingUtil.isEmpty(schema)).toBeFalsy();
      });

      it("should return false when schema has headers", () => {
        const schema: MessageSchema = {
          properties: [],
          headers: [{ id: "foo", name: "bar", type: { name: "string" } }],
          body: null,
        };
        expect(MappingUtil.isEmpty(schema)).toBeFalsy();
      });

      it("should return false when body type is defined in schema ", () => {
        const schema: MessageSchema = {
          properties: [],
          headers: [],
          body: { name: "string" },
        };
        expect(MappingUtil.isEmpty(schema)).toBeFalsy();
      });

      it("should return true when schema has no properties and headers, and body type not set", () => {
        expect(
          MappingUtil.isEmpty({ properties: [], headers: [], body: null }),
        ).toBeTruthy();
      });
    });

    describe("objectIsEmpty", () => {
      it("should return true when object has no fields", () => {
        expect(MappingUtil.objectIsEmpty({})).toBeTruthy();
      });

      it("should return false when object has fields", () => {
        expect(MappingUtil.objectIsEmpty({ foo: "bar" })).toBeFalsy();
      });
    });

    describe("constantExists", () => {
      it("should return false when there in no constant exists in the mapping that matches a predicate", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "bar",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [],
        };
        expect(
          MappingUtil.constantExists(mapping, () => false),
        ).toBeFalsy();
      });

      it("should return true when there is a constant in the mapping that matches a predicate", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "bar",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [],
        };
        expect(
          MappingUtil.constantExists(
            mapping,
            (constant) => constant.name === "bar",
          ),
        ).toBeTruthy();
      });
    });

    describe("findConstant", () => {
      it("should return undefined value when there is no constant exists in the mapping that matches a predicate", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "bar",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [],
        };
        expect(
          MappingUtil.findConstant(mapping, () => false),
        ).toBeUndefined();
      });

      it("should return constant that matches a predicate", () => {
        const constant: Constant = {
          id: "foo",
          name: "bar",
          type: { name: "string" },
          valueSupplier: null,
        };
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [constant],
          actions: [],
        };
        expect(
          MappingUtil.findConstant(
            mapping,
            (constant) => constant.name === "bar",
          ),
        ).toEqual(constant);
      });
    });

    describe("findConstantById", () => {
      it("should return undefined value when there is no constant with specified ID exists", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "bar",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [],
        };
        expect(MappingUtil.findConstantById(mapping, "bar")).toBeUndefined();
      });

      it("should return constant that has the specified ID", () => {
        const constant: Constant = {
          id: "foo",
          name: "bar",
          type: { name: "string" },
          valueSupplier: null,
        };
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [constant],
          actions: [],
        };
        expect(MappingUtil.findConstantById(mapping, "foo")).toEqual(constant);
      });
    });

    describe("findConstantByName", () => {
      it("should return undefined value when there is no constant with specified name exists", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "bar",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [],
        };
        expect(MappingUtil.findConstantByName(mapping, "foo")).toBeUndefined();
      });

      it("should return constant that has the specified name", () => {
        const constant: Constant = {
          id: "foo",
          name: "bar",
          type: { name: "string" },
          valueSupplier: null,
        };
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [constant],
          actions: [],
        };
        expect(MappingUtil.findConstantByName(mapping, "bar")).toEqual(
          constant,
        );
      });
    });

    describe("updateConstant", () => {
      it("should return mapping with constant with specified ID replaced with result of modifier function", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "bar",
              type: { name: "string" },
              valueSupplier: null,
            },
            {
              id: "baz",
              name: "biz",
              type: { name: "number" },
              valueSupplier: null,
            },
          ],
          actions: [],
        };
        expect(
          MappingUtil.updateConstant(mapping, "foo", (constant) => ({
            ...constant,
            name: "fiz",
          })),
        ).toEqual({
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "fiz",
              type: { name: "string" },
              valueSupplier: null,
            },
            {
              id: "baz",
              name: "biz",
              type: { name: "number" },
              valueSupplier: null,
            },
          ],
          actions: [],
        });
      });
    });

    describe("updateAction", () => {
      it("should return mapping with action with specified ID replaced with result of modifier function", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [],
          actions: [
            { id: "foo", sources: [], target: null, transformation: null },
            { id: "baz", sources: [], target: null, transformation: null },
          ],
        };
        expect(
          MappingUtil.updateAction(mapping, "foo", (action) => ({
            ...action,
            metadata: { a: "b" },
          })),
        ).toEqual({
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [],
          actions: [
            {
              id: "foo",
              sources: [],
              target: null,
              transformation: null,
              metadata: { a: "b" },
            },
            { id: "baz", sources: [], target: null, transformation: null },
          ],
        });
      });
    });

    describe("updateTransformation", () => {
      it("should return mapping in which a transformation is replaced in the action with specified ID", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [],
          actions: [
            { id: "foo", sources: [], target: null, transformation: null },
            { id: "baz", sources: [], target: null, transformation: null },
          ],
        };
        const transformation = { name: "a", parameters: ["b", "c"] };
        expect(
          MappingUtil.updateTransformation(mapping, "foo", transformation),
        ).toEqual({
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [],
          actions: [
            { id: "foo", sources: [], target: null, transformation },
            { id: "baz", sources: [], target: null, transformation: null },
          ],
        });
      });
    });

    describe("targetExists", () => {
      it("should return true when referenced attribute exists in target schema", () => {
        const reference: AttributeReference = {
          type: "attribute",
          kind: "property",
          path: ["foo"],
        };
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: {
            headers: [],
            properties: [{ id: "foo", name: "bar", type: { name: "string" } }],
            body: null,
          },
          constants: [],
          actions: [],
        };
        expect(MappingUtil.targetExists(mapping, reference)).toBeTruthy();
      });

      it("should return false when referenced attribute does not exist in target schema", () => {
        const reference: AttributeReference = {
          type: "attribute",
          kind: "body",
          path: ["foo", "bar"],
        };
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [],
          actions: [],
        };
        expect(MappingUtil.targetExists(mapping, reference)).toBeFalsy();
      });
    });

    describe("sourceExists", () => {
      it("should return true when referenced attribute exists in source schema", () => {
        const reference: AttributeReference = {
          type: "attribute",
          kind: "property",
          path: ["foo"],
        };
        const mapping: MappingDescription = {
          source: {
            headers: [],
            properties: [{ id: "foo", name: "bar", type: { name: "string" } }],
            body: null,
          },
          target: { headers: [], properties: [], body: null },
          constants: [],
          actions: [],
        };
        expect(MappingUtil.sourceExists(mapping, reference)).toBeTruthy();
      });

      it("should return true when referenced constant exists", () => {
        const reference: ConstantReference = {
          type: "constant",
          constantId: "foo",
        };
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "bar",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [],
        };
        expect(MappingUtil.sourceExists(mapping, reference)).toBeTruthy();
      });

      it("should return false when referenced attribute does not exist in source schema", () => {
        const reference: AttributeReference = {
          type: "attribute",
          kind: "property",
          path: ["bar"],
        };
        const mapping: MappingDescription = {
          source: {
            headers: [],
            properties: [{ id: "foo", name: "bar", type: { name: "string" } }],
            body: null,
          },
          target: { headers: [], properties: [], body: null },
          constants: [],
          actions: [],
        };
        expect(MappingUtil.sourceExists(mapping, reference)).toBeFalsy();
      });

      it("should return false when referenced constant does not exist", () => {
        const reference: ConstantReference = {
          type: "constant",
          constantId: "bar",
        };
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "foo",
              name: "bar",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [],
        };
        expect(MappingUtil.sourceExists(mapping, reference)).toBeFalsy();
      });
    });

    describe("removeDanglingActions", () => {
      it("should return mapping in which actions with empty sources list are filtered out", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: {
            headers: [],
            properties: [{ id: "bar", name: "baz", type: { name: "string" } }],
            body: null,
          },
          constants: [],
          actions: [
            {
              id: "foo",
              sources: [],
              target: { type: "attribute", kind: "property", path: ["bar"] },
              transformation: null,
            },
          ],
        };
        expect(MappingUtil.removeDanglingActions(mapping)).toEqual({
          source: { headers: [], properties: [], body: null },
          target: {
            headers: [],
            properties: [{ id: "bar", name: "baz", type: { name: "string" } }],
            body: null,
          },
          constants: [],
          actions: [],
        });
      });

      it("should filter out non-existent sources from actions", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: {
            headers: [],
            properties: [{ id: "bar", name: "baz", type: { name: "string" } }],
            body: null,
          },
          constants: [
            {
              id: "fiz",
              name: "qux",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [
            {
              id: "foo",
              sources: [
                { type: "constant", constantId: "fiz" },
                { type: "attribute", kind: "body", path: ["foo", "bar"] },
              ],
              target: { type: "attribute", kind: "property", path: ["bar"] },
              transformation: null,
            },
          ],
        };
        expect(MappingUtil.removeDanglingActions(mapping)).toEqual({
          source: { headers: [], properties: [], body: null },
          target: {
            headers: [],
            properties: [{ id: "bar", name: "baz", type: { name: "string" } }],
            body: null,
          },
          constants: [
            {
              id: "fiz",
              name: "qux",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [
            {
              id: "foo",
              sources: [{ type: "constant", constantId: "fiz" }],
              target: { type: "attribute", kind: "property", path: ["bar"] },
              transformation: null,
            },
          ],
        });
      });

      it("should return mapping in which actions that has all sources non-exist are filtered out", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: {
            headers: [],
            properties: [{ id: "bar", name: "baz", type: { name: "string" } }],
            body: null,
          },
          constants: [],
          actions: [
            {
              id: "foo",
              sources: [
                { type: "constant", constantId: "fiz" },
                { type: "attribute", kind: "body", path: ["foo", "bar"] },
              ],
              target: { type: "attribute", kind: "property", path: ["bar"] },
              transformation: null,
            },
          ],
        };
        expect(MappingUtil.removeDanglingActions(mapping)).toEqual({
          source: { headers: [], properties: [], body: null },
          target: {
            headers: [],
            properties: [{ id: "bar", name: "baz", type: { name: "string" } }],
            body: null,
          },
          constants: [],
          actions: [],
        });
      });

      it("should return mapping in which actions with non-existent targets are filtered out", () => {
        const mapping: MappingDescription = {
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "fiz",
              name: "qux",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [
            {
              id: "foo",
              sources: [{ type: "constant", constantId: "fiz" }],
              target: { type: "attribute", kind: "property", path: ["bar"] },
              transformation: null,
            },
          ],
        };
        expect(MappingUtil.removeDanglingActions(mapping)).toEqual({
          source: { headers: [], properties: [], body: null },
          target: { headers: [], properties: [], body: null },
          constants: [
            {
              id: "fiz",
              name: "qux",
              type: { name: "string" },
              valueSupplier: null,
            },
          ],
          actions: [],
        });
      });
    });
  });
});
