package org.qubership.integration.platform.ai.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.RetryInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/** Validates an authored requirement snapshot and applies focused edits without partial writes. */
public final class RequirementCaptureEditor {

  private static final Pattern LOCAL_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
  // ponytail: Catch explicit wording conflicts here; semantic source coverage needs an evaluated model check.
  private static final Pattern EXPLICIT_PROHIBITION = Pattern.compile(
      "(?iu)(?:\\b(?:do not|don't|never|must not|should not)\\b|"
          + "(?<![\\p{L}])(?:\u043d\u0435\u043b\u044c\u0437\u044f\\s+|\u0437\u0430\u043f\u0440\u0435\u0449\u0435\u043d\u043e\\s+|"
          + "\u043d\u0435\\s+(?:\u043b\u043e\u0433\u0438\u0440\u043e\u0432\u0430\u0442\u044c|\u0432\u044b\u0437\u044b\u0432\u0430\u0442\u044c|\u0443\u0434\u0430\u043b\u044f\u0442\u044c|\u0441\u043e\u0445\u0440\u0430\u043d\u044f\u0442\u044c|\u043e\u0442\u043f\u0440\u0430\u0432\u043b\u044f\u0442\u044c)\\b))");
  private static final Pattern PROHIBITION_WORDING = Pattern.compile(
      "(?iu)(?:\\b(?:no|not|never|without|avoid|exclude|disable|forbid|forbidden|"
          + "prohibit|prohibited|don't)\\b|"
          + "(?<![\\p{L}])(?:\u043d\u0435|\u043d\u0435\u043b\u044c\u0437\u044f|\u0431\u0435\u0437|\u0437\u0430\u043f\u0440\u0435\u0449\u0435\u043d\u043e|\u0438\u0441\u043a\u043b\u044e\u0447\u0438\u0442\u044c|\u0438\u0437\u0431\u0435\u0433\u0430\u0442\u044c)(?![\\p{L}]))");

  public record Issue(String code, String path, String entityId, String message) {}

  public record EditResult(DraftInput draft, List<Issue> issues, boolean changed) {
    public boolean accepted() {
      return issues.isEmpty();
    }
  }

  private RequirementCaptureEditor() {}

  public static EditResult initialize(DraftInput draft) {
    List<Issue> issues = validate(draft);
    if (issues.isEmpty()
        && draft.flow().interactions().isEmpty()
        && draft.facts().isEmpty()
        && draft.capabilities().isEmpty()
        && draft.openQuestions().isEmpty()
        && draft.settings().idsRequested() == null
        && draft.settings().preferredSystemType() == null) {
      add(issues, "EMPTY_DRAFT", "/draft", null, "Capture at least one known requirement or question.");
    }
    return new EditResult(draft, sorted(issues), issues.isEmpty());
  }

