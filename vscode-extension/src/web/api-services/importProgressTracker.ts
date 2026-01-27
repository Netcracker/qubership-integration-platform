import { ImportSpecificationResult } from "./importApiTypes";

export class ImportProgressTracker {
  private static instance: ImportProgressTracker;
  private importSessions: Map<string, ImportSpecificationResult> = new Map();

  static getInstance(): ImportProgressTracker {
    if (!ImportProgressTracker.instance) {
      ImportProgressTracker.instance = new ImportProgressTracker();
    }
    return ImportProgressTracker.instance;
  }

  startImportSession(importId: string, specificationGroupId: string): void {
    const session: ImportSpecificationResult = {
      id: importId,
      done: false,
      specificationGroupId: specificationGroupId,
    };
    this.importSessions.set(importId, session);
  }

  completeImportSession(
    importId: string,
    result: Partial<ImportSpecificationResult>,
  ): void {
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
}
