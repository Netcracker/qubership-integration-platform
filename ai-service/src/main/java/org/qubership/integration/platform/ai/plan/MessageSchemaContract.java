package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

public final class MessageSchemaContract {
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

  private MessageSchemaContract() {}

  static boolean customHttp(CapabilityInput capability) {
    return capability != null && "http-trigger".equals(capability.capabilityKey())
        && capability.httpMode() == RequirementCaptureInput.HttpMode.CUSTOM;
  }

  static void validateFact(DraftInput draft, FactInput fact, String path, List<Issue> issues) {
    if (fact.polarity() != RequirementCaptureInput.Polarity.POSITIVE || fact.interactionIds() == null
        || fact.interactionIds().size() != 1 || draft.capabilities().stream().filter(MessageSchemaContract::customHttp)
            .noneMatch(capability -> fact.interactionIds().contains(capability.interactionId()))) {
      issues.add(new Issue("MESSAGE_SCHEMA_OWNER_REQUIRED", path, fact.sourceFactId(),
          "Scope the request body schema to exactly one custom HTTP trigger."));
      return;
    }
    if (draft.facts().stream().filter(Objects::nonNull)
        .filter(other -> other.kind() == RequirementCaptureInput.FactKind.MESSAGE_SCHEMA
            && fact.interactionIds().equals(other.interactionIds())).count() > 1) {
      issues.add(new Issue("MESSAGE_SCHEMA_CONFLICT", path, fact.sourceFactId(),
          "Keep one confirmed request body schema for this trigger."));
    }
    try {
      parse(fact.text());
    } catch (IllegalArgumentException invalid) {
      issues.add(new Issue("MESSAGE_SCHEMA_INVALID", path + "/text", fact.sourceFactId(), invalid.getMessage()));
    }
  }

  static List<Issue> validateEvidence(DraftInput draft, RequirementDraft previous, AuthorSourceEvidence.Packet source) {
    var capabilities = draft.capabilities() == null ? List.<CapabilityInput>of() : draft.capabilities();
    long customTriggers = capabilities.stream().filter(MessageSchemaContract::customHttp).count();
    return draft.facts().stream().filter(Objects::nonNull)
        .filter(fact -> fact.kind() == RequirementCaptureInput.FactKind.MESSAGE_SCHEMA)
        .filter(fact -> previous == null || previous.authoredDraft() == null
            || !previous.authoredDraft().facts().contains(fact)
            || !capabilities.stream().filter(MessageSchemaContract::customHttp)
                .filter(capability -> fact.interactionIds() != null
                    && fact.interactionIds().contains(capability.interactionId()))
                .allMatch(previous.authoredDraft().capabilities()::contains))
        .filter(fact -> !authorSupplied(fact, source, customTriggers, capabilities))
        .map(fact -> new Issue("MESSAGE_SCHEMA_SOURCE_REQUIRED", "/draft/facts/" + fact.sourceFactId(),
            fact.sourceFactId(), "Copy the complete JSON Schema from the saved user turn into text and cite it"
                + " in evidence. When the flow has multiple custom HTTP triggers, quote the HTTP method and route"
                + " together with the schema."))
        .toList();
  }

  private static boolean authorSupplied(FactInput fact, AuthorSourceEvidence.Packet source, long customTriggers,
      List<CapabilityInput> capabilities) {
    var evidence = fact.evidence();
    if (evidence == null || fact.text() == null || fact.text().isBlank() || evidence.quote() == null
        || !evidence.quote().contains(fact.text()) || fact.interactionIds() == null) return false;
    return source.inputs().stream().anyMatch(turn -> Objects.equals(evidence.sourceTurnId(), turn.sourceTurnId())
        && turn.text() != null && turn.text().contains(evidence.quote())
        && (customTriggers <= 1 || scopeMatches(fact, capabilities)));
  }

  private static boolean scopeMatches(FactInput fact, List<CapabilityInput> capabilities) {
    String routeEvidence = fact.evidence().quote().replace(fact.text(), "");
    var namedRoutes = capabilities.stream().filter(MessageSchemaContract::customHttp)
        .filter(capability -> capability.httpMethod() != null && capability.path() != null
            && !capability.path().isBlank())
        .filter(capability -> Pattern.compile("(?<![\\w-])" + capability.httpMethod().name() + "\\s+"
            + Pattern.quote(capability.path()) + "(?![\\w/%?+#.~=-])").matcher(routeEvidence).find())
        .map(CapabilityInput::interactionId)
        .toList();
    return namedRoutes.equals(fact.interactionIds());
  }

  public static Optional<MappingSchemaSide> requestSchema(List<RequirementFact> facts, String interactionId) {
    var schemas = facts.stream().filter(fact -> fact.kind() == RequirementFactKind.MESSAGE_SCHEMA
        && fact.polarity() == RequirementFactPolarity.POSITIVE
        && fact.interactionIds().equals(List.of(interactionId))).toList();
    if (schemas.size() > 1) throw new IllegalArgumentException("Conflicting request body schemas for " + interactionId);
    return schemas.stream().findFirst().map(fact -> {
      JsonNode schema = parse(fact.text());
      String digest = CaptureProposal.digest(schema);
      return new MappingSchemaSide("1", interactionId, null, MappingPort.OUTPUT,
          "application/json", null, digest, "requirement:" + fact.sourceFactId(), schema);
    });
  }

  private static JsonNode parse(String text) {
    try {
      if (text != null) {
        JsonNode schema = JSON.readTree(text);
        if (schema != null && "object".equals(schema.path("type").asText())
            && schema.path("properties").isObject()) return schema;
      }
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Use a JSON Schema object with type object and properties.", invalid);
    }
    throw new IllegalArgumentException("Use a JSON Schema object with type object and properties.");
  }
}
