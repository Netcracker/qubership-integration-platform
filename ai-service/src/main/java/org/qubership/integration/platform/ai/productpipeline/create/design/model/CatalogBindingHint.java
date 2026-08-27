package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Read-only catalog match captured during requirement discovery before implementation approval.
 */
public record CatalogBindingHint(
    String schemaVersion,
    @JsonAlias("serviceCallSourceFactId") String serviceCallId,
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

  /** Compatibility constructor for v1 hints that keyed the call by source fact id. */
  public CatalogBindingHint(
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
    this(
        schemaVersion,
        serviceCallSourceFactId,
        serviceCallSourceFactId,
        operationQuery,
        systemId,
        specificationGroupId,
        specificationId,
        integrationOperationId,
        null,
        null,
        null,
        release,
        observedAt,
        evidenceRef);
  }

  public static CatalogBindingHint from(
      RequirementServiceCall call,
      CatalogBindingMatcher.CatalogMatch match,
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

  /** v1 JSON and older callers still read this name; same value as {@link #serviceCallId()}. */
  @JsonIgnore
  @Deprecated
  public String serviceCallSourceFactId() {
    return serviceCallId;
  }

  private static String operationQuery(
      RequirementServiceCall call, CatalogBindingMatcher.CatalogMatch match) {
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
