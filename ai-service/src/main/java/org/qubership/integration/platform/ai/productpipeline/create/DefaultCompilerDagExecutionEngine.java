package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Uni;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceIds;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchArtifactFactory;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;
import org.qubership.integration.platform.ai.compiler.ChainEditSkillContext;
import org.qubership.integration.platform.ai.compiler.plan.CompilerOrchestrationService;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanStatus;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphPatchArtifact;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphOwnershipFact;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillRunStatus;
import org.qubership.integration.platform.ai.skill.executor.StreamingSkillExecutor;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.orchestration.SkillSubgraph;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/** Default shared compiler DAG execution engine. */
@ApplicationScoped
public class DefaultCompilerDagExecutionEngine implements CompilerDagExecutionEngine {

  private static final Logger LOG = Logger.getLogger(DefaultCompilerDagExecutionEngine.class);
  private final CompilerOrchestrationService orchestration = new CompilerOrchestrationService();
  private static final String REQUIREMENT_ANALYZER_SKILL = "cip-requirement-analyzer";
  private static final String PLANNING_STAGE_ID = "planning";
  private static final String PRODUCER_VERSION = "1";

  private final InMemorySkillWorkspaceStore workspaceStore;
  private final SkillExecutorRegistry skillRegistry;
  private final CompilerNodeExecutionAdapterRegistry javaAdapterRegistry;
  private final QipKnowledgePackRepository packRepository;
  private final CompilerDerivedPlanningScheduler scheduler;
  private final GraphPatchExecutionContextStore executionContextStore;
  private final GraphPatchArtifactFactory graphPatchArtifactFactory;
  private final ValidatedGraphPatchApplier validatedGraphPatchApplier;
  private final CanonicalGraphDigest canonicalGraphDigest;
  private final GraphAssemblyService graphAssemblyService;
  private final CompilerValidationPipeline compilerValidationPipeline;
  private final ProductPipelineArtifactStore artifactStore;

  @Inject
  @SuppressWarnings("java:S107")
  public DefaultCompilerDagExecutionEngine(
      InMemorySkillWorkspaceStore workspaceStore,
      SkillExecutorRegistry skillRegistry,
      CompilerNodeExecutionAdapterRegistry javaAdapterRegistry,
      QipKnowledgePackRepository packRepository,
      GraphPatchExecutionContextStore executionContextStore,
      GraphPatchArtifactFactory graphPatchArtifactFactory,
      ValidatedGraphPatchApplier validatedGraphPatchApplier,
      CanonicalGraphDigest canonicalGraphDigest,
      GraphAssemblyService graphAssemblyService,
      CompilerValidationPipeline compilerValidationPipeline,
      ProductPipelineArtifactStore artifactStore) {
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
    this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
    this.javaAdapterRegistry = Objects.requireNonNull(javaAdapterRegistry, "javaAdapterRegistry");
    this.packRepository = Objects.requireNonNull(packRepository, "packRepository");
    this.executionContextStore = Objects.requireNonNull(executionContextStore, "executionContextStore");
    this.graphPatchArtifactFactory =
        Objects.requireNonNull(graphPatchArtifactFactory, "graphPatchArtifactFactory");
    this.validatedGraphPatchApplier =
        Objects.requireNonNull(validatedGraphPatchApplier, "validatedGraphPatchApplier");
    this.canonicalGraphDigest = Objects.requireNonNull(canonicalGraphDigest, "canonicalGraphDigest");
    this.graphAssemblyService = Objects.requireNonNull(graphAssemblyService, "graphAssemblyService");
    this.compilerValidationPipeline =
        Objects.requireNonNull(compilerValidationPipeline, "compilerValidationPipeline");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.scheduler = new CompilerDerivedPlanningScheduler(skillRegistry, javaAdapterRegistry);
  }

  @SuppressWarnings("java:S107")
  DefaultCompilerDagExecutionEngine(
      InMemorySkillWorkspaceStore workspaceStore,
      SkillExecutorRegistry skillRegistry,
      CompilerNodeExecutionAdapterRegistry javaAdapterRegistry,
      QipKnowledgePackRepository packRepository,
      GraphAssemblyService graphAssemblyService,
      CompilerValidationPipeline compilerValidationPipeline,
      ProductPipelineArtifactStore artifactStore) {
    this(
        workspaceStore,
        skillRegistry,
        javaAdapterRegistry,
        packRepository,
        new GraphPatchExecutionContextStore(),
        new GraphPatchArtifactFactory(new CanonicalGraphDigest(new ObjectMapper())),
        new ValidatedGraphPatchApplier(
            new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator(),
            new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier()),
        new CanonicalGraphDigest(new ObjectMapper()),
        graphAssemblyService,
        compilerValidationPipeline,
        artifactStore);
  }

  @SuppressWarnings("java:S107")
  DefaultCompilerDagExecutionEngine(
      InMemorySkillWorkspaceStore workspaceStore,
      SkillExecutorRegistry skillRegistry,
      CompilerNodeExecutionAdapterRegistry javaAdapterRegistry,
      QipKnowledgePackRepository packRepository,
      GraphAssemblyService graphAssemblyService,
      CompilerValidationPipeline compilerValidationPipeline) {
    this(
        workspaceStore,
        skillRegistry,
        javaAdapterRegistry,
        packRepository,
        graphAssemblyService,
        compilerValidationPipeline,
        new ProductPipelineArtifactStore(
            new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts(
                new org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore(),
                new ObjectMapper(),
                java.time.Clock.systemUTC())));
  }

