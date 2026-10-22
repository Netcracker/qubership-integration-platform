import {
  AsyncApiChannel,
  AsyncApiData,
  AsyncApiMessage,
  AsyncApiOperationObject,
  AsyncApiV3Channel,
  AsyncApiV3Data,
  AsyncApiV3Operation,
  AsyncApiV3Server,
} from "../parserTypes";
import { isObject } from "./asyncJsonUtils";

const CHANNELS_REF_PREFIX = "#/channels/";
const COMPONENTS_MESSAGES_PREFIX = "#/components/messages/";
const COMPONENTS_SCHEMAS_PREFIX = "#/components/schemas/";
const MESSAGES_SEGMENT = "/messages/";
const ACTION_SEND = "send";
const ACTION_RECEIVE = "receive";
const SCHEMA_FORMAT_PREFIXES = [
  "application/vnd.aai.asyncapi",
  "application/schema+",
];
// Draft-07 keywords whose value is a map of names to schemas, a schema or a
// list of them, and a list of schemas.
const SCHEMA_MAPS = new Set([
  "properties",
  "patternProperties",
  "definitions",
  "dependencies",
]);
const SCHEMA_VALUES = new Set([
  "additionalProperties",
  "items",
  "additionalItems",
  "contains",
  "propertyNames",
  "not",
  "if",
  "then",
  "else",
]);
const SCHEMA_LISTS = new Set(["allOf", "anyOf", "oneOf"]);

type AnyRecord = Record<string, any>;

/**
 * Normalizes an AsyncAPI 3.0 document into the canonical 2.x model used by the
 * rest of the import pipeline. Faithful port of the runtime-catalog backend
 * `AsyncApiV3Normalizer`, so offline imports of 3.0 specs produce the same
 * operations as the online backend.
 */
export class AsyncApiV3Normalizer {
  static normalize(v3: AsyncApiV3Data): AsyncApiData {
    this.unwrapMultiFormatSchemas(v3);

    const channels: Record<string, AsyncApiChannel> = {};
    const v3Channels = v3.channels ?? {};
    const declaredOperations = new Set<AsyncApiOperationObject>();

    if (v3.operations) {
      for (const [operationKey, v3Op] of Object.entries(v3.operations)) {
        const channelKey = this.resolveChannelKey(v3Op);
        if (channelKey === null) {
          continue;
        }
        const v3Channel = v3Channels[channelKey];
        if (!v3Channel) {
          continue;
        }

        this.validateAction(operationKey, v3Op.action);

        const channelPath = v3Channel.address ?? channelKey;
        const v2Channel = this.computeChannel(channels, channelPath, v3Channel);

        const v2Op = this.convertOperation(
          operationKey,
          v3Op,
          v3Channel,
          v3Channels,
        );
        v2Op.action = v3Op.action;
        declaredOperations.add(v2Op);

        if (v3Op.action === ACTION_SEND) {
          v2Channel.publish = v2Op;
        } else {
          v2Channel.subscribe = v2Op;
        }

        this.normalizeReplyOperation(
          operationKey,
          v3Op,
          v3Channels,
          channels,
          declaredOperations,
        );
      }
    }

    return {
      asyncapi: v3.asyncapi,
      info: {
        title: v3.info?.title ?? "",
        version: v3.info?.version ?? "",
        description: v3.info?.description,
        "x-protocol": v3.info?.["x-protocol"],
      },
      components: v3.components ?? {},
      servers: this.normalizeServers(v3.servers),
      channels,
    };
  }

  // A reference into an unwrapped schema, such as
  // #/components/schemas/Order/schema, is rewritten to the path the schema has
  // after unwrapping, #/components/schemas/Order.
  private static unwrapMultiFormatSchemas(v3: AsyncApiV3Data): void {
    const movedRefs = new Map<string, string>();
    for (const [key, v3Channel] of Object.entries(v3.channels ?? {})) {
      this.unwrapMultiFormatPayloads(
        v3Channel?.messages,
        CHANNELS_REF_PREFIX + key + MESSAGES_SEGMENT,
        movedRefs,
      );
    }
    this.unwrapMultiFormatPayloads(
      v3.components?.messages,
      COMPONENTS_MESSAGES_PREFIX,
      movedRefs,
    );
    const schemas = v3.components?.schemas;
    if (isObject(schemas)) {
      for (const name of Object.keys(schemas)) {
        if (this.unwrap(schemas, name)) {
          movedRefs.set(
            COMPONENTS_SCHEMAS_PREFIX + name + "/schema",
            COMPONENTS_SCHEMAS_PREFIX + name,
          );
        }
      }
    }
    for (const v3Channel of Object.values(v3.channels ?? {})) {
      this.rewriteMessageRefs(v3Channel?.messages, movedRefs);
    }
    this.rewriteMessageRefs(v3.components?.messages, movedRefs);
    if (isObject(schemas)) {
      for (const schema of Object.values(schemas)) {
        this.rewriteSchemaRefs(schema, movedRefs);
      }
    }
  }

