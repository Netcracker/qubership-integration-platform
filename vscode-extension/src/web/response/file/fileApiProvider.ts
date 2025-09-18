import {LibraryData} from '../apiTypes';
import {FileApi} from './fileApi';
import {Uri} from 'vscode';

let current: FileApi = {
    getMainChain: async () => {
        throw new Error('FileApi not configured');
    },
    readFile: async () => {
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
    findSpecificationGroupFiles: async () => {
        throw new Error('FileApi not configured');
    },
    findSpecificationFiles: async () => {
        throw new Error('FileApi not configured');
    },
};

export function setFileApi(api: FileApi) {
    current = api;
}

// Delegating facade so existing imports can keep using `fileApi`
export const fileApi: FileApi = {
    getMainChain: async (parameters: any): Promise<any> => current.getMainChain(parameters),
    readFile: async (parameters: any, propertyFilename: string): Promise<string> => current.readFile(parameters, propertyFilename),
    getLibrary: async (): Promise<LibraryData> => current.getLibrary(),
    writePropertyFile: async (parameters: any, propertyFilename: string, propertyData: string): Promise<void> => current.writePropertyFile(parameters, propertyFilename, propertyData),
    writeMainChain: async (parameters: any, chainData: any): Promise<void> => current.writeMainChain(parameters, chainData),
    removeFile: async (mainFolderUri, propertyFilename: string): Promise<void> => current.removeFile(mainFolderUri, propertyFilename),
    // Service-related methods
    getMainService: async (parameters: any): Promise<any> => current.getMainService(parameters),
    getService: async (parameters: any, serviceId: string): Promise<any> => current.getService(parameters, serviceId),
    writeMainService: async (parameters: any, serviceData: any): Promise<void> => current.writeMainService(parameters, serviceData),
    writeServiceFile: async (fileUri: Uri, serviceData: any): Promise<void> => current.writeServiceFile(fileUri, serviceData),
    findSpecificationGroupFiles: async (mainFolderUri: Uri): Promise<string[]> => current.findSpecificationGroupFiles(mainFolderUri),
    findSpecificationFiles: async (mainFolderUri: Uri): Promise<string[]> => current.findSpecificationFiles(mainFolderUri),
    // File operations
    writeFile: async (fileUri: Uri, data: Uint8Array): Promise<void> => current.writeFile(fileUri, data),
    readFileContent: async (fileUri: Uri): Promise<Uint8Array> => current.readFileContent(fileUri),
    deleteFile: async (fileUri: Uri): Promise<void> => current.deleteFile(fileUri),
    getFileType: async (mainFolderUri: Uri): Promise<string> => current.getFileType(mainFolderUri),
};
