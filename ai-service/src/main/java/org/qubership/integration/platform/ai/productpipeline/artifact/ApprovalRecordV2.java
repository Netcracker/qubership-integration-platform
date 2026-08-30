package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/** Binds approval to one target and an exact approved candidate subset. */
public record ApprovalRecordV2(
    CompilationArtifacts.Reference target,
    String targetContentHash,
    List<CompilationArtifacts.Reference> approvedCandidates,
    String actor,
    String comment,
    Instant approvedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) String bindingResolutionPolicy,
    @JsonInclude(JsonInclude.Include.NON_NULL) String bindingResolutionPolicyHash,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<String> attachmentKeys) {

  public ApprovalRecordV2 {
    approvedCandidates =
        approvedCandidates == null ? List.of() : List.copyOf(approvedCandidates);
    attachmentKeys = attachmentKeys == null ? List.of() : List.copyOf(attachmentKeys);
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
        List.of());
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
        List.of());
  }
}
