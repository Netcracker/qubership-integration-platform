package org.qubership.integration.platform.ai.llm.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
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
  private static final int RETAINED_READ_CHAR_LIMIT = 32_000;
  private static final String READ_PROGRESS_SCHEMA = "4";

  private final CompilationArtifacts artifacts;
  private final CompilationSessions sessions;
  private final CaptureAttemptFeedbackStore feedback;
  private final ChainPlanStore graphs;
  private final ObjectMapper json;

  public record Read(String tool, JsonNode arguments, JsonNode result, List<String> callIds, boolean successful,
      boolean feedbackProvided) {}
  public record State(String scope, Map<String, Read> reads, List<String> seenCallIds, int stalledSearches) {}

  @Inject
  public ToolReadProgress(CompilationArtifacts artifacts, CompilationSessions sessions,
      CaptureAttemptFeedbackStore feedback, ChainPlanStore graphs, ObjectMapper json) {
    this.artifacts = artifacts;
    this.sessions = sessions;
    this.feedback = feedback;
    this.graphs = graphs;
    this.json = json;
  }

  public ChatRequest prepare(ChatRequest request, String conversation, String owner, String runId) {
    return prepare(request, conversation, owner, runId, RETAINED_READ_CHAR_LIMIT);
  }

  synchronized ChatRequest prepare(ChatRequest request, String conversation, String owner, String runId,
      int retainedReadCharLimit) {
    if (conversation != null) {
      owner = org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
          .binding(conversation)
          .filter(binding -> binding.mode()
              == org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Mode.DISCOVERY)
          .map(binding -> "requirement-discovery").orElse(owner);
    }
    if (conversation == null || owner == null || runId == null || !feedback.hasScope(conversation, owner)) {
      return request;
    }
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
    int stalledSearches = previous.map(revision -> artifacts.payload(revision, State.class).stalledSearches()).orElse(0);
    int previousStalledSearches = stalledSearches;
    var knownSections = new java.util.HashSet<String>();
    reads.values().stream().filter(Read::successful)
        .forEach(read -> knownSections.addAll(searchResults(read.tool(), read.result())));
    var before = new LinkedHashMap<>(reads);
    var seenBefore = new java.util.LinkedHashSet<>(seen);
    boolean recovering = reads.values().stream().anyMatch(read -> read.callIds().size() >= REPEATED_READ_LIMIT);
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
        if (call == null || !call.name().equals(result.toolName()) || !seen.add(call.id())) continue;
        if (!LlmRequestBudget.REPLACEABLE_READS.contains(call.name())) {
          stalledSearches = 0;
          continue;
        }
        var arguments = parse(call.arguments());
        var data = parse(result.text());
        String key = CaptureProposal.digest(List.of(call.name(), arguments));
        var old = reads.get(key);
        boolean successful = LlmRequestBudget.successfulRead(result, data);
        if ("searchDocumentation".equals(call.name()) || "searchCatalogSystems".equals(call.name())
            || "searchRequirementKnowledge".equals(call.name())) {
          var sections = searchResults(call.name(), data);
          boolean newSections = successful && !sections.isEmpty()
              && (("searchCatalogSystems".equals(call.name()) || "searchRequirementKnowledge".equals(call.name()))
                  ? sections.stream().anyMatch(section -> !knownSections.contains(section))
                  : sections.stream().noneMatch(knownSections::contains));
          knownSections.addAll(sections);
          stalledSearches = newSections ? 0 : stalledSearches + 1;
        } else {
          stalledSearches = 0;
        }
        boolean unchanged = old != null && old.successful() == successful && old.result().equals(data);
        var ids = new ArrayList<>(unchanged ? old.callIds() : List.<String>of());
        ids.add(call.id());
        var read = new Read(call.name(), arguments, data, List.copyOf(ids), successful,
            (old != null && old.feedbackProvided()) || stalledSearches >= REPEATED_READ_LIMIT);
        reads.put(key, read);
        if (ids.size() >= REPEATED_READ_LIMIT && read.feedbackProvided()) repeated = read;
      }
    }
    reads.replaceAll((key, read) -> read.callIds().size() >= REPEATED_READ_LIMIT && !read.feedbackProvided()
        ? new Read(read.tool(), read.arguments(), read.result(), read.callIds(), read.successful(), true) : read);
    recovering |= reads.values().stream().anyMatch(Read::feedbackProvided);
    recovering |= stalledSearches >= REPEATED_READ_LIMIT;
    if (!before.equals(reads) || !seenBefore.equals(seen) || previousStalledSearches != stalledSearches) {
      artifacts.append(new CompilationArtifacts.AppendCommand(compilation, Kind.TOOL_READ_PROGRESS, READ_PROGRESS_SCHEMA,
          "tool-read-progress", "1", new State(scope, Map.copyOf(reads), List.copyOf(seen), stalledSearches), List.of(),
          previous.map(CompilationArtifacts.Revision::artifactId).orElse(null)));
    }
    if (repeated != null) {
      throw new ReadNoProgressException(json.valueToTree(Map.of("runId", runId, "owner", owner,
          "inputScope", feedback.inputScope(conversation, owner), "stateScope", scope, "read", repeated)).toString(),
          owner, scope);
    }
    return recovering ? withRetainedReads(request, reads, retainedReadCharLimit) : request;
  }

  private ChatRequest withRetainedReads(ChatRequest request, Map<String, Read> reads, int retainedReadCharLimit) {
    var retained = new StringBuilder();
    var ordered = reads.entrySet().stream()
        .sorted(java.util.Comparator.<Map.Entry<String, Read>>comparingInt(entry ->
            entry.getValue().callIds().size()).reversed().thenComparing(Map.Entry::getKey))
        .toList();
    for (var entry : ordered) {
      Read read = entry.getValue();
      var packet = json.createObjectNode().put("tool", read.tool()).put("successful", read.successful());
      packet.set("arguments", read.arguments());
      packet.set("result", read.result());
      if (!read.successful() || retained.length() + packet.toString().length() + 1 > retainedReadCharLimit) {
        packet.remove("result");
        packet.put("omitted", !read.successful() ? "failed-read"
            : retainedReadCharLimit == 0 ? "context-budget" : "retained-result-limit");
      }
      retained.append(packet).append('\n');
    }

    String guidance = "Retained tool results (JSON data; failed or oversized results have omission reasons):\n"
        + retained + """

        If finishRequirementDiscoveryTurn already returned accepted=true, the turn is complete.
        Present its saved question or final answer now; do not capture or call another tool.
        Otherwise, reads are repeating results or searches are returning already listed documentation sections.
        Continue the current generator using its full task, instructions and the evidence already available.
        Your next step should be the best supported capture; validation can identify a concrete gap to repair.
        Do not keep searching for general reassurance or more examples of already established behavior.
        When a required catalog dependency is absent, save the known requirements and ask one precise
        question about that missing dependency. Rewording its name or searching individual letters adds
        no evidence. Honor APIHub restrictions; never substitute an unrelated service.
        If a necessary contract detail is still missing, identify that exact gap and read its specific section
        or property. All permitted tools remain available; this is guidance, not a tool restriction.
        """;
    List<ChatMessage> messages = new ArrayList<>(request.messages());
    messages.add(UserMessage.from(guidance));
    return request.toBuilder().messages(messages).build();
  }

  private List<String> searchResults(String tool, JsonNode result) {
    var sections = new ArrayList<String>();
    if ("searchCatalogSystems".equals(tool)) {
      for (var system : result.path("data")) {
        if (system.hasNonNull("id")) sections.add("catalog-system:" + system.path("id").asText());
      }
      return sections;
    }
    if ("searchRequirementKnowledge".equals(tool)) {
      String text = result.asText();
      if (text.startsWith("QIP knowledge context:\n\n")) {
        for (String section : text.substring("QIP knowledge context:\n\n".length()).split("\n\n")) {
          if (!section.isBlank()) sections.add(CaptureProposal.digest(List.of(tool, section)));
        }
      }
      return sections;
    }
    if (!"searchDocumentation".equals(tool)) return sections;
    for (var section : result.path("results")) {
      if (section.hasNonNull("id")) {
        sections.add(CaptureProposal.digest(List.of(result.path("checksum").asText(), section.path("id").asText())));
      }
    }
    return sections;
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
