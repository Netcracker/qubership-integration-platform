import {LibraryData} from '../apiTypes';
import {FileApi} from './fileApi';

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
    }
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
};
