package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;

class RecoveryOutcomeTelemetryTest {

  @Test
  void presentedEventRecordsCategoryOfferedActionsAndIdentityWithoutRawEvidence() {
    RecoveryOutcomeTelemetry telemetry = new RecoveryOutcomeTelemetry();
    String identity = "sig-rate-limit";

    telemetry.recordPresented(
        "run-1", PipelineGates.RECOVERY_RETRY_TECHNICAL, identity, "SSLHandshakeException");

    RecoveryOutcomeTelemetry.Event event = telemetry.events().getFirst();
    assertEquals(RecoveryOutcomeTelemetry.KIND_PRESENTED, event.kind());
    assertEquals("run-1", event.runId());
    assertEquals("temporary-technical-failure", event.category());
    assertEquals(
        List.of(ChatEvent.RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        event.offeredActions());
    assertEquals(identity, event.failureIdentity());
    assertEquals(1, event.attempt());
    assertFalse(event.offeredActions().contains("SSLHandshakeException"));
    assertFalse(event.toString().contains("SSLHandshakeException"));
  }

  @Test
  void selectedEventUsesTheSemanticActionNotAPipelineStageId() {
    RecoveryOutcomeTelemetry telemetry = new RecoveryOutcomeTelemetry();
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_REVISE_BRIEF, "sig-brief", "planning");
    telemetry.recordSelected("run-1", ChatEvent.EDIT_REQUIREMENTS_ACTION);

    RecoveryOutcomeTelemetry.Event selected = telemetry.events().get(1);
    assertEquals(RecoveryOutcomeTelemetry.KIND_SELECTED, selected.kind());
    assertEquals(ChatEvent.EDIT_REQUIREMENTS_ACTION, selected.selectedAction());
    assertFalse(selected.offeredActions().contains("planning"));
    assertFalse(selected.offeredActions().contains("requirement-analysis"));
  }

  @Test
  void repeatingTheSameIdentityIsNoProgress() {
    RecoveryOutcomeTelemetry telemetry = new RecoveryOutcomeTelemetry();
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_RETRY_TECHNICAL, "sig-a", null);
    telemetry.recordSelected("run-1", ChatEvent.RETRY_CREATION_ACTION);
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_RETRY_TECHNICAL, "sig-a", null);

    RecoveryOutcomeTelemetry.Event outcome =
        telemetry.events().stream()
            .filter(event -> RecoveryOutcomeTelemetry.KIND_OUTCOME.equals(event.kind()))
            .reduce((first, second) -> second)
            .orElseThrow();
    assertEquals(RecoveryOutcomeTelemetry.OUTCOME_NO_PROGRESS, outcome.outcome());
    assertEquals(Boolean.FALSE, outcome.identityChanged());
    assertFalse(outcome.reachedMaterialization());
    assertEquals(2, telemetry.events().getLast().attempt());
  }

  @Test
  void aChangedIdentityIsPartialProgress() {
    RecoveryOutcomeTelemetry telemetry = new RecoveryOutcomeTelemetry();
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_RETRY_TECHNICAL, "sig-a", null);
    telemetry.recordSelected("run-1", ChatEvent.RETRY_CREATION_ACTION);
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_INTERNAL, "sig-b", null);

    RecoveryOutcomeTelemetry.Event outcome =
        telemetry.events().stream()
            .filter(event -> RecoveryOutcomeTelemetry.KIND_OUTCOME.equals(event.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals(RecoveryOutcomeTelemetry.OUTCOME_PARTIAL_PROGRESS, outcome.outcome());
    assertEquals(Boolean.TRUE, outcome.identityChanged());
    assertFalse(outcome.reachedMaterialization());
  }

  @Test
  void materializationAfterRecoveryIsSuccess() {
    RecoveryOutcomeTelemetry telemetry = new RecoveryOutcomeTelemetry();
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_RETRY_TECHNICAL, "sig-a", null);
    telemetry.recordSelected("run-1", ChatEvent.RETRY_CREATION_ACTION);
    telemetry.recordSuccess("run-1");

    RecoveryOutcomeTelemetry.Event outcome = telemetry.events().getLast();
    assertEquals(RecoveryOutcomeTelemetry.KIND_OUTCOME, outcome.kind());
    assertEquals(RecoveryOutcomeTelemetry.OUTCOME_SUCCESS, outcome.outcome());
    assertTrue(outcome.reachedMaterialization());
  }

  @Test
  void endingTheRunIsUserExit() {
    RecoveryOutcomeTelemetry telemetry = new RecoveryOutcomeTelemetry();
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_INTERNAL, "sig-a", null);
    telemetry.recordUserExit("run-1");

    List<RecoveryOutcomeTelemetry.Event> events = telemetry.events();
    RecoveryOutcomeTelemetry.Event selected = events.get(1);
    RecoveryOutcomeTelemetry.Event outcome = events.getLast();
    assertEquals(RecoveryOutcomeTelemetry.KIND_SELECTED, selected.kind());
    assertEquals(PipelineGates.STOP_WITH_REPORT_ACTION, selected.selectedAction());
    assertEquals(RecoveryOutcomeTelemetry.OUTCOME_USER_EXIT, outcome.outcome());
    assertFalse(outcome.reachedMaterialization());
  }

  @Test
  void presentedWithoutASelectionIsAbandonmentForTheOpenAttempt() {
    RecoveryOutcomeTelemetry telemetry = new RecoveryOutcomeTelemetry();
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_ENVIRONMENT, "sig-a", null);

    RecoveryOutcomeTelemetry.Event presented = telemetry.events().getFirst();
    assertEquals(RecoveryOutcomeTelemetry.KIND_PRESENTED, presented.kind());
    assertTrue(
        telemetry.events().stream()
            .noneMatch(event -> RecoveryOutcomeTelemetry.KIND_SELECTED.equals(event.kind())));
  }

  @Test
  void attemptsOnOneRunShareTheRunIdAndStayPrivacySafe() {
    RecoveryOutcomeTelemetry telemetry = new RecoveryOutcomeTelemetry();
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_RETRY_TECHNICAL, "sig-a", "secret-brief");
    telemetry.recordSelected("run-1", ChatEvent.RETRY_CREATION_ACTION);
    telemetry.recordPresented("run-1", PipelineGates.RECOVERY_RETRY_TECHNICAL, "sig-b", "secret-brief");

    for (RecoveryOutcomeTelemetry.Event event : telemetry.events()) {
      assertEquals("run-1", event.runId());
      assertFalse(event.toString().contains("secret-brief"));
    }
    assertEquals(1, telemetry.events().getFirst().attempt());
    assertEquals(2, telemetry.events().getLast().attempt());
  }
}
