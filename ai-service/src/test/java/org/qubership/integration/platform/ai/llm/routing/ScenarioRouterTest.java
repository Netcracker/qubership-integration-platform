package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.scenario.ScenarioHandler;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.ProductPipelineChatAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.UnsupportedCreateRunBindingException;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;

class ScenarioRouterTest {

  private static final String CONVERSATION_ID = "conv-router-1";

  private PlanCompilationTestSupport.Runtime compilationRuntime;
  private RequirementDraftStore requirementDraftStore;
  private RouterAgent routerAgent;
  private ChainContextExtractor chainContextExtractor;
  @SuppressWarnings("unchecked")
  private Instance<ScenarioHandler> handlers = mock(Instance.class);
  private ScenarioRouter router;

  @BeforeEach
  void setUp() {
    compilationRuntime = PlanCompilationTestSupport.memory();
    requirementDraftStore = compilationRuntime.requirementDraftStore();
    routerAgent = mock(RouterAgent.class);
    chainContextExtractor = mock(ChainContextExtractor.class);
    when(chainContextExtractor.hasChainContext(any(), anyString())).thenReturn(false);
    when(handlers.select(any(AnnotationLiteral.class))).thenReturn(handlers);
    when(handlers.isResolvable()).thenReturn(true);
    router =
        new ScenarioRouter(
            routerAgent,
            compilationRuntime.phaseResolver(),
            mock(ConversationService.class),
            chainContextExtractor,
            requirementDraftStore,
            handlers);
  }