  public static EditResult apply(DraftInput accepted, DraftUpdate update) {
    List<Issue> issues = new ArrayList<>();
    if (accepted == null) {
      add(issues, "DRAFT_NOT_FOUND", "/changes", null, "Read or initialize the draft first.");
      return new EditResult(null, List.copyOf(issues), false);
    }
    if (update == null) {
      add(issues, "MISSING_FIELD", "/changes", null, "changes is required.");
      return new EditResult(accepted, List.copyOf(issues), false);
    }
    requiredList(issues, update.addInteractions(), "/changes/addInteractions");
    requiredList(issues, update.updateInteractions(), "/changes/updateInteractions");
    requiredList(issues, update.removeInteractionIds(), "/changes/removeInteractionIds");
    requiredList(issues, update.addTransitions(), "/changes/addTransitions");
    requiredList(issues, update.removeTransitions(), "/changes/removeTransitions");
    requiredList(issues, update.addFacts(), "/changes/addFacts");
    requiredList(issues, update.updateFacts(), "/changes/updateFacts");
    requiredList(issues, update.removeFactIds(), "/changes/removeFactIds");
    requiredList(issues, update.setCapabilities(), "/changes/setCapabilities");
    requiredList(issues, update.removeCapabilityInteractionIds(), "/changes/removeCapabilityInteractionIds");
    requiredList(issues, update.addQuestions(), "/changes/addQuestions");
    requiredList(issues, update.updateQuestions(), "/changes/updateQuestions");
    requiredList(issues, update.removeQuestionIds(), "/changes/removeQuestionIds");
    if (!issues.isEmpty()) {
      return new EditResult(accepted, sorted(issues), false);
    }

    List<InteractionInput> interactions =
        editEntities(
            accepted.flow().interactions(),
            update.addInteractions(),
            update.updateInteractions(),
            update.removeInteractionIds(),
            InteractionInput::interactionId,
            "interactions",
            issues);
    List<FactInput> facts =
        editEntities(
            accepted.facts(),
            update.addFacts(),
            update.updateFacts(),
            update.removeFactIds(),
            FactInput::sourceFactId,
            "facts",
            issues);
    List<QuestionInput> questions =
        editEntities(
            accepted.openQuestions(),
            update.addQuestions(),
            update.updateQuestions(),
            update.removeQuestionIds(),
            QuestionInput::questionId,
            "questions",
            issues);
    List<CapabilityInput> capabilities =
        editEntities(
            accepted.capabilities(),
            List.of(),
            update.setCapabilities(),
            update.removeCapabilityInteractionIds(),
            CapabilityInput::interactionId,
            "capabilities",
            issues,
            true);

    Map<TransitionInput, TransitionInput> transitions = new LinkedHashMap<>();
    for (TransitionInput transition : accepted.flow().transitions()) {
      transitions.put(transition, transition);
    }
    Set<TransitionInput> removed = new HashSet<>();
    for (int i = 0; i < update.removeTransitions().size(); i++) {
      TransitionInput value = update.removeTransitions().get(i);
      if (value == null || !removed.add(value) || transitions.remove(value) == null) {
        add(issues, "UNKNOWN_ENTITY", "/changes/removeTransitions/" + i, null,
            "Remove an existing transition once.");
      }
    }
    for (int i = 0; i < update.addTransitions().size(); i++) {
      TransitionInput value = update.addTransitions().get(i);
      if (value == null || removed.contains(value) || transitions.putIfAbsent(value, value) != null) {
        add(issues, "CONFLICTING_EDIT", "/changes/addTransitions/" + i, null,
            "Add a new transition once without removing it in the same edit.");
      }
    }

    DraftInput candidate =
        new DraftInput(
            new FlowInput(interactions, List.copyOf(transitions.values())),
            facts,
            capabilities,
            questions,
            update.settings() == null ? accepted.settings() : update.settings());
    issues.addAll(validate(candidate));
    return new EditResult(
        issues.isEmpty() ? candidate : accepted,
        sorted(issues),
        issues.isEmpty() && !candidate.equals(accepted));
  }

  private static <T> List<T> editEntities(
      List<T> original,
      List<T> additions,
      List<T> replacements,
      List<String> removals,
      Function<T, String> identity,
      String family,
      List<Issue> issues) {
    return editEntities(original, additions, replacements, removals, identity, family, issues, false);
  }

  private static <T> List<T> editEntities(
      List<T> original,
      List<T> additions,
      List<T> replacements,
      List<String> removals,
      Function<T, String> identity,
      String family,
      List<Issue> issues,
      boolean upsert) {
    Map<String, T> values = new LinkedHashMap<>();
    for (T value : original) {
      values.put(identity.apply(value), value);
    }
    Set<String> touched = new HashSet<>();
    for (int i = 0; i < removals.size(); i++) {
      String id = removals.get(i);
      if (id == null || !touched.add(id) || values.remove(id) == null) {
        add(issues, "UNKNOWN_ENTITY", "/changes/" + operationField(family, "remove") + "/" + i, id,
            "Remove an existing " + family + " entry once.");
      }
    }
    for (int i = 0; i < additions.size(); i++) {
      T value = additions.get(i);
      String id = value == null ? null : identity.apply(value);
      if (id == null || !touched.add(id) || values.putIfAbsent(id, value) != null) {
        add(issues, "DUPLICATE_ENTITY", "/changes/" + operationField(family, "add") + "/" + i, id,
            "Add a new " + family + " identity once.");
      }
    }
    for (int i = 0; i < replacements.size(); i++) {
      T value = replacements.get(i);
      String id = value == null ? null : identity.apply(value);
      if (id == null || !touched.add(id) || (!upsert && !values.containsKey(id))) {
        add(issues, "CONFLICTING_EDIT", "/changes/" + operationField(family, "update") + "/" + i, id,
            "Update an existing " + family + " identity once.");
      } else {
        values.put(id, value);
      }
    }
    return List.copyOf(values.values());
  }

