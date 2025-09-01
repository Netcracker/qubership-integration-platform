import {Uri} from "vscode";
import {LibraryData} from "../apiTypes";


export interface FileApi {

    getMainChain(parameters: any): Promise<any>;

    readFile(parameters: any, propertyFilename: string): Promise<string>;

    getLibrary(): Promise<LibraryData>;

    writePropertyFile(parameters: any, propertyFilename: string, propertyData: string): Promise<void>;

    writeMainChain(parameters: any, chainData: any): Promise<void>;

    removeFile(mainFolderUri: Uri, propertyFilename: string): Promise<void>;
}
