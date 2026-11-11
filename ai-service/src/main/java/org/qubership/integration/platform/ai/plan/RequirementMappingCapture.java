package org.qubership.integration.platform.ai.plan;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FieldMappingInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

final class RequirementMappingCapture {
  private static final Pattern FIELD = Pattern.compile("(?:\\$\\.)?[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern MAP = Pattern.compile(
      "(?i)(?:\\bmap\\h+|\\band\\h+(?!map\\b))(?:the\\h+)?([\\w\\h-]+?)\\h+to\\h+([\\w\\h-]+?)(?=[.,;!?]|\\h+and\\h+(?:map|the)\\h+|$)");
  private static final Pattern NEGATION = Pattern.compile("(?i)\\b(?:no|not|never|without|don['’]t)\\b");
  private static final Pattern SENTENCE = Pattern.compile("[^.!?;\\r\\n]+");
  private static final Pattern PASSTHROUGH = Pattern.compile(
      "(?i)\\b(?:unchanged (?:request |message )?body(?!\\h+(?:field|property)\\b)|(?:request |message )?body unchanged"
          + "|(?:request |message )?body without (?:modification|transformation|changes))\\b");
  private static final Pattern ADAPTATION = Pattern.compile("(?i)\\b(?:map|rename|enrich|transform)\\b");
  private static final Pattern REQUIRES_SCHEMA = Pattern.compile("(?i)\\b(?:schema|validat\\w*)\\b");

  private RequirementMappingCapture() {}

  static DraftInput pin(DraftInput draft, List<String> authorTurns, List<FactInput> savedFacts) {
    boolean passthrough = passthrough(draft, authorTurns);
    var edge = passthrough ? draft.flow().transitions().getFirst() : null;
    String owner = edge == null ? null : edge.sourceInteractionId();
    String target = edge == null ? null : edge.targetInteractionId();
    boolean kafka = passthrough && draft.capabilities().stream().anyMatch(capability ->
        target.equals(capability.interactionId()) && "kafka-sender-2".equals(capability.capabilityKey()));
    var facts = draft.facts().stream()
        .filter(fact -> !passthrough || savedFacts.contains(fact) || fact.evidence() != null
            || fact.kind() != FactKind.MESSAGE_SCHEMA || !fact.interactionIds().equals(List.of(owner)))
        .map(fact -> savedFacts.contains(fact) ? fact : passthrough && wholeBodyPassthrough(fact, owner, target, kafka)
            ? asBehavior(fact) : pin(draft, fact, authorTurns))
        .toList();
    boolean schemaFree = passthrough && facts.stream().noneMatch(fact ->
        fact.kind() == FactKind.MESSAGE_SCHEMA
            || (fact.fieldMapping() != null && !wholeBodyPassthrough(fact, owner, target, kafka)));
    var questions = schemaFree ? draft.openQuestions().stream()
        .filter(question -> question.evidence() == null || !"requestSchema".equals(question.evidence().propertyKey())
            || !owner.equals(question.evidence().interactionId()))
        .toList() : draft.openQuestions();
    return new DraftInput(draft.flow(), facts, draft.capabilities(), questions, draft.settings());
  }

  static boolean needsInputBodySchema(DraftInput draft, String interactionId) {
    return draft.facts().stream().anyMatch(fact -> fact.polarity() == Polarity.POSITIVE
        && fact.kind() == FactKind.FIELD_MAPPING && fact.fieldMapping() != null
        && interactionId.equals(fact.fieldMapping().value() instanceof MappingValue.Copy copy
            && copy.source() instanceof MappingSource.Message source ? source.interactionId()
                : fact.fieldMapping().sourceInteractionId())
        && !confirmedBodyPassthrough(draft, fact));
  }

  static boolean confirmedBodyPassthrough(DraftInput draft, FactInput fact) {
    FieldMappingInput mapping = fact.fieldMapping();
    if (mapping == null) return false;
    var owner = interaction(draft, mapping.sourceInteractionId());
    if (owner == null) return false;
    if (wholeBodyIdentity(mapping)) {
      return owner.direction() == Direction.INBOUND
          || !(mapping.value() instanceof MappingValue.Copy copy && copy.source() instanceof MappingSource.Message source
              && source.port() == MappingPort.REQUEST);
    }
    if (owner.direction() != Direction.INBOUND || fact.evidence() == null || draft.flow().transitions().size() != 1) return false;
    var edge = draft.flow().transitions().getFirst();
    return draft.capabilities().stream()
        .filter(capability -> edge.targetInteractionId().equals(capability.interactionId()))
        .filter(capability -> "kafka-sender-2".equals(capability.capabilityKey())
            || "http-sender".equals(capability.capabilityKey()))
        .anyMatch(capability -> wholeBodyPassthrough(fact, edge.sourceInteractionId(), edge.targetInteractionId(),
            "kafka-sender-2".equals(capability.capabilityKey())));
  }