  @Override
  public Uni<CompilerDagExecutionResult> execute(
      CompilerDagExecutionRequest request, BiConsumer<String, String> skillProgress) {
    return execute(request, null, skillProgress);
  }

  @Override
  public Uni<CompilerDagExecutionResult> execute(
      CompilerDagExecutionRequest request,
      String attemptId,
      BiConsumer<String, String> skillProgress) {
    return execute(request, resolveLanguageVersion(request), attemptId, skillProgress);
  }

  /**
   * create-chain@1 seam entry: preserves planning-request languageVersion and attemptId for patch
   * invocation keys while using the shared engine request shape.
   */
  Uni<CompilerDagExecutionResult> execute(
      CompilerDagExecutionRequest request,
      String languageVersion,
      String attemptId,
      BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(request, "request");
    BiConsumer<String, String> progress =
        skillProgress == null ? (skillId, status) -> {} : skillProgress;
    String resolvedLanguageVersion =
        languageVersion == null || languageVersion.isBlank()
            ? resolveLanguageVersion(request)
            : languageVersion.trim();
    return Uni.createFrom()
        .item(() -> runDerivedPlanning(request, resolvedLanguageVersion, attemptId, progress));
  }

  private static String resolveLanguageVersion(CompilerDagExecutionRequest request) {
    if (request.runManifest() != null
        && request.runManifest().languageVersion() != null
        && !request.runManifest().languageVersion().isBlank()) {
      return request.runManifest().languageVersion().trim();
    }
    return "24.4";
  }

  @SuppressWarnings("java:S3776")
  private CompilerDagExecutionResult runDerivedPlanning(
      CompilerDagExecutionRequest engineRequest,
      String languageVersion,
      String attemptId,
      BiConsumer<String, String> skillProgress) {
    CompilerPlanningRequest request =
        toPlanningRequest(engineRequest, languageVersion, attemptId);
    CompilerExecutionSeed seed = engineRequest.effectiveSeed();
    String workspaceId = seed.workspaceId();
    applySeed(seed);
    PinnedRunContext pinned = resolvePinnedRun(engineRequest);
    ResolvedCompilerDag dag = engineRequest.executionDag();
    PlanningSchedulerState state = seededState(dag, seed);
    List<String> executed = new ArrayList<>();
    Map<String, ValidationResult> validatorPasses = new HashMap<>();
    SkillWorkspace workspace = workspaceStore.getOrCreate(workspaceId);
    PlanningPatchLedger.Builder patchLedger = new PlanningPatchLedger.Builder();
    List<PlanValidationFinding> degradations = new ArrayList<>();

    boolean running = true;
    while (running) {
      PlanningSchedulerState beforeVirtual = state;
      state = scheduler.completeVirtualNodes(state);
      appendNewCompletions(executed, beforeVirtual, state);

      Optional<ResolvedCompilerNode> next = scheduler.next(state);
      if (next.isEmpty()) {
        running = false;
      } else {
        ResolvedCompilerNode node = next.orElseThrow();
        if (node.executionMode() == CompilerNodeExecutionMode.PRE_SATISFIED
            || node.executionMode() == CompilerNodeExecutionMode.VIRTUAL_ORCHESTRATOR) {
          PlanningSchedulerState previous = state;
          state = scheduler.executeNext(state);
          appendNewCompletions(executed, previous, state);
        } else if (shouldSkipGenerator(node, workspace)) {
          LOG.infof(
              "Skipping generator skillId=%s reason=manifest SKIPPED",
              node.skillId());
          state = applyCompletion(state, node, List.of());
          executed.add(node.skillId());
        } else {
          skillProgress.accept(node.skillId(), "running");
          Optional<String> previousParent = ToolInvocationSink.currentParentSkillId();
          ToolInvocationSink.setParentSkillId(EvidenceIds.wireSkill(node.skillId()));
          try {
            List<SkillArtifact> outputs =
                executeNode(
                    request, seed, workspace, state, node, pinned, dag, validatorPasses, patchLedger);
            outputs =
                materializeGraphPatchOutputs(
                    request, seed, workspace, node, outputs, pinned, patchLedger);
            for (SkillArtifact output : outputs) {
              workspaceStore.putArtifact(workspaceId, output);
            }
            state = applyCompletion(state, node, outputs);
            executed.add(node.skillId());
            skillProgress.accept(node.skillId(), "completed");
          } catch (RuntimeException ex) {
            if (isSkippablePlanningSkillFailure(node, ex)) {
              LOG.warnf(
                  ex,
                  "Planning skill failed conversationId=%s skillId=%s mode=%s — continuing",
                  request.conversationId(),
                  node.skillId(),
                  node.executionMode());
              skillProgress.accept(node.skillId(), "error");
              degradations.add(PlanningDegradations.generatorSkipped(node.skillId()));
              List<SkillArtifact> fallback =
                  fallbackOutputsAfterSkillFailure(workspaceId, node, degradations);
              fallback = requireChainStructureFallback(workspaceId, node, fallback, ex);
              state = applyCompletion(state, node, fallback);
              executed.add(node.skillId());
            } else {
              skillProgress.accept(node.skillId(), "error");
              throw ex;
            }
          } finally {
            previousParent.ifPresentOrElse(
                ToolInvocationSink::setParentSkillId, ToolInvocationSink::clearParentSkillId);
          }
        }
        if (stopAfterAssemblyAndMandatoryValidators(dag, state)) {
          running = false;
        }
      }
    }

    if (!stopAfterAssemblyAndMandatoryValidators(dag, state)) {
      throw new IllegalStateException("contract failure: planner stopped before mandatory nodes completed");
    }
    return toResult(workspaceId, executed, patchLedger.build(), degradations);
  }

