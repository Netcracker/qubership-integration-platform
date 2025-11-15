import { Uri, window } from "vscode";
import * as yaml from "yaml";
import {
    ImportSpecificationResult,
    ImportSpecificationGroupRequest,
    SerializedFile,
} from "./importApiTypes";
import { SpecificationGroup, IntegrationSystem, IntegrationSystemType, Environment, Specification } from "./servicesTypes";
import { ImportProgressTracker } from "./importProgressTracker";
import { SpecificationGroupService } from "./SpecificationGroupService";
import { SpecificationProcessorService, EnvironmentCandidate } from "./SpecificationProcessorService";
import { EnvironmentService } from "./EnvironmentService";
import { SystemService } from "./SystemService";
import { fileApi } from "../response/file/fileApiProvider";
import { SoapSpecificationParser } from "./parsers/SoapSpecificationParser";
import { LabelUtils } from "./LabelUtils";
import { ContentParser } from './parsers/ContentParser';
import { ProjectConfigService } from "../services/ProjectConfigService";
import { SpecificationValidator } from "./SpecificationValidator";
import { ApiSpecificationType } from "./importApiTypes";
import { normalizePath } from "./pathUtils";
import type { EnvironmentRequest } from "./servicesTypes";
import { EnvironmentDefaultProperties } from "./EnvironmentDefaultProperties";

export class SpecificationImportService {
    private progressTracker: ImportProgressTracker;
    private serviceFileUri?: Uri;
    private specificationGroupService: SpecificationGroupService;
    private specificationProcessorService: SpecificationProcessorService;
    private environmentService: EnvironmentService;
    private systemService: SystemService;

    constructor(serviceFileUri?: Uri) {
        this.progressTracker = ImportProgressTracker.getInstance();
        this.serviceFileUri = serviceFileUri;
        this.specificationGroupService = new SpecificationGroupService(serviceFileUri);
        this.specificationProcessorService = new SpecificationProcessorService();
        this.environmentService = new EnvironmentService();
        this.systemService = new SystemService();
    }

    /**
     * Import specification group
     */
    async importSpecificationGroup(request: ImportSpecificationGroupRequest): Promise<ImportSpecificationResult> {
        const validationResult = await this.validateImportRequest(request);
        if (!validationResult.isValid) {
            throw new Error(`Invalid import request: ${validationResult.errors.join(', ')}`);
        }

        const system = await this.systemService.getSystemById(request.systemId);
        if (!system) {
            throw new Error(`System with id ${request.systemId} not found`);
        }

        return this.runImport({
            system,
            systemId: request.systemId,
            serializedFiles: request.files || [],
            specificationGroupResolver: async (protocolName?: string) =>
                this.specificationGroupService.createSpecificationGroup(system, request.name, protocolName),
            afterImport: async (specificationGroup, extractedFiles) => {
                try {
                    await this.createEnvironmentForSpecificationGroup(system, specificationGroup, request.systemId, extractedFiles);
                } catch (error) {
                    console.error(`[SpecificationImportService] Error creating environment:`, error);
                }
            }
        });
    }

    /**
     * Import specification into existing group
     */
    async importSpecification(specificationGroupId: string, files: SerializedFile[], systemId: string): Promise<ImportSpecificationResult> {
        const specificationGroup = await this.specificationGroupService.getSpecificationGroupById(specificationGroupId, systemId);
        if (!specificationGroup) {
            throw new Error(`Specification group with id ${specificationGroupId} not found`);
        }

        const system = await this.systemService.getSystemById(systemId);
        if (!system) {
            throw new Error(`System with id ${systemId} not found`);
        }

        return this.runImport({
            system,
            systemId,
            serializedFiles: files,
            specificationGroupResolver: async () => specificationGroup,
            specificationGroupIdHint: specificationGroupId
        });
    }

