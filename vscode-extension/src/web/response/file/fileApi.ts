import {Uri} from "vscode";
import {Chain, LibraryData} from "@netcracker/qip-ui";

export interface FileApi {
    getRootDirectory(): Uri;

    getMainChain(parameters: any): Promise<any>;

    findChainRecursively(folderUri: Uri, chainId: string): Promise<any>;

    findAndBuildChainsRecursively(folderUri: Uri, chainBuilder: (chainContent: any) => Partial<Chain> | undefined, result: Partial<Chain>[]): Promise<void>;

    readFile(parameters: any, propertyFilename: string): Promise<string>;

    parseFile(fileUri: Uri): Promise<any>;

    getLibrary(): Promise<LibraryData>;

    writePropertyFile(parameters: any, propertyFilename: string, propertyData: string): Promise<void>;

    writeMainChain(parameters: any, chainData: any): Promise<void>;

    removeFile(mainFolderUri: Uri, propertyFilename: string): Promise<void>;

    // Service-related methods
    getMainService(serviceFileUri: Uri): Promise<any>;

    getService(serviceFileUri: Uri, serviceId: string): Promise<any>;

    writeMainService(serviceFileUri: Uri, serviceData: any): Promise<void>;

    writeServiceFile(fileUri: Uri, serviceData: any): Promise<void>;

    getSpecificationGroupFiles(serviceFileUri: Uri): Promise<string[]>;

    getSpecificationFiles(serviceFileUri: Uri): Promise<string[]>;

    getResourcesPath(serviceFileUri: Uri): Promise<Uri>;

    getWorkspaceRoot(serviceFileUri: Uri): Promise<Uri>;

    getServiceIdFromFileUri(serviceFileUri: Uri): Promise<string>;


    // File operations
    writeFile(fileUri: Uri, data: Uint8Array): Promise<void>;

    readFileContent(fileUri: Uri): Promise<Uint8Array>;

    deleteFile(fileUri: Uri): Promise<void>;

    getFileType(fileUri: Uri): Promise<string>;
}
