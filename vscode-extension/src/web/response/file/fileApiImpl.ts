import { FileApi } from "./fileApi";
import { ExtensionContext, Uri } from "vscode";
import * as vscode from "vscode";
import * as yaml from "yaml";
import * as path from "path";
import { LibraryData } from "@netcracker/qip-ui";
import { QipFileType } from "../serviceApiUtils";
import { FileFilter, UnreadableFileError } from "../fileFilteringUtils";
import {
  getExtensionsForFile,
  getExtensionsForUri,
  extractFilename,
  FileExtensionsConfig,
} from "./fileExtensions";
import { Chain as ChainSchema } from "@netcracker/qip-schemas";
import { ContentParser } from "../../api-services/parsers/ContentParser";
import { ServiceNormalizer } from "../../api-services/ServiceNormalizer";
import { ProjectConfigService } from "../../services/ProjectConfigService";
import { FileCacheService } from "../../services/FileCacheService";
import {
  CHAIN_ROUTES,
  CONTEXT_SERVICE_ROUTES,
  MCP_SERVICE_ROUTES,
  SERVICE_ROUTES,
} from "../apiRouter";
import { extractEntityId } from "../navigationUtils";
import { shapeServiceFile, ServiceFileKind } from "./serviceFileShape";
import {
  isAnyServiceFile,
  plainServiceExtensions,
  serviceExtensionForType,
  serviceSchemaUrlForType,
} from "./serviceFileType";
import {
  CHAIN_MIGRATIONS,
  MCP_SERVICE_MIGRATIONS,
  repairMigrationsClaim,
  SERVICE_MIGRATIONS,
} from "../../services/importMigrationVersions";
export const RESOURCES_FOLDER = "resources";

/** Picks the key order to write with; the three kinds have different export DTOs. */
function serviceFileKind(fileUri: Uri): ServiceFileKind {
  const ext = getExtensionsForUri(fileUri);
  if (fileUri.path.endsWith(ext.mcpService)) {
    return "mcpService";
  }
  if (fileUri.path.endsWith(ext.contextService)) {
    return "contextService";
  }
  return "service";
}

export class VSCodeFileApi implements FileApi {
  context: ExtensionContext;

  constructor(context: ExtensionContext) {
    this.context = context;
  }

  private getExtensionsForContext(currentFileUri?: Uri) {
    if (currentFileUri) {
      return getExtensionsForFile(extractFilename(currentFileUri));
    }
    return getExtensionsForFile();
  }