  private static CompilerPlanningRequest toPlanningRequest(
      CompilerDagExecutionRequest engineRequest, String languageVersion, String attemptId) {
    return new CompilerPlanningRequest(
        engineRequest.conversationId(),
        engineRequest.runId(),
        engineRequest.requirementBrief(),
        new IdsBypass("engine", "compiler-dag-engine", "1"),
        languageVersion,
        List.of(),
        List.of(),
        attemptId);
  }

  /**
   * LLM generators fail open on {@code status=FAILED} even when marked mandatory, so one bad
   * capture does not tear the planning SSE stream. Java adapters / validators stay fail-closed.
   * Legacy optional non-LLM nodes still skip any "skill did not complete" contract failure.
   */
  private static boolean isSkippablePlanningSkillFailure(
      ResolvedCompilerNode node, RuntimeException ex) {
    String message = ex.getMessage();
    if (message == null || !message.startsWith("contract failure: skill did not complete ")) {
      return false;
    }
    if (node.executionMode() == CompilerNodeExecutionMode.LLM_SKILL) {
      return message.contains("status=FAILED");
    }
    return !node.mandatory();
  }

  private static boolean isGeneratorNode(ResolvedCompilerNode node) {
    if (node == null || node.executionMode() != CompilerNodeExecutionMode.LLM_SKILL) {
      return false;
    }
    String generatorId = node.generatorId();
    return generatorId != null && !generatorId.isBlank() && generatorId.startsWith("GEN-");
  }

  private boolean shouldSkipGenerator(ResolvedCompilerNode node, SkillWorkspace workspace) {
    if (!isGeneratorNode(node) || workspace == null) {
      return false;
    }
    try {
      return workspace
          .get(SkillArtifactType.GENERATOR_PLAN_MANIFEST)
          .map(
              artifact ->
                  ((SkillArtifactPayload.GeneratorPlanManifestPayload) artifact.payload())
                      .manifest())
          .map(
              manifest ->
                  orchestration.statusForSkill(manifest, node.skillId())
                      == GeneratorPlanStatus.SKIPPED)
          .orElse(false);
    } catch (RuntimeException ex) {
      LOG.warnf(
          ex,
          "Generator plan manifest unreadable skillId=%s — fail-open",
        node.skillId());
      return false;
    }
  }

  /**
   * When an LLM skill fails without outputs, keep any already-captured artifacts it was supposed to
   * produce (for example a prior naming manifest on replan). If naming produced nothing yet, emit a
   * soft default so downstream planning can continue.
   */
  private List<SkillArtifact> fallbackOutputsAfterSkillFailure(
      String conversationId, ResolvedCompilerNode node, List<PlanValidationFinding> degradations) {
    if (node.executionMode() != CompilerNodeExecutionMode.LLM_SKILL) {
      return List.of();
    }
    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    List<SkillArtifact> fallback = new ArrayList<>();
    boolean expectsNaming = false;
    for (String produced : node.produces()) {
      String normalized = CompilerDerivedPlanningScheduler.normalizeArtifactType(produced);
      if (normalized.isBlank()) {
        continue;
      }
      if (SkillArtifactType.NAMING_MANIFEST.name().equals(normalized)) {
        expectsNaming = true;
      }
      SkillArtifactType type;
      try {
        type = SkillArtifactType.valueOf(normalized);
      } catch (IllegalArgumentException ignored) {
        continue;
      }
      Optional<SkillArtifact> carried = workspace.get(type);
      if (carried.isPresent()) {
        fallback.add(carried.get());
        degradations.add(
            PlanningDegradations.fallbackSubstituted(node.skillId(), type.name()));
      }
    }
    if (expectsNaming
        && fallback.stream().noneMatch(a -> a.type() == SkillArtifactType.NAMING_MANIFEST)) {
      NamingManifest softDefault =
          new NamingManifest(1, "Generated.Internal.Chain", Map.of(), List.of(), List.of());
      SkillArtifact naming =
          SkillArtifact.of(
              SkillArtifactType.NAMING_MANIFEST,
              node.skillId(),
              new SkillArtifactPayload.NamingManifestPayload(softDefault));
      fallback.add(naming);
      workspaceStore.putArtifact(conversationId, naming);
      degradations.add(
          PlanningDegradations.defaultChainName(node.skillId(), softDefault.chainName()));
      LOG.warnf(
          "Using soft-default naming manifest conversationId=%s skillId=%s chainName=%s",
          conversationId, node.skillId(), softDefault.chainName());
    }
    return List.copyOf(fallback);
  }

