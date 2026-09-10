package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecoveryEvidenceTest {

  @Test
  void recoveryEvidenceRejectsBlankFailureId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryEvidence(
                1, " ", "MISSING_REQUIRED_PROPERTY", "design-execution",
                null, null, List.of(), List.of(), null, List.of()));
  }

  @Test
  void semanticFindingPreservesOneOfHintsAndDefaults() {
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
            Map.of("retryCount", "0", "retryDelay", "5000"),
            List.of("integrationOperationId"),
            "{\"valid\":false}");
    assertEquals(List.of("retryCount"), finding.missingKeys());
    assertEquals("0", finding.schemaDefaults().get("retryCount"));
  }

  @Test
  void recoveryEvidenceNullCollectionsBecomeEmpty() {
    RecoveryEvidence evidence =
        new RecoveryEvidence(
            1, "failure-1", "MISSING_REQUIRED_PROPERTY", "design-execution",
            null, null, null, null, null, null);

    assertEquals(List.of(), evidence.rejectedArtifactRefs());
    assertEquals(List.of(), evidence.findings());
    assertEquals(List.of(), evidence.priorAttemptRefs());
  }

  @Test
  void legacyJsonWithoutMappingFieldsRemainsReadable() throws Exception {
    RecoveryEvidence restored =
        new ObjectMapper()
            .readValue(
                """
                {
                  "schemaVersion": 1,
                  "failureId": "failure-1",
                  "observedCauseCode": "MISSING_REQUIRED_PROPERTY",
                  "observingStageId": "design-execution",
                  "rejectedArtifactRefs": [],
                  "findings": [
                    {
                      "code": "MISSING_REQUIRED_PROPERTY",
                      "violatedRule": "service-call.properties.required",
                      "occurrenceId": "call-1",
                      "nodeId": "call-1",
                      "elementType": "service-call",
                      "missingKeys": ["retryCount"],
                      "rawValidatorJson": "{\\"valid\\":false}"
                    }
                  ],
                  "priorAttemptRefs": []
                }
                """,
                RecoveryEvidence.class);

    assertEquals("failure-1", restored.failureId());
    assertEquals("MISSING_REQUIRED_PROPERTY", restored.observedCauseCode());
    assertEquals("design-execution", restored.observingStageId());
    assertEquals(1, restored.findings().size());
    assertEquals("MISSING_REQUIRED_PROPERTY", restored.findings().getFirst().code());
    assertEquals("call-1", restored.findings().getFirst().nodeId());
    assertTrue(restored.findings().getFirst().rawValidatorJson().contains("valid"));
  }

  @Test
  void recoveryEvidenceRejectsSchemaVersionOtherThanOne() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryEvidence(
                2, "failure-1", "MISSING_REQUIRED_PROPERTY", "design-execution",
                null, null, List.of(), List.of(), null, List.of()));
  }

  @Test
  void recoveryEvidenceTechnicalFailureStaysNullWhenAbsent() {
    RecoveryEvidence evidence =
        new RecoveryEvidence(
            1, "failure-1", "MISSING_REQUIRED_PROPERTY", "design-execution",
            null, null, List.of(), List.of(), null, List.of());

    assertNull(evidence.technicalFailure());
  }
}