  getRootDirectory(): Uri {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      throw new Error("No workspace folder is open");
    }
    return workspaceFolders[0].uri;
  }

  async findFileByNavigationPath(path: string): Promise<Uri> {
    const extensions = this.getExtensionsForContext();
    // A service route names no type, so every plain-service name is a candidate; the other
    // routes resolve to exactly one. Typed names come first, so a converted service that still
    // has its legacy sibling resolves to the file the next write lands on.
    let candidates: string[] | undefined = undefined;

    for (const regexp of SERVICE_ROUTES) {
      if (regexp.test(path)) {
        candidates = plainServiceExtensions(extensions);
      }
    }

    for (const regexp of CHAIN_ROUTES) {
      if (regexp.test(path)) {
        candidates = [extensions.chain];
      }
    }

    for (const regexp of CONTEXT_SERVICE_ROUTES) {
      if (regexp.test(path)) {
        candidates = [extensions.contextService];
      }
    }

    for (const regexp of MCP_SERVICE_ROUTES) {
      if (regexp.test(path)) {
        candidates = [extensions.mcpService];
      }
    }

    if (!candidates) {
      throw new Error(`Invalid navigation path: ${path}`);
    }

    const entityId = extractEntityId(path);

    let lastError: unknown;
    for (const extension of candidates) {
      try {
        return await this.findFileById(entityId, extension);
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError instanceof Error
      ? lastError
      : new Error(`File with id ${entityId} not found for path: ${path}`);
  }

  private isWindowsPath(p: string): boolean {
    return /^[a-zA-Z]:\\/.test(p) || p.includes("\\");
  }

  private resolveParentDirectory(uri: Uri): Uri {
    const p = path.dirname(uri.path);
    if (uri.scheme === "git" && uri.query) {
      const query = JSON.parse(uri.query);
      if (query?.path) {
        const dir = this.isWindowsPath(query.path)
          ? path.win32.dirname(query.path)
          : path.dirname(query.path);
        return uri.with({
          path: p,
          query: JSON.stringify({ ...query, path: dir }),
        });
      }
    }
    return uri.with({ path: p });
  }

  private addToPath(uri: Uri, siffux: string): Uri {
    const p = path.join(uri.path, siffux);
    if (uri.scheme === "git" && uri.query) {
      const query = JSON.parse(uri.query);
      if (query?.path) {
        const pth = this.isWindowsPath(query.path)
          ? path.win32.join(query.path, siffux)
          : path.join(query.path, siffux);
        return uri.with({
          path: p,
          query: JSON.stringify({ ...query, path: pth }),
        });
      }
    }
    return uri.with({ path: p });
  }

  private async getParentDirectoryUri(uri: Uri): Promise<Uri> {
    try {
      const stat = await vscode.workspace.fs.stat(uri);
      if (stat.type === vscode.FileType.File) {
        return this.resolveParentDirectory(uri);
      }
      return uri;
    } catch (_e) {
      return this.resolveParentDirectory(uri);
    }
  }

  private async getFilesByExtensionInDirectory(
    directoryUri: Uri,
    extension: string,
  ): Promise<string[]> {
    return this.getFilesByExtensionsInDirectory(directoryUri, [extension]);
  }

  private async getFilesByExtensionsInDirectory(
    directoryUri: Uri,
    extensions: string[],
  ): Promise<string[]> {
    const entries = await readDirectory(directoryUri);
    return entries
      .filter(([, type]: [string, number]) => type === 1)
      .filter(([name]: [string, number]) =>
        extensions.some((extension) => name.endsWith(extension)),
      )
      .map(([name]: [string, number]) => name);
  }

  private async getMainChainFileUri(baseUri: Uri): Promise<Uri> {
    if (!baseUri) {
      throw Error("No base uri provided");
    }
    const stat = await vscode.workspace.fs.stat(baseUri);
    if (stat.type === vscode.FileType.File) {
      return baseUri;
    }
    const extensions = this.getExtensionsForContext(baseUri);
    const files = await this.getFilesByExtensionInDirectory(
      baseUri,
      extensions.chain,
    );
    if (files.length !== 1) {
      console.error(
        `Single *${extensions.chain} file not found in the current directory`,
      );
      vscode.window.showWarningMessage(
        `*${extensions.chain} file not found in the current directory`,
      );
      throw Error(
        `Single *${extensions.chain} file not found in the current directory`,
      );
    }
    return vscode.Uri.joinPath(baseUri, files[0]);
  }

  async findAndBuildChainsRecursively<T>(
    folderUri: Uri,
    chainBuilder: (chainContent: any) => T | undefined,
    result: T[],
  ): Promise<void> {
    const entries = await readDirectory(folderUri);
    const extensions = this.getExtensionsForContext(folderUri);

    for (const [name, type] of entries) {
      if (type === vscode.FileType.File && name.endsWith(extensions.chain)) {
        const fileUri = vscode.Uri.joinPath(folderUri, name);

        const chainYaml = await this.parseFile(fileUri);
        const chain = chainBuilder(chainYaml);
        if (chain) {
          result.push(chain);
        }
      } else if (type === vscode.FileType.Directory) {
        const subFolderUri = vscode.Uri.joinPath(folderUri, name);
        await this.findAndBuildChainsRecursively(
          subFolderUri,
          chainBuilder,
          result,
        );
      }
    }
  }

  async findFileById(id: string, extension?: string): Promise<Uri> {
    const cacheService = FileCacheService.getInstance();

    const cachedUri = cacheService.getFileUri(id, extension);
    // The group extensions share one cache entry per id, and so do the four plain-service ones, so a
    // hit may be a file of another extension. Honour the requested extension and rescan instead, or
    // the caller's precedence order means nothing.
    if (
      cachedUri &&
      (!extension || extractFilename(cachedUri).endsWith(extension))
    ) {
      try {
        await vscode.workspace.fs.stat(cachedUri);
        return cachedUri;
      } catch {
        cacheService.invalidateByUri(cachedUri);
      }
    }

    if (extension) {
      const rootDir = this.getRootDirectory();
      const conventionUri = Uri.joinPath(rootDir, id, `${id}${extension}`);
      try {
        await vscode.workspace.fs.stat(conventionUri);
        const content = await this.parseFile(conventionUri);
        if (content?.id === id) {
          cacheService.setFileUri(id, extension, conventionUri);
          return conventionUri;
        }
      } catch {}

      const uri = await this.findFile(extension, (fileContent: any) => {
        return fileContent?.id === id;
      });
      cacheService.setFileUri(id, extension, uri);
      return uri;
    }

    const extensions = getExtensionsForFile();
    const typesToTry = [
      extensions.mcpService,
      extensions.contextService,
      ...plainServiceExtensions(extensions),
      extensions.chain,
      // `.api-group.` before `.specification-group.`, matching ApiGroupService.resolveGroupFile's precedence.
      extensions.apiGroup,
      extensions.specificationGroup,
      extensions.specification,
      extensions.api,
    ];

    for (const ext of typesToTry) {
      try {
        const uri = await this.findFile(ext, (fileContent: any) => {
          return fileContent?.id === id;
        });
        cacheService.setFileUri(id, ext, uri);
        return uri;
      } catch (e) {
        continue;
      }
    }

    throw new Error(`File with id ${id} not found with any known extension`);
  }

  async findFile(
    extension: string,
    filterPredicate?: (fileContent: any) => boolean,
  ): Promise<Uri> {
    const result: Uri[] = [];
    const unreadable: Uri[] = [];
    const folderUri = this.getRootDirectory();

    await this.collectFiles(
      folderUri,
      { extension: extension, predicate: filterPredicate, findFirst: true },
      result,
      unreadable,
    );

    if (result.length > 0) {
      return result[0];
    }
    // A file the parser choked on may be the one asked for, so no match is a miss only when every
    // candidate was readable. Reporting it as a plain miss let a caller that tries one name after
    // another move on and answer from a file that lost the precedence race.
    if (unreadable.length > 0) {
      throw new UnreadableFileError(extension, unreadable);
    }
    throw Error(`Unable to find file with extension: ${extension}`);
  }

  /**
   * Every file carrying the extension. No caller passes a predicate — this is a listing by name,
   * and each one re-reads the file it picks — so nothing here parses and an unreadable file is
   * listed like any other. A predicate would drop such a file silently; give one a reason to exist
   * and report the drop, the way `findFile` does.
   */
  async findFiles(
    extension: string,
    filterPredicate?: (fileContent: any) => boolean,
  ): Promise<Uri[]> {
    const result: Uri[] = [];
    const folderUri = this.getRootDirectory();

    await this.collectFiles(
      folderUri,
      { extension: extension, predicate: filterPredicate, findFirst: false },
      result,
    );

    return result;
  }

  /**
   * Walks the tree for files carrying the extension. `unreadable` collects the ones a predicate
   * had to parse and the parser rejected: they are neither a match nor a miss, and only `findFile`
   * can say which of the two the caller may treat them as.
   */
  private async collectFiles(
    folderUri: Uri,
    fileFilter: FileFilter,
    result: Uri[],
    unreadable: Uri[] = [],
  ): Promise<void> {
    const entries = await readDirectory(folderUri);

    for (const [name, type] of entries) {
      if (
        type === vscode.FileType.File &&
        name.endsWith(fileFilter.extension)
      ) {
        const fileUri = vscode.Uri.joinPath(folderUri, name);
        // Only a predicate needs the content. Letting a parse failure throw aborted the whole
        // scan; swallowing it made the file invisible. Both end the same way — the lookup answers
        // from another name — so the file is recorded and the decision left to `findFile`.
        if (fileFilter.predicate) {
          let contentYaml;
          try {
            contentYaml = await this.parseFile(fileUri);
          } catch {
            unreadable.push(fileUri);
            continue;
          }
          if (!fileFilter.predicate(contentYaml)) {
            continue;
          }
        }
        result.push(fileUri);
        if (fileFilter.findFirst) {
          return;
        }
      } else if (type === vscode.FileType.Directory) {
        const subFolderUri = vscode.Uri.joinPath(folderUri, name);
        await this.collectFiles(subFolderUri, fileFilter, result, unreadable);
      }
    }
  }

  async getMainChain(parameters: any): Promise<ChainSchema> {
    const baseUri = parameters as Uri;
    const fileUri = await this.getMainChainFileUri(baseUri);
    try {
      const parsed = await ContentParser.parseContentFromFile(fileUri);

      if (parsed && parsed.name) {
        // A chain authored before the claim was written would be unimportable;
        // the next save carries the repair into the file.
        repairMigrationsClaim(parsed.content, CHAIN_MIGRATIONS);
        return parsed;
      }
      throw Error("Invalid chain file content");
    } catch (e) {
      console.error(
        `Chain file ${fileUri} can't be parsed from QIP Extension API`,
        e,
      );
      throw e;
    }
  }

  async readFile(parameters: any, propertiesFilename: string): Promise<string> {
    const baseUri = parameters as Uri;
    const baseFolder = await this.getParentDirectoryUri(baseUri);
    const fileUri = this.addToPath(baseFolder, propertiesFilename);
    let fileContent;
    try {
      fileContent = await this.readFileContent(fileUri);
    } catch (error) {
      if (!propertiesFilename.includes(RESOURCES_FOLDER)) {
        return await this.readFile(
          baseUri,
          RESOURCES_FOLDER + "/" + propertiesFilename,
        );
      }
      throw error;
    }
    return fileContent;
  }

  async parseFile(fileUri: Uri): Promise<any> {
    try {
      return await ContentParser.parseContentFromFile(fileUri);
    } catch (e) {
      console.error(`Unable to parse file: ${fileUri}`, e);
      throw e;
    }
  }

  async getLibrary(): Promise<LibraryData> {
    const fileUri = vscode.Uri.joinPath(
      this.context.extensionUri,
      "media",
      "library.json",
    );
    const content = await this.readFileContent(fileUri);
    return JSON.parse(content);
  }

  async writePropertyFile(
    parameters: any,
    propertyFilename: string,
    propertyData: string,
  ): Promise<void> {
    const baseUri = parameters as Uri;
    const baseFolder = await this.getParentDirectoryUri(baseUri);
    const bytes = new TextEncoder().encode(propertyData);
    try {
      await this.writeFile(
        vscode.Uri.joinPath(baseFolder, RESOURCES_FOLDER, propertyFilename),
        bytes,
      );
      vscode.window.showInformationMessage("Property file has been updated!");
    } catch (err) {
      vscode.window.showErrorMessage("Failed to write file: " + err);
      throw Error("Failed to write file: " + err);
    }
  }

  async writeMainChain(parameters: any, chainData: ChainSchema): Promise<void> {
    const baseUri = parameters as Uri;
    const bytes = new TextEncoder().encode(yaml.stringify(chainData));
    const fileUri = await this.getMainChainFileUri(baseUri);
    try {
      await this.writeFile(fileUri, bytes);
      FileCacheService.getInstance().invalidateByUri(fileUri);
      vscode.window.showInformationMessage("Chain has been updated!");
    } catch (err) {
      vscode.window.showErrorMessage("Failed to write file: " + err);
      throw Error("Failed to write file: " + err);
    }
  }

  async removeFile(
    mainFolderUri: Uri,
    propertyFilename: string,
  ): Promise<void> {
    const baseFolder = await this.getParentDirectoryUri(mainFolderUri);
    const fileUri = vscode.Uri.joinPath(baseFolder, propertyFilename);
    try {
      await this.deleteFile(fileUri);
    } catch (error) {
      console.error("Error deleting property file", fileUri);
    }

    return;
  }

  // Service-related methods
  async getMainService(serviceFileUri: Uri): Promise<any> {
    try {
      const parsed = await ContentParser.parseContentFromFile(serviceFileUri);

      if (parsed && parsed.name) {
        return ServiceNormalizer.normalizeService(parsed);
      }
      throw Error("Invalid service file content");
    } catch (e) {
      console.error(
        `Service file ${serviceFileUri} can't be parsed from QIP Extension API`,
        e,
      );
      throw e;
    }
  }

  async getService(serviceFileUri: Uri, serviceId: string): Promise<any> {
    try {
      const parsed = await ContentParser.parseContentFromFile(serviceFileUri);

      if (parsed && parsed.id === serviceId) {
        return ServiceNormalizer.normalizeService(parsed);
      }
      throw Error("Invalid service file content or service ID mismatch");
    } catch (e) {
      console.error(
        `Service file ${serviceFileUri} can't be parsed from QIP Extension API`,
        e,
      );
      throw e;
    }
  }

  async getContextService(
    serviceFileUri: Uri,
    serviceId: string,
  ): Promise<any> {
    try {
      const parsed = await ContentParser.parseContentFromFile(serviceFileUri);

      if (parsed && parsed.id === serviceId) {
        // Context services run through the service migration list.
        repairMigrationsClaim(parsed.content, SERVICE_MIGRATIONS);
        return parsed;
      }
      throw Error("Invalid service file content or service ID mismatch");
    } catch (e) {
      console.error(
        `Service file ${serviceFileUri} can't be parsed from QIP Extension API`,
        e,
      );
      throw e;
    }
  }

  async getMcpService(serviceFileUri: Uri, serviceId: string): Promise<any> {
    try {
      const parsed = await ContentParser.parseContentFromFile(serviceFileUri);

      if (parsed && parsed.id === serviceId) {
        repairMigrationsClaim(parsed.content, MCP_SERVICE_MIGRATIONS);
        return parsed;
      }
      throw Error("Invalid service file content or service ID mismatch");
    } catch (e) {
      console.error(
        `Service file ${serviceFileUri} can't be parsed from QIP Extension API`,
        e,
      );
      throw e;
    }
  }

  async writeMainService(serviceFileUri: Uri, serviceData: any): Promise<void> {
    await this.writeServiceFile(serviceFileUri, serviceData);
    FileCacheService.getInstance().invalidateByUri(serviceFileUri);
  }

  async writeServiceFile(fileUri: Uri, serviceData: any): Promise<void> {
    const shaped = shapeServiceFile(serviceData, serviceFileKind(fileUri));
    const yamlString = yaml.stringify(shaped);
    const bytes = new TextEncoder().encode(yamlString);

    try {
      await this.writeFile(fileUri, bytes);
      FileCacheService.getInstance().invalidateByUri(fileUri);
      vscode.window.showInformationMessage("Service has been updated!");
    } catch (err) {
      console.error("writeServiceFile: Error writing file:", err);
      vscode.window.showErrorMessage("Failed to write file: " + err);
      throw Error("Failed to write file: " + err);
    }
  }

  async createServiceDirectory(
    parameters: any,
    serviceId: string,
  ): Promise<Uri> {
    const mainFolderUri = parameters as Uri;
    const serviceFolderUri = vscode.Uri.joinPath(mainFolderUri, serviceId);
    await createDirectory(serviceFolderUri);
    return serviceFolderUri;
  }

  // Directory operations
  async readDirectory(parameters: any): Promise<[string, number][]> {
    const mainFolderUri = parameters as Uri;
    return await readDirectory(mainFolderUri);
  }

  async createDirectory(parameters: any, dirName: string): Promise<void> {
    const mainFolderUri = parameters as Uri;
    const dirUri = vscode.Uri.joinPath(mainFolderUri, dirName);
    await createDirectory(dirUri);
  }

  async createDirectoryByUri(dirUri: Uri): Promise<void> {
    await createDirectory(dirUri);
  }

  // File operations
  async writeFile(fileUri: Uri, data: Uint8Array): Promise<void> {
    const parentDir = await this.getParentDirectoryUri(fileUri);
    await createDirectory(parentDir);
    await vscode.workspace.fs.writeFile(fileUri, data);
  }

  async readFileContent(fileUri: Uri): Promise<string> {
    const bytes = await vscode.workspace.fs.readFile(fileUri);
    return new TextDecoder("utf-8").decode(bytes);
  }

  async deleteFile(fileUri: Uri): Promise<void> {
    const fileStat = await vscode.workspace.fs.stat(fileUri);
    if (fileStat.type === vscode.FileType.Directory) {
      const entries = await vscode.workspace.fs.readDirectory(fileUri);
      if (entries.length === 0) {
        await vscode.workspace.fs.delete(fileUri);
      } else {
        throw new Error(`Directory ${fileUri.fsPath} is not empty`);
      }
    } else {
      await vscode.workspace.fs.delete(fileUri);
    }
    FileCacheService.getInstance().invalidateByUri(fileUri);
  }

  async createEmptyChain(
    createInParentDir: boolean = false,
  ): Promise<{ folderUri: Uri; chainId: string } | null> {
    try {
      const workspaceFolders = vscode.workspace.workspaceFolders;
      if (!workspaceFolders) {
        vscode.window.showErrorMessage("Open a workspace folder first");
        return null;
      }
      const arg = await vscode.window.showInputBox({
        prompt: "Enter new chain name",
      });

      let folderUri = workspaceFolders[0].uri;
      const chainId = crypto.randomUUID();
      const chainName = arg || "New Chain";
      if (createInParentDir) {
        folderUri = vscode.Uri.joinPath(folderUri, "..");
      }
      folderUri = vscode.Uri.joinPath(folderUri, chainId);

      await createDirectory(folderUri);

      const config = ProjectConfigService.getConfig();
      const chainFileUri = vscode.Uri.joinPath(
        folderUri,
        `${chainId}${config.extensions.chain}`,
      );
      const chain = {
        $schema: config.schemaUrls.chain,
        id: chainId,
        name: chainName,
        content: {
          migrations: CHAIN_MIGRATIONS,
        },
      };
      const bytes = new TextEncoder().encode(yaml.stringify(chain));

      await this.writeFile(chainFileUri, bytes);
      vscode.window.showInformationMessage(
        `Chain "${chainName}" created with id ${chainId}`,
      );
      return { folderUri, chainId };
    } catch (err) {
      vscode.window.showErrorMessage(`Failed: ${err}`);
      return null;
    }
  }

  async createEmptyService(): Promise<{
    folderUri: Uri;
    fileName: string;
    serviceId: string;
  } | null> {
    try {
      const workspaceFolders = vscode.workspace.workspaceFolders;
      if (!workspaceFolders) {
        vscode.window.showErrorMessage("Open a workspace folder first");
        return null;
      }

      const serviceName = await vscode.window.showInputBox({
        prompt: "Enter new service name",
        placeHolder: "My Service",
        validateInput: (value: string) => {
          if (!value || value.trim().length === 0) {
            return "Service name cannot be empty";
          }
          if (value.trim().length > 128) {
            return "Service name cannot be longer than 128 characters";
          }
          return null;
        },
      });

      if (!serviceName) {
        return null;
      }

      const serviceType = await vscode.window.showQuickPick(
        [
          {
            label: "External",
            value: "EXTERNAL",
            description: "External service",
          },
          {
            label: "Internal",
            value: "INTERNAL",
            description: "Internal service",
          },
          {
            label: "Implemented",
            value: "IMPLEMENTED",
            description: "Implemented service",
          },
          {
            label: "Context",
            value: "CONTEXT",
            description: "Context service",
          },
          {
            label: "MCP",
            value: "MCP",
            description: "MCP service",
          },
        ],
        {
          placeHolder: "Select service type",
          canPickMany: false,
        },
      );

      if (!serviceType) {
        return null;
      }

      const identifier =
        serviceType.value === "MCP"
          ? await vscode.window.showInputBox({
              prompt: "Enter MCP service identifier",
              placeHolder: "myService",
              validateInput: (value: string) => {
                if (!value || value.trim().length === 0) {
                  return "MCP service identifier cannot be empty";
                }
                if (value.trim().length > 128) {
                  return "MCP service identifier cannot be longer than 128 characters";
                }
                return null;
              },
            })
          : "";

      // Dismissing the prompt leaves the identifier empty, and the MCP schema
      // requires it — cancel instead of writing a file that fails validation.
      if (serviceType.value === "MCP" && !identifier?.trim()) {
        return null;
      }

      const serviceDescription = await vscode.window.showInputBox({
        prompt: "Enter service description (optional)",
        placeHolder: "Description of the service",
        validateInput: (value: string) => {
          if (value && value.trim().length > 512) {
            return "Description cannot be longer than 512 characters";
          }
          return null;
        },
      });

      // `crypto.randomUUID()` is dot-free, which the backend requires of an id it has to state in
      // a file name: it reads the id up to the first dot, so a dotted id names another service.
      const serviceId = crypto.randomUUID();

      const config = ProjectConfigService.getConfig();

      // Only what the prompts collected: a service created here has no
      // protocol, environments or labels yet, and writeServiceFile prunes an
      // empty description rather than writing a blank line into the file.
      const service = ((): object => {
        if (serviceType.value === "CONTEXT") {
          return {
            $schema: serviceSchemaUrlForType(
              serviceType.value,
              config.schemaUrls,
            ),
            id: serviceId,
            name: serviceName.trim(),
            content: {
              description: serviceDescription?.trim(),
              migrations: SERVICE_MIGRATIONS,
            },
          };
        }
        if (serviceType.value === "MCP") {
          return {
            $schema: serviceSchemaUrlForType(
              serviceType.value,
              config.schemaUrls,
            ),
            id: serviceId,
            name: serviceName.trim(),
            content: {
              identifier: identifier?.trim(),
              description: serviceDescription?.trim(),
              migrations: MCP_SERVICE_MIGRATIONS,
            },
          };
        }
        // The name states the type, so the content does not.
        return {
          $schema: serviceSchemaUrlForType(
            serviceType.value,
            config.schemaUrls,
          ),
          id: serviceId,
          name: serviceName.trim(),
          content: {
            description: serviceDescription?.trim(),
            migrations: SERVICE_MIGRATIONS,
          },
        };
      })();

      const extension = serviceExtensionForType(
        serviceType.value,
        config.extensions,
      );

      // Create service file (folder will be created automatically)
      const serviceFolderUri = vscode.Uri.joinPath(
        workspaceFolders[0].uri,
        serviceId,
      );
      const fileName = `${serviceId}${extension}`;
      const serviceFileUri = vscode.Uri.joinPath(serviceFolderUri, fileName);
      await this.writeServiceFile(serviceFileUri, service);

      vscode.window.showInformationMessage(
        `Service "${serviceName}" created successfully with type ${serviceType.label} in folder ${serviceId}`,
      );
      return { folderUri: serviceFolderUri, fileName, serviceId };
    } catch (err) {
      vscode.window.showErrorMessage(`Failed to create service: ${err}`);
      return null;
    }
  }

  async getFileType(fileUri: Uri): Promise<string> {
    try {
      const stat = await vscode.workspace.fs.stat(fileUri);
      const extensions: FileExtensionsConfig =
        this.getExtensionsForContext(fileUri);

      if (stat.type === vscode.FileType.File) {
        const name = extractFilename(fileUri);
        if (name.endsWith(extensions.mcpService)) {
          return QipFileType.MCP_SERVICE;
        }
        if (name.endsWith(extensions.contextService)) {
          return QipFileType.CONTEXT_SERVICE;
        }
        if (isAnyServiceFile(name, extensions)) {
          return QipFileType.SERVICE;
        }
        if (name.endsWith(extensions.chain)) {
          return QipFileType.CHAIN;
        }
        return QipFileType.UNKNOWN;
      }

      // Directory: infer by contents
      const entries = await this.readDirectoryInternal(fileUri);
      const hasChainFile = this.hasFileWithExtension(entries, extensions.chain);
      const hasServiceFile = plainServiceExtensions(extensions).some(
        (extension) => this.hasFileWithExtension(entries, extension),
      );

      if (hasServiceFile) {
        return QipFileType.SERVICE;
      }
      if (hasChainFile) {
        return QipFileType.CHAIN;
      }
      if (this.hasFileWithExtension(entries, extensions.mcpService)) {
        return QipFileType.MCP_SERVICE;
      }
      if (this.hasFileWithExtension(entries, extensions.contextService)) {
        return QipFileType.CONTEXT_SERVICE;
      }
      return QipFileType.FOLDER;
    } catch (e) {
      return QipFileType.UNKNOWN;
    }
  }

  async getFileCreatedWhen(fileUri: Uri): Promise<number> {
    const fileStat = await vscode.workspace.fs.stat(fileUri);
    return fileStat.ctime;
  }

  private hasFileWithExtension(entries: [string, number][], extension: string) {
    return entries.some(([name]: [string, number]) => name.endsWith(extension));
  }

  private async readDirectoryInternal(
    mainFolderUri: Uri,
  ): Promise<[string, number][]> {
    return await readDirectory(mainFolderUri);
  }

  async getSpecificationGroupFiles(serviceFileUri: Uri): Promise<string[]> {
    const extensions = this.getExtensionsForContext(serviceFileUri);
    const serviceFolderUri = await this.getParentDirectoryUri(serviceFileUri);
    // A project may store the group file as `.specification-group.<app>.yaml` (pre-rename)
    // or `.api-group.<app>.yaml`, both at the same depth. Scan for either.
    return await this.getFilesByExtensionsInDirectory(serviceFolderUri, [
      extensions.specificationGroup,
      extensions.apiGroup,
    ]);
  }

  async getSpecificationFiles(serviceFileUri: Uri): Promise<string[]> {
    const extensions = this.getExtensionsForContext(serviceFileUri);
    const serviceFolderUri = await this.getParentDirectoryUri(serviceFileUri);
    // A project may store the API file as `.specification.<app>.yaml` (pre-rename)
    // or `.api.<app>.yaml`, both at the same depth. Scan for either.
    return await this.getFilesByExtensionsInDirectory(serviceFolderUri, [
      extensions.specification,
      extensions.api,
    ]);
  }

  async getSpecApiFiles(): Promise<Uri[]> {
    return await this.findFiles(".api.yaml");
  }
}

export async function readDirectory(
  mainFolderUri: Uri,
): Promise<[string, number][]> {
  return await vscode.workspace.fs.readDirectory(mainFolderUri);
}

export async function createDirectory(dirUri: Uri): Promise<void> {
  return await vscode.workspace.fs.createDirectory(dirUri);
}
