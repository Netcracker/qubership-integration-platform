package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecTitleExtractor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.plan.workdocument.source.AttachmentRoles;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.storage.S3Service;

@ApplicationScoped
public class UploadedSpecOperations {
  private static final YAMLMapper YAML = new YAMLMapper();
  private final ConversationService conversations;
  private final S3Service s3;

  @Inject
  public UploadedSpecOperations(ConversationService conversations, S3Service s3) {
    this.conversations = conversations;
    this.s3 = s3;
  }

  public Set<String> pendingInteractionIds(String conversationId, RequirementDraft draft) {
    if (draft == null) return Set.of();
    List<RequirementFlow.Interaction> interactions = draft.flow().interactions();
    if (interactions.isEmpty()) {
      interactions = draft.facts().stream()
          .filter(fact -> fact.kind() == RequirementFactKind.SERVICE_CALL
              && fact.polarity() == RequirementFactPolarity.POSITIVE)
          .map(fact -> new RequirementFlow.Interaction(
              fact.serviceCallId() == null || fact.serviceCallId().isBlank() ? fact.sourceFactId() : fact.serviceCallId(),
              RequirementFlow.Direction.OUTBOUND, fact.participant(), fact.operation(), fact.text()))
          .toList();
    }
    Set<String> matched = new HashSet<>();
    if (interactions.isEmpty()) return matched;
    for (String key : conversations.getAllowedAttachmentKeys(conversationId)) {
      if (!AttachmentRoles.importsAsSpecification(key)) continue;
      byte[] content = s3.readObjectBytes(key);
      JsonNode document;
      try {
        document = YAML.readTree(content);
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
      if (document == null) continue;
      String name = UploadedSpecTitleExtractor.resolveSpecName(content, key.substring(key.lastIndexOf('/') + 1));
      for (var interaction : interactions) {
        if (draft.catalogBinding(interaction.interactionId()).isEmpty()
            && interaction.participant() != null && name.equalsIgnoreCase(interaction.participant().strip())
            && interaction.operation() != null && declaresOperation(document, interaction.operation().strip())) {
          matched.add(interaction.interactionId());
        }
      }
    }
    return Set.copyOf(matched);
  }

  private static boolean declaresOperation(JsonNode document, String operation) {
    if (document.path("operations").has(operation)) return true;
    for (JsonNode path : document.path("paths")) {
      for (var method : RequirementCaptureInput.HttpMethod.values()) {
        if (operation.equals(path.path(method.name().toLowerCase(Locale.ROOT)).path("operationId").asText())) return true;
      }
    }
    for (JsonNode channel : document.path("channels")) {
      if (operation.equals(channel.path("publish").path("operationId").asText())
          || operation.equals(channel.path("subscribe").path("operationId").asText())) return true;
    }
    String[] route = operation.split("\\s+", 2);
    return route.length == 2 && document.path("paths").path(route[1]).has(route[0].toLowerCase(Locale.ROOT));
  }
}
