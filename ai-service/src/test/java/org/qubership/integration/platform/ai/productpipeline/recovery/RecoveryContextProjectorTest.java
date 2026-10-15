package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class RecoveryContextProjectorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void nativeCaptureEvidenceSurvivesSerializationAndRecoveryProjection() throws Exception {
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback(
        org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind.VALIDATION, "Invalid amendment",
        org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass.IDENTICAL_SPAM,
        false, List.of(new org.qubership.integration.platform.ai.compiler.capture.CaptureFieldHint(
            "httpMethod", "capability.httpMethod", "GET")), "[]", true);
    var base = new Reference(Kind.REQUIREMENT_DRAFT, "accepted", "base-hash");
    var rejected = new Reference(Kind.REQUIREMENT_CAPTURE_ATTEMPT, "rejected", "candidate-hash");
    var consequence = new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
        "AUTHOR_AMENDMENT_REQUIRED", "Retain GET", true);
    var capture = new org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence(
        feedback, base, rejected, List.of(consequence));
    var evidence = new RecoveryEvidence(1, "failure", "CONTRACT_SHAPE", "requirement-discovery", "requirement-discovery",
        null, null, List.of(rejected), List.of(), null, List.of(), List.of(), List.of(base), List.of("source"), false, capture);
    var restored = MAPPER.readValue(MAPPER.writeValueAsBytes(evidence), RecoveryEvidence.class);
    assertEquals(capture, restored.captureFailure());
    var json = MAPPER.readTree(RecoveryContextProjector.project(new RecoveryContext(restored, null, null, "en"), MAPPER));
    assertEquals(MAPPER.valueToTree(capture), json.at("/evidence/captureFailure"));
  }

  @Test
  void projectionKeepsEverySourceMessageInOrderWithoutSerializingInputTimestamps() throws Exception {
    var inputs = List.of(
        new org.qubership.integration.platform.ai.productpipeline.artifact.UserInput("first", "conversation-source",
            "Use POST /orders", java.time.Instant.EPOCH),
        new org.qubership.integration.platform.ai.productpipeline.artifact.UserInput("second", "conversation-source",
            "Change the URL to /orders-v2", java.time.Instant.now()));
    var context = new RecoveryContext(null, sampleBrief(), null, "en", inputs);
    var root = MAPPER.readTree(RecoveryContextProjector.project(context, MAPPER));
    assertEquals(2, root.path("authorInputs").size());
    assertEquals("Use POST /orders", root.at("/authorInputs/0/text").asText());
    assertEquals("second", root.at("/authorInputs/1/inputId").asText());
    assertEquals("Change the URL to /orders-v2", root.at("/authorInputs/1/text").asText());
  }

  @Test
  void projectionKeepsAuthoredInputsConstraintsAndDraftText() throws Exception {
    var brief = new RequirementBrief("Goal", List.of("Original event"), List.of("Preserve ordering"),
        List.of(), List.of(), "summary", "draft-1", "Full author instruction", List.of());
    var context = new RecoveryContext(null, brief, null, "en");
    var root = MAPPER.readTree(RecoveryContextProjector.project(context, MAPPER));
    assertEquals(MAPPER.valueToTree(brief), root.path("approvedBrief"));
  }

  @Test
  void oversizedContextIsRefusedBeforeAnotherModelCallWithoutLosingSavedData() {
    var artifact = java.util.Map.of("script", "x".repeat(RecoveryContextProjector.CHAR_BUDGET));
    var context = new RecoveryContext(null, sampleBrief(), artifact, "en");
    assertThrows(IllegalArgumentException.class, () -> RecoveryContextProjector.project(context, MAPPER));
    assertEquals(artifact, context.rejectedArtifact());
  }

  @Test
  void projectionRetainsDocumentRecordReferences() throws Exception {
    var evidence = new RecoveryEvidence(1, "failure-1", "CONTRACT_SHAPE", "design-execution", "planning",
        null, null, List.of(), List.of(), null, List.of(), List.of("record-A", "record-B"));
    var root = MAPPER.readTree(RecoveryContextProjector.project(
        new RecoveryContext(evidence, sampleBrief(), null, "en"), MAPPER));
    assertEquals(MAPPER.valueToTree(evidence.documentRecordRefs()),
        root.path("evidence").path("documentRecordRefs"));
  }

  @Test
  void projectorJsonContainsFailureIdFindingsAndApprovedBrief() throws Exception {
    SemanticFinding finding =
        new SemanticFinding(
            "MISSING_REQUIRED_PROPERTY",
            "service-call.properties.required",
            "call-1",
            "call-1",
            "service-call",
            List.of("retryCount"),
            List.of(),
            List.of(),
            "",
            java.util.Map.of(),
            List.of("integrationOperationId"),
            "{\"valid\":false,\"missingRequired\":[\"retryCount\"]}");
    RecoveryEvidence evidence = sampleEvidence(finding, null);
    RequirementBrief brief =
        new RequirementBrief(
            "Proxy inventory",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            null,
            "",
            List.of(
                new RequirementFact(
                    "fact-1",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.BEHAVIOR,
                    "http-trigger",
                    "Expose GET /health",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "")));
    RecoveryContext context = new RecoveryContext(evidence, brief, null, "en");

    JsonNode root = MAPPER.readTree(RecoveryContextProjector.project(context, MAPPER));

    assertEquals("failure-1", root.path("failureId").asText());
    assertEquals("Proxy inventory", root.path("approvedBrief").path("goal").asText());
    assertTrue(root.path("approvedBrief").path("facts").isArray());
    assertEquals(1, root.path("approvedBrief").path("facts").size());
  }

  @Test
  void findingsExposeRawValidatorJsonInlineWhenUnderBudget() throws Exception {
    String validatorJson = "{\"valid\":false,\"code\":\"MISSING_REQUIRED_PROPERTY\"}";
    SemanticFinding finding =
        new SemanticFinding(
            "MISSING_REQUIRED_PROPERTY",
            "path",
            "n1",
            "n1",
            "service-call",
            List.of("retryCount"),
            List.of(),
            List.of(),
            "",
            java.util.Map.of(),
            List.of(),
            validatorJson);
    RecoveryContext context =
        new RecoveryContext(sampleEvidence(finding, null), sampleBrief(), null, "en");

    JsonNode root = MAPPER.readTree(RecoveryContextProjector.project(context, MAPPER));
    JsonNode findings = root.path("evidence").path("findings");

    assertEquals(validatorJson, findings.get(0).path("rawValidatorJson").asText());
    assertTrue(root.path("manifest").isMissingNode() || root.path("manifest").isEmpty());
  }

  @Test
  void projectionDoesNotMutateStoredRecoveryEvidence() throws Exception {
    String secret = "Authorization: Bearer top-secret-token";
    TechnicalFailureRecord technical =
        new TechnicalFailureRecord(
            true,
            1,
            "catalog",
            "lookup",
            "30s",
            "corr-42",
            "TimeoutException",
            secret,
            "503",
            secret);
    String largeJson = "{\"detail\":\"" + "x".repeat(120_000) + "\"}";
    SemanticFinding finding =
        new SemanticFinding(
            "MISSING_REQUIRED_PROPERTY",
            "path",
            "n1",
            "n1",
            "service-call",
            List.of(),
            List.of(),
            List.of(),
            "",
            java.util.Map.of(),
            List.of(),
            largeJson);
    RecoveryEvidence evidence = sampleEvidence(finding, technical);
    RecoveryContext context = new RecoveryContext(evidence, sampleBrief(), null, "en");

    assertThrows(IllegalArgumentException.class, () -> RecoveryContextProjector.project(context, MAPPER));

    assertEquals(secret, evidence.technicalFailure().exceptionMessage());
    assertEquals(largeJson, evidence.findings().getFirst().rawValidatorJson());
    assertEquals("corr-42", evidence.technicalFailure().correlationId());
  }

  @Test
  void redactsSensitiveHeadersInTechnicalFailureAndProjectedJson() throws Exception {
    String headers =
        "Authorization: Bearer abc123; Cookie: session=secret; Set-Cookie: sid=xyz; correlationId: corr-99";
    TechnicalFailureRecord technical =
        new TechnicalFailureRecord(
            true,
            2,
            "apihub",
            "search",
            "10s",
            "corr-99",
            "IOException",
            headers,
            headers,
            headers);
    RecoveryContext context =
        new RecoveryContext(
            sampleEvidence(
                new SemanticFinding(
                    "TECHNICAL",
                    "",
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    java.util.Map.of(),
                    List.of(),
                    "{}"),
                technical),
            sampleBrief(),
            null,
            "en");

    String projected = RecoveryContextProjector.project(context, MAPPER);
    JsonNode technicalNode = MAPPER.readTree(projected).path("evidence").path("technicalFailure");

    assertEquals("corr-99", technicalNode.path("correlationId").asText());
    assertTrue(technicalNode.path("exceptionMessage").asText().contains("corr-99"));
    assertFalse(projected.contains("Bearer abc123"));
    assertFalse(projected.contains("session=secret"));
    assertFalse(projected.contains("sid=xyz"));
    assertTrue(projected.contains("Authorization: [REDACTED]"));
    assertTrue(projected.contains("Cookie: [REDACTED]"));
    assertTrue(projected.contains("Set-Cookie: [REDACTED]"));
    assertEquals(headers, context.evidence().technicalFailure().exceptionMessage());
  }

  @Test
  void oversizedEvidenceUsesTheFallbackWithoutChangingStoredValidatorDetails() {
    String validatorJson = "{\"valid\":false,\"payload\":\"" + "z".repeat(120_000) + "\"}";
    SemanticFinding finding =
        new SemanticFinding(
            "MISSING_REQUIRED_PROPERTY",
            "path",
            "n1",
            "n1",
            "service-call",
            List.of("retryCount"),
            List.of(),
            List.of(),
            "",
            java.util.Map.of(),
            List.of(),
            validatorJson);
    RecoveryContext context =
        new RecoveryContext(sampleEvidence(finding, null), sampleBrief(), null, "en");

    assertThrows(IllegalArgumentException.class, () -> RecoveryContextProjector.project(context, MAPPER));
    assertEquals(validatorJson, context.evidence().findings().getFirst().rawValidatorJson());
  }

  private static RecoveryEvidence sampleEvidence(
      SemanticFinding finding, TechnicalFailureRecord technical) {
    Reference briefRef = new Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-brief");
    return new RecoveryEvidence(
        1,
        "failure-1",
        "MISSING_REQUIRED_PROPERTY",
        "design-execution",
        briefRef,
        null,
        List.of(),
        List.of(finding),
        technical,
        List.of());
  }

  private static RequirementBrief sampleBrief() {
    return new RequirementBrief(
        "Proxy inventory",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "",
        null,
        "",
        List.of(
            new RequirementFact(
                "fact-1",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.BEHAVIOR,
                "service-call",
                "Call inventory API",
                "",
                "",
                "",
                "",
                "",
                "")));
  }
}
