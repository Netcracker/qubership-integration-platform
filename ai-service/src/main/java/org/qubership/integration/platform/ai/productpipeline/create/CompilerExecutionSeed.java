package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/**
 * Everything a compiler run starts from, stated rather than inherited.
 *
 * <p>The engine used to open the conversation's workspace and assume what it found there: an empty
 * graph, a requirement brief, and a completed requirement analyzer. That assumption is exactly what
 * an edit cannot make — its graph already exists, and its upstream work was done by whoever built
 * the chain. A seed makes both cases the same shape: the artifacts the run begins with, the skills
 * it must not run again, and the workspace it owns.
 *
 * <p>{@code isolated} decides whether the run may see what an earlier run left in that workspace.
 * CREATE replans inside one conversation and reads its own earlier artifacts, so it does not
 * isolate. An edit keys its workspace by run id and clears it first, so nothing from another
 * conversation or another run can reach it.
 */
public record CompilerExecutionSeed(
    String workspaceId,
    boolean isolated,
    String seedText,
    List<SkillArtifact> artifacts,
    Set<String> preSatisfiedSkillIds) {

  /** The skill CREATE marks satisfied before the compiler DAG starts. */
  public static final String REQUIREMENT_ANALYZER_SKILL = "cip-requirement-analyzer";

  /** Upstream CREATE skills an edit never runs: the imported chain already answers them. */
  public static final Set<String> EDIT_PRE_SATISFIED_SKILLS =
      Set.of(
          REQUIREMENT_ANALYZER_SKILL,
          "cip-pattern-selector",
          "cip-naming-generator",
          "cip-trigger-generator",
          "cip-structure-generator");

  public CompilerExecutionSeed {
    workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    seedText = seedText == null ? "" : seedText;
    artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    preSatisfiedSkillIds =
        preSatisfiedSkillIds == null ? Set.of() : Set.copyOf(preSatisfiedSkillIds);
  }

  /** The seed CREATE has always run with, now written down. */
  public static CompilerExecutionSeed forCreate(String conversationId, RequirementBrief brief) {
    Objects.requireNonNull(brief, "requirementBrief");
    String text = planningSeedText(brief);
    return new CompilerExecutionSeed(
        conversationId,
        false,
        text,
        List.of(
            SkillArtifact.of(
                SkillArtifactType.RAW_USER_REQUEST,
                "planning-seed",
                new SkillArtifactPayload.RawUserRequestPayload(text, List.of())),
            SkillArtifact.of(
                SkillArtifactType.REQUIREMENT_BRIEF,
                REQUIREMENT_ANALYZER_SKILL,
                new SkillArtifactPayload.RequirementBriefPayload(brief))),
        Set.of(REQUIREMENT_ANALYZER_SKILL));
    }

  /**
   * A run whose starting graph is a chain that already exists.
   *
   * <p>The structure is a projection of the imported graph, not a CREATE plan replayed backwards:
   * an edit that fabricated a requirement brief, a pattern or a naming manifest would let those
   * inventions reach a generator as though a reader had approved them.
   */
  @SuppressWarnings("java:S107")
  public static CompilerExecutionSeed forEdit(
      String workspaceId,
      String userRequest,
      ChainPlanGraph importedGraph,
      MaterializationMap materializationMap,
      ChainEditIntent intent,
      List<ResolvedServiceCallBinding> bindings,
      Set<String> extraPreSatisfiedSkillIds) {
    Objects.requireNonNull(importedGraph, "importedGraph");
    Objects.requireNonNull(intent, "intent");
    String text = userRequest == null ? "" : userRequest;
    List<SkillArtifact> artifacts = new ArrayList<>();
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "chain-edit-seed",
            new SkillArtifactPayload.RawUserRequestPayload(text, List.of())));
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "chain-edit-seed",
            new SkillArtifactPayload.ChainPlanGraphPayload(importedGraph)));
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_STRUCTURE,
            "chain-edit-seed",
            new SkillArtifactPayload.ChainStructurePayload(
                new ChainStructure(importedGraph, List.of(), List.of()))));
    if (materializationMap != null) {
      artifacts.add(
          SkillArtifact.of(
              SkillArtifactType.MATERIALIZATION_MAP,
              "chain-edit-seed",
              new SkillArtifactPayload.MaterializationMapPayload(materializationMap)));
    }
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_EDIT_INTENT,
            "chain-edit-seed",
            new SkillArtifactPayload.ChainEditIntentPayload(intent)));
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.SERVICE_CALL_BINDINGS,
            "chain-edit-seed",
            new SkillArtifactPayload.ServiceCallBindingsPayload(
                bindings == null ? List.of() : bindings)));
    LinkedHashSet<String> preSatisfied = new LinkedHashSet<>(EDIT_PRE_SATISFIED_SKILLS);
    if (extraPreSatisfiedSkillIds != null) {
      preSatisfied.addAll(extraPreSatisfiedSkillIds);
    }
    return new CompilerExecutionSeed(workspaceId, true, text, artifacts, preSatisfied);
  }

  /** The same seed with one more artifact, for callers that scope a run after building it. */
  public CompilerExecutionSeed with(SkillArtifact artifact) {
    List<SkillArtifact> extended = new ArrayList<>(artifacts);
    extended.add(Objects.requireNonNull(artifact, "artifact"));
    return new CompilerExecutionSeed(
        workspaceId, isolated, seedText, extended, preSatisfiedSkillIds);
  }

  /** Artifact type names the run already holds, in scheduler spelling. */
  public Set<String> presentArtifactTypes() {
    LinkedHashSet<String> present = new LinkedHashSet<>();
    for (SkillArtifact artifact : artifacts) {
      if (artifact != null && artifact.type() != null) {
        present.add(artifact.type().name());
      }
    }
    return present;
  }

  private static String planningSeedText(RequirementBrief brief) {
    if (brief.approvedDraftText() != null && !brief.approvedDraftText().isBlank()) {
      return brief.approvedDraftText();
    }
    return RequirementBriefText.format(brief);
  }
}
