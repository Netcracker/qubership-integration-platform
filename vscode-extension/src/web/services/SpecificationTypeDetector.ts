import { ApiSpecificationType } from "../api-services/importApiTypes";
import { parseStructuredContent } from "../api-services/parsers/structuredContentParser";

/**
 * Single source of truth for specification content detection: structured
 * parsing, OpenAPI/Swagger/AsyncAPI recognition, spec-version extraction, and
 * async-protocol resolution. Consolidates logic that was previously duplicated
 * across SpecificationProcessorService, SpecificationImportService and
 * ProtocolDetectorService (mirroring the backend's single ProtocolExtractionService).
 */
export class SpecificationTypeDetector {
  private static readonly ASYNC_PROTOCOL_MAP: Record<
    string,
    ApiSpecificationType
  > = {
    amqp: ApiSpecificationType.AMQP,
    rabbitmq: ApiSpecificationType.AMQP,
    mqtt: ApiSpecificationType.MQTT,
    kafka: ApiSpecificationType.KAFKA,
    redis: ApiSpecificationType.REDIS,
    nats: ApiSpecificationType.NATS,
    soap: ApiSpecificationType.SOAP,
    http: ApiSpecificationType.HTTP,
    https: ApiSpecificationType.HTTP,
  };

  /** Parse content as JSON, then YAML. Returns null if both fail. */
  static parse(content: string): any {
    try {
      return parseStructuredContent(content);
    } catch {
      return null;
    }
  }

  static isOpenApiOrSwagger(content: any): boolean {
    return (
      !!content &&
      typeof content === "object" &&
      !!(content.openapi || content.swagger)
    );
  }

  static isAsyncApi(content: any): boolean {
    return (
      !!content &&
      typeof content === "object" &&
      typeof content.asyncapi === "string"
    );
  }

  /** Content-type detection for OpenAPI/Swagger (HTTP) and AsyncAPI (ASYNC). */
  static detectStructuredType(content: any): ApiSpecificationType | null {
    if (this.isAsyncApi(content)) {
      return ApiSpecificationType.ASYNC;
    }
    if (this.isOpenApiOrSwagger(content)) {
      return ApiSpecificationType.HTTP;
    }
    return null;
  }

  /** Spec version from `info.version` for OpenAPI/Swagger/AsyncAPI, else default. */
  static extractSpecVersion(parsed: any, fallback = "1.0.0"): string {
    const version = parsed?.info?.version;
    if (
      (parsed?.openapi || parsed?.swagger || parsed?.asyncapi) &&
      typeof version === "string" &&
      version.length > 0
    ) {
      return version;
    }
    return fallback;
  }

  /**
   * Async protocol name from `info.x-protocol`, then `servers.main.protocol`,
   * then the first server's protocol.
   */
  static extractAsyncProtocolName(content: any): string | undefined {
    const infoProtocol = content?.info?.["x-protocol"];
    if (typeof infoProtocol === "string") {
      return infoProtocol;
    }

    const servers = content?.servers;
    if (servers && typeof servers === "object") {
      const mainProtocol = servers.main?.protocol;
      if (typeof mainProtocol === "string") {
        return mainProtocol;
      }
      for (const server of Object.values(servers)) {
        const protocol = (server as any)?.protocol;
        if (typeof protocol === "string") {
          return protocol;
        }
      }
    }

    return undefined;
  }

  /** Map a protocol name to a specification type (defaults to ASYNC). */
  static mapAsyncProtocolToType(
    protocol: string | undefined,
  ): ApiSpecificationType {
    if (!protocol) {
      return ApiSpecificationType.ASYNC;
    }
    return (
      this.ASYNC_PROTOCOL_MAP[protocol.trim().toLowerCase()] ??
      ApiSpecificationType.ASYNC
    );
  }

  /** Resolve the concrete async specification type directly from content. */
  static detectAsyncProtocol(content: any): ApiSpecificationType {
    return this.mapAsyncProtocolToType(this.extractAsyncProtocolName(content));
  }
}
