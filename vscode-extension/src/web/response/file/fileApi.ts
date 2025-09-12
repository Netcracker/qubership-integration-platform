import {Uri} from "vscode";
import {LibraryData} from "../apiTypes";

export interface FileApi {

    getMainChain(parameters: any): Promise<any>;

    readFile(parameters: any, propertyFilename: string): Promise<string>;

    getLibrary(): Promise<LibraryData>;

    writePropertyFile(parameters: any, propertyFilename: string, propertyData: string): Promise<void>;

    writeMainChain(parameters: any, chainData: any): Promise<void>;

    removeFile(mainFolderUri: Uri, propertyFilename: string): Promise<void>;


    // Service-related methods
    getMainService(parameters: any): Promise<any>;

    getService(parameters: any, serviceId: string): Promise<any>;

    writeMainService(parameters: any, serviceData: any): Promise<void>;

    writeServiceFile(fileUri: Uri, serviceData: any): Promise<void>;

    createServiceDirectory(parameters: any, serviceId: string): Promise<Uri>;

    deleteServiceDirectory(parameters: any, serviceId: string): Promise<void>;

    // Directory operations
    readDirectory(parameters: any): Promise<[string, number][]>;

    createDirectory(parameters: any, dirName: string): Promise<void>;

    createDirectoryByUri(dirUri: Uri): Promise<void>;

    deleteDirectory(parameters: any, dirName: string): Promise<void>;

    // File operations
    writeFile(fileUri: Uri, data: Uint8Array): Promise<void>;

    readFileContent(fileUri: Uri): Promise<Uint8Array>;

    deleteFile(fileUri: Uri): Promise<void>;

    getFileStat(fileUri: Uri): Promise<any>;
}
