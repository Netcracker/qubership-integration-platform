package org.qubership.integration.platform.ai.a2a.transport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.QueueClosedEvent;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.qubership.integration.platform.ai.a2a.A2aFeatureDisabledException;
import org.qubership.integration.platform.ai.a2a.A2aFeatureGate;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.CallerContextProvider;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessDeniedException;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessPolicy;
import org.qubership.integration.platform.ai.a2a.access.TaskIdentity;
import org.qubership.integration.platform.ai.a2a.access.TaskOperation;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;

/**
 * Text-in / text-out A2A skill for peers that expect one round trip and a plain answer.
 *
 * <p>Runs one chat turn through {@link ScenarioRouter} and answers with a completed Task whose
 * first artifact carries a {@link TextPart}. That shape is what a client built on the Python
 * {@code a2a-sdk} reads: it concatenates {@code task.artifacts[0].parts[*].text} and ignores data
 * parts entirely, so an answer delivered only as structured data reaches such a caller as an empty
 * string. The same text is repeated on the status message for logs and inspectors.
 *
 * <p>Scenarios that reach the CREATE pipeline can run far longer than a caller will wait. The turn
 * is therefore bounded: past the budget the caller is told the run is in flight and which {@code
 * contextId} continues it, while the run itself is left subscribed and running.
 */
public final class QipAssistA2aAgentExecutor implements AgentExecutor {

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(QipAssistA2aAgentExecutor.class);

  /**
   * Matches a conversation identifier quoted in prose, label adjacent to value.
   *
   * <p>Anchored on the label so a bare UUID appearing anywhere in a requirements text is ignored.
   */
  private static final Pattern QUOTED_CONTEXT_ID =
      Pattern.compile(
          "(?i)context[\\s_-]?id\\W{0,4}([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

  private final ScenarioRouter router;
  private final ConversationService conversations;
  private final CallerContextProvider callerContextProvider;
  private final TaskAccessPolicy accessPolicy;
  private final A2aFeatureGate featureGate;
  private final Duration turnBudget;

  public QipAssistA2aAgentExecutor(
      ScenarioRouter router,
      ConversationService conversations,
      CallerContextProvider callerContextProvider,
      TaskAccessPolicy accessPolicy,
      A2aFeatureGate featureGate,
      Duration turnBudget) {
    this.router = Objects.requireNonNull(router, "router");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.callerContextProvider =
        Objects.requireNonNull(callerContextProvider, "callerContextProvider");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.featureGate = featureGate;
    this.turnBudget = Objects.requireNonNull(turnBudget, "turnBudget");
  }

  @Override
  public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(emitter, "emitter");
    if (featureGate != null && !featureGate.enabled()) {
      throw new A2aFeatureDisabledException(A2aFeatureGate.DISABLED_MESSAGE);
    }

    Message message = context.getMessage();
    if (message == null) {
      throw A2aProtocolErrorMapper.malformedStructuredData("Message is required");
    }
    String taskId = context.getTaskId();
    String contextId = context.getContextId();

    CallerContext caller = callerContextProvider.current();
    try {
      accessPolicy.check(
          caller,
          context.getTask() == null ? TaskOperation.CREATE : TaskOperation.CONTINUE,
          new TaskIdentity(taskId, contextId));
    } catch (TaskAccessDeniedException denied) {
      throw A2aProtocolErrorMapper.fromAccessDenied(denied);
    }

    String userText = joinTextParts(message);
    if (userText.isBlank()) {
      throw A2aProtocolErrorMapper.malformedStructuredData("A text part is required");
    }

    // Neither context.getContextId() nor message.contextId() can answer "did the caller send one?":
    // RequestContext.Builder mints an identifier when the caller omits it and rewrites the Message
    // with it, so both read back non-blank either way. The correlation carrier captures the field
    // at the request-handler boundary, before that rewrite, and is the only place the caller's own
    // value survives.
    String callerContextId =
        A2aClientCorrelationCarrier.lookup(requestCorrelationId(context)).contextId();
    ResolvedContext resolved = resolveConversationId(callerContextId, contextId, userText);
    TurnResult turn = runTurn(userText, resolved.conversationId());
    String answer = turn.answer(resolved.conversationId(), taskId);

    LOG.infof(
        "A2A assist turn taskId=%s conversationId=%s source=%s completed=%s answerChars=%d"
            + " activeStage=%s skillsUsed=%s",
        taskId,
        resolved.conversationId(),
        resolved.source(),
        turn.completed,
        answer.length(),
        turn.activity() == null ? "none" : turn.activity(),
        turn.skills().isEmpty() ? "none" : String.join(",", turn.skills()));

    // Echo the conversation the turn actually ran in, not the one the transport minted, so a
    // caller reading task.contextId converges on a single identifier instead of chasing a new
    // one every turn.
    emitAnswer(emitter, taskId, resolved.conversationId(), answer, turn);
  }

