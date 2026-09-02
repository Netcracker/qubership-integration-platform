package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeploy;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeployStore;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.OpenChainTurnContext;
import org.qubership.integration.platform.ai.chat.OpenChainTurnContextFactory;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ChatExecutionServiceTest {

  private static final String CONVERSATION_ID = "conv-chat-trace";
  private static final Instant FIXED = Instant.parse("2026-07-27T10:00:00Z");

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ConversationService conversations;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobs, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runStore = new ProductPipelineRunStore(blobs, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    ToolInvocationSink.unbind();
  }

  @ParameterizedTest
  @MethodSource("deployChainCardActions")
  void deployChainCardActionsRunAsScenarioWithDeployChainHint(String action) {
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("ok")));
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.openDecision(anyString())).thenReturn(Optional.empty());

    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-redeploy");
    ChatDecisionCommand decision = new ChatDecisionCommand();
    decision.setAction(action);
    decision.setArtifactHash("redeploy-op-1");
    request.setDecision(decision);

    service(router, decisions).streamV1Sse(request).collect().asList().await().indefinitely();

    ArgumentCaptor<ChatRequest> routed = ArgumentCaptor.forClass(ChatRequest.class);
    verify(router).route(routed.capture(), eq("conv-redeploy"));
    assertEquals(ScenarioType.DEPLOY_CHAIN, routed.getValue().getScenarioHint());
    verify(decisions, never()).apply(anyString(), any());
  }

  @Test
  void pendingDomainWaitFollowUpHintsDeployChain() {
    PendingRedeployStore store = new PendingRedeployStore();
    store.put("conv-wait", PendingRedeploy.domainWait("chain-1", "snap-1", false));
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("ok")));
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.openDecision(anyString())).thenReturn(Optional.empty());

    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-wait");
    request.setMessage("prod");

    service(router, decisions, store).streamV1Sse(request).collect().asList().await().indefinitely();

    ArgumentCaptor<ChatRequest> routed = ArgumentCaptor.forClass(ChatRequest.class);
    verify(router).route(routed.capture(), eq("conv-wait"));
    assertEquals(ScenarioType.DEPLOY_CHAIN, routed.getValue().getScenarioHint());
  }

  @Test
  void deployCardMarkerUsesPendingDomain() {
    PendingRedeployStore store = new PendingRedeployStore();
    store.put(
        "conv-redeploy",
        new PendingRedeploy("chain-1", "prod", null, "op-prod", "snap-1", false));
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("ok")));
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.openDecision(anyString())).thenReturn(Optional.empty());

    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-redeploy");
    ChatDecisionCommand decision = new ChatDecisionCommand();
    decision.setAction(ChatEvent.DEPLOY_ACTION);
    decision.setArtifactHash("op-prod");
    request.setDecision(decision);

    service(router, decisions, store).streamV1Sse(request).collect().asList().await().indefinitely();

    ArgumentCaptor<ChatRequest> routed = ArgumentCaptor.forClass(ChatRequest.class);
    verify(router).route(routed.capture(), eq("conv-redeploy"));
    assertEquals("Deploy the chain on domain prod", routed.getValue().getEffectiveUserText());
  }

  static Stream<String> deployChainCardActions() {
    return Stream.of(
        ChatEvent.REDEPLOY_ACTION,
        ChatEvent.CANCEL_REDEPLOY_ACTION,
        ChatEvent.DEPLOY_ACTION,
        ChatEvent.CANCEL_DEPLOY_ACTION,
        ChatEvent.UNDEPLOY_ACTION,
        ChatEvent.CANCEL_UNDEPLOY_ACTION,
        ChatEvent.REFRESH_DEPLOYMENT_ACTION,
        ChatEvent.DISMISS_DEPLOYMENT_FAILURE_ACTION,
        ChatEvent.SESSION_LOGGING_OFF_ACTION,
        ChatEvent.SESSION_LOGGING_ERROR_ACTION,
        ChatEvent.SESSION_LOGGING_INFO_ACTION,
        ChatEvent.SESSION_LOGGING_DEBUG_ACTION);
  }

  @Test
  void proposeDeploymentFixRunsAsPatchScenario() {
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("ok")));
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.openDecision(anyString())).thenReturn(Optional.empty());
    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-failed-deploy");
    ChatDecisionCommand decision = new ChatDecisionCommand();
    decision.setAction(ChatEvent.PROPOSE_DEPLOYMENT_FIX_ACTION);
    decision.setArtifactHash("dep-failed");
    request.setDecision(decision);

    service(router, decisions).streamV1Sse(request).collect().asList().await().indefinitely();

    ArgumentCaptor<ChatRequest> routed = ArgumentCaptor.forClass(ChatRequest.class);
    verify(router).route(routed.capture(), eq("conv-failed-deploy"));
    assertEquals(ScenarioType.COMPARE_AND_PATCH, routed.getValue().getScenarioHint());
    verify(decisions, never()).apply(anyString(), any());
  }

  @Test
  void streamSseSetsOpenChainTurnContextOnRoutedRequest() {
    OpenChainTurnContext turnContext =
        new OpenChainTurnContext(
            "conv-turn-ctx",
            "chain-1",
            "hello",
            "user: hello",
            Optional.empty(),
            Optional.empty(),
            false);
    OpenChainTurnContextFactory turnContextFactory = mock(OpenChainTurnContextFactory.class);
    when(turnContextFactory.build(any(), anyString())).thenReturn(turnContext);
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("ok")));
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.openDecision(anyString())).thenReturn(Optional.empty());

    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-turn-ctx");
    request.setMessage("hello");

    service(router, decisions, new PendingRedeployStore(), turnContextFactory)
        .streamV1Sse(request)
        .collect()
        .asList()
        .await()
        .indefinitely();

    verify(turnContextFactory).build(any(ChatRequest.class), eq("conv-turn-ctx"));
    ArgumentCaptor<ChatRequest> routed = ArgumentCaptor.forClass(ChatRequest.class);
    verify(router).route(routed.capture(), eq("conv-turn-ctx"));
    assertEquals(turnContext, routed.getValue().getOpenChainTurnContext());
  }

  @Test
  void factoryNpeEmitsErrorThenDoneNotToken() {
    OpenChainTurnContextFactory turnContextFactory = mock(OpenChainTurnContextFactory.class);
    when(turnContextFactory.build(any(), anyString()))
        .thenThrow(new NullPointerException("catalog npe"));
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("ok")));
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.openDecision(anyString())).thenReturn(Optional.empty());

    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-factory-npe");
    request.setMessage("hello");

    Multi<String> stream;
    try {
      stream =
          service(router, decisions, new PendingRedeployStore(), turnContextFactory)
              .streamV1Sse(request);
    } catch (RuntimeException e) {
      fail("streamSse must return a Multi, not throw: " + e);
      return;
    }

    List<String> frames = stream.collect().asList().await().indefinitely();

    assertTrue(
        frames.stream().anyMatch(frame -> frame.startsWith("event: error\n")),
        () -> "expected event: error, got: " + frames);
    assertTrue(
        frames.stream().anyMatch(frame -> frame.startsWith("event: done\n")),
        () -> "expected event: done, got: " + frames);
    assertFalse(
        frames.stream().anyMatch(frame -> frame.startsWith("event: token\n")),
        () -> "NPE must not become a token, got: " + frames);
    verify(router, never()).route(any(), anyString());
  }

  @Test
  void aDecisionEventIsStoredAsAssistantWithTheQuestion() {
    ChatEvent.Decision decision =
        new ChatEvent.Decision(
            "clarify:1",
            "clarify",
            "Which engine?",
            null,
            null,
            1L,
            null,
            List.of(),
            List.of("engine-a", "engine-b"));
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(), anyString()))
        .thenReturn(Multi.createFrom().item(decision));
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.openDecision(anyString())).thenReturn(Optional.empty());

    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-decision-persist");
    request.setMessage("create a chain");

    service(router, decisions).streamV1Sse(request).collect().asList().await().indefinitely();

    List<ConversationMessage> assistants =
        conversations.getMessages("conv-decision-persist").stream()
            .filter(message -> message.role() == ConversationMessage.Role.ASSISTANT)
            .toList();
    assertEquals(1, assistants.size());
    assertEquals(
        "[decision kind=clarify actions=engine-a,engine-b] Which engine?",
        assistants.get(0).content());
  }

  @Test
  void aDecisionEventIsObservableSseOutput() {
    ChatEvent.Decision decision =
        new ChatEvent.Decision(
            "clarify:1",
            "clarify",
            "That stage is not a candidate for this defect.",
            null,
            null,
            1L,
            null,
            List.of(),
            List.of("stop-with-report"));

    String frame = ChatExecutionService.toSse(decision, new ObjectMapper());

    assertTrue(frame.startsWith("event: decision\n"), frame);
    assertTrue(frame.contains("stop-with-report"), frame);
    assertTrue(frame.contains("That stage is not a candidate for this defect."), frame);
  }

  @Test
  void contextualRecoveryIsObservableSseOutput() {
    ChatEvent.Decision decision =
        new ChatEvent.Decision(
            "clarify:7",
            "clarify",
            "The provider temporarily limited requests.",
            null,
            null,
            7L,
            null,
            List.of(),
            List.of(ChatEvent.RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
            new ChatEvent.RecoveryPresentation(
                "temporary-technical-failure",
                "Creation paused temporarily",
                "The provider temporarily limited requests.",
                "Your approved requirements and plan are saved.",
                "rate_limit_exceeded",
                2_000L,
                "run-1",
                "design-execution"));

    String frame = ChatExecutionService.toSse(decision, new ObjectMapper());

    assertTrue(frame.contains("\"recovery\""), frame);
    assertTrue(frame.contains("temporary-technical-failure"), frame);
    assertTrue(frame.contains("rate_limit_exceeded"), frame);
    assertFalse(frame.contains("__GATE:"), frame);
  }

  @Test
  void contextualBriefDefectIsObservableSseOutput() {
    ChatEvent.Decision decision =
        new ChatEvent.Decision(
            "clarify:7",
            "clarify",
            "The approved requirements need correction.",
            null,
            null,
            7L,
            null,
            List.of(),
            List.of(ChatEvent.EDIT_REQUIREMENTS_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
            new ChatEvent.RecoveryPresentation(
                "requirement-brief-defect",
                "Requirements need correction",
                "The approved requirements need correction.",
                "Your approved product facts stay available.",
                "PLAN_BLOCKER: missing quartz",
                null,
                "run-1",
                "planning"));

    String frame = ChatExecutionService.toSse(decision, new ObjectMapper());

    assertTrue(frame.contains("\"recovery\""), frame);
    assertTrue(frame.contains("requirement-brief-defect"), frame);
    assertTrue(frame.contains("edit-requirements"), frame);
    assertFalse(frame.contains("requirement-analysis"), frame);
    assertFalse(frame.contains("__GATE:"), frame);
  }

  @Test
  void contextualPlanDefectIsObservableSseOutput() {
    ChatEvent.Decision decision =
        new ChatEvent.Decision(
            "clarify:9",
            "clarify",
            "The plan is missing information required to create the chain.",
            null,
            null,
            9L,
            null,
            List.of(),
            List.of(ChatEvent.REBUILD_PLAN_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
            new ChatEvent.RecoveryPresentation(
                "plan-artifact-defect",
                "The plan cannot be used",
                "The plan is missing information required to create the chain.",
                "Your approved requirements stay unchanged.",
                "PLAN_BLOCKER: invalid graph edge",
                null,
                "run-1",
                "design-execution"));

    String frame = ChatExecutionService.toSse(decision, new ObjectMapper());

    assertTrue(frame.contains("\"recovery\""), frame);
    assertTrue(frame.contains("plan-artifact-defect"), frame);
    assertTrue(frame.contains("rebuild-plan"), frame);
    assertFalse(frame.contains("design-planning"), frame);
    assertFalse(frame.contains("__GATE:"), frame);
  }

  @Test
  void contextualEnvironmentFailureIsObservableSseOutput() {
    ChatEvent.Decision decision =
        new ChatEvent.Decision(
            "clarify:10",
            "clarify",
            "This region is not supported for chain creation.",
            null,
            null,
            10L,
            null,
            List.of(),
            List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
            new ChatEvent.RecoveryPresentation(
                "permanent-environment-failure",
                "Creation cannot continue here",
                "This region is not supported for chain creation.",
                "Your approved requirements and plan are saved.",
                "PKIX path building failed (runId=run-1)",
                null,
                "run-1",
                "design-execution"));

    String frame = ChatExecutionService.toSse(decision, new ObjectMapper());

    assertTrue(frame.contains("\"recovery\""), frame);
    assertTrue(frame.contains("permanent-environment-failure"), frame);
    assertTrue(frame.contains("stop-with-report"), frame);
    assertFalse(frame.contains("retry-creation"), frame);
    assertFalse(frame.contains("__GATE:"), frame);
  }

  @Test
  void contextualInternalFailureIsObservableSseOutput() {
    ChatEvent.Decision decision =
        new ChatEvent.Decision(
            "clarify:11",
            "clarify",
            "A step inside the service broke. Repeating the same request will not help.",
            null,
            null,
            11L,
            null,
            List.of(),
            List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
            new ChatEvent.RecoveryPresentation(
                "internal-service-failure",
                "Creation hit an internal problem",
                "A step inside the service broke. Repeating the same request will not help.",
                "Your approved requirements and plan are saved.",
                "java.lang.IllegalStateException: catalog lookup broke (runId=run-1)",
                null,
                "run-1",
                "design-execution"));

    String frame = ChatExecutionService.toSse(decision, new ObjectMapper());

    assertTrue(frame.contains("\"recovery\""), frame);
    assertTrue(frame.contains("internal-service-failure"), frame);
    assertTrue(frame.contains("stop-with-report"), frame);
    assertFalse(frame.contains("retry-creation"), frame);
    assertFalse(frame.contains("__GATE:"), frame);
  }

  @ParameterizedTest
  @ValueSource(strings = {PipelineGates.RETRY_ACTION, PipelineGates.REVISE_ACTION})
  void anAllowedHaltResumeDoesNotCloseSseWithoutTokenDecisionOrError(String action) {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    CreateChainExecutionSnapshot halted =
        new CreateChainExecutionSnapshot(
            "conv-halt-resume",
            "run-halt-resume",
            CreateChainExecutionStatus.INPUT_REQUIRED,
            6L,
            new CreateChainPendingAction.Clarify(
                "The catalog could not find that service.",
                List.of(),
                PipelineGates.RETRY_ACTION.equals(action)
                    ? PipelineGates.STAGE_RETRY
                    : PipelineGates.STAGE_REVISE),
            "");
    CreateChainExecutionSnapshot running =
        new CreateChainExecutionSnapshot(
            "conv-halt-resume",
            "run-halt-resume",
            CreateChainExecutionStatus.WORKING,
            7L,
            null,
            "");
    AtomicReference<CreateChainExecutionSnapshot> snapshot = new AtomicReference<>(halted);
    when(facade.snapshot("conv-halt-resume")).thenAnswer(invocation -> Optional.of(snapshot.get()));
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              snapshot.set(running);
              return Multi.createFrom().empty();
            });

    ChatDecisionService decisions =
        new ChatDecisionService(
            facade, new ApprovalQuestionStore(new InMemoryArtifactBlobStore()), new RequirementDraftStore());
    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-halt-resume");
    ChatDecisionCommand decision = new ChatDecisionCommand();
    decision.setAction(action);
    decision.setRevision(6L);
    request.setDecision(decision);

    List<String> frames =
        commandPathService(decisions).streamV1Sse(request).collect().asList().await().indefinitely();

    assertTrue(
        frames.stream()
            .anyMatch(
                frame ->
                    frame.startsWith("event: token\n")
                        || frame.startsWith("event: decision\n")
                        || frame.startsWith("event: error\n")),
        () -> action + " closed SSE with no token, decision, or error; got: " + frames);
  }

  @Test
  void approveCommandPathEmitsToolStepsWhenToolsRunDuringTheTurn() {
    ChatDecisionService decisions = mock(ChatDecisionService.class);
    when(decisions.apply(eq("conv-approve-tools"), any()))
        .thenAnswer(
            invocation ->
                Multi.createFrom()
                    .emitter(
                        emitter -> {
                          ToolInvocationSink.onInvoke("captureSelectedPattern");
                          ToolInvocationSink.onComplete("captureSelectedPattern");
                          emitter.emit(ChatEvent.token("Plan ready."));
                          emitter.complete();
                        }));

    ChatRequest request = new ChatRequest();
    request.setConversationId("conv-approve-tools");
    ChatDecisionCommand decision = new ChatDecisionCommand();
    decision.setAction(ChatEvent.APPROVE_ACTION);
    decision.setArtifactType("implementation-plan");
    decision.setArtifactHash("sha256:plan");
    decision.setRevision(3L);
    request.setDecision(decision);

    List<String> frames =
        commandPathService(decisions).streamV1Sse(request).collect().asList().await().indefinitely();

    assertTrue(
        frames.stream().anyMatch(frame -> frame.contains("\"kind\":\"tool\"")),
        () -> "expected event: step kind=tool on the approve command path, got: " + frames);
    assertTrue(
        frames.stream().anyMatch(frame -> frame.contains("captureSelectedPattern")),
        () -> "expected the invoked tool name on the wire, got: " + frames);
  }

  @Test
  void recoverFailedSseEmitsErrorThenDone() {
    List<String> frames =
        ChatExecutionService.recoverFailedSse(
                "e648092b-45b0-4249-b3d8-88a991127fd3",
                new RuntimeException("invalid_request_error: dangling tool_call"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(2, frames.size());
    assertTrue(frames.get(0).startsWith("event: error\n"));
    assertTrue(frames.get(0).contains("dangling tool_call"));
    assertEquals(
        "event: done\ndata: e648092b-45b0-4249-b3d8-88a991127fd3\n\n", frames.get(1));
  }

  @Test
  void chatTraceReadsProductRunWithoutLegacyBundleStore() {
    ProductPipelineRunDocument created = createRun("run-trace-1");
    appendGraph(created.run().runId(), sampleGraph("Greetings"));

    String description =
        ChatExecutionService.describeActivePlanForTrace(
            CONVERSATION_ID, runStore, artifactStore);

    assertEquals("Greetings nodes=1", description);
  }

  @Test
  void describeActivePlanReturnsNoneWithoutProductRun() {
    String description =
        ChatExecutionService.describeActivePlanForTrace(
            CONVERSATION_ID, runStore, artifactStore);

    assertEquals("(none)", description);
  }

  @Test
  void describeActivePlanFallsBackToMaterializationResult() {
    ProductPipelineRunDocument created = createRun("run-trace-2");
    ProductPipelineRunStore traceRunStore = mock(ProductPipelineRunStore.class);
    when(traceRunStore.loadByConversation(CONVERSATION_ID)).thenReturn(Optional.of(created));
    artifactStore.append(
        new AppendCommand(
            created.run().runId(),
            Kind.MATERIALIZATION_RESULT,
            "1",
            "test",
            "1",
            Map.of("status", "complete"),
            List.of(),
            null,
            provenance(created.run().runId())));

    String description =
        ChatExecutionService.describeActivePlanForTrace(
            CONVERSATION_ID, traceRunStore, artifactStore);

    assertEquals("(materialized)", description);
    verify(traceRunStore).loadByConversation(CONVERSATION_ID);
  }

  private ProductPipelineRunDocument createRun(String runId) {
    return runStore.create(
        new RunSnapshot(
            runId,
            CONVERSATION_ID,
            1L,
            RunStatus.RUNNING,
            "planning",
            List.of(new StageSnapshot("planning", StageStatus.RUNNING, List.of(), null)),
            null));
  }

  private void appendGraph(String runId, ChainPlanGraph graph) {
    artifactStore.append(
        new AppendCommand(
            runId,
            Kind.CHAIN_PLAN_GRAPH,
            "1",
            "test",
            "1",
            graph,
            List.of(),
            null,
            provenance(runId)));
  }

  private static ArtifactProvenance provenance(String runId) {
    return new ArtifactProvenance(
        runId, "planning", "create-chain", "1", "profile-sha", "test", "1", "closure-sha");
  }

  private ChatExecutionService commandPathService(ChatDecisionService decisions) {
    return service(mock(ScenarioRouter.class), decisions, new PendingRedeployStore());
  }

  private ChatExecutionService service(ScenarioRouter router, ChatDecisionService decisions) {
    return service(router, decisions, new PendingRedeployStore());
  }

  private ChatExecutionService service(
      ScenarioRouter router, ChatDecisionService decisions, PendingRedeployStore pending) {
    OpenChainTurnContextFactory turnContextFactory = mock(OpenChainTurnContextFactory.class);
    when(turnContextFactory.build(any(), anyString())).thenReturn(null);
    return service(router, decisions, pending, turnContextFactory);
  }

  private ChatExecutionService service(
      ScenarioRouter router,
      ChatDecisionService decisions,
      PendingRedeployStore pending,
      OpenChainTurnContextFactory turnContextFactory) {
    AppConfig appConfig = mock(AppConfig.class);
    AppConfig.LlmConfig llm = mock(AppConfig.LlmConfig.class);
    AppConfig.LlmConfig.RateLimitConfig rateLimit = mock(AppConfig.LlmConfig.RateLimitConfig.class);
    AppConfig.TraceConfig trace = mock(AppConfig.TraceConfig.class);
    when(appConfig.llm()).thenReturn(llm);
    when(llm.rateLimit()).thenReturn(rateLimit);
    when(rateLimit.maxTurnBackoffs()).thenReturn(12);
    when(appConfig.trace()).thenReturn(trace);
    when(trace.logAssistantResult()).thenReturn(false);
    ChatMemorySanitizer sanitizer = mock(ChatMemorySanitizer.class);
    when(sanitizer.repairDanglingToolCalls(anyString())).thenReturn(0);
    conversations = new ConversationService();
    return new ChatExecutionService(
        router,
        conversations,
        mock(EffectiveUserTextService.class),
        appConfig,
        runStore,
        artifactStore,
        new ObjectMapper(),
        sanitizer,
        decisions,
        pending,
        turnContextFactory,
        new org.qubership.integration.platform.ai.chat.LastAssistantTurnStore());
  }

  private static ChainPlanGraph sampleGraph(String chainName) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection(chainName, "Sample"),
        List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }
}
