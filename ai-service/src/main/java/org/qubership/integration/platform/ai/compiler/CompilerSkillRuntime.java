package org.qubership.integration.platform.ai.compiler;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairRunner;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureMetrics;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifest;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.llm.agent.ChainPlanRepairAgent;
import org.qubership.integration.platform.ai.llm.agent.CompilerSkillAgent;
import org.qubership.integration.platform.ai.llm.agent.CreateChainPlanAgent;
import org.qubership.integration.platform.ai.llm.agent.DiscoveryAgent;
import org.qubership.integration.platform.ai.llm.agent.PatternSelectorAgent;
import org.qubership.integration.platform.ai.llm.agent.ScriptBodyRepairAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.llm.agent.ValidationAgent;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.ChainPlanTool;
import org.qubership.integration.platform.ai.plan.ChainPlanRepairDraftStore;
import org.qubership.integration.platform.ai.plan.ChainPlanRepairIssue;
import org.qubership.integration.platform.ai.plan.RequirementBriefTool;
import org.qubership.integration.platform.ai.plan.SelectedPatternTool;
import org.qubership.integration.platform.ai.plan.ValidationResultTool;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResultMerger;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/**
 * Generic runtime that executes one compiler skill and finishes via
 * phase-specific capture tools.
 */
@ApplicationScoped
public class CompilerSkillRuntime {

  private static final Logger LOG = Logger.getLogger(CompilerSkillRuntime.class);

  private final CompilerSkillDocumentService documentService;
  private final CompilerSkillContextBuilder contextBuilder;
  private final CaptureRouter captureRouter;
  private final CompilerSkillAgent generatorAgent;
  private final CreateChainPlanAgent createChainPlanAgent;
  private final ChainPlanRepairAgent chainPlanRepairAgent;
  private final ScriptBodyRepairAgent scriptBodyRepairAgent;
  private final DiscoveryAgent discoveryAgent;
  private final PatternSelectorAgent patternSelectorAgent;
  private final ValidationAgent validationAgent;
  private final CaptureSession captureSession;
  private final ChainPlanStore chainPlanStore;
  private final ChainPlanRepairDraftStore chainPlanRepairDraftStore;
  private final CompilerPlanValidator compilerPlanValidator;
  private final GraphPatchApplier patchApplier;
  private final ChainPlanGraphValidator graphValidator;
  private final GeneratorReadinessEvaluator readinessEvaluator;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final CaptureRepairRunner captureRepairRunner;
  private final CaptureRepairMessageBuilder repairMessageBuilder;
  private final ChatMemorySanitizer chatMemorySanitizer;
  private final CaptureFailureMetrics captureFailureMetrics;
  private final int maxRepairAttempts;
  private final GraphPatchExecutionContextStore executionContextStore;
  private final CanonicalGraphDigest canonicalGraphDigest;
  private final QipKnowledgePackRepository packRepository;
  private final DeterministicElementSchemaService schemaService;

  @Inject
  public CompilerSkillRuntime(
      CompilerSkillDocumentService documentService,
      CompilerSkillContextBuilder contextBuilder,
      CaptureRouter captureRouter,
      CompilerSkillAgent generatorAgent,
      CreateChainPlanAgent createChainPlanAgent,
      ChainPlanRepairAgent chainPlanRepairAgent,
      ScriptBodyRepairAgent scriptBodyRepairAgent,
      DiscoveryAgent discoveryAgent,
      PatternSelectorAgent patternSelectorAgent,
      ValidationAgent validationAgent,
      CaptureSession captureSession,
      ChainPlanStore chainPlanStore,
      ChainPlanRepairDraftStore chainPlanRepairDraftStore,
      CompilerPlanValidator compilerPlanValidator,
      GraphPatchApplier patchApplier,
      ChainPlanGraphValidator graphValidator,
      GeneratorReadinessEvaluator readinessEvaluator,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureRepairRunner captureRepairRunner,
      CaptureRepairMessageBuilder repairMessageBuilder,
      ChatMemorySanitizer chatMemorySanitizer,
      CaptureFailureMetrics captureFailureMetrics,
      AppConfig appConfig,
      GraphPatchExecutionContextStore executionContextStore,
      CanonicalGraphDigest canonicalGraphDigest,
      QipKnowledgePackRepository packRepository,
      DeterministicElementSchemaService schemaService) {
    this.documentService = documentService;
    this.contextBuilder = contextBuilder;
    this.captureRouter = captureRouter;
    this.generatorAgent = generatorAgent;
    this.createChainPlanAgent = createChainPlanAgent;
    this.chainPlanRepairAgent = chainPlanRepairAgent;
    this.scriptBodyRepairAgent = scriptBodyRepairAgent;
    this.discoveryAgent = discoveryAgent;
    this.patternSelectorAgent = patternSelectorAgent;
    this.validationAgent = validationAgent;
    this.captureSession = captureSession;
    this.chainPlanStore = chainPlanStore;
    this.chainPlanRepairDraftStore = chainPlanRepairDraftStore;
    this.compilerPlanValidator = compilerPlanValidator;
    this.patchApplier = patchApplier;
    this.graphValidator = graphValidator;
    this.readinessEvaluator = readinessEvaluator;
    this.feedbackStore = feedbackStore;
    this.captureRepairRunner = captureRepairRunner;
    this.repairMessageBuilder = repairMessageBuilder;
    this.chatMemorySanitizer = chatMemorySanitizer;
    this.captureFailureMetrics = captureFailureMetrics;
    this.maxRepairAttempts = appConfig.capture().maxRepairAttempts();
    this.executionContextStore =
        Objects.requireNonNull(executionContextStore, "executionContextStore");
    this.canonicalGraphDigest =
        Objects.requireNonNull(canonicalGraphDigest, "canonicalGraphDigest");
    this.packRepository = Objects.requireNonNull(packRepository, "packRepository");
    this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
  }

