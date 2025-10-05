import {Chain, LibraryData} from "@netcracker/qip-ui";
import {FileApi} from './fileApi';
import {Uri} from 'vscode';
import {Chain as ChainSchema} from "@netcracker/qip-schemas";

let current: FileApi = {
    getRootDirectory: () => {
        throw new Error('FileApi not configured');
    },
    getMainChain: async () => {
        throw new Error('FileApi not configured');
    },
    findChainRecursively: async () => {
        throw new Error('FileApi not configured');
    },
    findAndBuildChainsRecursively: async () => {
        throw new Error('FileApi not configured');
    },
    readFile: async () => {
        throw new Error('FileApi not configured');
    },
    parseFile: async () => {
        throw new Error('FileApi not configured');
    },
    getLibrary: function (): Promise<LibraryData> {
        throw new Error('Function not implemented.');
    },
    writePropertyFile: async () => {
        throw new Error('FileApi not configured');
    },
    writeMainChain: async () => {
        throw new Error('FileApi not configured');
    },
    removeFile: async () => {
        throw new Error('FileApi not configured');
    },
    // Service-related methods
    getMainService: async () => {
        throw new Error('FileApi not configured');
    },
    getService: async () => {
        throw new Error('FileApi not configured');
    },
    writeMainService: async () => {
        throw new Error('FileApi not configured');
    },
    writeServiceFile: async () => {
        throw new Error('FileApi not configured');
    },
    // File operations
    writeFile: async () => {
        throw new Error('FileApi not configured');
    },
    readFileContent: async () => {
        throw new Error('FileApi not configured');
    },
    deleteFile: async () => {
        throw new Error('FileApi not configured');
    },
    getFileType: async () => {
        throw new Error('FileApi not configured');
    },
    getSpecificationGroupFiles: async () => {
        throw new Error('FileApi not configured');
    },
    getSpecificationFiles: async () => {
        throw new Error('FileApi not configured');
    },
};

export function setFileApi(api: FileApi) {
    current = api;
}

// Delegating facade so existing imports can keep using `fileApi`
export const fileApi: FileApi = {
    getRootDirectory: () => current.getRootDirectory(),
    getMainChain: async (parameters: any): Promise<ChainSchema> => current.getMainChain(parameters),
    findChainRecursively: async (folderUri: Uri, chainId: string): Promise<any> => current.findChainRecursively(folderUri, chainId),
    findAndBuildChainsRecursively:  async (folderUri: Uri, chainBuilder: (chainContent: any) => Partial<Chain> | undefined, result: Partial<Chain>[]): Promise<void> => current.findAndBuildChainsRecursively(folderUri, chainBuilder, result),
    readFile: async (parameters: any, propertyFilename: string): Promise<string> => current.readFile(parameters, propertyFilename),
    parseFile: async (fileUri: Uri): Promise<any> => current.parseFile(fileUri),
    getLibrary: async (): Promise<LibraryData> => current.getLibrary(),
    writePropertyFile: async (parameters: any, propertyFilename: string, propertyData: string): Promise<void> => current.writePropertyFile(parameters, propertyFilename, propertyData),
    writeMainChain: async (parameters: any, chainData: any): Promise<void> => current.writeMainChain(parameters, chainData),
    removeFile: async (mainFolderUri, propertyFilename: string): Promise<void> => current.removeFile(mainFolderUri, propertyFilename),
    // Service-related methods
    getMainService: async (serviceFileUri: Uri): Promise<any> => current.getMainService(serviceFileUri),
    getService: async (serviceFileUri: Uri, serviceId: string): Promise<any> => current.getService(serviceFileUri, serviceId),
    writeMainService: async (serviceFileUri: Uri, serviceData: any): Promise<void> => current.writeMainService(serviceFileUri, serviceData),
    writeServiceFile: async (fileUri: Uri, serviceData: any): Promise<void> => current.writeServiceFile(fileUri, serviceData),
    getSpecificationGroupFiles: async (serviceFileUri: Uri): Promise<string[]> => current.getSpecificationGroupFiles(serviceFileUri),
    getSpecificationFiles: async (serviceFileUri: Uri): Promise<string[]> => current.getSpecificationFiles(serviceFileUri),
    // File operations
    writeFile: async (fileUri: Uri, data: Uint8Array): Promise<void> => current.writeFile(fileUri, data),
    readFileContent: async (fileUri: Uri): Promise<string> => current.readFileContent(fileUri),
    deleteFile: async (fileUri: Uri): Promise<void> => current.deleteFile(fileUri),
    getFileType: async (fileUri: Uri): Promise<string> => current.getFileType(fileUri),
};
