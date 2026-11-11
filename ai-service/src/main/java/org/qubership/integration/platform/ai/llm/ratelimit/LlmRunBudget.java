package org.qubership.integration.platform.ai.llm.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Reserves each transport attempt before dispatch, including attempts whose outcome is unknown. */
@ApplicationScoped
public class LlmRunBudget {
  private static final ThreadLocal<String> RUN = new ThreadLocal<>();
  private final ArtifactBlobStore blobs;
  private final ObjectMapper mapper;
  private final AppConfig.LlmConfig config;
  private final Clock clock;

  public record Active(long tokens, Instant startedAt) {}
  public record State(long requests, long tokens, long activeMillis, int grants,
      Set<String> grantCommands, Map<String, Active> active, String refusal) {
    public State {
      grantCommands = Set.copyOf(grantCommands);
      active = Map.copyOf(active);
      if (requests < 0 || tokens < 0 || activeMillis < 0 || grants < 0) {
        throw new IllegalArgumentException("Invalid model resource accounting");
      }
      if (grants != grantCommands.size() || requests < active.size()
          || active.values().stream().anyMatch(value -> value.tokens() < 0 || value.startedAt() == null)) {
        throw new IllegalArgumentException("Inconsistent model resource accounting");
      }
    }
  }
  public record Ticket(String runId, String id) {}
  public static final class Exhausted extends IllegalStateException {
    public Exhausted(String reason) { super(reason); }
  }

  @Inject
  public LlmRunBudget(ArtifactBlobStore blobs, ObjectMapper mapper, AppConfig config) {
    this(blobs, mapper, config.llm(), Clock.systemUTC());
  }

  LlmRunBudget(ArtifactBlobStore blobs, ObjectMapper mapper, AppConfig.LlmConfig config, Clock clock) {
    this.blobs = blobs;
    this.mapper = mapper.copy();
    this.config = config;
    this.clock = clock;
  }

  public static <T> T withRun(String runId, Supplier<T> action) {
    String previous = RUN.get();
    RUN.set(runId);
    try { return action.get(); }
    finally {
      if (previous == null) { RUN.remove(); } else { RUN.set(previous); }
    }
  }

  public void linkConversation(String runId, String conversationId) {
    if (runId == null || runId.isBlank() || conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("Run and conversation identities are required for model accounting");
    }
    blobs.put(linkKey(conversationId), runId.getBytes(StandardCharsets.UTF_8));
  }

  String currentRun() {
    if (RUN.get() != null) { return RUN.get(); }
    String conversation = ToolSession.resolveConversationId();
    if (conversation == null) { return null; }
    var capture = org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
        .binding(conversation);
    if (capture.isPresent()) { return capture.get().runId(); }
    return blobs.get(linkKey(conversation))
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
  }

  public Ticket reserve(String runId, long inputTokens, long outputTokens) {
    if (runId == null) { return null; }
    if (inputTokens < 0 || outputTokens < 0) { throw new IllegalArgumentException("Negative token reservation"); }
    String id = UUID.randomUUID().toString();
    long tokens = Math.addExact(inputTokens, outputTokens);
    State result = update(runId, state -> {
      String refusal = refusal(state, tokens);
      if (refusal != null) {
        return new State(state.requests(), state.tokens(), state.activeMillis(), state.grants(),
            state.grantCommands(), state.active(), refusal);
      }
      var active = new HashMap<>(state.active());
      active.put(id, new Active(tokens, clock.instant()));
      return new State(state.requests() + 1, Math.addExact(state.tokens(), tokens), state.activeMillis(),
          state.grants(), state.grantCommands(), active, null);
    });
    if (result.refusal() != null) { throw new Exhausted(result.refusal()); }
    return new Ticket(runId, id);
  }

  String complete(Ticket ticket, TokenUsage usage) {
    if (ticket == null) { return null; }
    return update(ticket.runId(), state -> {
      Active reservation = state.active().get(ticket.id());
      if (reservation == null) { return state; }
      long used = reservation.tokens();
      if (usage != null && usage.inputTokenCount() != null && usage.outputTokenCount() != null
          && usage.inputTokenCount() >= 0 && usage.outputTokenCount() >= 0) {
        used = (long) usage.inputTokenCount() + usage.outputTokenCount();
        if (usage.totalTokenCount() != null) { used = Math.max(used, usage.totalTokenCount()); }
      }
      var active = new HashMap<>(state.active());
      active.remove(ticket.id());
      long tokens = Math.addExact(state.tokens() - reservation.tokens(), used);
      long multiplier = (long) state.grants() + 1;
      String refusal = state.refusal();
      if (refusal == null && tokens > Math.multiplyExact(tokenLimit(), multiplier)) {
        refusal = "Reported model usage exceeds the token allowance for this creation run.";
      }
      if (refusal == null && elapsedMillis(state) >= Math.multiplyExact(timeLimitMillis(), multiplier)) {
        refusal = "The active model processing time allowance is exhausted.";
      }
      return new State(state.requests(), tokens,
          Math.addExact(state.activeMillis(), elapsedMillis(reservation.startedAt())), state.grants(),
          state.grantCommands(), active, refusal);
    }).refusal();
  }

  public void grant(String runId, String commandId) {
    if (commandId == null || commandId.isBlank()) { throw new IllegalArgumentException("Budget grant command required"); }
    update(runId, state -> {
      if (state.refusal() == null || state.grantCommands().contains(commandId)) { return state; }
      var commands = new HashSet<>(state.grantCommands());
      commands.add(commandId);
      return new State(state.requests(), state.tokens(), state.activeMillis(), Math.incrementExact(state.grants()),
          commands, state.active(), null);
    });
  }

