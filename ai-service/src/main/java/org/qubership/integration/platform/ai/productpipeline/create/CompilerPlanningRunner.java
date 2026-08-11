package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.ImplementationPlanRenderer;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementTopologyGuard;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFacts;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFactsService;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/**
 * Isolated compiler planning spine shared by product {@link PlanningCapability} and the legacy
 * orchestrator. Seeds from the canonical requirement brief only — never from conversation
 * transcript. Does not create bundles or call publication services.
 */
@ApplicationScoped
public class CompilerPlanningRunner {

  public static final List<String> DEFAULT_SKILL_ORDER =
      List.of(
          "cip-pattern-selector",
          "cip-chain-generator",
          "generator-plan-manifest",
          "plan-validator");

  private final RequirementTopologyGuard topologyGuard;
  private final PlanPresentationFactsService presentationFactsService;
  private final CompilerPlanningSpine asyncSpine;
  private final Function<CompilerPlanningRequest, PlanningSpineOutcome> syncSpineExecutor;

  /**
   * CDI constructor. Assigns fields here (no {@code this(...)} chaining) so Quarkus Arc records the
   * {@link CompilerPlanningSpine} injection point on the contextual instance.
   */
  @Inject
  public CompilerPlanningRunner(
      RequirementTopologyGuard topologyGuard,
      PlanPresentationFactsService presentationFactsService,
      CompilerPlanningSpine planningSpine) {
    this.topologyGuard = Objects.requireNonNull(topologyGuard, "topologyGuard");
    this.presentationFactsService =
        Objects.requireNonNull(presentationFactsService, "presentationFactsService");
    this.asyncSpine = Objects.requireNonNull(planningSpine, "planningSpine");
    this.syncSpineExecutor = null;
  }

  /** Test helper with a synchronous spine double (avoids CDI {@link CompilerPlanningSpine}). */
  static CompilerPlanningRunner forTests(
      RequirementTopologyGuard topologyGuard,
      PlanPresentationFactsService presentationFactsService,
      Function<CompilerPlanningRequest, PlanningSpineOutcome> spineExecutor) {
    return new CompilerPlanningRunner(
        topologyGuard,
        presentationFactsService,
        Objects.requireNonNull(spineExecutor, "spineExecutor"));
  }

  private CompilerPlanningRunner(
      RequirementTopologyGuard topologyGuard,
      PlanPresentationFactsService presentationFactsService,
      Function<CompilerPlanningRequest, PlanningSpineOutcome> syncSpineExecutor) {
    this.topologyGuard = Objects.requireNonNull(topologyGuard, "topologyGuard");
    this.presentationFactsService =
        Objects.requireNonNull(presentationFactsService, "presentationFactsService");
    this.asyncSpine = null;
    this.syncSpineExecutor = syncSpineExecutor;
  }

  public Multi<CapabilitySignal> plan(CompilerPlanningRequest request) {
    Objects.requireNonNull(request, "request");
    return Multi.createFrom()
        .emitter(
            emitter -> {
              BiConsumer<String, String> skillProgress =
                  (skillId, status) ->
                      emitter.emit(new CapabilitySignal.SkillProgress(skillId, status));
              Uni<PlanningSpineOutcome> spineUni;
              if (asyncSpine != null) {
                spineUni = asyncSpine.execute(request, skillProgress);
              } else if (syncSpineExecutor != null) {
                spineUni = Uni.createFrom().item(() -> syncSpineExecutor.apply(request));
              } else {
                emitter.fail(
                    new IllegalStateException(
                        "CompilerPlanningRunner has no planning spine; CDI must inject"
                            + " CompilerPlanningSpine"));
                return;
              }
              spineUni
                  .subscribe()
                  .with(
                      spine ->
                          finishWithSpine(request, spine)
                              .subscribe()
                              .with(emitter::emit, emitter::fail, emitter::complete),
                      emitter::fail);
            });
  }

