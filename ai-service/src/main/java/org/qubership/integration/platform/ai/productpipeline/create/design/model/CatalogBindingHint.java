package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.time.Instant;

/**
 * Read-only catalog match captured during requirement discovery before implementation approval.
 */
public record CatalogBindingHint(
    String schemaVersion,
    String serviceCallSourceFactId,
    String operationQuery,
    String systemId,
    String specificationGroupId,
    String specificationId,
    String integrationOperationId,
    String release,
    Instant observedAt,
    String evidenceRef) {

  public CatalogBindingHint {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    serviceCallSourceFactId =
        DesignArtifacts.requireText(serviceCallSourceFactId, "serviceCallSourceFactId");
    operationQuery = DesignArtifacts.requireText(operationQuery, "operationQuery");
    systemId = DesignArtifacts.requireText(systemId, "systemId");
    specificationGroupId =
        DesignArtifacts.requireText(specificationGroupId, "specificationGroupId");
    specificationId = DesignArtifacts.requireText(specificationId, "specificationId");
    integrationOperationId =
        DesignArtifacts.requireText(integrationOperationId, "integrationOperationId");
    release = DesignArtifacts.requireText(release, "release");
    observedAt = DesignArtifacts.requireNonNull(observedAt, "observedAt");
    evidenceRef = DesignArtifacts.requireText(evidenceRef, "evidenceRef");
  }
}
