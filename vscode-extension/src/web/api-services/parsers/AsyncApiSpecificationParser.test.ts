jest.mock("vscode", () => ({ Uri: class Uri {} }), { virtual: true });
jest.mock("../../response/file/fileApiProvider", () => ({
  fileApi: {},
  setFileApi: jest.fn(),
}));

import * as fs from "fs";
import * as path from "path";
import { AsyncApiSpecificationParser } from "./AsyncApiSpecificationParser";

const FIXTURES = path.resolve(
  __dirname,
  "../../../../tests/fixtures/asyncapi",
);

function load(rel: string): string {
  return fs.readFileSync(path.join(FIXTURES, rel), "utf8");
}

async function operationsFor(rel: string): Promise<any[]> {
  const data = await AsyncApiSpecificationParser.parseAsyncApiContent(
    load(rel),
  );
  return AsyncApiSpecificationParser.createOperationsFromAsyncApi(
    data,
    "spec-1",
  );
}

describe("AsyncApiSpecificationParser — backend parity", () => {
  describe("AsyncAPI 2.x", () => {
    it("Kafka: keeps publish method and keys multi-message oneOf as payload_n", async () => {
      const operations = await operationsFor("v2/kafka-v2-inline-oneof.yaml");
      expect(operations).toHaveLength(1);
      const op = operations[0];
      expect(op).toMatchObject({
        name: "onPresenceUpdate",
        method: "publish",
        path: "chat.presence",
      });
      expect(Object.keys(op.requestSchema)).toHaveLength(0);
      expect(Object.keys(op.responseSchemas).sort()).toEqual([
        "payload_0",
        "payload_1",
      ]);
    });

    it("AMQP: inverts publish/subscribe, emits binding specification, resolves $ref/oneOf messages", async () => {
      const operations = await operationsFor("v2/amqp-v2-with-messages.yaml");

      const notificationOps = operations.filter(
        (o) => o.path === "notifications",
      );
      expect(notificationOps).toHaveLength(2);
      // AMQP names operations after the channel.
      expect(notificationOps.every((o) => o.name === "notifications")).toBe(
        true,
      );
      // AMQP inverts: the channel's `publish` op is mapped to method "subscribe".
      const methods = notificationOps.map((o) => o.method).sort();
      expect(methods).toEqual(["publish", "subscribe"]);

      const sendOp = notificationOps.find((o) => o.method === "subscribe");
      expect(sendOp.specification).toEqual({
        username: "notification-svc",
        queue: "notifications",
        exchangeName: "notifications.direct",
      });
      // $ref message resolves under the message name.
      expect(sendOp.responseSchemas.Notification).toBeDefined();
      expect(Object.keys(sendOp.requestSchema)).toHaveLength(0);

      const alertOp = operations.find((o) => o.path === "alerts");
      expect(Object.keys(alertOp.responseSchemas).sort()).toEqual([
        "CriticalAlert",
        "WarningAlert",
      ]);
    });
  });

  describe("AsyncAPI 3.0 (normalized to 2.x)", () => {
    it("Kafka: resolves channel address and send action", async () => {
      const operations = await operationsFor("v3/kafka-v3-simple.yaml");
      expect(operations).toHaveLength(1);
      const op = operations[0];
      expect(op).toMatchObject({
        name: "publishUserSignedUp",
        method: "send",
        path: "user/signedup",
      });
      expect(op.specification).toEqual({ topic: "user/signedup" });
      expect(Object.keys(op.requestSchema)).toHaveLength(0);
      expect(op.responseSchemas.payload).toBeDefined();
    });

    it("resolves protocol from info.x-protocol when servers are absent (JSON)", async () => {
      const operations = await operationsFor("v3/kafka-v3-no-servers.json");
      expect(operations).toHaveLength(1);
      expect(operations[0]).toMatchObject({
        method: "send",
        path: "events/all",
      });
      expect(operations[0].specification.topic).toBe("events/all");
    });

    it("multi-operation channel produces both directions", async () => {
      const operations = await operationsFor(
        "v3/kafka-v3-multi-operation.yaml",
      );
      const userEvents = operations.filter((o) => o.path === "user/events");
      const methods = userEvents.map((o) => o.method).sort();
      expect(methods).toEqual(["receive", "send"]);
    });

    it("request/reply yields an inverse-direction <id>Reply operation", async () => {
      const operations = await operationsFor("v3/kafka-v3-request-reply.yaml");
      const names = operations.map((o) => o.name);
      expect(names).toContain("sendOrderRequest");
      expect(names).toContain("sendOrderRequestReply");

      const reply = operations.find(
        (o) => o.name === "sendOrderRequestReply",
      );
      expect(reply.method).toBe("receive");
      expect(reply.path).toBe("order/reply");
    });

    it("takes the MaaS classifier from the operation, then from the channel", async () => {
      const operations = await operationsFor(
        "v3/kafka-v3-maas-classifier.yaml",
      );
      const classifierOf = (address: string, method: string) =>
        operations.find((o) => o.path === address && o.method === method)
          .specification.maasClassifierName;

      expect(classifierOf("operation/topic", "send")).toBe(
        "operation.level.topic",
      );
      expect(classifierOf("channel/topic", "send")).toBe("channel.level.topic");
      expect(classifierOf("channel/topic", "receive")).toBe(
        "operation.level.topic",
      );
      expect(classifierOf("request/topic", "send")).toBeUndefined();
      expect(classifierOf("reply/topic", "receive")).toBe(
        "reply.channel.topic",
      );
    });

    it("keeps a declared operation on the reply channel, else the last reply", async () => {
      const operations = await operationsFor("v3/kafka-v3-reply-slot.yaml");
      const nameOf = (address: string) =>
        operations.find((o) => o.path === address && o.method === "receive")
          .name;

      expect(nameOf("order/reply")).toBe("receiveOrderReply");
      expect(nameOf("shared/reply")).toBe("sendRefundRequestReply");
    });

    it("AMQP v3: emits binding specification with exchangeName", async () => {
      const operations = await operationsFor(
        "v3/amqp-v3-exchange-bindings.yaml",
      );
      const email = operations.find(
        (o) => o.path === "notifications.email" && o.method === "send",
      );
      expect(email).toBeDefined();
      expect(email.specification).toEqual({
        username: "notification-svc",
        queue: "notifications.email",
        exchangeName: "notifications.topic",
      });
      expect(Object.keys(email.requestSchema)).toHaveLength(0);
    });
  });

  describe("component references", () => {
    // The backend resolves the same fixture against the same expected file.
    const expected = JSON.parse(
      load("expected/kafka-v2-inline-payload-refs.json"),
    ) as Record<string, unknown>;

    it.each(Object.keys(expected))(
      "%s matches the expected response schemas",
      async (channel) => {
        const operations = await operationsFor(
          "v2/kafka-v2-inline-payload-refs.yaml",
        );
        const op = operations.find((each) => each.path === channel);
        expect(op.responseSchemas).toEqual(expected[channel]);
      },
    );
  });

  describe("AsyncAPI 3.0 multi-format schemas", () => {
    // The backend's AsyncapiSpecificationParserSchemaTest reads the same documents.
    const JSON_SCHEMA = "application/schema+json;version=draft-07";
    const AVRO_SCHEMA = {
      schemaFormat: "application/vnd.apache.avro;version=1.9.0",
      schema: {
        type: "record",
        name: "Order",
        fields: [{ name: "id", type: "string" }],
      },
    };
    const CLOSED_OBJECT = {
      type: "object",
      additionalProperties: false,
      properties: { id: { type: "string" } },
    };
    const INTEGER_ENUM = { type: "integer", enum: [1, 2, 3] };
    const CLOSED_REF = { $ref: "#/components/schemas/Closed/schema" };
    const DEPENDENT_LITERAL = {
      type: "object",
      properties: { trigger: { type: "string" } },
      dependencies: {
        trigger: {
          properties: {
            marker: { const: { $ref: "#/components/schemas/Dependent" } },
          },
        },
      },
    };
    const CLOSED_SCHEMAS = {
      schemas: { Closed: { schemaFormat: JSON_SCHEMA, schema: CLOSED_OBJECT } },
    };

    function document(messages: unknown, components: unknown = {}): string {
      return JSON.stringify({
        asyncapi: "3.0.0",
        info: { title: "Orders", version: "1.0.0" },
        servers: { local: { host: "localhost:9092", protocol: "kafka" } },
        channels: { orders: { address: "orders", messages } },
        operations: {
          receiveOrder: {
            action: "receive",
            channel: { $ref: "#/channels/orders" },
          },
        },
        components,
      });
    }

    async function storedSchemas(
      content: string,
    ): Promise<Record<string, unknown>> {
      const data =
        await AsyncApiSpecificationParser.parseAsyncApiContent(content);
      const [operation] =
        AsyncApiSpecificationParser.createOperationsFromAsyncApi(
          data,
          "spec-1",
        );
      return Object.fromEntries(
        Object.entries(operation.responseSchemas).map(
          ([key, schema]: [string, any]) => {
            const { $id, $schema, ...rest } = schema;
            return [key, rest];
          },
        ),
      );
    }

    it.each([
      [
        "an AsyncAPI schema",
        document({
          order: {
            payload: {
              schemaFormat: "application/vnd.aai.asyncapi+json;version=3.0.0",
              schema: CLOSED_OBJECT,
            },
          },
        }),
        { payload: { ...CLOSED_OBJECT, definitions: {} } },
      ],
      [
        "a JSON Schema in a component message",
        document(
          { order: { $ref: "#/components/messages/Multi" } },
          {
            messages: {
              Multi: {
                payload: {
                  schemaFormat: "application/schema+yaml;version=draft-07",
                  schema: INTEGER_ENUM,
                },
              },
            },
          },
        ),
        { Multi: { ...INTEGER_ENUM, definitions: {} } },
      ],
      [
        "a JSON Schema in a channel with two messages",
        document({
          order: {
            payload: { schemaFormat: JSON_SCHEMA, schema: CLOSED_OBJECT },
          },
          other: { payload: INTEGER_ENUM },
        }),
        {
          payload_0: { ...CLOSED_OBJECT, definitions: {} },
          payload_1: { ...INTEGER_ENUM, definitions: {} },
        },
      ],
      [
        "a boolean schema",
        document({
          order: { payload: { schemaFormat: JSON_SCHEMA, schema: true } },
        }),
        { payload: { definitions: {} } },
      ],
      [
        "an Avro payload",
        document({ order: { payload: AVRO_SCHEMA } }),
        { payload: { definitions: {} } },
      ],
      [
        "a JSON Schema component",
        document(
          { order: { payload: { $ref: "#/components/schemas/Closed" } } },
          CLOSED_SCHEMAS,
        ),
        { payload: { ...CLOSED_OBJECT, definitions: {} } },
      ],
      [
        "a schema with a schemaFormat keyword and no schema",
        document(
          { order: { payload: { $ref: "#/components/schemas/Noted" } } },
          { schemas: { Noted: { type: "object", schemaFormat: "note" } } },
        ),
        { payload: { type: "object", schemaFormat: "note", definitions: {} } },
      ],
      [
        "a reference into a multi-format component",
        document(
          {
            order: { payload: { $ref: "#/components/schemas/Closed/schema" } },
          },
          CLOSED_SCHEMAS,
        ),
        { payload: { ...CLOSED_OBJECT, definitions: {} } },
      ],
      [
        "a component message with a reference into a multi-format component",
        document(
          { order: { $ref: "#/components/messages/Order" } },
          {
            messages: {
              Order: {
                payload: { $ref: "#/components/schemas/Closed/schema" },
              },
            },
            ...CLOSED_SCHEMAS,
          },
        ),
        { Order: { ...CLOSED_OBJECT, definitions: {} } },
      ],
      [
        "a component schema with a reference into a multi-format component",
        document(
          { order: { payload: { $ref: "#/components/schemas/Wrapper" } } },
          {
            schemas: {
              Wrapper: { type: "object", properties: { closed: CLOSED_REF } },
              ...CLOSED_SCHEMAS.schemas,
            },
          },
        ),
        {
          payload: {
            type: "object",
            properties: { closed: { $ref: "#/definitions/Closed" } },
            definitions: { Closed: CLOSED_OBJECT },
          },
        },
      ],
      [
        "a nested reference into a multi-format component",
        document(
          {
            order: {
              payload: {
                type: "object",
                properties: {
                  closed: { $ref: "#/components/schemas/Closed/schema" },
                },
              },
            },
          },
          CLOSED_SCHEMAS,
        ),
        {
          payload: {
            type: "object",
            properties: { closed: { $ref: "#/definitions/Closed" } },
            definitions: { Closed: CLOSED_OBJECT },
          },
        },
      ],
      [
        "a reference below the schema of a multi-format component",
        document(
          {
            order: {
              payload: {
                $ref: "#/components/schemas/Closed/schema/properties/id",
              },
            },
          },
          CLOSED_SCHEMAS,
        ),
        { payload: { type: "string", definitions: {} } },
      ],
      [
        "a reference into a multi-format message payload",
        document(
          {
            order: {
              payload: { $ref: "#/components/messages/Multi/payload/schema" },
            },
          },
          {
            messages: {
              Multi: {
                payload: { schemaFormat: JSON_SCHEMA, schema: INTEGER_ENUM },
              },
            },
          },
        ),
        { payload: { ...INTEGER_ENUM, definitions: {} } },
      ],
      [
        "a reference from a property named like a keyword",
        document(
          {
            order: {
              payload: { type: "object", properties: { default: CLOSED_REF } },
            },
          },
          CLOSED_SCHEMAS,
        ),
        {
          payload: {
            type: "object",
            properties: { default: { $ref: "#/definitions/Closed" } },
            definitions: { Closed: CLOSED_OBJECT },
          },
        },
      ],
      [
        "a reference under dependencies",
        document(
          {
            order: {
              payload: {
                type: "object",
                properties: { a: { type: "string" } },
                dependencies: {
                  a: { $ref: "#/components/schemas/Dependent" },
                  c: {
                    properties: {
                      d: { $ref: "#/components/schemas/Dependent" },
                    },
                  },
                },
              },
            },
          },
          { schemas: { Dependent: { type: "object", required: ["b"] } } },
        ),
        {
          payload: {
            type: "object",
            properties: { a: { type: "string" } },
            dependencies: {
              a: { $ref: "#/definitions/Dependent" },
              c: { properties: { d: { $ref: "#/definitions/Dependent" } } },
            },
            definitions: { Dependent: { type: "object", required: ["b"] } },
          },
        },
      ],
      [
        "an array with dependencies",
        document(
          {
            order: {
              payload: {
                type: "array",
                items: { $ref: "#/components/schemas/Dependent" },
                dependencies: { a: ["b"] },
              },
            },
          },
          { schemas: { Dependent: { type: "object", required: ["b"] } } },
        ),
        {
          payload: {
            type: "array",
            items: { $ref: "#/definitions/Dependent" },
            dependencies: { a: ["b"] },
            definitions: { Dependent: { type: "object", required: ["b"] } },
          },
        },
      ],
      [
        "a reference-shaped literal under dependencies",
        document(
          { order: { payload: DEPENDENT_LITERAL } },
          { schemas: { Dependent: { type: "object", required: ["b"] } } },
        ),
        { payload: { ...DEPENDENT_LITERAL, definitions: {} } },
      ],
      [
        "an Avro component",
        document(
          { order: { payload: { $ref: "#/components/schemas/Order" } } },
          { schemas: { Order: AVRO_SCHEMA } },
        ),
        { payload: { definitions: {} } },
      ],
    ])("stores %s as the backend does", async (_name, content, expected) => {
      expect(await storedSchemas(content)).toEqual(expected);
    });
  });

  // These fixtures intentionally omit a protocol marker (servers/x-protocol);
  // they exercise normalizer-only behavior (message refs, missing channels).
  const PROTOCOL_LESS = new Set([
    "v3/kafka-v3-channel-ref-messages.yaml",
    "v3/kafka-v3-edge-cases.yaml",
  ]);

  describe("every backend fixture imports cleanly", () => {
    const files = [
      ...fs.readdirSync(path.join(FIXTURES, "v2")).map((f) => `v2/${f}`),
      ...fs.readdirSync(path.join(FIXTURES, "v3")).map((f) => `v3/${f}`),
    ].filter((f) => !PROTOCOL_LESS.has(f));

    it.each(files)("%s yields backend-shaped operations", async (rel) => {
      const operations = await operationsFor(rel);
      expect(operations.length).toBeGreaterThan(0);
      for (const op of operations) {
        expect(Object.keys(op.requestSchema)).toHaveLength(0);
        expect(typeof op.responseSchemas).toBe("object");
        expect(["send", "receive", "publish", "subscribe"]).toContain(
          op.method,
        );
        expect(typeof op.path).toBe("string");
      }
      // Operation ids must be unique, even when an AMQP channel exposes both
      // send and receive (both named after the channel).
      const ids = operations.map((op) => op.id);
      expect(new Set(ids).size).toBe(ids.length);
    });
  });

  describe("normalization of protocol-less v3 fixtures", () => {
    it("resolves channel-ref messages onto channel addresses", async () => {
      const data = await AsyncApiSpecificationParser.parseAsyncApiContent(
        load("v3/kafka-v3-channel-ref-messages.yaml"),
      );
      expect(Object.keys(data.channels).length).toBeGreaterThan(0);
      expect(data.channels["events/inline"]).toBeDefined();
    });

    it("skips operations that reference a non-existent channel", async () => {
      const data = await AsyncApiSpecificationParser.parseAsyncApiContent(
        load("v3/kafka-v3-edge-cases.yaml"),
      );
      expect(data.channels["existing/topic"]).toBeDefined();
      // The operation pointing at a missing channel must not create a channel.
      expect(data.channels["nonExistentChannel"]).toBeUndefined();
    });
  });

  describe("extractAddressFromAsyncApiData", () => {
    it("maps x-protocol to a default url", () => {
      expect(
        AsyncApiSpecificationParser.extractAddressFromAsyncApiData({
          asyncapi: "2.6.0",
          info: { title: "t", version: "1.0.0", "x-protocol": "kafka" },
          channels: {},
        }),
      ).toBe("kafka://localhost:9092");
    });

    it("falls back to the first server url", () => {
      expect(
        AsyncApiSpecificationParser.extractAddressFromAsyncApiData({
          asyncapi: "2.6.0",
          info: { title: "t", version: "1.0.0" },
          channels: {},
          servers: { prod: { url: "broker:9092", protocol: "kafka" } },
        }),
      ).toBe("broker:9092");
    });

    it("returns null when no protocol or server is present", () => {
      expect(
        AsyncApiSpecificationParser.extractAddressFromAsyncApiData({
          asyncapi: "2.6.0",
          info: { title: "t", version: "1.0.0" },
          channels: {},
        }),
      ).toBeNull();
    });
  });

  it("rejects content without an asyncapi field", async () => {
    await expect(
      AsyncApiSpecificationParser.parseAsyncApiContent(
        JSON.stringify({ info: { title: "x" } }),
      ),
    ).rejects.toThrow("Not a valid AsyncAPI specification");
  });

  it("rejects an unsupported async protocol", async () => {
    const data = await AsyncApiSpecificationParser.parseAsyncApiContent(
      JSON.stringify({
        asyncapi: "2.6.0",
        info: { title: "t", version: "1.0.0", "x-protocol": "mqtt" },
        channels: { "some/topic": { publish: { operationId: "op" } } },
      }),
    );
    expect(() =>
      AsyncApiSpecificationParser.createOperationsFromAsyncApi(data, "spec-1"),
    ).toThrow(/Unsupported AsyncAPI protocol/);
  });
});
