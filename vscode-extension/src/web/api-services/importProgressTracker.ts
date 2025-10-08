import { ExtensionContext } from "vscode";
import { ImportSpecificationResult } from "./importApiTypes";

export class ImportProgressTracker {
    private static instance: ImportProgressTracker;
    private context: ExtensionContext;
    private importSessions: Map<string, ImportSpecificationResult> = new Map();

    private constructor(context: ExtensionContext) {
        this.context = context;
    }

    static getInstance(context: ExtensionContext): ImportProgressTracker {
        if (!ImportProgressTracker.instance) {
            ImportProgressTracker.instance = new ImportProgressTracker(context);
        }
        return ImportProgressTracker.instance;
    }

    startImportSession(importId: string, specificationGroupId: string): void {
        const session: ImportSpecificationResult = {
            id: importId,
            done: false,
            specificationGroupId: specificationGroupId,
            createdWhen: Date.now()
        };
        this.importSessions.set(importId, session);
    }

    completeImportSession(importId: string, result: Partial<ImportSpecificationResult>): void {
        const session = this.importSessions.get(importId);
        if (session) {
            Object.assign(session, result, { done: true });
        }
    }

    failImportSession(importId: string, error: string): void {
        const session = this.importSessions.get(importId);
        if (session) {
            session.done = true;
            session.warningMessage = error;
        }
    }

    getImportSession(importId: string): ImportSpecificationResult | undefined {
        return this.importSessions.get(importId);
    }

    cleanupImportSession(importId: string): void {
        this.importSessions.delete(importId);
    }

    cleanupExpiredSessions(): void {
        const now = Date.now();
        const expirationTime = 15 * 60 * 1000; // 15 minutes

        for (const [importId, session] of this.importSessions.entries()) {
            if (session.done && session.createdWhen && (now - session.createdWhen) > expirationTime) {
                this.importSessions.delete(importId);
            }
        }
    }
}
