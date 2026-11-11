package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.catalog.binding.CompositionCatalogBinder;
import org.qubership.integration.platform.ai.catalog.binding.McpSystemCatalogBinder;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineDependency;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifestBuilder;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorDescriptor;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorReadiness;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerExecutionSeed;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

/**
 * Maps an approval-bound owning-skill closure and resolved catalog bindings into the shared
 * {@link CompilerDagExecutionEngine} request.
 */
@ApplicationScoped
public class DefaultApprovedCompilerExecutionRunner implements ApprovedCompilerExecutionRunner {

  /** Always scoped on CREATE so RBAC is not dropped when the plan treated it as topology. */
  static final String SECURITY_GENERATOR_SKILL_ID = "cip-security-generator";

  /**
   * Always scoped on CREATE so header-modification properties are not dropped when the planner
   * folds them into structure instead of naming this skill.
   */
  static final String TRANSFORMATION_GENERATOR_SKILL_ID = "cip-transformation-generator";

  /** Authentication properties have no separate target in the typed design plan. */
  static final String AUTH_GENERATOR_SKILL_ID = "cip-auth-generator";

  private final CompilerDagExecutionEngine engine;
  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;
  private final ChainSemanticGraphCompiler graphCompiler;
  private final CompilerContractRepository contractRepository;
  private final CompositionCatalogBinder compositionBinder;
  private final McpSystemCatalogBinder mcpSystemCatalogBinder;
  private final GeneratorPlanManifestBuilder manifestBuilder;
  private final ChainPlanGraphValidator graphValidator;
  private final CanonicalGraphDigest graphDigest;

  @SuppressWarnings("java:S107")
  public DefaultApprovedCompilerExecutionRunner(
      CompilerDagExecutionEngine engine,
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      ChainSemanticGraphCompiler graphCompiler,
      CompilerContractRepository contractRepository,
      CompositionCatalogBinder compositionBinder,
      McpSystemCatalogBinder mcpSystemCatalogBinder,
      GeneratorPlanManifestBuilder manifestBuilder) {
    this(engine, runStore, artifactStore, graphCompiler, contractRepository, compositionBinder,
        mcpSystemCatalogBinder, manifestBuilder, new ChainPlanGraphValidator(null),
        new CanonicalGraphDigest(new com.fasterxml.jackson.databind.ObjectMapper()));
  }

