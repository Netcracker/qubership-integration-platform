package org.qubership.integration.platform.ai.productpipeline.create.design.model;

/** Human-readable approval view rendered by the server from a typed plan contract. */
public record DesignPlanReport(
    String schemaVersion, String markdown, String contractId, String contractHash) {

  public DesignPlanReport(String schemaVersion, String markdown) {
    this(schemaVersion, markdown, "", "");
  }

  public DesignPlanReport {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    markdown = DesignArtifacts.requireText(markdown, "markdown");
    contractId = DesignArtifacts.nullableTrimmed(contractId);
    contractHash = DesignArtifacts.nullableTrimmed(contractHash);
  }
}
