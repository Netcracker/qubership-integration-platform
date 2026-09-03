package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.llm.agent.MappingTurnAgent;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.IntentChange;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.Kind;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.RuleChange;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.QuerySelector;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Query;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class MappingTurnInterpreterTest {

  @Test
  void noneAndBlankMessagesDoNotCreateChanges() {
    MappingTurnInterpreter interpreter = interpreter(unusedCapture());
    assertTrue(((MappingTurnResult.Changes) interpreter.interpret(rockyBrief(), "")).operations().isEmpty());
    assertTrue(
        ((MappingTurnResult.Changes)
                interpreter.fromCapture(
                    new MappingTurnCapture(Kind.NONE, List.of(), List.of(), "", List.of()),
                    rockyFlow()))
            .operations()
            .isEmpty());
  }

  @Test
  void twoIntentChangesResolveFriendlyOperationNames() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(
                new IntentChange(
                    "onTaskStart",
                    "createTask",
                    List.of(new MappingIntentRule("name", "Subject", null)),
                    null),
                new IntentChange(
                    "createTask",
                    "onTaskResult",
                    List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")),
                    "SCRIPT")),
            List.of(),
            "",
            List.of());

    MappingTurnResult result = interpreter(unusedCapture()).fromCapture(capture, rockyFlow());

    MappingTurnResult.Changes changes = assertInstanceOf(MappingTurnResult.Changes.class, result);
    assertEquals(2, changes.operations().size());
    AddIntent request = assertInstanceOf(AddIntent.class, changes.operations().getFirst());
    AddIntent response = assertInstanceOf(AddIntent.class, changes.operations().get(1));
    assertEquals("task-start", request.sourceRef());
    assertEquals("create-task", request.targetRef());
    assertEquals("create-task", response.sourceRef());
    assertEquals("task-result", response.targetRef());
    assertEquals("SCRIPT", response.implementationPreference());
  }

  @Test
  void addIntentForUndescribedRemainderHopIsOmitted() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(
                new IntentChange(
                    "task-start",
                    "create-task",
                    List.of(new MappingIntentRule("name", "Subject", null)),
                    null),
                new IntentChange(
                    "create-task",
                    "task-result",
                    List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")),
                    null)),
            List.of(),
            "",
            List.of());

    MappingTurnResult result =
        interpreter(unusedCapture())
            .fromCapture(
                capture,
                rockyFlow(),
                "Map task-start -> create-task: copy name to Subject.");

    MappingTurnResult.Changes changes = assertInstanceOf(MappingTurnResult.Changes.class, result);
    assertEquals(1, changes.operations().size());
    AddIntent request = assertInstanceOf(AddIntent.class, changes.operations().getFirst());
    assertEquals("task-start", request.sourceRef());
    assertEquals("create-task", request.targetRef());
  }

  @Test
  void loneAddIntentForAnotherHopIsOmitted() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(
                new IntentChange(
                    "create-task",
                    "task-result",
                    List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")),
                    null)),
            List.of(),
            "",
            List.of());

    MappingTurnResult result =
        interpreter(unusedCapture())
            .fromCapture(
                capture,
                rockyFlow(),
                "Map task-start -> create-task: copy name to Subject.");

    MappingTurnResult.Changes changes = assertInstanceOf(MappingTurnResult.Changes.class, result);
    assertTrue(changes.operations().isEmpty());
  }

  @Test
  void gapModeOmitsAMissingInteractionWithoutRollingBackAValidSibling() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(
                new IntentChange(
                    "task-start",
                    "create-task",
                    List.of(new MappingIntentRule("name", "Subject", null)),
                    null),
                new IntentChange(
                    "missing-step",
                    "task-result",
                    List.of(new MappingIntentRule("id", "executionId", null)),
                    null)),
            List.of(),
            "",
            List.of());

    MappingTurnResult result =
        interpreter(capture)
            .interpretGap(
                rockyBrief(), "Map task-start -> create-task: copy name to Subject.");

    MappingTurnResult.Changes changes = assertInstanceOf(MappingTurnResult.Changes.class, result);
    assertEquals(1, changes.operations().size());
    AddIntent valid = assertInstanceOf(AddIntent.class, changes.operations().getFirst());
    assertEquals("task-start", valid.sourceRef());
    assertEquals("create-task", valid.targetRef());
  }

  @Test
  void gapModeKeepsADescribedMissingInteractionForValidationFeedback() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(
                new IntentChange(
                    "missing-step",
                    "task-result",
                    List.of(new MappingIntentRule("id", "executionId", null)),
                    null)),
            List.of(),
            "",
            List.of());

    MappingTurnResult result =
        interpreter(capture)
            .interpretGap(
                rockyBrief(), "Map missing-step -> task-result: copy id to executionId.");

    MappingTurnResult.Changes changes = assertInstanceOf(MappingTurnResult.Changes.class, result);
    AddIntent invalid = assertInstanceOf(AddIntent.class, changes.operations().getFirst());
    assertEquals("missing-step", invalid.sourceRef());
    assertEquals("task-result", invalid.targetRef());
  }

  @Test
  void addRuleUsesExistingIntentId() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(),
            List.of(new RuleChange("map-task-start-to-create-task", "", "Status", "Set to Not Started.")),
            "",
            List.of());

    MappingTurnResult result = interpreter(unusedCapture()).fromCapture(capture, rockyFlow());

    MappingTurnResult.Changes changes = assertInstanceOf(MappingTurnResult.Changes.class, result);
    AddRule add = assertInstanceOf(AddRule.class, changes.operations().getFirst());
    assertEquals("map-task-start-to-create-task", add.mappingIntentId());
    assertEquals("Status", add.targetPath());
  }

  @Test
  void ambiguousOperationNameBecomesClarification() {
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("call-a", Direction.OUTBOUND, "OM", "onTaskResult", ""),
                new Interaction("call-b", Direction.OUTBOUND, "WFM", "onTaskResult", "")),
            List.of(new Transition("call-a", "call-b")));
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(
                new IntentChange(
                    "onTaskResult",
                    "call-b",
                    List.of(new MappingIntentRule("id", "executionId", null)),
                    null)),
            List.of(),
            "",
            List.of());

    MappingTurnResult result = interpreter(unusedCapture()).fromCapture(capture, flow);

    Clarification clarification = assertInstanceOf(Clarification.class, result);
    assertEquals("AMBIGUOUS_TRANSITION", clarification.reason());
    assertTrue(clarification.candidates().contains("call-a"));
    assertTrue(clarification.candidates().contains("call-b"));
  }

  @Test
  void interpretDelegatesToTheAgent() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(
                new IntentChange(
                    "task-start",
                    "create-task",
                    List.of(new MappingIntentRule("name", "Subject", null)),
                    null)),
            List.of(),
            "",
            List.of());
    MappingTurnInterpreter interpreter = interpreter(capture);

    MappingTurnResult result = interpreter.interpret(rockyBrief(), "map name to Subject");

    MappingTurnResult.Changes changes = assertInstanceOf(MappingTurnResult.Changes.class, result);
    AddIntent add = assertInstanceOf(AddIntent.class, changes.operations().getFirst());
    assertEquals("task-start", add.sourceRef());
    assertEquals("create-task", add.targetRef());
  }

  @Test
  void queryCaptureResolvesUniqueOperationNames() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.QUERY,
            List.of(),
            List.of(),
            "",
            List.of(),
            new QuerySelector(
                null, "onTaskStart", "createTask", "name", "Subject", false, "ANY"));

    MappingTurnResult result = interpreter(unusedCapture()).fromCapture(capture, rockyFlow());

    Query query = assertInstanceOf(Query.class, result);
    assertEquals("task-start", query.selector().sourceRef());
    assertEquals("create-task", query.selector().targetRef());
    assertEquals("name", query.selector().sourcePath());
    assertEquals("Subject", query.selector().targetPath());
  }

  @Test
  void queryCaptureAmbiguousOperationBecomesClarification() {
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("call-a", Direction.OUTBOUND, "OM", "onTaskResult", ""),
                new Interaction("call-b", Direction.OUTBOUND, "WFM", "onTaskResult", "")),
            List.of(new Transition("call-a", "call-b")));
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.QUERY,
            List.of(),
            List.of(),
            "",
            List.of(),
            new QuerySelector(null, "onTaskResult", null, null, "id", false, "ANY"));

    MappingTurnResult result = interpreter(unusedCapture()).fromCapture(capture, flow);

    Clarification clarification = assertInstanceOf(Clarification.class, result);
    assertEquals("AMBIGUOUS_TRANSITION", clarification.reason());
    assertTrue(clarification.candidates().contains("call-a"));
    assertTrue(clarification.candidates().contains("call-b"));
  }

  @Test
  void queryCaptureMissingNameBecomesClarification() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.QUERY,
            List.of(),
            List.of(),
            "",
            List.of(),
            new QuerySelector(null, "unknownOp", "createTask", null, null, false, "ANY"));

    MappingTurnResult result = interpreter(unusedCapture()).fromCapture(capture, rockyFlow());

    Clarification clarification = assertInstanceOf(Clarification.class, result);
    assertEquals("MISSING_TRANSITION", clarification.reason());
    assertTrue(clarification.candidates().contains("unknownOp"));
  }

  @Test
  void updateAndDeleteCapturesBecomeTypedOperations() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(),
            List.of(),
            "",
            List.of(),
            null,
            List.of(
                new RuleChange(
                    "map-task-start-to-create-task", "title", "Subject", "{title} task", "Summary", null, null)),
            List.of(new RuleChange("map-task-start-to-create-task", "", "Status", null)),
            List.of(new IntentChange("createTask", "onTaskResult", List.of(), null)));
    RequirementBrief brief = briefWithRequestAndResponse();

    MappingTurnResult result = interpreter(unusedCapture()).fromCapture(capture, brief);

    MappingTurnResult.Changes changes = assertInstanceOf(MappingTurnResult.Changes.class, result);
    assertEquals(3, changes.operations().size());
    UpdateRule update = assertInstanceOf(UpdateRule.class, changes.operations().getFirst());
    assertEquals("Subject", update.targetPath());
    assertEquals("Summary", update.newTargetPath());
    DeleteRule delete = assertInstanceOf(DeleteRule.class, changes.operations().get(1));
    assertEquals("Status", delete.targetPath());
    DeleteIntent deleteIntent = assertInstanceOf(DeleteIntent.class, changes.operations().get(2));
    assertEquals("map-create-task-to-task-result", deleteIntent.mappingIntentId());
  }

  @Test
  void omittedTransitionForASharedTargetBecomesClarification() {
    MappingTurnCapture capture =
        new MappingTurnCapture(
            Kind.CHANGES,
            List.of(),
            List.of(),
            "",
            List.of(),
            null,
            List.of(new RuleChange("", "title", "id", null)),
            List.of(),
            List.of());
    RequirementBrief brief = briefWithRequestAndResponse();

    MappingTurnResult result = interpreter(unusedCapture()).fromCapture(capture, brief);

    Clarification clarification = assertInstanceOf(Clarification.class, result);
    assertEquals("OMITTED_TRANSITION", clarification.reason());
    assertTrue(clarification.candidates().size() >= 1);
  }

  private static MappingTurnInterpreter interpreter(MappingTurnCapture capture) {
    MappingTurnAgent agent = (flow, intents, message) -> capture;
    return new MappingTurnInterpreter(agent);
  }

  private static MappingTurnCapture unusedCapture() {
    return new MappingTurnCapture(Kind.NONE, List.of(), List.of(), "", List.of());
  }

  private static RequirementBrief rockyBrief() {
    return new RequirementBrief(
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Consume onTaskStart, create a Salesforce task, publish onTaskResult",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withFlow(rockyFlow());
  }

  private static RequirementBrief briefWithRequestAndResponse() {
    return MappingTurnApplicator.apply(
            rockyBrief(),
            MappingTurnResult.changes(
                new AddIntent(
                    "task-start", "create-task", List.of(new MappingIntentRule("name", "Subject", null))),
                new AddIntent(
                    "create-task",
                    "task-result",
                    List.of(new MappingIntentRule("id", "executionId", null)))))
        .brief();
  }

  private static RequirementFlow rockyFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
            new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
            new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
        List.of(
            new Transition("task-start", "create-task"),
            new Transition("create-task", "task-result")));
  }
}
