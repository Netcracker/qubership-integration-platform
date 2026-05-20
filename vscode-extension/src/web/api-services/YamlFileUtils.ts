import { Uri } from "vscode";
import * as vscode from "vscode";
import * as yaml from "yaml";
import { fileApi } from "../response/file/fileApiProvider";

/**
 * Utility class for saving YAML files
 */
export class YamlFileUtils {
  /**
   * Save data as YAML file
   */
  static async saveYamlFile(fileUri: Uri, data: any): Promise<void> {
    const yamlContent = yaml.stringify(data);
    const bytes = new TextEncoder().encode(yamlContent);
    await fileApi.writeFile(fileUri, bytes);
  }

  /**
   * Save data as YAML file with success message
   */
  static async saveYamlFileWithMessage(
    fileUri: Uri,
    data: any,
    successMessage: string,
  ): Promise<void> {
    await this.saveYamlFile(fileUri, data);
    vscode.window.showInformationMessage(successMessage);
  }
}