  private static unwrapMultiFormatPayloads(
    messages: unknown,
    refPrefix: string,
    movedRefs: Map<string, string>,
  ): void {
    if (!isObject(messages)) {
      return;
    }
    for (const [name, message] of Object.entries(messages)) {
      if (isObject(message) && this.unwrap(message, "payload")) {
        movedRefs.set(
          refPrefix + name + "/payload/schema",
          refPrefix + name + "/payload",
        );
      }
    }
  }

  private static unwrap(owner: AnyRecord, key: string): boolean {
    const value = owner[key];
    const schema = this.fromMultiFormat(value);
    if (schema === value) {
      return false;
    }
    owner[key] = schema;
    return true;
  }

  private static rewriteMessageRefs(
    messages: unknown,
    movedRefs: Map<string, string>,
  ): void {
    if (!isObject(messages)) {
      return;
    }
    for (const message of Object.values(messages)) {
      if (isObject(message)) {
        this.rewriteSchemaRefs(message.payload, movedRefs);
      }
    }
  }

  // Only positions that hold a schema are followed, so data such as const
  // values, examples, and x- extensions stays as written.
  private static rewriteSchemaRefs(
    schema: unknown,
    movedRefs: Map<string, string>,
  ): void {
    if (!isObject(schema)) {
      return;
    }
    const ref = schema.$ref;
    if (typeof ref === "string") {
      for (const [from, to] of movedRefs) {
        if (ref === from || ref.startsWith(from + "/")) {
          schema.$ref = to + ref.substring(from.length);
        }
      }
    }
    for (const [keyword, value] of Object.entries(schema)) {
      if (SCHEMA_MAPS.has(keyword) && isObject(value)) {
        Object.values(value).forEach((each) =>
          this.rewriteSchemaRefs(each, movedRefs),
        );
      } else if (
        (SCHEMA_LISTS.has(keyword) || SCHEMA_VALUES.has(keyword)) &&
        Array.isArray(value)
      ) {
        value.forEach((each) => this.rewriteSchemaRefs(each, movedRefs));
      } else if (SCHEMA_VALUES.has(keyword)) {
        this.rewriteSchemaRefs(value, movedRefs);
      }
    }
  }

  // A multi-format schema in an AsyncAPI or JSON Schema format becomes the
  // schema object it holds, and one in any other format, such as Avro, becomes
  // an empty schema, because the catalog reads no other format.
  private static fromMultiFormat(schema: unknown): unknown {
    if (
      !isObject(schema) ||
      typeof schema.schemaFormat !== "string" ||
      !("schema" in schema)
    ) {
      return schema;
    }
    const known = SCHEMA_FORMAT_PREFIXES.some((prefix) =>
      schema.schemaFormat.startsWith(prefix),
    );
    return known && isObject(schema.schema) ? schema.schema : {};
  }

  private static computeChannel(
    channels: Record<string, AsyncApiChannel>,
    path: string,
    v3Channel: AsyncApiV3Channel,
  ): AsyncApiChannel {
    if (!(path in channels)) {
      channels[path] = {
        description: v3Channel.description,
        parameters: v3Channel.parameters,
        bindings: v3Channel.bindings,
      };
    }
    return channels[path];
  }

  private static normalizeServers(
    v3Servers?: Record<string, AsyncApiV3Server>,
  ): AsyncApiData["servers"] {
    if (!v3Servers) {
      return {};
    }
    const servers: AsyncApiData["servers"] = {};
    for (const [key, v3Server] of Object.entries(v3Servers)) {
      servers[key] = {
        url: `${v3Server.host ?? ""}${v3Server.pathname ?? ""}`,
        protocol: v3Server.protocol ?? "",
        ...(v3Server["x-maas-instance"]
          ? { "x-maas-instance": v3Server["x-maas-instance"] }
          : {}),
      };
    }
    return servers;
  }

