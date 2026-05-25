package org.qubership.integration.platform.ai.diagnostics;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual connectivity checks against configured LLM endpoints (chat + embeddings). Does not log API
 * keys. Intended for development / troubleshooting.
 *
 * <p>Example: {@code GET /api/v1/diagnostics/llm} Optional query: {@code skipChat=true}, {@code
 * skipEmbedding=true}.
 */
@Path("/api/v1/diagnostics")
@Produces(MediaType.APPLICATION_JSON)
public class LlmDiagnosticsResource {

  private static final Logger LOG = Logger.getLogger(LlmDiagnosticsResource.class);

  private static final String CHAT_PROBE_MESSAGE =
      "Reply with exactly one ASCII word: pong. No punctuation or explanation.";

  private final EmbeddingModel embeddingModel;

  private final ChatModel chatModel;

  private final String llmBaseUrl;

  private final String configuredChatModelName;

  private final String configuredEmbeddingModelName;

  @Inject
  public LlmDiagnosticsResource(
      EmbeddingModel embeddingModel,
      ChatModel chatModel,
      @ConfigProperty(name = "quarkus.langchain4j.openai.base-url") String llmBaseUrl,
      @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name")
          String configuredChatModelName,
      @ConfigProperty(name = "quarkus.langchain4j.openai.embedding-model.model-name")
          String configuredEmbeddingModelName) {
    this.embeddingModel = embeddingModel;
    this.chatModel = chatModel;
    this.llmBaseUrl = llmBaseUrl;
    this.configuredChatModelName = configuredChatModelName;
    this.configuredEmbeddingModelName = configuredEmbeddingModelName;
  }

  @GET
  @Path("/llm")
  public Map<String, Object> checkLlm(
      @QueryParam("skipChat") boolean skipChat,
      @QueryParam("skipEmbedding") boolean skipEmbedding) {
    LOG.infof(
        "LLM connectivity diagnostic invoked (skipChat=%s, skipEmbedding=%s)",
        skipChat, skipEmbedding);

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("llmBaseUrl", llmBaseUrl);
    root.put("configuredChatModel", configuredChatModelName);
    root.put("configuredEmbeddingModel", configuredEmbeddingModelName);

    if (!skipEmbedding) {
      root.put("embedding", runEmbeddingProbe());
    }
    if (!skipChat) {
      root.put("chat", runChatProbe());
    }

    return root;
  }

  private Map<String, Object> runEmbeddingProbe() {
    Map<String, Object> emb = new LinkedHashMap<>();
    try {
      Response<Embedding> response = embeddingModel.embed("connectivity-probe");
      Embedding content = response.content();
      int dims = content.vector().length;
      emb.put("ok", true);
      emb.put("dimensions", dims);
      LOG.infof("Embedding probe succeeded: dimensions=%d", dims);
    } catch (Exception e) {
      LOG.error("Embedding connectivity probe failed", e);
      emb.put("ok", false);
      emb.put("errorType", e.getClass().getName());
      emb.put("errorMessage", e.getMessage());
    }
    return emb;
  }

  private Map<String, Object> runChatProbe() {
    Map<String, Object> chat = new LinkedHashMap<>();
    try {
      ChatResponse response =
          chatModel.chat(
              ChatRequest.builder().messages(UserMessage.from(CHAT_PROBE_MESSAGE)).build());
      String text = response.aiMessage() != null ? response.aiMessage().text() : "";
      String preview = text == null ? "" : text.trim();
      if (preview.length() > 240) {
        preview = preview.substring(0, 240) + "…";
      }
      chat.put("ok", true);
      chat.put("preview", preview);
      LOG.infof("Chat probe succeeded: preview length=%d", preview.length());
    } catch (Exception e) {
      LOG.error("Chat connectivity probe failed", e);
      chat.put("ok", false);
      chat.put("errorType", e.getClass().getName());
      chat.put("errorMessage", e.getMessage());
    }
    return chat;
  }
}
