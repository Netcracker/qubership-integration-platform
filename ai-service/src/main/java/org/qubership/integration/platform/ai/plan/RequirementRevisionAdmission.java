package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;

/** Admits source-scoped changes while retaining validated content and author answers. */
final class RequirementRevisionAdmission {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RequirementRevisionAdmission() {}

  static List<Issue> validate(DraftInput base, DraftInput candidate, List<FactInput> protectedFacts,
      Set<String> authorPaths, Map<String, Set<String>> mappingPaths, Predicate<FactInput> canAnswer) {
    return validate(base, candidate, protectedFacts, MAPPER.createObjectNode(), authorPaths, Set.of(), mappingPaths, canAnswer);
  }

  static List<Issue> validate(DraftInput base, DraftInput candidate, List<FactInput> protectedFacts,
      JsonNode protectedValues, Set<String> authorPaths, Set<String> repairPaths, Map<String, Set<String>> mappingPaths,
      Predicate<FactInput> canAnswer) {
    var issues = new ArrayList<>(base == null ? RequirementCaptureEditor.initialize(candidate).issues()
        : RequirementCaptureEditor.validate(candidate));
    if (!issues.isEmpty()) {
      return List.copyOf(issues);
    }
    var allowed = new java.util.LinkedHashSet<>(authorPaths);
    if (base != null) {
      if (base.settings().idsRequested() != null && candidate.openQuestions().stream()
          .anyMatch(question -> PipelineGates.IDS_PATH_CHOICE
              .equals(question.questionId()))) {
        issues.add(new Issue("CONFIRMED_ANSWER_CHANGED", "/draft/openQuestions", null,
            "Retain the confirmed IDS choice and do not reopen its question."));
      }
      repairPaths.stream().filter(path -> !path.contains("/policyConflict") && validPath(base, path))
          .forEach(allowed::add);
      candidate.facts().stream().filter(fact -> fact.policyConflict() != null
          && fact.policyConflict().decision() != null && canAnswer.test(fact))
          .forEach(fact -> allowed.add("/facts/" + fact.sourceFactId() + "/policyConflict"));
      var expected = amended(base, candidate, allowed);
      issues.addAll(RequirementCaptureEditor.validatePolicyResponses(base, candidate, canAnswer).stream()
          .filter(issue -> !supersedesAnswer(base, candidate, authorPaths, issue.entityId())).toList());
      for (var old : base.facts()) {
        var policy = old.policyConflict();
        if (policy == null || policy.decision() == null) {
          continue;
        }
        var replacement = candidate.facts().stream()
            .filter(fact -> old.sourceFactId().equals(fact.sourceFactId())).findFirst().orElse(null);
        boolean amended = authorPaths.stream().anyMatch(path -> path.equals("/facts/" + old.sourceFactId())
            || path.equals("/facts/" + old.sourceFactId() + "/policyConflict")
            || path.equals("/facts/" + old.sourceFactId() + "/policyConflict/decision"));
        if (!amended && (replacement == null || !Objects.equals(policy, replacement.policyConflict())
            || candidate.openQuestions().stream().anyMatch(question -> policy.questionId().equals(question.questionId())))) {
          issues.add(new Issue("CONFIRMED_ANSWER_CHANGED", "/draft/facts/" + old.sourceFactId() + "/policyConflict",
              old.sourceFactId(), "Retain the confirmed answer and do not reopen its question."
                  + " Only an explicit source-linked author amendment can supersede it."));
        }
      }
      issues.addAll(RequirementCaptureEditor.validateFactPreservation(expected, candidate, mappingPaths));
      preserveValues(tree(expected).get("capabilities"), tree(candidate).get("capabilities"),
          "/capabilities", issues);
      preserveValues(tree(expected).get("settings"), tree(candidate).get("settings"), "/settings", issues);
      var partial = base.flow().interactions().stream().filter(interaction -> interaction.direction() == null
          || interaction.participant() == null || interaction.operation() == null)
          .map(InteractionInput::interactionId).collect(java.util.stream.Collectors.toSet());
      var interactions = tree(expected).withObject("/interactions");
      partial.stream().filter(id -> candidate.flow().interactions().stream()
          .noneMatch(interaction -> id.equals(interaction.interactionId()))).forEach(interactions::remove);
      preserveValues(interactions, tree(candidate).get("interactions"), "/interactions", issues);
      preserveTransitions(tree(new DraftInput(expected.flow(), expected.facts(), expected.capabilities(),
          expected.openQuestions(), expected.settings())).get("transitions"), tree(candidate).get("transitions"),
          partial, candidate, issues);
    }
    var protection = new DraftInput(candidate.flow(), protectedFacts, candidate.capabilities(),
        candidate.openQuestions(), candidate.settings());
    issues.addAll(RequirementCaptureEditor.validateFactPreservation(amended(protection, candidate, allowed).facts(),
        candidate, false, mappingPaths));
    var retained = protectedValues == null || !protectedValues.isObject() ? MAPPER.createObjectNode() : protectedValues.deepCopy();
    replacePaths((ObjectNode) retained, tree(candidate), allowed);
    preserveValues(retained.path("capabilities"), tree(candidate).get("capabilities"), "/capabilities", issues);
    preserveValues(retained.path("settings"), tree(candidate).get("settings"), "/settings", issues);
    var removable = new java.util.LinkedHashSet<String>();
    var retainedInteractions = retained.path("interactions");
    if (retainedInteractions.isObject()) {
      var ids = candidate.flow().interactions().stream().map(InteractionInput::interactionId).collect(java.util.stream.Collectors.toSet());
      retainedInteractions.fields().forEachRemaining(field -> {
        if (!ids.contains(field.getKey()) && (field.getValue().path("direction").isNull()
            || field.getValue().path("participant").isNull() || field.getValue().path("operation").isNull())) {
          removable.add(field.getKey());
        }
      });
      removable.forEach(id -> ((ObjectNode) retainedInteractions).remove(id));
    }
    preserveValues(retainedInteractions, tree(candidate).get("interactions"), "/interactions", issues);
    preserveTransitions(retained.path("transitions"), tree(candidate).get("transitions"), removable, candidate, issues);
    return List.copyOf(issues);
  }