  private static boolean producesChainStructure(ResolvedCompilerNode node) {
    return node.produces().stream()
        .map(CompilerDerivedPlanningScheduler::normalizeArtifactType)
        .anyMatch(SkillArtifactType.CHAIN_STRUCTURE.name()::equals);
  }

  private List<SkillArtifact> requireChainStructureFallback(
      String conversationId,
      ResolvedCompilerNode node,
      List<SkillArtifact> fallback,
      Throwable cause) {
    if (!producesChainStructure(node)) {
      return fallback;
    }
    SkillArtifact structureArtifact =
        fallback.stream()
            .filter(artifact -> artifact.type() == SkillArtifactType.CHAIN_STRUCTURE)
            .filter(artifact -> !CompilerExecutionSeed.SEED_PRODUCER.equals(artifact.producerSkillId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new PlanningSkillArtifactUnavailableException(
                        node.skillId(),
                        Set.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                        cause));
    boolean hasGraph =
        fallback.stream()
            .anyMatch(artifact -> artifact.type() == SkillArtifactType.CHAIN_PLAN_GRAPH);
    if (hasGraph) {
      return fallback;
    }
    ChainStructure structure =
        ((SkillArtifactPayload.ChainStructurePayload) structureArtifact.payload())
            .structure();
    SkillArtifact graphArtifact =
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            structureArtifact.producerSkillId(),
            new SkillArtifactPayload.ChainPlanGraphPayload(structure.graph()));
    workspaceStore.putArtifact(conversationId, graphArtifact);
    List<SkillArtifact> augmented = new ArrayList<>(fallback);
    augmented.add(graphArtifact);
    return List.copyOf(augmented);
  }

  @SuppressWarnings("java:S107")
  private List<SkillArtifact> executeNode(
      CompilerPlanningRequest request,
      CompilerExecutionSeed seed,
      SkillWorkspace workspace,
      PlanningSchedulerState state,
      ResolvedCompilerNode node,
      PinnedRunContext pinned,
      ResolvedCompilerDag dag,
      Map<String, ValidationResult> validatorPasses,
      PlanningPatchLedger.Builder patchLedger) {
    if (node.executionMode() == CompilerNodeExecutionMode.LLM_SKILL) {
      SkillExecutor executor = skillRegistry.require(node.skillId());
      SkillRunContext context =
          new SkillRunContext(
              request.conversationId(),
              node.skillId(),
              packRepository.activeVersion().normalized(),
              SkillSubgraph.BUILD_CHAIN,
              state.invocationCount(),
              false,
              seed.seedText());
      GraphPatchExecutionContext executionContext =
          buildExecutionContext(request, seed, workspace, node, pinned);
      if (executionContext != null) {
        executionContextStore.set(request.conversationId(), node.skillId(), executionContext);
      }
      SkillExecutionResult result;
      try {
        result = invokeSkill(executor, context, workspace).await().indefinitely();
      } finally {
        executionContextStore.clear(request.conversationId(), node.skillId());
      }
      if (result.status() != SkillRunStatus.COMPLETED && result.status() != SkillRunStatus.SKIPPED) {
        throw new IllegalStateException(
            "contract failure: skill did not complete "
                + node.skillId()
                + " status="
                + result.status());
      }
      return result.outputs() == null ? List.of() : List.copyOf(result.outputs());
    }
    if (node.executionMode() == CompilerNodeExecutionMode.JAVA_ADAPTER) {
      CompilerNodeExecutionAdapter adapter = javaAdapterRegistry.require(node.adapterId());
      CompilerNodeExecutionResult result = adapter.execute(node, state);
      return augmentJavaAdapterOutputs(
          workspace,
          state,
          node,
          result.workspaceOutputs(),
          dag,
          validatorPasses,
          patchLedger);
    }
    return List.of();
  }

  private static Uni<SkillExecutionResult> invokeSkill(
      SkillExecutor executor, SkillRunContext context, SkillWorkspace workspace) {
    if (executor instanceof StreamingSkillExecutor streaming) {
      // Match prior product planning spine presentation behavior:
      // stream (tools/LLM) then finalize via run().
      return streaming
          .runStreaming(context, workspace)
          .collect()
          .asList()
          .onItem()
          .transformToUni(ignored -> streaming.run(context, workspace));
    }
    return executor.run(context, workspace);
  }

