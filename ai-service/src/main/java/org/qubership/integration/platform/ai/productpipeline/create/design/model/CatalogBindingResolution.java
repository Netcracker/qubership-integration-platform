package org.qubership.integration.platform.ai.productpipeline.create.design.model;

/** One resolved catalog or APIHub binding for an outbound service-call step. */
public record CatalogBindingResolution(
    String serviceCallStepId,
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
    APIHUB_IMPORT,
    UPLOADED_SPEC
  }

  public CatalogBindingResolution {
    serviceCallStepId = DesignArtifacts.requireText(serviceCallStepId, "serviceCallStepId");
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