  private static validateAction(operationKey: string, action?: string): void {
    if (action === ACTION_SEND || action === ACTION_RECEIVE) {
      return;
    }
    if (action == null) {
      throw new Error(
        `AsyncAPI 3.0 operation '${operationKey}' is missing required 'action' field. Allowed values: 'send', 'receive'.`,
      );
    }
    throw new Error(
      `AsyncAPI 3.0 operation '${operationKey}' has invalid action '${action}'. Allowed values: 'send', 'receive'.` +
        ` The v2.x keywords 'publish'/'subscribe' are not valid in v3.0.`,
    );
  }

  private static resolveChannelKey(v3Op: AsyncApiV3Operation): string | null {
    const ref = v3Op.channel?.$ref;
    if (!ref) {
      return null;
    }
    return ref.startsWith(CHANNELS_REF_PREFIX)
      ? ref.substring(CHANNELS_REF_PREFIX.length)
      : ref;
  }

  private static convertOperation(
    operationKey: string,
    v3Op: AsyncApiV3Operation,
    v3Channel: AsyncApiV3Channel,
    v3Channels: Record<string, AsyncApiV3Channel>,
  ): AsyncApiOperationObject {
    return {
      summary: v3Op.summary,
      operationId: v3Op.operationId ?? operationKey,
      maasClassifierName:
        v3Op["x-maas-classifier-name"] ?? v3Channel["x-maas-classifier-name"],
      message: this.convertMessages(v3Op, v3Channel, v3Channels),
    };
  }

  private static convertMessages(
    v3Op: AsyncApiV3Operation,
    v3Channel: AsyncApiV3Channel,
    v3Channels: Record<string, AsyncApiV3Channel>,
  ): AsyncApiMessage | undefined {
    const opMessages = v3Op.messages;
    if (opMessages && opMessages.length > 0) {
      return this.convertMessageList(opMessages, v3Channels);
    }
    if (v3Channel.messages && Object.keys(v3Channel.messages).length > 0) {
      return this.convertChannelMessages(v3Channel.messages);
    }
    return undefined;
  }

  private static convertMessageList(
    messageList: AnyRecord[],
    v3Channels: Record<string, AsyncApiV3Channel>,
  ): AsyncApiMessage {
    if (messageList.length === 1) {
      return this.convertSingleMessageEntry(messageList[0], v3Channels);
    }
    return {
      oneOf: messageList.map((msg) => this.resolveMessageRef(msg, v3Channels)),
    };
  }

  private static convertChannelMessages(
    channelMessages: AnyRecord,
  ): AsyncApiMessage {
    const entries = Object.entries(channelMessages);
    if (entries.length === 1) {
      const [key, value] = entries[0];
      return this.convertChannelMessageEntry(key, value);
    }
    return {
      oneOf: entries.map(([key, value]) =>
        this.channelMessageToMap(key, value),
      ),
    };
  }

  private static convertSingleMessageEntry(
    msgMap: AnyRecord,
    v3Channels: Record<string, AsyncApiV3Channel>,
  ): AsyncApiMessage {
    if (msgMap.$ref) {
      const ref = msgMap.$ref as string;
      const resolved = this.tryResolveChannelMessageRef(ref, v3Channels);
      if (resolved) {
        return resolved;
      }
      return { $ref: this.convertComponentRef(ref) };
    }
    return this.buildMessageFromMap(msgMap, msgMap.name);
  }

  private static convertChannelMessageEntry(
    key: string,
    value: unknown,
  ): AsyncApiMessage {
    if (isObject(value)) {
      if (value.$ref) {
        return { $ref: this.convertComponentRef(value.$ref as string) };
      }
      return this.buildMessageFromMap(value, key);
    }
    return { name: key };
  }

  private static buildMessageFromMap(
    msgMap: AnyRecord,
    name?: string,
  ): AsyncApiMessage {
    const message: AsyncApiMessage = {};
    if (msgMap.payload !== undefined) {
      message.payload = msgMap.payload;
    }
    if (msgMap.headers !== undefined) {
      message.headers = msgMap.headers;
    }
    if (name != null) {
      message.name = name;
    }
    return message;
  }