  private static boolean supersedesAnswer(DraftInput base, DraftInput candidate, Set<String> paths, String factId) {
    var original = base.facts().stream().filter(fact -> Objects.equals(factId, fact.sourceFactId()))
        .findFirst().orElse(null);
    var replacement = candidate.facts().stream().filter(fact -> Objects.equals(factId, fact.sourceFactId()))
        .findFirst().orElse(null);
    return original != null && replacement != null && original.policyConflict() != null
        && original.policyConflict().decision() != null && replacement.policyConflict() != null
        && Objects.equals(original.policyConflict().unresolved(), replacement.policyConflict().unresolved())
        && paths.contains("/facts/" + factId + "/policyConflict/decision");
  }

  static String canonicalPath(String path) {
    return path != null && (path.startsWith("/flow/interactions/") || path.startsWith("/flow/transitions/"))
        ? path.substring("/flow".length()) : path;
  }

  static boolean validPath(DraftInput base, String path) {
    if (base == null || path == null || !path.startsWith("/") || path.contains("~")) {
      return false;
    }
    try {
      var pointer = JsonPointer.compile(path);
      return Set.of("facts", "capabilities", "settings", "interactions", "transitions").contains(pointer.getMatchingProperty())
          && !pointer.tail().matches()
          && !path.endsWith("/sourceFactId") && !path.endsWith("/interactionId")
          && tree(base).at(pointer.head()).isObject()
          && !tree(base).at(pointer).isMissingNode();
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static DraftInput amended(DraftInput base, DraftInput candidate, Set<String> paths) {
    var expected = tree(base);
    var proposed = tree(candidate);
    replacePaths(expected, proposed, paths);
    return new DraftInput(new FlowInput(values(expected.get("interactions"), InteractionInput.class),
        transitions(expected.get("transitions"))), values(expected.get("facts"), FactInput.class),
        values(expected.get("capabilities"), CapabilityInput.class), base.openQuestions(),
        MAPPER.convertValue(expected.get("settings"), DraftSettings.class));
  }

  private static void replacePaths(ObjectNode expected, ObjectNode proposed, Set<String> paths) {
    for (var path : paths) {
      var pointer = JsonPointer.compile(path);
      if (expected.at(pointer.head()) instanceof ObjectNode parent) {
        var value = proposed.at(pointer);
        if (value.isMissingNode()) {
          parent.remove(pointer.last().getMatchingProperty());
        } else {
          parent.set(pointer.last().getMatchingProperty(), value);
        }
      }
    }
  }

  private static ObjectNode tree(DraftInput input) {
    var tree = MAPPER.createObjectNode();
    var facts = tree.putObject("facts");
    if (input.facts() != null) input.facts().stream().filter(Objects::nonNull).forEach(fact -> facts.set(fact.sourceFactId(), MAPPER.valueToTree(fact)));
    var capabilities = tree.putObject("capabilities");
    if (input.capabilities() != null) input.capabilities().stream().filter(capability -> capability != null && capability.interactionId() != null).forEach(capability -> capabilities.set(capability.interactionId(), MAPPER.valueToTree(capability)));
    tree.set("settings", MAPPER.valueToTree(input.settings()));
    var interactions = tree.putObject("interactions");
    if (input.flow() != null && input.flow().interactions() != null) input.flow().interactions().stream()
        .filter(interaction -> interaction != null && interaction.interactionId() != null)
        .forEach(interaction -> interactions.set(interaction.interactionId(), MAPPER.valueToTree(interaction)));
    var transitions = tree.putObject("transitions");
    if (input.flow() != null && input.flow().transitions() != null) input.flow().transitions().stream()
        .filter(edge -> edge != null && edge.sourceInteractionId() != null && edge.targetInteractionId() != null)
        .forEach(edge -> transitions.withArray(edge.sourceInteractionId()).add(edge.targetInteractionId()));
    return tree;
  }

  private static <T> List<T> values(JsonNode object, Class<T> type) {
    var values = new ArrayList<T>();
    object.elements().forEachRemaining(value -> values.add(MAPPER.convertValue(value, type)));
    return List.copyOf(values);
  }

  private static void preserveValues(JsonNode before, JsonNode after, String path, List<Issue> issues) {
    if (before.isNull() || before.isMissingNode()) {
      return;
    }
    if (before.isObject() && after.isObject()) {
      before.fields().forEachRemaining(field -> preserveValues(field.getValue(), after.path(field.getKey()),
          path + "/" + field.getKey(), issues));
    } else if (!before.equals(after)) {
      issues.add(new Issue("AUTHOR_AMENDMENT_REQUIRED", path, null,
          "Preserve this validated value: " + before
              + ". Restore it during automatic repair; only an amendment citing the exact author source and quote may replace it."));
    }
  }

  static List<FactInput> accumulate(List<FactInput> protectedFacts, List<FactInput> candidate) {
    var accumulated = new ArrayList<>(protectedFacts);
    for (var fact : candidate) {
      int index = -1;
      for (int i = 0; i < accumulated.size(); i++) {
        var old = accumulated.get(i);
        if (Objects.equals(old.sourceFactId(), fact.sourceFactId())
            || !RequirementCaptureEditor.validFactId(old.sourceFactId()) && Objects.equals(old.text(), fact.text())) {
          index = i;
          break;
        }
      }
      if (index < 0) {
        accumulated.add(fact);
      } else {
        var old = accumulated.get(index);
        if ((old.text() == null || Objects.equals(old.text(), fact.text()))
            && (old.kind() == null || old.kind() == fact.kind()) && (old.polarity() == null || old.polarity() == fact.polarity())) {
          var originalIntent = old.elementIntent();
          var newIntent = fact.elementIntent();
          var intent = originalIntent == null ? newIntent : originalIntent;
          if (originalIntent != null && newIntent != null && Objects.equals(originalIntent.elementRef(), newIntent.elementRef())) {
            var properties = new LinkedHashMap<String, org.qubership.integration.platform.ai.plan.model.PlanProperty>();
            originalIntent.requestedProperties().forEach(property -> properties.put(property.key(), property));
            newIntent.requestedProperties().forEach(property -> properties.putIfAbsent(property.key(), property));
            var values = new java.util.LinkedHashSet<>(originalIntent.exactValues());
            values.addAll(newIntent.exactValues());
            intent = new RequirementElementIntent(originalIntent.elementRef(), originalIntent.elementType(),
                originalIntent.placement(), first(originalIntent.ownerInteractionId(), newIntent.ownerInteractionId()),
                first(originalIntent.successorElementRef(), newIntent.successorElementRef()),
                first(originalIntent.predecessorElementRef(), newIntent.predecessorElementRef()),
                first(originalIntent.parentElementRef(), newIntent.parentElementRef()),
                first(originalIntent.requestedName(), newIntent.requestedName()), List.copyOf(properties.values()),
                List.copyOf(values));
          }
          var interactions = new java.util.LinkedHashSet<>(old.interactionIds());
          interactions.addAll(fact.interactionIds());
          accumulated.set(index, new FactInput(RequirementCaptureEditor.validFactId(old.sourceFactId())
              ? old.sourceFactId() : fact.sourceFactId(), List.copyOf(interactions), old.kind() == null ? fact.kind() : old.kind(),
              old.polarity() == null ? fact.polarity() : old.polarity(), first(old.text(), fact.text()), mergeMapping(old.fieldMapping(), fact.fieldMapping()),
              old.policyConflict() == null ? fact.policyConflict() : old.policyConflict(), intent));
        }
      }
    }
    return List.copyOf(accumulated);
  }

  static ObjectNode valuesToPreserve(DraftInput input, List<Issue> issues) {
    var values = tree(input);
    values.remove("facts");
    var raw = MAPPER.<JsonNode>valueToTree(input);
    for (var issue : issues) {
      var path = issue.path();
      if (path.startsWith("/draft/")) path = path.substring(6);
      if (path.startsWith("/changes/")) path = path.substring(8);
      if (path.startsWith("/flow/")) path = path.substring(5);
      if (path.startsWith("/interactions/") || path.startsWith("/capabilities/")) {
        var pointer = JsonPointer.compile(path);
        var family = pointer.getMatchingProperty();
        var key = pointer.tail().getMatchingProperty();
        var array = family.equals("interactions") ? raw.path("flow").path(family) : raw.path(family);
        if (pointer.tail().getMatchingIndex() >= 0 && array.path(pointer.tail().getMatchingIndex()).isObject()) {
          key = array.path(pointer.tail().getMatchingIndex()).path("interactionId").asText();
        }
        path = "/" + family + "/" + key + pointer.tail().tail();
        if (path.endsWith("/interactionId")) path = "/" + family + "/" + key;
      }
      if (path.startsWith("/transitions")) {
        var pointer = JsonPointer.compile(path);
        var edge = input.flow() == null || input.flow().transitions() == null ? null
            : pointer.tail().getMatchingIndex() >= 0 && pointer.tail().getMatchingIndex() < input.flow().transitions().size()
                ? input.flow().transitions().get(pointer.tail().getMatchingIndex()) : null;
        if (edge == null) {
          if (pointer.tail().matches()) values.remove("transitions");
          else if (values.get("transitions") instanceof ObjectNode outgoing) outgoing.remove(pointer.tail().getMatchingProperty());
        } else if (values.path("transitions").path(edge.sourceInteractionId()).isArray()) {
          var remaining = MAPPER.createArrayNode();
          boolean removed = false;
          for (var target : values.path("transitions").path(edge.sourceInteractionId())) {
            if (!removed && target.asText().equals(edge.targetInteractionId())) removed = true;
            else remaining.add(target);
          }
          ((ObjectNode) values.get("transitions")).set(edge.sourceInteractionId(), remaining);
        }
      } else {
        var pointer = JsonPointer.compile(path);
        if (values.at(pointer.head()) instanceof ObjectNode parent) parent.putNull(pointer.last().getMatchingProperty());
      }
    }
    return values;
  }

  static ObjectNode accumulateValues(JsonNode original, ObjectNode candidate) {
    var accumulated = original == null || !original.isObject() ? MAPPER.createObjectNode() : (ObjectNode) original.deepCopy();
    candidate.fields().forEachRemaining(field -> {
      var old = accumulated.path(field.getKey());
      var value = field.getValue();
      if (old.isNull() || old.isMissingNode()) accumulated.set(field.getKey(), value);
      else if (old.isObject() && value.isObject()) accumulated.set(field.getKey(), accumulateValues(old, (ObjectNode) value));
      else if (old.isArray() && value.isArray()) {
        var joined = ((com.fasterxml.jackson.databind.node.ArrayNode) old).deepCopy();
        value.forEach(item -> { if (!contains(joined, item)) joined.add(item); });
        accumulated.set(field.getKey(), joined);
      }
    });
    return accumulated;
  }

  private static boolean contains(JsonNode array, JsonNode value) {
    for (var item : array) if (item.equals(value)) return true;
    return false;
  }

  private static List<TransitionInput> transitions(JsonNode values) {
    var transitions = new ArrayList<TransitionInput>();
    values.fields().forEachRemaining(field -> field.getValue().forEach(target ->
        transitions.add(new TransitionInput(field.getKey(), target.asText()))));
    return transitions;
  }

  private static void preserveTransitions(JsonNode before, JsonNode after, Set<String> removable,
      DraftInput candidate, List<Issue> issues) {
    before.fields().forEachRemaining(field -> field.getValue().forEach(target -> {
      if (!contains(after.path(field.getKey()), target)
          && !(removable.contains(field.getKey()) && candidate.flow().interactions().stream()
              .noneMatch(i -> i.interactionId().equals(field.getKey())))
          && !(removable.contains(target.asText()) && candidate.flow().interactions().stream()
              .noneMatch(i -> i.interactionId().equals(target.asText())))) {
        issues.add(new Issue("AUTHOR_AMENDMENT_REQUIRED", "/transitions/" + field.getKey(), field.getKey(),
            "Preserve this accepted transition or cite a source-linked amendment for its outgoing transitions."));
      }
    }));
  }

  private static FieldMappingInput mergeMapping(FieldMappingInput old, FieldMappingInput candidate) {
    if (old == null) return candidate;
    if (candidate == null || old.sourcePath() != null && old.targetPath() != null) return old;
    return new FieldMappingInput(first(old.sourceInteractionId(), candidate.sourceInteractionId()),
        first(old.sourcePath(), candidate.sourcePath()), first(old.targetInteractionId(), candidate.targetInteractionId()),
        first(old.targetPath(), candidate.targetPath()), first(old.expression(), candidate.expression()));
  }

  private static String first(String original, String candidate) {
    return original == null ? candidate : original;
  }
}
