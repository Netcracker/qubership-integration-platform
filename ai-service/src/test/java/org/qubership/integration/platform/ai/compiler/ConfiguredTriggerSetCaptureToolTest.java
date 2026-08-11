package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;

class ConfiguredTriggerSetCaptureToolTest {

  private static final String CONVERSATION_ID = "conv-trigger";

  private CaptureSession session;
  private ConfiguredTriggerSetCaptureTool tool;

  @BeforeEach
  void setUp() {
    session = new CaptureSession();
    tool =
        new ConfiguredTriggerSetCaptureTool(
            session, new ObjectMapper(), new CaptureAttemptFeedbackStore());
    org.jboss.logmanager.MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
  }

  @Test
  void capturesConfiguredTriggerSet() {
    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class, () -> tool.captureConfiguredTriggerSet(validTriggerSet()));

    assertTrue(terminal.getMessage().contains("captured"));
    assertEquals(
        1,
        session
            .get(
                CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID),
                ConfiguredTriggerSet.class)
            .orElseThrow()
            .triggers()
            .size());
  }

  @Test
  void rejectsConfiguredTriggerSetWithoutTriggers() {
    String result =
        tool.captureConfiguredTriggerSet(new ConfiguredTriggerSet(1, List.of(), List.of(), List.of()));

    assertTrue(result.contains("triggers"));
    assertFalse(
        session.isPresent(CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID)));
  }

  @Test
  void duplicateCapturePreservesFirstTriggerSet() {
    ConfiguredTriggerSet first = validTriggerSet();
    assertThrows(CaptureValidationException.class, () -> tool.captureConfiguredTriggerSet(first));

    assertThrows(
        CaptureValidationException.class,
        () ->
            tool.captureConfiguredTriggerSet(
                new ConfiguredTriggerSet(
                    1,
                    List.of(new ConfiguredTrigger("entry", "entry-2", "http-trigger", "Other", List.of())),
                    List.of(),
                    List.of())));
    assertEquals(
        first,
        session
            .get(
                CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID),
                ConfiguredTriggerSet.class)
            .orElseThrow());
  }

  @Test
  void captureSessionRejectsWrongRuntimeTypeForTriggerSlot() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                session.accept(
                    CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID),
                    "not-trigger-set",
                    "ok",
                    "dup"));

    assertTrue(thrown.getMessage().contains("does not match slot"));
  }

  private static ConfiguredTriggerSet validTriggerSet() {
    return new ConfiguredTriggerSet(
        1,
        List.of(
            new ConfiguredTrigger(
                "entry",
                "http-trigger-1",
                "http-trigger",
                "Customer API",
                List.of(new PlanProperty("contextPath", "/api/customers")))),
        List.of("fact-1"),
        List.of());
  }
}
