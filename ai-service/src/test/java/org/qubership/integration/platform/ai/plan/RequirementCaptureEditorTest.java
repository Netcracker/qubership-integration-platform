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
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureEditorTest {

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
  void explicitProhibitionCannotBeSavedAsPositiveFact() {
    DraftInput accepted = initial();
    FactInput prohibition = new FactInput("no-logs", List.of("start"),
        FactKind.CONSTRAINT, Polarity.POSITIVE, "Do not log the input body.");
    var rejected = RequirementCaptureEditor.apply(accepted,
        new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(prohibition), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), null));

    assertFalse(rejected.accepted());
    assertEquals(accepted, rejected.draft());
    assertTrue(rejected.issues().stream().anyMatch(issue ->
        "REQUIREMENT_COVERAGE_GAP".equals(issue.code())
            && "/draft/facts/2/polarity".equals(issue.path())));

    FactInput corrected = new FactInput("no-logs", List.of("start"),
        FactKind.CONSTRAINT, Polarity.NEGATIVE, prohibition.text());
    var repair = RequirementCaptureEditor.apply(accepted,
        new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(corrected), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), null));
    assertTrue(repair.accepted());
    assertEquals(corrected, repair.draft().facts().get(2));

    FactInput unclear = new FactInput("no-logs", List.of("start"),
        FactKind.CONSTRAINT, Polarity.NEGATIVE, "Log the input body.");
    var unclearResult = RequirementCaptureEditor.apply(accepted,
        new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(unclear), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), null));
    assertFalse(unclearResult.accepted());
    assertTrue(unclearResult.issues().stream().anyMatch(issue ->
        "REQUIREMENT_COVERAGE_GAP".equals(issue.code())
            && "/draft/facts/2/text".equals(issue.path())));
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
