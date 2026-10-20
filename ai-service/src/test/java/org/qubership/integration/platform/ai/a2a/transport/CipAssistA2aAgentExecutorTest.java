package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.LocalPermitAllTaskAccessPolicy;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;

class CipAssistA2aAgentExecutorTest {
  @Test
  void recordsBothSidesBeforeAQuotedIdentifierContinuesTheSameConversation() throws Exception {
    ScenarioRouter router = mock(ScenarioRouter.class);
    ConversationService conversations = new ConversationService();
    String conversationId = UUID.randomUUID().toString();
    when(router.route(any(ChatRequest.class), anyString())).thenAnswer(invocation -> {
      String actualId = invocation.getArgument(1);
      ChatRequest request = invocation.getArgument(0);
      assertEquals(conversationId, actualId);
      var messages = conversations.getMessages(actualId);
      assertEquals(request.getMessage(), messages.getLast().content());
      return Multi.createFrom().item(ChatEvent.token("Saved response"));
    });
    CipAssistA2aAgentExecutor executor = new CipAssistA2aAgentExecutor(router, conversations,
        () -> new CallerContext("local", "local-user"), new LocalPermitAllTaskAccessPolicy(), null,
        Duration.ofSeconds(1));

    executor.execute(context(conversationId, "Create an HTTP chain"), mock(AgentEmitter.class));
    assertEquals(List.of("Create an HTTP chain", "Saved response"),
        conversations.getMessages(conversationId).stream().map(message -> message.content()).toList());

    executor.execute(context(UUID.randomUUID().toString(), "Continue with contextId " + conversationId),
        mock(AgentEmitter.class));
    assertEquals(4, conversations.getMessages(conversationId).size());
  }

  private static RequestContext context(String resolvedId, String text) {
    Message message = Message.builder().role(Message.Role.ROLE_USER)
        .messageId(UUID.randomUUID().toString()).parts(List.of(new TextPart(text))).build();
    return new RequestContext.Builder().setTaskId(UUID.randomUUID().toString()).setContextId(resolvedId)
        .setParams(MessageSendParams.builder().message(message).build()).build();
  }
}