  /** Test constructor: share the same GraphPatchExecutionContextStore the test mutates. */
  CompilerSkillRuntime(
      CompilerSkillDocumentService documentService,
      CompilerSkillContextBuilder contextBuilder,
      CaptureRouter captureRouter,
      CompilerSkillAgent generatorAgent,
      CreateChainPlanAgent createChainPlanAgent,
      ChainPlanRepairAgent chainPlanRepairAgent,
      ScriptBodyRepairAgent scriptBodyRepairAgent,
      DiscoveryAgent discoveryAgent,
      PatternSelectorAgent patternSelectorAgent,
      ValidationAgent validationAgent,
      CaptureSession captureSession,
      ChainPlanStore chainPlanStore,
      ChainPlanRepairDraftStore chainPlanRepairDraftStore,
      CompilerPlanValidator compilerPlanValidator,
      GraphPatchApplier patchApplier,
      ChainPlanGraphValidator graphValidator,
      GeneratorReadinessEvaluator readinessEvaluator,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureRepairRunner captureRepairRunner,
      CaptureRepairMessageBuilder repairMessageBuilder,
      ChatMemorySanitizer chatMemorySanitizer,
      AppConfig appConfig,
      QipKnowledgePackRepository packRepository,
      GraphPatchExecutionContextStore executionContextStore,
      DeterministicElementSchemaService schemaService) {
    this(
        documentService,
        contextBuilder,
        captureRouter,
        generatorAgent,
        createChainPlanAgent,
        chainPlanRepairAgent,
        scriptBodyRepairAgent,
        discoveryAgent,
        patternSelectorAgent,
        validationAgent,
        captureSession,
        chainPlanStore,
        chainPlanRepairDraftStore,
        compilerPlanValidator,
        patchApplier,
        graphValidator,
        readinessEvaluator,
        feedbackStore,
        captureRepairRunner,
        repairMessageBuilder,
        chatMemorySanitizer,
        null, // CaptureFailureMetrics
        appConfig,
        Objects.requireNonNull(executionContextStore, "executionContextStore"),
        new CanonicalGraphDigest(new com.fasterxml.jackson.databind.ObjectMapper()),
        packRepository,
        Objects.requireNonNull(schemaService, "schemaService"));
  }

  public Multi<ChatEvent> runStreaming(SkillRunContext context, SkillWorkspace workspace, String capabilityId) {
    String conversationId = context.conversationId();
    ToolSession.bind(conversationId);
    Context toolSessionContext = ToolSession.attachedContext();
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, capabilityId);
    clearCaptureState(conversationId, capabilityId);

    CompilerSkillDocument document = documentService.loadByCapabilityId(capabilityId);
    if (!document.supported()) {
      ToolSession.clear();
      MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
      return Multi.createFrom()
          .item(ChatEvent.token("Compiler skill is not supported: " + capabilityId));
    }
    CaptureRoute route = captureRouter.routeFor(capabilityId);
    String memoryId = CompilerSkillMemoryIds.forSkill(conversationId, capabilityId);

    CompilerSkillInputSnapshot snapshot = contextBuilder.snapshotFromWorkspace(workspace);
    GeneratorPlan activePlan = readActivePlan(workspace, capabilityId);
    String userMessage = buildUserMessage(conversationId, document, snapshot, activePlan, route);
    ChainPlanGraph inputGraph = snapshot.chainPlanGraph();

    Multi<String> agentStream =
        ToolSession.propagateBinding(
            toolSessionContext,
            agentStreamFor(
                route, document.phase(), conversationId, memoryId, capabilityId, userMessage, inputGraph));