  /**
   * Server-owned correlation id for this request, planted in params metadata by the request
   * handler. Mirrors the lookup {@link CreateChainA2aAgentExecutor} performs for the same reason.
   */
  private static String requestCorrelationId(RequestContext context) {
    Map<String, Object> metadata = context.getMetadata();
    if (metadata == null) {
      return null;
    }
    Object value = metadata.get(A2aClientCorrelationCarrier.METADATA_KEY);
    return value == null ? null : String.valueOf(value);
  }

  /** Where the conversation key came from. Reported on every turn so drift is visible in logs. */
  private enum ContextSource {
    /** {@code message.contextId}, the field the protocol defines. */
    PROTOCOL_FIELD,
    /** Recovered from the message text because the caller never set the field. */
    RECOVERED_FROM_TEXT,
    /** Named in the text but unknown here, so it was refused and a fresh conversation started. */
    TEXT_REFERENCE_UNKNOWN,
    /** Nothing to go on: this turn opens a new conversation. */
    NEW
  }

  private record ResolvedContext(String conversationId, ContextSource source) {}

  /**
   * Picks the conversation this turn belongs to.
   *
   * <p>{@code message.contextId} is the only field the A2A specification defines for this, and a
   * caller that sets it is believed outright. A caller that instead writes the identifier into the
   * message text is not speaking the protocol, but the value it quotes is one this service issued
   * and echoed in {@code task.contextId}, so it is worth recovering rather than dropping the
   * conversation on the floor.
   *
   * <p>A quoted identifier is honored only when a conversation by that name already exists here.
   * That guard matters: a model can quote an identifier it invented or one belonging to a
   * different exchange, and silently merging two conversations is worse than starting a fresh one.
   */
  private ResolvedContext resolveConversationId(
      String callerContextId, String resolvedContextId, String userText) {
    if (callerContextId != null && !callerContextId.isBlank()) {
      return new ResolvedContext(callerContextId, ContextSource.PROTOCOL_FIELD);
    }
    String quoted = findQuotedContextId(userText);
    if (quoted != null) {
      if (conversations.getMessages(quoted).isEmpty()) {
        LOG.warnf(
            "A2A assist quotes unknown contextId=%s in message text; starting a new conversation"
                + " as %s instead of joining it",
            quoted, resolvedContextId);
        return new ResolvedContext(resolvedContextId, ContextSource.TEXT_REFERENCE_UNKNOWN);
      }
      LOG.infof(
          "A2A assist recovered contextId=%s from message text; the caller is not setting"
              + " message.contextId",
          quoted);
      return new ResolvedContext(quoted, ContextSource.RECOVERED_FROM_TEXT);
    }
    return new ResolvedContext(resolvedContextId, ContextSource.NEW);
  }

  /**
   * Reads a {@code contextId <uuid>} reference out of prose.
   *
   * <p>Deliberately narrow: the label has to be adjacent to the value, so an identifier merely
   * mentioned elsewhere in a long requirements text is not mistaken for a continuation.
   */
  static String findQuotedContextId(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    Matcher matcher = QUOTED_CONTEXT_ID.matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  @Override
  public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
    throw new TaskNotCancelableError("The conversational skill answers in one turn");
  }

  /**
   * Drains one router turn, or gives up on it after the budget.
   *
   * <p>The subscription is deliberately not cancelled on expiry: a CREATE run behind the turn owns
   * durable state, and cutting the stream would abandon work the caller can still reach through
   * the conversation.
   */
  private TurnResult runTurn(String userText, String conversationId) {
    ChatRequest request = new ChatRequest();
    request.setConversationId(conversationId);
    request.setMessage(userText);

    List<String> tokens = java.util.Collections.synchronizedList(new ArrayList<>());
    // Ordered and de-duplicated: the same skill reports running and finished, and the caller wants
    // the sequence of work, not a tally.
    Set<String> skills = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    AtomicReference<String> activity = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);