    private async runImport(params: {
        system: IntegrationSystem;
        systemId: string;
        serializedFiles: SerializedFile[];
        specificationGroupResolver: (protocolName?: string) => Promise<SpecificationGroup>;
        afterImport?: (specificationGroup: SpecificationGroup, files: File[]) => Promise<void>;
        specificationGroupIdHint?: string;
    }): Promise<ImportSpecificationResult> {
        const importId = crypto.randomUUID();
        let specificationGroupId = params.specificationGroupIdHint || '';

        try {
            const extractedFiles = await this.convertSerializedFilesToFiles(params.serializedFiles || []);
            const importingProtocol = await this.detectImportingProtocol(extractedFiles);
            if (!importingProtocol) {
                const errorMessage = 'Unsupported specification format: unable to detect protocol';
                window.showErrorMessage(errorMessage);
                throw new Error(errorMessage);
            }

            const systemProtocol = this.convertToApiSpecificationType(params.system.protocol);
            SpecificationValidator.validateSpecificationProtocol(systemProtocol, importingProtocol);
            await this.ensureSystemProtocol(params.system, importingProtocol);

            const specificationGroup = await params.specificationGroupResolver(importingProtocol);
            specificationGroupId = specificationGroup.id;

            this.progressTracker.startImportSession(importId, specificationGroup.id);

            const contentCache = new Map<string, Promise<string>>();

            await this.specificationProcessorService.processSpecificationFiles(
                specificationGroup,
                extractedFiles,
                params.systemId,
                contentCache
            );

            await this.saveSpecificationFiles(params.systemId, specificationGroup, extractedFiles, contentCache);
            await this.specificationGroupService.saveSpecificationGroupFile(params.systemId, specificationGroup);

            if (params.afterImport) {
                await params.afterImport(specificationGroup, extractedFiles);
            }

            const result: ImportSpecificationResult = {
                id: importId,
                specificationGroupId,
                done: true
            };

            this.progressTracker.completeImportSession(importId, result);
            return result;
        } catch (error) {
            console.error(`[SpecificationImportService] Specification import failed:`, error);
            const warningMessage = this.buildImportErrorMessage(error);
            window.showErrorMessage(warningMessage);
            const result: ImportSpecificationResult = {
                id: importId,
                specificationGroupId,
                done: true,
                warningMessage: warningMessage
            };

            this.progressTracker.failImportSession(importId, warningMessage);
            return result;
        }
    }

    /**
     * Get import result
     */
    async getImportSpecificationResult(importId: string): Promise<ImportSpecificationResult> {
        const result = this.progressTracker.getImportSession(importId);
        if (!result) {
        return {
            id: importId,
            done: true,
                specificationGroupId: '',
                warningMessage: `Import session ${importId} not found. It may have expired or been cleaned up.`
            };
        }
        return result;
    }

    /**
     * Validate import request
     */
    private async validateImportRequest(request: ImportSpecificationGroupRequest): Promise<{
        isValid: boolean;
        errors: string[];
    }> {
        const errors: string[] = [];

        if (!request.systemId) {
            errors.push('System ID is required');
        }

        if (!request.name) {
            errors.push('Specification group name is required');
        }

        if (!request.files || request.files.length === 0) {
            errors.push('At least one file is required');
        }

        return {
            isValid: errors.length === 0,
            errors
        };
    }

    /**
     * Convert SerializedFile array to File array
     */
    private async convertSerializedFilesToFiles(serializedFiles: SerializedFile[]): Promise<File[]> {
        const files: File[] = [];

        for (let index = 0; index < serializedFiles.length; index++) {
            const serializedFile = serializedFiles[index];

            try {
                const file = new File([serializedFile.content], serializedFile.name, {
                    type: serializedFile.type || 'application/octet-stream'
                });

                files.push(file);
        } catch (error) {
                console.error(`[SpecificationImportService] Error converting file ${serializedFile.name}:`, error);
                throw new Error(`Failed to convert file ${serializedFile.name}: ${error}`);
            }
        }

        return files;
    }

