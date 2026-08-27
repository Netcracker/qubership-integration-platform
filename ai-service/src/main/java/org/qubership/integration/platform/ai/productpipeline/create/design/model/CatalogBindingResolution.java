package org.qubership.integration.platform.ai.productpipeline.create.design.model;

/** One resolved catalog or APIHub binding for an outbound service-call occurrence. */
public record CatalogBindingResolution(
    String serviceCallId,
    Source source,
    String systemId,
    String specificationGroupId,
    String specificationId,
    String integrationOperationId,
    String packageId,
    String release,
    String evidenceRef) {

  public enum Source {
    EXISTING_CATALOG,
    APIHUB_IMPORT
  }

  public CatalogBindingResolution {
    serviceCallId = DesignArtifacts.requireText(serviceCallId, "serviceCallId");
    source = DesignArtifacts.requireNonNull(source, "source");
    systemId = DesignArtifacts.requireText(systemId, "systemId");
    specificationGroupId =
        DesignArtifacts.requireText(specificationGroupId, "specificationGroupId");
    specificationId = DesignArtifacts.requireText(specificationId, "specificationId");
    integrationOperationId =
        DesignArtifacts.requireText(integrationOperationId, "integrationOperationId");
    packageId = DesignArtifacts.nullableTrimmed(packageId);
    release = DesignArtifacts.requireText(release, "release");
    evidenceRef = DesignArtifacts.requireText(evidenceRef, "evidenceRef");
  }
}
