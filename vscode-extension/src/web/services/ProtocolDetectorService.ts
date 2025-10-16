import * as path from 'path';
import { ApiSpecificationType } from "../api-services/importApiTypes";
import { FileParserService } from "./FileParserService";

export class ProtocolDetectorService {
    private static readonly ARCHIVE_EXTENSIONS = ['.zip', '.tar', '.gz', '.rar', '.7z'];
    private static readonly GRAPHQL_KEYWORDS = [
        'type ', 'interface ', 'union ', 'enum ', 'input ',
        'scalar ', 'directive ', 'extend ', 'schema ',
        'query', 'mutation', 'subscription'
    ];

    /**
     * Detects operation protocol from files
     */
    static async getOperationProtocol(files: File[]): Promise<ApiSpecificationType> {
        if (!files || files.length === 0) {
            return ApiSpecificationType.HTTP;
        }

        const extractedFiles = await this.extractArchives(files);

        for (const file of extractedFiles) {
            const protocol = await this.detectProtocolFromFile(file);
            if (protocol) {
                return protocol;
            }
        }

        return ApiSpecificationType.HTTP;
    }

    /**
     * Detects protocol from single file
     */
    static async detectProtocolFromFile(file: File): Promise<ApiSpecificationType | null> {
        if (!file || !file.name) {
            return null;
        }

        const fileName = file.name.toLowerCase();
        const fileExtension = path.extname(fileName).toLowerCase();

        // Detection by file extension
        const extensionProtocol = this.detectProtocolByExtension(fileName, fileExtension);
        if (extensionProtocol) {
            return extensionProtocol;
        }

        // For JSON and YAML files, analyze content
        if (['.json', '.yaml', '.yml'].includes(fileExtension)) {
            return await this.analyzeFileContent(file);
        }

        return null;
    }

    /**
     * Detects protocol by file extension
     */
    private static detectProtocolByExtension(fileName: string, extension: string): ApiSpecificationType | null {
        if (extension === '.wsdl' || fileName.includes('wsdl')) {
            return ApiSpecificationType.SOAP;
        } else if (extension === '.xml') {
            return ApiSpecificationType.SOAP;
        } else if (extension === '.graphql' || extension === '.graphqls') {
            return ApiSpecificationType.GRAPHQL;
        } else if (extension === '.proto') {
            return ApiSpecificationType.GRPC;
        }

        return null;
    }

    /**
     * Analyzes file content to determine protocol
     */
    private static async analyzeFileContent(file: File): Promise<ApiSpecificationType | null> {
        try {
            if (!FileParserService.hasTextMethod(file)) {
                return this.detectProtocolFromFileName(file.name);
            }

            const { content } = await FileParserService.parseFileContent(file);

            // Check for OpenAPI/Swagger
            if (this.isOpenApiSpec(content)) {
                return ApiSpecificationType.HTTP;
            }

            // Check for AsyncAPI
            if (this.isAsyncApiSpec(content)) {
                return this.determineAsyncProtocol(content);
            }

            // Check for GraphQL (for JSON files)
            if (file.name.toLowerCase().endsWith('.json')) {
                const textContent = await FileParserService.readFileText(file);
                if (this.isGraphQLSpec(textContent)) {
                    return ApiSpecificationType.GRAPHQL;
                }
            }

        } catch (error) {
            return this.detectProtocolFromFileName(file.name);
        }

        return null;
    }

    /**
     * Fallback protocol detection from filename
     */
    private static detectProtocolFromFileName(fileName: string): ApiSpecificationType | null {
        const lowerFileName = fileName.toLowerCase();

        if (lowerFileName.includes('asyncapi')) {
            return ApiSpecificationType.ASYNC;
        } else if (lowerFileName.includes('openapi') || lowerFileName.includes('swagger')) {
            return ApiSpecificationType.HTTP;
        } else if (lowerFileName.includes('graphql')) {
            return ApiSpecificationType.GRAPHQL;
        }

        return null;
    }

