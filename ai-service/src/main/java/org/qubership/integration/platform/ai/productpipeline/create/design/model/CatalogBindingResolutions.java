package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;

/** Ordered catalog binding resolutions, one entry per outbound service-call step. */
public record CatalogBindingResolutions(
    String schemaVersion, List<CatalogBindingResolution> resolutions) {

  public CatalogBindingResolutions {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    resolutions = DesignArtifacts.copyList(resolutions);
  }
}
