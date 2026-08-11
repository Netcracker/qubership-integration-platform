package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairRunner;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.llm.agent.CompilerSkillAgent;
import org.qubership.integration.platform.ai.llm.agent.ChainPlanRepairAgent;
import org.qubership.integration.platform.ai.llm.agent.CreateChainPlanAgent;
import org.qubership.integration.platform.ai.llm.agent.DiscoveryAgent;
import org.qubership.integration.platform.ai.llm.agent.PatternSelectorAgent;
import org.qubership.integration.platform.ai.llm.agent.ScriptBodyRepairAgent;
import org.qubership.integration.platform.ai.llm.agent.ValidationAgent;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.ChainPlanRepairDraftStore;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.ChainPlanTool;
import org.qubership.integration.platform.ai.plan.RequirementBriefTool;
import org.qubership.integration.platform.ai.plan.SelectedPatternTool;
import org.qubership.integration.platform.ai.plan.ValidationResultTool;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemQipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContextStore;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchFixtures;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillRunStatus;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.orchestration.SkillSubgraph;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

@ExtendWith(MockitoExtension.class)
class CompilerSkillRuntimeTest {

  private static final String GENERATOR_ID = "cip-error-handling-generator";
  private static final String CHAIN_GENERATOR_ID = "cip-chain-generator";
  private static final String DISCOVERY_ID = "cip-requirement-analyzer";
  private static final String SCRIPT_GENERATOR_ID = "cip-script-generator";
  private static final String VALIDATOR_ID = "plan-validator";
  private static final String CONVERSATION_ID = "conv-compiler-runtime";

  @Mock private CompilerSkillAgent generatorAgent;
  @Mock private CreateChainPlanAgent createChainPlanAgent;
  @Mock private ChainPlanRepairAgent chainPlanRepairAgent;
  @Mock private ScriptBodyRepairAgent scriptBodyRepairAgent;
  @Mock private DiscoveryAgent discoveryAgent;
  @Mock private PatternSelectorAgent patternSelectorAgent;
  @Mock private ValidationAgent validationAgent;
  @Mock private CompilerPlanValidator compilerPlanValidator;
  @Mock private DeterministicElementSchemaService schemaService;
  @Mock private AppConfig appConfig;
  @Mock private ChatMemorySanitizer chatMemorySanitizer;

  private CaptureSession captureSession;
  private ChainPlanStore chainPlanStore;
  private ChainPlanRepairDraftStore chainPlanRepairDraftStore;
  private CaptureAttemptFeedbackStore feedbackStore;
  private FakeKnowledgeClient knowledgeClient;
  private GraphPatchExecutionContextStore executionContextStore;
  private CompilerSkillRuntime runtime;

  @BeforeEach
  void setUp(@TempDir Path outputDir) throws Exception {
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    QipKnowledgePackBuildGenerator.generate(QipKnowledgePackFixturePaths.packRoot(), outputDir);
    FilesystemQipKnowledgePackRepository repository =
        new FilesystemQipKnowledgePackRepository(
            outputDir, QipKnowledgePackFixturePaths.packVersion());
    captureSession = new CaptureSession();
    chainPlanStore = new ChainPlanStore();
    chainPlanRepairDraftStore = new ChainPlanRepairDraftStore();
    feedbackStore = new CaptureAttemptFeedbackStore();
    executionContextStore = new GraphPatchExecutionContextStore();
    knowledgeClient = FakeKnowledgeClient.defaultFixture();
    AppConfig.CaptureConfig captureConfig = mock(AppConfig.CaptureConfig.class);
    when(appConfig.capture()).thenReturn(captureConfig);
    when(captureConfig.maxRepairAttempts()).thenReturn(1);
    CaptureRepairRunner captureRepairRunner =
        new CaptureRepairRunner(
            new CaptureRepairMessageBuilder(schemaService), feedbackStore, appConfig);
    CaptureRepairMessageBuilder repairMessageBuilder =
        new CaptureRepairMessageBuilder(schemaService);
    CompilerSkillAddonRepository addonRepository =
        CompilerSkillAddonRepository.forFilesystem(
            outputDir,
            QipKnowledgePackFixturePaths.packVersion(),
            getClass().getClassLoader());
    runtime =
        new CompilerSkillRuntime(
            new CompilerSkillDocumentService(repository),
            new CompilerSkillContextBuilder(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                repository,
                addonRepository,
                new CompilerSkillRuntimeEligibility(repository),
                knowledgeClient,
                knowledgeClient),
            new CaptureRouter(addonRepository),
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
            new GraphPatchApplier(),
            new ChainPlanGraphValidator(schemaService),
            new GeneratorReadinessEvaluator(),
            feedbackStore,
            captureRepairRunner,
            repairMessageBuilder,
            chatMemorySanitizer,
            appConfig,
            repository,
            executionContextStore,
            schemaService);
  }

