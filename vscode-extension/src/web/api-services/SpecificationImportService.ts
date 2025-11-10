import { ExtensionContext, Uri } from "vscode";
const vscode = require('vscode');
import {
    ImportSpecificationResult,
    ImportSpecificationGroupRequest,
    SerializedFile,
} from "./importApiTypes";
import { SpecificationGroup, Specification, IntegrationSystem } from "./servicesTypes";
import { ImportProgressTracker } from "./importProgressTracker";
import { SpecificationGroupService } from "./SpecificationGroupService";
import { SpecificationProcessorService } from "./SpecificationProcessorService";
import { EnvironmentService, EnvironmentRequest } from "./EnvironmentService";
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
                        vscode.window.showErrorMessage(error instanceof Error ? error.message : 'Protocol validation failed');
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

            if (importingProtocol) {
                console.log(`[SpecificationImportService] Protocol ${importingProtocol} detected`);
            }

            try {
                await this.createEnvironmentForSpecificationGroup(
                    system,
                    specificationGroup,
                    request.systemId,
                    extractedFiles
                );
            } catch (error) {
                console.log(`[SpecificationImportService] Error creating environment:`, error);
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
                        vscode.window.showErrorMessage(error instanceof Error ? error.message : 'Protocol validation failed');
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
            console.log(`[SpecificationImportService] Specification import failed:`, error);

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
                createdWhen: Date.now(),
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
                console.log(`[SpecificationImportService] Error converting file ${serializedFile.name}:`, error);
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
                        console.log(`[SpecificationImportService] Protocol detected: "${protocol}"`);
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

            console.log(`[SpecificationImportService] Saving specification files for group: ${specificationGroup.name}`);

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
                        createdWhen: specification.createdWhen,
                        modifiedWhen: specification.modifiedWhen,
                        createdBy: specification.createdBy,
                        modifiedBy: specification.modifiedBy,
                        deprecated: specification.deprecated,
                        version: specification.version,
                        source: "MANUAL",
                        operations: specification.operations || [],
                        specificationSources: await Promise.all(extractedFiles.map(async (file, index) => ({
                            id: crypto.randomUUID(),
                            name: file.name,
                            createdWhen: Date.now(),
                            modifiedWhen: Date.now(),
                            createdBy: { id: "", username: "" },
                            modifiedBy: { id: "", username: "" },
                            sourceHash: this.calculateHash(await this.readFileContent(file)),
                            fileName: `source-${specification.id}/${file.name}`,
                            mainSource: file === sourceFile
                        }))),
                        parentId: specificationGroup.id,
                        labels: specification.labels ? LabelUtils.fromEntityLabels(specification.labels) : []
                    }
                };

                console.log(`[SpecificationImportService] Created QIP specification with ${Array.isArray(qipSpecification.content.operations) ? qipSpecification.content.operations.length : 0} operations`);

                const yaml = require('yaml');
                // Disable anchors to avoid "Excessive alias count" error when parsing large specifications
                const yamlContent = yaml.stringify(qipSpecification, {
                    aliasDuplicateObjects: false
                });
                const bytes = new TextEncoder().encode(yamlContent);
                await fileApi.writeFile(specFileUri, bytes);
                console.log(`[SpecificationImportService] Saved specification file: ${specFileName}`);

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

            console.log(`[SpecificationImportService] All specification files saved successfully`);
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
            console.log(`[SpecificationImportService] Copied source file: ${sourceFile.name} to ${sourceFolderName}/`);
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
            let address: string;
            const specData = await this.extractSpecificationData(files);

            if (specData) {
                const extractedAddress = this.specificationProcessorService.extractAddressFromSpecification(specData);
                if (extractedAddress) {
                    address = extractedAddress;
                } else {
                    address = this.getDefaultAddressForProtocol(system.protocol);
                }
            } else {
                address = this.getDefaultAddressForProtocol(system.protocol);
            }

            let environmentName = `Environment for ${specificationGroup.name}`;
            if (system.protocol?.toLowerCase() === 'soap' && specData && specData.service && specData.service.portName) {
                environmentName = specData.service.portName;
            }

            const environmentRequest: EnvironmentRequest = {
                name: environmentName,
                address: address,
                description: `Environment created for ${specificationGroup.name} specification group`,
                systemId: systemId,
                isActive: false
            };

            const environment = await this.environmentService.createEnvironment(environmentRequest);

            console.log(`[SpecificationImportService] Environment created successfully:`, {
                id: environment.id,
                name: environment.name,
                address: environment.address
            });

        } catch (error) {
            console.log(`[SpecificationImportService] Error creating environment:`, error);
        }
    }

    /**
     * Extract specification data from files
     */
    private async extractSpecificationData(files: File[]): Promise<any> {
        for (const file of files) {
            try {
                const content = await file.text();
                if (content) {
                    // Try JSON first, then YAML
                    return ContentParser.parseContent(content);
                }
            } catch (error) {
                console.log(`[SpecificationImportService] Error reading file ${file.name}:`, error);
            }
        }
        return null;
    }

    /**
     * Get default address for protocol
     */
    private getDefaultAddressForProtocol(protocol?: string): string {
        switch (protocol?.toUpperCase()) {
            case 'HTTP':
            case 'HTTPS':
                return 'https://api.example.com';
            case 'SOAP':
                return 'https://soap.example.com/ws';
            case 'GRAPHQL':
                return 'https://graphql.example.com/graphql';
            case 'GRPC':
                return 'grpc://grpc.example.com:9090';
            case 'AMQP':
            case 'RABBIT':
                return 'amqp://localhost:5672';
            case 'KAFKA':
                return 'kafka://localhost:9092';
            case 'MQTT':
                return 'mqtt://localhost:1883';
            case 'REDIS':
                return 'redis://localhost:6379';
            case 'NATS':
                return 'nats://localhost:4222';
            default:
                return 'https://api.example.com';
        }
    }

    /**
     * Convert string protocol to ApiSpecificationType
     */
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
}
