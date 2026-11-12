package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.chat.attachment.AttachmentKeys;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecTitleExtractor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
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

  public record Binding(String attachmentKey, String method, String path) {
    public String route() {
      return method + " " + path;
    }
  }

  private record Operation(Binding binding, String operationId) {
    boolean matches(String operation) {
      String[] route = operation.split("\\s+", 2);
      return !operationId.isEmpty() && operationId.equals(operation)
          || route.length == 2 && binding.method().equalsIgnoreCase(route[0])
              && binding.path().equals(route[1]);
    }

    boolean mentionedIn(String text) {
      return Pattern.compile("(?<![\\w-])(?i:" + binding.method() + ")`?\\h+`?"
          + Pattern.quote(binding.path()) + "(?![\\w/{}%~:-]|\\.[\\w/])").matcher(text).find()
          || !operationId.isEmpty() && Pattern.compile("(?<![\\w.-])"
              + Pattern.quote(operationId) + "(?![\\w-]|\\.[\\w])").matcher(text).find();
    }
  }

  private record Specification(String key, String name, JsonNode document) {
    boolean isOpenApi() {
      return document.hasNonNull("openapi") || document.hasNonNull("swagger");
    }

    List<Operation> operations() {
      List<Operation> operations = new ArrayList<>();
      document.path("paths").fields().forEachRemaining(path -> {
        for (var method : RequirementCaptureInput.HttpMethod.values()) {
          JsonNode operation = path.getValue().get(method.name().toLowerCase(Locale.ROOT));
          if (operation != null && operation.isObject()) {
            operations.add(new Operation(new Binding(key, method.name(), path.getKey()),
                operation.path("operationId").asText("")));
          }
        }
      });
      return operations;
    }
  }

  public Map<String, Binding> resolveOpenApi(
      String conversationId, RequirementCaptureInput.DraftInput input) {
    List<RequirementFact> facts = RequirementCaptureProjection.facts(input);
    var interactions = RequirementCaptureProjection.flow(input).interactions().stream()
        .filter(interaction -> isCatalogOutbound(interaction, facts))
        .toList();
    return interactions.isEmpty() ? Map.of()
        : bindings(openApiCandidates(conversationId, interactions, specifications(conversationId), true));
  }

  public List<Binding> ambiguousOpenApi(String conversationId, RequirementDraft draft, String interactionId) {
    if (draft.catalogBinding(interactionId).isPresent()) return List.of();
    var interactions = draft.flow().interactions().stream()
        .filter(interaction -> isCatalogOutbound(interaction, draft.facts())).toList();
    if (interactions.stream().noneMatch(interaction -> interaction.interactionId().equals(interactionId))) {
      return List.of();
    }
    var candidates = openApiCandidates(conversationId, interactions, specifications(conversationId), false)
        .getOrDefault(interactionId, List.of());
    return candidates.size() > 1 ? candidates.stream().map(Operation::binding).toList() : List.of();
  }

  private static Map<String, Binding> bindings(Map<String, List<Operation>> candidates) {
    Map<String, Binding> bindings = new HashMap<>();
    candidates.forEach((id, operations) -> {
      if (operations.size() == 1) bindings.put(id, operations.getFirst().binding());
    });
    return Map.copyOf(bindings);
  }

  private Map<String, List<Operation>> openApiCandidates(String conversationId,
      List<RequirementFlow.Interaction> interactions, List<Specification> specs, boolean requireAuthorEvidence) {
    var openApis = specs.stream().filter(Specification::isOpenApi).toList();
    if (interactions.isEmpty() || openApis.isEmpty()) return Map.of();
    var sources = conversations.archive().read(conversationId).activeTurns().reversed();
    Map<String, List<Operation>> candidates = new HashMap<>();
    for (var interaction : interactions) {
      var matchingSpecs = openApis.stream()
          .filter(spec -> spec.name().equalsIgnoreCase(interaction.participant())).toList();
      boolean exactTitle = !matchingSpecs.isEmpty();
      if (!exactTitle && interactions.size() != 1) continue;
      var eligible = exactTitle ? matchingSpecs : openApis;
      var authored = authorOperations(interaction, sources, eligible, exactTitle);
      var exact = authored.stream().filter(operation -> operation.matches(interaction.operation())).toList();
      if (!exact.isEmpty()) {
        candidates.put(interaction.interactionId(), exact);
      } else if (!authored.isEmpty() && exactTitle && interactions.stream()
          .filter(other -> other.participant().equalsIgnoreCase(interaction.participant())).count() == 1) {
        candidates.put(interaction.interactionId(), authored);
      } else if (authored.isEmpty() && exactTitle && !requireAuthorEvidence) {
        candidates.put(interaction.interactionId(), eligible.stream().flatMap(spec -> spec.operations().stream())
            .filter(operation -> operation.matches(interaction.operation())).toList());
      }
    }
    return candidates;
  }

  private static List<Operation> authorOperations(RequirementFlow.Interaction interaction,
      List<ConversationArchive.Turn> sources, List<Specification> eligible, boolean exactTitle) {
    for (var turn : sources) {
      String text = turn.rawText();
      if (text == null || text.isBlank()) continue;
      var attachmentKeys = sourceAttachmentKeys(turn);
      var explicitlySelected = eligible.stream().filter(spec -> text.contains(spec.key())).toList();
      var selected = explicitlySelected.isEmpty() ? eligible : explicitlySelected;
      List<Operation> mentioned = selected.stream()
          .filter(spec -> !explicitlySelected.isEmpty() || attachmentKeys.contains(spec.key())
              || Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(spec.name()) + "(?![\\p{L}\\p{N}])",
                  Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find())
          .flatMap(spec -> spec.operations().stream())
          .filter(operation -> operation.mentionedIn(text) || !explicitlySelected.isEmpty()
              && operation.matches(interaction.operation()))
          .filter(operation -> exactTitle || operation.matches(interaction.operation()))
          .toList();
      if (!mentioned.isEmpty()) return mentioned;
    }
    return List.of();
  }

  private static List<String> sourceAttachmentKeys(ConversationArchive.Turn turn) {
    if (turn.metadata() == null) return List.of();
    List<String> keys = new ArrayList<>();
    turn.metadata().path("attachmentObjectKeys").forEach(key -> keys.add(key.asText()));
    return AttachmentKeys.normalize(keys);
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
    List<RequirementFlow.Interaction> unresolved = new ArrayList<>();
    for (var interaction : interactions) {
      if (draft.catalogBinding(interaction.interactionId()).isPresent()) continue;
      if (draft.uploadedSpecBindings().containsKey(interaction.interactionId())) {
        matched.add(interaction.interactionId());
      } else if (!RequirementFlowValidator.isNativeDirectInteraction(interaction, draft.facts())) {
        unresolved.add(interaction);
      }
    }
    if (unresolved.isEmpty()) return Set.copyOf(matched);
    var specs = specifications(conversationId);
    if (specs.isEmpty()) return Set.copyOf(matched);
    matched.addAll(bindings(openApiCandidates(conversationId, unresolved.stream()
        .filter(interaction -> isCatalogOutbound(interaction, draft.facts())).toList(), specs, false)).keySet());
    for (var interaction : unresolved) {
      if (specs.stream().anyMatch(spec ->
          (!spec.isOpenApi() || interaction.direction() == RequirementFlow.Direction.INBOUND)
              && spec.name().equalsIgnoreCase(interaction.participant())
              && declaresOperation(spec.document(), interaction.operation()))) {
        matched.add(interaction.interactionId());
      }
    }
    return Set.copyOf(matched);
  }

  private static boolean isCatalogOutbound(RequirementFlow.Interaction interaction, List<RequirementFact> facts) {
    return interaction.direction() == RequirementFlow.Direction.OUTBOUND
        && !RequirementFlowValidator.isDirectHttpTarget(interaction.participant())
        && RequirementFlowValidator.catalogLookupAction(interaction, facts) == RequirementFlowValidator.LookupAction.ASK;
  }

  private List<Specification> specifications(String conversationId) {
    List<Specification> specs = new ArrayList<>();
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
      specs.add(new Specification(key, name, document));
    }
    return specs;
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
