package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class ValidationArtifactTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void typedIssueMetadataRoundTripsAndLegacyPayloadsKeepTheirShape() throws Exception {
    var typed = new ValidationIssue("counter-1", ValidationSeverity.BLOCKER, "Check this value",
        "validator", List.of("Node/A"), List.of(), "Correct it", "CONTRACT_SHAPE", "Node/A",
        "/properties/Topic~1A~0B");
    assertEquals(typed, objectMapper.readValue(objectMapper.writeValueAsString(typed), ValidationIssue.class));
    var legacy = new ValidationIssue("counter-1", ValidationSeverity.BLOCKER, "Check this value",
        "validator", List.of("Node/A"), List.of(), "Correct it");
    var legacyJson = objectMapper.valueToTree(legacy);
    assertFalse(legacyJson.has("findingCode"));
    assertFalse(legacyJson.has("originRecordId"));
    assertFalse(legacyJson.has("fieldPointer"));
    assertEquals(legacy, objectMapper.treeToValue(legacyJson, ValidationIssue.class));
  }

  @Test
  void roundTripsValidationIssue() throws Exception {
    QipKnowledgePackVersion version = new QipKnowledgePackVersion("v1_0_1", "v1_0_1");
    ValidationIssue original =
        new ValidationIssue(
            "VR-L-001",
            ValidationSeverity.ERROR,
            "Deprecated split element found",
            "cip-configuration-validator",
            List.of("split-1"),
            List.of(
                new QipKnowledgeCitation(
                    "VR-L-001",
                    QipKnowledgeRefType.VALIDATION_RULE,
                    "knowledge/ai/validation-rules.yaml",
                    version,
                    "VR-L-001")),
            "Replace split with split-2");

    ValidationIssue restored =
        objectMapper.readValue(objectMapper.writeValueAsString(original), ValidationIssue.class);

    assertEquals(original.issueId(), restored.issueId());
    assertEquals(ValidationSeverity.ERROR, restored.severity());
    assertEquals("VR-L-001", restored.ruleRefs().get(0).refId());
  }

  @Test
  void hasBlockingIssuesWhenBlockerPresent() {
    ValidationResult result =
        new ValidationResult(
            false,
            List.of(
                new ValidationIssue(
                    "issue-1",
                    ValidationSeverity.BLOCKER,
                    "Missing finally block",
                    "cip-error-handling-generator",
                    List.of("trigger-1"),
                    List.of(),
                    "Add finally-2")),
            "Blocking validation failure");

    assertTrue(result.hasBlockingIssues());
  }

  @Test
  void hasNoBlockingIssuesForInfoAndWarningOnly() {
    ValidationResult result =
        new ValidationResult(
            true,
            List.of(
                new ValidationIssue(
                    "issue-2",
                    ValidationSeverity.INFO,
                    "Optional naming hint",
                    "cip-configuration-validator",
                    List.of(),
                    List.of(),
                    null),
                new ValidationIssue(
                    "issue-3",
                    ValidationSeverity.WARNING,
                    "Timeout not set",
                    "cip-configuration-validator",
                    List.of("svc-1"),
                    List.of(),
                    "Set timeout")),
            "Non-blocking warnings only");

    assertFalse(result.hasBlockingIssues());
  }
}
