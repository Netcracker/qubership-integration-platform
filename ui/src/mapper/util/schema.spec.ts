import { MessageSchemaUtil } from "./schema.ts";
import {
  Attribute,
  AttributeKind,
  AttributeReference,
  DataType,
  MessageSchema,
  ObjectType,
} from "../model/model.ts";

describe("Mapper", () => {
  describe("MessageSchemaUtil", () => {
    const messageSchema: MessageSchema = {
      headers: [
        {
          id: "foo",
          name: "header1",
          type: { name: "string" },
          defaultValue: "bla-bla-bla",
        },
        { id: "bar", name: "header2", type: { name: "number" } },
      ],
      properties: [
        { id: "baz", name: "property1", type: { name: "string" } },
        { id: "biz", name: "property2", type: { name: "number" } },
      ],
      body: {
        name: "object",
        schema: {
          id: "schema1",
          attributes: [
            {
              id: "bar",
              name: "attribute1",
              type: {
                name: "object",
                schema: {
                  id: "schema3",
                  attributes: [
                    { id: "fiz", name: "attribute2", type: { name: "string" } },
                  ],
                },
              },
            },
          ],
        },
      },
    };

    describe("getMessageSchemaKey", () => {
      it('should return "properties" for "property"', () => {
        expect(MessageSchemaUtil.getMessageSchemaKey("property")).toEqual(
          "properties",
        );
      });

      it('should return "headers" for "header"', () => {
        expect(MessageSchemaUtil.getMessageSchemaKey("header")).toEqual(
          "headers",
        );
      });

      it('should return "body" for "body"', () => {
        expect(MessageSchemaUtil.getMessageSchemaKey("body")).toEqual("body");
      });

      it("should return null value if attribute kind is null or undefined", () => {
        [undefined, null].forEach((kind: AttributeKind) => {
          expect(MessageSchemaUtil.getMessageSchemaKey(kind)).toBeNull();
        });
      });
    });

    describe("getMessageSchemaAttributes", () => {
      it('should return header attributes list when kind is "header"', () => {
        expect(
          MessageSchemaUtil.getMessageSchemaAttributes(messageSchema, "header"),
        ).toEqual(messageSchema.headers);
      });

      it('should return property attributes when kind is "property"', () => {
        expect(
          MessageSchemaUtil.getMessageSchemaAttributes(
            messageSchema,
            "property",
          ),
        ).toEqual(messageSchema.properties);
      });

      it('should return list with single attribute that have the body type when kind is "body" and body is not null or undefined', () => {
        expect(
          MessageSchemaUtil.getMessageSchemaAttributes(messageSchema, "body"),
        ).toEqual([expect.objectContaining({ type: messageSchema.body })]);
      });

      it('should return empty list when body is undefined or null and kind is "body"', () => {
        [undefined, null].forEach((body: DataType) => {
          expect(
            MessageSchemaUtil.getMessageSchemaAttributes(
              { ...messageSchema, body },
              "body",
            ),
          ).toEqual([]);
        });
      });
    });

    describe("getScope", () => {
      it('should return body type when kind is "body"', () => {
        expect(MessageSchemaUtil.getScope(messageSchema, "body")).toEqual(
          messageSchema.body,
        );
      });

      it('should return ObjectType with attributes that match schema headers when kind is "header"', () => {
        expect(MessageSchemaUtil.getScope(messageSchema, "header")).toEqual(
          expect.objectContaining({
            name: "object",
            schema: expect.objectContaining({
              attributes: messageSchema.headers,
            }) as MessageSchema,
          }),
        );
      });

      it('should return ObjectType with attributes that match schema properties when kind is "property"', () => {
        expect(MessageSchemaUtil.getScope(messageSchema, "property")).toEqual(
          expect.objectContaining({
            name: "object",
            schema: expect.objectContaining({
              attributes: messageSchema.properties,
            }) as MessageSchema,
          }),
        );
      });

      it("should return NullType when kind is null or undefined", () => {
        [undefined, null].forEach((kind: AttributeKind) => {
          expect(MessageSchemaUtil.getScope(messageSchema, kind)).toEqual(
            expect.objectContaining({ name: "null" }),
          );
        });
      });
    });

    describe("findAttribute", () => {
      it("should test all the attributes in schema to match the predicate", () => {
        const predicate = jest.fn();
        MessageSchemaUtil.findAttribute(messageSchema, predicate);
        expect(predicate).toHaveBeenCalledTimes(7);
        expect(predicate).toHaveBeenCalledWith(
          messageSchema.headers[0],
          "header",
          [messageSchema.headers[0]],
        );
        expect(predicate).toHaveBeenCalledWith(
          messageSchema.headers[1],
          "header",
          [messageSchema.headers[1]],
        );
        expect(predicate).toHaveBeenCalledWith(
          messageSchema.properties[0],
          "property",
          [messageSchema.properties[0]],
        );
        expect(predicate).toHaveBeenCalledWith(
          messageSchema.properties[1],
          "property",
          [messageSchema.properties[1]],
        );
        expect(predicate).toHaveBeenCalledWith(
          expect.objectContaining({ type: messageSchema.body }),
          "body",
          [],
        );
        expect(predicate).toHaveBeenCalledWith(
          (messageSchema.body as ObjectType).schema.attributes[0],
          "body",
          [(messageSchema.body as ObjectType).schema.attributes[0]],
        );
        expect(predicate).toHaveBeenCalledWith(
          { id: "fiz", name: "attribute2", type: { name: "string" } },
          "body",
          [
            (messageSchema.body as ObjectType).schema.attributes[0],
            { id: "fiz", name: "attribute2", type: { name: "string" } },
          ],
        );
      });

      it("should return null when attribute that matches predicate not exists", () => {
        expect(
          MessageSchemaUtil.findAttribute(messageSchema, () => false),
        ).toBeNull();
      });

      it("should return details for attribute for which predicate returns true", () => {
        expect(
          MessageSchemaUtil.findAttribute(
            messageSchema,
            (_a, _k, p) => p.length === 2,
          ),
        ).toEqual({
          kind: "body",
          path: [
            (messageSchema.body as ObjectType).schema.attributes[0],
            { id: "fiz", name: "attribute2", type: { name: "string" } },
          ],
          definitions: [],
        });
      });
    });

    describe("findAttributeByPath", () => {
      it("should return null when an attribute with the specified path not exists", () => {
        expect(
          MessageSchemaUtil.findAttributeByPath(messageSchema, "property", [
            "bar",
            "fiz",
          ]),
        ).toBeNull();
      });

      it("should return details for an attribute that has the specified path", () => {
        expect(
          MessageSchemaUtil.findAttributeByPath(messageSchema, "body", [
            "attribute1",
            "attribute2",
          ]),
        ).toEqual({
          kind: "body",
          path: [
            (messageSchema.body as ObjectType).schema.attributes[0],
            { id: "fiz", name: "attribute2", type: { name: "string" } },
          ],
          definitions: [],
        });
      });
    });

    describe("restorePath", () => {
      it("should return null when path not exists", () => {
        const reference: AttributeReference = {
          type: "attribute",
          kind: "body",
          path: ["foo", "bar"],
        };
        expect(
          MessageSchemaUtil.restorePath(messageSchema, reference),
        ).toBeNull();
      });

      it("should return attributes list that corresponds the specified path", () => {
        const reference: AttributeReference = {
          type: "attribute",
          kind: "body",
          path: ["bar", "fiz"],
        };
        expect(MessageSchemaUtil.restorePath(messageSchema, reference)).toEqual(
          [
            (messageSchema.body as ObjectType).schema.attributes[0],
            { id: "fiz", name: "attribute2", type: { name: "string" } },
          ],
        );
      });
    });

    describe("attributeExists", () => {
      it("should return false when referenced attribute does not exist in schema", () => {
        const reference: AttributeReference = {
          type: "attribute",
          kind: "body",
          path: ["foo", "bar"],
        };
        expect(
          MessageSchemaUtil.attributeExists(messageSchema, reference),
        ).toBeFalsy();
      });

      it("should return true when referenced attribute exists in schema", () => {
        const reference: AttributeReference = {
          type: "attribute",
          kind: "body",
          path: ["bar", "fiz"],
        };
        expect(
          MessageSchemaUtil.attributeExists(messageSchema, reference),
        ).toBeTruthy();
      });
    });

    describe("removeAttribute", () => {
      it("should return a message schema without a specified attribute when kind is body", () => {
        const path: Attribute[] = [
          (messageSchema.body as ObjectType).schema.attributes[0],
          { id: "fiz", name: "attribute2", type: { name: "string" } },
        ];
        expect(
          MessageSchemaUtil.removeAttribute(messageSchema, "body", path),
        ).toEqual({
          ...messageSchema,
          body: {
            name: "object",
            schema: {
              // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
              id: expect.anything(),
              attributes: [
                {
                  id: "bar",
                  name: "attribute1",
                  type: {
                    name: "object",
                    schema: {
                      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
                      id: expect.anything(),
                      attributes: [],
                    },
                  },
                },
              ],
            },
          },
        });
      });

      it("should return a message schema without a specified attribute when kind is header or property", () => {
        expect(
          MessageSchemaUtil.removeAttribute(messageSchema, "header", [
            messageSchema.headers[0],
          ]),
        ).toEqual({
          ...messageSchema,
          headers: messageSchema.headers.slice(1),
        });
        expect(
          MessageSchemaUtil.removeAttribute(messageSchema, "property", [
            messageSchema.properties[0],
          ]),
        ).toEqual({
          ...messageSchema,
          properties: messageSchema.properties.slice(1),
        });
      });
    });

    describe("updateAttribute", () => {
      it("should throw exception when an attribute with the same name exists", () => {
        expect(
          () =>
            MessageSchemaUtil.updateAttribute(messageSchema, "header", [], {
              id: "foo",
              name: "header2",
              type: { name: "string" },
            }),
          // eslint-disable-next-line @typescript-eslint/no-unsafe-argument
        ).toThrow(expect.anything());
      });

      it('should return schema with the updated attribute when kind is "header" or "property"', () => {
        expect(
          MessageSchemaUtil.updateAttribute(messageSchema, "header", [], {
            ...messageSchema.headers[0],
            name: "h1",
          }),
        ).toEqual({
          ...messageSchema,
          headers: [
            {
              id: "foo",
              name: "h1",
              type: { name: "string" },
              defaultValue: "bla-bla-bla",
            },
            { id: "bar", name: "header2", type: { name: "number" } },
          ],
        });

        expect(
          MessageSchemaUtil.updateAttribute(messageSchema, "property", [], {
            ...messageSchema.properties[0],
            name: "p1",
          }),
        ).toEqual({
          ...messageSchema,
          properties: [
            { id: "baz", name: "p1", type: { name: "string" } },
            { id: "biz", name: "property2", type: { name: "number" } },
          ],
        });
      });

      it('should return schema with the updated attribute when kind is "body"', () => {
        expect(
          MessageSchemaUtil.updateAttribute(
            messageSchema,
            "body",
            [(messageSchema.body as ObjectType).schema.attributes[0]],
            { id: "fiz", name: "foo", type: { name: "string" } },
          ),
        ).toEqual({
          ...messageSchema,
          body: {
            name: "object",
            schema: {
              // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
              id: expect.anything(),
              attributes: [
                {
                  id: "bar",
                  name: "attribute1",
                  type: {
                    name: "object",
                    schema: {
                      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
                      id: expect.anything(),
                      attributes: [
                        { id: "fiz", name: "foo", type: { name: "string" } },
                      ],
                    },
                  },
                },
              ],
            },
          },
        });
      });

      it("should add the attribute to schema when an attribute with specified ID does not exist", () => {
        expect(
          MessageSchemaUtil.updateAttribute(
            messageSchema,
            "body",
            [(messageSchema.body as ObjectType).schema.attributes[0]],
            { id: "qux", name: "foo", type: { name: "string" } },
          ),
        ).toEqual({
          ...messageSchema,
          body: {
            name: "object",
            schema: {
              // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
              id: expect.anything(),
              attributes: [
                {
                  id: "bar",
                  name: "attribute1",
                  type: {
                    name: "object",
                    schema: {
                      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
                      id: expect.anything(),
                      attributes: [
                        {
                          id: "fiz",
                          name: "attribute2",
                          type: { name: "string" },
                        },
                        { id: "qux", name: "foo", type: { name: "string" } },
                      ],
                    },
                  },
                },
              ],
            },
          },
        });
      });
    });

    describe("clearAttributes", () => {
      it('should clear headers when kind is "header"', () => {
        expect(
          MessageSchemaUtil.clearAttributes(messageSchema, "header", []),
        ).toEqual({
          ...messageSchema,
          headers: [],
        });
      });

      it('should clear properties when kind is "property" and path is empty', () => {
        expect(
          MessageSchemaUtil.clearAttributes(messageSchema, "property", []),
        ).toEqual({
          ...messageSchema,
          properties: [],
        });
      });

      it('should clear attribute list of the ObjectType schema for the specified path when kind is "body"', () => {
        expect(
          MessageSchemaUtil.clearAttributes(messageSchema, "body", [
            (messageSchema.body as ObjectType).schema.attributes[0],
          ]),
        ).toEqual({
          ...messageSchema,
          body: {
            name: "object",
            schema: {
              // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
              id: expect.anything(),
              attributes: [
                {
                  id: "bar",
                  name: "attribute1",
                  type: {
                    name: "object",
                    schema: {
                      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
                      id: expect.anything(),
                      attributes: [],
                    },
                  },
                },
              ],
            },
          },
        });
      });
    });

    describe("replaceAttributeType", () => {
      it("should return schema with replaced attribute type", () => {
        expect(
          MessageSchemaUtil.replaceAttributeType(
            messageSchema,
            "header",
            [messageSchema.headers[0]],
            {
              name: "boolean",
            },
          ),
        ).toEqual({
          ...messageSchema,
          headers: [
            {
              id: "foo",
              name: "header1",
              type: { name: "boolean" },
              defaultValue: "bla-bla-bla",
            },
            { id: "bar", name: "header2", type: { name: "number" } },
          ],
        });
      });

      it("should remove default value when a corresponding flag is specified", () => {
        expect(
          MessageSchemaUtil.replaceAttributeType(
            messageSchema,
            "header",
            [messageSchema.headers[0]],
            { name: "boolean" },
            true,
          ),
        ).toEqual({
          ...messageSchema,
          headers: [
            {
              id: "foo",
              name: "header1",
              type: { name: "boolean" },
              defaultValue: undefined,
            },
            { id: "bar", name: "header2", type: { name: "number" } },
          ],
        });
      });
    });

    describe("updateDataFormat", () => {
      it("should update a data format key-value in metadata of the target attribute", () => {
        expect(
          MessageSchemaUtil.updateDataFormat(messageSchema, [], "XML"),
        ).toEqual({
          ...messageSchema,
          body: { ...messageSchema.body, metadata: { dataFormat: "XML" } },
        });

        expect(
          MessageSchemaUtil.updateDataFormat(
            messageSchema,
            [(messageSchema.body as ObjectType).schema.attributes[0]],
            "XML",
          ),
        ).toEqual({
          ...messageSchema,
          body: {
            name: "object",
            schema: {
              // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
              id: expect.anything(),
              attributes: [
                {
                  id: "bar",
                  name: "attribute1",
                  type: {
                    name: "object",
                    schema: {
                      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
                      id: expect.anything(),
                      attributes: [
                        {
                          id: "fiz",
                          name: "attribute2",
                          type: { name: "string" },
                        },
                      ],
                    },
                    metadata: { dataFormat: "XML" },
                  },
                },
              ],
            },
          },
        });
      });
    });

    describe("updateXmlNamespaces", () => {
      const schema: MessageSchema = {
        ...messageSchema,
        body: {
          name: "object",
          schema: {
            id: "schema1",
            attributes: [
              {
                id: "a",
                name: "foo:root",
                type: {
                  name: "object",
                  schema: {
                    id: "schema2",
                    attributes: [
                      { id: "0", name: "foo:bar", type: { name: "string" } },
                      { id: "1", name: "baz:biz", type: { name: "string" } },
                      { id: "2", name: "@foo:fiz", type: { name: "string" } },
                      { id: "3", name: "@baz:qux", type: { name: "string" } },
                      { id: "4", name: "fiz", type: { name: "string" } },
                    ],
                  },
                  metadata: {
                    xmlNamespaces: [
                      { alias: "foo", uri: "foo" },
                      { alias: "bar", uri: "http://localhost/bar" },
                    ],
                  },
                },
              },
            ],
          },
        },
      };

      it("should return schema with updated namespaces and names of attributes", () => {
        expect(
          MessageSchemaUtil.updateXmlNamespaces(
            schema,
            [(schema.body as ObjectType).schema.attributes[0]],
            [
              { alias: "biz", uri: "foo" },
              { alias: "qux", uri: "qux" },
            ],
          ),
        ).toEqual({
          ...schema,
          body: {
            name: "object",
            schema: {
              // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
              id: expect.anything(),
              attributes: [
                {
                  id: "a",
                  name: "biz:root",
                  type: {
                    name: "object",
                    schema: {
                      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
                      id: expect.anything(),
                      attributes: [
                        { id: "0", name: "biz:bar", type: { name: "string" } },
                        { id: "1", name: "baz:biz", type: { name: "string" } },
                        { id: "2", name: "@biz:fiz", type: { name: "string" } },
                        { id: "3", name: "@baz:qux", type: { name: "string" } },
                        { id: "4", name: "fiz", type: { name: "string" } },
                      ],
                    },
                    metadata: {
                      xmlNamespaces: [
                        { alias: "biz", uri: "foo" },
                        { alias: "qux", uri: "qux" },
                      ],
                    },
                    definitions: [],
                  },
                },
              ],
            },
          },
        });
      });
    });
  });
});
