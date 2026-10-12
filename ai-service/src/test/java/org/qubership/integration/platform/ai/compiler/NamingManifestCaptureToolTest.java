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
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.compiler.NamingManifestCaptureTool.NamingCapture;
import org.qubership.integration.platform.ai.compiler.NamingManifestCaptureTool.RoleLabel;

class NamingManifestCaptureToolTest {

  private static final String CONVERSATION_ID = "conv-naming";

  private CaptureAttemptFeedbackStore feedbackStore;
  private CaptureSession session;
  private NamingManifestCaptureTool tool;

  @BeforeEach
  void setUp() {
    session = new CaptureSession();
    feedbackStore = new CaptureAttemptFeedbackStore();
    tool = new NamingManifestCaptureTool(session, new ObjectMapper(), feedbackStore);
    org.jboss.logmanager.MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
  }

  @Test
  void repeatIsBlockedBeforeNamingConversion() {
    feedbackStore.beginScope(CONVERSATION_ID, "", java.util.Map.of("inputs", "v1"));
    var invalid = new NamingCapture(1, " ", List.of(), List.of(), List.of());
    assertTrue(tool.captureNamingManifest(invalid).contains("chainName"));
    assertThrows(org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException.class,
        () -> tool.captureNamingManifest(invalid));
    assertFalse(session.isPresent(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID)));
  }

  @Test
  void capturesNamingManifest() {
    CaptureValidationException terminal =
        assertThrows(CaptureValidationException.class, () -> tool.captureNamingManifest(validManifest()));

    assertTrue(terminal.getMessage().contains("captured"));
    assertEquals(
        "customer-events-chain",
        session
            .get(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID), NamingManifest.class)
            .orElseThrow()
            .chainName());
    assertEquals("HTTP Trigger", session
        .get(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID), NamingManifest.class)
        .orElseThrow().labelsByRoleId().get("entry"));
  }

  @Test
  void rejectsManifestWithoutChainName() {
    String result =
        tool.captureNamingManifest(new NamingCapture(1, " ", List.of(new RoleLabel("entry", "HTTP Trigger")), List.of(), List.of()));

    assertTrue(result.contains("chainName"));
    assertFalse(session.isPresent(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID)));
  }

  @Test
  void duplicateCapturePreservesFirstManifest() {
    NamingCapture first = validManifest();
    assertThrows(CaptureValidationException.class, () -> tool.captureNamingManifest(first));

    assertThrows(CaptureValidationException.class, () -> tool.captureNamingManifest(new NamingCapture(1, "other", List.of(new RoleLabel("entry", "Other")), List.of(), List.of())));
    assertEquals(
        first.chainName(),
        session
            .get(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID), NamingManifest.class)
            .orElseThrow().chainName());
  }

  @Test
  void captureSessionRejectsWrongRuntimeTypeForNamingSlot() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                session.accept(
                    CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID),
                    "not-manifest",
                    "ok",
                    "dup"));

    assertTrue(thrown.getMessage().contains("does not match slot"));
  }

  @Test
  void rejectsDuplicateRoleIds() {
    String result = tool.captureNamingManifest(new NamingCapture(1, "Example",
        List.of(new RoleLabel("entry", "First"), new RoleLabel("entry", "Second")),
        List.of(), List.of()));

    assertTrue(result.contains("unique roleId"));
    assertFalse(session.isPresent(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID)));
  }

  private static NamingCapture validManifest() {
    return new NamingCapture(
        1,
        "customer-events-chain",
        List.of(new RoleLabel("entry", "HTTP Trigger")),
        java.util.List.of("fact-1"),
        java.util.List.of());
  }
}
