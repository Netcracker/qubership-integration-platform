import { Uri } from "vscode";
import * as vscode from "vscode";
import * as yaml from "yaml";
import { IntegrationSystem, ApiGroup } from "./servicesTypes";
import { fileApi } from "../response/file/fileApiProvider";
import { getBaseFolder } from "../response/serviceApiUtils";
import { YamlFileUtils } from "./YamlFileUtils";
import { LabelUtils } from "./LabelUtils";
import { ProjectConfigService } from "../services/ProjectConfigService";
import { getExtensionsForUri } from "../response/file/fileExtensions";
import { ContentParser } from "./parsers/ContentParser";

/** One group file plus any same-id siblings stored under the other group extension. */
export interface ResolvedGroupFile {
  fileName: string;
  info: any;
  duplicates: string[];
}

/**
 * Service for managing API groups
 */
export class ApiGroupService {
  private readonly mainFolder?: Uri;

  constructor(mainFolder?: Uri) {
    this.mainFolder = mainFolder;
  }

  /**
   * Resolve a group id to exactly one file in the service folder.
   *
   * A project can hold both `<id>.api-group.<app>.yaml` and `<id>.specification-group.<app>.yaml` for one group:
   * a backend export dropped next to a hand-kept older file, a half-finished migration, a merge. The extension
   * itself never creates that state, but every path that reads, writes, lists or deletes a group goes through
   * this resolver so they all agree on which file wins: the current `.api-group.` one. The losers come back as
   * `duplicates` so a delete can clear them instead of leaving a file that resurrects the group.
   */
  static async resolveGroupFile(
    serviceFileUri: Uri,
    groupId: string,
  ): Promise<ResolvedGroupFile | null> {
    const serviceFolderUri = Uri.joinPath(serviceFileUri, "..");
    const apiGroupExtension = getExtensionsForUri(serviceFileUri).apiGroup;
    const groupFiles = await fileApi.getSpecificationGroupFiles(serviceFileUri);

    const matches: { fileName: string; info: any }[] = [];
    for (const fileName of groupFiles) {
      try {
        const parsed = await ContentParser.parseContentFromFile(
          Uri.joinPath(serviceFolderUri, fileName),
        );
        if (parsed?.id === groupId) {
          matches.push({ fileName, info: parsed });
        }
      } catch (error) {
        console.error(
          `[ApiGroupService] Error reading API group file ${fileName}:`,
          error,
        );
      }
    }

    if (matches.length === 0) {
      return null;
    }

    const preferred =
      matches.find((match) => match.fileName.endsWith(apiGroupExtension)) ??
      matches[0];
    return {
      fileName: preferred.fileName,
      info: preferred.info,
      duplicates: matches
        .filter((match) => match !== preferred)
        .map((match) => match.fileName),
    };
  }

  /**
   * Rebuild a group's derived `apis[]` from the API files on disk.
   *
   * `parentId` on each API file is the source of truth for the API -> group
   * link; `apis[]` is derived and read by nobody. Call this after any API write
   * or delete so a stale or hand-edited list is corrected from the actual files.
   */
  static async regenerateGroupApis(
    serviceFileUri: Uri,
    groupId: string,
  ): Promise<void> {
    const serviceFolderUri = Uri.joinPath(serviceFileUri, "..");

    const resolved = await ApiGroupService.resolveGroupFile(
      serviceFileUri,
      groupId,
    );

    // Group file already gone (e.g. after a whole-group delete) — nothing to do.
    if (!resolved) {
      return;
    }
    const { fileName: groupFileName, info: groupInfo } = resolved;

    const apiIds: string[] = [];
    const specificationFiles =
      await fileApi.getSpecificationFiles(serviceFileUri);
    for (const fileName of specificationFiles) {
      try {
        const parsed = await ContentParser.parseContentFromFile(
          Uri.joinPath(serviceFolderUri, fileName),
        );
        const parentId = parsed?.content?.parentId ?? parsed?.parentId;
        if (parentId === groupId && parsed?.id) {
          apiIds.push(parsed.id);
        }
      } catch (error) {
        console.error(
          `[ApiGroupService] Error reading specification file ${fileName}:`,
          error,
        );
      }
    }

    if (!groupInfo.content) {
      groupInfo.content = {};
    }
    groupInfo.content.apis = apiIds;

    const bytes = new TextEncoder().encode(yaml.stringify(groupInfo));
    await fileApi.writeFile(
      Uri.joinPath(serviceFolderUri, groupFileName),
      bytes,
    );
  }

  // Best-effort wrapper around regenerateGroupApis. `apis[]` is derived, so a
  // failure must not roll back the committed API write or delete — the next
  // write heals it. No-ops when the service file or group id is missing.
  static async regenerateGroupApisSafely(
    serviceFileUri: Uri | undefined,
    groupId: string | undefined,
  ): Promise<void> {
    if (!serviceFileUri || !groupId) {
      return;
    }
    try {
      await this.regenerateGroupApis(serviceFileUri, groupId);
    } catch (error) {
      console.error(
        `[ApiGroupService] Error regenerating apis[] for group ${groupId}:`,
        error,
      );
    }
  }

