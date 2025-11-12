import { ExtensionContext, Uri, window } from "vscode";
import {
    ImportSpecificationResult,
    ImportSpecificationGroupRequest,
    SerializedFile,
} from "./importApiTypes";
import { SpecificationGroup, Specification, IntegrationSystem, IntegrationSystemType, Environment } from "./servicesTypes";
import { ImportProgressTracker } from "./importProgressTracker";
import { SpecificationGroupService } from "./SpecificationGroupService";
import { SpecificationProcessorService, EnvironmentCandidate } from "./SpecificationProcessorService";
import { EnvironmentService } from "./EnvironmentService";
import { SystemService } from "./SystemService";
import { fileApi } from "../response/file/fileApiProvider";
import { getExtensionsForFile } from "../response/file/fileExtensions";
import { GraphQLSpecificationParser } from "./parsers/GraphQLSpecificationParser";
import { ProtoSpecificationParser } from "./parsers/ProtoSpecificationParser";
import { OpenApiSpecificationParser } from "./parsers/OpenApiSpecificationParser";
import { SoapSpecificationParser } from "./parsers/SoapSpecificationParser";
import { AsyncApiSpecificationParser } from "./parsers/AsyncApiSpecificationParser";
import { LabelUtils } from "./LabelUtils";
import { ContentParser } from './parsers/ContentParser';
import { ProjectConfigService } from "../services/ProjectConfigService";
import { SpecificationValidator } from "./SpecificationValidator";
import { ApiSpecificationType } from "./importApiTypes";
import { normalizePath } from "./pathUtils";
import type { EnvironmentRequest } from "./servicesTypes";

export class SpecificationImportService {
    private context: ExtensionContext;
    private progressTracker: ImportProgressTracker;
    private serviceFileUri?: Uri;
    private specificationGroupService: SpecificationGroupService;
    private specificationProcessorService: SpecificationProcessorService;
    private environmentService: EnvironmentService;
    private systemService: SystemService;

    constructor(context: ExtensionContext, serviceFileUri?: Uri) {
        this.context = context;
        this.progressTracker = ImportProgressTracker.getInstance(context);
        this.serviceFileUri = serviceFileUri;
        this.specificationGroupService = new SpecificationGroupService(context, serviceFileUri);
        this.specificationProcessorService = new SpecificationProcessorService(context, serviceFileUri);
        this.environmentService = new EnvironmentService(context, serviceFileUri);
        this.systemService = new SystemService(context, serviceFileUri);
    }

    /**
     * Import specification group
     */
    async importSpecificationGroup(request: ImportSpecificationGroupRequest): Promise<ImportSpecificationResult> {
        const importId = crypto.randomUUID();

        try {
            const validationResult = await this.validateImportRequest(request);
            if (!validationResult.isValid) {
                throw new Error(`Invalid import request: ${validationResult.errors.join(', ')}`);
            }
            const system = await this.systemService.getSystemById(request.systemId);
            if (!system) {
                throw new Error(`System with id ${request.systemId} not found`);
            }

            const extractedFiles = await this.convertSerializedFilesToFiles(request.files || []);
            const importingProtocol = await this.detectImportingProtocol(extractedFiles);
            const protocolName = importingProtocol ? importingProtocol.toUpperCase() : undefined;

            if (importingProtocol) {
                const systemProtocol = this.convertToApiSpecificationType(system.protocol);
                const importProtocol = this.convertToApiSpecificationType(importingProtocol);
                if (importProtocol) {
                    try {
                        SpecificationValidator.validateSpecificationProtocol(systemProtocol, importProtocol);
                    } catch (error) {
                        console.error(`[SpecificationImportService] Protocol validation failed:`, error);
                        window.showErrorMessage(error instanceof Error ? error.message : 'Protocol validation failed');
                        throw error;
                    }
                }
            }

            const specificationGroup = await this.specificationGroupService.createSpecificationGroup(
                system,
                request.name,
                protocolName
            );
            this.systemService.saveSystem(system);
            this.progressTracker.startImportSession(importId, specificationGroup.id);

            await this.specificationProcessorService.processSpecificationFiles(
                specificationGroup,
                extractedFiles,
                request.systemId
            );

            await this.saveSpecificationFiles(request.systemId, specificationGroup, extractedFiles);

            try {
                await this.specificationGroupService.saveSpecificationGroupFile(
                    request.systemId,
                    specificationGroup
                );
            } catch (error) {
                console.error(`[SpecificationImportService] Failed to save specification group file:`, error);
                throw new Error(`Failed to save specification group file: ${error instanceof Error ? error.message : 'Unknown error'}`);
            }

            try {
                await this.createEnvironmentForSpecificationGroup(
                    system,
                    specificationGroup,
                    request.systemId,
                    extractedFiles
                );
            } catch (error) {
                console.error(`[SpecificationImportService] Error creating environment:`, error);
            }

            const result: ImportSpecificationResult = {
                id: importId,
                specificationGroupId: specificationGroup.id,
                done: true
            };


            this.progressTracker.completeImportSession(importId, result);

            return result;

        } catch (error) {
            const result: ImportSpecificationResult = {
                id: importId,
                specificationGroupId: '',
                done: true,
                warningMessage: error instanceof Error ? error.message : 'Unknown error'
            };

            this.progressTracker.failImportSession(importId, result.warningMessage || 'Unknown error');

            return result;
        }
    }

