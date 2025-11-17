import { SpecificationGroup, Specification } from "./servicesTypes";
import { ApiSpecificationType } from "./importApiTypes";
import {
    SoapSpecificationParser,
    ProtoSpecificationParser,
    GraphQLSpecificationParser,
    OpenApiSpecificationParser,
    AsyncApiSpecificationParser
} from "./parsers";
import { ContentParser } from './parsers/ContentParser';
import { normalizePath } from "./pathUtils";
import * as yaml from "yaml";

export interface EnvironmentCandidate {
    name?: string;
    address: string;
    protocol?: string;
}

/**
 * Service for processing specification files
 */
export class SpecificationProcessorService {

    /**
     * Process specification files
     */
    async processSpecificationFiles(
        specificationGroup: SpecificationGroup,
        files: File[],
        systemId?: string,
        contentCache?: Map<string, Promise<string>>
    ): Promise<void> {
        const cache = contentCache ?? new Map<string, Promise<string>>();
        for (const file of files) {
            try {
                await this.processSpecificationFile(file, specificationGroup, systemId, files, cache);
            } catch (error) {
                throw error;
            }
        }

    }

    /**
     * Process single specification file
     */
    private async processSpecificationFile(
        file: File,
        specificationGroup: SpecificationGroup,
        systemId?: string,
        allFiles?: File[],
        contentCache?: Map<string, Promise<string>>
    ): Promise<void> {
        const fileExtension = this.getFileExtension(file.name);
        const specificationType = await this.detectSpecificationType(file, fileExtension, contentCache);

        if (!specificationType) {
            if (this.isWsdlDependencyExtension(fileExtension)) {
                console.log('[SpecificationProcessorService] Skipping WSDL dependency file', file.name);
                return;
            }
            throw new Error(`Unsupported specification format: ${file.name}`);
        }


        const version = await this.extractVersionFromFile(file);
        const specificationId = this.buildSpecificationId(systemId, specificationGroup.name, version);
        const operations = await this.createOperationsFromFile(file, specificationType, specificationId, allFiles, contentCache);
        if (specificationType === ApiSpecificationType.GRPC && operations.length === 0) {
            throw new Error(`No gRPC operations detected in ${file.name}. Ensure the proto file declares at least one RPC method.`);
        }
        console.log('[SpecificationProcessorService] Generated', operations.length, 'operations for', file.name);
        const specification = this.createSpecificationEntity(file, specificationGroup.id, specificationId, version, specificationType, operations);

        specificationGroup.specifications.push(specification);
    }

    /**
     * Detect specification type from file
     */
    private async detectSpecificationType(
        file: File,
        fileExtension: string,
        contentCache?: Map<string, Promise<string>>
    ): Promise<ApiSpecificationType | null> {
        if (this.isWsdlDependencyExtension(fileExtension)) {
            return null;
        }

        const typeByExtension = this.detectTypeByExtension(fileExtension);
        if (typeByExtension) {
            return typeByExtension;
        }

        const content = await this.getFileContentWithCache(file, contentCache);
        if (!content) {
            return null;
        }

        if (this.isLikelyWsdlContent(fileExtension, content)) {
            return ApiSpecificationType.SOAP;
        }

        const parsedContent = this.tryParseStructuredContent(content);
        if (!parsedContent) {
            return null;
        }

        if (parsedContent.asyncapi) {
            return ApiSpecificationType.ASYNC;
        }

        if (parsedContent.openapi || parsedContent.swagger) {
            return ApiSpecificationType.HTTP;
        }

        return null;
    }

    /**
     * Get file extension
     */
    private getFileExtension(fileName: string): string {
        const lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex !== -1 ? fileName.substring(lastDotIndex) : '';
    }

    private buildSpecificationId(systemId: string | undefined, groupName: string, version: string): string {
        return systemId ? `${systemId}-${groupName}-${version}` : crypto.randomUUID();
    }

    private createSpecificationEntity(
        file: File,
        groupId: string,
        specificationId: string,
        version: string,
        specificationType: ApiSpecificationType,
        operations: Specification['operations']
    ): Specification {
        return {
            id: specificationId,
            name: version,
            description: `Specification for ${file.name}`,
            parentId: groupId,
            version,
            format: specificationType.toString(),
            content: '',
            deprecated: false,
            source: file.name,
            operations: operations || []
        };
    }

