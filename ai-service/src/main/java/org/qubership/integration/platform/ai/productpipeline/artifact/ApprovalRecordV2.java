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
    @JsonInclude(JsonInclude.Include.NON_NULL) String subjectArtifactKind,
    @JsonInclude(JsonInclude.Include.NON_NULL) String subjectSchemaVersion,
    @JsonInclude(JsonInclude.Include.NON_NULL) String subjectRevisionId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String subjectSha256,
    @JsonInclude(JsonInclude.Include.NON_NULL) String compilerContractVersion,
    @JsonInclude(JsonInclude.Include.NON_NULL) String compilerContractSha256) {

  public ApprovalRecordV2 {
    approvedCandidates =
        approvedCandidates == null ? List.of() : List.copyOf(approvedCandidates);
  }

  /** Compatibility constructor for approvals without binding-resolution policy metadata. */
  public ApprovalRecordV2(
      CompilationArtifacts.Reference target,
      String targetContentHash,
      List<CompilationArtifacts.Reference> approvedCandidates,
      String actor,
      String comment,
      Instant approvedAt) {
    this(
        target,
        targetContentHash,
        approvedCandidates,
        actor,
        comment,
        approvedAt,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public ApprovalRecordV2(
      CompilationArtifacts.Reference target,
      String targetContentHash,
      List<CompilationArtifacts.Reference> approvedCandidates,
      String actor,
      String comment,
      Instant approvedAt,
      String bindingResolutionPolicy,
      String bindingResolutionPolicyHash) {
    this(
        target,
        targetContentHash,
        approvedCandidates,
        actor,
        comment,
        approvedAt,
        bindingResolutionPolicy,
        bindingResolutionPolicyHash,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