    router
        .route(request, conversationId)
        .subscribe()
        .withSubscriber(
            new io.smallrye.mutiny.subscription.MultiSubscriber<ChatEvent>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onItem(ChatEvent event) {
                if (event instanceof ChatEvent.Token token) {
                  tokens.add(token.text());
                } else if (event instanceof ChatEvent.Decision decision) {
                  tokens.add(decision.question());
                } else if (event instanceof ChatEvent.Error failure) {
                  error.set(failure.message());
                } else if (event instanceof ChatEvent.Step step) {
                  // Progress cannot be pushed to a caller that made one non-streaming call, so it
                  // is kept here and reported in the answer instead of being dropped.
                  if (step.label() != null && !step.label().isBlank()) {
                    activity.set(step.label());
                    if ("skill".equals(step.kind())) {
                      skills.add(step.label());
                    }
                  }
                }
              }

              @Override
              public void onFailure(Throwable failure) {
                error.set(failure.getMessage() == null ? "Turn failed" : failure.getMessage());
                done.countDown();
              }

              @Override
              public void onCompletion() {
                done.countDown();
              }
            });

    boolean completed;
    try {
      completed = done.await(turnBudget.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      completed = false;
    }
    // Tokens are stream fragments, not messages: RequirementAnalysisCapability maps an LLM agent
    // stream word by word into ChatEvent.Token, and the spacing lives inside the fragments. Any
    // separator inserted here shatters the answer into one word per line.
    //
    // ponytail: two consecutive logical messages therefore run together at the seam ("outline?I've
    // captured"). The boundary is not visible in ChatEvent, so marking it needs a new event kind
    // rather than a guess here.
    return new TurnResult(
        String.join("", tokens).trim(),
        error.get(),
        completed,
        activity.get(),
        List.copyOf(skills));
  }

  private void emitAnswer(
      AgentEmitter emitter, String taskId, String contextId, String answer, TurnResult turn) {
    Message statusMessage =
        Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .messageId(UUID.randomUUID().toString())
            .parts(List.of(new TextPart(answer)))
            .build();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("skillId", A2aProtocolConstants.ASSIST_SKILL_ID);
    // What the turn actually did, in order. Metadata rather than answer text: a caller that shows
    // the answer to a person should not have to strip a trace out of it first.
    if (!turn.skills().isEmpty()) {
      metadata.put("skillsUsed", String.join(",", turn.skills()));
    }
    if (!turn.completed()) {
      metadata.put("turnComplete", "false");
      if (turn.activity() != null && !turn.activity().isBlank()) {
        metadata.put("activeStage", turn.activity());
      }
    }
    Artifact artifact =
        Artifact.builder()
            .artifactId(UUID.randomUUID().toString())
            .name("answer")
            .parts(List.of(new TextPart(answer)))
            .metadata(metadata)
            .build();
    Task task =
        Task.builder()
            .id(taskId)
            .contextId(contextId)
            .status(new TaskStatus(A2aTaskState.COMPLETED.toSdk(), statusMessage, null))
            .artifacts(List.of(artifact))
            .build();

    emitter.addTask(task);
    emitter.addArtifact(
        List.of(new TextPart(answer)), artifact.artifactId(), "answer", metadata);
    emitter.complete(statusMessage);
    emitter.emitEvent(new QueueClosedEvent(taskId));
  }

  private static String joinTextParts(Message message) {
    StringBuilder text = new StringBuilder();
    for (var part : message.parts()) {
      if (part instanceof TextPart textPart && textPart.text() != null) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(textPart.text());
      }
    }
    return text.toString();
  }

  private record TurnResult(
      String text, String error, boolean completed, String activity, List<String> skills) {

    String answer(String conversationId, String taskId) {
      if (error != null && text.isEmpty()) {
        return error;
      }
      if (!completed) {
        String progress = text.isEmpty() ? "" : text + "\n\n";
        // Naming the stage answers the question a caller actually has when a turn runs long:
        // whether anything is happening. It is the only progress this profile can carry, because
        // one non-streaming call has no channel for an update before its response.
        String stage = activity == null || activity.isBlank() ? "" : " Working on: " + activity + ".";
        // Names the protocol field, not just the value: a caller that pastes the identifier into
        // its next message body instead of setting the field starts a new conversation every turn.
        return progress
            + "This request is still running."
            + stage
            + " Task "
            + taskId
            + " is in flight. To continue it, set the A2A message field `contextId` to "
            + conversationId
            + " on your next request.";
      }
      return text.isEmpty() ? "The request produced no answer." : text;
    }
  }
}
