package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class ValidationResultToolTest {

  private static final String CONVERSATION_ID = "conv-validation-tool";

  private CaptureSession captureSession;
  private CaptureAttemptFeedbackStore feedbackStore;
  private ValidationResultTool tool;

  @BeforeEach
  void setUp() {
    MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, "plan-validator");
    captureSession = new CaptureSession();
    feedbackStore = new CaptureAttemptFeedbackStore();
    tool =
        new ValidationResultTool(
            captureSession,
            feedbackStore,
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));
  }

  @AfterEach
  void tearDown() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
    MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
  }

  @Test
  void storesValidCapture() {
    ValidationResult capture =
        new ValidationResult(true, List.of(), "Plan validation passed");

    String result = tool.captureValidationResult(capture);

    assertTrue(result.contains("Validation result captured"));
    assertTrue(captureSession.get(CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, CONVERSATION_ID), ValidationResult.class).isPresent());
    assertTrue(captureSession.get(CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, CONVERSATION_ID), ValidationResult.class).orElseThrow().valid());
  }

  @Test
  void rejectsBlankSummary() {
    ValidationResult capture = new ValidationResult(true, List.of(), " ");

    String result = tool.captureValidationResult(capture);

    assertTrue(result.contains("summary must be non-blank"));
    assertFalse(captureSession.get(CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, CONVERSATION_ID), ValidationResult.class).isPresent());
    assertTrue(feedbackStore.lastValidationFailure(CONVERSATION_ID, "plan-validator").isPresent());
  }

  @Test
  void rejectsValidFalseWithoutIssues() {
    ValidationResult capture = new ValidationResult(false, List.of(), "Invalid plan");

    String result = tool.captureValidationResult(capture);

    assertTrue(result.contains("valid=false requires at least one issue"));
    assertFalse(captureSession.get(CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, CONVERSATION_ID), ValidationResult.class).isPresent());
  }

  @Test
  void normalizesWarningOnlyValidFalseToValidTrue() {
    ValidationIssue warning =
        new ValidationIssue(
            "validation-1",
            ValidationSeverity.WARNING,
            "Advisory note",
            "plan-validator",
            List.of(),
            List.of(),
            "Review");
    ValidationResult capture =
        new ValidationResult(false, List.of(warning), "Advisory findings only");

    tool.captureValidationResult(capture);

    ValidationResult stored = captureSession.get(CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, CONVERSATION_ID), ValidationResult.class).orElseThrow();
    assertTrue(stored.valid());
    assertEquals(1, stored.issues().size());
  }

  @Test
  void duplicateCaptureThrowsTerminalValidationExceptionAndPreservesFirstValue() {
    CaptureKey key =
        CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, CONVERSATION_ID);
    ValidationResult first =
        new ValidationResult(true, List.of(), "First validation passed");
    ValidationResult second =
        new ValidationResult(true, List.of(), "Second validation passed");

    String firstResult = tool.captureValidationResult(first);
    ValidationResult firstStored =
        captureSession.get(key, ValidationResult.class).orElseThrow();

    CaptureValidationException duplicate =
        assertThrows(
            CaptureValidationException.class,
            () -> tool.captureValidationResult(second));

    assertTrue(firstResult.contains("finish this turn"));
    assertTrue(duplicate.getMessage().contains("already captured"));
    assertTrue(duplicate.getMessage().contains("finish this turn"));
    assertSame(
        firstStored,
        captureSession.get(key, ValidationResult.class).orElseThrow());
  }
}