  private static boolean wholeBodyPassthrough(FactInput fact, String owner, String target, boolean kafka) {
    var mapping = fact.fieldMapping();
    if (mapping == null || !owner.equals(mapping.sourceInteractionId()) || !target.equals(mapping.targetInteractionId())) {
      return false;
    }
    if (wholeBodyIdentity(mapping)) return true;
    String quote = fact.evidence() == null ? fact.text() : fact.evidence().quote();
    if (quote == null || !hasPositiveMatch(PASSTHROUGH, quote)
        || !MappingContract.canonicalPath(mapping.targetPath()).equals(kafka ? "$.value" : "$.body")) return false;
    if (mapping.value() == null) {
      return (mapping.expression() == null || mapping.expression().isBlank())
          && MappingContract.canonicalPath(mapping.sourcePath()).equals("$.body");
    }
    return mapping.value() instanceof MappingValue.Copy copy && copy.source() instanceof MappingSource.Message source
        && owner.equals(source.interactionId()) && source.port() != MappingPort.RESPONSE
        && (MappingContract.canonicalPath(source.path()).equals("$.body") || "$".equals(source.path()));
  }

  private static FactInput asBehavior(FactInput fact) {
    return new FactInput(fact.sourceFactId(), fact.interactionIds(), FactKind.BEHAVIOR, fact.polarity(), fact.text(),
        null, fact.policyConflict(), fact.elementIntent(), fact.evidence());
  }

  static boolean wholeBodyIdentity(FieldMappingInput mapping) {
    if (mapping == null || !"$".equals(mapping.targetPath()) || mapping.expression() != null) return false;
    if (mapping.value() == null) return "$".equals(mapping.sourcePath());
    return mapping.value() instanceof MappingValue.Copy copy && copy.source() instanceof MappingSource.Message source
        && mapping.sourceInteractionId().equals(source.interactionId()) && "$".equals(source.path());
  }

  private static boolean passthrough(DraftInput draft, List<String> authorTurns) {
    if (draft.flow().transitions().size() != 1) return false;
    var edge = draft.flow().transitions().getFirst();
    var owner = interaction(draft, edge.sourceInteractionId());
    if (owner == null || owner.direction() != Direction.INBOUND) return false;
    String target = edge.targetInteractionId();
    if (draft.capabilities().stream().noneMatch(capability -> target.equals(capability.interactionId())
        && ("kafka-sender-2".equals(capability.capabilityKey()) || "http-sender".equals(capability.capabilityKey())))) {
      return false;
    }
    return authorTurns.stream().anyMatch(text -> hasPositiveMatch(PASSTHROUGH, text))
        && authorTurns.stream().noneMatch(text -> MessageSchemaContract.containsSchema(text)
            || hasPositiveMatch(ADAPTATION, text) || hasPositiveMatch(REQUIRES_SCHEMA, text));
  }

  private static FactInput pin(DraftInput draft, FactInput fact, List<String> authorTurns) {
    FieldMappingInput mapping = fact.fieldMapping();
    if (mapping == null || fact.polarity() != Polarity.POSITIVE
        || !catalogRequest(draft, mapping.targetInteractionId())) return fact;
    if (mapping.value() instanceof MappingValue.Copy copy && copy.source() instanceof MappingSource.Message source) {
      String path = MappingContract.canonicalPath(source.path());
      return path.equals(source.path()) ? fact : withOrigin(fact,
          new MappingSource.Message(source.interactionId(), source.port(), path));
    }
    if (mapping.value() != null || quotedConstant(mapping)) return fact;
    var matches = authorOrigins(draft, mapping, authorTurns);
    if (matches.size() > 1) return fact;
    MappingSource.Message verified = matches.isEmpty() ? null : matches.getFirst();
    if (verified != null && !describesCopy(mapping, verified)) return fact;
    if (verified == null && mapping.expression() == null && mapping.sourcePath() != null
        && FIELD.matcher(mapping.sourcePath()).matches()) {
      var owner = interaction(draft, mapping.sourceInteractionId());
      if (owner != null && owner.direction() == Direction.INBOUND) {
        verified = new MappingSource.Message(owner.interactionId(), MappingPort.OUTPUT,
            MappingContract.canonicalPath(mapping.sourcePath()));
      }
    }
    return verified == null ? fact : withOrigin(fact, verified);
  }

