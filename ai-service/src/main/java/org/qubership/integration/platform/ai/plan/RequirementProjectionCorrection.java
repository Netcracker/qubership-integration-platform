package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class RequirementProjectionCorrection {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final Set<String> INTENT_FIELDS = Set.of("elementRef", "elementType", "placement",
      "ownerInteractionId", "successorElementRef", "predecessorElementRef", "parentElementRef");

  private RequirementProjectionCorrection() {}

  static boolean validReport(RequirementCaptureInput.DraftInput base,
      RequirementAnalysisCapture.SourceOmission report) {
    try {
      String path = report.path();
      if (base == null || path == null || path.contains("~")) return false;
      String[] parts = path.split("/", -1);
      var fact = base.facts().stream().filter(value -> Objects.equals(value.sourceFactId(), report.sourceFactId()))
          .findFirst().orElse(null);
      if (fact == null) return false;
      boolean intent = parts.length == 5 && parts[1].equals("facts") && parts[2].equals(report.sourceFactId())
          && parts[3].equals("elementIntent") && INTENT_FIELDS.contains(parts[4]);
      boolean capability = parts.length == 3 && parts[1].equals("capabilities")
          && (fact.interactionIds().contains(parts[2]) || fact.elementIntent() != null
              && Objects.equals(fact.elementIntent().ownerInteractionId(), parts[2]));
      var before = RequirementRevisionAdmission.tree(base).at(JsonPointer.compile(path));
      if (capability && before.isMissingNode()) before = MAPPER.nullNode();
      var after = MAPPER.readTree(report.proposedValue());
      return (intent || capability) && !before.isMissingNode() && after != null && !before.equals(after)
          && before.equals(MAPPER.readTree(report.previousValue()));
    } catch (Exception malformed) {
      return false;
    }
  }

  static boolean validLocalReports(RequirementCaptureInput.DraftInput base,
      List<RequirementAnalysisCapture.SourceOmission> reports) {
    try {
      if (base == null || reports == null || reports.size() < 3 || reports.size() > 4) return false;
      var byPath = new java.util.LinkedHashMap<String, RequirementAnalysisCapture.SourceOmission>();
      for (var report : reports) {
        if (report == null || report.path() == null || byPath.put(report.path(), report) != null) return false;
      }
      var declaration = reports.stream().filter(report -> report.path().equals(
          "/facts/" + report.sourceFactId() + "/elementIntent")).findFirst().orElse(null);
      if (declaration == null) return false;
      var fact = base.facts().stream().filter(value -> Objects.equals(value.sourceFactId(), declaration.sourceFactId()))
          .findFirst().orElse(null);
      if (fact == null || fact.fieldMapping() == null || fact.elementIntent() != null
          || fact.kind() != RequirementCaptureInput.FactKind.FIELD_MAPPING) return false;
      var intent = MAPPER.readValue(declaration.proposedValue(), RequirementElementIntent.class);
      String target = fact.fieldMapping().targetInteractionId();
      if (intent == null || !Objects.equals(intent.elementRef(), target)
          || !"script".equals(intent.elementType()) || intent.placement() != RequirementElementIntent.Placement.LOCAL_NODE
          || !Objects.equals(intent.predecessorElementRef(), fact.fieldMapping().sourceInteractionId())
          || intent.successorElementRef() != null || intent.parentElementRef() != null || intent.requestedName() != null
          || !intent.requestedProperties().isEmpty() || !intent.exactValues().isEmpty() || intent.bodyBinding() != null)
        return false;
      var response = base.flow().interactions().stream().filter(value -> Objects.equals(target, value.interactionId()))
          .findFirst().orElse(null);
      var owner = base.flow().interactions().stream().filter(value -> Objects.equals(intent.ownerInteractionId(), value.interactionId()))
          .findFirst().orElse(null);
      if (response == null || owner == null || response.direction() != org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.OUTBOUND
          || owner.direction() != org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND
          || !Objects.equals(response.participant(), owner.participant()) || response.failureMode() != null
          || response.retryPolicy() != null || base.capabilities().stream().anyMatch(value -> Objects.equals(target, value.interactionId())))
        return false;
      var edges = base.flow().transitions().stream().filter(edge -> Objects.equals(target, edge.targetInteractionId())
          || Objects.equals(target, edge.sourceInteractionId())).toList();
      if (edges.size() != 1 || !Objects.equals(edges.getFirst().sourceInteractionId(), intent.predecessorElementRef())
          || !Objects.equals(edges.getFirst().targetInteractionId(), target)
          || base.flow().transitions().stream().anyMatch(edge -> Objects.equals(intent.predecessorElementRef(), edge.sourceInteractionId())
              && !Objects.equals(target, edge.targetInteractionId()))) return false;
      if (base.facts().stream().anyMatch(value -> value.interactionIds().contains(target)
          && (value.kind() != RequirementCaptureInput.FactKind.FIELD_MAPPING || value.fieldMapping() == null
              || !Objects.equals(target, value.fieldMapping().targetInteractionId())
              || !Objects.equals(intent.predecessorElementRef(), value.fieldMapping().sourceInteractionId())
              || !(value.fieldMapping().value() instanceof org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue.Copy copy)
              || !(copy.source() instanceof org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource.Message)
              || !"ERROR".equals(value.fieldMapping().missingSourcePolicy())))) return false;
      var interaction = byPath.get("/interactions/" + target);
      var transition = byPath.get("/transitions/" + intent.predecessorElementRef());
      var owners = byPath.get("/facts/" + fact.sourceFactId() + "/interactionIds");
      if (interaction == null || transition == null || !"null".equals(interaction.proposedValue().trim())) return false;
      var expectedOwners = new java.util.ArrayList<>(fact.interactionIds());
      if (!expectedOwners.contains(intent.ownerInteractionId())) {
        expectedOwners.add(intent.ownerInteractionId());
        if (owners == null || !MAPPER.valueToTree(expectedOwners).equals(MAPPER.readTree(owners.proposedValue()))) return false;
      } else if (owners != null) return false;
      var original = RequirementRevisionAdmission.tree(base);
      for (var report : reports) {
        var before = original.at(JsonPointer.compile(report.path()));
        if (before.isMissingNode() || !before.equals(MAPPER.readTree(report.previousValue()))) return false;
      }
      var oldEdges = original.at("/transitions/" + intent.predecessorElementRef());
      var remaining = oldEdges.deepCopy();
      if (!(remaining instanceof com.fasterxml.jackson.databind.node.ArrayNode array)) return false;
      for (int i = array.size() - 1; i >= 0; i--) if (target.equals(array.get(i).asText())) array.remove(i);
      return remaining.equals(MAPPER.readTree(transition.proposedValue()));
    } catch (Exception invalid) {
      return false;
    }
  }

  static RequirementCaptureInput.DraftInput normalizeLocalBodyBinding(RequirementCaptureInput.DraftInput candidate,
      AuthorSourceEvidence.Packet source, List<RequirementAnalysisCapture.SourceOmission> reports) {
    var declaration = reports.stream().filter(report -> report.path().equals(
        "/facts/" + report.sourceFactId() + "/elementIntent")).findFirst().orElseThrow();
    var fact = candidate.facts().stream().filter(value -> declaration.sourceFactId().equals(value.sourceFactId()))
        .findFirst().orElse(null);
    if (fact == null) return candidate;
    var intent = fact.elementIntent();
    if (intent == null) return candidate;
    var owners = candidate.facts().stream().map(RequirementCaptureInput.FactInput::elementIntent)
        .filter(Objects::nonNull).toList();
    var resolved = RequirementBodySources.resolve(intent, owners, source);
    if (resolved.error() != null || resolved.binding() == null
        || resolved.binding().authority() != RequirementBodyBinding.Authority.GENERATED_CANDIDATE
        || !Objects.equals(resolved.binding(), intent.bodyBinding())) return candidate;
    var normalized = new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
        intent.ownerInteractionId(), intent.successorElementRef(), intent.predecessorElementRef(), intent.parentElementRef(),
        intent.requestedName(), intent.requestedProperties(), intent.exactValues(), null);
    var facts = candidate.facts().stream().map(value -> value == fact
        ? new RequirementCaptureInput.FactInput(value.sourceFactId(), value.interactionIds(), value.kind(), value.polarity(),
            value.text(), value.fieldMapping(), value.policyConflict(), normalized, value.evidence()) : value).toList();
    return new RequirementCaptureInput.DraftInput(candidate.flow(), facts, candidate.capabilities(),
        candidate.openQuestions(), candidate.settings());
  }

  static boolean validLocalAuthority(RequirementDraft draft, AuthorSourceEvidence.Packet source,
      List<RequirementAnalysisCapture.SourceOmission> reports) {
    if (draft == null || source == null || !validLocalReports(draft.authoredDraft(), reports)) return false;
    var target = reports.stream().map(RequirementAnalysisCapture.SourceOmission::path)
        .filter(path -> path.startsWith("/interactions/")).findFirst().orElseThrow().substring("/interactions/".length());
    var interaction = draft.authoredDraft().flow().interactions().stream()
        .filter(value -> target.equals(value.interactionId())).findFirst().orElseThrow();
    return draft.catalogBindings().stream().noneMatch(binding -> target.equals(binding.interactionId()))
        && !draft.uploadedSpecBindings().containsKey(target) && !explicitProtocol(interaction.operation());
  }

  private static boolean explicitProtocol(String value) {
    if (value == null || value.isBlank()) return false;
    String operation = value.trim();
    if (operation.matches("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+") || operation.matches("/\\S*")) return true;
    String[] parts = operation.split("\\s+");
    return parts.length == 2 && explicitProtocol(parts[1])
        && java.util.Arrays.stream(RequirementCaptureInput.HttpMethod.values())
            .anyMatch(method -> method.name().equalsIgnoreCase(parts[0]));
  }

  static boolean preservesCapabilities(RequirementCaptureInput.DraftInput base, RequirementDraft candidate,
      AuthorSourceEvidence.Packet source) {
    for (var before : base.capabilities()) {
      var after = candidate.authoredDraft().capabilities().stream()
          .filter(value -> Objects.equals(value.interactionId(), before.interactionId())).findFirst().orElse(null);
      if (after != null) {
        if (!retained(before.httpMethod(), after.httpMethod()) || !retained(before.path(), after.path()) || !retained(before.topic(), after.topic())
            || !retained(before.mcpServerId(), after.mcpServerId())
            || !retained(before.targetReference(), after.targetReference())) return false;
      } else if (before.httpMode() != null || before.httpMethod() != null || nonempty(before.path())
          || nonempty(before.topic()) || nonempty(before.mcpServerId()) || nonempty(before.targetReference())) {
        if (!catalogPreserves(before, candidate, source)) return false;
      }
    }
    return true;
  }

  private static boolean retained(Object before, Object after) {
    return before == null || before instanceof String text && text.isBlank() || Objects.equals(before, after);
  }

  private static boolean nonempty(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean catalogPreserves(RequirementCaptureInput.CapabilityInput before, RequirementDraft candidate,
      AuthorSourceEvidence.Packet source) {
    if (!"http-sender".equals(before.capabilityKey()) || before.httpMode() != null || before.httpMethod() == null
        || !nonempty(before.path()) || nonempty(before.topic()) || nonempty(before.mcpServerId())
        || nonempty(before.targetReference())) return false;
    String path;
    try {
      var uri = java.net.URI.create(before.path());
      path = uri.isAbsolute() ? uri.getRawPath() : before.path();
      if (uri.isAbsolute() && source.inputs().stream().anyMatch(input -> input.text() != null
          && input.text().contains(before.path()))) return false;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
    var owner = before.interactionId();
    boolean anchor = candidate.authoredDraft().facts().stream().map(RequirementCaptureInput.FactInput::elementIntent)
        .filter(Objects::nonNull).anyMatch(intent -> "service-call".equals(intent.elementType())
            && intent.placement() == RequirementElementIntent.Placement.ANCHOR
            && Objects.equals(owner, intent.ownerInteractionId()));
    var uploaded = candidate.uploadedSpecBindings().get(owner);
    boolean uploadedMatch = uploaded != null && before.httpMethod().name().equalsIgnoreCase(uploaded.method())
        && Objects.equals(path, uploaded.path());
    boolean catalogMatch = candidate.catalogBindings().stream().anyMatch(binding -> Objects.equals(owner, binding.interactionId())
        && before.httpMethod().name().equalsIgnoreCase(binding.method()) && Objects.equals(path, binding.path()));
    return anchor && (uploadedMatch || catalogMatch);
  }

  static boolean matches(RequirementCaptureInput.DraftInput base, RequirementCaptureInput.DraftInput candidate,
      List<RequirementAnalysisCapture.SourceOmission> reports) {
    var original = RequirementRevisionAdmission.tree(base);
    var expected = original.deepCopy();
    var proposed = RequirementRevisionAdmission.tree(candidate);
    var paths = new HashSet<String>();
    try {
      boolean local = validLocalReports(base, reports);
      for (var report : reports) {
        String path = report.path();
        if ((!local && !validReport(base, report)) || !paths.add(path)) return false;
        var pointer = JsonPointer.compile(path);
        var before = original.at(pointer);
        var after = proposed.at(pointer);
        if (before.isMissingNode()) before = MAPPER.nullNode();
        boolean removedTransition = local && path.startsWith("/transitions/") && after.isMissingNode();
        if (removedTransition) after = MAPPER.createArrayNode();
        else if (after.isMissingNode()) after = MAPPER.nullNode();
        if (before.equals(after)
            || !before.equals(MAPPER.readTree(report.previousValue()))
            || !after.equals(MAPPER.readTree(report.proposedValue()))
            || !(expected.at(pointer.head()) instanceof ObjectNode parent)) return false;
        if (removedTransition || after.isNull() && (path.startsWith("/capabilities/") || local && path.startsWith("/interactions/")))
          parent.remove(pointer.last().getMatchingProperty());
        else parent.set(pointer.last().getMatchingProperty(), after);
      }
      return expected.equals(proposed) && Objects.equals(base.openQuestions(), candidate.openQuestions());
    } catch (Exception malformed) {
      return false;
    }
  }
}
