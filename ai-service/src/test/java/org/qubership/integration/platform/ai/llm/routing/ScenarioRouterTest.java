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
