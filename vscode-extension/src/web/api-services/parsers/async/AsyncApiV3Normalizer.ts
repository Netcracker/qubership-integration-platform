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
const MESSAGES_SEGMENT = "/messages/";
const ACTION_SEND = "send";
const ACTION_RECEIVE = "receive";

type AnyRecord = Record<string, any>;

/**
 * Normalizes an AsyncAPI 3.0 document into the canonical 2.x model used by the
 * rest of the import pipeline. Faithful port of the runtime-catalog backend
 * `AsyncApiV3Normalizer`, so offline imports of 3.0 specs produce the same
 * operations as the online backend.
 */
export class AsyncApiV3Normalizer {
  static normalize(v3: AsyncApiV3Data): AsyncApiData {
    const channels: Record<string, AsyncApiChannel> = {};
    const v3Channels = v3.channels ?? {};

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

        if (v3Op.action === ACTION_SEND) {
          v2Channel.publish = v2Op;
        } else {
          v2Channel.subscribe = v2Op;
        }

        this.normalizeReplyOperation(operationKey, v3Op, v3Channels, channels);
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
      maasClassifierName: v3Op["x-maas-classifier-name"],
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
    };

    if (reply.messages && reply.messages.length > 0) {
      replyOp.message = this.convertMessageList(reply.messages, v3Channels);
    } else if (replyV3Channel?.messages) {
      replyOp.message = this.convertChannelMessages(replyV3Channel.messages);
    }

    if (v3Op.action === ACTION_SEND) {
      replyV2Channel.subscribe = replyOp;
    } else {
      replyV2Channel.publish = replyOp;
    }
  }

  private static resolveRefToKey(ref: string): string {
    return ref.startsWith(CHANNELS_REF_PREFIX)
      ? ref.substring(CHANNELS_REF_PREFIX.length)
      : ref;
  }
}
