import {QipSpecificationGenerator} from "../../services/QipSpecificationGenerator";
import {ContentParser} from "./ContentParser";
import {OpenApiData} from "./parserTypes";

export class OpenApiSpecificationParser {
  private static readonly OPENAPI_32_VERSION_PREFIX = "3.2";
  private static readonly OPENAPI_31_FALLBACK_VERSION = "3.1.0";

  /**
   * Parse OpenAPI/Swagger content and extract operations.
   *
   * Optional warnings sink collects non-fatal import notices (e.g. the
   * OpenAPI 3.2 bridge) so the import flow can surface them to the user.
   */
  static async parseOpenApiContent(
    content: string,
    warnings?: string[],
  ): Promise<OpenApiData> {
    const specData = ContentParser.parseContentWithErrorHandling(
      content,
      "OpenApiSpecificationParser",
    );

    // Validate that it's an OpenAPI/Swagger spec
    if (!specData.openapi && !specData.swagger) {
      throw new Error("Not a valid OpenAPI or Swagger specification");
    }

    this.normalizeUnsupportedOpenApiVersion(specData, warnings);

    // Basic validation
    this.validateOpenApiSpec(specData);

    return specData as OpenApiData;
  }

  /**
   * Bridges OpenAPI 3.2 onto the 3.1 import path: rewrites the `openapi`
   * version to 3.1.0 and records a warning. Mirrors the runtime-catalog
   * backend (SwaggerSpecificationParser) so offline imports stay byte-compatible
   * with the online import. 3.2-only constructs (the QUERY method, `$self`,
   * extended mediaType keys) degrade gracefully: the QUERY method is dropped
   * because it is absent from `QipSpecificationGenerator.HTTP_METHODS`.
   */
  private static normalizeUnsupportedOpenApiVersion(
    spec: any,
    warnings?: string[],
  ): void {
    const version =
      typeof spec?.openapi === "string" ? (spec.openapi as string) : "";
    if (!version.startsWith(this.OPENAPI_32_VERSION_PREFIX)) {
      return;
    }
    spec.openapi = this.OPENAPI_31_FALLBACK_VERSION;
    warnings?.push(
      `OpenAPI ${version} imported with the 3.1 parser. 3.2-only features may be dropped.`,
    );
  }

  /**
   * Basic validation of OpenAPI/Swagger specification
   */
  private static validateOpenApiSpec(spec: any): void {
    // Check required fields
    if (!spec.info) {
      throw new Error('OpenAPI specification must have an "info" object');
    }

    if (!spec.info.title) {
      throw new Error('OpenAPI specification "info" must have a "title" field');
    }

    if (!spec.info.version) {
      throw new Error(
        'OpenAPI specification "info" must have a "version" field',
      );
    }

    // Check version format
    if (spec.openapi && !spec.openapi.match(/^\d+\.\d+\.\d+$/)) {
      console.warn(
        "[OpenApiSpecificationParser] OpenAPI version format may be invalid:",
        spec.openapi,
      );
    }

    if (spec.swagger && !spec.swagger.match(/^\d+\.\d+$/)) {
      console.warn(
        "[OpenApiSpecificationParser] Swagger version format may be invalid:",
        spec.swagger,
      );
    }

    // Check paths
    if (!spec.paths || Object.keys(spec.paths).length === 0) {
      console.warn(
        "[OpenApiSpecificationParser] OpenAPI specification has no paths defined",
      );
    }
  }

  /**
   * Create operations from OpenAPI data using QipSpecificationGenerator
   */
  static createOperationsFromOpenApi(
    openApiData: OpenApiData,
    specificationId: string,
  ): any[] {
    // Create full QIP specification using QipSpecificationGenerator
    const qipSpec = QipSpecificationGenerator.createQipSpecificationFromOpenApi(
      openApiData,
      "specification",
      specificationId,
    );
    const operations = qipSpec.content?.operations || [];

    return operations.map((operation: any) => ({
      ...operation,
      id: `${specificationId}-${operation.name}`,
    }));
  }

  /**
   * Extract address from OpenAPI/Swagger data
   */
  static extractAddressFromOpenApiData(
    openApiData: OpenApiData,
  ): string | null {
    // For Swagger 2.0
    if (openApiData.swagger) {
      const specData = openApiData as any;
      const host = specData.host;
      const basePath = specData.basePath || "";
      const schemes = specData.schemes || ["https"];
      const scheme = schemes[0];

      if (host) {
        return `${scheme}://${host}${basePath}`;
      }
    }

    // For OpenAPI 3.x
    if (openApiData.openapi) {
      const servers = openApiData.servers;
      if (servers && servers.length > 0) {
        return servers[0].url;
      }
    }

    return null;
  }
}
