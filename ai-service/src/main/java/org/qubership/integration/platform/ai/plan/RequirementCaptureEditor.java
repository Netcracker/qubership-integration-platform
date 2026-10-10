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
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FieldMappingInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.RetryInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/** Validates an authored requirement snapshot and applies focused edits without partial writes. */
public final class RequirementCaptureEditor {

  private static final Pattern LOCAL_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

  public record Issue(String code, String path, String entityId, String message) {}

  public record EditResult(DraftInput draft, List<Issue> issues, boolean changed, DraftInput candidate) {
    public EditResult(DraftInput draft, List<Issue> issues, boolean changed) {
      this(draft, issues, changed, null);
    }
    public boolean accepted() {
      return issues.isEmpty();
    }
  }

  private RequirementCaptureEditor() {}

  public static EditResult initialize(DraftInput draft) {
    List<Issue> issues = new ArrayList<>(validate(draft));
    if (draft != null && draft.facts() != null) {
      for (FactInput fact : draft.facts()) {
        if (fact != null && fact.policyConflict() != null && fact.policyConflict().decision() != null) {
          add(issues, "POLICY_RESPONSE_REQUIRED", "/draft/facts", fact.sourceFactId(),
              "Persist the policy question first. An initial request is not an informed response.");
        }
      }
    }
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
            update.settings() == null ? accepted.settings()
                : new RequirementCaptureInput.DraftSettings(
                    update.settings().idsRequested() == null
                        ? accepted.settings().idsRequested() : update.settings().idsRequested(),
                    update.settings().preferredSystemType() == null
                        ? accepted.settings().preferredSystemType() : update.settings().preferredSystemType(),
                    update.settings().targetFolderPath() == null
                        ? accepted.settings().targetFolderPath() : update.settings().targetFolderPath()));
    issues.addAll(validate(candidate));
    issues.addAll(validatePolicyResponses(accepted, candidate, fact -> true));
    return new EditResult(
        issues.isEmpty() ? candidate : accepted,
        sorted(issues),
        issues.isEmpty() && !candidate.equals(accepted), candidate);
  }

  static List<Issue> validatePolicyResponses(
      DraftInput previous, DraftInput candidate, Predicate<FactInput> responseAllowed) {
    List<Issue> issues = new ArrayList<>();
    for (FactInput fact : candidate.facts()) {
      var conflict = fact.policyConflict();
      if (conflict == null || conflict.decision() == null) {
        continue;
      }
      FactInput old = previous.facts().stream()
          .filter(f -> f.sourceFactId().equals(fact.sourceFactId())).findFirst().orElse(null);
      var prior = old == null ? null : old.policyConflict();
      if (Objects.equals(prior, conflict) && Objects.equals(old.interactionIds(), fact.interactionIds())) {
        continue;
      }
      boolean asked = wasPolicyQuestionAsked(previous, fact);
      if (!responseAllowed.test(fact) || !asked) {
        add(issues, "POLICY_RESPONSE_REQUIRED", "/changes/facts", fact.sourceFactId(),
            "Resolve a previously persisted policy question only after a new user reply."
                + " Changed values or scope require a new question.");
      }
    }
    return issues;
  }

  static boolean wasPolicyQuestionAsked(DraftInput previous, FactInput fact) {
    if (previous == null || fact.policyConflict() == null) {
      return false;
    }
    FactInput old = previous.facts().stream()
        .filter(f -> f.sourceFactId().equals(fact.sourceFactId())).findFirst().orElse(null);
    return old != null && old.kind() == FactKind.CONSTRAINT && old.polarity() == Polarity.POSITIVE
        && old.interactionIds().equals(fact.interactionIds())
        && (old.policyConflict() == null || old.policyConflict().equals(fact.policyConflict().unresolved()))
        && previous.openQuestions().stream().anyMatch(q ->
            q.questionId().equals(fact.policyConflict().questionId())
                && q.interactionIds().equals(fact.interactionIds()));
  }

  static List<Issue> validateFactPreservation(DraftInput accepted, DraftInput candidate) {
    return validateFactPreservation(accepted, candidate, Map.of());
  }

  static List<Issue> validateFactPreservation(
      DraftInput accepted, DraftInput candidate, Map<String, Set<String>> editableMappingPaths) {
    List<Issue> invalidOrder = validate(accepted).stream()
        .filter(issue -> "ELEMENT_FLOW_ORDER_MISMATCH".equals(issue.code())).toList();
    if (!invalidOrder.isEmpty()) {
      var protectedCandidate = new DraftInput(candidate.flow(), factsToPreserve(candidate, invalidOrder),
          candidate.capabilities(), candidate.openQuestions(), candidate.settings());
      return validateFactPreservation(factsToPreserve(accepted, invalidOrder), protectedCandidate, true, editableMappingPaths);
    }
    return validateFactPreservation(accepted.facts(), candidate, true, editableMappingPaths);
  }

  public static List<Issue> validateElementOrder(
      org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow flow,
      List<RequirementFact> facts) {
    if (flow == null) { return List.of(); }
    Set<String> interactions = new HashSet<>();
    flow.interactions().forEach(interaction -> interactions.add(interaction.interactionId()));
    Map<String, List<String>> successors = new HashMap<>();
    flow.transitions().forEach(transition -> successors.computeIfAbsent(
        transition.sourceInteractionId(), ignored -> new ArrayList<>()).add(transition.targetInteractionId()));
    List<Issue> issues = new ArrayList<>();
    for (RequirementFact fact : facts) {
      if (fact.elementIntent() != null) {
        checkElementOrder(issues, fact.elementIntent(), fact.sourceFactId(), interactions, successors);
      }
    }
    return List.copyOf(issues);
  }

  private static void checkElementOrder(List<Issue> issues, RequirementElementIntent intent, String factId,
      Set<String> interactions, Map<String, List<String>> successors) {
    if (intent.placement() != RequirementElementIntent.Placement.ANCHOR
        || !interactions.contains(intent.elementRef())) { return; }
    var refs = new String[] {intent.successorElementRef(), intent.predecessorElementRef()};
    var fields = new String[] {"successorElementRef", "predecessorElementRef"};
    for (int i = 0; i < refs.length; i++) {
      String ref = refs[i];
      if (ref != null && !ref.equals(intent.elementRef()) && interactions.contains(ref)
          && !hasPath(successors, i == 0 ? intent.elementRef() : ref, i == 0 ? ref : intent.elementRef())) {
        add(issues, "ELEMENT_FLOW_ORDER_MISMATCH", "/draft/facts/" + factId + "/elementIntent/" + fields[i], factId,
            "This element order contradicts flow.transitions. Parallel or alternative interactions must not"
                + " name each other as predecessor or successor. Use null for this reference unless the source"
                + " requires that sequence; then correct the flow too. Preserve the fact text and settings.");
      }
    }
  }

  static List<FactInput> factsToPreserve(DraftInput input, List<Issue> issues) {
    if (input == null || input.facts() == null) {
      return List.of();
    }
    List<FactInput> facts = new ArrayList<>();
    for (int i = 0; i < input.facts().size(); i++) {
      FactInput fact = input.facts().get(i);
      String prefix = "/draft/facts/" + i;
      List<Issue> factIssues = issues.stream().filter(issue ->
          (issue.path().equals(prefix) || issue.path().startsWith(prefix + "/"))
              && !issue.path().equals(prefix + "/sourceFactId")
              || Objects.equals(issue.entityId(), fact == null ? null : fact.sourceFactId())
                  && issue.path().startsWith("/draft/facts/")
                  && !issue.path().endsWith("/sourceFactId")).toList();
      if (fact != null && fact.text() != null && !fact.text().isBlank()
          && fact.kind() != null && fact.polarity() != null && fact.interactionIds() != null
          && factIssues.stream().allMatch(issue -> issue.path().contains("/elementIntent"))
          && factIssues.stream().noneMatch(issue -> "ELEMENT_LITERAL_SOURCE_MISMATCH".equals(issue.code())
              && factIssues.stream().noneMatch(other -> "ELEMENT_LITERAL_FACT_MISMATCH".equals(other.code())
                  && other.path().equals(issue.path())))) {
        facts.add(factIssues.isEmpty() ? fact : new FactInput(fact.sourceFactId(), fact.interactionIds(),
            fact.kind(), fact.polarity(), fact.text(), fact.fieldMapping(), fact.policyConflict(),
            intentToPreserve(fact.elementIntent(), factIssues)));
      }
    }
    return List.copyOf(facts);
  }

  private static RequirementElementIntent intentToPreserve(RequirementElementIntent intent, List<Issue> issues) {
    if (intent == null || issues.stream().anyMatch(issue -> issue.path().endsWith("/elementIntent")
        || issue.path().endsWith("/elementRef"))) {
      return null;
    }
    Set<String> paths = new HashSet<>();
    issues.forEach(issue -> paths.add(issue.path().substring(issue.path().indexOf("/elementIntent")
        + "/elementIntent".length())));
    var properties = intent.requestedProperties().stream().filter(property -> property != null
        && property.key() != null && !paths.contains("/requestedProperties")
        && !paths.contains("/requestedProperties/" + property.key().replace("~", "~0").replace("/", "~1"))).toList();
    List<String> values = new ArrayList<>();
    for (int i = 0; i < intent.exactValues().size(); i++) {
      if (!paths.contains("/exactValues/" + i)) {
        values.add(intent.exactValues().get(i));
      }
    }
    return new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
        intent.ownerInteractionId(), paths.contains("/successorElementRef") ? null : intent.successorElementRef(),
        paths.contains("/predecessorElementRef") ? null : intent.predecessorElementRef(),
        paths.contains("/parentElementRef") ? null : intent.parentElementRef(),
        paths.contains("/requestedName") ? null : intent.requestedName(), properties, values);
  }

  static List<Issue> validateFactPreservation(
      List<FactInput> originalFacts, DraftInput candidate, boolean saved) {
    return validateFactPreservation(originalFacts, candidate, saved, Map.of());
  }

  static List<Issue> validateFactPreservation(
      List<FactInput> originalFacts, DraftInput candidate, boolean saved, Map<String, Set<String>> editableMappingPaths) {
    Set<String> interactions = new HashSet<>();
    candidate.flow().interactions().forEach(interaction -> interactions.add(interaction.interactionId()));
    Map<String, FactInput> facts = new HashMap<>();
    candidate.facts().forEach(fact -> facts.put(fact.sourceFactId(), fact));
    Map<String, Integer> counts = new HashMap<>();
    originalFacts.forEach(fact -> counts.merge(fact.sourceFactId(), 1, Integer::sum));
    List<Issue> issues = new ArrayList<>();
    for (FactInput original : originalFacts) {
      boolean stableId = original.sourceFactId() != null
          && LOCAL_ID.matcher(original.sourceFactId()).matches()
          && counts.get(original.sourceFactId()) == 1;
      FactInput updated = stableId ? facts.get(original.sourceFactId())
          : candidate.facts().stream()
              .filter(fact -> Objects.equals(original.text(), fact.text())).findFirst().orElse(null);
      // ponytail: Preserve accepted wording within a turn; semantic corrections start with new author input.
      if (updated == null
          || !Objects.equals(original.text(), updated.text())
          || original.kind() != updated.kind()
          || original.polarity() != updated.polarity()
          || (!original.interactionIds().isEmpty() && updated.interactionIds().isEmpty())
          || original.interactionIds().stream().anyMatch(id ->
              interactions.contains(id) && !updated.interactionIds().contains(id))
          || !preservesElementIntent(original.elementIntent(), updated.elementIntent(), saved)
          || !sameMappingContent(original.fieldMapping(), updated.fieldMapping(), interactions,
              editableMappingPaths.getOrDefault(original.sourceFactId(), Set.of()))
          || !Objects.equals(original.policyConflict() == null ? null : original.policyConflict().unresolved(),
              updated.policyConflict() == null ? null : updated.policyConflict().unresolved())) {
        add(issues, "REPAIR_FACT_LOSS", (saved ? "/changes" : "/draft")
                + "/facts/" + original.sourceFactId(),
            original.sourceFactId(),
            "Preserve this fact's valid ID, kind, full text, polarity, and mapping values during repair."
                + " Correct only reported fields; reassign references when removing an incorrect interaction."
                + " Required fact text: " + original.text()
                + (saved ? " The accepted draft was retained." : " No draft was saved."));
      }
    }
    return sorted(issues);
  }

  private static boolean preservesElementIntent(
      RequirementElementIntent original, RequirementElementIntent updated, boolean saved) {
    if (saved) {
      return Objects.equals(original, updated);
    }
    // Rejected captures protect validated values while their invalid representation remains repairable.
    return original == null || updated != null
        && Objects.equals(original.elementRef(), updated.elementRef())
        && Objects.equals(original.elementType(), updated.elementType())
        && original.placement() == updated.placement()
        && preservesValue(original.ownerInteractionId(), updated.ownerInteractionId())
        && preservesValue(original.successorElementRef(), updated.successorElementRef())
        && preservesValue(original.predecessorElementRef(), updated.predecessorElementRef())
        && preservesValue(original.parentElementRef(), updated.parentElementRef())
        && preservesValue(original.requestedName(), updated.requestedName())
        && updated.requestedProperties().containsAll(original.requestedProperties())
        && updated.exactValues().containsAll(original.exactValues());
  }

  private static boolean preservesValue(String original, String updated) {
    return original == null || original.equals(updated);
  }

  private static boolean sameMappingContent(
      FieldMappingInput original, FieldMappingInput updated, Set<String> interactions, Set<String> editablePaths) {
    if (original == null || updated == null) {
      return original == updated;
    }
    return (editablePaths.contains("sourcePath") || Objects.equals(original.sourcePath(), updated.sourcePath()))
        && (editablePaths.contains("targetPath") || Objects.equals(original.targetPath(), updated.targetPath()))
        && Objects.equals(original.expression(), updated.expression())
        && (!interactions.contains(original.sourceInteractionId())
            || Objects.equals(original.sourceInteractionId(), updated.sourceInteractionId()))
        && (!interactions.contains(original.targetInteractionId())
            || Objects.equals(original.targetInteractionId(), updated.targetInteractionId()));
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

    validateElementIntents(draft, ids, successors, issues);
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
      }
      checkTargets(issues, fact.interactionIds(), ids, path + "/interactionIds", fact.sourceFactId());
      var conflict = fact.policyConflict();
      if (conflict != null) {
        if (fact.kind() != RequirementCaptureInput.FactKind.CONSTRAINT
            || fact.polarity() != RequirementCaptureInput.Polarity.POSITIVE
            || fact.interactionIds() == null || fact.interactionIds().size() != 1
            || blank(conflict.policyId()) || blank(conflict.propertyKey())
            || blank(conflict.requestedValue()) || blank(conflict.recommendedValue())
            || blank(conflict.questionId())
            || Objects.equals(conflict.requestedValue(), conflict.recommendedValue())) {
          add(issues, "INVALID_POLICY_CONFLICT", path + "/policyConflict", fact.sourceFactId(),
              "Use one positive CONSTRAINT tied to one interaction, distinct values, a policy ID,"
                  + " a property key, and a question ID.");
        }
        boolean questionPresent = draft.openQuestions().stream()
            .anyMatch(q -> q != null && Objects.equals(q.questionId(), conflict.questionId())
                && Objects.equals(q.interactionIds(), fact.interactionIds()));
        if ((conflict.decision() == null) != questionPresent) {
          add(issues, "POLICY_QUESTION_REQUIRED", path + "/policyConflict", fact.sourceFactId(),
              "Keep the scoped question open until the user answers; remove it when recording the response.");
        }
      }
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
              fact.sourceFactId(),
              "Use one approved direct flow transition for this field mapping. For an earlier event"
                  + " value retained across a call, keep the save-before-call BEHAVIOR and map each"
                  + " result field on the delivery transition with sourcePath=null and an expression"
                  + " reading the saved original field. Preserve its original source and destination."
                  + " Do not copy from the call response or add a transition that bypasses the call.");
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
        if (MappingContract.expandedCanonicalPaths(mapping.targetPath()).size() > 1) {
          add(issues, "MAPPING_SINGLE_TARGET_REQUIRED", path + "/fieldMapping/targetPath", fact.sourceFactId(),
              "Capture one FIELD_MAPPING fact per target field. Do not put a field list in targetPath.");
        }
        if (MappingContract.expandedCanonicalPaths(mapping.sourcePath()).size() > 1) {
          add(issues, "MAPPING_SINGLE_SOURCE_REQUIRED", path + "/fieldMapping/sourcePath", fact.sourceFactId(),
              "Use one source field path. For a computed value, set sourcePath=null and describe all inputs"
                  + " in expression. Preserve the full requested behavior.");
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

  private static void validateElementIntents(DraftInput draft, Set<String> interactions,
      Map<String, List<String>> successors, List<Issue> issues) {
    Map<String, List<RequirementElementIntent>> intents = new HashMap<>();
    String chainName = null;
    for (FactInput fact : draft.facts()) {
      if (fact == null || fact.elementIntent() == null) {
        continue;
      }
      var intent = fact.elementIntent();
      String path = "/draft/facts/" + fact.sourceFactId() + "/elementIntent";
      checkId(issues, intent.elementRef(), path + "/elementRef");
      if (intent.placement() == null || intent.elementType() == null || intent.elementType().isBlank()
          || intent.requestedProperties() == null || intent.exactValues() == null) {
        add(issues, "INVALID_ELEMENT_INTENT", path, fact.sourceFactId(),
            "Provide elementType, placement, requestedProperties, and exactValues; use empty lists when unspecified.");
        continue;
      }
      boolean chain = intent.placement() == RequirementElementIntent.Placement.CHAIN;
      if (chain ? !"chain".equals(intent.elementType())
          : !interactions.contains(intent.ownerInteractionId())
              || !fact.interactionIds().contains(intent.ownerInteractionId())) {
        add(issues, "INVALID_ELEMENT_OWNER", path, fact.sourceFactId(),
            "Chain intent uses elementType=chain. Other intent must target its owning business interaction.");
      }
      if (intent.placement() == RequirementElementIntent.Placement.ANCHOR
          && !Objects.equals(intent.elementRef(), intent.ownerInteractionId())
          || intent.placement() == RequirementElementIntent.Placement.LOCAL_NODE
              && interactions.contains(intent.elementRef())) {
        add(issues, "INVALID_ELEMENT_REFERENCE", path, fact.sourceFactId(),
            "An anchor uses its interaction ID; a local element uses a separate elementRef.");
      }
      if ((intent.placement() == RequirementElementIntent.Placement.BEFORE_HOOK
          || intent.placement() == RequirementElementIntent.Placement.AFTER_HOOK)
          && (!"script".equals(intent.elementType()) || intent.requestedName() != null
              || !intent.requestedProperties().isEmpty())) {
        add(issues, "INVALID_HOOK_INTENT", path, fact.sourceFactId(),
            "An inline hook is script behavior on its owner, without a display name or standalone properties.");
      }
      if (chain && (intent.ownerInteractionId() != null || !intent.requestedProperties().isEmpty()
          || intent.successorElementRef() != null || intent.predecessorElementRef() != null
          || intent.parentElementRef() != null)) {
        add(issues, "INVALID_CHAIN_INTENT", path, fact.sourceFactId(),
            "Chain intent records the exact chain name and has no element owner or configuration.");
      }
      if (intent.parentElementRef() != null
          && intent.placement() != RequirementElementIntent.Placement.LOCAL_NODE) {
        add(issues, "INVALID_ELEMENT_PARENT", path + "/parentElementRef", fact.sourceFactId(),
            "Only a local element may request a containing element; anchors and inline hooks keep their existing owner.");
      }
      if (chain && conflictingValue(chainName, intent.requestedName())) {
        add(issues, "CONFLICTING_ELEMENT_INTENT", path, fact.sourceFactId(),
            "Facts about the chain must agree on its requested name.");
      }
      if (chain && intent.requestedName() != null) {
        chainName = intent.requestedName();
      }
      var priors = intents.computeIfAbsent(intent.elementRef(), ignored -> new ArrayList<>());
      if (priors.stream().anyMatch(prior -> prior.placement() != intent.placement()
          || !Objects.equals(prior.elementType(), intent.elementType())
          || !Objects.equals(prior.ownerInteractionId(), intent.ownerInteractionId())
          || conflictingValue(prior.requestedName(), intent.requestedName())
          || conflictingValue(prior.successorElementRef(), intent.successorElementRef())
          || conflictingValue(prior.predecessorElementRef(), intent.predecessorElementRef())
          || conflictingValue(prior.parentElementRef(), intent.parentElementRef())
          || prior.requestedProperties().stream().anyMatch(property -> intent.requestedProperties().stream()
              .anyMatch(other -> property != null && other != null && property.key().equals(other.key())
                  && !Objects.equals(property.value(), other.value()))))) {
        add(issues, "CONFLICTING_ELEMENT_INTENT", path, fact.sourceFactId(),
            "Facts about the same elementRef must agree on type, owner, placement, names, and supplied property values.");
      }
      priors.add(intent);
    }
    for (FactInput fact : draft.facts()) {
      var intent = fact == null ? null : fact.elementIntent();
      if (intent == null) {
        continue;
      }
      var refs = new String[] {intent.successorElementRef(), intent.predecessorElementRef(), intent.parentElementRef()};
      var fields = new String[] {"successorElementRef", "predecessorElementRef", "parentElementRef"};
      for (int i = 0; i < refs.length; i++) {
        String ref = refs[i];
        if (ref != null && (!intents.containsKey(ref) && !interactions.contains(ref)
            || ref.equals(intent.elementRef()))) {
          add(issues, "UNKNOWN_ELEMENT_REFERENCE", "/draft/facts/" + fact.sourceFactId()
              + "/elementIntent/" + fields[i], fact.sourceFactId(),
              "Relative placement must name another accepted elementRef or business interaction.");
        }
      }
      checkElementOrder(issues, intent, fact.sourceFactId(), interactions, successors);
    }
  }

  private static boolean conflictingValue(String left, String right) {
    return left != null && right != null && !left.equals(right);
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

  private static boolean hasPath(Map<String, List<String>> successors, String source, String target) {
    var pending = new ArrayDeque<String>();
    var visited = new HashSet<String>();
    pending.add(source);
    while (!pending.isEmpty()) {
      String current = pending.remove();
      if (current.equals(target)) {
        return true;
      }
      if (visited.add(current)) {
        pending.addAll(successors.getOrDefault(current, List.of()));
      }
    }
    return false;
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