    /**
     * Import specification into existing group
     */
    async importSpecification(specificationGroupId: string, files: SerializedFile[], systemId: string): Promise<ImportSpecificationResult> {
        const importId = crypto.randomUUID();

        try {
            const specificationGroup = await this.specificationGroupService.getSpecificationGroupById(specificationGroupId, systemId);
            if (!specificationGroup) {
                throw new Error(`Specification group with id ${specificationGroupId} not found`);
            }

            const system = await this.systemService.getSystemById(systemId);
            if (!system) {
                throw new Error(`System with id ${systemId} not found`);
            }

            this.progressTracker.startImportSession(importId, specificationGroup.id);

            const extractedFiles = await this.convertSerializedFilesToFiles(files);

            const importingProtocol = await this.detectImportingProtocol(extractedFiles);
            if (importingProtocol) {
                const systemProtocol = this.convertToApiSpecificationType(system.protocol);
                const importProtocol = this.convertToApiSpecificationType(importingProtocol);
                if (importProtocol) {
                    try {
                        SpecificationValidator.validateSpecificationProtocol(systemProtocol, importProtocol);
                    } catch (error) {
                        console.error(`[SpecificationImportService] Protocol validation failed:`, error);
                        window.showErrorMessage(error instanceof Error ? error.message : 'Protocol validation failed');
                        throw error;
                    }
                }
            }

            await this.specificationProcessorService.processSpecificationFiles(
                specificationGroup,
                extractedFiles,
                systemId
            );

            await this.saveSpecificationFiles(systemId, specificationGroup, extractedFiles);
            await this.specificationGroupService.saveSpecificationGroupFile(systemId, specificationGroup);

            const result: ImportSpecificationResult = {
                id: importId,
                specificationGroupId: specificationGroup.id,
                done: true
            };

            this.progressTracker.completeImportSession(importId, result);

            return result;

        } catch (error) {
            console.error(`[SpecificationImportService] Specification import failed:`, error);

            const result: ImportSpecificationResult = {
                id: importId,
                specificationGroupId: specificationGroupId,
                done: true,
                warningMessage: error instanceof Error ? error.message : 'Unknown error'
            };

            this.progressTracker.failImportSession(importId, result.warningMessage || 'Unknown error');

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
    private async detectImportingProtocol(files: File[]): Promise<string | null> {
        try {
            for (const file of files) {
                const content = await this.readFileContent(file);
                if (!content) {
                    continue;
                }

                try {
                    const specData = ContentParser.parseContent(content);
                    const protocol = this.specificationProcessorService.detectProtocolFromSpecification(specData);
                    if (protocol) {
                        return protocol;
                    }
                } catch (parseError) {
                }
            }

            for (const file of files) {
                const fileName = file.name.toLowerCase();
                if (fileName.includes('openapi') || fileName.includes('swagger')) {
                    return 'http';
                } else if (fileName.includes('graphql')) {
                    return 'graphql';
                } else if (fileName.includes('proto')) {
                    return 'grpc';
                } else if (fileName.includes('wsdl')) {
                    return 'soap';
                }
            }

            return 'http';

        } catch (error) {
            return null;
        }
    }

    /**
     * Parse GraphQL content using GraphQLSpecificationParser
     */
    async parseGraphQLContent(content: string): Promise<any> {
        return GraphQLSpecificationParser.parseGraphQLContent(content);
    }

    /**
     * Parse Proto content using ProtoSpecificationParser
     */
    async parseProtoContent(content: string): Promise<any> {
        return ProtoSpecificationParser.parseProtoContent(content);
    }

    /**
     * Save specification files and copy source files
     */
    private async saveSpecificationFiles(
        systemId: string,
        specificationGroup: SpecificationGroup,
        extractedFiles: File[]
    ): Promise<void> {
        try {
            const baseFolder = await this.getBaseFolder();
            if (!baseFolder) {
                throw new Error('No base folder available');
            }

            for (let i = 0; i < specificationGroup.specifications.length; i++) {
                const specification = specificationGroup.specifications[i];
                const sourceFile = extractedFiles[i];

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
                        specificationSources: await Promise.all(extractedFiles.map(async (file, index) => ({
                            id: crypto.randomUUID(),
                            name: file.name,
                            sourceHash: this.calculateHash(await this.readFileContent(file)),
                            fileName: `source-${specification.id}/${file.name}`,
                            mainSource: file === sourceFile
                        }))),
                        parentId: specificationGroup.id,
                        labels: specification.labels ? LabelUtils.fromEntityLabels(specification.labels) : []
                    }
                };

                const yaml = require('yaml');
                // Disable anchors to avoid "Excessive alias count" error when parsing large specifications
                const yamlContent = yaml.stringify(qipSpecification, {
                    aliasDuplicateObjects: false
                });
                const bytes = new TextEncoder().encode(yamlContent);
                await fileApi.writeFile(specFileUri, bytes);

                // Copy source file and additional files to resources folder
                await this.copySourceFileToResources(baseFolder, specification.id, sourceFile);

                // Copy additional files (like XSD for SOAP)
                if (extractedFiles.length > 1) {
                    const additionalFiles = extractedFiles.filter(f => f !== sourceFile);
                    for (const additionalFile of additionalFiles) {
                        await this.copySourceFileToResources(baseFolder, specification.id, additionalFile);
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
        sourceFile: File
    ): Promise<void> {
        try {
            const resourcesFolder = Uri.joinPath(baseFolder, 'resources');
            const sourceFolderName = `source-${specificationId}`;
            const sourceFolder = Uri.joinPath(resourcesFolder, sourceFolderName);

            const targetFileUri = Uri.joinPath(sourceFolder, sourceFile.name);
            const fileContent = await this.readFileContent(sourceFile);
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
            const candidates = specData ? this.specificationProcessorService.extractEnvironmentCandidates(specData) : [];
            if (candidates.length === 0) {
                return;
            }

            const systemType = system.integrationSystemType || system.type;
            const existingEnvironments = await this.environmentService.getEnvironmentsForSystem(systemId);
            const existingAddresses = new Set(
                existingEnvironments
                    .map((env) => this.normalizeEnvironmentAddress(env.address))
                    .filter((value): value is string => Boolean(value))
                    .map((value) => value.toLowerCase())
            );

            if (systemType === IntegrationSystemType.EXTERNAL) {
                await this.createEnvironmentsForExternalSystem(candidates, specificationGroup, systemId, existingAddresses);
            } else {
                await this.applyInternalEnvironmentStrategy(
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
            if (!normalizedAddress) {
                continue;
            }
            const addressKey = normalizedAddress.toLowerCase();
            if (existingAddresses.has(addressKey)) {
                continue;
            }

            const environmentRequest: EnvironmentRequest = {
                name: this.buildEnvironmentName(specificationGroup.name, candidate, normalizedAddress, index),
                address: normalizedAddress,
                description: this.buildEnvironmentDescription(specificationGroup.name),
                systemId,
                isActive: existingAddresses.size === 0 && index === 0
            };

            const environment = await this.environmentService.createEnvironment(environmentRequest);
            existingAddresses.add(addressKey);

        }
    }

    private async applyInternalEnvironmentStrategy(
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

        const addressKey = normalizedAddress.toLowerCase();
        if (existingAddresses.has(addressKey)) {
            return;
        }

        if (existingEnvironments.length === 0) {
            await this.environmentService.createEnvironment({
                name: this.buildEnvironmentName(specificationGroup.name, primaryCandidate, normalizedAddress, 0),
                address: normalizedAddress,
                description: this.buildEnvironmentDescription(specificationGroup.name),
                systemId,
                isActive: true
            });
            return;
        }

        const targetEnvironment = existingEnvironments[0];
        const currentAddress = this.normalizeEnvironmentAddress(targetEnvironment.address);
        if (!currentAddress) {
            await this.environmentService.updateEnvironment(systemId, targetEnvironment.id, {
                name: this.buildEnvironmentName(specificationGroup.name, primaryCandidate, normalizedAddress, 0),
                address: normalizedAddress
            });
        }
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
                return ApiSpecificationType.HTTP;
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
            const extension = this.getFileExtension(candidate.name);
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

        return documents;
    }

}