  private Multi<CapabilitySignal> finishWithSpine(
      CompilerPlanningRequest request, PlanningSpineOutcome spine) {
    SkillWorkspace workspace = seedWorkspaceFromBrief(request.requirementBrief());
    applySpineArtifacts(workspace, spine);

    List<String> executed = spine.executedSkillIds();
    if (!request.expectedSkillOrder().isEmpty()
        && !startsWithExpectedOrder(executed, request.expectedSkillOrder())) {
      return Multi.createFrom()
          .items(
              new CapabilitySignal.Message("planning skill order mismatch"),
              new CapabilitySignal.Completed(
                  org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome.of(
                      StageOutcomeClass.CONTRACT_FAILURE,
                      "planning skill order mismatch: expected "
                          + request.expectedSkillOrder()
                          + " but executed "
                          + executed)));
    }

    ChainPlanGraph graph = spine.graph();
    String planningSeed = planningSeedText(request.requirementBrief());
    graph = applyForcedElseConditionProperties(planningSeed, graph);
    if (graph != null) {
      workspace.put(
          SkillArtifact.of(
              SkillArtifactType.CHAIN_PLAN_GRAPH,
              "planning-spine",
              new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    }
    List<RequirementFact> facts = request.requirementBrief().facts();
    List<String> exclusions = new ArrayList<>();
    if (spine.selectedPatternSummary() != null) {
      exclusions.addAll(
          topologyGuard.evaluateAfterPatternSelection(facts, spine.selectedPatternSummary()));
    }
    exclusions.addAll(topologyGuard.evaluateAfterGraphCapture(facts, graph));
    exclusions.addAll(topologyGuard.evaluateAfterGeneratorManifest(facts, spine.ownerSkills()));
    exclusions.addAll(elsePropertyExclusions(graph));

    PlanValidationResult validation = buildValidationResult(spine.validationResult(), exclusions);
    if (!validation.approvalEligible()) {
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  new org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome(
                      StageOutcomeClass.VALIDATION_FAILURE,
                      List.of(
                          new org.qubership.integration.platform.ai.productpipeline.capability
                              .ArtifactCandidate(
                              org.qubership.integration.platform.ai.compiler.artifact
                                  .CompilationArtifacts.Kind.PLAN_VALIDATION_RESULT,
                              validation,
                              List.of())),
                      "planning validation failed",
                      null)));
    }

    PlanPresentationFacts presentationFacts = presentationFactsService.build(workspace);
    ImplementationPlan plan =
        ImplementationPlanRenderer.render(
            presentationFacts,
            "planning-capability",
            "1",
            List.of("requirement-brief", "ids-bypass"),
            request.dependencyClosure());
    var coverage = ImplementationPlanRenderer.verifyCoverage(plan);
    if (coverage.isPresent()) {
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome.of(
                      StageOutcomeClass.VALIDATION_FAILURE, coverage.get())));
    }

    CompilerPlanningResult result =
        new CompilerPlanningResult(
            StageOutcomeClass.CANDIDATE,
            plan,
            validation,
            graph,
            null,
            null,
            executed,
            exclusions,
            "planning candidate ready");
    return Multi.createFrom()
        .items(
            new CapabilitySignal.Progress("planning", "completed"),
            new CapabilitySignal.Completed(
                new org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome(
                    StageOutcomeClass.CANDIDATE,
                    List.of(
                        new org.qubership.integration.platform.ai.productpipeline.capability
                            .ArtifactCandidate(
                            org.qubership.integration.platform.ai.compiler.artifact
                                .CompilationArtifacts.Kind.IMPLEMENTATION_PLAN,
                            plan,
                            List.of()),
                        new org.qubership.integration.platform.ai.productpipeline.capability
                            .ArtifactCandidate(
                            org.qubership.integration.platform.ai.compiler.artifact
                                .CompilationArtifacts.Kind.PLAN_VALIDATION_RESULT,
                            validation,
                            List.of())),
                    result.message(),
                    null)));
  }

  /** Seeds an isolated workspace from the canonical brief only (no transcript). */
  public static SkillWorkspace seedWorkspaceFromBrief(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("compiler-planning");
    String planningText =
        brief.approvedDraftText() != null && !brief.approvedDraftText().isBlank()
            ? brief.approvedDraftText()
            : RequirementBriefText.format(brief);
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "planning-seed",
            new SkillArtifactPayload.RawUserRequestPayload(planningText, List.of())));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.REQUIREMENT_BRIEF,
            "planning-seed",
            new SkillArtifactPayload.RequirementBriefPayload(brief)));
    return workspace;
  }

  public static PlanValidationResult buildValidationResult(
      ValidationResult compilerValidation, List<String> exclusionFindings) {
    List<PlanValidationFinding> findings = new ArrayList<>();
    if (compilerValidation != null) {
      if (compilerValidation.issues() != null) {
        for (ValidationIssue issue : compilerValidation.issues()) {
          if (issue == null || issue.severity() != ValidationSeverity.BLOCKER) {
            continue;
          }
          String code = propertyFindingCode(issue.message());
          String message =
              code != null
                  ? code
                      + (issue.message() == null || issue.message().isBlank()
                          ? ""
                          : ": " + issue.message())
                  : issue.message() == null ? "compiler validation blocker" : issue.message();
          findings.add(
              new PlanValidationFinding(code != null ? code : "COMPILER_BLOCKER", message, true));
        }
      }
      if (!compilerValidation.valid()
          && findings.stream().noneMatch(PlanValidationFinding::blocker)) {
        findings.add(
            new PlanValidationFinding(
                "COMPILER_INVALID",
                compilerValidation.summary() == null
                    ? "compiler validation failed"
                    : compilerValidation.summary(),
                true));
      }
    } else if (exclusionFindings == null || exclusionFindings.isEmpty()) {
      findings.add(
          new PlanValidationFinding(
              "COMPILER_MISSING", "compiler validation result is required", true));
    }
    if (exclusionFindings != null) {
      for (String exclusion : exclusionFindings) {
        if (exclusion == null || exclusion.isBlank()) {
          continue;
        }
        String mapped = propertyFindingCode(exclusion);
        String code = mapped != null ? mapped : "EXCLUSION";
        findings.add(new PlanValidationFinding(code, exclusion, true));
      }
    }
    return new PlanValidationResult(findings);
  }

  /**
   * When the approved planning seed explicitly forces {@code else.condition}, ensure every {@code
   * else} node carries a condition property so the deterministic gate can reject it.
   */
  public static ChainPlanGraph applyForcedElseConditionProperties(
      String planningSeed, ChainPlanGraph graph) {
    if (graph == null
        || graph.nodes() == null
        || planningSeed == null
        || !planningSeed.toLowerCase(java.util.Locale.ROOT).contains("force else.condition")) {
      return graph;
    }
    boolean changed = false;
    List<ChainPlanNode> nodes = new ArrayList<>(graph.nodes().size());
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || !"else".equals(trimType(node.type()))) {
        nodes.add(node);
        continue;
      }
      if (hasProperty(node, "condition")) {
        nodes.add(node);
        continue;
      }
      List<PlanProperty> properties = new ArrayList<>();
      if (node.properties() != null) {
        properties.addAll(node.properties());
      }
      properties.add(new PlanProperty("condition", "forced-else-condition"));
      nodes.add(
          new ChainPlanNode(
              node.nodeId(),
              node.type(),
              node.label(),
              node.parentNodeId(),
              node.order(),
              properties));
      changed = true;
    }
    if (!changed) {
      return graph;
    }
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, graph.edges());
  }

  static List<String> elsePropertyExclusions(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return List.of();
    }
    LinkedHashSet<String> exclusions = new LinkedHashSet<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || !"else".equals(trimType(node.type())) || node.properties() == null) {
        continue;
      }
      for (PlanProperty property : node.properties()) {
        if (property == null || property.key() == null || property.key().isBlank()) {
          continue;
        }
        String key = property.key().trim();
        if ("condition".equals(key) || "priority".equals(key)) {
          exclusions.add("else." + key);
        }
      }
    }
    return List.copyOf(exclusions);
  }

  private static String planningSeedText(RequirementBrief brief) {
    if (brief == null) {
      return "";
    }
    if (brief.approvedDraftText() != null && !brief.approvedDraftText().isBlank()) {
      return brief.approvedDraftText();
    }
    return RequirementBriefText.format(brief);
  }

  private static boolean hasProperty(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return false;
    }
    for (PlanProperty property : node.properties()) {
      if (property != null && key.equals(property.key()) && property.value() != null
          && !property.value().isBlank()) {
        return true;
      }
    }
    return false;
  }

  private static String trimType(String type) {
    return type == null ? "" : type.trim();
  }

  /** Maps structural messages such as {@code (else) has unknown property key 'condition'} → {@code else.condition}. */
  static String propertyFindingCode(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    String trimmed = message.trim();
    if (trimmed.equals("else.condition") || trimmed.equals("else.priority")) {
      return trimmed;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "\\((else)\\)\\s+has unknown property key '(condition|priority)'",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(trimmed);
    if (matcher.find()) {
      return matcher.group(1).toLowerCase(java.util.Locale.ROOT)
          + "."
          + matcher.group(2).toLowerCase(java.util.Locale.ROOT);
    }
    if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("else.condition")) {
      return "else.condition";
    }
    if (trimmed.toLowerCase(java.util.Locale.ROOT).contains("else.priority")) {
      return "else.priority";
    }
    return null;
  }

  private static void applySpineArtifacts(SkillWorkspace workspace, PlanningSpineOutcome spine) {
    if (spine.graph() != null) {
      workspace.put(
          SkillArtifact.of(
              SkillArtifactType.CHAIN_PLAN_GRAPH,
              "planning-spine",
              new SkillArtifactPayload.ChainPlanGraphPayload(spine.graph())));
    }
    if (spine.validationResult() != null) {
      workspace.put(
          SkillArtifact.of(
              SkillArtifactType.PRE_BUILD_VALIDATION,
              "planning-spine",
              new SkillArtifactPayload.ValidationResultPayload(spine.validationResult())));
    }
    if (spine.selectedPatternId() != null) {
      workspace.put(
          SkillArtifact.of(
              SkillArtifactType.SELECTED_PATTERN,
              "planning-spine",
              new SkillArtifactPayload.SelectedPatternPayload(
                  new org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern(
                      spine.selectedPatternId(),
                      spine.selectedPatternId(),
                      spine.selectedPatternSummary() == null
                          ? spine.selectedPatternId()
                          : spine.selectedPatternSummary(),
                      null,
                      List.of(),
                      spine.selectedPatternSummary() == null
                          ? spine.selectedPatternId()
                          : spine.selectedPatternSummary()))));
    }
  }

  private static boolean startsWithExpectedOrder(List<String> executed, List<String> expected) {
    if (executed.size() < expected.size()) {
      return false;
    }
    for (int i = 0; i < expected.size(); i++) {
      if (!expected.get(i).equals(executed.get(i))) {
        return false;
      }
    }
    return true;
  }

  /** Outcome produced by the compiler-derived planning spine (or test double). */
  public record PlanningSpineOutcome(
      List<String> executedSkillIds,
      ChainPlanGraph graph,
      ValidationResult validationResult,
      String selectedPatternId,
      String selectedPatternSummary,
      List<String> ownerSkills) {

    public PlanningSpineOutcome {
      executedSkillIds = executedSkillIds == null ? List.of() : List.copyOf(executedSkillIds);
      ownerSkills = ownerSkills == null ? List.of() : List.copyOf(ownerSkills);
    }

    public static PlanningSpineOutcome empty(List<String> skillOrder) {
      return new PlanningSpineOutcome(skillOrder, null, null, null, null, List.of());
    }
  }
}