  private List<SkillArtifact> augmentJavaAdapterOutputs(
      SkillWorkspace workspace,
      PlanningSchedulerState state,
      ResolvedCompilerNode node,
      List<SkillArtifact> baseOutputs,
      ResolvedCompilerDag dag,
      Map<String, ValidationResult> validatorPasses,
      PlanningPatchLedger.Builder patchLedger) {
    List<SkillArtifact> outputs =
        baseOutputs == null ? new ArrayList<>() : new ArrayList<>(baseOutputs);
    if ("cip-chain-assembler".equals(node.skillId()) || "graph-assembly".equals(node.adapterId())) {
      GraphAssemblyResult assemblyResult = buildAssemblyResult(workspace, patchLedger.build());
      outputs.add(
          SkillArtifact.of(
              SkillArtifactType.GRAPH_ASSEMBLY_RESULT,
              node.skillId(),
              new SkillArtifactPayload.GraphAssemblyResultPayload(assemblyResult)));
      outputs.add(
          SkillArtifact.of(
              SkillArtifactType.CHAIN_PLAN_GRAPH,
              node.skillId(),
              new SkillArtifactPayload.ChainPlanGraphPayload(assemblyResult.graph())));
      return List.copyOf(outputs);
    }
    if (CompilerValidationPipeline.validatorSkillIds().contains(node.skillId())) {
      GraphAssemblyResult assemblyResult = resolveGraphAssemblyResult(workspace);
      NamingManifest namingManifest =
          workspace
              .get(SkillArtifactType.NAMING_MANIFEST)
              .map(a -> ((SkillArtifactPayload.NamingManifestPayload) a.payload()).manifest())
              .orElse(null);
      ValidationResult passResult =
          compilerValidationPipeline.validatePass(
              node.skillId(), namingManifest, assemblyResult.graph());
      validatorPasses.put(node.skillId(), passResult);
      outputs.add(
          SkillArtifact.of(
              SkillArtifactType.PRE_BUILD_VALIDATION,
              node.skillId(),
              new SkillArtifactPayload.ValidationResultPayload(passResult)));
      if (shouldPersistValidationBundle(state, node, dag, validatorPasses)) {
        CompilerValidationBundle bundle =
            buildValidationBundle(assemblyResult.graphDigest(), validatorPasses);
        outputs.add(
            SkillArtifact.of(
                SkillArtifactType.COMPILER_VALIDATION_BUNDLE,
                node.skillId(),
                new SkillArtifactPayload.CompilerValidationBundlePayload(bundle)));
      }
    }
    return List.copyOf(outputs);
  }

