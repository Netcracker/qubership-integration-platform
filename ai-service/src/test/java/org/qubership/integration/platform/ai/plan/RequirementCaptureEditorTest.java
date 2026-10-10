package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.SystemType;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureEditorTest {

  @Test
  void interactionOrderMustAgreeWithTheBusinessFlowAndRemainRepairable() {
    var base = initial();
    var interactions = new java.util.ArrayList<>(base.flow().interactions());
    interactions.add(new InteractionInput("notify", Direction.OUTBOUND, "Notification API", "notify", null, null, null));
    for (boolean predecessor : List.of(false, true)) {
      String owner = predecessor ? "notify" : "create-task";
      var intent = new RequirementElementIntent(owner, "http-sender", RequirementElementIntent.Placement.ANCHOR,
          owner, predecessor ? null : "notify", predecessor ? "create-task" : null, null, List.of(), List.of());
      var fact = new FactInput("branch", List.of(owner), FactKind.BEHAVIOR, Polarity.POSITIVE,
          "Run the outbound branches independently.", null, null, intent);
      var parallel = new DraftInput(new FlowInput(interactions, List.of(
          new TransitionInput("start", "create-task"), new TransitionInput("start", "notify"))),
          List.of(fact), base.capabilities(), List.of(), base.settings());
      var rejected = RequirementCaptureEditor.initialize(parallel);
      assertFalse(rejected.accepted());
      assertTrue(rejected.issues().stream().anyMatch(issue -> issue.code().equals("ELEMENT_FLOW_ORDER_MISMATCH")
          && issue.path().endsWith(predecessor ? "/predecessorElementRef" : "/successorElementRef")));
      var repaired = new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(),
          null, null, new RequirementElementIntent(owner, "http-sender", intent.placement(), owner,
              null, null, null, List.of(), List.of()));
      var corrected = new DraftInput(parallel.flow(), List.of(repaired), parallel.capabilities(), List.of(), base.settings());
      assertTrue(RequirementCaptureEditor.initialize(corrected).accepted());
      assertTrue(RequirementCaptureEditor.validateFactPreservation(
          RequirementCaptureEditor.factsToPreserve(parallel, rejected.issues()), corrected, false).isEmpty());
      assertTrue(RequirementCaptureEditor.validateFactPreservation(parallel, corrected).isEmpty());
      var changedText = new FactInput(repaired.sourceFactId(), repaired.interactionIds(), repaired.kind(), repaired.polarity(),
          "Discard one branch.", null, null, repaired.elementIntent());
      assertFalse(RequirementCaptureEditor.validateFactPreservation(parallel, new DraftInput(parallel.flow(),
          List.of(changedText), parallel.capabilities(), List.of(), base.settings())).isEmpty());
      var sequential = new DraftInput(new FlowInput(interactions, List.of(
          new TransitionInput("start", "create-task"), new TransitionInput("create-task", "notify"))),
          List.of(fact), base.capabilities(), List.of(), base.settings());
      assertTrue(RequirementCaptureEditor.initialize(sequential).accepted());
      assertFalse(RequirementCaptureEditor.validateFactPreservation(sequential, corrected).isEmpty());
      var intermediate = new java.util.ArrayList<>(interactions);
      intermediate.add(new InteractionInput("store", Direction.OUTBOUND, "Store API", "store", null, null, null));
      var indirect = new DraftInput(new FlowInput(intermediate, List.of(
          new TransitionInput("start", "create-task"), new TransitionInput("create-task", "store"),
          new TransitionInput("store", "notify"))), List.of(fact), base.capabilities(), List.of(), base.settings());
      assertTrue(RequirementCaptureEditor.initialize(indirect).accepted());
    }
  }

  @Test
  void reportedPlacementReferencesCanBeRepairedWhileValidElementValuesStayProtected() {
    var base = initial();
    for (int field = 0; field < 3; field++) {
      var intent = new RequirementElementIntent("prepare", "script", RequirementElementIntent.Placement.LOCAL_NODE,
          "start", field == 0 ? "missing" : null, field == 1 ? "missing" : null,
          field == 2 ? "missing" : null, "Prepare", List.of(), List.of("Groovy"));
      var fact = new FactInput("prepare", List.of("start"), FactKind.BEHAVIOR, Polarity.POSITIVE,
          "Run Prepare in Groovy.", null, null, intent);
      var input = new DraftInput(base.flow(), List.of(fact), base.capabilities(), base.openQuestions(), base.settings());
      var rejected = RequirementCaptureEditor.initialize(input);
      assertFalse(rejected.accepted());
      var corrected = new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), fact.text(),
          null, null, new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
              intent.ownerInteractionId(), null, null, null, intent.requestedName(), List.of(), intent.exactValues()));
      var candidate = new DraftInput(input.flow(), List.of(corrected), input.capabilities(), input.openQuestions(),
          input.settings());
      var protectedFacts = RequirementCaptureEditor.factsToPreserve(input, rejected.issues());
      assertTrue(RequirementCaptureEditor.validateFactPreservation(protectedFacts, candidate, false).isEmpty());
      var renamed = new FactInput(corrected.sourceFactId(), corrected.interactionIds(), corrected.kind(),
          corrected.polarity(), corrected.text(), null, null,
          new RequirementElementIntent("prepare", "script", intent.placement(), "start", null, null,
              "Other name", List.of(), intent.exactValues()));
      assertFalse(RequirementCaptureEditor.validateFactPreservation(protectedFacts, new DraftInput(input.flow(),
          List.of(renamed), input.capabilities(), input.openQuestions(), input.settings()), false).isEmpty());
    }
  }

  @Test
  void idsChoicePreservesFolderAndSystemTypeWhileExplicitChangesReplaceThem() {
    var base = initial();
    var accepted = new DraftInput(base.flow(), base.facts(), base.capabilities(), base.openQuestions(),
        new DraftSettings(null, SystemType.INTERNAL, "/Tests"));
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        new DraftSettings(false, null, null));
    var result = RequirementCaptureEditor.apply(accepted, update);
    assertTrue(result.accepted(), result.issues().toString());
    assertEquals(new DraftSettings(false, SystemType.INTERNAL, "/Tests"), result.draft().settings());
    var changed = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        new DraftSettings(null, SystemType.EXTERNAL, "/Other tests"));
    assertEquals(new DraftSettings(false, SystemType.EXTERNAL, "/Other tests"),
        RequirementCaptureEditor.apply(result.draft(), changed).draft().settings());
  }

  @Test
  void unrelatedSettingsEditsPreserveIdsChoiceAndAnExplicitChangeReplacesIt() {
    var base = initial();
    for (Boolean choice : List.of(true, false)) {
      var accepted = new DraftInput(base.flow(), base.facts(), base.capabilities(), base.openQuestions(),
          new DraftSettings(choice, null));
      var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          new DraftSettings(null, null, "/Tests"));
      var result = RequirementCaptureEditor.apply(accepted, update);
      assertTrue(result.accepted(), result.issues().toString());
      assertEquals(choice, result.draft().settings().idsRequested());
      assertEquals(base.facts(), result.draft().facts());
      var changed = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          new DraftSettings(!choice, null, "/Tests"));
      assertEquals(!choice, RequirementCaptureEditor.apply(result.draft(), changed).draft().settings().idsRequested());
    }
  }

  @Test
  void nestedPlacementRejectsMissingSelfAndConflictingParents() {
    var base = initial();
    var facts = new java.util.ArrayList<>(base.facts());
    for (String parent : List.of("missing", "nested-work", "start")) {
      var intent = new RequirementElementIntent("nested-work", "script",
          RequirementElementIntent.Placement.LOCAL_NODE, "start", null, null, parent,
          "Nested work", List.of(), List.of());
      facts.add(new FactInput("nested-" + parent, List.of("start"), FactKind.BEHAVIOR,
          Polarity.POSITIVE, "Run the script inside its container.", null, null, intent));
    }
    var result = RequirementCaptureEditor.initialize(new DraftInput(base.flow(), facts,
        base.capabilities(), base.openQuestions(), base.settings()));

    assertFalse(result.accepted());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("UNKNOWN_ELEMENT_REFERENCE")));
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CONFLICTING_ELEMENT_INTENT")));
  }

  @Test
  void nestedPlacementPreservesTheContainerAndItsChildFacts() {
    var base = initial();
    var facts = new java.util.ArrayList<>(base.facts());
    var container = new RequirementElementIntent("reuse-work", "reuse",
        RequirementElementIntent.Placement.LOCAL_NODE, "start", null, null, null, List.of(), List.of());
    var child = new RequirementElementIntent("nested-work", "script",
        RequirementElementIntent.Placement.LOCAL_NODE, "start", null, null, "reuse-work",
        "Nested work", List.of(), List.of());
    facts.add(new FactInput("reuse-config", List.of("start"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Create the reusable container.", null, null, container));
    facts.add(new FactInput("nested-config", List.of("start"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Run the script inside the container.", null, null, child));
    var result = RequirementCaptureEditor.initialize(new DraftInput(base.flow(), facts,
        base.capabilities(), base.openQuestions(), base.settings()));

    assertTrue(result.accepted(), result.issues().toString());
    assertEquals(facts, result.draft().facts());
  }

  @Test
  void laterFactsCannotIntroduceConflictingNamesOrUnknownOrderReferences() {
    var base = initial();
    var facts = new java.util.ArrayList<>(base.facts());
    for (int i = 0; i < 3; i++) {
      var intent = new RequirementElementIntent("checkpoint", "checkpoint",
          RequirementElementIntent.Placement.LOCAL_NODE, "start", i == 2 ? "missing" : null,
          null, i == 0 ? null : i == 1 ? "First name" : "Second name", List.of(), List.of());
      facts.add(new FactInput("checkpoint-" + i, List.of("start"), FactKind.BEHAVIOR,
          Polarity.POSITIVE, "Save the current context.", null, null, intent));
    }
    var result = RequirementCaptureEditor.initialize(new DraftInput(base.flow(), facts,
        base.capabilities(), base.openQuestions(), base.settings()));
    assertFalse(result.accepted());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("CONFLICTING_ELEMENT_INTENT")));
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("UNKNOWN_ELEMENT_REFERENCE")));
    var valid = RequirementCaptureEditor.initialize(new DraftInput(base.flow(), facts.subList(0, 4),
        base.capabilities(), base.openQuestions(), base.settings()));
    assertTrue(valid.accepted(), valid.issues().toString());
  }

  @Test
  void fieldListsAreRejectedBeforeAcceptanceWithoutLosingOtherFacts() {
    DraftInput initial = initial();
    for (boolean sourceList : List.of(false, true)) {
      var mapping = new FactInput("mapping", List.of("start", "create-task"),
          FactKind.FIELD_MAPPING, Polarity.POSITIVE, "Keep the original values for the result.",
          new RequirementCaptureInput.FieldMappingInput("start",
              sourceList ? "taskId,executionId,parameters" : null, "create-task",
              sourceList ? "Description" : "executionId,orderId,processId,executionNumber,taskId",
              "Read the original values saved before the call."));
      var input = new DraftInput(initial.flow(), List.of(initial.facts().getFirst(), mapping),
          initial.capabilities(), initial.openQuestions(), initial.settings());
      var rejected = RequirementCaptureEditor.initialize(input);
      assertFalse(rejected.accepted());
      assertTrue(rejected.issues().stream().anyMatch(issue ->
          (sourceList ? "MAPPING_SINGLE_SOURCE_REQUIRED" : "MAPPING_SINGLE_TARGET_REQUIRED").equals(issue.code())
              && "mapping".equals(issue.entityId())));
      var protectedFacts = RequirementCaptureEditor.factsToPreserve(input, rejected.issues());
      assertEquals(List.of(initial.facts().getFirst().sourceFactId(), "mapping"), protectedFacts.stream().map(FactInput::sourceFactId).toList());
      assertEquals(mapping.text(), protectedFacts.getLast().text());
      assertEquals(mapping.fieldMapping().expression(), protectedFacts.getLast().fieldMapping().expression());
      var corrected = new FactInput(mapping.sourceFactId(), mapping.interactionIds(), mapping.kind(),
          mapping.polarity(), mapping.text(), new RequirementCaptureInput.FieldMappingInput(
              "start", null, "create-task", sourceList ? "Description" : "executionId",
              mapping.fieldMapping().expression()));
      var accepted = RequirementCaptureEditor.initialize(new DraftInput(input.flow(),
          List.of(initial.facts().getFirst(), corrected), input.capabilities(), input.openQuestions(), input.settings()));
      assertTrue(accepted.accepted(), accepted.issues().toString());
    }
  }

  @Test
  void focusedFactEditRetainsFlowAndOtherFacts() {
    DraftInput initial = initial();
    FactInput revised = new FactInput("mapping", List.of("create-task"),
        FactKind.BEHAVIOR, Polarity.POSITIVE, "Set Subject from description.");
    DraftUpdate update = update(List.of(), List.of(revised), List.of());

    var result = RequirementCaptureEditor.apply(initial, update);

    assertTrue(result.accepted());
    assertTrue(result.changed());
    assertEquals(initial.flow(), result.draft().flow());
    assertEquals(initial.capabilities(), result.draft().capabilities());
    assertEquals(initial.facts().getFirst(), result.draft().facts().getFirst());
    assertEquals(revised, result.draft().facts().get(1));
    assertEquals(2, RequirementCaptureProjection.pending(result.draft(), List.of()).stream()
        .filter(work -> "OPERATION_LOOKUP_REQUIRED".equals(work.code())).count());
  }

  @Test
  void removalWithDanglingTransitionRejectsEntireEdit() {
    DraftInput initial = initial();
    DraftUpdate invalid = update(List.of("create-task"), List.of(), List.of());
    var rejected = RequirementCaptureEditor.apply(initial, invalid);
    assertFalse(rejected.accepted());
    assertEquals(initial, rejected.draft());
    assertTrue(rejected.issues().stream().anyMatch(issue ->
        "UNKNOWN_INTERACTION_REFERENCE".equals(issue.code())));

    DraftUpdate stillDangling = update(List.of("create-task"), List.of(),
        List.of(new TransitionInput("start", "create-task")));
    var rejectedAgain = RequirementCaptureEditor.apply(initial, stillDangling);
    assertFalse(rejectedAgain.accepted());
    assertTrue(rejectedAgain.issues().stream().anyMatch(issue ->
        "UNKNOWN_INTERACTION_REFERENCE".equals(issue.code())));
  }

  @Test
  void goalOnlyDraftIsValidPartialContent() {
    DraftInput partial = new DraftInput(new FlowInput(List.of(), List.of()),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE,
            "Create Salesforce tasks.")), List.of(), List.of(), new DraftSettings(false, null));
    assertTrue(RequirementCaptureEditor.initialize(partial).accepted());
    assertTrue(RequirementCaptureProjection.pending(partial, List.of()).stream()
        .anyMatch(work -> "ENTRY_POINT_REQUIRED".equals(work.code())));
  }

  @Test
  void missingInteractionIdentityReturnsIssue() {
    DraftInput invalid = new DraftInput(
        new FlowInput(List.of(new InteractionInput(null, Direction.INBOUND,
            "Caller", "start", null, null, null)), List.of()),
        List.of(), List.of(), List.of(), new DraftSettings(false, null));

    var result = RequirementCaptureEditor.initialize(invalid);

    assertFalse(result.accepted());
    assertTrue(result.issues().stream().anyMatch(issue ->
        "INVALID_VALUE".equals(issue.code())
            && "/draft/flow/interactions/0/interactionId".equals(issue.path())));
  }

  @Test
  void directHttpTargetRequiresItsNativeSenderCapability() {
    DraftInput draft = new DraftInput(
        new FlowInput(List.of(
            new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /tasks",
                null, null, null),
            new InteractionInput("send", Direction.OUTBOUND, "https://example.com/tasks",
                "POST /tasks", null, null, null)),
            List.of(new TransitionInput("entry", "send"))),
        List.of(new FactInput("goal", List.of("send"), FactKind.GOAL,
            Polarity.POSITIVE, "Forward the task.")),
        List.of(new CapabilityInput("entry", "http-trigger",
            RequirementCaptureInput.HttpMode.CUSTOM,
            RequirementCaptureInput.HttpMethod.POST, "/tasks", null, null, null)),
        List.of(), new DraftSettings(false, null));

    var rejected = RequirementCaptureEditor.initialize(draft);

    assertFalse(rejected.accepted());
    assertTrue(rejected.issues().stream().anyMatch(issue ->
        "REQUIREMENT_COVERAGE_GAP".equals(issue.code())
            && "send".equals(issue.entityId())));
  }

  @Test
  void observationAndNonEnglishConstraintPreserveAuthoredPolarity() {
    DraftInput accepted = initial();
    FactInput observation = new FactInput("context-check", List.of("start"),
        FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Set X-Request-Id to prove that headers set in script do not leak into the context.");
    FactInput prohibition = new FactInput("no-logs", List.of("start"),
        FactKind.CONSTRAINT, Polarity.NEGATIVE, "Interdire la journalisation du corps entrant.");
    var result = RequirementCaptureEditor.apply(accepted,
        new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(observation, prohibition), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), null));

    assertTrue(result.accepted(), result.issues().toString());
    assertEquals(accepted.flow(), result.draft().flow());
    assertEquals(4, result.draft().facts().size());
    assertTrue(result.draft().facts().containsAll(accepted.facts()));
    assertTrue(result.draft().facts().containsAll(List.of(observation, prohibition)));
  }

  @Test
  void fieldMappingRequiresExactEndpointsAndSurvivesFocusedUpdate() {
    DraftInput accepted = initial();
    FactInput mapping = new FactInput("mapping", List.of("start", "create-task"),
        FactKind.FIELD_MAPPING, Polarity.POSITIVE, "Copy title to Subject.",
        new RequirementCaptureInput.FieldMappingInput(
            "start", "title", "create-task", "Subject", null));

    var updated = RequirementCaptureEditor.apply(accepted,
        update(List.of(), List.of(mapping), List.of()));
    assertTrue(updated.accepted(), updated.issues().toString());
    assertEquals(mapping, updated.draft().facts().get(1));
    assertTrue(RequirementCaptureProjection.render(updated.draft(), List.of())
        .contains("Entry 1.title -> Call 1.Subject"));

    FactInput unanchored = new FactInput("mapping", List.of("create-task"),
        FactKind.FIELD_MAPPING, Polarity.POSITIVE, mapping.text(), mapping.fieldMapping());
    var rejected = RequirementCaptureEditor.apply(updated.draft(),
        update(List.of(), List.of(unanchored), List.of()));
    assertFalse(rejected.accepted());
    assertEquals(updated.draft(), rejected.draft());
    assertTrue(rejected.issues().stream().anyMatch(issue ->
        "MAPPING_TARGETS_REQUIRED".equals(issue.code())));
  }

  private static DraftInput initial() {
    return new DraftInput(
        new FlowInput(List.of(
            new InteractionInput("start", Direction.INBOUND, "OM", "onTaskStart", null, null, null),
            new InteractionInput("create-task", Direction.OUTBOUND, "Salesforce", "createTask",
                null, null, null)),
            List.of(new TransitionInput("start", "create-task"))),
        List.of(
            new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE,
                "Create a task in Salesforce."),
            new FactInput("mapping", List.of("create-task"), FactKind.BEHAVIOR,
                Polarity.POSITIVE, "Set Subject from name.")),
        List.of(new CapabilityInput("start", "async-api-trigger", null, null, null, null, null, null)),
        List.of(), new DraftSettings(false, null));
  }

  private static DraftUpdate update(
      List<String> removedInteractions, List<FactInput> updatedFacts,
      List<TransitionInput> removedTransitions) {
    return new DraftUpdate(List.of(), List.of(), removedInteractions, List.of(),
        removedTransitions, List.of(), updatedFacts, List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), null);
  }
}
