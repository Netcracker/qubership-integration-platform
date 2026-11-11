package org.qubership.integration.platform.ai.a2a.access;

import java.util.Objects;

/**
 * Resolved caller identity for A2A Task operations.
 *
 * <p>Frozen by prompt 04. Prompt 07 may swap providers without changing this record.
 */
public record CallerContext(String tenantId, String subjectId) {

  public CallerContext {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    if (tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId must not be blank");
    }
    if (subjectId.isBlank()) {
      throw new IllegalArgumentException("subjectId must not be blank");
    }
  }
}
