package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;

public final class MessageSchemaContract {
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

  private static final ObjectReader JSON_FRAGMENT = JSON.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private MessageSchemaContract() {}

  static boolean customHttp(CapabilityInput capability) {
    return capability != null && "http-trigger".equals(capability.capabilityKey())
        && capability.httpMode() == RequirementCaptureInput.HttpMode.CUSTOM;
  }

  static List<CapabilityInput> missingCopySchemas(DraftInput draft) {
    var origins = new HashSet<String>();
    var supplied = new HashSet<String>();
    for (FactInput fact : draft.facts()) {
      if (fact.polarity() != RequirementCaptureInput.Polarity.POSITIVE) continue;
      if (fact.fieldMapping() != null && fact.fieldMapping().value() instanceof MappingValue.Copy copy
          && copy.source() instanceof MappingSource.Message message) {
        origins.add(message.interactionId());
      }
      if (fact.kind() == RequirementCaptureInput.FactKind.MESSAGE_SCHEMA && fact.interactionIds().size() == 1) {
        supplied.add(fact.interactionIds().getFirst());
      }
    }
    return draft.capabilities().stream().filter(MessageSchemaContract::customHttp)
        .filter(owner -> origins.contains(owner.interactionId()) && !supplied.contains(owner.interactionId())).toList();
  }

  static DraftInput withInitialClarification(DraftInput draft, AuthorSourceEvidence.Packet source,
      List<String> reviewedInputIds) {
    if (draft == null || draft.facts() == null || source.inputs().isEmpty()
        || source.inputs().stream().map(AuthorSourceEvidence.Input::text).filter(Objects::nonNull)
            .anyMatch(MessageSchemaContract::containsSchema)) {
      return draft;
    }
    var facts = draft.facts().stream().filter(fact -> fact == null
        || fact.kind() != RequirementCaptureInput.FactKind.MESSAGE_SCHEMA
        || quotedSchemaInput(fact, source)).toList();
    var partial = new DraftInput(draft.flow(), facts, draft.capabilities(), draft.openQuestions(), draft.settings());
    if (!RequirementCaptureEditor.initialize(partial).accepted()) return draft;
    var missing = missingCopySchemas(partial).stream()
        .filter(owner -> owner.httpMethod() != null && owner.path() != null && !owner.path().isBlank()).toList();
    if (missing.isEmpty()) return draft;
    var questions = new ArrayList<>(partial.openQuestions());
    boolean multipleRoutes = partial.capabilities().stream().filter(MessageSchemaContract::customHttp).count() > 1;
    var usedIds = questions.stream().map(QuestionInput::questionId).collect(Collectors.toSet());
    for (CapabilityInput owner : missing) {
      var evidence = new AuthorQuestionEvidence(owner.interactionId(), "requestSchema",
          AuthorQuestionEvidence.Kind.MISSING_VALUE, reviewedInputIds);
      boolean present = questions.stream().anyMatch(question -> question.evidence() != null
          && evidence.interactionId().equals(question.evidence().interactionId())
          && evidence.propertyKey().equals(question.evidence().propertyKey()));
      if (!present) {
        questions.add(new QuestionInput(RequirementCaptureProjection.uniqueDerivedId(usedIds, "request-schema"),
            List.of(owner.interactionId()), "Provide the JSON Schema for the request body of "
                + owner.httpMethod() + " " + owner.path() + "."
                + (multipleRoutes ? " Include the method and path with the schema." : ""), evidence));
      }
    }
    return new DraftInput(partial.flow(), facts, partial.capabilities(), List.copyOf(questions), partial.settings());
  }

  static Optional<FactInput> answer(DraftInput draft, QuestionInput question, String turnId, String text) {
    var evidence = question.evidence();
    if (evidence == null || evidence.kind() != AuthorQuestionEvidence.Kind.MISSING_VALUE
        || !"requestSchema".equals(evidence.propertyKey()) || draft.openQuestions().size() != 1
        || draft.capabilities().stream().filter(MessageSchemaContract::customHttp).count() != 1
        || missingCopySchemas(draft).stream().noneMatch(owner -> owner.interactionId().equals(evidence.interactionId()))) {
      return Optional.empty();
    }
    String schema = text.strip();
    try {
      parse(schema);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    var usedIds = draft.facts().stream().map(FactInput::sourceFactId).collect(Collectors.toSet());
    return Optional.of(new FactInput(RequirementCaptureProjection.uniqueDerivedId(usedIds, "request-schema"),
        List.of(evidence.interactionId()), RequirementCaptureInput.FactKind.MESSAGE_SCHEMA,
        RequirementCaptureInput.Polarity.POSITIVE, schema, null, null, null,
        new RequirementCaptureInput.ClaimEvidenceInput(turnId, schema)));
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

  private static boolean quotedSchemaInput(FactInput fact, AuthorSourceEvidence.Packet source) {
    if (!authorQuoted(fact, source)) return false;
    try {
      return JSON.readTree(fact.text()) != null;
    } catch (JsonProcessingException invalid) {
      return fact.text().stripLeading().startsWith("{");
    }
  }

  private static boolean authorSupplied(FactInput fact, AuthorSourceEvidence.Packet source, long customTriggers,
      List<CapabilityInput> capabilities) {
    return fact.interactionIds() != null && authorQuoted(fact, source)
        && (customTriggers <= 1 || scopeMatches(fact, capabilities));
  }

  private static boolean authorQuoted(FactInput fact, AuthorSourceEvidence.Packet source) {
    var evidence = fact.evidence();
    if (evidence == null || fact.text() == null || fact.text().isBlank() || evidence.quote() == null
        || !evidence.quote().contains(fact.text())) return false;
    return source.inputs().stream().anyMatch(turn -> Objects.equals(evidence.sourceTurnId(), turn.sourceTurnId())
        && turn.text() != null && turn.text().contains(evidence.quote()));
  }

  private static boolean containsSchema(String text) {
    int start = text.indexOf('{');
    if (start < 0 || !text.contains("\"properties\"") && !text.contains("\\u")) return false;
    char[] chars = text.toCharArray();
    for (; start >= 0; start = text.indexOf('{', start + 1)) {
      try (var parser = JSON.getFactory().createParser(chars, start, chars.length - start)) {
        JsonNode node = JSON_FRAGMENT.readTree(parser);
        if (containsSchema(node)) return true;
        start += Math.max(0, (int) parser.currentLocation().getCharOffset() - 1);
      } catch (IOException invalidFragment) {
        // A brace in prose need not open a JSON object.
      }
    }
    return false;
  }

  private static boolean containsSchema(JsonNode node) {
    if (isRequestSchema(node)) return true;
    for (JsonNode child : node) {
      if (containsSchema(child)) return true;
    }
    return false;
  }

  private static boolean isRequestSchema(JsonNode node) {
    return node != null && "object".equals(node.path("type").asText()) && node.path("properties").isObject();
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
        if (isRequestSchema(schema)) return schema;
      }
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Use a JSON Schema object with type object and properties.", invalid);
    }
    throw new IllegalArgumentException("Use a JSON Schema object with type object and properties.");
  }
}
