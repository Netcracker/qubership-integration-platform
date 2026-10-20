package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class HttpTriggerCaptureToolTest {
  @TempDir Path directory;
  private static final String CONVERSATION = "http-capture-test";

  @AfterEach void clear() {
    HttpTriggerCaptureSession.unbind(CONVERSATION);
    MDC.remove(ChatMdc.CONVERSATION_ID);
    MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
  }

  @Test void injectedReferenceIsRejectedBeforeANewNativeCaptureIsAccepted() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    mapper.writeValue(directory.resolve("plan.json").toFile(), Map.of(
        "schemaVersion", 1, "conversationId", CONVERSATION,
        "boundary", "checkpoint", "mutation", "http-semantic-reference"));
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, java.time.Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, java.time.Clock.systemUTC());
    var feedback = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(), artifacts, sessions);
    feedback.beginScope(CONVERSATION, "trigger", Map.of("inputs", "approved"));
    var session = new CaptureSession();
    var configured = new ConfiguredTriggerSetCaptureTool(session, mapper, feedback,
        DeterministicElementSchemaService.createForUnitTests(mapper));
    var tool = new HttpTriggerCaptureTool(configured, session, feedback, mapper);
    tool.recoveryControl = new E2eRecoveryTestControl(directory, mapper);
    HttpTriggerCaptureAdapterTest.binding();
    MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION);
    MDC.put(CompilerSkillMdc.CAPABILITY_ID, "trigger");
    var proposal = new HttpTriggerCapture(List.of(
        new HttpTriggerCapture.Endpoint("http-entry", "http-trigger-1", false)));

    var rejected = tool.capture(proposal, CONVERSATION);
    assertEquals("CAPTURE_SHAPE_INVALID", rejected.issues().getFirst().code());
    var history = artifacts.history(sessions.current(CONVERSATION).orElseThrow().compilationId(),
        CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK);
    assertEquals(1, history.size());
    assertNotEquals("http-trigger-1", history.getFirst().payload()
        .path("proposal").path("arguments").path("endpoints").get(0).path("semanticNodeId").asText());
    assertThrows(CaptureValidationException.class, () -> tool.capture(proposal, CONVERSATION));
    var accepted = mapper.readTree(directory.resolve("events/000002.json").toFile());
    assertEquals("CAPTURE_ACCEPTED", accepted.path("type").asText());
    assertEquals("http-trigger-1", accepted.path("proposal").path("endpoints").get(0).path("semanticNodeId").asText());
    assertEquals("/tasks", accepted.path("configuredTriggers").path("triggers").get(0).path("properties").get(0).path("value").asText());
    assertEquals("http-trigger-1", proposal.endpoints().getFirst().semanticNodeId());
    assertEquals("DUPLICATE_CAPTURE", tool.capture(proposal, CONVERSATION).issues().getFirst().code());
    try (var events = Files.list(directory.resolve("events"))) { assertEquals(2, events.count()); }
  }
}