  private static String operationField(String family, String operation) {
    return switch (family) {
      case "interactions" -> switch (operation) {
        case "add" -> "addInteractions";
        case "update" -> "updateInteractions";
        default -> "removeInteractionIds";
      };
      case "facts" -> switch (operation) {
        case "add" -> "addFacts";
        case "update" -> "updateFacts";
        default -> "removeFactIds";
      };
      case "questions" -> switch (operation) {
        case "add" -> "addQuestions";
        case "update" -> "updateQuestions";
        default -> "removeQuestionIds";
      };
      case "capabilities" -> operation.equals("remove")
          ? "removeCapabilityInteractionIds" : "setCapabilities";
      default -> throw new IllegalArgumentException("Unknown requirement family: " + family);
    };
  }

  public static List<Issue> validate(DraftInput draft) {
    List<Issue> issues = new ArrayList<>();
    if (draft == null) {
      add(issues, "MISSING_FIELD", "/draft", null, "draft is required.");
      return List.copyOf(issues);
    }
    if (draft.flow() == null || draft.settings() == null) {
      if (draft.flow() == null) {
        add(issues, "MISSING_FIELD", "/draft/flow", null, "flow is required.");
      }
      if (draft.settings() == null) {
        add(issues, "MISSING_FIELD", "/draft/settings", null, "settings is required.");
      }
    }
    requiredList(issues, draft.facts(), "/draft/facts");
    requiredList(issues, draft.capabilities(), "/draft/capabilities");
    requiredList(issues, draft.openQuestions(), "/draft/openQuestions");
    if (draft.flow() != null) {
      requiredList(issues, draft.flow().interactions(), "/draft/flow/interactions");
      requiredList(issues, draft.flow().transitions(), "/draft/flow/transitions");
    }
    if (!issues.isEmpty()) {
      return sorted(issues);
    }

    Set<String> ids = new LinkedHashSet<>();
    Map<String, InteractionInput> byId = new LinkedHashMap<>();
    for (int i = 0; i < draft.flow().interactions().size(); i++) {
      InteractionInput interaction = draft.flow().interactions().get(i);
      String path = "/draft/flow/interactions/" + i;
      if (interaction == null) {
        add(issues, "INVALID_TYPE", path, null, "Interaction must be an object.");
        continue;
      }
      String id = interaction.interactionId();
      checkId(issues, id, path + "/interactionId");
      if (id != null && !ids.add(id)) {
        add(issues, "DUPLICATE_ENTITY", path + "/interactionId", id,
            "Interaction identity is repeated.");
      }
      if (id != null) {
        byId.put(id, interaction);
      }
      if (interaction.direction() == Direction.INBOUND
          && (interaction.failureMode() != null || interaction.retryPolicy() != null)) {
        add(issues, "INVALID_VALUE", path, id,
            "Inbound interactions cannot configure call failure or retry behavior.");
      }
      RetryInput retry = interaction.retryPolicy();
      if (retry != null) {
        Integer count = retry.retryCount();
        Integer delay = retry.retryDelayMs();
        if (count != null && (count < 0 || count > 5)) {
          add(issues, "INVALID_VALUE", path + "/retryPolicy/retryCount", id,
              "retryCount must be between 0 and 5.");
        }
        if (delay != null && delay < 5000) {
          add(issues, "INVALID_VALUE", path + "/retryPolicy/retryDelayMs", id,
              "retryDelayMs must be at least 5000.");
        }
        if (Integer.valueOf(0).equals(count) && delay != null) {
          add(issues, "INVALID_VALUE", path + "/retryPolicy/retryDelayMs", id,
              "Omit retryDelayMs when retryCount is zero.");
        }
      }
    }

    Set<TransitionInput> edges = new HashSet<>();
    Map<String, List<String>> successors = new HashMap<>();
    for (int i = 0; i < draft.flow().transitions().size(); i++) {
      TransitionInput edge = draft.flow().transitions().get(i);
      String path = "/draft/flow/transitions/" + i;
      if (edge == null) {
        add(issues, "INVALID_TYPE", path, null, "Transition must be an object.");
        continue;
      }
      if (!byId.containsKey(edge.sourceInteractionId())
          || !byId.containsKey(edge.targetInteractionId())) {
        add(issues, "UNKNOWN_INTERACTION_REFERENCE", path, null,
            "Transition endpoints must name accepted interactions.");
        continue;
      }
      if (Objects.equals(edge.sourceInteractionId(), edge.targetInteractionId())) {
        add(issues, "SELF_TRANSITION", path, edge.sourceInteractionId(),
            "Transition cannot point to itself.");
      }
      if (!edges.add(edge)) {
        add(issues, "DUPLICATE_TRANSITION", path, null, "Transition is repeated.");
      }
      if (byId.get(edge.targetInteractionId()).direction() == Direction.INBOUND) {
        add(issues, "INBOUND_HAS_PREDECESSOR", path, edge.targetInteractionId(),
            "Inbound interaction cannot have a predecessor.");
      }
      successors.computeIfAbsent(edge.sourceInteractionId(), ignored -> new ArrayList<>())
          .add(edge.targetInteractionId());
    }
    if (hasCycle(successors, ids)) {
      add(issues, "FLOW_CYCLE", "/draft/flow/transitions", null,
          "Transitions must not form a cycle.");
    }

    Set<String> factIds = new HashSet<>();
    for (int i = 0; i < draft.facts().size(); i++) {
      FactInput fact = draft.facts().get(i);
      String path = "/draft/facts/" + i;
      if (fact == null) {
        add(issues, "INVALID_TYPE", path, null, "Fact must be an object.");
        continue;
      }
      checkId(issues, fact.sourceFactId(), path + "/sourceFactId");
      if (!factIds.add(fact.sourceFactId())) {
        add(issues, "DUPLICATE_ENTITY", path + "/sourceFactId", fact.sourceFactId(),
            "Fact identity is repeated.");
      }
      if (fact.kind() == null || fact.polarity() == null || blank(fact.text())) {
        add(issues, "MISSING_FIELD", path, fact.sourceFactId(),
            "Fact needs kind, polarity, and nonblank text.");
      } else if (fact.polarity() == RequirementCaptureInput.Polarity.POSITIVE
          && EXPLICIT_PROHIBITION.matcher(fact.text()).find()) {
        add(issues, "REQUIREMENT_COVERAGE_GAP", path + "/polarity", fact.sourceFactId(),
            "An explicit prohibition needs NEGATIVE polarity. Split mixed requirements into separate facts.");
      } else if (fact.polarity() == RequirementCaptureInput.Polarity.NEGATIVE
          && !PROHIBITION_WORDING.matcher(fact.text()).find()) {
        add(issues, "REQUIREMENT_COVERAGE_GAP", path + "/text", fact.sourceFactId(),
            "State a negative fact as a clear prohibition, such as 'Do not log the input body.'");
      }
      checkTargets(issues, fact.interactionIds(), ids, path + "/interactionIds", fact.sourceFactId());
      if (fact.kind() == RequirementCaptureInput.FactKind.FIELD_MAPPING) {
        var mapping = fact.fieldMapping();
        if (mapping == null) {
          add(issues, "MAPPING_FIELDS_REQUIRED", path + "/fieldMapping", fact.sourceFactId(),
              "Specify the approved source, target, and field paths.");
          continue;
        }
        if (blank(mapping.sourceInteractionId()) || blank(mapping.targetInteractionId())
            || !ids.contains(mapping.sourceInteractionId())
            || !ids.contains(mapping.targetInteractionId())
            || !draft.flow().transitions().contains(new TransitionInput(
                mapping.sourceInteractionId(), mapping.targetInteractionId()))) {
          add(issues, "MAPPING_TRANSITION_REQUIRED", path + "/fieldMapping",
              fact.sourceFactId(), "Use one approved flow transition for this field mapping.");
        }
        if (!fact.interactionIds().contains(mapping.sourceInteractionId())
            || !fact.interactionIds().contains(mapping.targetInteractionId())) {
          add(issues, "MAPPING_TARGETS_REQUIRED", path + "/interactionIds",
              fact.sourceFactId(), "Target the source and destination interactions of this mapping.");
        }
        if (blank(mapping.targetPath()) || blank(mapping.sourcePath())
            && blank(mapping.expression())) {
          add(issues, "MAPPING_FIELD_REQUIRED", path + "/fieldMapping",
              fact.sourceFactId(), "Provide a target field and a source field or expression.");
        }
        if (fact.polarity() != RequirementCaptureInput.Polarity.POSITIVE) {
          add(issues, "MAPPING_POLARITY_INVALID", path + "/polarity", fact.sourceFactId(),
              "Use a positive mapping fact; record prohibitions as constraints.");
        }
      } else if (fact.fieldMapping() != null) {
        add(issues, "MAPPING_KIND_REQUIRED", path + "/kind", fact.sourceFactId(),
            "Use FIELD_MAPPING for a structured field mapping.");
      }
    }
    Set<String> questionIds = new HashSet<>();
    for (int i = 0; i < draft.openQuestions().size(); i++) {
      QuestionInput question = draft.openQuestions().get(i);
      String path = "/draft/openQuestions/" + i;
      if (question == null) {
        add(issues, "INVALID_TYPE", path, null, "Question must be an object.");
        continue;
      }
      checkId(issues, question.questionId(), path + "/questionId");
      if (!questionIds.add(question.questionId())) {
        add(issues, "DUPLICATE_ENTITY", path + "/questionId", question.questionId(),
            "Question identity is repeated.");
      }
      if (blank(question.text())) {
        add(issues, "MISSING_FIELD", path + "/text", question.questionId(),
            "Question text is required.");
      }
      checkTargets(issues, question.interactionIds(), ids, path + "/interactionIds", question.questionId());
    }

    Set<String> capabilityIds = new HashSet<>();
    Set<String> validKeys = new HashSet<>(ChainElementFamilies.TRIGGERS);
    validKeys.addAll(ChainElementFamilies.SENDERS);
    validKeys.addAll(ChainElementFamilies.FILE_TRANSFER);
    validKeys.add("chain-call-2");
    for (int i = 0; i < draft.capabilities().size(); i++) {
      CapabilityInput capability = draft.capabilities().get(i);
      String path = "/draft/capabilities/" + i;
      if (capability == null) {
        add(issues, "INVALID_TYPE", path, null, "Capability must be an object.");
        continue;
      }
      String id = capability.interactionId();
      if (!byId.containsKey(id)) {
        add(issues, "UNKNOWN_INTERACTION_REFERENCE", path + "/interactionId", id,
            "Capability must name an accepted interaction.");
      }
      if (!capabilityIds.add(id)) {
        add(issues, "DUPLICATE_ENTITY", path + "/interactionId", id,
            "Only one capability may target an interaction.");
      }
      String key = capability.capabilityKey();
      if (!validKeys.contains(key)) {
        add(issues, "UNSUPPORTED_CAPABILITY", path + "/capabilityKey", id,
            "Use a supported native element key.");
        continue;
      }
      Direction direction = byId.containsKey(id) ? byId.get(id).direction() : null;
      if (direction != null
          && (direction == Direction.INBOUND) != ChainElementFamilies.isTrigger(key)) {
        add(issues, "CAPABILITY_DIRECTION_MISMATCH", path + "/capabilityKey", id,
            "Capability does not match the interaction direction.");
      }
      checkApplicable(issues, path, capability);
    }
    for (InteractionInput interaction : draft.flow().interactions()) {
      if (interaction != null && interaction.direction() == Direction.OUTBOUND
          && RequirementFlowValidator.isDirectHttpTarget(interaction.participant())
          && !capabilityIds.contains(interaction.interactionId())) {
        add(issues, "REQUIREMENT_COVERAGE_GAP", "/draft/capabilities",
            interaction.interactionId(),
            "The direct HTTP URI needs an http-sender capability with method and URI.");
      }
    }
    return sorted(issues);
  }

