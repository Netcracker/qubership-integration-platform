import { ExtensionContext } from "vscode";
import { SpecificationImportService } from "./SpecificationImportService";
import {
    ImportSpecificationResult,
    ImportSpecificationGroupRequest,
    SerializedFile
} from "./importApiTypes";
import { Uri } from "vscode";

export class SpecificationImportApiHandler {
    private service: SpecificationImportService;

    constructor(context: ExtensionContext, serviceFileUri?: Uri) {
        this.service = new SpecificationImportService(context, serviceFileUri);
    }

    async handleImportSpecificationGroup(request: ImportSpecificationGroupRequest): Promise<ImportSpecificationResult> {
        try {
            const result = await this.service.importSpecificationGroup(request);
            console.log(`[SpecificationImportApiHandler] Import completed successfully:`, {
                importId: result.id,
                specificationGroupId: result.specificationGroupId,
                done: result.done
            });
            return result;
        } catch (error) {
            console.error(`[SpecificationImportApiHandler] Import failed:`, error);
            throw error;
        }
    }

    async handleImportSpecification(specificationGroupId: string, files: SerializedFile[], systemId: string): Promise<ImportSpecificationResult> {
        console.log(`[SpecificationImportApiHandler] Handling import specification request`);
        console.log(`[SpecificationImportApiHandler] Request details:`, {
            specificationGroupId,
            systemId,
            filesCount: files?.length || 0
        });
        
        try {
            const result = await this.service.importSpecification(specificationGroupId, files, systemId);
            console.log(`[SpecificationImportApiHandler] Specification import completed successfully:`, {
                importId: result.id,
                specificationGroupId: result.specificationGroupId,
                done: result.done
            });
            return result;
        } catch (error) {
            console.error(`[SpecificationImportApiHandler] Specification import failed:`, error);
            throw error;
        }
    }

    async handleGetImportResult(importId: string): Promise<ImportSpecificationResult> {
        console.log(`[SpecificationImportApiHandler] Getting import result for ID: ${importId}`);
        
        try {
            const result = await this.service.getImportSpecificationResult(importId);
            console.log(`[SpecificationImportApiHandler] Import result retrieved:`, {
                importId: result.id,
                specificationGroupId: result.specificationGroupId,
                done: result.done
            });
            return result;
        } catch (error) {
            console.error(`[SpecificationImportApiHandler] Failed to get import result:`, error);
            throw error;
        }
    }
}
