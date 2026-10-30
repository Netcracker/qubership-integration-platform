package org.qubership.integration.platform.ai.llm.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;

/** Persists completed reads within the existing compiler input and accepted graph scope. */
@ApplicationScoped
public class ToolReadProgress {
  private static final int REPEATED_READ_LIMIT = 3;

  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;
  private final CaptureAttemptFeedbackStore feedback;
  private final ChainPlanStore graphs;
  private final ObjectMapper json;

  public record Read(String tool, JsonNode arguments, JsonNode result, List<String> callIds) {}
  public record State(String scope, Map<String, Read> reads, List<String> seenCallIds) {}

  @Inject
  public ToolReadProgress(CompilationArtifacts artifacts, CompilationSessions sessions,
      CaptureAttemptFeedbackStore feedback, ChainPlanStore graphs, ObjectMapper json) {
    this.artifacts = artifacts;
    this.sessions = sessions;
    this.feedback = feedback;
    this.graphs = graphs;
    this.json = json;
  }

  public synchronized void check(ChatRequest request, String conversation, String owner, String runId) {
    if (conversation == null || owner == null || runId == null || !feedback.hasScope(conversation, owner)) return;
    String scope = CaptureProposal.digest(List.of(runId, owner, feedback.inputScope(conversation, owner),
        graphs.get(conversation).map(CaptureProposal::digest).orElse("")));
    String compilation = sessions.active(conversation);
    var previous = artifacts.history(compilation, Kind.TOOL_READ_PROGRESS).stream()
        .filter(revision -> "tool-read-progress".equals(revision.producerId()))
        .filter(revision -> scope.equals(revision.payload().path("scope").asText()))
        .reduce((first, last) -> last);
    var reads = new LinkedHashMap<String, Read>();
    var seen = new java.util.LinkedHashSet<String>();
    previous.ifPresent(revision -> {
      var state = artifacts.payload(revision, State.class);
      reads.putAll(state.reads());
      seen.addAll(state.seenCallIds());
    });
    var before = new LinkedHashMap<>(reads);
    int limit = REPEATED_READ_LIMIT;
    Read repeated = null;
    var calls = new LinkedHashMap<String, ToolExecutionRequest>();
    int start = 0;
    for (int i = 0; i < request.messages().size(); i++) {
      if (request.messages().get(i) instanceof UserMessage) start = i + 1;
    }
    for (var message : request.messages().subList(start, request.messages().size())) {
      if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
        ai.toolExecutionRequests().forEach(call -> calls.put(call.id(), call));
      } else if (message instanceof ToolExecutionResultMessage result) {
        var call = calls.get(result.id());
        if (call == null || !call.name().equals(result.toolName())
            || !LlmRequestBudget.REPLACEABLE_READS.contains(call.name()) || !seen.add(call.id())) continue;
        var arguments = parse(call.arguments());
        var data = parse(result.text());
        String key = CaptureProposal.digest(List.of(call.name(), arguments));
        var old = reads.get(key);
        var ids = new ArrayList<>(old != null && old.result().equals(data) ? old.callIds() : List.<String>of());
        ids.add(call.id());
        var read = new Read(call.name(), arguments, data, List.copyOf(ids));
        reads.put(key, read);
        if (ids.size() >= limit) repeated = read;
      }
    }
    if (!before.equals(reads)) {
      artifacts.append(new CompilationArtifacts.AppendCommand(compilation, Kind.TOOL_READ_PROGRESS, "1",
          "tool-read-progress", "1", new State(scope, Map.copyOf(reads), List.copyOf(seen)), List.of(),
          previous.map(CompilationArtifacts.Revision::artifactId).orElse(null)));
    }
    if (repeated != null) {
      throw new ReadNoProgressException(json.valueToTree(Map.of("runId", runId, "owner", owner,
          "inputScope", feedback.inputScope(conversation, owner), "stateScope", scope, "read", repeated)).toString());
    }
  }

  private JsonNode parse(String value) {
    try {
      JsonNode parsed = json.readTree(value);
      if (parsed != null && parsed.isTextual()) {
        try { return json.readTree(parsed.textValue()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { return parsed; }
      }
      return parsed == null ? json.nullNode() : parsed;
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
      return json.getNodeFactory().textNode(value);
    }
  }
}
