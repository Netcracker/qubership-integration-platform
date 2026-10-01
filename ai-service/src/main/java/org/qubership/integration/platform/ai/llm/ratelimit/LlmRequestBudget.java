package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.memory.QipJtokkitTokenCountEstimator;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Checks complete model requests before transport retries can send them. */
public final class LlmRequestBudget {
  private static final Logger LOG = Logger.getLogger(LlmRequestBudget.class);
  public static final class Overflow extends IllegalArgumentException {
    Overflow(String message) { super(message); }
  }

  public static boolean isOverflow(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof Overflow) { return true; }
      if (current == current.getCause()) { break; }
    }
    return false;
  }

  private final AppConfig.LlmConfig config;
  private final QipJtokkitTokenCountEstimator estimator;

  LlmRequestBudget(AppConfig config) {
    this.config = config.llm();
    this.estimator = new QipJtokkitTokenCountEstimator(this.config.modelName());
  }

  ChatRequest prepare(ChatRequest request) {
    var parameters = request.parameters();
    var tools = parameters.toolSpecifications();
    boolean analysis = tools != null && tools.stream().anyMatch(tool ->
        "captureRequirementBrief".equals(tool.name())
            && tool.parameters().properties().containsKey("capture"));
    if (analysis) {
      int ceiling = positive(config.analysisOutputTokens(), 4096);
      Integer existing = parameters.maxOutputTokens();
      parameters = parameters.overrideWith(DefaultChatRequestParameters.builder()
          .maxOutputTokens(existing == null ? ceiling : Math.min(existing, ceiling)).build());
      request = request.toBuilder().parameters(parameters).build();
    }
    String packet = request.messages().toString() + "\n" + tools + "\n" + parameters.responseFormat();
    // ponytail: CL100K estimates differ from provider tokenizers; reserve headroom before sending.
    int inputTokens = estimator.estimateTokenCountInText(packet);
    int outputTokens = parameters.maxOutputTokens() == null ? 8192 : parameters.maxOutputTokens();
    int allowance = positive(config.contextTokens(), 131072);
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

  static String hash(String packet) {
    return org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash.sha256Hex(packet);
  }

  private static int positive(int value, int fallback) {
    return value > 0 ? value : fallback;
  }
}
