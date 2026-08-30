package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.time.Instant;
import java.util.Objects;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Read-only catalog match captured during requirement discovery before implementation approval.
 */
public record CatalogBindingHint(
    String schemaVersion,
    String serviceCallId,
    String sourceFactId,
    String operationQuery,
    String systemId,
    String specificationGroupId,
    String specificationId,
    String integrationOperationId,
    String protocol,
    String method,
    String path,
    String release,
    Instant observedAt,
    String evidenceRef) {

  public CatalogBindingHint {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    serviceCallId = DesignArtifacts.requireText(serviceCallId, "serviceCallId");
    sourceFactId =
        sourceFactId == null || sourceFactId.isBlank()
            ? serviceCallId
            : DesignArtifacts.requireText(sourceFactId, "sourceFactId");
    operationQuery = DesignArtifacts.requireText(operationQuery, "operationQuery");
    systemId = DesignArtifacts.requireText(systemId, "systemId");
    specificationGroupId =
        DesignArtifacts.requireText(specificationGroupId, "specificationGroupId");
    specificationId = DesignArtifacts.requireText(specificationId, "specificationId");
    integrationOperationId =
        DesignArtifacts.requireText(integrationOperationId, "integrationOperationId");
    protocol = DesignArtifacts.optionalText(protocol);
    method = DesignArtifacts.optionalText(method);
    path = DesignArtifacts.optionalText(path);
    release = DesignArtifacts.requireText(release, "release");
    observedAt = DesignArtifacts.requireNonNull(observedAt, "observedAt");
    evidenceRef = DesignArtifacts.requireText(evidenceRef, "evidenceRef");
  }

  public static CatalogBindingHint from(
      RequirementServiceCall call,
      CatalogMatch match,
      String release,
      Instant observedAt) {
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(match, "match");
    String operationQuery = operationQuery(call, match);
    return new CatalogBindingHint(
        "2",
        call.serviceCallId(),
        call.sourceFactId(),
        operationQuery,
        match.systemId(),
        match.specificationGroupId(),
        match.specificationId(),
        match.integrationOperationId(),
        match.protocol(),
        match.method(),
        match.path(),
        release,
        observedAt,
        match.evidenceRef());
  }

  private static String operationQuery(
      RequirementServiceCall call, CatalogMatch match) {
    if (match.method() != null && match.path() != null) {
      return match.method() + " " + match.path();
    }
    if (call.operation() != null && !call.operation().isBlank()) {
      return call.operation();
    }
    if (match.operationName() != null && !match.operationName().isBlank()) {
      return match.operationName();
    }
    return "service-call";
  }
}