  long remainingMillis(Ticket ticket) {
    if (ticket == null) { return Long.MAX_VALUE; }
    State state = state(ticket.runId());
    return Math.max(0L, Math.multiplyExact(timeLimitMillis(), (long) state.grants() + 1) - elapsedMillis(state));
  }

  long checkDelayMillis(Ticket ticket) {
    // ponytail: shared active time is checked every 25 ms; cancellation still depends on the provider.
    return Math.max(1L, Math.min(25L,
        remainingMillis(ticket) / Math.max(1, state(ticket.runId()).active().size())));
  }

  Exhausted expire(Ticket ticket) {
    String reason = "The active model processing time allowance is exhausted.";
    State result = update(ticket.runId(), state -> !state.active().containsKey(ticket.id())
        || elapsedMillis(state) < Math.multiplyExact(timeLimitMillis(), (long) state.grants() + 1) ? state
        : new State(state.requests(), state.tokens(), state.activeMillis(), state.grants(),
            state.grantCommands(), state.active(), reason));
    return result.active().containsKey(ticket.id()) && reason.equals(result.refusal()) ? new Exhausted(reason) : null;
  }

  <T> T invoke(Ticket ticket, Supplier<T> action) {
    if (ticket == null) { return action.get(); }
    var task = new java.util.concurrent.FutureTask<>(action::get);
    var mdc = org.jboss.logmanager.MDC.copyObject();
    Thread.ofVirtual().name("model-request").start(() -> {
      mdc.forEach(org.jboss.logmanager.MDC::putObject);
      try { withRun(ticket.runId(), () -> { task.run(); return null; }); }
      finally {
        try { complete(ticket, null); }
        finally { org.jboss.logmanager.MDC.clear(); }
      }
    });
    try {
      while (true) {
        try { return task.get(checkDelayMillis(ticket), java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) {
          if (remainingMillis(ticket) == 0) {
            Exhausted failure = expire(ticket);
            if (failure != null) { task.cancel(true); throw failure; }
          }
        }
      }
    } catch (InterruptedException interrupted) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Model request was interrupted", interrupted);
    } catch (java.util.concurrent.ExecutionException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) { throw runtime; }
      throw new IllegalStateException("Model transport failed", failure.getCause());
    }
  }

  public State state(String runId) {
    return blobs.get(key(runId)).map(bytes -> read(bytes)).orElseGet(LlmRunBudget::empty);
  }

  /** Checks remaining resources without reserving or charging a transport attempt. */
  public String refusalForNextRequest(String runId) {
    return update(runId, state -> {
      String reason = refusal(state, 1L);
      return reason == null || reason.equals(state.refusal()) ? state
          : new State(state.requests(), state.tokens(), state.activeMillis(), state.grants(),
              state.grantCommands(), state.active(), reason);
    }).refusal();
  }

  private String refusal(State state, long reservedTokens) {
    if (state.refusal() != null) { return state.refusal(); }
    long multiplier = (long) state.grants() + 1;
    if (state.requests() >= Math.multiplyExact(requestLimit(), multiplier)) {
      return "The model request allowance for this creation run is exhausted.";
    }
    if (reservedTokens > Math.multiplyExact(tokenLimit(), multiplier) - state.tokens()) {
      return "The model token allowance cannot cover another request and its reserved response.";
    }
    if (elapsedMillis(state) >= Math.multiplyExact(timeLimitMillis(), multiplier)) {
      return "The active model processing time allowance is exhausted.";
    }
    return null;
  }

  private State update(String runId, UnaryOperator<State> change) {
    for (int attempt = 0; attempt < 8; attempt++) {
      var versioned = blobs.getVersioned(key(runId));
      State before = versioned.map(blob -> read(blob.content())).orElseGet(LlmRunBudget::empty);
      State after = change.apply(before);
      if (after.equals(before)) { return after; }
      try {
        blobs.putIfVersion(key(runId), mapper.writeValueAsBytes(after),
            versioned.map(blob -> blob.version()).orElse(null));
        return after;
      } catch (StaleBlobVersionException conflict) {
        if (attempt == 7) { throw conflict; }
      } catch (java.io.IOException failure) {
        throw new IllegalStateException("Cannot save model resource accounting", failure);
      }
    }
    throw new IllegalStateException("Model resource accounting did not settle");
  }

  private State read(byte[] bytes) {
    try { return mapper.readValue(bytes, State.class); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read model resource accounting", failure); }
  }

  private long elapsedMillis(State state) {
    long elapsed = state.activeMillis();
    for (Active active : state.active().values()) {
      elapsed = Math.addExact(elapsed, elapsedMillis(active.startedAt()));
    }
    return elapsed;
  }
  private long elapsedMillis(Instant start) { return Math.max(0L, Duration.between(start, clock.instant()).toMillis()); }
  private long requestLimit() { return config.maxRequestsPerRun() > 0 ? config.maxRequestsPerRun() : 128; }
  private long tokenLimit() { return config.maxTokensPerRun() > 0 ? config.maxTokensPerRun() : 2_000_000L; }
  private long timeLimitMillis() {
    Duration time = config.maxActiveTimePerRun();
    return time == null || time.toMillis() <= 0 ? 900_000L : time.toMillis();
  }
  private static State empty() { return new State(0, 0, 0, 0, Set.of(), Map.of(), null); }
  private static String key(String runId) { return "product-pipeline-llm-budget/" + LlmRequestBudget.hash(runId) + ".json"; }
  private static String linkKey(String conversation) { return "product-pipeline-llm-links/" + LlmRequestBudget.hash(conversation); }
}