  @Test
  void applyCapturedEmptyPatchFailsWhenOwnedRequiredMissing() {
    String quartzSkill = "cip-quartz-scheduler-generator";
    when(schemaService.requiredPatchPropertyKeys("quartz-scheduler")).thenReturn(Set.of("cron"));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("dual-trigger", "Dual Trigger"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1", "quartz-scheduler", "Hourly", null, null, List.of())),
            List.of());
    GraphPatch emptyPatch =
        new GraphPatch(
            "quartz-empty",
            quartzSkill,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No schedule intent");
    MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, quartzSkill);
    executionContextStore.set(
        new GraphPatchExecutionContext(
            "run-1",
            quartzSkill,
            "req-1",
            null,
            "compiler-1",
            "24.4",
            new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary"),
            List.of(),
            graph,
            new GraphPatchOwnershipPolicy(
                false,
                false,
                Set.of(),
                Set.of(),
                Map.of("quartz-scheduler", Set.of("cron", "deleteJob"))),
            ""));

    SkillExecutionResult result =
        runtime.applyCapturedPatch(CONVERSATION_ID, graph, emptyPatch, quartzSkill);

    assertEquals(SkillRunStatus.FAILED, result.status());
    assertTrue(result.message().contains("cron"));
    assertTrue(result.message().contains("quartz-scheduler-1"));
  }

