import { AsyncApiSchemaResolver } from "./AsyncApiSchemaResolver";

const components = {
  messages: {
    OrderEvent: {
      payload: {
        type: "object",
        properties: {
          order: { $ref: "#/components/schemas/Order" },
          lines: {
            type: "array",
            items: { $ref: "#/components/schemas/Item" },
          },
          meta: { allOf: [{ $ref: "#/components/schemas/Meta" }] },
        },
      },
      headers: { type: "object", properties: { traceId: { type: "string" } } },
    },
    ListEvent: {
      payload: {
        type: "array",
        items: { $ref: "#/components/schemas/Item" },
      },
    },
  },
  schemas: {
    Order: {
      type: "object",
      properties: {
        id: { type: "string" },
        item: { $ref: "#/components/schemas/Item" },
      },
    },
    Item: { type: "object", properties: { sku: { type: "string" } } },
    Meta: { type: "object", properties: { ts: { type: "string" } } },
  },
};

describe("AsyncApiSchemaResolver", () => {
  const resolver = new AsyncApiSchemaResolver();

  it("returns null for refs outside #/components", () => {
    expect(resolver.resolveRef("#/definitions/Foo", components)).toBeNull();
  });

  it("flattens the message payload and stamps draft-07 markers", () => {
    const resolved = resolver.resolveRef(
      "#/components/messages/OrderEvent",
      components,
    );
    expect(resolved).not.toBeNull();
    expect(resolved!.name).toBe("OrderEvent");
    const schema = resolved!.schema;
    // payload flattened to the top level; headers dropped.
    expect(schema.type).toBe("object");
    expect(schema.headers).toBeUndefined();
    expect(schema.$schema).toBe("http://json-schema.org/draft-07/schema#");
    expect(schema.$id).toContain("OrderEvent");
  });

  it("rewrites $refs to #/definitions and inlines nested schemas (object, array items, allOf)", () => {
    const { schema } = resolver.resolveRef(
      "#/components/messages/OrderEvent",
      components,
    )!;
    expect(schema.properties.order.$ref).toBe("#/definitions/Order");
    expect(schema.properties.lines.items.$ref).toBe("#/definitions/Item");
    expect(schema.properties.meta.allOf[0].$ref).toBe("#/definitions/Meta");

    // Transitively referenced schemas are inlined into definitions.
    expect(schema.definitions.Order).toBeDefined();
    expect(schema.definitions.Item).toBeDefined();
    expect(schema.definitions.Meta).toBeDefined();
    // Nested ref inside Order is rewritten too.
    expect(schema.definitions.Order.properties.item.$ref).toBe(
      "#/definitions/Item",
    );
  });

  it("resolves a top-level array payload with $ref items", () => {
    const { schema } = resolver.resolveRef(
      "#/components/messages/ListEvent",
      components,
    )!;
    expect(schema.type).toBe("array");
    expect(schema.items.$ref).toBe("#/definitions/Item");
    expect(schema.definitions.Item).toBeDefined();
  });

  it("resolves a nested ref by its component section, not by definition name", () => {
    // A message and a schema share the name "Shared"; a nested ref points at the
    // message. Resolving by the original `#/components/messages/...` path must
    // pick the message, not the same-named schema.
    const collisionComponents = {
      messages: {
        Evt: {
          payload: {
            type: "object",
            properties: { shared: { $ref: "#/components/messages/Shared" } },
          },
        },
        Shared: {
          payload: {
            type: "object",
            properties: { fromMessage: { type: "string" } },
          },
        },
      },
      schemas: {
        Shared: {
          type: "object",
          properties: { fromSchema: { type: "string" } },
        },
      },
    };

    const { schema } = resolver.resolveRef(
      "#/components/messages/Evt",
      collisionComponents,
    )!;
    expect(schema.properties.shared.$ref).toBe("#/definitions/Shared");
    expect(schema.definitions.Shared.properties.fromMessage).toBeDefined();
    expect(schema.definitions.Shared.properties.fromSchema).toBeUndefined();
  });
});