    /**
     * Detect protocol from specification data
     */
    detectProtocolFromSpecification(specData: any): string | null {
        if (!specData) {
            return null;
        }

        if (specData.type === 'WSDL') {
            return 'soap';
        }

        if (specData.swagger || specData.openapi) {
            return 'http';
        }

        if (specData.asyncapi) {
            const protocol = specData.info?.['x-protocol']?.toLowerCase() ||
                           specData.servers?.main?.protocol?.toLowerCase() ||
                           (specData.servers && Object.keys(specData.servers).length > 0
                               ? (Object.values(specData.servers)[0] as any)?.protocol?.toLowerCase()
                               : null);
            return protocol || null;
        }

        return null;
    }

    /**
     * Extract address from specification data
     */
    extractEnvironmentCandidates(specData: any): EnvironmentCandidate[] {
        if (!specData) {
            return [];
        }

        const candidates: EnvironmentCandidate[] = [];

        if (specData.type === 'WSDL' && Array.isArray(specData.endpoints)) {
            specData.endpoints.forEach((endpoint: any) => {
                const normalizedAddress = this.normalizeEnvironmentAddress(endpoint?.address);
                if (normalizedAddress) {
                    candidates.push({
                        name: endpoint?.endpointName || endpoint?.serviceName,
                        address: normalizedAddress,
                        protocol: 'SOAP'
                    });
                }
            });
        }

        if (specData.openapi || specData.swagger) {
            const openApiCandidates = this.extractOpenApiServers(specData);
            candidates.push(...openApiCandidates);
        }

        if (specData.asyncapi) {
            const initialCandidateCount = candidates.length;
            if (specData.servers && typeof specData.servers === 'object') {
                Object.entries(specData.servers).forEach(([key, value]: [string, any]) => {
                    const normalizedAddress = this.normalizeEnvironmentAddress(value?.url);
                    if (normalizedAddress) {
                        candidates.push({
                            name: key,
                            address: normalizedAddress,
                            protocol: (value?.protocol || specData.info?.['x-protocol'] || '').toUpperCase() || undefined
                        });
                    }
                });
            }

            if (candidates.length === initialCandidateCount) {
                const fallbackAddress = AsyncApiSpecificationParser.extractAddressFromAsyncApiData(specData);
                const normalizedAddress = this.normalizeEnvironmentAddress(fallbackAddress || undefined);
                if (normalizedAddress) {
                    candidates.push({
                        address: normalizedAddress,
                        protocol: this.resolveProtocolName(specData.info?.['x-protocol'])
                    });
                }
            }
        }

        return this.deduplicateEnvironmentCandidates(candidates);
    }

    /**
     * Read file content
     */
    private async readFileContent(file: File): Promise<string | null> {
        try {
            return await file.text();
        } catch (error) {
            return null;
        }
    }

    private async getFileContentWithCache(file: File, cache?: Map<string, Promise<string>>): Promise<string | null> {
        if (!cache) {
            return this.readFileContent(file);
        }

        const key = normalizePath(file.name);
        if (!cache.has(key)) {
            cache.set(key, file.text());
        }

        try {
            return await cache.get(key)!;
        } catch {
            return null;
        }
    }

    private async buildWsdlAdditionalDocuments(
        mainFile: File,
        allFiles?: File[],
        cache?: Map<string, Promise<string>>
    ): Promise<Array<{ uri: string; content: string }>> {
        if (!allFiles || allFiles.length === 0) {
            return [];
        }

        const mainPath = normalizePath(mainFile.name);
        const documents: Array<{ uri: string; content: string }> = [];

        for (const candidate of allFiles) {
            const candidatePath = normalizePath(candidate.name);
            if (candidatePath === mainPath) {
                continue;
            }

            const extension = this.getFileExtension(candidate.name).toLowerCase();
            if (!['.wsdl', '.xsd'].includes(extension)) {
                continue;
            }

            const content = await this.getFileContentWithCache(candidate, cache);
            if (content !== null) {
                documents.push({
                    uri: candidatePath,
                    content
                });
            }
        }

        if (documents.length === 0) {
            console.log('[SpecificationProcessorService] No WSDL dependencies detected for', mainPath);
        } else {
            console.log('[SpecificationProcessorService] Resolved WSDL dependencies for', mainPath, documents.map(({ uri }) => uri));
        }

        return documents;
    }

