package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldFillPolicy;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;

class SelectedPatternToolTest {

  private static final String CONVERSATION_ID = "conv-pattern";

  private CaptureSession captureSession;
  private CaptureAttemptFeedbackStore feedbackStore;
  private SelectedPatternTool tool;

  @BeforeEach
  void setUp() {
    captureSession = new CaptureSession();
    feedbackStore = new CaptureAttemptFeedbackStore();
    CaptureFieldFillPolicy fillPolicy =
        new CaptureFieldFillPolicy(List.of(new SelectedPatternCaptureFillRules()));
    CaptureToolOutcomeGateway gateway =
        new CaptureToolOutcomeGateway(feedbackStore.fingerprintStore(), feedbackStore);
    tool =
        new SelectedPatternTool(
            captureSession, new ObjectMapper(), feedbackStore, fillPolicy, gateway);
  }

  private java.util.Optional<SelectedPattern> getPattern(String conversationId) {
    return captureSession.get(
        CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, conversationId), SelectedPattern.class);
  }

  private java.util.Optional<ElementSkeleton> getSkeleton(String conversationId) {
    return captureSession.get(
        CaptureKey.conversation(CaptureSlot.ELEMENT_SKELETON, conversationId), ElementSkeleton.class);
  }

  @Test
  void capturesPatternAndSkeletonAtomically() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);

    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class,
            () -> tool.captureSelectedPattern(validPatternCaptureWithSkeleton()));

    assertTrue(terminal.getMessage().contains("Selected pattern captured"));
    assertTrue(
        captureSession.isPresent(
            CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID)));
    assertTrue(
        captureSession.isPresent(
            CaptureKey.conversation(CaptureSlot.ELEMENT_SKELETON, CONVERSATION_ID)));
  }

  @Test
  void successfulCaptureTerminatesStreamingToolLoop() {
    // CaptureValidationException implements PreventsErrorHandlerExecution so quarkus-langchain4j
    // aborts the agent stream immediately. Without this, harvest waits for an LLM end-turn that
    // may never arrive (live hang after GP-01 + skeleton).
    assertTrue(
        new CaptureValidationException("x")
            instanceof io.quarkiverse.langchain4j.runtime.PreventsErrorHandlerExecution);

    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);

    assertThrows(
        CaptureValidationException.class,
        () -> tool.captureSelectedPattern(validPatternCaptureWithSkeleton()));
    assertTrue(
        captureSession.isPresent(
            CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID)));
  }

  @Test
  void fillsNullPatternIdFromValidNestedSkeletonAndStores() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);

    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.captureSelectedPattern(
                    new SelectedPatternCapture(
                        null,
                        "Protected Request-Response",
                        "Production HTTP API",
                        "http-trigger -> try-catch-finally-2 -> try-2",
                        List.of("cip-pattern-selector"),
                        existingSkeleton())));

    assertTrue(terminal.getMessage().contains("Selected pattern captured"));
    SelectedPattern stored = getPattern(CONVERSATION_ID).orElseThrow();
    assertEquals("GP-01", stored.patternId());
    assertTrue(feedbackStore.lastPlanFailure(CONVERSATION_ID).isEmpty());
  }

  @Test
  void bothBlankPatternIdsSoftFailWithoutHints() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);

    ElementSkeleton blankNested =
        new ElementSkeleton(
            1,
            null,
            List.of("entry"),
            List.of(new ElementRole("entry", "http-trigger", null, 1, 1)),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    String result =
        tool.captureSelectedPattern(
            new SelectedPatternCapture(
                null, "name", "reason", "summary", List.of(), blankNested));

    assertTrue(result.contains("GP-01 through GP-07"));
    assertFalse(getPattern(CONVERSATION_ID).isPresent());
    CaptureAttemptFeedback feedback = feedbackStore.lastPlanFailure(CONVERSATION_ID).orElseThrow();
    assertEquals(CaptureFailureClass.CORRECTABLE, feedback.failureClass());
    assertTrue(feedback.fieldHints().isEmpty());
  }

  @Test
  void invalidNestedPatternIdDoesNotFillOrHint() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);

    ElementSkeleton invalidNested =
        new ElementSkeleton(
            1,
            "GP-99",
            List.of("entry"),
            List.of(new ElementRole("entry", "http-trigger", null, 1, 1)),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    String result =
        tool.captureSelectedPattern(
            new SelectedPatternCapture(
                null, "name", "reason", "summary", List.of(), invalidNested));

    assertTrue(result.contains("GP-01 through GP-07"));
    assertFalse(getPattern(CONVERSATION_ID).isPresent());
    CaptureAttemptFeedback feedback = feedbackStore.lastPlanFailure(CONVERSATION_ID).orElseThrow();
    assertEquals(CaptureFailureClass.CORRECTABLE, feedback.failureClass());
    assertTrue(feedback.fieldHints().isEmpty());
  }

  @Test
  void mismatchedPatternIdAndSkeletonDoesNotOverride() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);

    String result =
        tool.captureSelectedPattern(
            new SelectedPatternCapture(
                "GP-02",
                "name",
                "reason",
                "summary",
                List.of(),
                existingSkeleton()));

    assertTrue(result.contains("must match patternId"));
    assertFalse(getPattern(CONVERSATION_ID).isPresent());
    CaptureAttemptFeedback feedback = feedbackStore.lastPlanFailure(CONVERSATION_ID).orElseThrow();
    assertEquals(CaptureFailureClass.CORRECTABLE, feedback.failureClass());
    assertTrue(feedback.fieldHints().isEmpty());
  }

  @Test
  void duplicateSkeletonLeavesPatternAndSkeletonUnchanged() {
    org.jboss.logmanager.MDC.put(org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
    ElementSkeleton existing = existingSkeleton();
    captureSession.accept(
        CaptureKey.conversation(CaptureSlot.ELEMENT_SKELETON, CONVERSATION_ID),
        existing,
        "ok",
        "dup");

    assertThrows(
        CaptureValidationException.class, () -> tool.captureSelectedPattern(validPatternCaptureWithSkeleton()));

    assertFalse(
        captureSession.isPresent(
            CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID)));
    assertEquals(existing, getSkeleton(CONVERSATION_ID).orElseThrow());
  }

  @Test
  void rejectsCaptureWithoutElementSkeleton() {
    org.jboss.logmanager.MDC.put(org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);

    String result =
        tool.captureSelectedPattern(
            new SelectedPatternCapture(
                "GP-01", "Protected Request-Response", "reason", "summary", List.of()));

    assertTrue(result.contains("elementSkeleton"));
    assertFalse(
        captureSession.isPresent(
            CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, CONVERSATION_ID)));
    assertFalse(
        captureSession.isPresent(
            CaptureKey.conversation(CaptureSlot.ELEMENT_SKELETON, CONVERSATION_ID)));
  }

  @Test
  void rejectsInvalidPatternId() {
    org.jboss.logmanager.MDC.put(org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, CONVERSATION_ID);

    String result =
        tool.captureSelectedPattern(
            new SelectedPatternCapture("GP-99", "Invalid", "reason", "summary", List.of()));

    assertTrue(result.contains("GP-01 through GP-07"));
    assertFalse(getPattern(CONVERSATION_ID).isPresent());
  }

  private static SelectedPatternCapture validPatternCaptureWithSkeleton() {
    return new SelectedPatternCapture(
        "GP-01",
        "Protected Request-Response",
        "Production HTTP API",
        "http-trigger -> try-catch-finally-2 -> try-2",
        List.of("cip-pattern-selector"),
        existingSkeleton());
  }

  private static ElementSkeleton existingSkeleton() {
    return new ElementSkeleton(
        1,
        "GP-01",
        List.of("entry"),
        List.of(new ElementRole("entry", "http-trigger", null, 1, 1)),
        List.of(),
        List.of("cip-pattern-selector"),
        List.of("fact-1"),
        List.of());
  }
}