  private GraphAssemblyResult buildAssemblyResult(
      SkillWorkspace workspace, PlanningPatchLedger patchLedger) {
    // Prefer the workspace CHAIN_PLAN_GRAPH: materializeGraphPatchOutputs already applied every
    // accepted generator patch in order. workspace.get(GRAPH_PATCH_ARTIFACT) only returns the
    // latest patch, so replaying that alone would drop earlier patches (e.g. script bodies).
    ChainPlanGraph currentGraph =
        workspace
            .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
            .map(a -> ((SkillArtifactPayload.ChainPlanGraphPayload) a.payload()).graph())
            .orElse(null);
    if (currentGraph != null) {
      return graphAssemblyService.assembleFromGraph(
          currentGraph, patchLedger.orderedReferences(), patchLedger.ownershipFacts());
    }
    ChainStructure structure =
        workspace
            .get(SkillArtifactType.CHAIN_STRUCTURE)
            .map(a -> ((SkillArtifactPayload.ChainStructurePayload) a.payload()).structure())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "contract failure: CHAIN_STRUCTURE is required for graph assembly"));
    List<GraphPatchArtifact> acceptedPatchArtifacts =
        workspace
            .get(SkillArtifactType.GRAPH_PATCH_ARTIFACT)
            .map(
                artifact ->
                    List.of(
                        ((SkillArtifactPayload.GraphPatchArtifactPayload) artifact.payload())
                            .artifact()))
            .orElse(List.of());
    return graphAssemblyService.assemble(structure, acceptedPatchArtifacts);
  }

  private GraphAssemblyResult resolveGraphAssemblyResult(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.GRAPH_ASSEMBLY_RESULT)
        .map(a -> ((SkillArtifactPayload.GraphAssemblyResultPayload) a.payload()).result())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "contract failure: GRAPH_ASSEMBLY_RESULT is required for validator nodes"));
  }

  private static boolean shouldPersistValidationBundle(
      PlanningSchedulerState state,
      ResolvedCompilerNode currentNode,
      ResolvedCompilerDag dag,
      Map<String, ValidationResult> validatorPasses) {
    Set<String> mandatoryValidatorSkills = new LinkedHashSet<>();
    for (ResolvedCompilerNode node : dag.nodes()) {
      if (node.mandatory() && CompilerValidationPipeline.validatorSkillIds().contains(node.skillId())) {
        mandatoryValidatorSkills.add(node.skillId());
      }
    }
    if (mandatoryValidatorSkills.isEmpty()) {
      return false;
    }
    Set<String> completedWithCurrent = new LinkedHashSet<>(state.completedSkillIds());
    completedWithCurrent.add(currentNode.skillId());
    if (!completedWithCurrent.containsAll(mandatoryValidatorSkills)) {
      return false;
    }
    for (String skillId : mandatoryValidatorSkills) {
      if (validatorPasses.get(skillId) == null) {
        return false;
      }
    }
    return true;
  }

  private static CompilerValidationBundle buildValidationBundle(
      String graphDigest, Map<String, ValidationResult> validatorPasses) {
    List<CompilerValidationPass> passes = new ArrayList<>();
    for (String validatorSkillId : CompilerValidationPipeline.validatorSkillIds()) {
      ValidationResult result = validatorPasses.get(validatorSkillId);
      if (result != null) {
        passes.add(new CompilerValidationPass(validatorSkillId, result));
      }
    }
    return new CompilerValidationBundle(1, graphDigest, List.copyOf(passes));
  }

  @SuppressWarnings("java:S107")
  private List<SkillArtifact> materializeGraphPatchOutputs(
      CompilerPlanningRequest request,
      CompilerExecutionSeed seed,
      SkillWorkspace workspace,
      ResolvedCompilerNode node,
      List<SkillArtifact> outputs,
      PinnedRunContext pinned,
      PlanningPatchLedger.Builder patchLedger) {
    if (outputs == null || outputs.isEmpty()) {
      return List.of();
    }
    SkillArtifact rawPatchArtifact =
        outputs.stream().filter(a -> a != null && a.type() == SkillArtifactType.GRAPH_PATCH).findFirst().orElse(null);
    if (rawPatchArtifact == null) {
      return List.copyOf(outputs);
    }
    if (!(rawPatchArtifact.payload()
        instanceof SkillArtifactPayload.GraphPatchPayload(GraphPatch patch))) {
      throw new IllegalStateException("contract failure: GRAPH_PATCH payload type mismatch");
    }
    var inputGraphArtifact = workspace.get(SkillArtifactType.CHAIN_PLAN_GRAPH).orElse(null);
    if (inputGraphArtifact == null) {
      throw new IllegalStateException("contract failure: CHAIN_PLAN_GRAPH is required for graph patch");
    }
    var inputGraph = ((SkillArtifactPayload.ChainPlanGraphPayload) inputGraphArtifact.payload()).graph();
    GraphPatchExecutionContext context =
        new GraphPatchExecutionContext(
            request.runId(),
            node.skillId(),
            sha256Text(seed.seedText()),
            canonicalGraphDigest.sha256(inputGraph),
            pinned.pin().compilerPackageDigest(),
            request.languageVersion(),
            request.requirementBrief(),
            pinned.manifest().sourceReferences(),
            inputGraph,
            node.ownership(),
            request.attemptId(),
            ChainEditSkillContext.targetNodeIds(workspace, node.skillId()));
    var applied = validatedGraphPatchApplier.apply(context, patch);
    if (!applied.validationResult().valid()) {
      throw new IllegalStateException("contract failure: " + applied.validationResult().summary());
    }
    GraphPatchArtifact patchArtifact = graphPatchArtifactFactory.create(context, patch, applied.graph());
    Reference durableRef = persistGraphPatch(request.runId(), pinned.manifest(), patchArtifact);
    if (patchArtifact.applicability() == PatchApplicability.APPLICABLE) {
      patchLedger.addApplicable(
          durableRef,
          new GraphOwnershipFact(
              patchArtifact.ownerCapabilityId(),
              "APPLY_GRAPH_PATCH",
              patchArtifact.patchId(),
              "compiler-node-ownership:" + patchArtifact.ownerCapabilityId()));
    } else {
      patchLedger.addNotApplicable(durableRef);
    }

    ArrayList<SkillArtifact> enriched = new ArrayList<>(outputs.size() + 2);
    enriched.addAll(outputs);
    enriched.add(
        SkillArtifact.of(
            SkillArtifactType.GRAPH_PATCH_ARTIFACT,
            node.skillId(),
            new SkillArtifactPayload.GraphPatchArtifactPayload(patchArtifact)));
    if (patchArtifact.applicability() == PatchApplicability.APPLICABLE) {
      enriched.add(
          SkillArtifact.of(
              SkillArtifactType.CHAIN_PLAN_GRAPH,
              node.skillId(),
              new SkillArtifactPayload.ChainPlanGraphPayload(applied.graph())));
    }
    return List.copyOf(enriched);
  }

  private Reference persistGraphPatch(
      String runId, RunManifest manifest, GraphPatchArtifact patchArtifact) {
    if (patchArtifact.invocationKey() == null || patchArtifact.invocationKey().isBlank()) {
      throw new IllegalStateException(
          "contract failure: GRAPH_PATCH_ARTIFACT invocationKey is required");
    }
    Optional<Revision> existing =
        artifactStore.findGraphPatchByInvocationKey(runId, patchArtifact.invocationKey());
    if (existing.isPresent()) {
      GraphPatchArtifact existingPayload =
          artifactStore.payload(existing.orElseThrow(), GraphPatchArtifact.class);
      if (Objects.equals(existingPayload, patchArtifact)) {
        return existing.orElseThrow().reference();
      }
      throw new IllegalStateException(
          "contract failure: GRAPH_PATCH_ARTIFACT invocationKey conflict for '"
              + patchArtifact.invocationKey()
              + "'");
    }
    List<Reference> inputs = new ArrayList<>();
    artifactStore
        .latest(runId, Kind.CHAIN_PLAN_GRAPH)
        .map(Revision::reference)
        .ifPresent(inputs::add);
    if (patchArtifact.consumedArtifacts() != null) {
      inputs.addAll(patchArtifact.consumedArtifacts());
    }
    Revision appended =
        artifactStore.append(
            new AppendCommand(
                runId,
                Kind.GRAPH_PATCH_ARTIFACT,
                "1",
                patchArtifact.ownerCapabilityId(),
                PRODUCER_VERSION,
                patchArtifact,
                inputs,
                null,
                graphPatchProvenance(runId, manifest, patchArtifact.ownerCapabilityId())));
    return appended.reference();
  }

  private static ArtifactProvenance graphPatchProvenance(
      String runId, RunManifest manifest, String ownerCapabilityId) {
    return new ArtifactProvenance(
        runId,
        PLANNING_STAGE_ID,
        manifest == null || manifest.profileId() == null ? "unknown" : manifest.profileId(),
        manifest == null || manifest.profileVersion() == null ? "1" : manifest.profileVersion(),
        manifest == null || manifest.profileDigest() == null ? "unknown" : manifest.profileDigest(),
        ownerCapabilityId,
        PRODUCER_VERSION,
        manifest == null || manifest.dependencyClosureDigest() == null
            ? "unknown"
            : manifest.dependencyClosureDigest());
  }

  private PlanningSchedulerState applyCompletion(
      PlanningSchedulerState state, ResolvedCompilerNode node, List<SkillArtifact> outputs) {
    String invocationKey = invocationKey(node, state, outputs);
    PlanningSchedulerState next = scheduler.recordInvocation(state, invocationKey);
    LinkedHashSet<String> produced = new LinkedHashSet<>();
    for (SkillArtifact output : outputs) {
      if (output != null && output.type() != null) {
        produced.add(output.type().name());
      }
    }
    next = next.complete(node.skillId(), produced.toArray(String[]::new));
    if (produced.contains(SkillArtifactType.CHAIN_PLAN_GRAPH.name())
        || produced.contains(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name())) {
      next = scheduler.bumpGraphRevision(next);
    }
    GraphPatchArtifact patchArtifact = firstPatchArtifact(outputs);
    if (patchArtifact != null) {
      next = convergeAfterPatchArtifact(next, node.skillId(), patchArtifact);
    }
    return next;
  }

  static PlanningSchedulerState convergeAfterPatchArtifact(
      PlanningSchedulerState state, String ownerSkillId, GraphPatchArtifact patchArtifact) {
    if (patchArtifact == null || patchArtifact.applicability() != PatchApplicability.APPLICABLE) {
      return state;
    }
    Set<String> descendants = descendantsOf(state.dag(), ownerSkillId);
    if (descendants.isEmpty()) {
      return state;
    }
    LinkedHashSet<String> completed = new LinkedHashSet<>(state.completedSkillIds());
    completed.removeAll(descendants);
    return new PlanningSchedulerState(
        state.dag(),
        state.presentArtifactTypes(),
        completed,
        state.invocationKeys(),
        state.latestDigestByArtifactType(),
        state.invocationCount(),
        state.graphRevisionCount());
  }

  private static Set<String> descendantsOf(ResolvedCompilerDag dag, String ownerSkillId) {
    LinkedHashMap<String, Set<String>> outgoing = new LinkedHashMap<>();
    for (ResolvedCompilerNode node : dag.nodes()) {
      outgoing.computeIfAbsent(node.skillId(), ignored -> new LinkedHashSet<>());
    }
    for (ResolvedCompilerNode node : dag.nodes()) {
      for (String dependency : node.dependsOn()) {
        outgoing.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(node.skillId());
      }
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    queue.add(ownerSkillId);
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      for (String next : outgoing.getOrDefault(current, Set.of())) {
        if (result.add(next)) {
          queue.addLast(next);
        }
      }
    }
    return result;
  }

  private static String invocationKey(
      ResolvedCompilerNode node, PlanningSchedulerState state, List<SkillArtifact> outputs) {
    GraphPatchArtifact patchArtifact = firstPatchArtifact(outputs);
    if (patchArtifact != null
        && patchArtifact.invocationKey() != null
        && !patchArtifact.invocationKey().isBlank()) {
      return patchArtifact.invocationKey();
    }
    return (node.executionMode() == CompilerNodeExecutionMode.JAVA_ADAPTER
            ? node.adapterId()
            : node.skillId())
        + ":"
        + state.graphRevisionCount();
  }

  private static GraphPatchArtifact firstPatchArtifact(List<SkillArtifact> outputs) {
    if (outputs == null) {
      return null;
    }
    for (SkillArtifact output : outputs) {
      if (output == null || output.type() != SkillArtifactType.GRAPH_PATCH_ARTIFACT) {
        continue;
      }
      if (output.payload()
          instanceof SkillArtifactPayload.GraphPatchArtifactPayload(GraphPatchArtifact artifact)) {
        return artifact;
      }
    }
    return null;
  }

  private static void appendNewCompletions(
      List<String> executed, PlanningSchedulerState before, PlanningSchedulerState after) {
    for (String completed : after.completedSkillIds()) {
      if (!before.completedSkillIds().contains(completed)
          && !REQUIREMENT_ANALYZER_SKILL.equals(completed)
          && !executed.contains(completed)) {
        executed.add(completed);
      }
    }
  }

  private static boolean stopAfterAssemblyAndMandatoryValidators(
      ResolvedCompilerDag dag, PlanningSchedulerState state) {
    boolean assemblyMandatory = false;
    for (ResolvedCompilerNode node : dag.nodes()) {
      if (!node.mandatory()) {
        continue;
      }
      for (String produced : node.produces()) {
        if (SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()
            .equals(CompilerDerivedPlanningScheduler.normalizeArtifactType(produced))) {
          assemblyMandatory = true;
          break;
        }
      }
    }
    if (assemblyMandatory
        && !state.presentArtifactTypes().contains(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name())) {
      return false;
    }
    for (ResolvedCompilerNode node : dag.nodes()) {
      if (node.mandatory() && !state.completedSkillIds().contains(node.skillId())) {
        return false;
      }
    }
    return true;
  }

  private static PlanningSchedulerState seededState(
      ResolvedCompilerDag dag, CompilerExecutionSeed seed) {
    Set<String> present = new LinkedHashSet<>(seed.presentArtifactTypes());
    Set<String> completed = new LinkedHashSet<>(seed.preSatisfiedSkillIds());
    return new PlanningSchedulerState(dag, present, completed, Set.of(), java.util.Map.of(), 0, 0);
  }

  private PinnedRunContext resolvePinnedRun(CompilerDagExecutionRequest request) {
    RunManifest manifest = request.runManifest();
    if (manifest == null) {
      throw new IllegalStateException("contract failure: run manifest is required for pinned planning");
    }
    CompilerRunPin pin = manifest.compilerRunPin();
    if (pin == null) {
      throw new IllegalStateException("contract failure: compiler run pin is required");
    }
    if (request.executionDag() == null) {
      throw new IllegalStateException("contract failure: execution DAG is required");
    }
    return new PinnedRunContext(manifest, pin);
  }

  private GraphPatchExecutionContext buildExecutionContext(
      CompilerPlanningRequest request,
      CompilerExecutionSeed seed,
      SkillWorkspace workspace,
      ResolvedCompilerNode node,
      PinnedRunContext pinned) {
    if (!"captureGraphPatch".equals(node.captureTool()) && !"repairScriptBodies".equals(node.captureTool())) {
      return null;
    }
    var inputGraphArtifact = workspace.get(SkillArtifactType.CHAIN_PLAN_GRAPH).orElse(null);
    if (inputGraphArtifact == null) {
      return null;
    }
    var inputGraph = ((SkillArtifactPayload.ChainPlanGraphPayload) inputGraphArtifact.payload()).graph();
    return new GraphPatchExecutionContext(
        request.runId(),
        node.skillId(),
        sha256Text(seed.seedText()),
        canonicalGraphDigest.sha256(inputGraph),
        pinned.pin().compilerPackageDigest(),
        request.languageVersion(),
        request.requirementBrief(),
        pinned.manifest().sourceReferences(),
        inputGraph,
        ownershipFor(node),
        request.attemptId(),
        ChainEditSkillContext.targetNodeIds(workspace, node.skillId()));
  }

  private static GraphPatchOwnershipPolicy ownershipFor(ResolvedCompilerNode node) {
    return node.ownership() == null ? GraphPatchOwnershipPolicy.denyAll() : node.ownership();
  }

  private static String sha256Text(String value) {
    String text = value == null ? "" : value;
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * Writes the seed into the workspace the run owns. An isolated run clears that workspace first,
   * so nothing another conversation or an earlier run left behind can be read as this run's own
   * input.
   */
  private void applySeed(CompilerExecutionSeed seed) {
    if (seed.isolated()) {
      workspaceStore.clear(seed.workspaceId());
    }
    for (SkillArtifact artifact : seed.artifacts()) {
      workspaceStore.putArtifact(seed.workspaceId(), artifact);
    }
  }

  private CompilerDagExecutionResult toResult(
      String conversationId,
      List<String> executedSkillIds,
      PlanningPatchLedger patchLedger,
      List<PlanValidationFinding> degradations) {
    SkillWorkspace workspace = workspaceStore.getOrCreate(conversationId);
    ChainPlanGraph graph =
        workspace
            .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
            .map(a -> ((SkillArtifactPayload.ChainPlanGraphPayload) a.payload()).graph())
            .orElse(null);
    GraphAssemblyResult assemblyResult =
        workspace
            .get(SkillArtifactType.GRAPH_ASSEMBLY_RESULT)
            .map(a -> ((SkillArtifactPayload.GraphAssemblyResultPayload) a.payload()).result())
            .orElse(null);
    CompilerValidationBundle validationBundle =
        workspace
            .get(SkillArtifactType.COMPILER_VALIDATION_BUNDLE)
            .map(a -> ((SkillArtifactPayload.CompilerValidationBundlePayload) a.payload()).bundle())
            .orElse(null);
    LOG.infof(
        "Compiler DAG execution completed conversationId=%s executed=%s",
        conversationId, executedSkillIds);
    return new CompilerDagExecutionResult(
        StageOutcomeClass.SUCCEEDED,
        null,
        List.copyOf(executedSkillIds),
        patchLedger,
        graph,
        assemblyResult,
        validationBundle,
        List.copyOf(degradations));
  }

  private record PinnedRunContext(RunManifest manifest, CompilerRunPin pin) {
    private PinnedRunContext {
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(pin, "pin");
    }
  }

}