    private extractOpenApiServers(specData: any): EnvironmentCandidate[] {
        const candidates: EnvironmentCandidate[] = [];

        if (Array.isArray(specData.servers) && specData.servers.length > 0) {
            specData.servers.forEach((server: any, index: number) => {
                const url = this.buildOpenApiServerUrl(server);
                const normalizedAddress = this.normalizeEnvironmentAddress(url);
                if (normalizedAddress) {
                    const name = server?.description || server?.name || specData.info?.title || `Server ${index + 1}`;
                    candidates.push({
                        name,
                        address: normalizedAddress,
                        protocol: this.resolveProtocolName(server?.protocol || specData.info?.['x-protocol'] || specData.protocol)
                    });
                }
            });
        } else if (specData.swagger && specData.host) {
            const schemes = Array.isArray(specData.schemes) && specData.schemes.length > 0 ? specData.schemes : ['https'];
            const basePath = typeof specData.basePath === 'string' ? specData.basePath : '';
            schemes.forEach((scheme: string) => {
                const url = `${scheme}://${specData.host}${basePath}`;
                const normalizedAddress = this.normalizeEnvironmentAddress(url);
                if (normalizedAddress) {
                    candidates.push({
                        name: specData.info?.title || specData.host,
                        address: normalizedAddress,
                        protocol: scheme.toUpperCase()
                    });
                }
            });
        }

        return candidates;
    }

    private buildOpenApiServerUrl(server: any): string | undefined {
        if (!server?.url || typeof server.url !== 'string') {
            return undefined;
        }
        let resolvedUrl = server.url;
        const variables = server.variables && typeof server.variables === 'object' ? server.variables : undefined;
        if (variables) {
            Object.entries(variables).forEach(([key, value]: [string, any]) => {
                const token = `{${key}}`;
                if (resolvedUrl.includes(token)) {
                    resolvedUrl = resolvedUrl.replaceAll(token, value?.default ?? "");
                }
            });
        }
        return resolvedUrl;
    }

    private normalizeEnvironmentAddress(value: string | undefined | null): string | null {
        if (!value || typeof value !== 'string') {
            return null;
        }
        const trimmed = value.trim();
        if (!trimmed) {
            return null;
        }
        if (trimmed === "/") {
            return trimmed;
        }
        return trimmed.replace(/\/+$/, "");
    }

    private deduplicateEnvironmentCandidates(candidates: EnvironmentCandidate[]): EnvironmentCandidate[] {
        const map = new Map<string, EnvironmentCandidate>();
        candidates.forEach((candidate) => {
            const key = candidate.address.toLowerCase();
            if (!map.has(key)) {
                map.set(key, candidate);
            }
        });
        return Array.from(map.values());
    }

    private resolveProtocolName(protocol: unknown): string | undefined {
        if (typeof protocol !== 'string') {
            return undefined;
        }
        const trimmed = protocol.trim();
        return trimmed ? trimmed.toUpperCase() : undefined;
    }

    /**
     * Extract version from file name or content
     */
    private async extractVersionFromFile(file: File): Promise<string> {
        try {
            // First try to extract from file name
            const fileName = file.name;
            const versionMatch = fileName.match(/v?(\d+\.\d+\.\d+)/);
            if (versionMatch) {
                return versionMatch[1];
            }

            // If not found in filename, try to extract from content
            if (file.text) {
                const content = await file.text();

                // Try to parse as JSON
                try {
                    const json = JSON.parse(content);

                    // For Swagger 2.0
                    if (json.swagger && json.info && json.info.version) {
                        return json.info.version;
                    }

                    // For OpenAPI 3.x
                    if (json.openapi && json.info && json.info.version) {
                        return json.info.version;
                    }

                    // For AsyncAPI
                    if (json.asyncapi && json.info && json.info.version) {
                        return json.info.version;
                    }

                } catch (jsonError) {
                    // If not JSON, try as YAML
                    try {
                        const yamlData = ContentParser.parseContent(content);

                        // For AsyncAPI YAML
                        if (yamlData.asyncapi && yamlData.info && yamlData.info.version) {
                            return yamlData.info.version;
                        }

                        // For OpenAPI YAML
                        if (yamlData.openapi && yamlData.info && yamlData.info.version) {
                            return yamlData.info.version;
                        }

                        // For Swagger YAML
                        if (yamlData.swagger && yamlData.info && yamlData.info.version) {
                            return yamlData.info.version;
                        }

                    } catch (yamlError) {
                        console.log('Error parsing file content as both JSON and YAML for version extraction:', { jsonError, yamlError });
                    }
                }
            }

        } catch (error) {
            console.log('Error reading file content for version extraction:', error);
        }

        // Fallback to default version
        return '1.0.0';
    }