    /**
     * Checks if content is OpenAPI/Swagger specification
     */
    private static isOpenApiSpec(content: any): boolean {
        if (!content || typeof content !== 'object') {
            return false;
        }

        // Check for Swagger 2.0
        if (content.swagger && typeof content.swagger === 'string') {
            const version = content.swagger;
            if (version.startsWith('2.') || version === '2.0') {
                return true;
            }
        }

        // Check for OpenAPI 3.x
        if (content.openapi && typeof content.openapi === 'string') {
            const version = content.openapi;
            if (version.startsWith('3.')) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if content is AsyncAPI specification
     */
    private static isAsyncApiSpec(content: any): boolean {
        if (!content || typeof content !== 'object') {
            return false;
        }

        return content.asyncapi && typeof content.asyncapi === 'string';
    }

    /**
     * Determines specific AsyncAPI protocol
     */
    private static determineAsyncProtocol(content: any): ApiSpecificationType {
        const map = (proto: string | undefined): ApiSpecificationType | null => {
            if (!proto) {
                return null;
            }
            const p = String(proto).toLowerCase();
            const protocolMap: Record<string, ApiSpecificationType> = {
                'kafka': ApiSpecificationType.KAFKA,
                'amqp': ApiSpecificationType.AMQP,
                'mqtt': ApiSpecificationType.MQTT,
                'redis': ApiSpecificationType.REDIS,
                'nats': ApiSpecificationType.NATS
            };
            return protocolMap[p] ?? null;
        };

        // 1) info.x-protocol
        const fromX = map(content?.info?.['x-protocol']);
        if (fromX) {
            return fromX;
        }

        // 2) servers.main.protocol
        const fromMain = map(content?.servers?.main?.protocol);
        if (fromMain) {
            return fromMain;
        }

        // 3) first servers[*].protocol
        if (content?.servers && typeof content.servers === 'object') {
            for (const server of Object.values(content.servers)) {
                const candidate = map((server as any)?.protocol);
                if (candidate) {
                    return candidate;
                }
            }
        }

        return ApiSpecificationType.ASYNC;
    }

    /**
     * Checks if content is GraphQL specification
     */
    private static isGraphQLSpec(content: string): boolean {
        const lowerContent = content.toLowerCase();
        let keywordCount = 0;

        for (const keyword of this.GRAPHQL_KEYWORDS) {
            if (lowerContent.includes(keyword)) {
                keywordCount++;
            }
        }

        return keywordCount >= 2;
    }

    /**
     * Checks if file is archive
     */
    static isArchiveFile(fileName: string): boolean {
        const extension = path.extname(fileName).toLowerCase();
        return this.ARCHIVE_EXTENSIONS.includes(extension);
    }

    /**
     * Extracts archives (in browser environment, returns files as-is)
     */
    static async extractArchives(files: File[]): Promise<File[]> {
        if (!files || files.length === 0) {
            return [];
        }

        const processedFiles: File[] = [];

        for (const file of files) {
            if (!file) {
                continue;
            }

            if (this.isArchiveFile(file.name)) {
                // In browser environment, archives are not extracted
                processedFiles.push(file);
            } else {
                processedFiles.push(file);
            }
        }

        return processedFiles;
    }

    /**
     * Gets supported extensions for protocol
     */
    static getSupportedExtensions(protocol: ApiSpecificationType): string[] {
        const extensionMap: Record<ApiSpecificationType, string[]> = {
            [ApiSpecificationType.HTTP]: ['.json', '.yaml', '.yml'],
            [ApiSpecificationType.SOAP]: ['.wsdl', '.xsd'],
            [ApiSpecificationType.GRAPHQL]: ['.graphql', '.graphqls', '.json'],
            [ApiSpecificationType.GRPC]: ['.proto'],
            [ApiSpecificationType.ASYNC]: ['.json', '.yaml', '.yml'],
            [ApiSpecificationType.KAFKA]: ['.json', '.yaml', '.yml'],
            [ApiSpecificationType.AMQP]: ['.json', '.yaml', '.yml'],
            [ApiSpecificationType.MQTT]: ['.json', '.yaml', '.yml'],
            [ApiSpecificationType.REDIS]: ['.json', '.yaml', '.yml'],
            [ApiSpecificationType.NATS]: ['.json', '.yaml', '.yml']
        };

        return extensionMap[protocol] || ['.json', '.yaml', '.yml'];
    }
}
