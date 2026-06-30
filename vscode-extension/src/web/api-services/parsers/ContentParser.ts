import { Uri } from "vscode";
import { fileApi } from "../../response/file/fileApiProvider";
import { parseStructuredContent } from "./structuredContentParser";

/**
 * Utility class for parsing JSON and YAML content
 */
export class ContentParser {
  /**
   * Parse content as JSON or YAML
   * Tries JSON first, then YAML if JSON parsing fails
   */
  static parseContent(content: string): any {
    return parseStructuredContent(content);
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
