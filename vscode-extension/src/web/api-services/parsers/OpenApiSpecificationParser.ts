import { QipSpecificationGenerator } from "../../services/QipSpecificationGenerator";
import { ContentParser } from "./ContentParser";
import { OpenApiData } from "./parserTypes";

export class OpenApiSpecificationParser {
  /**
   * Parse OpenAPI/Swagger content and extract operations
   */
  static async parseOpenApiContent(content: string): Promise<OpenApiData> {
    const specData = ContentParser.parseContentWithErrorHandling(
      content,
      "OpenApiSpecificationParser",
    );

    // Validate that it's an OpenAPI/Swagger spec
    if (!specData.openapi && !specData.swagger) {
      throw new Error("Not a valid OpenAPI or Swagger specification");
    }

    // Basic validation
    this.validateOpenApiSpec(specData);

    return specData as OpenApiData;
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
        const address = `${scheme}://${host}${basePath}`;
        return address;
      }
    }

    // For OpenAPI 3.x
    if (openApiData.openapi) {
      const servers = openApiData.servers;
      if (servers && servers.length > 0) {
        const address = servers[0].url;
        return address;
      }
    }

    return null;
  }
}
