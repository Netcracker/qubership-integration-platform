import { Uri } from "vscode";
import { Chain, LibraryData } from "@netcracker/qip-ui";
import { Chain as ChainSchema } from "@netcracker/qip-schemas";

export interface FileApi {
  getRootDirectory(): Uri;

  getMainChain(parameters: any): Promise<ChainSchema>;

  findFileByNavigationPath(navigatePath: string): Promise<Uri>;

  findFileById(id: string, extension?: string): Promise<Uri>;

  findFile(
    extension: string,
    filterPredicate?: (fileContent: any) => boolean,
  ): Promise<Uri>;

  findFiles(
    extension: string,
    filterPredicate?: (fileContent: any) => boolean,
  ): Promise<Uri[]>;

  findAndBuildChainsRecursively<T>(
    folderUri: Uri,
    chainBuilder: (chainContent: any) => T | undefined,
    result: T[],
  ): Promise<void>;

  readFile(parameters: any, propertyFilename: string): Promise<string>;

  parseFile(fileUri: Uri): Promise<any>;

  getLibrary(): Promise<LibraryData>;

  writePropertyFile(
    parameters: any,
    propertyFilename: string,
    propertyData: string,
  ): Promise<void>;

  writeMainChain(parameters: any, chainData: any): Promise<void>;

  removeFile(mainFolderUri: Uri, propertyFilename: string): Promise<void>;

  // Service-related methods
  getMainService(serviceFileUri: Uri): Promise<any>;

  getService(serviceFileUri: Uri, serviceId: string): Promise<any>;

  getContextService(serviceFileUri: Uri, serviceId: string): Promise<any>;

  writeMainService(serviceFileUri: Uri, serviceData: any): Promise<void>;

  writeServiceFile(fileUri: Uri, serviceData: any): Promise<void>;

  getSpecificationGroupFiles(serviceFileUri: Uri): Promise<string[]>;

  getSpecificationFiles(serviceFileUri: Uri): Promise<string[]>;

  // File operations
  writeFile(fileUri: Uri, data: Uint8Array): Promise<void>;

  readFileContent(fileUri: Uri): Promise<string>;

  deleteFile(fileUri: Uri): Promise<void>;

  getFileType(fileUri: Uri): Promise<string>;

  getFileCreatedWhen(fileUri: Uri): Promise<number>;

  getSpecApiFiles(): Promise<Uri[]>;
}