  private static tryResolveChannelMessageRef(
    ref: string,
    v3Channels: Record<string, AsyncApiV3Channel>,
  ): AsyncApiMessage | null {
    if (
      !ref ||
      !ref.startsWith(CHANNELS_REF_PREFIX) ||
      !ref.includes(MESSAGES_SEGMENT)
    ) {
      return null;
    }
    const withoutPrefix = ref.substring(CHANNELS_REF_PREFIX.length);
    const msgIdx = withoutPrefix.indexOf(MESSAGES_SEGMENT);
    if (msgIdx < 0) {
      return null;
    }
    const channelKey = withoutPrefix.substring(0, msgIdx);
    const messageName = withoutPrefix.substring(
      msgIdx + MESSAGES_SEGMENT.length,
    );

    const channel = v3Channels[channelKey];
    if (!channel?.messages) {
      return null;
    }
    const msgValue = channel.messages[messageName];
    if (!msgValue) {
      return null;
    }
    if (isObject(msgValue)) {
      if (msgValue.$ref) {
        return null;
      }
      return this.buildMessageFromMap(msgValue, messageName);
    }
    return null;
  }

  private static resolveMessageRef(
    msgMap: AnyRecord,
    v3Channels: Record<string, AsyncApiV3Channel>,
  ): AnyRecord {
    if (!msgMap.$ref) {
      return { ...msgMap };
    }
    const ref = msgMap.$ref as string;
    const resolved = this.tryResolveChannelMessageRef(ref, v3Channels);
    if (resolved) {
      const result: AnyRecord = {};
      if (resolved.payload != null) {
        result.payload = resolved.payload;
      }
      if (resolved.headers != null) {
        result.headers = resolved.headers;
      }
      if (resolved.name != null) {
        result.name = resolved.name;
      }
      return result;
    }
    return { ...msgMap, $ref: this.convertComponentRef(ref) };
  }

  private static channelMessageToMap(key: string, value: unknown): AnyRecord {
    if (isObject(value)) {
      const result: AnyRecord = { ...value };
      if (result.$ref) {
        result.$ref = this.convertComponentRef(result.$ref as string);
      }
      return result;
    }
    return { name: key };
  }

  private static convertComponentRef(ref: string): string {
    if (!ref || ref.startsWith(COMPONENTS_MESSAGES_PREFIX)) {
      return ref;
    }
    if (ref.startsWith(CHANNELS_REF_PREFIX) && ref.includes(MESSAGES_SEGMENT)) {
      const messageName = ref.substring(
        ref.lastIndexOf(MESSAGES_SEGMENT) + MESSAGES_SEGMENT.length,
      );
      return COMPONENTS_MESSAGES_PREFIX + messageName;
    }
    return ref;
  }

  private static normalizeReplyOperation(
    operationKey: string,
    v3Op: AsyncApiV3Operation,
    v3Channels: Record<string, AsyncApiV3Channel>,
    channels: Record<string, AsyncApiChannel>,
    declaredOperations: ReadonlySet<AsyncApiOperationObject | undefined>,
  ): void {
    const reply = v3Op.reply;
    const replyRef = reply?.channel?.$ref;
    if (!reply || !replyRef) {
      return;
    }

    const replyChannelKey = this.resolveRefToKey(replyRef);
    const replyV3Channel = v3Channels[replyChannelKey];
    const replyAddress = replyV3Channel?.address ?? replyChannelKey;

    const replyV2Channel = this.computeChannel(
      channels,
      replyAddress,
      replyV3Channel ?? {},
    );

    const baseId = v3Op.operationId ?? operationKey;
    const replyOp: AsyncApiOperationObject = {
      summary: v3Op.summary ? `${v3Op.summary} (reply)` : "reply",
      operationId: `${baseId}Reply`,
      action: v3Op.action === ACTION_SEND ? ACTION_RECEIVE : ACTION_SEND,
      maasClassifierName: replyV3Channel?.["x-maas-classifier-name"],
    };

    if (reply.messages && reply.messages.length > 0) {
      replyOp.message = this.convertMessageList(reply.messages, v3Channels);
    } else if (replyV3Channel?.messages) {
      replyOp.message = this.convertChannelMessages(replyV3Channel.messages);
    }

    // The generated reply never replaces an operation declared on the reply channel.
    if (v3Op.action === ACTION_SEND) {
      if (!declaredOperations.has(replyV2Channel.subscribe)) {
        replyV2Channel.subscribe = replyOp;
      }
    } else if (!declaredOperations.has(replyV2Channel.publish)) {
      replyV2Channel.publish = replyOp;
    }
  }

  private static resolveRefToKey(ref: string): string {
    return ref.startsWith(CHANNELS_REF_PREFIX)
      ? ref.substring(CHANNELS_REF_PREFIX.length)
      : ref;
  }
}