  private static void checkApplicable(List<Issue> issues, String path, CapabilityInput capability) {
    String key = capability.capabilityKey();
    String id = capability.interactionId();
    if (capability.httpMode() != null && !"http-trigger".equals(key)) {
      add(issues, "CAPABILITY_FIELD_NOT_APPLICABLE", path + "/httpMode", id,
          "httpMode belongs to an HTTP trigger.");
    }
    if ((capability.httpMethod() != null || capability.path() != null)
        && !"http-trigger".equals(key) && !"http-sender".equals(key)) {
      add(issues, "CAPABILITY_FIELD_NOT_APPLICABLE", path + "/path", id,
          "HTTP method and path belong to an HTTP trigger or sender.");
    }
    if (capability.topic() != null
        && !"kafka-trigger-2".equals(key) && !"kafka-sender-2".equals(key)) {
      add(issues, "CAPABILITY_FIELD_NOT_APPLICABLE", path + "/topic", id,
          "topic belongs to a Kafka element.");
    }
    if (capability.mcpServerId() != null && !"mcp-trigger".equals(key)) {
      add(issues, "CAPABILITY_FIELD_NOT_APPLICABLE", path + "/mcpServerId", id,
          "mcpServerId belongs to an MCP trigger.");
    }
    if (capability.targetReference() != null
        && !"chain-call-2".equals(key) && !"mcp-trigger".equals(key)) {
      add(issues, "CAPABILITY_FIELD_NOT_APPLICABLE", path + "/targetReference", id,
          "targetReference belongs to a chain call or MCP trigger.");
    }
    if ("http-trigger".equals(key)
        && capability.httpMode() == RequirementCaptureInput.HttpMode.CATALOG
        && capability.path() != null) {
      add(issues, "CAPABILITY_FIELD_NOT_APPLICABLE", path + "/path", id,
          "Catalog HTTP triggers do not have a custom path.");
    }
  }

