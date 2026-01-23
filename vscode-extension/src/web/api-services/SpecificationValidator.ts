import Ajv from 'ajv';
import addFormats from 'ajv-formats';
import {ApiSpecificationType} from "../api-services/importApiTypes";
import {
    QIP_SCHEMAS,
    QipSchemaType,
    ValidationResult,
    ValidationError,
    getQipSchemaType,
    isQipSchema
} from "../services/qipSchemas";
import {FileParserService} from "../services/FileParserService";

export class SpecificationValidator {
    private static ajv: Ajv;

    static {
        // Initialize AJV for JSON Schema validation
        this.ajv = new Ajv({allErrors: true});
        addFormats(this.ajv);

        this.ajv.addSchema(QIP_SCHEMAS.SPECIFICATION, 'specification');
        this.ajv.addSchema(QIP_SCHEMAS.SPECIFICATION_GROUP, 'specification-group');
        this.ajv.addSchema(QIP_SCHEMAS.SERVICE, 'service');
        this.ajv.addSchema(QIP_SCHEMAS.CHAIN, 'chain');
    }

    /**
     * Validates OpenAPI/Swagger specification from file
     */
    static async validateOpenApiSpecFromFile(file: File): Promise<boolean> {
        try {
            const {content} = await FileParserService.parseFileContent(file);
            this.validateOpenApiSpec(content);
            return true;
        } catch (error) {
            return false;
        }
    }

    /**
     * Validates QIP specification by JSON Schema
     */
    static validateQipSpecification(file: File): Promise<ValidationResult> {
        return new Promise(async (resolve) => {
            try {
                if (!FileParserService.hasTextMethod(file)) {
                    resolve({
                        valid: false,
                        errors: [{path: 'file', message: 'File.text() method not available'}]
                    });
                    return;
                }

                const {content} = await FileParserService.parseFileContent(file);

                // Check for $schema presence
                if (!content || !content.$schema) {
                    resolve({
                        valid: false,
                        errors: [{path: '$schema', message: 'Missing $schema field'}]
                    });
                    return;
                }

                // Determine schema type
                const schemaType = getQipSchemaType(content.$schema);
                if (!schemaType) {
                    resolve({
                        valid: false,
                        errors: [{path: '$schema', message: `Unknown QIP schema: ${content.$schema}`}]
                    });
                    return;
                }

                // Ensure AJV is initialized
                if (!this.ajv) {
                    this.ajv = new Ajv({allErrors: true});
                    addFormats(this.ajv);
                    this.ajv.addSchema(QIP_SCHEMAS.SPECIFICATION, 'specification');
                    this.ajv.addSchema(QIP_SCHEMAS.SPECIFICATION_GROUP, 'specification-group');
                    this.ajv.addSchema(QIP_SCHEMAS.SERVICE, 'service');
                    this.ajv.addSchema(QIP_SCHEMAS.CHAIN, 'chain');
                }

                // Validate by schema
                const validate = this.ajv.getSchema(schemaType.toLowerCase());
                if (!validate) {
                    resolve({
                        valid: false,
                        errors: [{path: 'schema', message: `Schema validator not found for type: ${schemaType}`}]
                    });
                    return;
                }

                const valid = validate(content) as boolean;
                const errors: ValidationError[] = [];

                if (!valid && validate.errors) {
                    errors.push(...validate.errors.map(err => ({
                        path: err.instancePath || err.schemaPath || 'root',
                        message: err.message || 'Validation error',
                        data: err.data
                    })));
                }

                resolve({
                    valid: valid || false,
                    errors,
                    schemaType
                });

            } catch (error) {
                resolve({
                    valid: false,
                    errors: [{
                        path: 'file',
                        message: `Parse error: ${error instanceof Error ? error.message : 'Unknown error'}`
                    }]
                });
            }
        });
    }

    /**
     * Checks if file is QIP specification
     */
    static async isQipSpecification(file: File): Promise<boolean> {
        try {
            if (!FileParserService.hasTextMethod(file)) {
                return false;
            }

            const {content} = await FileParserService.parseFileContent(file);
            return !!(content && content.$schema && isQipSchema(content.$schema));
        } catch (error) {
            return false;
        }
    }

