import { Uri } from "vscode";
import { fileApi } from "../response/file/fileApiProvider";

/**
 * Utility class for saving YAML files
 */
export class YamlFileUtils {
    /**
     * Save data as YAML file
     */
    static async saveYamlFile(fileUri: Uri, data: any): Promise<void> {
        const yaml = require('yaml');
        const yamlContent = yaml.stringify(data);
        const bytes = new TextEncoder().encode(yamlContent);
        await fileApi.writeFile(fileUri, bytes);
    }

    /**
     * Save data as YAML file with success message
     */
    static async saveYamlFileWithMessage(fileUri: Uri, data: any, successMessage: string): Promise<void> {
        await this.saveYamlFile(fileUri, data);
        const vscode = require('vscode');
        vscode.window.showInformationMessage(successMessage);
    }
}