  @Test
  void acceptEmptyPatchWithNullInputGraphDoesNotNpe() throws Exception {
    GraphPatch emptyPatch =
        new GraphPatch(
            "noop",
            GENERATOR_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No changes required");
    putGraphPatch(GENERATOR_ID, emptyPatch);
    feedbackStore.recordPatchValidationFailure(CONVERSATION_ID, GENERATOR_ID, "prior failure");

    Method accept =
        CompilerSkillRuntime.class.getDeclaredMethod(
            "acceptCapturedGraphPatch", String.class, String.class, ChainPlanGraph.class);
    accept.setAccessible(true);
    boolean accepted =
        (boolean) accept.invoke(runtime, CONVERSATION_ID, GENERATOR_ID, null);

    assertTrue(accepted);
    assertTrue(feedbackStore.lastPatchFailure(CONVERSATION_ID, GENERATOR_ID).isEmpty());
  }

  @Test
  void emptyPatchLeavesGraphUnchanged() {
    ChainPlanGraph graph = greetingsGraph();
    GraphPatch patch =
        new GraphPatch(
            "noop",
            GENERATOR_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Static greeting does not require error handling");

    SkillExecutionResult result = runtime.applyCapturedPatch(graph, patch, GENERATOR_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    assertTrue(result.outputs().stream().anyMatch(a -> a.type() == SkillArtifactType.GRAPH_PATCH));
    assertFalse(
        result.outputs().stream().anyMatch(a -> a.type() == SkillArtifactType.CHAIN_PLAN_GRAPH));
    assertTrue(result.message().contains("does not require"));
  }

  @Test
  void runResolvesCapturedPatchWithoutCallingAgent() {
    ChainPlanGraph graph = greetingsGraph();
    GraphPatch patch =
        new GraphPatch(
            "noop",
            GENERATOR_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Static greeting does not require error handling");
    putGraphPatch(GENERATOR_ID, patch);

    SkillWorkspace workspace = workspaceWithGraph(graph);
    SkillRunContext context = runContext(GENERATOR_ID, 3);

    SkillExecutionResult result = runtime.resolveResultAfterStream(context, workspace, GENERATOR_ID);

    verify(generatorAgent, never()).chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    assertEquals(SkillRunStatus.COMPLETED, result.status());
    assertTrue(result.message().contains("does not require"));
  }

  @Test
  void appliesNonEmptyPatchAndReturnsArtifacts() {
    when(schemaService.allowedPatchPropertyKeys(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Set.of());

    ChainPlanGraph graph = greetingsGraph();
    GraphPatch patch = GraphPatchFixtures.wrapHttpTriggerFlow("http-trigger-1", "script-1");

    SkillExecutionResult result = runtime.applyCapturedPatch(graph, patch, GENERATOR_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    assertTrue(result.outputs().stream().anyMatch(a -> a.type() == SkillArtifactType.GRAPH_PATCH));
    assertTrue(
        result.outputs().stream().anyMatch(a -> a.type() == SkillArtifactType.CHAIN_PLAN_GRAPH),
        "Non-empty generator patches must update CHAIN_PLAN_GRAPH for implement");
    ChainPlanGraph patched =
        result.outputs().stream()
            .filter(a -> a.type() == SkillArtifactType.CHAIN_PLAN_GRAPH)
            .map(a -> ((SkillArtifactPayload.ChainPlanGraphPayload) a.payload()).graph())
            .findFirst()
            .orElseThrow();
    assertTrue(
        patched.nodes().stream().anyMatch(n -> "try-catch-finally-2".equals(n.type())),
        "Patched graph should include EH wrapper from the patch");
  }

  @Test
  void sanitizesMemoryBeforeRepairRetryAfterTerminalGraphPatchValidation() {
    AtomicInteger agentCalls = new AtomicInteger();
    when(generatorAgent.chat(eq(memoryId(GENERATOR_ID)), any()))
        .thenAnswer(
            invocation -> {
              if (agentCalls.incrementAndGet() == 1) {
                feedbackStore.recordPatchValidationFailure(
                    CONVERSATION_ID, GENERATOR_ID, "invalid HTTP method");
                return Multi.createFrom()
                    .failure(new CaptureValidationException("invalid HTTP method"));
              }
              putGraphPatch(
                  GENERATOR_ID,
                  GraphPatchFixtures.wrapHttpTriggerFlow("http-trigger-1", "script-1"));
              return Multi.createFrom().empty();
            });

    runtime
        .runStreaming(runContext(GENERATOR_ID, 3), workspaceWithGraph(greetingsGraph()), GENERATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();

    verify(generatorAgent, times(2)).chat(eq(memoryId(GENERATOR_ID)), any());
    // Initial route sanitize keeps the 1-arg parse default; before-retry uses validation summary.
    verify(chatMemorySanitizer).repairDanglingToolCalls(eq(memoryId(GENERATOR_ID)));
    verify(chatMemorySanitizer)
        .repairDanglingToolCalls(eq(memoryId(GENERATOR_ID)), contains("invalid HTTP method"));
  }

  @Test
  void generatorRetriesAfterApplyFailureThenSucceeds() {
    ChainPlanGraph graph = greetingsGraph();
    GraphPatch badPatch =
        new GraphPatch(
            "bad-edge",
            GENERATOR_ID,
            List.of(),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanEdge("edge-bad", "missing-node", "script-1", null),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            "Bad edge patch");
    GraphPatch fixedPatch =
        new GraphPatch(
            "noop",
            GENERATOR_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No changes required");
    AtomicInteger calls = new AtomicInteger();
    when(generatorAgent.chat(eq(memoryId(GENERATOR_ID)), any()))
        .thenAnswer(
            invocation -> {
              if (calls.getAndIncrement() == 0) {
                putGraphPatch(GENERATOR_ID, badPatch);
              } else {
                putGraphPatch(GENERATOR_ID, fixedPatch);
              }
              return io.smallrye.mutiny.Multi.createFrom().empty();
            });

    SkillWorkspace workspace = workspaceWithGraph(graph);
    SkillRunContext context = runContext(GENERATOR_ID, 3);

    runtime
        .runStreaming(context, workspace, GENERATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();
    SkillExecutionResult result = runtime.resolveResultAfterStream(context, workspace, GENERATOR_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    verify(generatorAgent, times(2)).chat(eq(memoryId(GENERATOR_ID)), any());
  }

  @Test
  void generatorStopsAfterSingleRepairBudgetAndFails() {
    ChainPlanGraph graph = greetingsGraph();
    GraphPatch badPatch =
        new GraphPatch(
            "bad-edge",
            GENERATOR_ID,
            List.of(),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanEdge("edge-bad", "missing-node", "script-1", null),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            "Bad edge patch");
    when(generatorAgent.chat(eq(memoryId(GENERATOR_ID)), any()))
        .thenAnswer(
            invocation -> {
              putGraphPatch(GENERATOR_ID, badPatch);
              return io.smallrye.mutiny.Multi.createFrom().empty();
            });

    SkillWorkspace workspace = workspaceWithGraph(graph);
    SkillRunContext context = runContext(GENERATOR_ID, 3);

    runtime
        .runStreaming(context, workspace, GENERATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();
    SkillExecutionResult result = runtime.resolveResultAfterStream(context, workspace, GENERATOR_ID);

    assertEquals(SkillRunStatus.FAILED, result.status());
    verify(generatorAgent, times(2)).chat(eq(memoryId(GENERATOR_ID)), any());
  }

  @Test
  void finishGeneratorRunDoesNotCallAgentWhenPatchMissing() {
    ChainPlanGraph graph = greetingsGraph();
    SkillWorkspace workspace = workspaceWithGraph(graph);
    SkillRunContext context = runContext(GENERATOR_ID, 3);

    SkillExecutionResult result = runtime.resolveResultAfterStream(context, workspace, GENERATOR_ID);

    verify(generatorAgent, never()).chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    assertEquals(SkillRunStatus.FAILED, result.status());
    assertEquals(CompilerGraphPatchTool.CAPTURE_REQUIRED_MESSAGE, result.message());
  }

  @Test
  void scriptGeneratorUsesScriptBodyRepairAgentDirectly() {
    ChainPlanGraph graph = graphWithMissingScriptBody();
    chainPlanStore.put(CONVERSATION_ID, graph);
    when(scriptBodyRepairAgent.chat(eq(memoryId(SCRIPT_GENERATOR_ID)), any()))
        .thenAnswer(
            invocation -> {
              putScriptRepair(SCRIPT_GENERATOR_ID, scriptRepairPatch("return 'Hello world!';"));
              return io.smallrye.mutiny.Multi.createFrom().empty();
            });

    SkillWorkspace workspace = workspaceWithGraph(graph);
    SkillRunContext context = runContext(SCRIPT_GENERATOR_ID, 7);

    runtime
        .runStreaming(context, workspace, SCRIPT_GENERATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();

    verify(generatorAgent, never()).chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(scriptBodyRepairAgent, times(1)).chat(eq(memoryId(SCRIPT_GENERATOR_ID)), any());
  }

  @Test
  void scriptGeneratorCompletesAfterDirectScriptBodyRepair() {
    ChainPlanGraph graph = graphWithMissingScriptBody();
    chainPlanStore.put(CONVERSATION_ID, graph);
    when(scriptBodyRepairAgent.chat(eq(memoryId(SCRIPT_GENERATOR_ID)), any()))
        .thenAnswer(
            invocation -> {
              putScriptRepair(SCRIPT_GENERATOR_ID, scriptRepairPatch("return 'Hello world!';"));
              return io.smallrye.mutiny.Multi.createFrom().empty();
            });

    SkillWorkspace workspace = workspaceWithGraph(graph);
    SkillRunContext context = runContext(SCRIPT_GENERATOR_ID, 7);

    runtime
        .runStreaming(context, workspace, SCRIPT_GENERATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();

    SkillExecutionResult result =
        runtime.resolveResultAfterStream(context, workspace, SCRIPT_GENERATOR_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    verify(generatorAgent, never()).chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(scriptBodyRepairAgent).chat(eq(memoryId(SCRIPT_GENERATOR_ID)), message.capture());
    assertTrue(message.getValue().contains("Runtime Context Package"));
    assertTrue(message.getValue().contains("package: fixture@1.0.0"));
    assertEquals(1, knowledgeClient.contextCalls());
  }

  @Test
  void scriptGeneratorRetriesAfterInvalidRepairPatchDuringStreaming() {
    ChainPlanGraph graph = graphWithMissingScriptBody();
    chainPlanStore.put(CONVERSATION_ID, graph);
    AtomicInteger calls = new AtomicInteger();
    when(scriptBodyRepairAgent.chat(eq(memoryId(SCRIPT_GENERATOR_ID)), any()))
        .thenAnswer(
            invocation -> {
              if (calls.getAndIncrement() == 0) {
                putScriptRepair(SCRIPT_GENERATOR_ID, new GraphPatch(
                        "script-repair-bad",
                        SCRIPT_GENERATOR_ID,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "Empty repair"));
              } else {
                putScriptRepair(SCRIPT_GENERATOR_ID, scriptRepairPatch("return 'Hello world!';"));
              }
              return io.smallrye.mutiny.Multi.createFrom().empty();
            });

    SkillWorkspace workspace = workspaceWithGraph(graph);
    SkillRunContext context = runContext(SCRIPT_GENERATOR_ID, 7);

    runtime
        .runStreaming(context, workspace, SCRIPT_GENERATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();

    SkillExecutionResult result =
        runtime.resolveResultAfterStream(context, workspace, SCRIPT_GENERATOR_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    verify(scriptBodyRepairAgent, times(2)).chat(eq(memoryId(SCRIPT_GENERATOR_ID)), any());
  }

  @Test
  void scriptGeneratorFailsWhenEmptyPatchLeavesBodiesMissing() {
    ChainPlanGraph graph = graphWithMissingScriptBody();
    GraphPatch emptyPatch =
        new GraphPatch(
            "noop",
            SCRIPT_GENERATOR_ID,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No script changes");

    SkillExecutionResult result = runtime.applyCapturedPatch(graph, emptyPatch, SCRIPT_GENERATOR_ID);

    assertEquals(SkillRunStatus.FAILED, result.status());
    assertTrue(result.message().contains("without script bodies"));
  }

  @Test
  void scriptGeneratorCompletesWithoutAgentCallWhenNothingToRepair() {
    ChainPlanGraph graph = greetingsGraph();
    chainPlanStore.put(CONVERSATION_ID, graph);

    SkillWorkspace workspace = workspaceWithGraph(graph);
    SkillRunContext context = runContext(SCRIPT_GENERATOR_ID, 7);

    runtime
        .runStreaming(context, workspace, SCRIPT_GENERATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();

    SkillExecutionResult result =
        runtime.resolveResultAfterStream(context, workspace, SCRIPT_GENERATOR_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    verify(scriptBodyRepairAgent, never())
        .chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void graphConstructionFailsWhenPlanMissing() {
    SkillExecutionResult result =
        runtime.finishGraphConstructionRun(CONVERSATION_ID, CHAIN_GENERATOR_ID);

    assertEquals(SkillRunStatus.FAILED, result.status());
    assertEquals(ChainPlanTool.CAPTURE_REQUIRED_MESSAGE, result.message());
  }

  @Test
  void graphConstructionCompletesWhenPlanCaptured() {
    ChainPlanGraph graph = greetingsGraph();
    chainPlanStore.put(CONVERSATION_ID, graph);
    captureSession.accept(
        CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, CONVERSATION_ID),
        graph,
        "ok",
        "dup");

    SkillExecutionResult result =
        runtime.finishGraphConstructionRun(CONVERSATION_ID, CHAIN_GENERATOR_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    assertTrue(
        result.outputs().stream().anyMatch(a -> a.type() == SkillArtifactType.CHAIN_PLAN_GRAPH));
  }

  @Test
  void graphConstructionFailsWhenSessionEmptyDespiteDurableGraph() {
    chainPlanStore.put(CONVERSATION_ID, greetingsGraph());

    SkillExecutionResult result =
        runtime.finishGraphConstructionRun(CONVERSATION_ID, CHAIN_GENERATOR_ID);

    assertEquals(SkillRunStatus.FAILED, result.status());
    assertEquals(ChainPlanTool.CAPTURE_REQUIRED_MESSAGE, result.message());
  }

  @Test
  void graphConstructionUsesConstrainedRepairWhenStaleDurableGraphExists() {
    when(createChainPlanAgent.chat(eq(memoryId(CHAIN_GENERATOR_ID)), any()))
        .thenAnswer(
            invocation -> {
              chainPlanStore.put(CONVERSATION_ID, greetingsGraph());
              chainPlanRepairDraftStore.put(CONVERSATION_ID, graphMissingSiblingEdge());
              feedbackStore.recordPlanValidationFailure(
                  CONVERSATION_ID,
                  "Plan validation failed:\n"
                      + "node 'b' (script) must have an execution edge to another sibling");
              return io.smallrye.mutiny.Multi.createFrom().empty();
            });
    when(chainPlanRepairAgent.chat(eq(memoryId(CHAIN_GENERATOR_ID)), any()))
        .thenAnswer(
            invocation -> {
              ChainPlanGraph graph = greetingsGraph();
              chainPlanStore.put(CONVERSATION_ID, graph);
              captureSession.accept(
                  CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, CONVERSATION_ID),
                  graph,
                  "ok",
                  "dup");
              return io.smallrye.mutiny.Multi.createFrom().empty();
            });

    SkillWorkspace workspace = new InMemorySkillWorkspace(CONVERSATION_ID);
    SkillRunContext context = runContext(CHAIN_GENERATOR_ID, 2);

    runtime
        .runStreaming(context, workspace, CHAIN_GENERATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();

    SkillExecutionResult result =
        runtime.resolveResultAfterStream(context, workspace, CHAIN_GENERATOR_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    verify(createChainPlanAgent).chat(eq(memoryId(CHAIN_GENERATOR_ID)), any());
    verify(chainPlanRepairAgent).chat(eq(memoryId(CHAIN_GENERATOR_ID)), any());
    verify(chatMemorySanitizer, atLeastOnce())
        .repairDanglingToolCalls(memoryId(CHAIN_GENERATOR_ID));
  }

  @Test
  void discoveryFailsWhenBriefMissing() {
    SkillExecutionResult result = runtime.finishDiscoveryRun(CONVERSATION_ID, DISCOVERY_ID);

    assertEquals(SkillRunStatus.FAILED, result.status());
    assertEquals(RequirementBriefTool.CAPTURE_REQUIRED_MESSAGE, result.message());
  }

  @Test
  void patternSelectionFailsWhenElementSkeletonMissing() {
    captureSession.accept(
        CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID),
        new org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern(
            "GP-01", "Protected HTTP API", "reason", null, List.of(), "summary"),
        "ok",
        "dup");

    SkillExecutionResult result =
        runtime.finishPatternAndSkeleton(CONVERSATION_ID, "cip-pattern-selector");

    assertEquals(SkillRunStatus.FAILED, result.status());
    assertEquals(SelectedPatternTool.SKELETON_REQUIRED_MESSAGE, result.message());
    assertFalse(
        result.outputs().stream().anyMatch(a -> a.type() == SkillArtifactType.SELECTED_PATTERN));
  }

  @Test
  void discoveryCompletesWhenBriefCaptured() {
    putBrief(new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
            "Greeting endpoint", List.of(), List.of(), List.of(), List.of(), "Return hello"));

    SkillExecutionResult result = runtime.finishDiscoveryRun(CONVERSATION_ID, DISCOVERY_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    assertTrue(
        result.outputs().stream().anyMatch(a -> a.type() == SkillArtifactType.REQUIREMENT_BRIEF));
  }

  @Test
  void discoveryRetriesWhenValidationFailedThenSucceeds() {
    AtomicInteger calls = new AtomicInteger();
    when(discoveryAgent.chat(eq(memoryId(DISCOVERY_ID)), any()))
        .thenAnswer(
            invocation -> {
              if (calls.getAndIncrement() == 0) {
                feedbackStore.recordPlanValidationFailure(
                    CONVERSATION_ID,
                    "Requirement brief needs a non-empty goal or summary.");
                return io.smallrye.mutiny.Multi.createFrom().empty();
              }
              putBrief(new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                      "Greeting endpoint",
                      List.of(),
                      List.of(),
                      List.of(),
                      List.of(),
                      "Return hello"));
              return io.smallrye.mutiny.Multi.createFrom().empty();
            });

    SkillWorkspace workspace = new InMemorySkillWorkspace(CONVERSATION_ID);
    SkillRunContext context = runContext(DISCOVERY_ID, 1);

    runtime
        .runStreaming(context, workspace, DISCOVERY_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();

    SkillExecutionResult result =
        runtime.resolveResultAfterStream(context, workspace, DISCOVERY_ID);

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    verify(discoveryAgent, times(2)).chat(eq(memoryId(DISCOVERY_ID)), any());
  }

  @Test
  void validatorFailsWhenCaptureMissing() {
    SkillExecutionResult result =
        runtime.finishValidatorRun(
            CONVERSATION_ID, VALIDATOR_ID, workspaceWithGraph(greetingsGraph()));

    assertEquals(SkillRunStatus.FAILED, result.status());
    assertEquals(ValidationResultTool.CAPTURE_REQUIRED_MESSAGE, result.message());
  }

  @Test
  void validatorStreamingUsesDeterministicValidatorWithoutLlm() {
    when(compilerPlanValidator.validate(any(PlanGraphValidationInput.class)))
        .thenReturn(new ValidationResult(true, List.of(), "Plan validation passed"));

    SkillWorkspace workspace = workspaceWithGraph(greetingsGraph());
    SkillRunContext context = runContext(VALIDATOR_ID, 1);

    runtime
        .runStreaming(context, workspace, VALIDATOR_ID)
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertTrue(captureSession.get(CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, CONVERSATION_ID), ValidationResult.class).isPresent());
    verify(validationAgent, never()).chat(any(), any());

    SkillExecutionResult result = runtime.resolveResultAfterStream(context, workspace, VALIDATOR_ID);
    assertEquals(SkillRunStatus.COMPLETED, result.status());
    assertEquals("Plan validation passed", result.message());
    // Once in streaming short-circuit, once again in finishValidatorRun merge.
    verify(compilerPlanValidator, times(2)).validate(any(PlanGraphValidationInput.class));
  }

  @Test
  void validatorCompletesWithBlockingArtifactsWhenDeterministicFindsBlockers() {
    when(compilerPlanValidator.validate(any(PlanGraphValidationInput.class)))
        .thenReturn(
            new ValidationResult(
                false,
                List.of(
                    new org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue(
                        "validation-1",
                        org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity.BLOCKER,
                        "Chain has no trigger element",
                        "plan-validator",
                        List.of(),
                        List.of(),
                        "Add a trigger")),
                "Plan validation failed with 1 blocker(s)"));
    putValidation(new ValidationResult(true, List.of(), "Plan validation passed"));

    SkillExecutionResult result =
        runtime.finishValidatorRun(
            CONVERSATION_ID, VALIDATOR_ID, workspaceWithGraph(greetingsGraph()));

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    assertTrue(
        result.outputs().stream()
            .anyMatch(
                artifact ->
                    artifact.type() == SkillArtifactType.PRE_BUILD_VALIDATION
                        && artifact.payload()
                            instanceof SkillArtifactPayload.ValidationResultPayload payload
                        && !payload.result().valid()));
    assertTrue(
        result.outputs().stream()
            .anyMatch(
                artifact ->
                    artifact.type() == SkillArtifactType.PLAN_CAPTURE_OUTCOME
                        && artifact.payload()
                            instanceof SkillArtifactPayload.PlanCaptureOutcomePayload payload
                        && !payload.captured()));
  }

  @Test
  void validatorCompletesWhenCaptureAndDeterministicPass() {
    when(compilerPlanValidator.validate(any(PlanGraphValidationInput.class)))
        .thenReturn(new ValidationResult(true, List.of(), "Plan validation passed"));
    putValidation(new ValidationResult(true, List.of(), "Plan validation passed"));

    SkillExecutionResult result =
        runtime.finishValidatorRun(
            CONVERSATION_ID, VALIDATOR_ID, workspaceWithGraph(greetingsGraph()));

    assertEquals(SkillRunStatus.COMPLETED, result.status());
    assertEquals("Plan validation passed", result.message());
    assertTrue(
        result.outputs().stream()
            .anyMatch(
                artifact ->
                    artifact.type() == SkillArtifactType.PLAN_CAPTURE_OUTCOME
                        && artifact.payload()
                            instanceof SkillArtifactPayload.PlanCaptureOutcomePayload payload
                        && payload.captured()));
  }

  private static ChainPlanGraph graphWithMissingScriptBody() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Greetings", "Greeting"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, List.of()),
            new ChainPlanNode("script-1", "script", "Response Script", null, 2, List.of())),
        List.of(new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null)));
  }

  private static ChainPlanGraph greetingsGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Greetings", "Greeting"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "HTTP Trigger", null, 1, List.of()),
            new ChainPlanNode(
                "script-1",
                "script",
                "Response Script",
                null,
                2,
                List.of(new PlanProperty("script", "return 'Hello world!';")))),
        List.of(new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null)));
  }

  private static ChainPlanGraph graphMissingSiblingEdge() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Missing sibling", "Missing sibling"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "HTTP Trigger", null, 1, List.of()),
            new ChainPlanNode("a", "script", "A", null, 2, List.of()),
            new ChainPlanNode("b", "script", "B", null, 3, List.of())),
        List.of(new ChainPlanEdge("edge-1", "trigger", "a", null)));
  }

  private static SkillWorkspace workspaceWithGraph(ChainPlanGraph graph) {
    SkillWorkspace workspace = new InMemorySkillWorkspace(CONVERSATION_ID);
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            GENERATOR_ID,
            new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    return workspace;
  }

  private static SkillRunContext runContext(String skillId, int stepIndex) {
    return new SkillRunContext(
        CONVERSATION_ID, skillId, QipKnowledgePackFixturePaths.PACK_DIR, SkillSubgraph.BUILD_CHAIN, stepIndex, false, "");
  }

  private static String memoryId(String skillId) {
    return CompilerSkillMemoryIds.forSkill(CONVERSATION_ID, skillId);
  }

  private static GraphPatch scriptRepairPatch(String scriptBody) {
    return new GraphPatch(
        "script-repair",
        SCRIPT_GENERATOR_ID,
        List.of(),
        List.of(),
        List.of(
            new PropertyPatch(
                GraphPatchOperation.ADD,
                "script-1",
                new PlanProperty("script", scriptBody))),
        List.of(),
        List.of(),
        "Scripts filled");
  }

  private void putGraphPatch(String capabilityId, GraphPatch patch) {
    captureSession.accept(
        CaptureKey.capability(CaptureSlot.GRAPH_PATCH, CONVERSATION_ID, capabilityId),
        patch,
        "ok",
        "dup");
  }

  private void putScriptRepair(String capabilityId, GraphPatch patch) {
    captureSession.accept(
        CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, CONVERSATION_ID, capabilityId),
        patch,
        "ok",
        "dup");
  }

  private void putBrief(RequirementBrief brief) {
    captureSession.accept(
        CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION_ID),
        brief,
        "ok",
        "dup");
  }

  private void putValidation(ValidationResult result) {
    captureSession.accept(
        CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, CONVERSATION_ID),
        result,
        "ok",
        "dup");
  }

}
