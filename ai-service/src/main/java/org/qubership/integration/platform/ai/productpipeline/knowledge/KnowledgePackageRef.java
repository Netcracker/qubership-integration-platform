package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.Objects;

/** Immutable reference to one certified knowledge package selected by the sidecar. */
public record KnowledgePackageRef(
    String packageKey,
    String knowledgeVersion,
    String schemaVersion,
    String packageChecksum,
    String certificationStatus,
    String certificationDigest) {

  public KnowledgePackageRef {
    requireText(packageKey, "packageKey");
    requireText(knowledgeVersion, "knowledgeVersion");
    requireText(schemaVersion, "schemaVersion");
    requireText(packageChecksum, "packageChecksum");
    if (!"CERTIFIED".equals(certificationStatus)) {
      throw new IllegalArgumentException("certificationStatus must be CERTIFIED");
    }
    requireText(certificationDigest, "certificationDigest");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