    /**
     * Detect importing protocol from files
     */
    private async detectImportingProtocol(files: File[]): Promise<ApiSpecificationType | null> {
        let fallbackProtocol: ApiSpecificationType | null = null;
        for (const file of files) {
            const extension = this.getFileExtension(file.name);
            const extensionBasedProtocol = this.detectProtocolByExtension(extension);
            if (extensionBasedProtocol) {
                if (extensionBasedProtocol === ApiSpecificationType.ASYNC) {
                    fallbackProtocol = fallbackProtocol ?? ApiSpecificationType.ASYNC;
                } else {
                    return extensionBasedProtocol;
                }
            }

            const content = await this.readFileContent(file);
            if (!content) {
                continue;
            }

            if (this.isWsdlContent(extension, content)) {
                return ApiSpecificationType.SOAP;
            }

            const parsedContent = this.safeParseContent(content);
            if (!parsedContent) {
                continue;
            }

            const protocolFromContent = this.detectProtocolFromParsedContent(parsedContent);
            if (protocolFromContent) {
                return protocolFromContent;
            }
        }

        return fallbackProtocol;
    }

    /**
     * Save specification files and copy source files
     */
    private async saveSpecificationFiles(
        systemId: string,
        specificationGroup: SpecificationGroup,
        extractedFiles: File[],
        contentCache?: Map<string, Promise<string>>
    ): Promise<void> {
        try {
            const baseFolder = await this.getBaseFolder();
            if (!baseFolder) {
                throw new Error('No base folder available');
            }

            for (let i = 0; i < specificationGroup.specifications.length; i++) {
                const specification = specificationGroup.specifications[i];
                const sourceFile = this.resolveSpecificationSourceFile(specification, extractedFiles);

                if (!sourceFile) {
                    console.warn(`[SpecificationImportService] No source file found for specification: ${specification.name}`);
                    continue;
                }

                // Create specification file with operations using existing architecture
                const config = ProjectConfigService.getConfig();
                const specFileName = `${systemId}-${specificationGroup.name}-${specification.version}${config.extensions.specification}`;
                const specFileUri = Uri.joinPath(baseFolder, specFileName);

                // Create QIP specification using operations from SpecificationProcessorService
                const qipSpecification = {
                    id: specification.id,
                    $schema: config.schemaUrls.specification,
                    name: specification.name,
                    content: {
                        deprecated: specification.deprecated,
                        version: specification.version,
                        source: "MANUAL",
                        operations: specification.operations || [],
                        specificationSources: await Promise.all(extractedFiles.map(async (file) => ({
                            id: crypto.randomUUID(),
                            name: file.name,
                            sourceHash: this.calculateHash(await this.getFileContentCached(file, contentCache)),
                            fileName: `source-${specification.id}/${file.name}`,
                            mainSource: file === sourceFile
                        }))),
                        parentId: specificationGroup.id,
                        labels: specification.labels ? LabelUtils.fromEntityLabels(specification.labels) : []
                    }
                };

                // Disable anchors to avoid "Excessive alias count" error when parsing large specifications
                const yamlContent = yaml.stringify(qipSpecification, {
                    aliasDuplicateObjects: false
                });
                const bytes = new TextEncoder().encode(yamlContent);
                await fileApi.writeFile(specFileUri, bytes);

                // Copy source file and additional files to resources folder
                await this.copySourceFileToResources(baseFolder, specification.id, sourceFile, contentCache);

                // Copy additional files (like XSD for SOAP)
                if (extractedFiles.length > 1) {
                    const additionalFiles = extractedFiles.filter(f => f !== sourceFile);
                    for (const additionalFile of additionalFiles) {
                        await this.copySourceFileToResources(baseFolder, specification.id, additionalFile, contentCache);
                    }
                }
            }

        } catch (error) {
            console.error(`[SpecificationImportService] Error saving specification files:`, error);
            throw new Error(`Failed to save specification files: ${error instanceof Error ? error.message : 'Unknown error'}`);
        }
    }