  @Test
  void createChainWithoutReadyDraftOpensGather() {
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.CREATE_CHAIN_PLAN);
    ScenarioRouter.RoutingOutcome outcome = router.resolveRouting(request, CONVERSATION_ID);
    assertEquals(ScenarioType.GATHER_REQUIREMENTS, outcome.scenarioType());
  }

  @Test
  void createChainWithReadyDraftStaysOnCreateChainPlan() {
    requirementDraftStore.put(CONVERSATION_ID, new RequirementDraft(true, "vision"));
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.CREATE_CHAIN_PLAN);
    ScenarioRouter.RoutingOutcome outcome = router.resolveRouting(request, CONVERSATION_ID);
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, outcome.scenarioType());
  }

  @Test
  void implementWithoutReadyDraftOpensGather() {
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.IMPLEMENT_CHAIN);
    ScenarioRouter.RoutingOutcome outcome = router.resolveRouting(request, CONVERSATION_ID);
    assertEquals(ScenarioType.GATHER_REQUIREMENTS, outcome.scenarioType());
  }

  @Test
  void unsupportedBindingSurfacesLockedSseError() {
    CreateRunSelectionService selection = mock(CreateRunSelectionService.class);
    when(selection.existing(CONVERSATION_ID))
        .thenThrow(new UnsupportedCreateRunBindingException());
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    ScenarioRouter productRouter =
        new ScenarioRouter(
            routerAgent,
            compilationRuntime.phaseResolver(),
            mock(ConversationService.class),
            chainContextExtractor,
            requirementDraftStore,
            handlers,
            selection,
            adapter);
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("hello");
    var events =
        productRouter.route(request, CONVERSATION_ID).collect().asList().await().indefinitely();
    assertEquals(
        new UnsupportedCreateRunBindingException().sseMessage(),
        ((org.qubership.integration.platform.ai.chat.ChatEvent.Error) events.get(0)).message());
  }

  @Test
  void createOwnedHintRoutesToProductAdapter() {
    CreateRunSelectionService selection = mock(CreateRunSelectionService.class);
    when(selection.existing(CONVERSATION_ID)).thenReturn(java.util.Optional.empty());
    when(selection.selectOrCreate(CONVERSATION_ID))
        .thenReturn(
            new CreateRunSelectionService.CreateRunSelection(
                mock(org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest.class),
                "run-1"));
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    when(adapter.handle(any(), anyString()))
        .thenReturn(
            io.smallrye.mutiny.Multi.createFrom()
                .item(org.qubership.integration.platform.ai.chat.ChatEvent.token("product")));
    requirementDraftStore.put(CONVERSATION_ID, new RequirementDraft(true, "vision"));
    ScenarioRouter productRouter =
        new ScenarioRouter(
            routerAgent,
            compilationRuntime.phaseResolver(),
            mock(ConversationService.class),
            chainContextExtractor,
            requirementDraftStore,
            handlers,
            selection,
            adapter);
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.CREATE_CHAIN_PLAN);
    request.setResolvedEffectiveUserText("create");
    var events =
        productRouter.route(request, CONVERSATION_ID).collect().asList().await().indefinitely();
    assertEquals(
        "product",
        ((org.qubership.integration.platform.ai.chat.ChatEvent.Token) events.get(0)).text());
  }

  @Test
  void finishedCreateRunReleasesTheConversationToOtherScenarios() {
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    ScenarioHandler handler = mock(ScenarioHandler.class);
    when(handler.handle(any(), anyString(), any()))
        .thenReturn(
            io.smallrye.mutiny.Multi.createFrom()
                .item(org.qubership.integration.platform.ai.chat.ChatEvent.token("patch")));
    when(handlers.get()).thenReturn(handler);
    ScenarioRouter productRouter = boundRouter(adapter, snapshotWith(CreateChainExecutionStatus.COMPLETED));

    var events =
        productRouter
            .route(patchRequest(), CONVERSATION_ID)
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(
        "patch",
        ((org.qubership.integration.platform.ai.chat.ChatEvent.Token) events.get(0)).text());
    org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).handle(any(), anyString());
  }

  @Test
  void unfinishedCreateRunKeepsOwningTheConversation() {
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    when(adapter.handle(any(), anyString()))
        .thenReturn(
            io.smallrye.mutiny.Multi.createFrom()
                .item(org.qubership.integration.platform.ai.chat.ChatEvent.token("product")));
    ScenarioRouter productRouter = boundRouter(adapter, snapshotWith(CreateChainExecutionStatus.WORKING));

    var events =
        productRouter
            .route(patchRequest(), CONVERSATION_ID)
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(
        "product",
        ((org.qubership.integration.platform.ai.chat.ChatEvent.Token) events.get(0)).text());
  }

  private static ChatRequest patchRequest() {
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.COMPARE_AND_PATCH);
    request.setResolvedEffectiveUserText("fix the script in Normalize payload");
    return request;
  }

  private static CreateChainExecutionSnapshot snapshotWith(CreateChainExecutionStatus status) {
    return new CreateChainExecutionSnapshot(CONVERSATION_ID, "run-1", status, 1L, null, "");
  }

  private ScenarioRouter boundRouter(
      ProductPipelineChatAdapter adapter, CreateChainExecutionSnapshot snapshot) {
    CreateRunSelectionService selection = mock(CreateRunSelectionService.class);
    when(selection.existing(CONVERSATION_ID))
        .thenReturn(
            java.util.Optional.of(
                new CreateRunSelectionService.CreateRunSelection(
                    mock(org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest.class),
                    "run-1")));
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot(CONVERSATION_ID)).thenReturn(java.util.Optional.of(snapshot));
    return new ScenarioRouter(
        routerAgent,
        compilationRuntime.phaseResolver(),
        mock(ConversationService.class),
        chainContextExtractor,
        requirementDraftStore,
        handlers,
        selection,
        adapter,
        facade);
  }

  /**
   * The chain screen still sends IMPLEMENT_CHAIN with some messages. Obeying that hint skipped the
   * classifier and, with no IMPLEMENT_CHAIN handler, fell through to a new CREATE run. With a chain
   * open the hint is a screen label, not an instruction: patch the chain that is already there.
   */
  @Test
  void createOwnedHintWithOpenChainBecomesCompareAndPatch() {
    when(chainContextExtractor.hasChainContext(any(), anyString())).thenReturn(true);
    when(routerAgent.classify(any(), anyString(), any())).thenReturn(ScenarioType.GATHER_REQUIREMENTS);
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.IMPLEMENT_CHAIN);
    request.setResolvedEffectiveUserText("delete the audit step");

    ScenarioRouter.RoutingOutcome outcome = router.resolveRouting(request, CONVERSATION_ID);

    assertEquals(ScenarioType.COMPARE_AND_PATCH, outcome.scenarioType());
    org.mockito.Mockito.verify(routerAgent, org.mockito.Mockito.never())
        .classify(any(), anyString(), any());
  }

  /**
   * The classifier never sees the open-chain attachment, so "add a script" on a catalog chain often
   * comes back as GATHER_REQUIREMENTS. Starting CREATE would interview the reader about a new
   * integration instead of patching the chain on screen.
   */
  @Test
  void openChainDoesNotStartCreateWhenClassifierPicksGather() {
    when(chainContextExtractor.hasChainContext(any(), anyString())).thenReturn(true);
    when(routerAgent.classify(any(), anyString(), any()))
        .thenReturn(ScenarioType.GATHER_REQUIREMENTS);
    CreateRunSelectionService selection = mock(CreateRunSelectionService.class);
    when(selection.existing(CONVERSATION_ID)).thenReturn(java.util.Optional.empty());
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    ScenarioHandler handler = mock(ScenarioHandler.class);
    when(handler.handle(any(), anyString(), any()))
        .thenReturn(
            io.smallrye.mutiny.Multi.createFrom()
                .item(org.qubership.integration.platform.ai.chat.ChatEvent.token("patch")));
    when(handlers.get()).thenReturn(handler);
    ScenarioRouter productRouter =
        new ScenarioRouter(
            routerAgent,
            compilationRuntime.phaseResolver(),
            mock(ConversationService.class),
            chainContextExtractor,
            requirementDraftStore,
            handlers,
            selection,
            adapter);
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("add a script after Return greeting");
    request.setAttachment("## Current Chain: Demo (ID: 11111111-1111-1111-1111-111111111111)");

    var events =
        productRouter.route(request, CONVERSATION_ID).collect().asList().await().indefinitely();

    assertEquals(
        "patch",
        ((org.qubership.integration.platform.ai.chat.ChatEvent.Token) events.get(0)).text());
    org.mockito.Mockito.verify(selection, org.mockito.Mockito.never())
        .selectOrCreate(CONVERSATION_ID);
    org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).handle(any(), anyString());
    org.mockito.Mockito.verify(handler)
        .handle(any(), anyString(), org.mockito.Mockito.eq(ScenarioType.COMPARE_AND_PATCH));
  }

  /**
   * Live log: scenarioHint=IMPLEMENT_CHAIN skipped the classifier, then "no handler" fell back to
   * CREATE_CHAIN_PLAN and opened a new CREATE interview. An open-chain attachment must force
   * COMPARE_AND_PATCH instead, even when the UI still sends that hint.
   */
  @Test
  void implementHintWithOpenChainDoesNotStartCreate() {
    when(chainContextExtractor.hasChainContext(any(), anyString())).thenReturn(true);
    when(routerAgent.classify(any(), anyString(), any()))
        .thenReturn(ScenarioType.GATHER_REQUIREMENTS);
    CreateRunSelectionService selection = mock(CreateRunSelectionService.class);
    when(selection.existing(CONVERSATION_ID)).thenReturn(java.util.Optional.empty());
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    ScenarioHandler handler = mock(ScenarioHandler.class);
    when(handler.handle(any(), anyString(), any()))
        .thenReturn(
            io.smallrye.mutiny.Multi.createFrom()
                .item(org.qubership.integration.platform.ai.chat.ChatEvent.token("patch")));
    when(handlers.get()).thenReturn(handler);
    ScenarioRouter productRouter =
        new ScenarioRouter(
            routerAgent,
            compilationRuntime.phaseResolver(),
            mock(ConversationService.class),
            chainContextExtractor,
            requirementDraftStore,
            handlers,
            selection,
            adapter);
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.IMPLEMENT_CHAIN);
    request.setResolvedEffectiveUserText(
        "add quartz-scheduler to the chain. it has to start every 5 minutes");
    request.setAttachment("## Current Chain: Demo (ID: 11111111-1111-1111-1111-111111111111)");

    var events =
        productRouter.route(request, CONVERSATION_ID).collect().asList().await().indefinitely();

    assertEquals(
        "patch",
        ((org.qubership.integration.platform.ai.chat.ChatEvent.Token) events.get(0)).text());
    org.mockito.Mockito.verify(selection, org.mockito.Mockito.never())
        .selectOrCreate(CONVERSATION_ID);
    org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).handle(any(), anyString());
    org.mockito.Mockito.verify(handler)
        .handle(any(), anyString(), org.mockito.Mockito.eq(ScenarioType.COMPARE_AND_PATCH));
    org.mockito.Mockito.verify(routerAgent, org.mockito.Mockito.never())
        .classify(any(), anyString(), any());
  }

  /** A hint outside CREATE names a scenario rather than a screen, so it still decides. */
  @Test
  void nonCreateHintStillDecidesWhenAChainIsOpen() {
    when(chainContextExtractor.hasChainContext(any(), anyString())).thenReturn(true);
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.ASK_CHAIN);
    request.setResolvedEffectiveUserText("what does this chain do");

    ScenarioRouter.RoutingOutcome outcome = router.resolveRouting(request, CONVERSATION_ID);

    assertEquals(ScenarioType.ASK_CHAIN, outcome.scenarioType());
  }

  /** Without a chain in context there is nothing to patch, so the hint is honored as before. */
  @Test
  void createOwnedHintStillDecidesWithoutAChainInContext() {
    requirementDraftStore.put(CONVERSATION_ID, new RequirementDraft(true, "vision"));
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.CREATE_CHAIN_PLAN);
    request.setResolvedEffectiveUserText("delete the audit step");

    ScenarioRouter.RoutingOutcome outcome = router.resolveRouting(request, CONVERSATION_ID);

    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, outcome.scenarioType());
  }

  /**
   * The chain the run just built is the chain the reader now wants changed. An unfinished run that
   * kept this turn would answer a change request with the next step of its own plan.
   */
  @Test
  void unfinishedCreateRunLetsGoOfATurnAboutTheChainItBuilt() {
    when(chainContextExtractor.hasChainContext(any(), anyString())).thenReturn(true);
    when(routerAgent.classify(any(), anyString(), any())).thenReturn(ScenarioType.COMPARE_AND_PATCH);
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    ScenarioHandler handler = mock(ScenarioHandler.class);
    when(handler.handle(any(), anyString(), any()))
        .thenReturn(
            io.smallrye.mutiny.Multi.createFrom()
                .item(org.qubership.integration.platform.ai.chat.ChatEvent.token("patch")));
    when(handlers.get()).thenReturn(handler);
    ScenarioRouter productRouter =
        boundRouter(adapter, snapshotWith(CreateChainExecutionStatus.WORKING));

    var events =
        productRouter
            .route(patchRequest(), CONVERSATION_ID)
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(
        "patch",
        ((org.qubership.integration.platform.ai.chat.ChatEvent.Token) events.get(0)).text());
    org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).handle(any(), anyString());
  }

  /** A turn that is not about the open chain still belongs to the run that is mid-flight. */
  @Test
  void unfinishedCreateRunKeepsATurnThatIsNotAboutTheOpenChain() {
    when(chainContextExtractor.hasChainContext(any(), anyString())).thenReturn(true);
    when(routerAgent.classify(any(), anyString(), any()))
        .thenReturn(ScenarioType.GATHER_REQUIREMENTS);
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    when(adapter.handle(any(), anyString()))
        .thenReturn(
            io.smallrye.mutiny.Multi.createFrom()
                .item(org.qubership.integration.platform.ai.chat.ChatEvent.token("product")));
    ScenarioRouter productRouter =
        boundRouter(adapter, snapshotWith(CreateChainExecutionStatus.WORKING));
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("I also need a second integration for invoices");

    var events =
        productRouter.route(request, CONVERSATION_ID).collect().asList().await().indefinitely();

    assertEquals(
        "product",
        ((org.qubership.integration.platform.ai.chat.ChatEvent.Token) events.get(0)).text());
  }

  @Test
  void nonCreateHintDoesNotSelectProductRun() {
    CreateRunSelectionService selection = mock(CreateRunSelectionService.class);
    when(selection.existing(CONVERSATION_ID)).thenReturn(java.util.Optional.empty());
    ProductPipelineChatAdapter adapter = mock(ProductPipelineChatAdapter.class);
    ScenarioHandler handler = mock(ScenarioHandler.class);
    when(handler.handle(any(), anyString(), any()))
        .thenReturn(
            io.smallrye.mutiny.Multi.createFrom()
                .item(org.qubership.integration.platform.ai.chat.ChatEvent.token("ask")));
    when(handlers.get()).thenReturn(handler);
    ScenarioRouter productRouter =
        new ScenarioRouter(
            routerAgent,
            compilationRuntime.phaseResolver(),
            mock(ConversationService.class),
            chainContextExtractor,
            requirementDraftStore,
            handlers,
            selection,
            adapter);
    ChatRequest request = new ChatRequest();
    request.setScenarioHint(ScenarioType.ASK_CHAIN);
    request.setResolvedEffectiveUserText("explain");
    var events =
        productRouter.route(request, CONVERSATION_ID).collect().asList().await().indefinitely();
    assertEquals(
        "ask", ((org.qubership.integration.platform.ai.chat.ChatEvent.Token) events.get(0)).text());
    org.mockito.Mockito.verify(selection, org.mockito.Mockito.never()).selectOrCreate(CONVERSATION_ID);
  }
}
