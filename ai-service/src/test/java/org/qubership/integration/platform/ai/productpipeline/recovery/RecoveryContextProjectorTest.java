package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    RecoveryContextProjector.project(context, MAPPER);

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
  void oversizedEvidenceUsesManifestAndAttachmentsWithoutDroppingValidatorDetails()
      throws Exception {
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

    JsonNode root = MAPPER.readTree(RecoveryContextProjector.project(context, MAPPER));

    assertTrue(root.path("manifest").isArray());
    assertFalse(root.path("manifest").isEmpty());
    String attachmentKey = root.path("manifest").get(0).asText();
    assertEquals(validatorJson, root.path("attachments").path(attachmentKey).asText());
    assertEquals(attachmentKey, root.path("evidence").path("findings").get(0).path("attachmentRef").asText());
    assertTrue(root.path("evidence").path("findings").get(0).path("rawValidatorJson").isMissingNode());
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