    return Multi.createFrom()
        .<ChatEvent>emitter(
            emitter -> agentStream
                .onTermination()
                .invoke(
                    () -> {
                      ToolSession.clear();
                      MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
                    })
                .subscribe()
                .with(item -> {
                }, emitter::fail, emitter::complete));
  }

  public Uni<SkillExecutionResult> run(
      SkillRunContext context, SkillWorkspace workspace, String capabilityId) {
    return Uni.createFrom().item(resolveResultAfterStream(context, workspace, capabilityId));
  }

  SkillExecutionResult resolveResultAfterStream(
      SkillRunContext context, SkillWorkspace workspace, String capabilityId) {
    CompilerSkillDocument document;
    try {
      document = documentService.loadByCapabilityId(capabilityId);
    } catch (CompilerSkillNotFoundException e) {
      return SkillExecutionResult.failed(e.getMessage());
    }
    if (!document.supported()) {
      return SkillExecutionResult.failed("Compiler skill is not supported: " + capabilityId);
    }
    CaptureRoute route;
    try {
      route = captureRouter.routeFor(capabilityId);
    } catch (IllegalStateException e) {
      return SkillExecutionResult.failed(e.getMessage());
    }

    return switch (route.captureTool()) {
      case CAPTURE_SELECTED_PATTERN ->
          finishPatternAndSkeleton(context.conversationId(), capabilityId);
      case CAPTURE_NAMING_MANIFEST ->
          finishNaming(context.conversationId(), capabilityId);
      case CAPTURE_CONFIGURED_TRIGGER_SET ->
          finishTriggers(context.conversationId(), capabilityId, workspace);
      case CAPTURE_CHAIN_STRUCTURE ->
          finishStructure(context.conversationId(), capabilityId);
      default -> finishExistingRoute(context, capabilityId, workspace, route, document.phase());
    };
  }

  private SkillExecutionResult finishExistingRoute(
      SkillRunContext context,
      String capabilityId,
      SkillWorkspace workspace,
      CaptureRoute route,
      QipKnowledgeCapabilityPhase phase) {
    return switch (phase) {
      case DISCOVERY -> finishDiscoveryRun(context.conversationId(), capabilityId, route);
      case GRAPH_CONSTRUCTION -> finishGraphConstructionRun(context.conversationId(), capabilityId);
      case GENERATOR -> finishGeneratorRun(context, capabilityId, workspace, route);
      case VALIDATOR -> finishValidatorRun(context.conversationId(), capabilityId, workspace);
      default ->
          SkillExecutionResult.failed("Generic compiler runtime does not support phase " + phase);
    };
  }

  SkillExecutionResult finishValidatorRun(
      String conversationId, String capabilityId, SkillWorkspace workspace) {
    ValidationResult captured =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, conversationId),
                ValidationResult.class)
            .orElse(null);
    if (captured == null) {
      return SkillExecutionResult.failed(ValidationResultTool.CAPTURE_REQUIRED_MESSAGE);
    }

    ChainPlanGraph graph;
    try {
      graph = requireGraph(workspace);
    } catch (IllegalStateException e) {
      return SkillExecutionResult.failed(e.getMessage());
    }

    ValidationResult deterministic =
        compilerPlanValidator.validate(new PlanGraphValidationInput(graph));
    ValidationResult merged = ValidationResultMerger.merge(captured, deterministic);

    boolean capturedPlan = merged.valid();
    return SkillExecutionResult.completed(
        List.of(
            SkillArtifact.of(
                SkillArtifactType.PRE_BUILD_VALIDATION,
                capabilityId,
                new SkillArtifactPayload.ValidationResultPayload(merged)),
            SkillArtifact.of(
                SkillArtifactType.PLAN_CAPTURE_OUTCOME,
                capabilityId,
                new SkillArtifactPayload.PlanCaptureOutcomePayload(
                    capturedPlan,
                    capturedPlan ? "Plan captured" : merged.summary()))),
        merged.summary());
  }

  SkillExecutionResult finishDiscoveryRun(
      String conversationId, String capabilityId, CaptureRoute route) {
    if (route.captureTool() == CaptureTool.CAPTURE_SELECTED_PATTERN) {
      return finishPatternSelectionRun(conversationId, capabilityId);
    }
    return captureSession
        .get(
            CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId),
            RequirementBrief.class)
        .map(
            brief ->
                SkillExecutionResult.completed(
                    List.of(
                        SkillArtifact.of(
                            SkillArtifactType.REQUIREMENT_BRIEF,
                            capabilityId,
                            new SkillArtifactPayload.RequirementBriefPayload(brief))),
                    "Discovery completed"))
        .orElse(SkillExecutionResult.failed(RequirementBriefTool.CAPTURE_REQUIRED_MESSAGE));
  }

  SkillExecutionResult finishDiscoveryRun(String conversationId, String capabilityId) {
    return finishDiscoveryRun(conversationId, capabilityId, captureRouter.routeFor(capabilityId));
  }

  SkillExecutionResult finishPatternSelectionRun(String conversationId, String capabilityId) {
    return captureSession
        .get(
            CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, conversationId),
            SelectedPattern.class)
        .map(
            pattern ->
                SkillExecutionResult.completed(
                    List.of(
                        SkillArtifact.of(
                            SkillArtifactType.SELECTED_PATTERN,
                            capabilityId,
                            new SkillArtifactPayload.SelectedPatternPayload(pattern))),
                    "Pattern selection completed"))
        .orElse(SkillExecutionResult.failed(SelectedPatternTool.CAPTURE_REQUIRED_MESSAGE));
  }

  SkillExecutionResult finishPatternAndSkeleton(String conversationId, String capabilityId) {
    SelectedPattern pattern =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, conversationId),
                SelectedPattern.class)
            .orElse(null);
    if (pattern == null) {
      return SkillExecutionResult.failed(SelectedPatternTool.CAPTURE_REQUIRED_MESSAGE);
    }
    ElementSkeleton skeleton =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.ELEMENT_SKELETON, conversationId),
                ElementSkeleton.class)
            .orElse(null);
    if (skeleton == null) {
      return SkillExecutionResult.failed(SelectedPatternTool.SKELETON_REQUIRED_MESSAGE);
    }
    return SkillExecutionResult.completed(
        List.of(
            SkillArtifact.of(
                SkillArtifactType.SELECTED_PATTERN,
                capabilityId,
                new SkillArtifactPayload.SelectedPatternPayload(pattern)),
            SkillArtifact.of(
                SkillArtifactType.ELEMENT_SKELETON,
                capabilityId,
                new SkillArtifactPayload.ElementSkeletonPayload(skeleton))),
        "Pattern selection completed");
  }

  SkillExecutionResult finishNaming(String conversationId, String capabilityId) {
    NamingManifest naming =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, conversationId),
                NamingManifest.class)
            .orElse(null);
    if (naming == null) {
      return SkillExecutionResult.failed(NamingManifestCaptureTool.CAPTURE_REQUIRED_MESSAGE);
    }
    return SkillExecutionResult.completed(
        List.of(
            SkillArtifact.of(
                SkillArtifactType.NAMING_MANIFEST,
                capabilityId,
                new SkillArtifactPayload.NamingManifestPayload(naming))),
        "Naming capture completed");
  }

  SkillExecutionResult finishTriggers(
      String conversationId, String capabilityId, SkillWorkspace workspace) {
    ConfiguredTriggerSet triggerSet =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId),
                ConfiguredTriggerSet.class)
            .orElse(null);
    if (triggerSet == null) {
      return SkillExecutionResult.failed(ConfiguredTriggerSetCaptureTool.CAPTURE_REQUIRED_MESSAGE);
    }
    List<SkillArtifact> outputs = new ArrayList<>();
    outputs.add(
        SkillArtifact.of(
            SkillArtifactType.CONFIGURED_TRIGGER_SET,
            capabilityId,
            new SkillArtifactPayload.ConfiguredTriggerSetPayload(triggerSet)));
    // Skill-orchestrator path never calls captureChainStructure; merge trigger endpoint props into
    // CHAIN_PLAN_GRAPH here so PropertiesApplier receives contextPath / httpMethodRestrict.
    ChainPlanGraph currentGraph =
        workspace == null
            ? null
            : workspace
                .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
                .map(a -> ((SkillArtifactPayload.ChainPlanGraphPayload) a.payload()).graph())
                .orElse(null);
    ChainPlanGraph enriched = ConfiguredTriggerSetGraphEnricher.enrich(currentGraph, triggerSet);
    if (enriched != null && enriched != currentGraph) {
      outputs.add(
          SkillArtifact.of(
              SkillArtifactType.CHAIN_PLAN_GRAPH,
              capabilityId,
              new SkillArtifactPayload.ChainPlanGraphPayload(enriched)));
      LOG.infof(
          "Merged ConfiguredTriggerSet properties into CHAIN_PLAN_GRAPH conversationId=%s"
              + " capabilityId=%s",
          conversationId,
          capabilityId);
    }
    return SkillExecutionResult.completed(List.copyOf(outputs), "Trigger capture completed");
  }

  SkillExecutionResult finishStructure(String conversationId, String capabilityId) {
    ChainStructure structure =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, conversationId),
                ChainStructure.class)
            .orElse(null);
    if (structure == null || structure.graph() == null) {
      return SkillExecutionResult.failed(ChainStructureCaptureTool.CAPTURE_REQUIRED_MESSAGE);
    }
    return SkillExecutionResult.completed(
        List.of(
            SkillArtifact.of(
                SkillArtifactType.CHAIN_STRUCTURE,
                capabilityId,
                new SkillArtifactPayload.ChainStructurePayload(structure)),
            SkillArtifact.of(
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                capabilityId,
                new SkillArtifactPayload.ChainPlanGraphPayload(structure.graph()))),
        "Structure capture completed");
  }

  SkillExecutionResult finishGraphConstructionRun(String conversationId, String capabilityId) {
    CaptureKey chainPlanKey = CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, conversationId);
    if (!captureSession.isPresent(chainPlanKey)) {
      return SkillExecutionResult.failed(ChainPlanTool.CAPTURE_REQUIRED_MESSAGE);
    }
    ChainPlanGraph plan = chainPlanStore.get(conversationId).orElse(null);
    if (plan == null) {
      return SkillExecutionResult.failed(ChainPlanTool.CAPTURE_REQUIRED_MESSAGE);
    }

    return SkillExecutionResult.completed(
        List.of(
            SkillArtifact.of(
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                capabilityId,
                new SkillArtifactPayload.ChainPlanGraphPayload(plan))),
        "Graph construction completed");
  }

  SkillExecutionResult finishGeneratorRun(
      SkillRunContext context, String capabilityId, SkillWorkspace workspace, CaptureRoute route) {
    String conversationId = context.conversationId();
    ChainPlanGraph graph;
    try {
      graph = requireGraph(workspace);
    } catch (IllegalStateException e) {
      return SkillExecutionResult.failed(e.getMessage());
    }
    return finishRun(conversationId, capabilityId, graph, route);
  }

  SkillExecutionResult finishRun(
      String conversationId, String capabilityId, ChainPlanGraph graph, CaptureRoute route) {
    CaptureSlot slot =
        route.captureTool() == CaptureTool.REPAIR_SCRIPT_BODIES
            ? CaptureSlot.SCRIPT_BODY_REPAIR
            : CaptureSlot.GRAPH_PATCH;
    GraphPatch patch =
        captureSession
            .get(CaptureKey.capability(slot, conversationId, capabilityId), GraphPatch.class)
            .orElse(null);
    if (patch == null) {
      String message =
          route.captureTool() == CaptureTool.REPAIR_SCRIPT_BODIES
              ? ScriptBodyRepairTool.CAPTURE_REQUIRED_MESSAGE
              : CompilerGraphPatchTool.CAPTURE_REQUIRED_MESSAGE;
      return SkillExecutionResult.failed(message);
    }
    return applyCapturedPatch(conversationId, graph, patch, capabilityId);
  }

  SkillExecutionResult applyCapturedPatch(
      ChainPlanGraph graph, GraphPatch patch, String capabilityId) {
    return applyCapturedPatch(null, graph, patch, capabilityId);
  }

  SkillExecutionResult applyCapturedPatch(
      String conversationId, ChainPlanGraph graph, GraphPatch patch, String capabilityId) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(patch, "patch");

    if (!capabilityId.equals(patch.ownerCapabilityId())) {
      return SkillExecutionResult.failed(
          "Graph patch ownerCapabilityId must be '" + capabilityId + "'");
    }

    if (isEmptyPatch(patch)) {
      SkillExecutionResult scriptFailure =
          scriptGeneratorFailureIfBodiesMissing(conversationId, capabilityId, graph);
      if (scriptFailure != null) {
        return scriptFailure;
      }
      List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
          findOwnedSchemaGaps(conversationId, capabilityId, graph);
      if (!gaps.isEmpty()) {
        return SkillExecutionResult.failed(
            OwnedSchemaRequiredPropertyGate.formatCorrectableMessage(capabilityId, gaps));
      }
      String message = patch.rationale() != null && !patch.rationale().isBlank()
          ? patch.rationale()
          : "Compiler skill produced no graph changes";
      return SkillExecutionResult.completed(
          List.of(
              SkillArtifact.of(
                  SkillArtifactType.GRAPH_PATCH,
                  capabilityId,
                  new SkillArtifactPayload.GraphPatchPayload(patch))),
          message);
    }

    GraphPatchExecutionContext executionContext = null;
    if (conversationId != null) {
      executionContext =
          executionContextStore
              .get(conversationId, capabilityId)
              .or(executionContextStore::current)
              .orElse(null);
    }
    ChainPlanGraph baseGraph =
        executionContext != null && executionContext.inputGraph() != null
            ? executionContext.inputGraph()
            : graph;
    List<String> declared =
        packRepository.loadCompilerGeneratorPolicy().readinessSignalsFor(capabilityId);
    GraphPatchPreviewValidator previewValidator =
        new GraphPatchPreviewValidator(
            new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), patchApplier),
            patchApplier,
            graphValidator,
            readinessEvaluator,
            canonicalGraphDigest);
    GraphPatchPreviewValidator.GraphPatchPreviewResult preview =
        previewValidator.validate(baseGraph, patch, executionContext, declared);
    if (GraphPatchPreviewValidator.digestMismatch(
        executionContext, preview.inputGraphDigest())) {
      return SkillExecutionResult.failed(
          "contract failure: harvest input graph digest mismatch");
    }
    if (!preview.ownershipResult().valid()) {
      return SkillExecutionResult.failed(
          "Compiler skill patch failed: " + preview.ownershipResult().summary());
    }
    if (!preview.structuralValidation().isEmpty()) {
      return SkillExecutionResult.failed(
          "Compiler skill patch produced invalid graph:\n"
              + String.join("\n", preview.structuralValidation()));
    }
    if (!preview.pass()) {
      return SkillExecutionResult.failed(
          "Compiler skill patch preview failed: readiness "
              + String.join(", ", preview.readinessGaps()));
    }

    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        findOwnedSchemaGaps(conversationId, capabilityId, preview.patchedGraph());
    if (!gaps.isEmpty()) {
      return SkillExecutionResult.failed(
          OwnedSchemaRequiredPropertyGate.formatCorrectableMessage(capabilityId, gaps));
    }

    SkillExecutionResult scriptFailure =
        scriptGeneratorFailureIfBodiesMissing(
            conversationId, capabilityId, preview.patchedGraph());
    if (scriptFailure != null) {
      return scriptFailure;
    }

    // Emit the patched graph so product planning keeps CHAIN_PLAN_GRAPH in sync. Without
    // this, generator propertyPatches stay only in GRAPH_PATCH and implement applies an empty plan.
    return SkillExecutionResult.completed(
        List.of(
            SkillArtifact.of(
                SkillArtifactType.GRAPH_PATCH,
                capabilityId,
                new SkillArtifactPayload.GraphPatchPayload(patch)),
            SkillArtifact.of(
                SkillArtifactType.CHAIN_PLAN_GRAPH,
                capabilityId,
                new SkillArtifactPayload.ChainPlanGraphPayload(preview.patchedGraph()))),
        patch.rationale() != null && !patch.rationale().isBlank()
            ? patch.rationale()
            : "Compiler skill patch applied");
  }

  /**
   * Wraps a tool-arguments failure hook so it also repairs the shared chat memory. A failed
   * argument mapping leaves the assistant {@code tool_call} in memory without a result; sanitizing
   * here keeps the next agent turn on this conversation well formed.
   */
  private Runnable sanitizingOnToolArgs(String memoryId, Runnable recordFailure) {
    return () -> {
      recordFailure.run();
      chatMemorySanitizer.repairDanglingToolCalls(memoryId);
    };
  }

  /**
   * Before an outer repair retry, fill unanswered tool_calls with honest dangling text. When the
   * last failure is VALIDATION with a non-blank summary, reuse that summary (capped locally at 800
   * chars). Otherwise keep the parse-default filler.
   */
  private Runnable sanitizingBeforeRepairRetry(
      String memoryId, Supplier<Optional<CaptureAttemptFeedback>> feedbackSupplier) {
    return () -> {
      Optional<CaptureAttemptFeedback> last = feedbackSupplier.get();
      if (last.isPresent()
          && last.get().kind() == CaptureFailureKind.VALIDATION
          && last.get().summary() != null
          && !last.get().summary().isBlank()) {
        String summary = last.get().summary();
        int max = 800;
        String dangling = summary.length() <= max ? summary : summary.substring(0, max);
        chatMemorySanitizer.repairDanglingToolCalls(memoryId, dangling);
        return;
      }
      chatMemorySanitizer.repairDanglingToolCalls(memoryId);
    };
  }

  private Multi<String> agentStreamFor(
      CaptureRoute route,
      QipKnowledgeCapabilityPhase phase,
      String conversationId,
      String memoryId,
      String capabilityId,
      String rawUserMessage,
      ChainPlanGraph inputGraph) {
    // The prompt carries generated design text. A brace in it — a mermaid arrow, a JSON example —
    // is content, not a Qute expression, and reaches the renderer as one unless escaped here.
    String userMessage = QuteUserMessageEscaping.escapeForAiServiceUserMessage(rawUserMessage);
    if (!toolMatchesPhase(route.captureTool(), phase)) {
      return Multi.createFrom()
          .failure(
              new IllegalStateException(
                  "Capture tool "
                      + route.captureTool().toolName()
                      + " is not valid for compiler phase "
                      + phase));
    }
    return switch (route.captureTool()) {
      case CAPTURE_REQUIREMENT_BRIEF -> {
        chatMemorySanitizer.repairDanglingToolCalls(memoryId);
        yield captureRepairRunner.runWithRepair(
              message -> discoveryAgent.chat(memoryId, message),
              () ->
                  captureSession.isPresent(
                      CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId)),
              () -> feedbackStore.lastPlanFailure(conversationId),
              sanitizingOnToolArgs(
                  memoryId,
                  () -> feedbackStore.recordPlanToolArgumentsFailure(
                      conversationId, "ToolArgumentsException")),
              route.captureTool().toolName(),
              userMessage,
              true,
              null,
              sanitizingBeforeRepairRetry(
                  memoryId, () -> feedbackStore.lastPlanFailure(conversationId)));
      }
      case CAPTURE_SELECTED_PATTERN -> {
        chatMemorySanitizer.repairDanglingToolCalls(memoryId);
        yield captureRepairRunner.runWithRepair(
              message -> patternSelectorAgent.chat(memoryId, message),
              () ->
                  captureSession.isPresent(
                      CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, conversationId)),
              () -> feedbackStore.lastPlanFailure(conversationId),
              sanitizingOnToolArgs(
                  memoryId,
                  () -> feedbackStore.recordPlanToolArgumentsFailure(
                      conversationId, "ToolArgumentsException")),
              route.captureTool().toolName(),
              userMessage,
              true,
              null,
              sanitizingBeforeRepairRetry(
                  memoryId, () -> feedbackStore.lastPlanFailure(conversationId)));
      }
      case CAPTURE_NAMING_MANIFEST -> {
        chatMemorySanitizer.repairDanglingToolCalls(memoryId);
        yield captureRepairRunner.runWithRepair(
            message -> generatorAgent.chat(memoryId, message),
            () ->
                captureSession.isPresent(
                    CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, conversationId)),
            () -> feedbackStore.lastPlanFailure(conversationId),
            sanitizingOnToolArgs(
                memoryId,
                () ->
                    feedbackStore.recordPlanToolArgumentsFailure(
                        conversationId, "ToolArgumentsException")),
            route.captureTool().toolName(),
            userMessage,
            true,
            null,
            sanitizingBeforeRepairRetry(
                memoryId, () -> feedbackStore.lastPlanFailure(conversationId)));
      }
      case CAPTURE_CONFIGURED_TRIGGER_SET -> {
        chatMemorySanitizer.repairDanglingToolCalls(memoryId);
        yield captureRepairRunner.runWithRepair(
            message -> generatorAgent.chat(memoryId, message),
            () ->
                captureSession.isPresent(
                    CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId)),
            () -> feedbackStore.lastPlanFailure(conversationId),
            sanitizingOnToolArgs(
                memoryId,
                () ->
                    feedbackStore.recordPlanToolArgumentsFailure(
                        conversationId, "ToolArgumentsException")),
            route.captureTool().toolName(),
            userMessage,
            true,
            null,
            sanitizingBeforeRepairRetry(
                memoryId, () -> feedbackStore.lastPlanFailure(conversationId)));
      }
      case CAPTURE_CHAIN_STRUCTURE -> {
        chatMemorySanitizer.repairDanglingToolCalls(memoryId);
        yield captureRepairRunner.runWithRepair(
            message -> createChainPlanAgent.chat(memoryId, message),
            () ->
                captureSession.isPresent(
                    CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, conversationId)),
            () -> feedbackStore.lastPlanFailure(conversationId),
            sanitizingOnToolArgs(
                memoryId,
                () ->
                    feedbackStore.recordPlanToolArgumentsFailure(
                        conversationId, "ToolArgumentsException")),
            route.captureTool().toolName(),
            userMessage,
            true,
            null,
            sanitizingBeforeRepairRetry(
                memoryId, () -> feedbackStore.lastPlanFailure(conversationId)));
      }
      case CAPTURE_CHAIN_PLAN -> {
        // ADR 0003 P4 PARTIAL: ChainPlanTool / ChainPlanRepairTool use CaptureToolOutcomeGateway
        // for soft→IDENTICAL_SPAM. Still special: retryValidationFailures=false + dedicated
        // runPlanRepairIfNeeded outer (not CaptureRepairRunner validation outer).
        chatMemorySanitizer.repairDanglingToolCalls(memoryId);
        yield graphConstructionStream(conversationId, memoryId, userMessage);
      }
      case CAPTURE_GRAPH_PATCH -> {
        chatMemorySanitizer.repairDanglingToolCalls(memoryId);
        yield graphPatchGeneratorStream(
            conversationId, memoryId, capabilityId, userMessage, inputGraph);
      }
      case REPAIR_SCRIPT_BODIES -> {
        chatMemorySanitizer.repairDanglingToolCalls(memoryId);
        yield scriptBodyRepairStream(
            conversationId, memoryId, capabilityId, userMessage, inputGraph);
      }
      case CAPTURE_VALIDATION_RESULT ->
          // CompilerPlanValidator is the authoritative deterministic gate and is merged
          // again in finishValidatorRun.
          deterministicValidationStream(conversationId, inputGraph);
    };
  }

  /**
   * Runs {@link CompilerPlanValidator} and stores the report so {@link #finishValidatorRun} can
   * complete without an LLM tool loop.
   */
  private Multi<String> deterministicValidationStream(
      String conversationId, ChainPlanGraph inputGraph) {
    if (inputGraph == null) {
      return Multi.createFrom()
          .failure(new IllegalStateException("ChainPlanGraph is required for plan-validator"));
    }
    ValidationResult result =
        compilerPlanValidator.validate(new PlanGraphValidationInput(inputGraph));
    captureSession.accept(
        CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, conversationId),
        result,
        "Plan validation completed (deterministic): " + result.summary(),
        "Validation result already captured. Do not call captureValidationResult again;"
            + " finish this turn without further tool calls.");
    LOG.infof(
        "plan-validator: deterministic validation conversationId=%s valid=%s summary='%s'",
        conversationId,
        result.valid(),
        result.summary());
    return Multi.createFrom()
        .item("Plan validation completed (deterministic): " + result.summary());
  }

  private Multi<String> graphConstructionStream(
      String conversationId, String memoryId, String rawUserMessage) {
    String userMessage = QuteUserMessageEscaping.escapeForAiServiceUserMessage(rawUserMessage);
    return captureRepairRunner
        .runWithRepair(
            message -> createChainPlanAgent.chat(memoryId, message),
            () ->
                captureSession.isPresent(
                    CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, conversationId)),
            () -> feedbackStore.lastPlanFailure(conversationId),
            sanitizingOnToolArgs(
                memoryId,
                () -> feedbackStore.recordPlanToolArgumentsFailure(
                    conversationId, "ToolArgumentsException")),
            "captureChainPlan",
            userMessage,
            false,
            null,
            sanitizingBeforeRepairRetry(
                memoryId, () -> feedbackStore.lastPlanFailure(conversationId)))
        .onCompletion()
        .switchTo(() -> runPlanRepairIfNeeded(conversationId, memoryId, 0));
  }

  private Multi<String> graphPatchGeneratorStream(
      String conversationId,
      String memoryId,
      String capabilityId,
      String userMessage,
      ChainPlanGraph inputGraph) {
    return captureRepairRunner.runWithRepair(
        message -> generatorAgent.chat(memoryId, message),
        () -> acceptCapturedGraphPatch(conversationId, capabilityId, inputGraph),
        () -> feedbackStore.lastPatchFailure(conversationId, capabilityId),
        sanitizingOnToolArgs(
            memoryId,
            () -> feedbackStore.recordPatchToolArgumentsFailure(
                conversationId, capabilityId, "ToolArgumentsException")),
        CaptureTool.CAPTURE_GRAPH_PATCH.toolName(),
        userMessage,
        true,
        feedback -> repairMessageBuilder.build(feedback, CaptureTool.CAPTURE_GRAPH_PATCH.toolName()),
        sanitizingBeforeRepairRetry(
            memoryId, () -> feedbackStore.lastPatchFailure(conversationId, capabilityId)));
  }

  private Multi<String> runPlanRepairIfNeeded(String conversationId, String memoryId, int repairIndex) {
    if (captureSession.isPresent(CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, conversationId))) {
      return Multi.createFrom().empty();
    }
    if (repairIndex >= maxRepairAttempts) {
      return Multi.createFrom().empty();
    }
    ChainPlanGraph draft = chainPlanRepairDraftStore.get(conversationId).orElse(null);
    if (draft == null || feedbackStore.lastPlanFailure(conversationId).isEmpty()) {
      return Multi.createFrom().empty();
    }
    List<ChainPlanRepairIssue> issues = graphValidator.diagnoseForRepair(draft);
    if (issues.isEmpty()) {
      return Multi.createFrom().empty();
    }
    String repairMessage = buildPlanRepairMessage(issues);
    LOG.infof(
        "Plan repair retry conversationId=%s repairIndex=%d issues=%d",
        conversationId,
        repairIndex + 1,
        issues.size());
    if (captureFailureMetrics != null) {
      captureFailureMetrics.recordOuterRepair("repairChainPlanPatch");
    }
    chatMemorySanitizer.repairDanglingToolCalls(memoryId);
    return chainPlanRepairAgent
        .chat(memoryId, repairMessage)
        .onFailure()
        .recoverWithMulti(
            error -> {
              if (isCaptureValidationFailure(error)) {
                // IDENTICAL_SPAM CVE ends the repair-agent turn; chain to the next dedicated
                // outer when budget remains (same role as CaptureRepairRunner CVE recover).
                return Multi.createFrom().empty();
              }
              return Multi.createFrom().failure(error);
            })
        .onCompletion()
        .switchTo(() -> runPlanRepairIfNeeded(conversationId, memoryId, repairIndex + 1));
  }

  private static boolean isCaptureValidationFailure(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof CaptureValidationException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private Multi<String> scriptBodyRepairStream(
      String conversationId,
      String memoryId,
      String capabilityId,
      String userMessage,
      ChainPlanGraph inputGraph) {
    ChainPlanGraph graph =
        inputGraph != null ? inputGraph : chainPlanStore.get(conversationId).orElse(null);
    if (graph == null) {
      return Multi.createFrom().empty();
    }
    List<String> missingNodeIds = missingScriptNodeIds(conversationId, capabilityId, graph);
    if (missingNodeIds.isEmpty()) {
      // An earlier generator (e.g. cip-auth-generator's M2M before-hook) may have already
      // filled the only script body between plan time and this turn. Nothing to repair is a
      // legitimate no-op, not a failure to call repairScriptBodies — record it as an empty
      // patch so finishRun's isEmptyPatch branch completes the skill instead of failing it.
      GraphPatch emptyPatch = emptyScriptRepairPatch(capabilityId);
      captureSession.accept(
          CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, conversationId, capabilityId),
          emptyPatch,
          "No script bodies missing; skipped.",
          "Script body repair patch already captured. Do not call repairScriptBodies again;"
              + " finish this turn without further tool calls.");
      return Multi.createFrom().empty();
    }
    return captureRepairRunner.runWithRepair(
        message -> scriptBodyRepairAgent.chat(memoryId, message),
        () -> acceptCapturedScriptRepair(conversationId, capabilityId, graph),
        () -> feedbackStore.lastPatchFailure(conversationId, capabilityId),
        sanitizingOnToolArgs(
            memoryId,
            () -> feedbackStore.recordPatchToolArgumentsFailure(
                conversationId, capabilityId, "ToolArgumentsException")),
        CaptureTool.REPAIR_SCRIPT_BODIES.toolName(),
        userMessage,
        true,
        feedback -> repairMessageBuilder.scriptBodiesRepairMessage(missingNodeIds, feedback),
        sanitizingBeforeRepairRetry(
            memoryId, () -> feedbackStore.lastPatchFailure(conversationId, capabilityId)));
  }

  private static GraphPatch emptyScriptRepairPatch(String capabilityId) {
    return new GraphPatch(
        "no-script-bodies-missing",
        capabilityId,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "No script bodies missing; skipped.");
  }

  private boolean acceptCapturedGraphPatch(
      String conversationId, String capabilityId, ChainPlanGraph inputGraph) {
    CaptureKey key = CaptureKey.capability(CaptureSlot.GRAPH_PATCH, conversationId, capabilityId);
    GraphPatch patch = captureSession.get(key, GraphPatch.class).orElse(null);
    if (patch == null) {
      return false;
    }
    if (!capabilityId.equals(patch.ownerCapabilityId())) {
      captureSession.clearIfSame(key, patch);
      feedbackStore.recordPatchValidationFailure(
          conversationId,
          capabilityId,
          "Graph patch ownerCapabilityId must be '" + capabilityId + "'");
      return false;
    }
    if (isEmptyPatch(patch)) {
      if (inputGraph != null) {
        List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
            findOwnedSchemaGaps(conversationId, capabilityId, inputGraph);
        if (!gaps.isEmpty()) {
          captureSession.clearIfSame(key, patch);
          feedbackStore.recordPatchValidationFailure(
              conversationId,
              capabilityId,
              OwnedSchemaRequiredPropertyGate.formatCorrectableMessage(capabilityId, gaps));
          return false;
        }
      }
      feedbackStore.clearPatch(conversationId, capabilityId);
      return true;
    }
    if (inputGraph == null) {
      captureSession.clearIfSame(key, patch);
      feedbackStore.recordPatchValidationFailure(
          conversationId, capabilityId, "CHAIN_PLAN_GRAPH is required");
      return false;
    }
    GraphPatchApplyResult applyResult = patchApplier.apply(inputGraph, patch);
    if (!applyResult.validationResult().valid()) {
      captureSession.clearIfSame(key, patch);
      feedbackStore.recordPatchValidationFailure(
          conversationId,
          capabilityId,
          "Patch apply failed: " + applyResult.validationResult().summary());
      return false;
    }
    List<String> graphErrors = graphValidator.validate(applyResult.graph());
    if (!graphErrors.isEmpty()) {
      captureSession.clearIfSame(key, patch);
      feedbackStore.recordPatchValidationFailure(
          conversationId,
          capabilityId,
          "Patch apply failed: " + String.join("\n", graphErrors));
      return false;
    }
    feedbackStore.clearPatch(conversationId, capabilityId);
    return true;
  }

  private boolean acceptCapturedScriptRepair(
      String conversationId, String capabilityId, ChainPlanGraph inputGraph) {
    CaptureKey key =
        CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, conversationId, capabilityId);
    GraphPatch patch = captureSession.get(key, GraphPatch.class).orElse(null);
    if (patch == null) {
      return false;
    }
    if (!capabilityId.equals(patch.ownerCapabilityId())) {
      captureSession.clearIfSame(key, patch);
      feedbackStore.recordPatchValidationFailure(
          conversationId,
          capabilityId,
          "Graph patch ownerCapabilityId must be '" + capabilityId + "'");
      return false;
    }
    if (inputGraph == null) {
      captureSession.clearIfSame(key, patch);
      feedbackStore.recordPatchValidationFailure(
          conversationId, capabilityId, "CHAIN_PLAN_GRAPH is required");
      return false;
    }
    GraphPatchApplyResult applyResult = patchApplier.apply(inputGraph, patch);
    if (!applyResult.validationResult().valid()) {
      captureSession.clearIfSame(key, patch);
      feedbackStore.recordPatchValidationFailure(
          conversationId,
          capabilityId,
          "Script repair patch failed: " + applyResult.validationResult().summary());
      return false;
    }
    List<String> stillMissing = readinessEvaluator.scriptNodesMissingBody(applyResult.graph());
    if (!stillMissing.isEmpty()) {
      captureSession.clearIfSame(key, patch);
      feedbackStore.recordPatchValidationFailure(
          conversationId,
          capabilityId,
          "Script repair patch is incomplete. Missing script node ids: "
              + String.join(", ", stillMissing)
              + ".");
      return false;
    }
    feedbackStore.clearPatch(conversationId, capabilityId);
    return true;
  }

  private static String buildPlanRepairMessage(List<ChainPlanRepairIssue> issues) {
    StringBuilder message = new StringBuilder();
    message.append("Repair the invalid ChainPlanGraph draft by calling repairChainPlanPatch.\n");
    message.append("Submit only edgePatches. Do not call captureChainPlan.\n");
    message.append("Validation diagnostics:\n");
    for (ChainPlanRepairIssue issue : issues) {
      message
          .append("- code=")
          .append(issue.code())
          .append(", nodeId=")
          .append(issue.nodeId())
          .append(", nodeType=")
          .append(issue.nodeType())
          .append(", parentNodeId=")
          .append(issue.parentNodeId())
          .append(", siblingNodeIds=")
          .append(issue.siblingNodeIds())
          .append(", expectedScopeNodeId=")
          .append(issue.expectedScopeNodeId())
          .append(", edgeId=")
          .append(issue.edgeId())
          .append(", invalidRefs=")
          .append(issue.invalidRefs())
          .append(", scopeEdges=")
          .append(issue.scopeEdges())
          .append('\n');
    }
    return message.toString();
  }

  private String buildUserMessage(
      String conversationId,
      CompilerSkillDocument document,
      CompilerSkillInputSnapshot snapshot,
      GeneratorPlan activePlan,
      CaptureRoute route) {
    if (route.captureTool() == CaptureTool.REPAIR_SCRIPT_BODIES) {
      ChainPlanGraph graph = snapshot.chainPlanGraph();
      List<String> missingNodeIds =
          graph != null ? readinessEvaluator.scriptNodesMissingBody(graph) : List.of();
      return contextBuilder.buildScriptRepairMessage(
          conversationId, document, snapshot, missingNodeIds);
    }
    return contextBuilder.buildUserMessage(
        conversationId, document, snapshot, activePlan, route.captureTool());
  }

  private static boolean toolMatchesPhase(CaptureTool captureTool, QipKnowledgeCapabilityPhase phase) {
    return switch (phase) {
      case DISCOVERY ->
          captureTool == CaptureTool.CAPTURE_REQUIREMENT_BRIEF
              || captureTool == CaptureTool.CAPTURE_SELECTED_PATTERN;
      case GRAPH_CONSTRUCTION ->
          captureTool == CaptureTool.CAPTURE_CHAIN_PLAN
              || captureTool == CaptureTool.CAPTURE_CHAIN_STRUCTURE;
      case GENERATOR ->
          captureTool == CaptureTool.CAPTURE_GRAPH_PATCH
              || captureTool == CaptureTool.REPAIR_SCRIPT_BODIES
              || captureTool == CaptureTool.CAPTURE_NAMING_MANIFEST
              || captureTool == CaptureTool.CAPTURE_CONFIGURED_TRIGGER_SET;
      case VALIDATOR -> captureTool == CaptureTool.CAPTURE_VALIDATION_RESULT;
      default -> false;
    };
  }

  private void clearCaptureState(String conversationId, String capabilityId) {
    feedbackStore.clearAll(conversationId);
    CaptureRoute route = captureRouter.routeFor(capabilityId);
    switch (route.captureTool()) {
      case CAPTURE_REQUIREMENT_BRIEF ->
          captureSession.clear(
              CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId));
      case CAPTURE_SELECTED_PATTERN -> {
        captureSession.clear(
            CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, conversationId));
        captureSession.clear(
            CaptureKey.conversation(CaptureSlot.ELEMENT_SKELETON, conversationId));
      }
      case CAPTURE_NAMING_MANIFEST ->
          captureSession.clear(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, conversationId));
      case CAPTURE_CONFIGURED_TRIGGER_SET ->
          captureSession.clear(
              CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId));
      case CAPTURE_CHAIN_STRUCTURE ->
          captureSession.clear(CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, conversationId));
      case CAPTURE_CHAIN_PLAN -> {
        captureSession.clear(CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, conversationId));
        chainPlanStore.remove(conversationId);
        chainPlanRepairDraftStore.remove(conversationId);
      }
      case CAPTURE_GRAPH_PATCH, REPAIR_SCRIPT_BODIES -> {
        CaptureSlot slot =
            route.captureTool() == CaptureTool.REPAIR_SCRIPT_BODIES
                ? CaptureSlot.SCRIPT_BODY_REPAIR
                : CaptureSlot.GRAPH_PATCH;
        captureSession.clear(CaptureKey.capability(slot, conversationId, capabilityId));
      }
      case CAPTURE_VALIDATION_RESULT ->
          captureSession.clear(
              CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, conversationId));
    }
  }

  private List<OwnedSchemaRequiredPropertyGate.Gap> findOwnedSchemaGaps(
      String conversationId, String capabilityId, ChainPlanGraph graph) {
    GraphPatchOwnershipPolicy ownership = GraphPatchOwnershipPolicy.denyAll();
    List<String> targetNodeIds = List.of();
    if (conversationId != null) {
      GraphPatchExecutionContext context =
          executionContextStore
              .get(conversationId, capabilityId)
              .or(executionContextStore::current)
              .orElse(null);
      if (context != null) {
        ownership = context.ownership();
        targetNodeIds = context.editTargetNodeIds();
      }
    }
    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(
            graph, ownership, schemaService::requiredPatchPropertyKeys);
    if (targetNodeIds == null || targetNodeIds.isEmpty()) {
      return gaps;
    }
    List<String> scopedTargets = targetNodeIds;
    return gaps.stream().filter(gap -> scopedTargets.contains(gap.nodeId())).toList();
  }

  private SkillExecutionResult scriptGeneratorFailureIfBodiesMissing(
      String conversationId, String capabilityId, ChainPlanGraph graph) {
    if (!ScriptBodyPromptRedaction.SCRIPT_GENERATOR_CAPABILITY.equals(capabilityId)) {
      return null;
    }
    List<String> missing = missingScriptNodeIds(conversationId, capabilityId, graph);
    if (missing.isEmpty()) {
      return null;
    }
    return SkillExecutionResult.failed(
        "Script generator completed without script bodies for nodes: "
            + String.join(", ", missing));
  }

  private List<String> missingScriptNodeIds(
      String conversationId, String capabilityId, ChainPlanGraph graph) {
    List<String> missing = readinessEvaluator.scriptNodesMissingBody(graph);
    if (conversationId == null) {
      return missing;
    }
    List<String> targets =
        executionContextStore
            .get(conversationId, capabilityId)
            .or(executionContextStore::current)
            .map(GraphPatchExecutionContext::editTargetNodeIds)
            .orElse(List.of());
    if (targets == null || targets.isEmpty()) {
      return missing;
    }
    return missing.stream().filter(targets::contains).toList();
  }

  private static boolean isEmptyPatch(GraphPatch patch) {
    return empty(patch.nodePatches())
        && empty(patch.edgePatches())
        && empty(patch.propertyPatches())
        && empty(patch.chainPatches());
  }

  private static boolean empty(List<?> values) {
    return values == null || values.isEmpty();
  }

  private static GeneratorPlan readActivePlan(SkillWorkspace workspace, String capabilityId) {
    return workspace
        .get(SkillArtifactType.GENERATOR_PLAN_MANIFEST)
        .map(
            artifact -> {
              GeneratorPlanManifest manifest = ((SkillArtifactPayload.GeneratorPlanManifestPayload) artifact.payload())
                  .manifest();
              return manifest.plans().stream()
                  .filter(plan -> capabilityId.equals(plan.skillId()))
                  .findFirst()
                  .orElse(null);
            })
        .orElse(null);
  }

  private static ChainPlanGraph requireGraph(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
        .map(a -> ((SkillArtifactPayload.ChainPlanGraphPayload) a.payload()).graph())
        .orElseThrow(() -> new IllegalStateException("CHAIN_PLAN_GRAPH is required"));
  }
}
