package org.qubership.integration.platform.ai.compiler.capture.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CaptureFailurePolicyTest {

  private final CaptureFailurePolicy policy = new CaptureFailurePolicy();

  @Test
  void correctableFirstFailIsSoftWithOuterAllowed() {
    CaptureFailureDecision decision =
        policy.decide(
            CaptureFailureClass.CORRECTABLE, CaptureAttemptState.forFingerprint(false), "shape");

    assertTrue(decision.softToolResult());
    assertFalse(decision.throwCve());
    assertTrue(decision.outerAllowed());
    assertEquals(CaptureFailureClass.CORRECTABLE, decision.failureClass());
  }

  @Test
  void correctableAfterSoftBecomesIdenticalSpamAndRefusesTheOuterTurn() {
    CaptureFailureDecision decision =
        policy.decide(
            CaptureFailureClass.CORRECTABLE, CaptureAttemptState.forFingerprint(true), "shape");

    assertFalse(decision.softToolResult());
    assertTrue(decision.throwCve());
    assertFalse(decision.outerAllowed());
    assertEquals(CaptureFailureClass.IDENTICAL_SPAM, decision.failureClass());
  }

  @Test
  void permanentNeverSoftAndOuterForbidden() {
    CaptureFailureDecision decision =
        policy.decide(
            CaptureFailureClass.PERMANENT, CaptureAttemptState.forFingerprint(false), "ownership");

    assertFalse(decision.softToolResult());
    assertTrue(decision.throwCve());
    assertFalse(decision.outerAllowed());
    assertEquals(CaptureFailureClass.PERMANENT, decision.failureClass());
  }

  @Test
  void permanentIgnoresPriorSoftCredit() {
    CaptureFailureDecision decision =
        policy.decide(
            CaptureFailureClass.PERMANENT, CaptureAttemptState.forFingerprint(true), "ownership");

    assertFalse(decision.softToolResult());
    assertFalse(decision.outerAllowed());
  }

  @Test
  void identicalSpamIsImmediateCveAndRefusesTheOuterTurn() {
    CaptureFailureDecision decision =
        policy.decide(
            CaptureFailureClass.IDENTICAL_SPAM, CaptureAttemptState.forFingerprint(true), "spam");

    assertTrue(decision.throwCve());
    assertFalse(decision.outerAllowed());
  }

  @Test
  void toolArgumentsForbidsOuter() {
    CaptureFailureDecision decision =
        policy.decide(
            CaptureFailureClass.TOOL_ARGUMENTS,
            CaptureAttemptState.forFingerprint(false),
            "bad json");

    assertFalse(decision.outerAllowed());
    assertFalse(decision.throwCve());
  }

  @Test
  void acceptedAndDuplicateTerminateWithCve() {
    assertTrue(
        policy
            .decide(CaptureFailureClass.ACCEPTED, CaptureAttemptState.forFingerprint(false), "ok")
            .throwCve());
    assertTrue(
        policy
            .decide(
                CaptureFailureClass.DUPLICATE, CaptureAttemptState.forFingerprint(false), "dup")
            .throwCve());
  }
}
