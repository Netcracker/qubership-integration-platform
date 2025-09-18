import * as yaml from 'js-yaml';

export interface ParsedFileContent {
    content: any;
    format: 'json' | 'yaml' | 'unknown';
}

export class FileParserService {
    private static readonly JSON_EXTENSIONS = ['.json'];
    private static readonly YAML_EXTENSIONS = ['.yaml', '.yml'];
    
    /**
     * Parses file content based on file extension and content analysis
     */
    static async parseFileContent(file: File): Promise<ParsedFileContent> {
        try {
            const content = await file.text();
            const fileName = file.name.toLowerCase();
            
            if (this.isJsonFile(fileName) || this.isJsonContent(content)) {
                return {
                    content: JSON.parse(content),
                    format: 'json'
                };
            } else if (this.isYamlFile(fileName) || this.isYamlContent(content)) {
                return {
                    content: yaml.load(content),
                    format: 'yaml'
                };
            }
            
            throw new Error(`Unsupported file format: ${fileName}`);
        } catch (error) {
            throw new Error(`Failed to parse file ${file.name}: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }
    
    /**
     * Checks if file is JSON based on extension
     */
    private static isJsonFile(fileName: string): boolean {
        return this.JSON_EXTENSIONS.some(ext => fileName.endsWith(ext));
    }
    
    /**
     * Checks if file is YAML based on extension
     */
    private static isYamlFile(fileName: string): boolean {
        return this.YAML_EXTENSIONS.some(ext => fileName.endsWith(ext));
    }
    
    /**
     * Checks if content is JSON format
     */
    private static isJsonContent(content: string): boolean {
        try {
            const trimmed = content.trim();
            return (trimmed.startsWith('{') && trimmed.endsWith('}')) || 
                   (trimmed.startsWith('[') && trimmed.endsWith(']'));
        } catch {
            return false;
        }
    }
    
    /**
     * Checks if content is YAML format
     */
    private static isYamlContent(content: string): boolean {
        try {
            const trimmed = content.trim();
            return trimmed.includes(':') && 
                   (trimmed.includes('openapi:') || 
                    trimmed.includes('swagger:') || 
                    trimmed.includes('asyncapi:') ||
                    trimmed.includes('info:') ||
                    trimmed.includes('paths:'));
        } catch {
            return false;
        }
    }
    
    /**
     * Checks if file has text() method available
     */
    static hasTextMethod(file: File): boolean {
        return typeof file.text === 'function';
    }
    
    /**
     * Safely reads file text with fallback for mock objects
     */
    static async readFileText(file: File): Promise<string> {
        if (this.hasTextMethod(file)) {
            return await file.text();
        }
        throw new Error('File.text() method not available');
    }
}
