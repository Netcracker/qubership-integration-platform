import { Uri } from "vscode";
import * as yaml from "yaml";
import { fileApi } from "../../response/file/fileApiProvider";

/**
 * Utility class for parsing JSON and YAML content
 */
export class ContentParser {
  /**
   * Parse content as JSON or YAML
   * Tries JSON first, then YAML if JSON parsing fails
   */
  static parseContent(content: string): any {
    try {
      // Try to parse as JSON first
      return JSON.parse(content);
    } catch (jsonError) {
      try {
        // If JSON parsing fails, try YAML
        return yaml.parse(content, { maxAliasCount: -1 });
      } catch (yamlError) {
        console.error(
          "[ContentParser] Error parsing content as both JSON and YAML:",
          { jsonError, yamlError },
        );
        throw new Error("Failed to parse content: not valid JSON or YAML");
      }
    }
  }

  /**
   * Parse content as JSON or YAML with custom error handling
   */
  static parseContentWithErrorHandling(
    content: string,
    parserName: string,
  ): any {
    try {
      return this.parseContent(content);
    } catch (error) {
      console.error(`[${parserName}] Error parsing content:`, error);
      throw new Error(
        `Failed to parse ${parserName} specification: ${error instanceof Error ? error.message : "Unknown error"}`,
      );
    }
  }

  /**
   * Read file content and parse it as JSON or YAML
   */
  static async parseContentFromFile(fileUri: Uri): Promise<any> {
    const content = await fileApi.readFileContent(fileUri);
    return this.parseContent(content);
  }
}
