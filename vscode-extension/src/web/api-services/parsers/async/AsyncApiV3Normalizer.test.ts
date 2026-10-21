import { detectAsyncApiVersion } from "./asyncApiVersion";
import { AsyncApiV3Normalizer } from "./AsyncApiV3Normalizer";
import { AsyncApiV3Data, AsyncApiV3Operation } from "../parserTypes";

function v3(operation: Record<string, any>): AsyncApiV3Data {
  return {
    asyncapi: "3.0.0",
    info: { title: "t", version: "1.0.0" },
    servers: { prod: { host: "h:9092", protocol: "kafka" } },
    channels: {
      ch: {
        address: "the/topic",
        messages: { M: { payload: { type: "object" } } },
      },
    },
    operations: { op: operation },
  };
}

describe("detectAsyncApiVersion", () => {
  it.each([
    ["3.0.0", "V3"],
    ["3.1.0", "V3"],
    ["2.6.0", "V2"],
    ["2.0.0", "V2"],
    ["", "V2"],
  ])("detects %s as %s", (version, expected) => {
    expect(detectAsyncApiVersion(version)).toBe(expected);
  });

  it("treats missing version as V2", () => {
    expect(detectAsyncApiVersion(undefined)).toBe("V2");
  });
});

describe("AsyncApiV3Normalizer", () => {
  it("maps send -> publish and receive -> subscribe on the channel address", () => {
    const sent = AsyncApiV3Normalizer.normalize(
      v3({ action: "send", channel: { $ref: "#/channels/ch" } }),
    );
    expect(sent.channels["the/topic"].publish).toBeDefined();
    expect(sent.channels["the/topic"].subscribe).toBeUndefined();

    const received = AsyncApiV3Normalizer.normalize(
      v3({ action: "receive", channel: { $ref: "#/channels/ch" } }),
    );
    expect(received.channels["the/topic"].subscribe).toBeDefined();
  });

  it("carries the channel address and binds the resolved channel message", () => {
    const result = AsyncApiV3Normalizer.normalize(
      v3({
        action: "send",
        operationId: "publishM",
        channel: { $ref: "#/channels/ch" },
      }),
    );
    const op = result.channels["the/topic"].publish!;
    expect(op.operationId).toBe("publishM");
    expect(op.action).toBe("send");
    expect(op.message?.payload).toEqual({ type: "object" });
  });

  it.each([
    ["receiver", "sender"],
    ["sender", "receiver"],
  ])(
    "keeps the operation declared on the reply channel (%s first)",
    (first, second) => {
      const declared: Record<string, AsyncApiV3Operation> = {
        receiver: {
          action: "receive",
          channel: { $ref: "#/channels/replies" },
        },
        sender: {
          action: "send",
          channel: { $ref: "#/channels/ch" },
          reply: { channel: { $ref: "#/channels/replies" } },
        },
      };
      const spec = v3({});
      spec.channels!.replies = { address: "reply/topic" };
      spec.operations = {
        [first]: declared[first],
        [second]: declared[second],
      };

      const result = AsyncApiV3Normalizer.normalize(spec);
      expect(result.channels["reply/topic"].subscribe!.operationId).toBe(
        "receiver",
      );
    },
  );

  it("rejects the v2 keyword 'publish' used as a v3 action", () => {
    expect(() =>
      AsyncApiV3Normalizer.normalize(
        v3({ action: "publish", channel: { $ref: "#/channels/ch" } }),
      ),
    ).toThrow(/'publish'\/'subscribe' are not valid in v3/);
  });

  it("rejects a missing action", () => {
    expect(() =>
      AsyncApiV3Normalizer.normalize(
        v3({ channel: { $ref: "#/channels/ch" } }),
      ),
    ).toThrow(/missing required 'action'/);
  });

  it("normalizes 3.0 servers (host + pathname) into a 2.x url", () => {
    const result = AsyncApiV3Normalizer.normalize({
      asyncapi: "3.0.0",
      servers: {
        prod: { host: "rabbit:5672", pathname: "/vhost", protocol: "amqp" },
      },
      channels: {},
      operations: {},
    });
    expect(result.servers!.prod).toEqual({
      url: "rabbit:5672/vhost",
      protocol: "amqp",
    });
  });

  it("rewrites references into an unwrapped schema and leaves reference-shaped instance data alone", () => {
    // A fresh object each time, because the normalizer rewrites in place.
    const ref = () => ({ $ref: "#/components/schemas/Closed/schema" });
    const literals = () => ({
      const: ref(),
      enum: [ref()],
      default: ref(),
      example: ref(),
      examples: [ref()],
    });
    const closed = { type: "object", properties: { id: { type: "string" } } };
    const result = AsyncApiV3Normalizer.normalize({
      asyncapi: "3.0.0",
      info: { title: "t", version: "1.0.0" },
      channels: {
        ch: {
          address: "the/topic",
          messages: {
            M: {
              payload: {
                type: "object",
                properties: { marker: literals(), default: ref() },
              },
            },
          },
        },
      },
      operations: {
        op: { action: "receive", channel: { $ref: "#/channels/ch" } },
      },
      components: {
        schemas: {
          Closed: {
            schemaFormat: "application/schema+json;version=draft-07",
            schema: closed,
          },
        },
      },
    });

    const payload = result.channels["the/topic"].subscribe!.message!.payload;
    expect(payload.properties.marker).toEqual(literals());
    expect(payload.properties.default).toEqual({
      $ref: "#/components/schemas/Closed",
    });
    expect(result.components!.schemas.Closed).toEqual(closed);
  });
});
