package org.qubership.integration.platform.ai.chain.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationResult;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

/** Resolves chainId from chat attachment, regex fallback, or the chain a CREATE run built. */
@ApplicationScoped
public class ChainContextExtractor {

  private static final Pattern CHAIN_ID_IN_ATTACHMENT =
      Pattern.compile("(?m)\\(ID:\\s*([a-fA-F0-9-]{8,})\\)");

  private final ObjectMapper objectMapper;
  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;

  @Inject
  public ChainContextExtractor(
      ObjectMapper objectMapper,
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore) {
    this.objectMapper = objectMapper;
    this.runStore = runStore;
    this.artifactStore = artifactStore;
  }

  public boolean hasChainContext(ChatRequest request, String conversationId) {
    return resolveChainId(request, conversationId).isPresent();
  }

  public Optional<String> resolveChainId(ChatRequest request, String conversationId) {
    Optional<String> fromAttachment = parseAttachment(request != null ? request.getAttachment() : null);
    if (fromAttachment.isPresent()) {
      return fromAttachment;
    }
    return resolveFromPipelineState(conversationId);
  }

  private Optional<String> parseAttachment(String attachment) {
    if (attachment == null || attachment.isBlank()) {
      return Optional.empty();
    }

    Optional<String> fromJson = parseCompactSchemaChainId(attachment);
    if (fromJson.isPresent()) {
      return fromJson;
    }

    Matcher matcher = CHAIN_ID_IN_ATTACHMENT.matcher(attachment);
    if (matcher.find()) {
      return Optional.of(matcher.group(1).trim());
    }
    return Optional.empty();
  }

  private Optional<String> parseCompactSchemaChainId(String attachment) {
    int jsonStart = attachment.indexOf("```json");
    if (jsonStart < 0) {
      return Optional.empty();
    }
    int bodyStart = attachment.indexOf('\n', jsonStart);
    int jsonEnd = attachment.indexOf("```", bodyStart + 1);
    if (bodyStart < 0 || jsonEnd <= bodyStart) {
      return Optional.empty();
    }
    String jsonBody = attachment.substring(bodyStart + 1, jsonEnd).trim();
    try {
      JsonNode node = objectMapper.readTree(jsonBody);
      String chainId = node.path("chainId").asText("");
      if (!chainId.isBlank()) {
        return Optional.of(chainId.trim());
      }
    } catch (Exception ignored) {
      // attachment is best-effort
    }
    return Optional.empty();
  }

  /**
   * The chain this conversation's CREATE run wrote to the catalog, if it wrote one.
   *
   * <p>Without this, a conversation that just built a chain has no chain in context until the UI
   * sends one, so "now drop the audit step" reads as the start of another integration rather than a
   * change to the one on screen. A materialized run has a chain id; a run still drafting does not,
   * and answers empty.
   */
  private Optional<String> resolveFromPipelineState(String conversationId) {
    if (runStore == null || artifactStore == null || conversationId == null) {
      return Optional.empty();
    }
    try {
      return runStore
          .loadByConversation(conversationId)
          .flatMap(document -> artifactStore.latest(document.run().runId(), Kind.MATERIALIZATION_RESULT))
          .map(revision -> artifactStore.payload(revision, MaterializationResult.class))
          .map(MaterializationResult::chainId)
          .filter(chainId -> chainId != null && !chainId.isBlank());
    } catch (RuntimeException e) {
      // Chain context is best-effort: a store that cannot answer must not fail the turn.
      return Optional.empty();
    }
  }
}
