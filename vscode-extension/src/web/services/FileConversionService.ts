import {SerializedFile} from '../api-services/importApiTypes';

/**
 * Service for converting between File objects and SerializedFile objects
 * Handles file conversion for import/export operations
 */
export class FileConversionService {
    /**
     * Converts a File object to SerializedFile
     */
    static async fileToSerializedFile(file: File): Promise<SerializedFile> {
        try {
            const arrayBuffer = await file.arrayBuffer();

            return {
                name: file.name,
                size: file.size,
                type: file.type,
                lastModified: file.lastModified,
                content: arrayBuffer
            };
        } catch (error) {
            throw new Error(`Failed to convert File to SerializedFile: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Converts a SerializedFile object to File
     */
    static serializedFileToFile(serializedFile: SerializedFile): File {
        try {
            // Validate that content is an ArrayBuffer
            if (!(serializedFile.content instanceof ArrayBuffer)) {
                throw new Error('Invalid content type: expected ArrayBuffer');
            }

            const blob = new Blob([serializedFile.content], {type: serializedFile.type});

            return new File([blob], serializedFile.name, {
                type: serializedFile.type,
                lastModified: serializedFile.lastModified
            });
        } catch (error) {
            throw new Error(`Failed to convert SerializedFile to File: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Converts multiple File objects to SerializedFile objects
     */
    static async filesToSerializedFiles(files: File[]): Promise<SerializedFile[]> {
        try {
            const promises = files.map(file => this.fileToSerializedFile(file));
            return await Promise.all(promises);
        } catch (error) {
            throw new Error(`Failed to convert Files to SerializedFiles: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Converts multiple SerializedFile objects to File objects
     */
    static serializedFilesToFiles(serializedFiles: SerializedFile[]): File[] {
        try {
            return serializedFiles.map(serializedFile => this.serializedFileToFile(serializedFile));
        } catch (error) {
            throw new Error(`Failed to convert SerializedFiles to Files: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Validates if a SerializedFile has valid structure
     */
    static validateSerializedFile(serializedFile: any): serializedFile is SerializedFile {
        return (
            serializedFile &&
            typeof serializedFile.name === 'string' &&
            typeof serializedFile.size === 'number' &&
            typeof serializedFile.type === 'string' &&
            typeof serializedFile.lastModified === 'number' &&
            serializedFile.content instanceof ArrayBuffer
        );
    }

    /**
     * Validates if a File has valid structure
     */
    static validateFile(file: any): file is File {
        return (
            file &&
            typeof file.name === 'string' &&
            typeof file.size === 'number' &&
            typeof file.type === 'string' &&
            typeof file.lastModified === 'number' &&
            typeof file.arrayBuffer === 'function'
        );
    }

    /**
     * Gets file extension from filename
     */
    static getFileExtension(fileName: string): string {
        const lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex === -1 || lastDotIndex === 0) {
            return '';
        }
        return fileName.substring(lastDotIndex);
    }

    /**
     * Gets filename without extension
     */
    static getFileNameWithoutExtension(fileName: string): string {
        const lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex === -1 || lastDotIndex === 0) {
            return fileName;
        }
        return fileName.substring(0, lastDotIndex);
    }

    /**
     * Checks if file is a text file based on type
     */
    static isTextFile(file: File | SerializedFile): boolean {
        const type = file.type.toLowerCase();
        return (
            type.startsWith('text/') ||
            type === 'application/json' ||
            type === 'application/xml' ||
            type === 'application/yaml' ||
            type === 'application/x-yaml' ||
            type === 'text/yaml' ||
            type === 'text/xml' ||
            type === 'text/plain'
        );
    }

    /**
     * Checks if file is a specification file based on extension
     */
    static isSpecificationFile(file: File | SerializedFile): boolean {
        const extension = this.getFileExtension(file.name).toLowerCase();
        return [
            '.yaml', '.yml', '.json', '.xml', '.wsdl', '.xsd', '.proto', '.graphql', '.gql'
        ].includes(extension);
    }

    /**
     * Gets file size in human readable format
     */
    static getFileSizeString(bytes: number): string {
        if (bytes === 0) {return '0 Bytes';}

        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));

        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }
}
