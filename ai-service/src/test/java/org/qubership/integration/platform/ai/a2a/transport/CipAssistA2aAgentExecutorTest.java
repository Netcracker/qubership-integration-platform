package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.mockito.ArgumentCaptor;
import org.a2aproject.sdk.transport.rest.context.RestContextKeys;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.LocalPermitAllTaskAccessPolicy;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;
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

    executor.execute(contextWithBearer(conversationId, "Create an HTTP chain"), mock(AgentEmitter.class));
    assertEquals(List.of("Create an HTTP chain", "Saved response"),
        conversations.getMessages(conversationId).stream().map(message -> message.content()).toList());

    executor.execute(contextWithBearer(UUID.randomUUID().toString(), "Continue with contextId " + conversationId),
        mock(AgentEmitter.class));
    assertEquals(4, conversations.getMessages(conversationId).size());
  }

  @Test
  void assistTurnBindsTheInboundBearerForItsConversationAndReleasesIt() throws Exception {
    ScenarioRouter router = mock(ScenarioRouter.class);
    ConversationService conversations = new ConversationService();
    String conversationId = UUID.randomUUID().toString();
    java.util.concurrent.atomic.AtomicReference<String> seenToken =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<String> seenConversation =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(router.route(any(ChatRequest.class), anyString()))
        .thenAnswer(
            invocation -> {
              seenToken.set(UserTokenStore.current().orElse(null));
              seenConversation.set(ToolSession.resolveConversationId());
              return Multi.createFrom().item(ChatEvent.token("done"));
            });
    CipAssistA2aAgentExecutor executor =
        new CipAssistA2aAgentExecutor(
            router,
            conversations,
            () -> new CallerContext("local", "local-user"),
            new LocalPermitAllTaskAccessPolicy(),
            null,
            Duration.ofSeconds(1));

    executor.execute(contextWithBearer(conversationId, "Hello"), mock(AgentEmitter.class));

    assertEquals(A2aTestAuth.BEARER, seenToken.get());
    assertEquals(conversationId, seenConversation.get());
    try (ToolSession.Handle ignored = ToolSession.open(conversationId)) {
      assertTrue(UserTokenStore.current().isEmpty());
    }
  }

  @Test
  void aCatalogRejectionFailsTheTaskInsteadOfAnsweringWithTheChatText() throws Exception {
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(ChatRequest.class), anyString())).thenAnswer(invocation -> {
      UserTokenStore.rejectCurrent(401);
      return Multi.createFrom().item(ChatEvent.token("Could not read the chains."));
    });
    CipAssistA2aAgentExecutor executor =
        new CipAssistA2aAgentExecutor(
            router,
            new ConversationService(),
            () -> new CallerContext("local", "local-user"),
            new LocalPermitAllTaskAccessPolicy(),
            null,
            Duration.ofSeconds(1));
    AgentEmitter emitter = mock(AgentEmitter.class);

    executor.execute(contextWithBearer(UUID.randomUUID().toString(), "List chains"), emitter);

    ArgumentCaptor<Message> failure = ArgumentCaptor.forClass(Message.class);
    verify(emitter).fail(failure.capture());
    assertEquals(
        UserTokenStore.SESSION_EXPIRED_MESSAGE,
        ((TextPart) failure.getValue().parts().getFirst()).text());
    verify(emitter, never()).complete(any(Message.class));
  }

  @Test
  void aTurnWithoutBearerIsRejectedBeforeTheModelRuns() throws Exception {
    ScenarioRouter router = mock(ScenarioRouter.class);
    AgentEmitter emitter = mock(AgentEmitter.class);

    executor(router, new ConversationService())
        .execute(context(UUID.randomUUID().toString(), "List chains"), emitter);

    ArgumentCaptor<Message> failure = ArgumentCaptor.forClass(Message.class);
    verify(emitter).fail(failure.capture());
    assertEquals(
        UserTokenStore.SESSION_EXPIRED_MESSAGE,
        ((TextPart) failure.getValue().parts().getFirst()).text());
    verify(router, never()).route(any(ChatRequest.class), anyString());
  }

  @Test
  void aTurnWithoutBearerRunsWhenAuthIsNotRequired() throws Exception {
    ScenarioRouter router = mock(ScenarioRouter.class);
    when(router.route(any(ChatRequest.class), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("done")));
    AgentEmitter emitter = mock(AgentEmitter.class);

    System.setProperty("qip.ai.auth.required", "false");
    try {
      executor(router, new ConversationService())
          .execute(context(UUID.randomUUID().toString(), "Hello"), emitter);
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }

    verify(router).route(any(ChatRequest.class), anyString());
    verify(emitter).complete(any(Message.class));
    verify(emitter, never()).fail(any(Message.class));
  }

  @Test
  void aRejectionRecordedAsTheStreamEndsReachesTheWaiter() throws Exception {
    // The stream ends on another thread, so the rejection races the waiter. Repeating the turn
    // lets a rejection that is read too early show up instead of hiding behind one lucky schedule.
    ExecutorService streams = Executors.newSingleThreadExecutor();
    try {
      for (int turn = 0; turn < 200; turn++) {
        ScenarioRouter router = mock(ScenarioRouter.class);
        when(router.route(any(ChatRequest.class), anyString())).thenAnswer(invocation -> {
          UserTokenStore.rejectCurrent(401);
          return Multi.createFrom().item(ChatEvent.token("Could not read the chains."))
              .runSubscriptionOn(streams);
        });
        AgentEmitter emitter = mock(AgentEmitter.class);

        executor(router, new ConversationService())
            .execute(contextWithBearer(UUID.randomUUID().toString(), "List chains"), emitter);

        verify(emitter).fail(any(Message.class));
        verify(emitter, never()).complete(any(Message.class));
      }
    } finally {
      streams.shutdownNow();
    }
  }

  @Test
  void aConversationHeldByAnotherUserEndsTheTaskWithoutAModelCall() throws Exception {
    String conversationId = UUID.randomUUID().toString();
    UserTokenStore.Hold other = UserTokenStore.acquire(conversationId, jwt("alice"));
    try {
      ScenarioRouter router = mock(ScenarioRouter.class);
      AgentEmitter emitter = mock(AgentEmitter.class);

      executor(router, new ConversationService()).execute(
          contextWithAuthorization(conversationId, "List chains", "Bearer " + jwt("bob")), emitter);

      ArgumentCaptor<Message> failure = ArgumentCaptor.forClass(Message.class);
      verify(emitter).fail(failure.capture());
      assertEquals(
          new UserTokenStore.ConversationBusyException().getMessage(),
          ((TextPart) failure.getValue().parts().getFirst()).text());
      verify(router, never()).route(any(ChatRequest.class), anyString());
    } finally {
      other.release();
    }
  }

  private static CipAssistA2aAgentExecutor executor(
      ScenarioRouter router, ConversationService conversations) {
    return new CipAssistA2aAgentExecutor(
        router,
        conversations,
        () -> new CallerContext("local", "local-user"),
        new LocalPermitAllTaskAccessPolicy(),
        null,
        Duration.ofSeconds(1));
  }

  private static RequestContext contextWithBearer(String resolvedId, String text) {
    return contextWithAuthorization(resolvedId, text, A2aTestAuth.AUTHORIZATION_HEADER);
  }

  private static RequestContext contextWithAuthorization(
      String resolvedId, String text, String authorization) {
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", authorization);
    ServerCallContext callContext =
        new ServerCallContext(
            UnauthenticatedUser.INSTANCE, Map.of(RestContextKeys.HEADERS_KEY, headers), Set.of());
    Message message = Message.builder().role(Message.Role.ROLE_USER)
        .messageId(UUID.randomUUID().toString()).parts(List.of(new TextPart(text))).build();
    return new RequestContext.Builder().setTaskId(UUID.randomUUID().toString()).setContextId(resolvedId)
        .setParams(MessageSendParams.builder().message(message).build())
        .setServerCallContext(callContext)
        .build();
  }

  private static String jwt(String subject) {
    String payload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("{\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
    return "header." + payload + ".signature";
  }

  private static RequestContext context(String resolvedId, String text) {
    Message message = Message.builder().role(Message.Role.ROLE_USER)
        .messageId(UUID.randomUUID().toString()).parts(List.of(new TextPart(text))).build();
    return new RequestContext.Builder().setTaskId(UUID.randomUUID().toString()).setContextId(resolvedId)
        .setParams(MessageSendParams.builder().message(message).build()).build();
  }
}
