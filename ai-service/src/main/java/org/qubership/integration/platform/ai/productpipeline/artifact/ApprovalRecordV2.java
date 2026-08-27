package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/** Binds approval to one target, an exact approved candidate subset, and a full semantic pin. */
public record ApprovalRecordV2(
    CompilationArtifacts.Reference target,
    String targetContentHash,
    List<CompilationArtifacts.Reference> approvedCandidates,
    String actor,
    String comment,
    Instant approvedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) String bindingResolutionPolicy,
    @JsonInclude(JsonInclude.Include.NON_NULL) String bindingResolutionPolicyHash,
    String subjectArtifactKind,
    String subjectSchemaVersion,
    String subjectRevisionId,
    String subjectSha256,
    String compilerContractVersion,
    String compilerContractSha256) {

  public ApprovalRecordV2 {
    approvedCandidates =
        approvedCandidates == null ? List.of() : List.copyOf(approvedCandidates);
  }
}