  @Inject
  @SuppressWarnings("java:S107")
  public DefaultApprovedCompilerExecutionRunner(
      CompilerDagExecutionEngine engine, ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore, ChainSemanticGraphCompiler graphCompiler,
      CompilerContractRepository contractRepository, CompositionCatalogBinder compositionBinder,
      McpSystemCatalogBinder mcpSystemCatalogBinder, GeneratorPlanManifestBuilder manifestBuilder,
      ChainPlanGraphValidator graphValidator, CanonicalGraphDigest graphDigest) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.graphCompiler = Objects.requireNonNull(graphCompiler, "graphCompiler");
    this.contractRepository = Objects.requireNonNull(contractRepository, "contractRepository");
    this.compositionBinder = Objects.requireNonNull(compositionBinder, "compositionBinder");
    this.mcpSystemCatalogBinder =
        Objects.requireNonNull(mcpSystemCatalogBinder, "mcpSystemCatalogBinder");
    this.manifestBuilder = Objects.requireNonNull(manifestBuilder, "manifestBuilder");
    this.graphValidator = Objects.requireNonNull(graphValidator, "graphValidator");
    this.graphDigest = Objects.requireNonNull(graphDigest, "graphDigest");
  }

  @Override
  public CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      RunManifest runManifest,
      String attemptId,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph,
      BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(approvedPlan, "approvedPlan");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(runManifest, "runManifest");
    if (!Objects.equals(approvedPlan.semanticRevisionId(), revision.revisionId())) {
      throw new IllegalStateException(
          "Approved design plan does not match the approved semantic revision");
    }
    Revision storedBriefRevision = loadStoredBriefRevision(runManifest.runId(), revision);
    if (storedBriefRevision == null) {
      throw new IllegalStateException(
          "design-execution requires a committed RequirementBrief");
    }
    RequirementBrief storedBrief =
        artifactStore.payload(storedBriefRevision, RequirementBrief.class);
    return executePrepared(
        approvedPlan,
        revision,
        bindings,
        runManifest,
        attemptId,
        repairEvidence,
        priorGraph,
        resolveConversationId(runManifest.runId()),
        storedBrief,
        storedBriefRevision.reference(),
        skillProgress);
  }

  /**
   * Runs the production compiler DAG from explicit, already saved planning inputs.
   *
   * <p>This entry point exists for the executor harness. It deliberately accepts no planner and
   * therefore cannot spend planner tokens while replaying generator cases.
   */
  public CompilerDagExecutionResult executeHarness(
      DesignExecutionPlan approvedPlan,
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      RunManifest runManifest,
      String conversationId,
      RequirementBrief storedBrief,
      org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference
          storedBriefRef,
      BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(storedBrief, "storedBrief");
    Objects.requireNonNull(storedBriefRef, "storedBriefRef");
    return executePrepared(
        approvedPlan,
        revision,
        bindings,
        runManifest,
        null,
        null,
        null,
        conversationId,
        storedBrief,
        storedBriefRef,
        skillProgress);
  }

  private CompilerDagExecutionResult executePrepared(
      DesignExecutionPlan approvedPlan,
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      RunManifest runManifest,
      String attemptId,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph,
      String conversationId,
      RequirementBrief storedBrief,
      org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference
          storedBriefRef,
      BiConsumer<String, String> skillProgress) {
    BiConsumer<String, String> progress =
        skillProgress == null ? (skillId, status) -> {} : skillProgress;
    CompilerRunPin pin = requirePin(runManifest);
    List<String> approvedOwningSkillIds = orderedOwningSkillIds(approvedPlan);
    ResolvedCompilerDag executionDag = scopeDag(pin.resolvedDag(), approvedOwningSkillIds);
    List<ResolvedServiceCallBinding> resolvedBindings =
        bindings == null ? List.of() : List.copyOf(bindings);
    CompilerContract contract =
        contractRepository.require(
            pin.compilerContractVersion() != null
                ? pin.compilerContractVersion()
                : CompilerContract.V1);
    ChainPlanGraph graph = graphCompiler.compile(revision, contract, resolvedBindings, storedBrief);
    graph = compositionBinder.bind(graph, storedBrief);
    graph = mcpSystemCatalogBinder.bind(graph, storedBrief);
    RequirementBrief brief =
        DesignExecutionBriefFactory.build(storedBrief, revision);
    CompilerExecutionSeed seed =
        CompilerExecutionSeed.forCreate(conversationId, brief, revision, graph, resolvedBindings);
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace(conversationId);
    seed.artifacts().forEach(workspace::put);
    Set<String> required = new LinkedHashSet<>(approvedOwningSkillIds);
    required.addAll(List.of(SECURITY_GENERATOR_SKILL_ID, TRANSFORMATION_GENERATOR_SKILL_ID,
        AUTH_GENERATOR_SKILL_ID));
    List<CompilerGeneratorDescriptor> generators = executionDag.nodes().stream()
        .filter(node -> node.generatorId() != null && node.generatorId().startsWith("GEN-"))
        .map(node -> new CompilerGeneratorDescriptor(node.generatorId(), node.skillId(),
            node.stableTieBreaker(), "GRAPH_PATCH_ARTIFACT", List.of(),
            new CompilerGeneratorReadiness("pinned", node.readinessSignals())))
        .toList();
    var readiness = manifestBuilder.build(pin.compilerPackageVersion(), generators, required, workspace);
    seed = seed.with(SkillArtifact.of(SkillArtifactType.GENERATOR_PLAN_MANIFEST,
        CompilerExecutionSeed.SEMANTIC_COMPILER_PRODUCER,
        new SkillArtifactPayload.GeneratorPlanManifestPayload(readiness.manifest())));
    RepairInput repairInput = selectRepairGraph(runManifest.runId(), repairEvidence, priorGraph, contract, revision, storedBrief);
    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            runManifest.runId(),
            conversationId,
            runManifest,
            brief,
            revision,
            executionDag,
            approvedOwningSkillIds,
            List.of(storedBriefRef),
            seed,
            repairEvidence == null ? null : repairEvidence.withRepairInput(repairInput.graph(), repairInput.reference()));
    return engine.execute(request, attemptId, progress).await().indefinitely();
  }

  private record RepairInput(ChainPlanGraph graph, Reference reference) {}

  private RepairInput selectRepairGraph(String runId, StageRepairEvidence repair, ChainPlanGraph current,
      CompilerContract contract, ChainSemanticRevision semantic, RequirementBrief brief) {
    if (current == null || repair == null) {
      return new RepairInput(null, null);
    }
    boolean safe = preservesApprovedInputs(current, contract, semantic, brief);
    Revision currentRevision = repair.priorOutput(Kind.CHAIN_PLAN_GRAPH)
        .flatMap(ref -> artifactStore.get(runId, ref)).orElse(null);
    if (currentRevision == null) {
      return new RepairInput(safe ? current : null, null);
    }
    if (!current.equals(artifactStore.payload(currentRevision, ChainPlanGraph.class))) {
      return new RepairInput(null, null);
    }
    RecoveryEvidence evidence = artifactStore.history(runId, Kind.RECOVERY_EVIDENCE).stream()
        .filter(stored -> stored.contentHash().equals(repair.recoveryEvidenceRef()))
        .map(stored -> artifactStore.payload(stored, RecoveryEvidence.class)).findFirst().orElse(null);
    if (evidence == null || !Objects.equals(evidence.observingStageId(), repair.failedStageId())
        || !evidence.rejectedArtifactRefs().contains(currentRevision.reference())) {
      return new RepairInput(safe ? current : null, safe ? currentRevision.reference() : null);
    }
    List<Reference> history = evidence.priorAttemptRefs();
    RepairInput selected = null;
    RecoveryEvidence selectedEvidence = null;
    for (Reference observed : history) {
      if (observed.kind() != Kind.RECOVERY_EVIDENCE) {
        continue;
      }
      RecoveryEvidence previous = artifactStore.get(runId, observed)
          .map(stored -> artifactStore.payload(stored, RecoveryEvidence.class)).orElse(null);
      if (previous == null || !evidence.observingStageId().equals(previous.observingStageId())) {
        continue;
      }
      Revision prior = selectedRevision(runId, previous, Kind.CHAIN_PLAN_GRAPH);
      if (prior == null || !StageRepairEvidence.matchesCompileInputs(prior, currentRevision.inputs())) {
        continue;
      }
      ChainPlanGraph graph = artifactStore.payload(prior, ChainPlanGraph.class);
      if (!preservesApprovedInputs(graph, contract, semantic, brief)) {
        continue;
      }
      if (selected == null || !graph.equals(selected.graph())
          && resolvesCoveredFinding(runId, previous, graph, selectedEvidence, selected.graph(), currentRevision.inputs())) {
        selected = new RepairInput(graph, prior.reference());
        selectedEvidence = previous;
      }
    }
    if (selected != null) {
      return safe && (current.equals(selected.graph())
          || resolvesCoveredFinding(runId, evidence, current, selectedEvidence, selected.graph(), currentRevision.inputs()))
          ? new RepairInput(current, currentRevision.reference()) : selected;
    }
    return new RepairInput(safe ? current : null, safe ? currentRevision.reference() : null);
  }

  private boolean preservesApprovedInputs(ChainPlanGraph graph, CompilerContract contract,
      ChainSemanticRevision semantic, RequirementBrief brief) {
    try {
      graphValidator.validate(graph, contract, semantic);
      var issues = CompilerQualityValidator.validateApprovedValues(graph, brief);
      return issues.isEmpty() || CompilerQualityValidator.onlyMissingScriptLiterals(issues);
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      return false;
    }
  }

  private Revision selectedRevision(String runId, RecoveryEvidence evidence, Kind kind) {
    List<Reference> refs = evidence.rejectedArtifactRefs().stream().filter(ref -> ref.kind() == kind).toList();
    return refs.size() == 1 ? artifactStore.get(runId, refs.getFirst()).orElse(null) : null;
  }

  private boolean resolvesCoveredFinding(String runId, RecoveryEvidence currentEvidence, ChainPlanGraph current,
      RecoveryEvidence priorEvidence, ChainPlanGraph prior, List<Reference> inputs) {
    Revision currentBundleRef = selectedRevision(runId, currentEvidence, Kind.COMPILER_VALIDATION_BUNDLE);
    Revision priorBundleRef = selectedRevision(runId, priorEvidence, Kind.COMPILER_VALIDATION_BUNDLE);
    Revision currentFindingsRef = selectedRevision(runId, currentEvidence, Kind.PLAN_VALIDATION_RESULT);
    Revision priorFindingsRef = selectedRevision(runId, priorEvidence, Kind.PLAN_VALIDATION_RESULT);
    if (currentBundleRef == null || priorBundleRef == null || currentFindingsRef == null || priorFindingsRef == null) {
      return false;
    }
    if (List.of(currentBundleRef, priorBundleRef, currentFindingsRef, priorFindingsRef).stream()
        .anyMatch(ref -> !StageRepairEvidence.matchesCompileInputs(ref, inputs))) {
      return false;
    }
    CompilerValidationBundle currentBundle = artifactStore.payload(currentBundleRef, CompilerValidationBundle.class);
    CompilerValidationBundle priorBundle = artifactStore.payload(priorBundleRef, CompilerValidationBundle.class);
    if (priorBundle.passes().isEmpty()
        || java.util.stream.Stream.concat(currentBundle.passes().stream(), priorBundle.passes().stream())
            .anyMatch(pass -> pass.result() == null || pass.validatorSkillId() == null || pass.validatorSkillId().isBlank())
        || !graphDigest.sha256(current).equals(currentBundle.graphDigest())
        || !graphDigest.sha256(prior).equals(priorBundle.graphDigest())
        || !currentBundle.passes().stream().map(pass -> pass.validatorSkillId()).collect(java.util.stream.Collectors.toSet())
            .containsAll(priorBundle.passes().stream().map(pass -> pass.validatorSkillId()).toList())) {
      return false;
    }
    Set<String> currentFindings = locatedFindings(artifactStore.payload(currentFindingsRef, PlanValidationResult.class));
    PlanValidationResult priorReport = artifactStore.payload(priorFindingsRef, PlanValidationResult.class);
    Set<String> priorFindings = locatedFindings(priorReport);
    if (currentFindings == null || priorFindings == null
        || priorFindings.stream().noneMatch(key -> !currentFindings.contains(key))) {
      return false;
    }
    var delta = org.qubership.integration.platform.ai.chain.edit.CanonicalGraphDiff.between(
        prior, current, "repair-input-comparison", "design-execution", "Check protected repair scope");
    return prior.chain().equals(current.chain()) && delta.nodePatches().isEmpty() && delta.edgePatches().isEmpty()
        && delta.propertyPatches().stream().allMatch(patch -> priorReport.findings().stream()
            .filter(finding -> finding.blocker() && finding.originRecordId().equals(patch.targetNodeId()))
            .anyMatch(finding -> {
              String property = "/properties/" + patch.property().key();
              return finding.fieldPointer().equals(property) || finding.fieldPointer().startsWith(property + ".")
                  || finding.fieldPointer().startsWith(property + "/");
            }));
  }

  private static Set<String> locatedFindings(PlanValidationResult result) {
    Set<String> keys = new LinkedHashSet<>();
    for (var finding : result.findings()) {
      if (!finding.blocker()) {
        continue;
      }
      if (finding.originRecordId().isBlank() || finding.fieldPointer().isBlank()) {
        return null;
      }
      keys.add(org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger.evidenceIdentity(
          new org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause(
              org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode.VALIDATION_BLOCKER,
              List.of(finding), "")));
    }
    return keys;
  }

  private Revision loadStoredBriefRevision(String runId, ChainSemanticRevision semantic) {
    ProductPipelineRunDocument doc = runStore.load(runId).orElseThrow();
    Reference brief = org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
        .selectedRequirementBriefReference(doc);
    if (brief == null) { return null; }
    List<Reference> semantics = doc.run().stages().stream()
        .filter(stage -> stage.status() == org.qubership.integration.platform.ai.productpipeline.store.StageStatus.SUCCEEDED)
        .flatMap(stage -> stage.outputRefs().stream()).filter(ref -> ref.kind() == Kind.CHAIN_SEMANTIC_REVISION)
        .distinct().toList();
    if (semantics.size() != 1) { throw new IllegalStateException("Execution must select one committed semantic revision"); }
    Revision selected = artifactStore.get(runId, semantics.getFirst()).orElseThrow();
    if (!semantic.equals(artifactStore.payload(selected, ChainSemanticRevision.class))) {
      throw new IllegalStateException("Execution semantic payload differs from its committed revision");
    }
    Revision lineageBrief = org.qubership.integration.platform.ai.productpipeline.recovery.SupersededBriefLineageGuard
        .originatingBriefRevision(artifactStore, runId, selected).orElseThrow(() ->
            new IllegalStateException("Execution semantic brief lineage is unavailable"));
    if (!brief.equals(lineageBrief.reference())) {
      throw new IllegalStateException("Execution semantic brief lineage differs from the committed brief");
    }
    return lineageBrief;
  }

  private String resolveConversationId(String runId) {
    ProductPipelineRunDocument document =
        runStore
            .load(runId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "contract failure: missing product pipeline run for runId " + runId));
    String conversationId = document.run().conversationId();
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalStateException(
          "contract failure: product pipeline run is missing conversationId for runId " + runId);
    }
    return conversationId;
  }

  public static List<String> orderedOwningSkillIds(DesignExecutionPlan plan) {
    LinkedHashSet<String> ordered = new LinkedHashSet<>();
    for (DesignExecutionPlan.Step step : plan.steps()) {
      if (step.ownerKind() != DesignExecutionPlan.OwnerKind.SKILL) {
        continue;
      }
      ordered.addAll(step.owningSkillIds());
    }
    return List.copyOf(ordered);
  }

  /**
   * Approved owning generators plus their real prerequisites, then assembler and validators.
   *
   * <p>The pinned assembler lists every generation skill in {@code dependsOn} via the
   * {@code all-generation-skills} macro. Walking that list would run quartz, retry, and the rest
   * on a HealthProxy CREATE. Seed the walk from non-terminal owners only, then attach assembler
   * and Validation nodes and drop {@code dependsOn} edges that point outside the cut.
   *
   * <p>{@code cip-security-generator} is always seeded when it exists in the pin. Access control
   * is properties on the HTTP trigger, not a plan step, so the planner may omit this skill.
   *
   * <p>{@code cip-transformation-generator} is always seeded when it exists in the pin.
   * Header-modification add/remove maps are properties, not topology. The planner often assigns
   * that work to structure, which would leave {@code headerModificationToAdd} and
   * {@code headerModificationToRemove} empty.
   *
   * <p>{@code cip-auth-generator} is always seeded when it exists in the pin. It owns
   * authentication properties and emits an empty patch when authentication is not requested.
   */
  static Set<String> skillClosureIds(
      DesignExecutionPlan plan, ResolvedCompilerDag fullDag) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (ResolvedCompilerNode node : scopeDag(fullDag, orderedOwningSkillIds(plan)).nodes()) {
      if (node != null && node.skillId() != null) {
        ids.add(node.skillId());
      }
    }
    return Set.copyOf(ids);
  }

  static ResolvedCompilerDag scopeDag(
      ResolvedCompilerDag fullDag, List<String> approvedOwningSkillIds) {
    Objects.requireNonNull(fullDag, "fullDag");
    LinkedHashMap<String, ResolvedCompilerNode> byId = indexBySkillId(fullDag);
    LinkedHashSet<String> closure = generatorClosure(byId, approvedOwningSkillIds);
    attachExecutionTerminals(fullDag, closure);
    return new ResolvedCompilerDag(
        copyScopedNodes(fullDag, closure),
        copyScopedDependencies(fullDag, closure),
        fullDag.digest());
  }

  private static LinkedHashMap<String, ResolvedCompilerNode> indexBySkillId(
      ResolvedCompilerDag fullDag) {
    LinkedHashMap<String, ResolvedCompilerNode> byId = new LinkedHashMap<>();
    for (ResolvedCompilerNode node : fullDag.nodes()) {
      if (node != null && node.skillId() != null) {
        byId.put(node.skillId(), node);
      }
    }
    return byId;
  }

  private static LinkedHashSet<String> generatorClosure(
      LinkedHashMap<String, ResolvedCompilerNode> byId, List<String> approvedOwningSkillIds) {
    LinkedHashSet<String> closure = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    for (String skillId : approvedOwningSkillIds) {
      ResolvedCompilerNode node = byId.get(skillId);
      if (node == null) {
        throw new IllegalStateException(
            "approved owning skill is outside the pinned compiler DAG: " + skillId);
      }
      if (!isExecutionTerminal(node)) {
        queue.addLast(skillId);
      }
    }
    enqueueAlwaysScheduledGenerators(byId, queue);
    while (!queue.isEmpty()) {
      String skillId = queue.removeFirst();
      ResolvedCompilerNode node = byId.get(skillId);
      if (!closure.add(skillId) || node == null) {
        continue;
      }
      enqueueDependencies(queue, node);
    }
    return closure;
  }

  private static void enqueueAlwaysScheduledGenerators(
      LinkedHashMap<String, ResolvedCompilerNode> byId, ArrayDeque<String> queue) {
    enqueueIfPresent(byId, queue, SECURITY_GENERATOR_SKILL_ID);
    enqueueIfPresent(byId, queue, TRANSFORMATION_GENERATOR_SKILL_ID);
    enqueueIfPresent(byId, queue, AUTH_GENERATOR_SKILL_ID);
  }

  private static void enqueueIfPresent(
      LinkedHashMap<String, ResolvedCompilerNode> byId, ArrayDeque<String> queue, String skillId) {
    ResolvedCompilerNode node = byId.get(skillId);
    if (node != null && !isExecutionTerminal(node)) {
      queue.addLast(skillId);
    }
  }

  private static void enqueueDependencies(ArrayDeque<String> queue, ResolvedCompilerNode node) {
    for (String dependency : node.dependsOn()) {
      if (dependency != null && !dependency.isBlank()) {
        queue.addLast(dependency.trim());
      }
    }
  }

  private static void attachExecutionTerminals(
      ResolvedCompilerDag fullDag, LinkedHashSet<String> closure) {
    for (ResolvedCompilerNode node : fullDag.nodes()) {
      if (isExecutionTerminal(node)) {
        closure.add(node.skillId());
      }
    }
  }

  private static List<ResolvedCompilerNode> copyScopedNodes(
      ResolvedCompilerDag fullDag, LinkedHashSet<String> closure) {
    List<ResolvedCompilerNode> nodes = new ArrayList<>();
    for (ResolvedCompilerNode node : fullDag.nodes()) {
      if (node == null || !closure.contains(node.skillId())) {
        continue;
      }
      nodes.add(copyWithFilteredDependsOn(node, closure));
    }
    return nodes;
  }

  private static ResolvedCompilerNode copyWithFilteredDependsOn(
      ResolvedCompilerNode node, LinkedHashSet<String> closure) {
    return new ResolvedCompilerNode(
        node.skillId(),
        node.compilerPhase(),
        node.generatorId(),
        node.consumes(),
        node.produces(),
        node.dependsOn().stream().filter(closure::contains).toList(),
        node.captureTool(),
        node.applicabilitySignals(),
        node.readinessSignals(),
        node.runtimeReady(),
        node.runtimeReadinessFindings(),
        node.topologicalLevel(),
        node.stableTieBreaker(),
        node.mandatory(),
        node.executionMode(),
        node.adapterId(),
        node.ownership());
  }

  private static List<CompilerPipelineDependency> copyScopedDependencies(
      ResolvedCompilerDag fullDag, LinkedHashSet<String> closure) {
    List<CompilerPipelineDependency> dependencies = new ArrayList<>();
    for (CompilerPipelineDependency edge : fullDag.dependencies()) {
      if (edge != null
          && closure.contains(edge.producerSkillId())
          && closure.contains(edge.consumerSkillId())) {
        dependencies.add(edge);
      }
    }
    return dependencies;
  }

  private static boolean isExecutionTerminal(ResolvedCompilerNode node) {
    if (node == null || node.skillId() == null) {
      return false;
    }
    if ("cip-chain-assembler".equals(node.skillId())) {
      return true;
    }
    String phase = node.compilerPhase();
    return phase != null && phase.equals("Validation");
  }

  private static CompilerRunPin requirePin(RunManifest runManifest) {
    if (runManifest.compilerRunPin() == null || runManifest.compilerRunPin().resolvedDag() == null) {
      throw new IllegalStateException("compiler run pin with resolved DAG is required");
    }
    return runManifest.compilerRunPin();
  }
}
