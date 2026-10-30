package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/** Binds approval to one exact artifact revision and content hash. */
public record ApprovalRecord(
    CompilationArtifacts.Reference target,
    String targetContentHash,
    String actor,
    String comment,
    Instant approvedAt,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String sourceTurnId) {
  public ApprovalRecord(CompilationArtifacts.Reference target, String targetContentHash, String actor,
      String comment, Instant approvedAt) {
    this(target, targetContentHash, actor, comment, approvedAt, null);
  }
}
