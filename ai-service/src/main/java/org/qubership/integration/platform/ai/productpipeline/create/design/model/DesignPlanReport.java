package org.qubership.integration.platform.ai.productpipeline.create.design.model;

/** Exact Markdown process report emitted by cip-design-planner. */
public record DesignPlanReport(String schemaVersion, String markdown) {

  public DesignPlanReport {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    markdown = DesignArtifacts.requireText(markdown, "markdown");
  }
}
