package org.qubership.integration.platform.ai.llm.routing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;
import java.net.URLClassLoader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.scenario.ScenarioHandler;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.ProductPipelineChatAdapter;
import org.qubership.integration.platform.ai.skills.RockySkills;

class ScenarioRouterPublicModeTest {
  private final RouterAgent classifier = mock(RouterAgent.class);
  private final ConversationService conversations = mock(ConversationService.class);
  private final CreateRunSelectionService selection = mock(CreateRunSelectionService.class);
  private final ProductPipelineChatAdapter create = mock(ProductPipelineChatAdapter.class);
  private final ScenarioHandler handler = mock(ScenarioHandler.class);
  @SuppressWarnings("unchecked") private final Instance<ScenarioHandler> handlers = mock(Instance.class);

  private ScenarioRouter router() {
    when(handlers.select(any(AnnotationLiteral.class))).thenReturn(handlers);
    when(handlers.isResolvable()).thenReturn(true);
    when(handlers.get()).thenReturn(handler);
    when(handler.handle(any(), anyString(), any())).thenReturn(Multi.createFrom().empty());
    when(conversations.getMessages(anyString())).thenReturn(List.of());
    var router = new ScenarioRouter(classifier, mock(ConversationPhaseResolver.class), conversations,
        mock(ChainContextExtractor.class), mock(RequirementDraftStore.class), handlers, selection, create);
    router.rockySkills = new RockySkills(new URLClassLoader(new java.net.URL[0], null));
    return router;
  }

  @ParameterizedTest
  @EnumSource(value = ScenarioType.class, names = {"ASK_CHAIN", "ASK_PLAN", "MANAGE_CHAIN_LIST",
      "DEPLOY_CHAIN", "IMPORT_SPECIFICATION", "CONSULTATION"})
  void publicScenariosStayAvailableWithoutPrivateSkills(ScenarioType type) {
    var router = router();
    var request = new ChatRequest();
    request.setScenarioHint(type);
    router.route(request, "old-create-conversation").collect().asList().await().indefinitely();
    verify(handler).handle(request, "old-create-conversation", type);
    verifyNoInteractions(selection, create);
  }

  @ParameterizedTest
  @EnumSource(value = ScenarioType.class, names = {"CREATE_CHAIN_PLAN", "IMPLEMENT_CHAIN", "COMPARE_AND_PATCH"})
  void generationIsRefusedBeforeAnyPrivateRunOrHandler(ScenarioType type) {
    var router = router();
    var request = new ChatRequest();
    request.setScenarioHint(type);
    var events = router.route(request, "old-create-conversation").collect().asList().await().indefinitely();
    assertEquals(List.of(ChatEvent.token(RockySkills.UNAVAILABLE)), events);
    verifyNoInteractions(handler, selection, create);
  }

  @Test
  void discoveryClassificationUsesReadOnlyConsultationWithoutBindingCreate() {
    var router = router();
    var request = new ChatRequest();
    request.setMessage("How do integrations work?");
    when(classifier.classify(anyString(), anyString(), anyString())).thenReturn(ScenarioType.GATHER_REQUIREMENTS);
    router.route(request, "public-chat").collect().asList().await().indefinitely();
    verify(handler).handle(request, "public-chat", ScenarioType.CONSULTATION);
    verifyNoInteractions(selection, create);
  }
}
