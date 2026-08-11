package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;

class NamingManifestCaptureToolTest {

  private static final String CONVERSATION_ID = "conv-naming";

  private CaptureSession session;
  private NamingManifestCaptureTool tool;

  @BeforeEach
  void setUp() {
    session = new CaptureSession();
    tool = new NamingManifestCaptureTool(session, new ObjectMapper(), new CaptureAttemptFeedbackStore());
    org.jboss.logmanager.MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
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
  }

  @Test
  void rejectsManifestWithoutChainName() {
    String result =
        tool.captureNamingManifest(new NamingManifest(1, " ", java.util.Map.of("entry", "HTTP Trigger"), java.util.List.of(), java.util.List.of()));

    assertTrue(result.contains("chainName"));
    assertFalse(session.isPresent(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID)));
  }

  @Test
  void duplicateCapturePreservesFirstManifest() {
    NamingManifest first = validManifest();
    assertThrows(CaptureValidationException.class, () -> tool.captureNamingManifest(first));

    assertThrows(CaptureValidationException.class, () -> tool.captureNamingManifest(new NamingManifest(1, "other", java.util.Map.of("entry", "Other"), java.util.List.of(), java.util.List.of())));
    assertEquals(
        first,
        session
            .get(CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, CONVERSATION_ID), NamingManifest.class)
            .orElseThrow());
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

  private static NamingManifest validManifest() {
    return new NamingManifest(
        1,
        "customer-events-chain",
        java.util.Map.of("entry", "HTTP Trigger"),
        java.util.List.of("fact-1"),
        java.util.List.of());
  }
}
