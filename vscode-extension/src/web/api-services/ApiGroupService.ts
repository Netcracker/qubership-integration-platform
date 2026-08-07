import { Uri } from "vscode";
import * as vscode from "vscode";
import * as yaml from "yaml";
import { IntegrationSystem, ApiGroup } from "./servicesTypes";
import { fileApi } from "../response/file/fileApiProvider";
import { getBaseFolder } from "../response/serviceApiUtils";
import { YamlFileUtils } from "./YamlFileUtils";
import { LabelUtils } from "./LabelUtils";
import { ProjectConfigService } from "../services/ProjectConfigService";
import {
  noMatchError,
  refuseUnreadableSibling,
  resolveFirstCandidate,
  scanMissRefusal,
  UnreadableOutcomeError,
} from "../response/file/lookupOutcome";
import {
  resolveApiFiles,
  resolveGroupFiles,
} from "../response/file/entityFiles";
import {
  API_GROUP_NAMES,
  CandidateOrder,
  candidateExtensions,
  currentExtension,
  legacyExtension,
} from "../response/file/namePrecedence";
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
    const groupFiles = await resolveGroupFiles(serviceFileUri);
    const resolved = groupFiles.byId.get(groupId);
    if (!resolved) {
      // A group whose only file the scan could not read is not an absent group: `null` here sends
      // every caller on as if it were, and the file to fix goes unnamed.
      const refusal = scanMissRefusal(
        groupId,
        groupFiles.unreadable,
        "API group ",
      );
      if (refusal) {
        throw refusal;
      }
      return null;
    }
    return {
      fileName: resolved.fileName,
      info: resolved.parsed,
      duplicates: resolved.duplicates.map((duplicate) => duplicate.fileName),
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

    // One entry per API id: an API stored under both names is one API, and listing it twice is
    // what a scan over the raw file names does.
    const apiIds: string[] = [];
    for (const [apiId, { parsed }] of (await resolveApiFiles(serviceFileUri))
      .byId) {
      const parentId = parsed?.content?.parentId ?? parsed?.parentId;
      if (parentId === groupId) {
        apiIds.push(apiId);
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
      // so try both before giving up, in the order `API_GROUP_NAMES` declares.
      const groupFileUri = await this.findGroupFileById(
        groupId,
        candidateExtensions(API_GROUP_NAMES, config.extensions),
      );
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
      // A file the scan could not read is no "no such group": answering null here would send the
      // caller on as if the group were absent, and the file that may hold it stays unnamed.
      if (error instanceof UnreadableOutcomeError) {
        throw error;
      }
      return null;
    }
  }

  private async findGroupFileById(
    groupId: string,
    extensions: CandidateOrder,
  ): Promise<Uri> {
    return await resolveFirstCandidate(
      extensions,
      (extension) => fileApi.findFileById(groupId, extension),
      {
        // A group stored under both extensions is the pair a re-save can overwrite, so the
        // lower-precedence name may not stand in for one the scan could not read.
        onUnreadable: (unreadable, resolved) =>
          refuseUnreadableSibling(groupId, resolved, unreadable, extensions),
        onNoMatch: (failures) =>
          noMatchError(failures, () => {
            const lastError = failures.causes[failures.causes.length - 1];
            return lastError instanceof Error
              ? lastError
              : new Error(`API group file for id ${groupId} not found`);
          }),
      },
    );
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
        `${apiGroup.id}${currentExtension(API_GROUP_NAMES, config.extensions)}`,
      );
      const legacyFile = Uri.joinPath(
        baseFolder,
        `${apiGroup.id}${legacyExtension(API_GROUP_NAMES, config.extensions)}`,
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
        // `schemaUrls` carries the same keys as `extensions`, so the name and its schema URL come
        // from one declaration and cannot drift apart.
        $schema: useLegacyName
          ? legacyExtension(API_GROUP_NAMES, config.schemaUrls)
          : currentExtension(API_GROUP_NAMES, config.schemaUrls),
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
