import { ExtensionContext, Uri } from "vscode";
const vscode = require('vscode');
import {
    ImportSpecificationResult,
    ImportSpecificationGroupRequest,
    SerializedFile,
    ApiSpecificationType,
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
            const specificationGroup = await this.specificationGroupService.createSpecificationGroup(
                system,
                request.name,
                importingProtocol || undefined
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

            this.progressTracker.startImportSession(importId, specificationGroup.id);

            const extractedFiles = await this.convertSerializedFilesToFiles(files);
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
            // Check for AsyncAPI protocol first
            const asyncApiProtocol = await this.specificationProcessorService.detectAsyncApiProtocol(files);
            if (asyncApiProtocol) {
                return asyncApiProtocol;
            }

        for (const file of files) {
                const fileName = file.name.toLowerCase();
                if (fileName.includes('openapi') || fileName.includes('swagger')) {
                    return 'HTTP';
                } else if (fileName.includes('graphql')) {
                return 'GRAPHQL';
                } else if (fileName.includes('proto')) {
                    return 'GRPC';
                } else if (fileName.includes('wsdl')) {
                    return 'SOAP';
                }
            }

            return asyncApiProtocol || 'HTTP';

        } catch (error) {
            return 'UNKNOWN';
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
     * Detect AsyncAPI protocol from files using AsyncApiSpecificationParser
     */
    async detectAsyncApiProtocol(files: File[]): Promise<string | null> {
        try {
            for (const file of files) {
                if (file.name.includes('asyncapi') || file.name.endsWith('.yaml') || file.name.endsWith('.yml') || file.name.endsWith('.json')) {
                    const content = await this.readFileContent(file);
                    if (content) {
                        try {
                            const asyncApiData = await AsyncApiSpecificationParser.parseAsyncApiContent(content);
                            const protocol = AsyncApiSpecificationParser.extractAddressFromAsyncApiData(asyncApiData);
                            if (protocol) {
                                return protocol;
                            }
                        } catch (parseError) {
                            console.log(`[SpecificationImportService] Error parsing file content:`, parseError);
                        }
                    }
                }
            }
            return null;
        } catch (error) {
            console.log(`[SpecificationImportService] Error detecting AsyncAPI protocol:`, error);
            return null;
        }
    }

    /**
     * Extract address from specification data using specialized parsers
     */
    extractAddressFromSwaggerData(specData: any): string | null {
        // For SOAP/WSDL files
        if (specData.type === 'WSDL') {
            return SoapSpecificationParser.extractAddressFromWsdlData(specData);
        }

        // For Swagger 2.0 and OpenAPI 3.x
        if (specData.swagger || specData.openapi) {
            return OpenApiSpecificationParser.extractAddressFromOpenApiData(specData);
        }

        // For AsyncAPI
        if (specData.asyncapi) {
            return AsyncApiSpecificationParser.extractAddressFromAsyncApiData(specData);
        }

        return null;
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
                const ext = getExtensionsForFile();
                const specFileName = `${systemId}-${specificationGroup.name}-${specification.version}${ext.specification}`;
                const specFileUri = Uri.joinPath(baseFolder, specFileName);

                // Create QIP specification using operations from SpecificationProcessorService
                const qipSpecification = {
                    $schema: "http://qubership.org/schemas/product/qip/specification",
                    id: specification.id,
                    name: specification.name,
                    content: {
                        createdWhen: specification.createdWhen,
                        modifiedWhen: specification.modifiedWhen,
                        createdBy: specification.createdBy,
                        modifiedBy: specification.modifiedBy,
                        deprecated: specification.deprecated,
                        version: specification.version,
                        source: "IMPORTED",
                        operations: specification.operations || [],
                        parentId: specificationGroup.id,
                        labels: specification.labels ? LabelUtils.fromEntityLabels(specification.labels) : []
                    },
                    specificationSources: await Promise.all(extractedFiles.map(async (file, index) => ({
                        id: crypto.randomUUID(),
                        name: file.name,
                        createdWhen: Date.now(),
                        modifiedWhen: Date.now(),
                        createdBy: { id: "", username: "" },
                        modifiedBy: { id: "", username: "" },
                        sourceHash: this.calculateHash(await this.readFileContent(file)),
                        fileName: `resources/source-${specification.id}/${file.name}`,
                        mainSource: file === sourceFile
                    })))
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
                await this.copySourceFileToResources(baseFolder, systemId, specificationGroup.name, specification.version, sourceFile);

                // Copy additional files (like XSD for SOAP)
                if (extractedFiles.length > 1) {
                    const additionalFiles = extractedFiles.filter(f => f !== sourceFile);
                    for (const additionalFile of additionalFiles) {
                        await this.copySourceFileToResources(baseFolder, systemId, specificationGroup.name, specification.version, additionalFile);
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
        systemId: string,
        groupName: string,
        version: string,
        sourceFile: File
    ): Promise<void> {
        try {
            // Create resources folder structure: resources/source-{systemId}-{groupName}-{version}/
            const resourcesFolder = Uri.joinPath(baseFolder, 'resources');
            const sourceFolderName = `source-${systemId}-${groupName}-${version}`;
            const sourceFolder = Uri.joinPath(resourcesFolder, sourceFolderName);

            // Copy file
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
            // Extract address from specification data or use default
            let address: string;
            const specData = await this.extractSpecificationData(files);

            if (specData) {
                const extractedAddress = this.extractAddressFromSwaggerData(specData);
                if (extractedAddress) {
                    address = extractedAddress;
                } else {
                    address = this.getDefaultAddressForProtocol(system.protocol);
                }
            } else {
                address = this.getDefaultAddressForProtocol(system.protocol);
            }

            // Determine environment name
            let environmentName = `Environment for ${specificationGroup.name}`;
            if (system.protocol === 'SOAP' && specData && specData.service && specData.service.portName) {
                environmentName = specData.service.portName;
            }

            // Create environment request
            const environmentRequest: EnvironmentRequest = {
                name: environmentName,
                address: address,
                description: `Environment created for ${specificationGroup.name} specification group`,
                systemId: systemId,
                isActive: false // Don't set as active automatically
            };

            // Create environment using EnvironmentService
            const environment = await this.environmentService.createEnvironment(environmentRequest);

            console.log(`[SpecificationImportService] Environment created successfully:`, {
                id: environment.id,
                name: environment.name,
                address: environment.address
            });

        } catch (error) {
            console.log(`[SpecificationImportService] Error creating environment:`, error);
            // Don't throw error to avoid breaking the import process
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
}
