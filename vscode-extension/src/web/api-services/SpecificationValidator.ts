import {
  ApiSpecificationType,
  ASYNC_SPECIFICATION_TYPES,
} from "../api-services/importApiTypes";
import { FileParserService } from "../services/FileParserService";

export class SpecificationValidator {
  /**
   * Validates OpenAPI/Swagger specification from file
   */
  static async validateOpenApiSpecFromFile(file: File): Promise<boolean> {
    try {
      const { content } = await FileParserService.parseFileContent(file);
      this.validateOpenApiSpec(content);
      return true;
    } catch (error) {
      return false;
    }
  }

  /**
   * Validates specification protocol
   */
  static validateSpecificationProtocol(
    systemProtocol: ApiSpecificationType | undefined,
    importingProtocol: ApiSpecificationType,
  ): void {
    if (!systemProtocol) {
      return;
    }

    if (systemProtocol === importingProtocol) {
      return;
    }

    if (
      ASYNC_SPECIFICATION_TYPES.has(systemProtocol) &&
      ASYNC_SPECIFICATION_TYPES.has(importingProtocol)
    ) {
      return;
    }

    throw new Error(
      `Protocol mismatch: Cannot import ${importingProtocol} specification into ${systemProtocol} service. ` +
        `The specification protocol (${importingProtocol}) must match the service protocol (${systemProtocol}).`,
    );
  }

  /**
   * Validates OpenAPI specification structure
   */
  private static validateOpenApiSpec(spec: any): void {
    if (!spec || typeof spec !== "object") {
      throw new Error("Invalid specification: must be an object");
    }

    if (!spec.info) {
      throw new Error('Invalid specification: missing "info" field');
    }

    if (!spec.info.title) {
      throw new Error('Invalid specification: missing "info.title" field');
    }

    if (!spec.info.version) {
      throw new Error('Invalid specification: missing "info.version" field');
    }

    // Check OpenAPI version
    const isOpenApi3 = spec.openapi && spec.openapi.startsWith("3.");
    const isSwagger2 = spec.swagger && spec.swagger.startsWith("2.");

    if (!isOpenApi3 && !isSwagger2) {
      throw new Error(
        "Invalid specification: must be OpenAPI 3.x or Swagger 2.x",
      );
    }

    // Check for paths
    if (!spec.paths || typeof spec.paths !== "object") {
      throw new Error(
        'Invalid specification: missing or invalid "paths" field',
      );
    }

    // Check that there is at least one path
    const pathKeys = Object.keys(spec.paths);
    if (pathKeys.length === 0) {
      throw new Error("Invalid specification: no paths defined");
    }

    // Check path structure
    for (const path of pathKeys) {
      const pathItem = spec.paths[path];
      if (!pathItem || typeof pathItem !== "object") {
        throw new Error(
          `Invalid specification: invalid path item for "${path}"`,
        );
      }

      // Check HTTP methods
      const httpMethods = [
        "get",
        "post",
        "put",
        "delete",
        "patch",
        "head",
        "options",
      ];
      const hasValidMethod = httpMethods.some((method) => pathItem[method]);

      if (!hasValidMethod) {
        throw new Error(
          `Invalid specification: no valid HTTP methods found for path "${path}"`,
        );
      }
    }
  }
}
