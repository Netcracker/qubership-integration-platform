import { OpenApiSpecificationParser } from "./OpenApiSpecificationParser";
import { AsyncApiSpecificationParser } from "./AsyncApiSpecificationParser";
import { ProtoSpecificationParser } from "./ProtoSpecificationParser";
import { SpecificationTypeDetector } from "../../services/SpecificationTypeDetector";
import { ASYNC_SPECIFICATION_TYPES } from "../importApiTypes";

export interface ExtractedOperationSchemas {
  specification: Record<string, unknown>;
  requestSchema: Record<string, unknown>;
  responseSchemas: Record<string, unknown>;
}

interface GeneratedOperation {
  name?: string;
  method?: string;
  path?: string;
  specification?: Record<string, unknown>;
  requestSchema?: Record<string, unknown>;
  responseSchemas?: Record<string, unknown>;
}

const emptySchemas = (): ExtractedOperationSchemas => ({
  specification: {},
  requestSchema: {},
  responseSchemas: {},
});

// Extraction only ever matches an already-generated operation by (path, method);
// the id prefix normally built from a specificationId is irrelevant here.
const EXTRACTION_ID = "extract";

// A soft backstop against pathological or corrupted files. The extension parses in
// the extension host, where a runaway parse blocks the editor, so the cap stays here
// even though the backend extractor has none.
const MAX_SOURCE_LENGTH = 5_000_000;

type ExtractableProtocol = "HTTP" | "ASYNC" | "GRPC";

/**
 * Rebuilds one operation's request/response schemas and its specification slice
 * on demand from the raw specification source, mirroring the runtime-catalog
 * backend's `OperationSchemaExtractor`: parse the raw file, match the operation
 * by (path, method), and hand back the same values the full per-protocol parser
 * would have produced at import — reusing each parser's `createOperationsFromX`
 * rather than reimplementing schema production.
 *
 * SOAP and GraphQL carry no schemas by design (mirrors the backend's
 * `hasNoSchemaExtraction`). Any parse failure, oversized source, or unmatched
 * operation degrades gracefully to empty values instead of throwing.
 */
export class OperationSchemaExtractor {
  /**
   * Whether reading a model of this type can rebuild an operation's
   * specification slice from the raw source. The writer strips the stored slice
   * only when this holds, so the strip and the rebuild stay one decision rather
   * than two lists that drift apart — the same gate runtime-catalog's
   * `ServiceSerializer.stripOperationSpecifications` uses.
   *
   * `graphql` is the one place this says no where the backend says yes: the
   * extractor has no graphql path, so a stripped slice would be lost.
   */
  static canRebuildSpecification(specificationType?: string): boolean {
    const type = (specificationType ?? "").toLowerCase();
    return type === "openapi" || type === "asyncapi" || type === "protobuf";
  }

  static async extract(
    rawContent: string | null | undefined,
    formatHint: string | undefined,
    path: string,
    method: string,
  ): Promise<ExtractedOperationSchemas> {
    if (!rawContent || rawContent.length > MAX_SOURCE_LENGTH) {
      return emptySchemas();
    }

    const protocol = this.resolveProtocol(formatHint, rawContent);
    if (!protocol) {
      return emptySchemas();
    }

    try {
      const operations = await this.parseAllOperations(protocol, rawContent);
      const matched = this.matchOperation(operations, path, method);
      return matched
        ? {
            specification: matched.specification ?? {},
            requestSchema: matched.requestSchema ?? {},
            responseSchemas: matched.responseSchemas ?? {},
          }
        : emptySchemas();
    } catch (error) {
      console.warn(
        `[OperationSchemaExtractor] Failed to extract schemas for ${method} ${path}:`,
        error,
      );
      return emptySchemas();
    }
  }

  private static resolveProtocol(
    formatHint: string | undefined,
    rawContent: string,
  ): ExtractableProtocol | null {
    const fromHint = this.protocolFromFormat(formatHint);
    return fromHint !== undefined ? fromHint : this.sniffProtocol(rawContent);
  }

  /**
   * `undefined` means the hint is missing or unrecognized (fall back to
   * sniffing); `null` means it names a protocol that carries no schemas.
   */
  private static protocolFromFormat(
    formatHint: string | undefined,
  ): ExtractableProtocol | null | undefined {
    const upper = (formatHint ?? "").toUpperCase();
    if (upper === "HTTP") {
      return "HTTP";
    }
    if ((ASYNC_SPECIFICATION_TYPES as ReadonlySet<string>).has(upper)) {
      return "ASYNC";
    }
    if (upper === "GRPC") {
      return "GRPC";
    }
    if (upper === "SOAP" || upper === "GRAPHQL") {
      return null;
    }
    return undefined;
  }

  private static sniffProtocol(rawContent: string): ExtractableProtocol | null {
    if (this.isLikelyWsdlContent(rawContent)) {
      return null; // SOAP: no schema extraction by design
    }

    const parsed = SpecificationTypeDetector.parse(rawContent);
    if (parsed && typeof parsed === "object") {
      if (SpecificationTypeDetector.isAsyncApi(parsed)) {
        return "ASYNC";
      }
      if (SpecificationTypeDetector.isOpenApiOrSwagger(parsed)) {
        return "HTTP";
      }
    }

    if (this.isLikelyProtoContent(rawContent)) {
      return "GRPC";
    }

    // GraphQL and anything unrecognized degrade to empty schemas.
    return null;
  }

  private static isLikelyWsdlContent(content: string): boolean {
    const snippet = content.slice(0, 512).toLowerCase();
    return (
      snippet.includes("http://schemas.xmlsoap.org/wsdl") ||
      snippet.includes("http://www.w3.org/ns/wsdl")
    );
  }

  private static isLikelyProtoContent(content: string): boolean {
    return (
      /\bsyntax\s*=\s*["']proto[23]["']/.test(content) ||
      (/\bservice\s+\w+\s*\{/.test(content) && /\brpc\s+\w+\s*\(/.test(content))
    );
  }

  private static async parseAllOperations(
    protocol: ExtractableProtocol,
    rawContent: string,
  ): Promise<GeneratedOperation[]> {
    switch (protocol) {
      case "HTTP": {
        const data =
          await OpenApiSpecificationParser.parseOpenApiContent(rawContent);
        return OpenApiSpecificationParser.createOperationsFromOpenApi(
          data,
          EXTRACTION_ID,
        );
      }
      case "ASYNC": {
        const data =
          await AsyncApiSpecificationParser.parseAsyncApiContent(rawContent);
        return AsyncApiSpecificationParser.createOperationsFromAsyncApi(
          data,
          EXTRACTION_ID,
        );
      }
      case "GRPC": {
        const data =
          await ProtoSpecificationParser.parseProtoContent(rawContent);
        return ProtoSpecificationParser.createOperationsFromProto(
          data,
          EXTRACTION_ID,
        );
      }
    }
  }

  /**
   * Matches by exact (path, method), mirroring the backend's `matchOperation`.
   * Returns undefined (never throws) when the filter is not a single hit, so a
   * miss or an ambiguous match degrades to empty schemas rather than picking a
   * wrong operation.
   */
  private static matchOperation(
    operations: GeneratedOperation[],
    path: string,
    method: string,
  ): GeneratedOperation | undefined {
    const byPathAndMethod = operations.filter(
      (op) =>
        op.path === path &&
        !!method &&
        op.method?.toUpperCase() === method.toUpperCase(),
    );
    return byPathAndMethod.length === 1 ? byPathAndMethod[0] : undefined;
  }
}
