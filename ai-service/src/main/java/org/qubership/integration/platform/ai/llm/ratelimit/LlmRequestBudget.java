package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.memory.CipJtokkitTokenCountEstimator;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Checks complete model requests before transport retries can send them. */
public final class LlmRequestBudget {
  private static final Logger LOG = Logger.getLogger(LlmRequestBudget.class);
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
  private static final Set<String> REPLACEABLE_READS = Set.of("readRequirementDraft",
      "searchCatalogSystems", "getApiSpecifications", "listCatalogOperations", "describeBoundOperation",
      "describeElementPatchSchema", "describeElementProperty");
  public static final class Overflow extends IllegalArgumentException {
    public Overflow(String message) { super(message); }
  }

  public static boolean isOverflow(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof Overflow) { return true; }
      if (current instanceof dev.langchain4j.exception.HttpException http
          && (http.statusCode() == 400 || http.statusCode() == 413)) {
        if (http.getMessage() == null) { continue; }
        try {
          var error = JSON.readTree(http.getMessage()).path("error");
          if ("context_length_exceeded".equals(error.path("code").asText())) { return true; }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
          // An unstructured provider error does not establish context overflow.
        }
      }
      if (current == current.getCause()) { break; }
    }
    return false;
  }

  private final AppConfig.LlmConfig config;
  private final CipJtokkitTokenCountEstimator estimator;

  LlmRequestBudget(AppConfig config) {
    this.config = config.llm();
    this.estimator = new CipJtokkitTokenCountEstimator(this.config.modelName());
  }

  ChatRequest prepare(ChatRequest request) {
    request.messages().forEach(message -> {
      if (message instanceof dev.langchain4j.data.message.UserMessage user && user.hasSingleText()) {
        org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.bind(user.singleText());
      }
    });
    var parameters = request.parameters();
    var tools = parameters.toolSpecifications();
    if (tools != null && !tools.isEmpty()) {
      tools = tools.stream().map(org.qubership.integration.platform.ai.plan.RequirementCaptureSchemas::specification)
          .toList();
      parameters = parameters.overrideWith(DefaultChatRequestParameters.builder().toolSpecifications(tools).build());
      request = request.toBuilder().parameters(parameters).build();
    }
    boolean analysis = tools != null && tools.stream().anyMatch(tool ->
        "captureRequirementBrief".equals(tool.name())
            && tool.parameters().properties().containsKey("capture"));
    if (parameters.maxOutputTokens() == null) {
      parameters = parameters.overrideWith(DefaultChatRequestParameters.builder().maxOutputTokens(8192).build());
      request = request.toBuilder().parameters(parameters).build();
    }
    if (analysis) {
      int ceiling = positive(config.analysisOutputTokens(), 4096);
      Integer existing = parameters.maxOutputTokens();
      parameters = parameters.overrideWith(DefaultChatRequestParameters.builder()
          .maxOutputTokens(existing == null ? ceiling : Math.min(existing, ceiling)).build());
      request = request.toBuilder().parameters(parameters).build();
    }
    int outputTokens = parameters.maxOutputTokens() == null ? 8192 : parameters.maxOutputTokens();
    int allowance = positive(config.contextTokens(), 131072);
    if ((long) estimateInputTokens(request) + outputTokens
        + positive(config.contextHeadroomTokens(), 8192) > allowance) {
      request = rebuild(request);
      if ((long) estimateInputTokens(request) + outputTokens
          + positive(config.contextHeadroomTokens(), 8192) > allowance) {
        request = omitRepairHistory(request);
      }
    }
    String packet = packet(request);
    // ponytail: CL100K estimates differ from provider tokenizers; reserve headroom before sending.
    int inputTokens = estimator.estimateTokenCountInText(packet);
    long reserved = (long) inputTokens + outputTokens
        + positive(config.contextHeadroomTokens(), 8192);
    String hash = hash(packet);
    LOG.infof("LLM input hash=%s estimatedTokens=%d outputTokens=%d contextTokens=%d stage=%s",
        hash, inputTokens, outputTokens, allowance, analysis ? "analysis" : "agent");
    if (reserved > allowance) {
      throw new Overflow("LLM_CONTEXT_OVERFLOW: required input estimate="
          + inputTokens + ", output=" + outputTokens + ", context=" + allowance + ", hash=" + hash);
    }
    return request;
  }

  int estimateInputTokens(ChatRequest request) {
    return estimator.estimateTokenCountInText(packet(request));
  }

  private static String packet(ChatRequest request) {
    return request.messages() + "\n" + request.parameters().toolSpecifications()
        + "\n" + request.parameters().responseFormat();
  }

  /** Retains the latest successful read for the same tool and exact arguments. Sources stay intact. */
  ChatRequest rebuild(ChatRequest request) {
    Map<String, String> keysByCall = new HashMap<>();
    Map<String, ToolExecutionResultMessage> latest = new HashMap<>();
    for (ChatMessage message : request.messages()) {
      if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
        for (var call : ai.toolExecutionRequests()) {
          if (REPLACEABLE_READS.contains(call.name())) {
            keysByCall.put(call.id(), call.name() + "\u0000" + call.arguments());
          }
        }
      } else if (message instanceof ToolExecutionResultMessage result
          && keysByCall.containsKey(result.id()) && successfulRead(result)) {
        latest.put(keysByCall.get(result.id()), result);
      }
    }
    var messages = new ArrayList<ChatMessage>(request.messages());
    for (int i = 0; i < messages.size(); i++) {
      if (!(messages.get(i) instanceof ToolExecutionResultMessage result)
          || !successfulRead(result)) { continue; }
      var replacement = latest.get(keysByCall.get(result.id()));
      if (replacement == null || replacement == result) { continue; }
      var marker = JSON.createObjectNode();
      marker.put("supersededByToolResultId", replacement.id());
      marker.put("instruction", "Use the later result for the same read. Author sources remain unchanged.");
      messages.set(i, ToolExecutionResultMessage.builder().id(result.id()).toolName(result.toolName())
          .text(marker.toString()).isError(result.isError()).attributes(result.attributes()).build());
    }
    return request.toBuilder().messages(messages).build();
  }

  private static ChatRequest omitRepairHistory(ChatRequest request) {
    var messages = request.messages().stream().map(message -> {
      if (message instanceof dev.langchain4j.data.message.UserMessage user && user.hasSingleText()) {
        String reduced = org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.omit(user.singleText());
        if (!reduced.equals(user.singleText())) {
          return (ChatMessage) user.toBuilder().contents(java.util.List.of(dev.langchain4j.data.message.TextContent.from(reduced))).build();
        }
      }
      return message;
    }).toList();
    return request.toBuilder().messages(messages).build();
  }

  private static boolean successfulRead(ToolExecutionResultMessage result) {
    if (Boolean.TRUE.equals(result.isError())) { return false; }
    try {
      var value = JSON.readTree(result.text());
      return value == null || !value.path("ok").isBoolean() || value.path("ok").asBoolean();
    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
      return true;
    }
  }

  static String hash(String packet) {
    return org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash.sha256Hex(packet);
  }

  private static int positive(int value, int fallback) {
    return value > 0 ? value : fallback;
  }
}
