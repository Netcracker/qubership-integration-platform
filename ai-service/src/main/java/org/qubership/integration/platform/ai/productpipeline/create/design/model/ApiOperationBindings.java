package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;

/** Operation portion of resolved catalog bindings for outbound service calls. */
public record ApiOperationBindings(String schemaVersion, List<Binding> bindings) {

  public ApiOperationBindings {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    bindings = DesignArtifacts.copyList(bindings);
  }

  public record Binding(
      String serviceCallId,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      String packageId,
      String release) {

    public Binding {
      serviceCallId = DesignArtifacts.requireText(serviceCallId, "serviceCallId");
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