    /**
     * Create operations from file based on specification type
     */
    private async createOperationsFromFile(
        file: File,
        specificationType: ApiSpecificationType,
        specificationId: string,
        allFiles?: File[],
        contentCache?: Map<string, Promise<string>>
    ): Promise<any[]> {
        try {
            const content = await this.getFileContentWithCache(file, contentCache);
            if (!content) {
                throw new Error(`Unable to read specification content from ${file.name}`);
            }

            // If specification type is HTTP but content suggests AsyncAPI, correct it
            let actualSpecificationType = specificationType;
            if (specificationType === ApiSpecificationType.HTTP) {
                try {
                    let parsedContent: any;
                    try {
                        parsedContent = JSON.parse(content);
                    } catch (jsonError) {
                        try {
                            parsedContent = ContentParser.parseContent(content);
                        } catch (yamlError) {
                            // Keep original type if parsing fails
                        }
                    }

                    if (parsedContent && parsedContent.asyncapi) {
                        actualSpecificationType = ApiSpecificationType.ASYNC;
                    }
                } catch (error) {
                }
            }


            switch (actualSpecificationType) {
                case ApiSpecificationType.SOAP:
                    const additionalDocuments = await this.buildWsdlAdditionalDocuments(file, allFiles, contentCache);
                    const wsdlData = await SoapSpecificationParser.parseWsdlContent(content, {
                        fileName: file.name,
                        additionalDocuments
                    });
                    return SoapSpecificationParser.createOperationsFromWsdl(wsdlData, specificationId);

                case ApiSpecificationType.GRPC:
                    const protoData = await ProtoSpecificationParser.parseProtoContent(content);
                    return ProtoSpecificationParser.createOperationsFromProto(protoData, specificationId);

                case ApiSpecificationType.GRAPHQL:
                    const graphqlData = await GraphQLSpecificationParser.parseGraphQLContent(content);
                    return GraphQLSpecificationParser.createOperationsFromGraphQL(graphqlData, specificationId);

                case ApiSpecificationType.HTTP:
                    // For OpenAPI, use OpenApiSpecificationParser directly
                    const openApiData = await OpenApiSpecificationParser.parseOpenApiContent(content);
                    return OpenApiSpecificationParser.createOperationsFromOpenApi(openApiData, specificationId);

                case ApiSpecificationType.ASYNC:
                    const asyncApiData = await AsyncApiSpecificationParser.parseAsyncApiContent(content);
                    return AsyncApiSpecificationParser.createOperationsFromAsyncApi(asyncApiData, specificationId);

                default:
                    throw new Error(`Unsupported specification type ${specificationType} for ${file.name}`);
            }
        } catch (error) {
            console.error('[SpecificationProcessorService] Failed to create operations:', error);
            throw error instanceof Error ? error : new Error('Failed to create operations');
        }
    }

    private detectTypeByExtension(extension: string): ApiSpecificationType | null {
        switch (extension.toLowerCase()) {
            case '.wsdl':
                return ApiSpecificationType.SOAP;
            case '.graphql':
            case '.gql':
                return ApiSpecificationType.GRAPHQL;
            case '.proto':
                return ApiSpecificationType.GRPC;
            default:
                return null;
        }
    }

    private isLikelyWsdlContent(extension: string, content: string): boolean {
        if (extension.toLowerCase() === '.wsdl') {
            return true;
        }
        const snippet = content.slice(0, 512).toLowerCase();
        return snippet.includes('http://schemas.xmlsoap.org/wsdl') || snippet.includes('http://www.w3.org/ns/wsdl');
    }

    private isWsdlDependencyExtension(extension: string): boolean {
        const normalized = extension.toLowerCase();
        return normalized === '.xsd';
    }

    private tryParseStructuredContent(content: string): any | null {
        try {
            return JSON.parse(content);
        } catch {
            try {
                return yaml.parse(content, { maxAliasCount: -1 });
            } catch {
                return null;
            }
        }
    }

}