  private static boolean describesCopy(FieldMappingInput mapping, MappingSource.Message origin) {
    if (mapping.sourcePath() != null && !origin.path().equals(MappingContract.canonicalPath(mapping.sourcePath()))) {
      return false;
    }
    if (mapping.expression() == null || mapping.expression().isBlank()) return true;
    String field = Pattern.quote(origin.path().substring(2));
    return mapping.expression().strip().matches("(?i)(?:the\\h+)?"
        + "(?:(?:original(?: inbound| request| inbound request)?|inbound|request)(?: field)?\\h+)?"
        + field + "(?:\\h+(?:saved|retained)\\h+(?:before the [\\w -]+ call"
        + "|from the (?:original inbound request|[\\w -]+ (?:request|response))))?");
  }

  private static boolean quotedConstant(FieldMappingInput mapping) {
    if (mapping.expression() != null && !mapping.expression().isBlank()) return false;
    String source = MappingContract.canonicalPath(mapping.sourcePath());
    return source.length() >= 2 && source.startsWith("\"") && source.endsWith("\"");
  }

  private static FactInput withOrigin(FactInput fact, MappingSource.Message origin) {
    var mapping = fact.fieldMapping();
    var pinned = new FieldMappingInput(mapping.sourceInteractionId(), null, mapping.targetInteractionId(),
        MappingContract.canonicalPath(mapping.targetPath()), null, new MappingValue.Copy(origin), "ERROR");
    return new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(),
        pinned, fact.policyConflict(), fact.elementIntent(), fact.evidence());
  }

  private static LinkedHashSet<MappingSource.Message> authorOrigins(
      DraftInput draft, FieldMappingInput mapping, List<String> authorTurns) {
    var matches = new LinkedHashSet<MappingSource.Message>();
    String targetField = MappingContract.canonicalPath(mapping.targetPath());
    for (String text : authorTurns) {
      var clauses = MAP.matcher(text);
      while (clauses.find()) {
        if (negated(text, clauses)) continue;
        var target = clauses.group(2).strip().split("\\h+");
        if (!targetField.equals(MappingContract.canonicalPath(target[target.length - 1]))) continue;
        String label = String.join(" ", Arrays.copyOf(target, target.length - 1))
            .replaceFirst("(?i)\\h*request(?:\\h+field)?$", "").strip();
        if (!matchesTarget(draft, label, mapping.targetInteractionId())) continue;
        String from = clauses.group(1).strip();
        int lastSpace = from.lastIndexOf(' ');
        if (lastSpace < 0) continue;
        String field = from.substring(lastSpace + 1);
        if (!FIELD.matcher(field).matches()) continue;
        String source = from.substring(0, lastSpace).strip().toLowerCase(Locale.ROOT);
        List<InteractionInput> owners;
        MappingPort port;
        if (source.matches("(?:original(?: inbound| request| inbound request)?|inbound|request)(?: field)?")) {
          owners = draft.flow().interactions().stream().filter(row -> row.direction() == Direction.INBOUND).toList();
          port = MappingPort.OUTPUT;
        } else if (source.matches(".+ response(?: field)?")) {
          owners = labeledCalls(draft, source.replaceFirst(" response(?: field)?$", ""));
          port = MappingPort.RESPONSE;
        } else if (source.endsWith(" request")) {
          owners = labeledCalls(draft, source.substring(0, source.length() - " request".length()));
          port = MappingPort.REQUEST;
        } else {
          var explicitResponse = Pattern.compile("(?i)\\b" + Pattern.quote(source) + "\\h+response\\h+"
              + Pattern.quote(field) + "\\b");
          if (authorTurns.stream().noneMatch(turn -> hasPositiveMatch(explicitResponse, turn))) continue;
          owners = labeledCalls(draft, source);
          port = MappingPort.RESPONSE;
        }
        if (owners.size() == 1) matches.add(new MappingSource.Message(owners.getFirst().interactionId(), port,
            MappingContract.canonicalPath(field)));
      }
    }
    return matches;
  }

  private static boolean hasPositiveMatch(Pattern pattern, String text) {
    var matches = pattern.matcher(text);
    while (matches.find()) {
      if (!negated(text, matches)) return true;
    }
    return false;
  }

  private static boolean negated(String text, Matcher match) {
    var sentences = SENTENCE.matcher(text);
    while (sentences.find()) {
      if (sentences.start() <= match.start() && match.start() < sentences.end()) {
        return NEGATION.matcher(text.substring(sentences.start(), match.start())).find();
      }
    }
    return false;
  }

  private static boolean matchesTarget(DraftInput draft, String label, String target) {
    var calls = label.isBlank() ? draft.flow().interactions().stream()
        .filter(row -> catalogRequest(draft, row.interactionId())).toList() : labeledCalls(draft, label);
    return calls.size() == 1 && calls.getFirst().interactionId().equals(target);
  }

  private static List<InteractionInput> labeledCalls(DraftInput draft, String label) {
    String normalized = label.toLowerCase(Locale.ROOT);
    return draft.flow().interactions().stream().filter(row -> row.direction() == Direction.OUTBOUND)
        .filter(row -> normalized.equals(row.interactionId().toLowerCase(Locale.ROOT))
            || (" " + Objects.toString(row.participant(), "").toLowerCase(Locale.ROOT) + " ")
                .contains(" " + normalized + " "))
        .toList();
  }

  static List<Issue> validate(DraftInput draft, List<FactInput> savedFacts) {
    var copies = draft.facts().stream()
        .filter(fact -> fact.polarity() == Polarity.POSITIVE && fact.fieldMapping() != null && !savedFacts.contains(fact))
        .filter(fact -> catalogRequest(draft, fact.fieldMapping().targetInteractionId()))
        .filter(fact -> !quotedConstant(fact.fieldMapping()) && !confirmedBodyPassthrough(draft, fact)).toList();
    if (copies.isEmpty()) return List.of();
    Map<String, Integer> order;
    try {
      order = BriefCopyMappingValidator.linearOrder(RequirementCaptureProjection.flow(draft));
    } catch (IllegalArgumentException invalid) {
      return List.of(new Issue("MAPPING_FLOW_UNSUPPORTED", "/draft/flow", null, invalid.getMessage()));
    }
    return copies.stream()
        .filter(fact -> !validOrigin(draft, fact.fieldMapping(), order))
        .map(fact -> new Issue("MAPPING_ORIGIN_REQUIRED", "/draft/facts/" + fact.sourceFactId() + "/fieldMapping/value/source",
            fact.sourceFactId(), "Capture the original interaction, message port, and field path as a typed COPY. "
                + "Keep execution placement separate. Use OUTPUT for an incoming request and RESPONSE for a service reply. "
                + "Retain the existing target and author requirement: " + fact.text()))
        .toList();
  }

  private static boolean validOrigin(DraftInput draft, FieldMappingInput mapping, Map<String, Integer> order) {
    if (!(mapping.value() instanceof MappingValue.Copy copy) || !(copy.source() instanceof MappingSource.Message origin)
        || origin.port() == null || origin.path() == null || !FIELD.matcher(origin.path()).matches()) return false;
    var owner = interaction(draft, origin.interactionId());
    if (owner == null || (owner.direction() == Direction.INBOUND) != (origin.port() == MappingPort.OUTPUT)) return false;
    return order.get(origin.interactionId()) <= order.get(mapping.sourceInteractionId());
  }

  private static InteractionInput interaction(DraftInput draft, String id) {
    return draft.flow().interactions().stream().filter(row -> row.interactionId().equals(id)).findFirst().orElse(null);
  }

  static boolean catalogRequest(DraftInput draft, String id) {
    var target = interaction(draft, id);
    return target != null && target.direction() == Direction.OUTBOUND
        && !RequirementFlowValidator.isDirectHttpTarget(target.participant())
        && draft.capabilities().stream().filter(capability -> id.equals(capability.interactionId()))
            .allMatch(capability -> "http-sender".equals(capability.capabilityKey())
                && !RequirementFlowValidator.isDirectHttpTarget(capability.path()));
  }
}