  /**
   * Get API group by ID
   */
  async getApiGroupById(
    groupId: string,
    systemId: string,
  ): Promise<ApiGroup | null> {
    try {
      const config = ProjectConfigService.getConfig();
      // A group file may sit under either extension: `.api-group.<app>.yaml` from the current backend export or
      // `.specification-group.<app>.yaml` from an older one. findFileById throws when an extension matches nothing,
      // so try both before giving up. The `.api-group.` one comes first, matching resolveGroupFile's precedence.
      const groupFileUri = await this.findGroupFileById(groupId, [
        config.extensions.apiGroup,
        config.extensions.specificationGroup,
      ]);
      const parsed = await ContentParser.parseContentFromFile(groupFileUri);

      return {
        id: parsed.id,
        name: parsed.name,
        description: parsed.description || "",
        parentId: parsed.content?.parentId || parsed.parentId,
        specifications: [],
        synchronization:
          parsed.content?.synchronization || parsed.synchronization || false,
      };
    } catch (error) {
      console.error(
        `[ApiGroupService] Error getting API group ${groupId}:`,
        error,
      );
      return null;
    }
  }

  private async findGroupFileById(
    groupId: string,
    extensions: string[],
  ): Promise<Uri> {
    let lastError: unknown;
    for (const extension of extensions) {
      try {
        return await fileApi.findFileById(groupId, extension);
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError instanceof Error
      ? lastError
      : new Error(`API group file for id ${groupId} not found`);
  }

  /**
   * Create API group
   */
  async createApiGroup(
    system: IntegrationSystem,
    name: string,
    protocol?: string,
  ): Promise<ApiGroup> {
    const groupId = `${system.id}-${name}`;

    const apiGroup: ApiGroup = {
      id: groupId,
      name: name,
      systemId: system.id, // Store systemId for UI compatibility
      specifications: [],
      synchronization: false,
    };

    if (protocol) {
      system.protocol = protocol;
    }

    return apiGroup;
  }

  /**
   * Save API group file
   */
  async saveApiGroupFile(systemId: string, apiGroup: ApiGroup): Promise<void> {
    try {
      const baseFolder = await getBaseFolder(
        this.mainFolder,
        vscode.workspace.workspaceFolders?.[0]?.uri,
      );
      if (!baseFolder) {
        throw new Error("No base folder available");
      }

      const config = ProjectConfigService.getConfig();
      // Same precedence as resolveGroupFile: the current `.api-group.` file wins, so a write lands where every
      // read looks. A group that exists only under the pre-rename extension keeps that file, so re-saving it
      // does not leave two files for one group. Anything new is written in the `.api-group.` format.
      const apiGroupFile = Uri.joinPath(
        baseFolder,
        `${apiGroup.id}${config.extensions.apiGroup}`,
      );
      const legacyFile = Uri.joinPath(
        baseFolder,
        `${apiGroup.id}${config.extensions.specificationGroup}`,
      );
      const useLegacyName =
        !(await this.fileExists(apiGroupFile)) &&
        (await this.fileExists(legacyFile));
      const groupFile = useLegacyName ? legacyFile : apiGroupFile;

      console.log(`[ApiGroupService] Saving API group file:`, {
        systemId,
        apiGroupId: apiGroup.id,
        groupFile: groupFile.fsPath,
      });

      const yamlData = {
        id: apiGroup.id,
        $schema: useLegacyName
          ? config.schemaUrls.specificationGroup
          : config.schemaUrls.apiGroup,
        name: apiGroup.name,
        content: {
          synchronization: apiGroup.synchronization || false,
          parentId: systemId,
          labels: apiGroup.labels
            ? LabelUtils.fromEntityLabels(apiGroup.labels)
            : [],
        },
      };

      console.log(`[ApiGroupService] YAML content:`, yamlData);
      await YamlFileUtils.saveYamlFile(groupFile, yamlData);
      console.log(
        `[ApiGroupService] Saved API group file: ${groupFile.fsPath}`,
      );
    } catch (error) {
      console.error(`[ApiGroupService] Error saving API group file:`, {
        error: error instanceof Error ? error.message : String(error),
        stack: error instanceof Error ? error.stack : undefined,
        systemId,
        apiGroupId: apiGroup.id,
        mainFolder: this.mainFolder?.fsPath,
      });
      throw new Error(
        `Failed to save API group file: ${error instanceof Error ? error.message : "Unknown error"}`,
      );
    }
  }

  private async fileExists(uri: Uri): Promise<boolean> {
    try {
      await vscode.workspace.fs.stat(uri);
      return true;
    } catch {
      return false;
    }
  }
}
