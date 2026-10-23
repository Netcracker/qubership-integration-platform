package org.qubership.integration.platform.ai.llm.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Keeps controlled setup offline and forwards only repair requests when explicitly enabled. */
public final class RecoveryAcceptanceProvider {
  private RecoveryAcceptanceProvider() {}

  public static boolean live() {
    return "true".equals(System.getenv("QIP_RECOVERY_LIVE"));
  }

  public static class Profile extends TestArtifactBlobStoreProducer.Profile {
    @Override
    public Map<String, String> getConfigOverrides() {
      if (!live()) return Map.of();
      return Map.ofEntries(
          Map.entry("qip.ai.llm.provider", required("LLM_PROVIDER")),
          Map.entry("qip.ai.llm.base-url", required("LLM_BASE_URL")),
          Map.entry("qip.ai.llm.model-name", required("LLM_CHAT_MODEL")),
          Map.entry("quarkus.langchain4j.openai.openai-upstream.api-key", required("LLM_API_KEY")),
          Map.entry("quarkus.langchain4j.openai.openai-upstream.base-url", required("LLM_BASE_URL")),
          Map.entry("quarkus.langchain4j.openai.openai-upstream.chat-model.model-name", required("LLM_CHAT_MODEL")),
          Map.entry("quarkus.langchain4j.openai.openai-upstream.chat-model.reasoning-effort", required("LLM_REASONING_EFFORT")),
          Map.entry("quarkus.langchain4j.openai.openai-upstream.chat-model.temperature", required("LLM_TEMPERATURE")),
          Map.entry("quarkus.langchain4j.openai.openai-upstream.chat-model.responses.max-output-tokens", required("LLM_MAX_TOKENS")),
          Map.entry("quarkus.langchain4j.timeout", required("LLM_TIMEOUT")));
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("Missing acceptance setting: " + name);
    return value;
  }

  public static StreamingChatModel controlledPrefix(StreamingChatModel script, StreamingChatModel upstream,
      int controlledRequests, String caseId, ObjectMapper json) {
    var calls = new AtomicInteger();
    return new StreamingChatModel() {
      @Override
      public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        int index = calls.incrementAndGet();
        boolean external = live() && index > controlledRequests;
        evidence(json, caseId, "request-" + index, Map.of("controlled", !external,
            "messages", request.messages().stream().map(Object::toString).toList(),
            "tools", request.parameters().toolSpecifications().stream().map(Object::toString).toList(), "model", external ? required("LLM_CHAT_MODEL") : "scripted"));
        (external ? upstream : script).chat(request, handler);
      }
      @Override
      public ChatRequestParameters defaultRequestParameters() {
        return live() ? upstream.defaultRequestParameters() : script.defaultRequestParameters();
      }
      @Override
      public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
        return live() ? upstream.supportedCapabilities() : script.supportedCapabilities();
      }
    };
  }

  public static RateLimitStreamingChatModel boundary(StreamingChatModel delegate, AppConfig config,
      RateLimitBackoffSleeper sleeper, LlmRunBudget budget, ToolReadProgress progress) {
    return new RateLimitStreamingChatModel(delegate, config, sleeper,
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget).withToolReadProgress(progress);
  }

  public static java.time.Duration observation(AppConfig config) {
    return live() ? java.time.Duration.ofSeconds(Long.parseLong(required("QIP_RECOVERY_OBSERVATION_SECONDS"))) : java.time.Duration.ofSeconds(30);
  }

  public static synchronized void evidence(ObjectMapper json, String caseId, String name, Object value) {
    String destination = System.getenv("QIP_RECOVERY_EVIDENCE");
    if (destination == null) return;
    try {
      Path folder = Path.of(destination).resolve(caseId);
      Files.createDirectories(folder);
      Path target = folder.resolve(name + ".json");
      if (Files.exists(target)) throw new IllegalStateException("Acceptance evidence already exists: " + target);
      json.writeValue(target.toFile(), value);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Cannot retain acceptance evidence", failure);
    }
  }
}