    /**
     * Gets QIP specification type
     */
    static async getQipSpecificationType(file: File): Promise<QipSchemaType | null> {
        try {
            if (!FileParserService.hasTextMethod(file)) {
                return null;
            }

            const {content} = await FileParserService.parseFileContent(file);
            return getQipSchemaType(content.$schema);
        } catch (error) {
            return null;
        }
    }

    /**
     * Validates all QIP files in list
     */
    static async validateQipFiles(files: File[]): Promise<Map<string, ValidationResult>> {
        const results = new Map<string, ValidationResult>();

        for (const file of files) {
            if (await this.isQipSpecification(file)) {
                const validation = await this.validateQipSpecification(file);
                results.set(file.name, validation);
            }
        }

        return results;
    }

    /**
     * Gets validation statistics
     */
    static getValidationStats(results: Map<string, ValidationResult>): {
        total: number;
        valid: number;
        invalid: number;
        byType: Record<string, number>;
    } {
        const stats = {
            total: results.size,
            valid: 0,
            invalid: 0,
            byType: {} as Record<string, number>
        };

        for (const result of results.values()) {
            if (result.valid) {
                stats.valid++;
            } else {
                stats.invalid++;
            }

            if (result.schemaType) {
                stats.byType[result.schemaType] = (stats.byType[result.schemaType] || 0) + 1;
            }
        }

        return stats;
    }

    /**
     * Validates specification protocol
     */
    private static readonly asyncProtocols = new Set<ApiSpecificationType>([
        ApiSpecificationType.ASYNC,
        ApiSpecificationType.AMQP,
        ApiSpecificationType.MQTT,
        ApiSpecificationType.KAFKA,
        ApiSpecificationType.REDIS,
        ApiSpecificationType.NATS
    ]);

    static validateSpecificationProtocol(systemProtocol: ApiSpecificationType | undefined, importingProtocol: ApiSpecificationType): void {
        if (!systemProtocol) {
            return;
        }

        if (systemProtocol === importingProtocol) {
            return;
        }

        if (this.asyncProtocols.has(systemProtocol) && this.asyncProtocols.has(importingProtocol)) {
            return;
        }

        throw new Error(
            `Protocol mismatch: Cannot import ${importingProtocol} specification into ${systemProtocol} service. ` +
            `The specification protocol (${importingProtocol}) must match the service protocol (${systemProtocol}).`
        );
    }

    /**
     * Validates OpenAPI specification structure
     */
    private static validateOpenApiSpec(spec: any): void {
        if (!spec || typeof spec !== 'object') {
            throw new Error('Invalid specification: must be an object');
        }

        if (!spec.info) {
            throw new Error('Invalid specification: missing "info" field');
        }

        if (!spec.info.title) {
            throw new Error('Invalid specification: missing "info.title" field');
        }

        if (!spec.info.version) {
            throw new Error('Invalid specification: missing "info.version" field');
        }

        // Check OpenAPI version
        const isOpenApi3 = spec.openapi && spec.openapi.startsWith('3.');
        const isSwagger2 = spec.swagger && spec.swagger.startsWith('2.');

        if (!isOpenApi3 && !isSwagger2) {
            throw new Error('Invalid specification: must be OpenAPI 3.x or Swagger 2.x');
        }

        // Check for paths
        if (!spec.paths || typeof spec.paths !== 'object') {
            throw new Error('Invalid specification: missing or invalid "paths" field');
        }

        // Check that there is at least one path
        const pathKeys = Object.keys(spec.paths);
        if (pathKeys.length === 0) {
            throw new Error('Invalid specification: no paths defined');
        }

        // Check path structure
        for (const path of pathKeys) {
            const pathItem = spec.paths[path];
            if (!pathItem || typeof pathItem !== 'object') {
                throw new Error(`Invalid specification: invalid path item for "${path}"`);
            }

            // Check HTTP methods
            const httpMethods = ['get', 'post', 'put', 'delete', 'patch', 'head', 'options'];
            const hasValidMethod = httpMethods.some(method => pathItem[method]);

            if (!hasValidMethod) {
                throw new Error(`Invalid specification: no valid HTTP methods found for path "${path}"`);
            }
        }
    }
}