  private static void checkTargets(
      List<Issue> issues, List<String> targets, Set<String> ids, String path, String entityId) {
    if (targets == null) {
      add(issues, "MISSING_FIELD", path, entityId, "interactionIds is required.");
      return;
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < targets.size(); i++) {
      String target = targets.get(i);
      if (!ids.contains(target)) {
        add(issues, "UNKNOWN_INTERACTION_REFERENCE", path + "/" + i, entityId,
            "Target must name an accepted interaction.");
      }
      if (!seen.add(target)) {
        add(issues, "DUPLICATE_ENTITY", path + "/" + i, entityId,
            "Target is repeated.");
      }
    }
  }

  private static boolean hasCycle(Map<String, List<String>> successors, Set<String> ids) {
    Map<String, Integer> degree = new HashMap<>();
    for (String id : ids) {
      degree.put(id, 0);
    }
    for (List<String> targets : successors.values()) {
      for (String target : targets) {
        degree.computeIfPresent(target, (ignored, prior) -> prior + 1);
      }
    }
    ArrayDeque<String> pending = new ArrayDeque<>();
    degree.forEach((id, value) -> {
      if (value == 0) {
        pending.add(id);
      }
    });
    int visited = 0;
    while (!pending.isEmpty()) {
      String id = pending.remove();
      visited++;
      for (String target : successors.getOrDefault(id, List.of())) {
        int next = degree.computeIfPresent(target, (ignored, prior) -> prior - 1);
        if (next == 0) {
          pending.add(target);
        }
      }
    }
    return visited != degree.size();
  }

  private static void checkId(List<Issue> issues, String id, String path) {
    if (id == null || !LOCAL_ID.matcher(id).matches()) {
      add(issues, "INVALID_VALUE", path, null,
          "Use a local ID beginning with a letter and at most 64 characters.");
    }
  }

  private static void requiredList(List<Issue> issues, List<?> values, String path) {
    if (values == null) {
      add(issues, "MISSING_FIELD", path, null, "List is required; use [] when empty.");
    }
  }

  private static void add(
      List<Issue> issues, String code, String path, String entityId, String message) {
    issues.add(new Issue(code, path, entityId, message));
  }

  private static List<Issue> sorted(List<Issue> issues) {
    return issues.stream()
        .sorted((a, b) -> {
          int byCode = a.code().compareTo(b.code());
          if (byCode != 0) {
            return byCode;
          }
          int byPath = a.path().compareTo(b.path());
          return byPath != 0 ? byPath : Objects.toString(a.entityId(), "")
              .compareTo(Objects.toString(b.entityId(), ""));
        })
        .toList();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
