package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;

/** Operation portion of resolved catalog bindings for outbound service calls. */
public record ApiOperationBindings(String schemaVersion, List<Binding> bindings) {

  public ApiOperationBindings {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    bindings = DesignArtifacts.copyList(bindings);
  }

  public record Binding(
      String serviceCallStepId,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      String packageId,
      String release) {

    public Binding {
      serviceCallStepId = DesignArtifacts.requireText(serviceCallStepId, "serviceCallStepId");
      systemId = DesignArtifacts.requireText(systemId, "systemId");
      specificationGroupId =
          DesignArtifacts.requireText(specificationGroupId, "specificationGroupId");
      specificationId = DesignArtifacts.requireText(specificationId, "specificationId");
      integrationOperationId =
          DesignArtifacts.requireText(integrationOperationId, "integrationOperationId");
      packageId = DesignArtifacts.nullableTrimmed(packageId);
      release = DesignArtifacts.requireText(release, "release");
    }
  }
}