    /**
     * Copy source file to resources folder
     */
    private async copySourceFileToResources(
        baseFolder: Uri,
        specificationId: string,
        sourceFile: File,
        contentCache?: Map<string, Promise<string>>
    ): Promise<void> {
        try {
            const resourcesFolder = Uri.joinPath(baseFolder, 'resources');
            const sourceFolderName = `source-${specificationId}`;
            const sourceFolder = Uri.joinPath(resourcesFolder, sourceFolderName);

            const targetFileUri = Uri.joinPath(sourceFolder, sourceFile.name);
            const fileContent = await this.getFileContentCached(sourceFile, contentCache);
            const bytes = new TextEncoder().encode(fileContent || '');

            await fileApi.writeFile(targetFileUri, bytes);
        } catch (error) {
            console.error(`[SpecificationImportService] Error copying source file ${sourceFile.name}:`, error);
            throw error;
        }
    }

    /**
     * Read file content as text
     */
    private async readFileContent(file: File): Promise<string> {
        return await file.text();
    }

    private async getFileContentCached(file: File, cache?: Map<string, Promise<string>>): Promise<string> {
        if (!cache) {
            return this.readFileContent(file);
        }
        const key = normalizePath(file.name);
        if (!cache.has(key)) {
            cache.set(key, file.text());
        }
        return cache.get(key)!;
    }

    private async ensureSystemProtocol(system: IntegrationSystem, protocol: ApiSpecificationType): Promise<void> {
        const protocolChanged = this.syncSystemProtocol(system, protocol);
        if (!protocolChanged) {
            return;
        }
        console.log(`[SpecificationImportService] Updating system protocol`, {
            systemId: system.id,
            protocol: system.protocol,
            extendedProtocol: system.extendedProtocol
        });
        await this.systemService.saveSystem(system);
    }

    private syncSystemProtocol(system: IntegrationSystem, protocol: ApiSpecificationType): boolean {
        if (!protocol) {
            return false;
        }
        const normalizedProtocol = protocol.toString().toUpperCase();
        let changed = false;
        if (system.protocol?.toUpperCase() !== normalizedProtocol) {
            system.protocol = normalizedProtocol;
            changed = true;
        }
        if (system.extendedProtocol?.toUpperCase() !== normalizedProtocol) {
            system.extendedProtocol = normalizedProtocol;
            changed = true;
        }
        return changed;
    }

    /**
     * Get base folder
     */
    private async getBaseFolder(): Promise<Uri> {
        if (!this.serviceFileUri) {
            throw new Error('Service file must be selected');
        }
        const type = await fileApi.getFileType(this.serviceFileUri);
        if (type !== 'SERVICE') {
            throw new Error('Service file must be selected');
        }
        const lastSlashIndex = this.serviceFileUri.path.lastIndexOf('/');
        const parentPath = lastSlashIndex > 0 ? this.serviceFileUri.path.substring(0, lastSlashIndex) : this.serviceFileUri.path;
        return this.serviceFileUri.with({ path: parentPath });
    }


