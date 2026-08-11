package org.qubership.integration.platform.ai.chain.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/** Resolves chainId from chat attachment, regex fallback, or implement pipeline state. */
@ApplicationScoped
public class ChainContextExtractor {

  private static final Pattern CHAIN_ID_IN_ATTACHMENT =
      Pattern.compile("(?m)\\(ID:\\s*([a-fA-F0-9-]{8,})\\)");

  private final ObjectMapper objectMapper;
  private final InMemorySkillWorkspaceStore workspaceStore;

  @Inject
  public ChainContextExtractor(
      ObjectMapper objectMapper, InMemorySkillWorkspaceStore workspaceStore) {
    this.objectMapper = objectMapper;
    this.workspaceStore = workspaceStore;
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

  private Optional<String> resolveFromPipelineState(String conversationId) {
    return Optional.empty();
  }
}
