package org.qubership.integration.platform.ai.compiler;

import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/**
 * Prompt-time snapshot of workspace inputs for a compiler skill run.
 *
 * <p>{@code editContext} is empty on a CREATE run. On an edit it carries the typed intent and any
 * resolved catalog binding, so a generator configuring an element it did not place reads the
 * operation's real id, method and path rather than recalling them.
 */
public record CompilerSkillInputSnapshot(
    String rawUserRequest,
    String requirementBrief,
    String selectedPatternId,
    ChainPlanGraph chainPlanGraph,
    String generatorPlanManifestSummary,
    String editContext) {

  public CompilerSkillInputSnapshot(
      String rawUserRequest,
      String requirementBrief,
      String selectedPatternId,
      ChainPlanGraph chainPlanGraph,
      String generatorPlanManifestSummary) {
    this(
        rawUserRequest,
        requirementBrief,
        selectedPatternId,
        chainPlanGraph,
        generatorPlanManifestSummary,
        null);
  }
}
