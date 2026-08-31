package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
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

  /**
   * Producer id of every artifact an edit run seeds before any skill has run.
   *
   * <p>An edit seeds CHAIN_STRUCTURE with the imported graph so downstream skills have a starting
   * shape. That makes the seed indistinguishable from a real capture unless the producer is
   * checked, which is how a failed structure stage used to pass for a successful one.
   */
  public static final String SEED_PRODUCER = "chain-edit-seed";

  /** Producer id of semantic revision and compiled graph artifacts on the CREATE seed. */
  public static final String SEMANTIC_COMPILER_PRODUCER = "chain-semantic-compiler";

  /** The skill CREATE marks satisfied before the compiler DAG starts. */
  public static final String REQUIREMENT_ANALYZER_SKILL = "cip-requirement-analyzer";

  /** Structure is already projected from the compiled graph, so CREATE does not run this skill. */
  public static final String STRUCTURE_GENERATOR_SKILL = "cip-structure-generator";

  /** Pattern is projected from the compiled graph, so CREATE does not run this skill. */
  public static final String PATTERN_SELECTOR_SKILL = "cip-pattern-selector";

  /** Triggers are projected from the compiled graph, so CREATE does not run this skill. */
  public static final String TRIGGER_GENERATOR_SKILL = "cip-trigger-generator";

  /** Upstream CREATE skills that a property-only edit never runs. */
  public static final Set<String> EDIT_PRE_SATISFIED_SKILLS =
      Set.of(
          REQUIREMENT_ANALYZER_SKILL,
          PATTERN_SELECTOR_SKILL,
          "cip-naming-generator",
          TRIGGER_GENERATOR_SKILL,
          STRUCTURE_GENERATOR_SKILL);

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
                new SkillArtifactPayload.RequirementBriefPayload(brief)),
            SkillArtifact.of(
                SkillArtifactType.SERVICE_CALL_BINDINGS,
                SEMANTIC_COMPILER_PRODUCER,
                new SkillArtifactPayload.ServiceCallBindingsPayload(List.of()))),
        Set.of(REQUIREMENT_ANALYZER_SKILL));
  }

  /**
   * CREATE seed after design-execution compiled the semantic revision. The workspace already holds
   * the revision, the compiled graph, structure, pattern, and trigger set, so {@code
   * cip-structure-generator}, {@code cip-pattern-selector}, and {@code cip-trigger-generator} do
   * not run again.
   */
  public static CompilerExecutionSeed forCreate(
      String conversationId,
      RequirementBrief brief,
      ChainSemanticRevision revision,
      ChainPlanGraph graph,
      List<ResolvedServiceCallBinding> bindings) {
    Objects.requireNonNull(brief, "requirementBrief");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(graph, "graph");
    String text = planningSeedText(brief);
    SelectedPattern pattern = CompilerCreateSeedProjector.pattern(graph);
    ElementSkeleton skeleton = CompilerCreateSeedProjector.skeleton(graph, pattern.patternId());
    ConfiguredTriggerSet triggerSet = CompilerCreateSeedProjector.triggerSet(graph);
    return new CompilerExecutionSeed(
        conversationId,
        false,
        text,
        List.of(
            SkillArtifact.of(
                SkillArtifactType.RAW_USER_REQUEST,
                REQUIREMENT_ANALYZER_SKILL,
                new SkillArtifactPayload.RawUserRequestPayload(text, List.of())),
            SkillArtifact.of(
                SkillArtifactType.REQUIREMENT_BRIEF,
                REQUIREMENT_ANALYZER_SKILL,
                new SkillArtifactPayload.RequirementBriefPayload(brief)),
            SkillArtifact.of(
                SkillArtifactType.CHAIN_SEMANTIC_REVISION,
                SEMANTIC_COMPILER_PRODUCER,
                new SkillArtifactPayload.ChainSemanticRevisionPayload(revision)),
            SkillArtifact.of(
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                SEMANTIC_COMPILER_PRODUCER,
                new SkillArtifactPayload.ChainPlanGraphPayload(graph)),
            SkillArtifact.of(
                SkillArtifactType.CHAIN_STRUCTURE,
                SEMANTIC_COMPILER_PRODUCER,
                new SkillArtifactPayload.ChainStructurePayload(
                    new ChainStructure(graph, List.of(), List.of()))),
            SkillArtifact.of(
                SkillArtifactType.SELECTED_PATTERN,
                SEMANTIC_COMPILER_PRODUCER,
                new SkillArtifactPayload.SelectedPatternPayload(pattern)),
            SkillArtifact.of(
                SkillArtifactType.ELEMENT_SKELETON,
                SEMANTIC_COMPILER_PRODUCER,
                new SkillArtifactPayload.ElementSkeletonPayload(skeleton)),
            SkillArtifact.of(
                SkillArtifactType.CONFIGURED_TRIGGER_SET,
                SEMANTIC_COMPILER_PRODUCER,
                new SkillArtifactPayload.ConfiguredTriggerSetPayload(triggerSet)),
            SkillArtifact.of(
                SkillArtifactType.SERVICE_CALL_BINDINGS,
                SEMANTIC_COMPILER_PRODUCER,
                new SkillArtifactPayload.ServiceCallBindingsPayload(bindings))),
        Set.of(
            REQUIREMENT_ANALYZER_SKILL,
            STRUCTURE_GENERATOR_SKILL,
            PATTERN_SELECTOR_SKILL,
            TRIGGER_GENERATOR_SKILL));
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
            SEED_PRODUCER,
            new SkillArtifactPayload.RawUserRequestPayload(text, List.of())));
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            SEED_PRODUCER,
            new SkillArtifactPayload.ChainPlanGraphPayload(importedGraph)));
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_STRUCTURE,
            SEED_PRODUCER,
            new SkillArtifactPayload.ChainStructurePayload(
                new ChainStructure(importedGraph, List.of(), List.of()))));
    if (materializationMap != null) {
      artifacts.add(
          SkillArtifact.of(
              SkillArtifactType.MATERIALIZATION_MAP,
              SEED_PRODUCER,
              new SkillArtifactPayload.MaterializationMapPayload(materializationMap)));
    }
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_EDIT_INTENT,
            SEED_PRODUCER,
            new SkillArtifactPayload.ChainEditIntentPayload(intent)));
    artifacts.add(
        SkillArtifact.of(
            SkillArtifactType.SERVICE_CALL_BINDINGS,
            SEED_PRODUCER,
            new SkillArtifactPayload.ServiceCallBindingsPayload(
                bindings == null ? List.of() : bindings)));
    LinkedHashSet<String> preSatisfied = new LinkedHashSet<>(EDIT_PRE_SATISFIED_SKILLS);
    if (intent.requiresStructureStage()) {
      preSatisfied.remove(STRUCTURE_GENERATOR_SKILL);
    }
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

  /**
   * Artifact types this seed already holds that a completed compile must still present.
   *
   * <p>MATERIALIZATION_MAP is the catalog ownership join. CREATE compile does not produce it. An
   * edit that started with the map must still have it when the run finishes.
   */
  public Set<String> retainedArtifactTypes() {
    if (presentArtifactTypes().contains(SkillArtifactType.MATERIALIZATION_MAP.name())) {
      return Set.of(SkillArtifactType.MATERIALIZATION_MAP.name());
    }
    return Set.of();
  }

  private static String planningSeedText(RequirementBrief brief) {
    if (brief.approvedDraftText() != null && !brief.approvedDraftText().isBlank()) {
      return brief.approvedDraftText();
    }
    return RequirementBriefText.format(brief);
  }
}
