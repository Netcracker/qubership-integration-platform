import {ContentParser} from "./ContentParser";
import {AsyncApiData, AsyncApiV3Data} from "./parserTypes";
import {detectAsyncApiVersion} from "./async/asyncApiVersion";
import {AsyncApiV3Normalizer} from "./async/AsyncApiV3Normalizer";
import {AsyncApiSpecificationResolver, selectAsyncResolver,} from "./async/AsyncApiSpecificationResolvers";
import {SpecificationTypeDetector} from "../../services/SpecificationTypeDetector";

export class AsyncApiSpecificationParser {
  /**
   * Parse AsyncAPI content. AsyncAPI 3.0 documents are normalized into the
   * canonical 2.x model (channels with publish/subscribe), as the
   * runtime-catalog backend does. Methods differ by source: 3.0 keeps the
   * `send`/`receive` action, 2.x uses `publish`/`subscribe`.
   */
  static async parseAsyncApiContent(content: string): Promise<AsyncApiData> {
    const specData = ContentParser.parseContentWithErrorHandling(
      content,
      "AsyncApiSpecificationParser",
    );

    if (!specData.asyncapi) {
      throw new Error("Not a valid AsyncAPI specification");
    }

    if (detectAsyncApiVersion(specData.asyncapi) === "V3") {
      return AsyncApiV3Normalizer.normalize(specData as AsyncApiV3Data);
    }

    return specData as AsyncApiData;
  }

  /**
   * Create operations from (already-normalized) AsyncAPI data. Selects the
   * protocol resolver (kafka/amqp), then for each channel operation produces an
   * operation with an empty request schema and the message payload(s) under
   * `responseSchemas`. The output matches the backend.
   */
  static createOperationsFromAsyncApi(
    asyncApiData: AsyncApiData,
    specificationId: string,
  ): any[] {
    const operations: any[] = [];

    const channels = asyncApiData.channels;
    if (!channels) {
      return operations;
    }

    const protocol = this.resolveProtocol(asyncApiData);
    const resolver = selectAsyncResolver(protocol);
    if (!resolver) {
      throw new Error(
        `Unsupported AsyncAPI protocol: ${protocol || "unknown"}. Supported protocols: kafka, amqp.`,
      );
    }

    const isAmqp = this.isAmqpProtocol(protocol);
    const components = asyncApiData.components;

    Object.entries(channels).forEach(([channelName, channel]) => {
      const operationObjects = resolver.getOperationObjects(channel);
      for (const operationObject of operationObjects) {
        // AMQP renames the operation after its channel (backend parity), which
        // can repeat across a channel's send+receive. Keep the original
        // operationId for the unique id so the two directions don't collide.
        const operationKey = operationObject.operationId;
        if (isAmqp) {
          operationObject.operationId = channelName;
        }
        operations.push(
          this.buildOperation(
            resolver,
            specificationId,
            channelName,
            channel,
            operationObject,
            components,
            operationKey,
          ),
        );
      }
    });

    return operations;
  }

  private static buildOperation(
    resolver: AsyncApiSpecificationResolver,
    specificationId: string,
    channelName: string,
    channel: AsyncApiData["channels"][string],
    operationObject: NonNullable<
      AsyncApiData["channels"][string]["publish"]
    >,
    components: AsyncApiData["components"],
    operationKey?: string,
  ): any {
    const specification = resolver.getSpecificationJsonNode(
      channelName,
      channel,
      operationObject,
    );
    const method = resolver.getMethod(channel, operationObject);
    const { requestSchema, responseSchemas } = resolver.setUpOperationMessages(
      operationObject,
      components,
    );
    const name = operationObject.operationId ?? channelName;
    // Use the original operationId for the id so the display name (which AMQP
    // sets to the channel) can repeat without producing duplicate ids; fall
    // back to method+channel when no operationId was declared.
    const idKey = operationKey ?? `${method}-${channelName}`;

    return {
      id: `${specificationId}-${idKey}`,
      name,
      method,
      path: channelName,
      specification,
      requestSchema,
      responseSchemas,
    };
  }

  /**
   * Extract address from AsyncAPI data
   */
  static extractAddressFromAsyncApiData(
    asyncApiData: AsyncApiData,
  ): string | null {
    // Check x-protocol first (priority over servers)
    const protocol = asyncApiData.info?.["x-protocol"];

    if (protocol) {
      // Convert protocol to URL format
      const protocolUrls: { [key: string]: string } = {
        amqp: "amqp://localhost:5672",
        mqtt: "mqtt://localhost:1883",
        kafka: "kafka://localhost:9092",
        redis: "redis://localhost:6379",
        nats: "nats://localhost:4222",
        "custom-protocol": "custom-protocol://localhost",
      };

      return protocolUrls[protocol.toLowerCase()] || `${protocol}://localhost`;
    }

    // Check servers if no x-protocol
    const servers = asyncApiData.servers;
    if (servers) {
      const mainServer = (servers as Record<string, { url?: string }>).main;
      if (mainServer?.url) {
        return mainServer.url;
      }

      const serverEntries = Object.entries(
        servers as Record<string, { url?: string }>,
      );
      if (serverEntries.length > 0) {
        const [, firstServer] = serverEntries[0];
        if (firstServer?.url) {
          return firstServer.url;
        }
      }
    }

    return null;
  }

  private static resolveProtocol(asyncApiData: AsyncApiData): string {
    return (
      SpecificationTypeDetector.extractAsyncProtocolName(asyncApiData) ??
      "unknown"
    ).toLowerCase();
  }

  private static isAmqpProtocol(protocol: string): boolean {
    const normalized = protocol.toLowerCase();
    return (
      normalized === "amqp" ||
      normalized === "rabbit" ||
      normalized === "rabbitmq"
    );
  }
}
