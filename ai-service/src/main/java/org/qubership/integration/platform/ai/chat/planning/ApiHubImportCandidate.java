package org.qubership.integration.platform.ai.chat.planning;

import java.time.Instant;

/** ApiHub specification selected for import into runtime-catalog (IMPORT_SPECIFICATION scenario). */
public record ApiHubImportCandidate(
    String candidateId,
    Instant recordedAt,
    String catalogSystemName,
    String catalogSystemType,
    String apiHubPackageId,
    String apiHubVersion,
    String apiHubDocumentId,
    String apiHubSpecificationName,
    String sourceNote) {

  public boolean hasRequiredFields() {
    return catalogSystemName != null
        && !catalogSystemName.isBlank()
        && catalogSystemType != null
        && !catalogSystemType.isBlank()
        && apiHubPackageId != null
        && !apiHubPackageId.isBlank()
        && apiHubVersion != null
        && !apiHubVersion.isBlank()
        && apiHubDocumentId != null
        && !apiHubDocumentId.isBlank()
        && apiHubSpecificationName != null
        && !apiHubSpecificationName.isBlank();
  }
}