    /**
     * Calculate string hash
     */
    private calculateHash(str: string): string {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            const char = str.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash; // Convert to 32bit integer
        }
        return Math.abs(hash).toString(16);
    }

    private resolveSpecificationSourceFile(specification: Specification, files: File[]): File | undefined {
        if (specification.source) {
            const normalizedSource = normalizePath(specification.source);
            const matched = files.find((file) => normalizePath(file.name) === normalizedSource);
            if (matched) {
                return matched;
            }
        }

        return files[0];
    }

    /**
     * Create environment for specification group
     * Based on the old implementation but without protocol filtering
     */
    private async createEnvironmentForSpecificationGroup(
        system: IntegrationSystem,
        specificationGroup: SpecificationGroup,
        systemId: string,
        files: File[]
    ): Promise<void> {
        try {
            const specData = await this.extractSpecificationData(files);
            const rawCandidates = specData ? this.specificationProcessorService.extractEnvironmentCandidates(specData) : [];
            const candidates = rawCandidates.length > 0
                ? rawCandidates
                : this.buildGrpcFallbackCandidates(system, specificationGroup);
            if (candidates.length === 0) {
                return;
            }

            const systemType = system.integrationSystemType || system.type;
            const existingEnvironments = await this.environmentService.getEnvironmentsForSystem(systemId);
            const existingAddresses = this.buildExistingAddressSet(existingEnvironments);

            if (systemType === IntegrationSystemType.EXTERNAL) {
                await this.createEnvironmentsForExternalSystem(
                    candidates,
                    specificationGroup,
                    systemId,
                    existingAddresses
                );
            } else {
                await this.ensureInternalEnvironment(
                    candidates,
                    specificationGroup,
                    systemId,
                    existingEnvironments,
                    existingAddresses
                );
            }
        } catch (error) {
            console.error(`[SpecificationImportService] Error creating environment:`, error);
        }
    }

    /**
     * Extract specification data from files
     */
    private async extractSpecificationData(files: File[]): Promise<any> {
        for (const file of files) {
            try {
                const content = await file.text();
                if (!content) {
                    continue;
                }

                const extension = this.getFileExtension(file.name);
                if (this.isWsdlContent(extension, content)) {
                    const additionalDocuments = await this.buildWsdlAdditionalDocumentsForImport(file, files);
                    return await SoapSpecificationParser.parseWsdlContent(content, {
                        fileName: file.name,
                        additionalDocuments
                    });
                }

                const parsedContent = ContentParser.parseContent(content);
                if (parsedContent && typeof parsedContent === "object") {
                    if (parsedContent.openapi || parsedContent.swagger || parsedContent.asyncapi) {
                        return parsedContent;
                    }
                    if (parsedContent.type === 'WSDL') {
                        return parsedContent;
                    }
                }
            } catch (error) {
                console.error(`[SpecificationImportService] Error reading file ${file.name}:`, error);
            }
        }
        return null;
    }

    /**
     * Convert string protocol to ApiSpecificationType
     */
    private async createEnvironmentsForExternalSystem(
        candidates: EnvironmentCandidate[],
        specificationGroup: SpecificationGroup,
        systemId: string,
        existingAddresses: Set<string>
    ): Promise<void> {
        for (let index = 0; index < candidates.length; index++) {
            const candidate = candidates[index];
            const normalizedAddress = this.normalizeEnvironmentAddress(candidate.address);
            if (!normalizedAddress || this.isDuplicateAddress(existingAddresses, normalizedAddress)) {
                continue;
            }

            const environmentRequest = this.buildEnvironmentRequest(specificationGroup.name, candidate, normalizedAddress, index);
            await this.environmentService.createEnvironment({
                ...environmentRequest,
                systemId,
                isActive: existingAddresses.size === 0 && index === 0
            });
            existingAddresses.add(normalizedAddress.toLowerCase());
        }
    }

    private async ensureInternalEnvironment(
        candidates: EnvironmentCandidate[],
        specificationGroup: SpecificationGroup,
        systemId: string,
        existingEnvironments: Environment[],
        existingAddresses: Set<string>
    ): Promise<void> {
        const primaryCandidate = candidates[0];
        const normalizedAddress = this.normalizeEnvironmentAddress(primaryCandidate.address);
        if (!normalizedAddress) {
            return;
        }

        if (this.isDuplicateAddress(existingAddresses, normalizedAddress)) {
            return;
        }

        const environmentRequest = this.buildEnvironmentRequest(specificationGroup.name, primaryCandidate, normalizedAddress, 0);

        if (existingEnvironments.length === 0) {
            await this.environmentService.createEnvironment({
                ...environmentRequest,
                systemId,
                isActive: true
            });
            return;
        }

        const targetEnvironment = existingEnvironments[0];
        const currentAddress = this.normalizeEnvironmentAddress(targetEnvironment.address);
        if (!currentAddress) {
            await this.environmentService.updateEnvironment(systemId, targetEnvironment.id, {
                name: environmentRequest.name,
                address: normalizedAddress,
                sourceType: environmentRequest.sourceType,
                properties: environmentRequest.properties
            });
        }
    }

    private buildExistingAddressSet(environments: Environment[]): Set<string> {
        return new Set(
            environments
                .map((env) => this.normalizeEnvironmentAddress(env.address))
                .filter((value): value is string => Boolean(value))
                .map((value) => value.toLowerCase())
        );
    }

    private isDuplicateAddress(existing: Set<string>, address: string): boolean {
        return existing.has(address.toLowerCase());
    }

    private buildEnvironmentRequest(
        specificationGroupName: string,
        candidate: EnvironmentCandidate,
        normalizedAddress: string,
        index: number
    ): EnvironmentRequest {
        return {
            name: this.buildEnvironmentName(specificationGroupName, candidate, normalizedAddress, index),
            address: normalizedAddress,
            description: this.buildEnvironmentDescription(specificationGroupName),
            sourceType: this.resolveEnvironmentSourceType(candidate.protocol),
            properties: this.resolveEnvironmentProperties(candidate.protocol)
        };
    }

    private resolveEnvironmentSourceType(protocol?: string): string {
        const normalized = protocol?.toUpperCase();
        if (normalized === 'MAAS' || normalized === 'MAAS_BY_CLASSIFIER') {
            return 'MAAS_BY_CLASSIFIER';
        }
        return 'MANUAL';
    }

    private resolveEnvironmentProperties(protocol?: string): Record<string, string> | undefined {
        const normalized = protocol?.toUpperCase();
        if (normalized === 'MAAS' || normalized === 'MAAS_BY_CLASSIFIER') {
            return {};
        }
        if (!protocol) {
        return undefined;
        }
        const defaults = EnvironmentDefaultProperties.getDefaultProperties(protocol);
        return Object.keys(defaults).length > 0 ? defaults : undefined;
    }

    private buildEnvironmentName(
        specificationGroupName: string,
        candidate: EnvironmentCandidate,
        fallbackAddress: string,
        index: number
    ): string {
        const name = candidate.name?.trim();
        if (name) {
            return name;
        }
        const suffix = index > 0 ? ` #${index + 1}` : "";
        return `Environment for ${specificationGroupName}${suffix}`.trim() || fallbackAddress;
    }

    private buildEnvironmentDescription(specificationGroupName: string): string {
        return `Environment created for ${specificationGroupName} specification group`;
    }

    private buildGrpcFallbackCandidates(
        system: IntegrationSystem,
        specificationGroup: SpecificationGroup
    ): EnvironmentCandidate[] {
        const protocol = system.protocol?.toLowerCase();
        if (protocol !== 'grpc') {
            return [];
        }
        return [
            {
                name: `${specificationGroup.name} gRPC endpoint`,
                address: 'grpc://localhost:50051',
                protocol: 'GRPC'
            }
        ];
    }

    private normalizeEnvironmentAddress(address: string | undefined | null): string | null {
        if (!address || typeof address !== 'string') {
            return null;
        }
        const trimmed = address.trim();
        if (!trimmed) {
            return null;
        }
        if (trimmed === "/") {
            return trimmed;
        }
        return trimmed.replace(/\/+$/, "");
    }

    private convertToApiSpecificationType(protocol: string | undefined): ApiSpecificationType | undefined {
        if (!protocol) {
            return undefined;
        }
        const upperProtocol = protocol.toUpperCase();
        switch (upperProtocol) {
            case 'HTTP':
            case 'HTTPS':
                return ApiSpecificationType.HTTP;
            case 'SOAP':
                return ApiSpecificationType.SOAP;
            case 'GRAPHQL':
                return ApiSpecificationType.GRAPHQL;
            case 'GRPC':
                return ApiSpecificationType.GRPC;
            case 'AMQP':
            case 'RABBIT':
                return ApiSpecificationType.AMQP;
            case 'MQTT':
                return ApiSpecificationType.MQTT;
            case 'KAFKA':
                return ApiSpecificationType.KAFKA;
            case 'REDIS':
                return ApiSpecificationType.REDIS;
            case 'NATS':
                return ApiSpecificationType.NATS;
            case 'ASYNC':
            case 'ASYNCAPI':
                return ApiSpecificationType.ASYNC;
            default:
                return undefined;
        }
    }

    private buildImportErrorMessage(error: unknown): string {
        const rawMessage = error instanceof Error ? error.message : (error ? String(error) : 'Unknown error');
        if (!rawMessage) {
            return 'Unsupported specification format: unable to detect protocol';
        }
        if (/unsupported protocol/i.test(rawMessage) || /protocol:\s*null/i.test(rawMessage)) {
            return 'Unsupported specification format: unable to detect protocol';
        }
        return rawMessage;
    }

    private detectProtocolByExtension(extension: string): ApiSpecificationType | null {
        switch (extension) {
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

    private detectProtocolFromParsedContent(parsed: unknown): ApiSpecificationType | null {
        if (!parsed || typeof parsed !== 'object') {
            return null;
        }

        const specData = parsed as Record<string, unknown>;

        if (specData.openapi || specData.swagger) {
            return ApiSpecificationType.HTTP;
        }

        if (specData.type === 'WSDL') {
            return ApiSpecificationType.SOAP;
        }

        if (specData.asyncapi) {
            const asyncProtocol = this.extractAsyncProtocol(specData);
            return this.resolveAsyncProtocol(asyncProtocol);
        }

        return null;
    }

    private extractAsyncProtocol(specData: Record<string, unknown>): string | undefined {
        const infoProtocol = (specData.info as Record<string, unknown> | undefined)?.['x-protocol'];
        if (typeof infoProtocol === 'string') {
            return infoProtocol;
        }

        const servers = specData.servers;
        if (servers && typeof servers === 'object') {
            const serverList = Object.values(servers as Record<string, unknown>);
            for (const server of serverList) {
                const protocol = (server as Record<string, unknown>)?.protocol;
                if (typeof protocol === 'string') {
                    return protocol;
                }
            }
        }

        return undefined;
    }

    private resolveAsyncProtocol(protocol: string | undefined): ApiSpecificationType {
        if (!protocol) {
            return ApiSpecificationType.ASYNC;
        }
        const normalized = protocol.trim().toUpperCase();
        switch (normalized) {
            case 'AMQP':
            case 'RABBITMQ':
                return ApiSpecificationType.AMQP;
            case 'MQTT':
                return ApiSpecificationType.MQTT;
            case 'KAFKA':
                return ApiSpecificationType.KAFKA;
            case 'REDIS':
                return ApiSpecificationType.REDIS;
            case 'NATS':
                return ApiSpecificationType.NATS;
            case 'SOAP':
                return ApiSpecificationType.SOAP;
            case 'HTTP':
            case 'HTTPS':
                return ApiSpecificationType.HTTP;
            default:
                return ApiSpecificationType.ASYNC;
        }
    }

    private safeParseContent(content: string): any | null {
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

    private getFileExtension(fileName: string): string {
        const lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex !== -1 ? fileName.substring(lastDotIndex).toLowerCase() : '';
    }

    private isWsdlContent(extension: string, content: string): boolean {
        if (extension === '.wsdl') {
            return true;
        }
        const snippet = content.slice(0, 512).toLowerCase();
        return snippet.includes('http://schemas.xmlsoap.org/wsdl') || snippet.includes('http://www.w3.org/ns/wsdl');
    }

    private async buildWsdlAdditionalDocumentsForImport(
        mainFile: File,
        allFiles: File[]
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
            try {
                const candidateContent = await candidate.text();
                documents.push({
                    uri: candidatePath,
                    content: candidateContent
                });
            } catch (error) {
                console.error(`[SpecificationImportService] Error reading WSDL dependency ${candidate.name}:`, error);
            }
        }

        if (documents.length === 0) {
            console.log('[SpecificationImportService] No WSDL dependencies detected for', mainPath);
        } else {
            console.log('[SpecificationImportService] Resolved WSDL dependencies for', mainPath, documents.map(({ uri }) => uri));
        }

        return documents;
    }

}
