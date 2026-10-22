package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Inputs for the isolated compiler planning spine. */
public record CompilerPlanningRequest(
    String conversationId,
    String runId,
    RequirementBrief requirementBrief,
    IdsBypass idsBypass,
    String languageVersion,
    List<String> dependencyClosure,
    List<String> expectedSkillOrder,
    String attemptId) {

  public CompilerPlanningRequest {
    Objects.requireNonNull(conversationId, "conversationId");
    // An edit run has no requirement brief: the chain it edits already exists, and fabricating one
    // would smuggle invented requirements into a generator as though a reader had approved them.
    dependencyClosure = dependencyClosure == null ? List.of() : List.copyOf(dependencyClosure);
    expectedSkillOrder = expectedSkillOrder == null ? List.of() : List.copyOf(expectedSkillOrder);
    languageVersion =
        languageVersion == null || languageVersion.isBlank() ? "24.4" : languageVersion.trim();
  }
}
