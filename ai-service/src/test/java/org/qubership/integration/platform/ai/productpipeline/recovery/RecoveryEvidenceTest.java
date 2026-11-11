package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;

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

  @Test
  void mappingDiagnosticRoundTripsObservingProducerSchemaAndJsonPayload() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    MappingValidationDetails details =
        new MappingValidationDetails(
            "salesforce-create-task",
            "trigger-http",
            "OUTPUT",
            "call-1",
            "REQUEST",
            "$.executionId",
            "$.preserved.executionId",
            "",
            "PROPOSED",
            "brief-1",
            "hash-brief",
            "trigger-http",
            "OUTPUT",
            "sha-source",
            "conversation-schema",
            "call-1",
            "REQUEST",
            "sha-target",
            "conversation-schema",
            "Subject",
            "$.preserved.executionId");
    PlanValidationFinding finding =
        new PlanValidationFinding(
            "MAPPING_UNKNOWN_TARGET",
            "Target path $.preserved.executionId is absent from the target contract.",
            true,
            details);
    String rawJson = mapper.writeValueAsString(finding);
    RecoveryEvidence evidence =
        new RecoveryEvidence(
            1,
            "failure-1",
            "MAPPING_CONTRACT",
            "design-execution",
            "requirement-analysis",
            new Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-brief"),
            null,
            List.of(),
            List.of(
                new SemanticFinding(
                    "MAPPING_UNKNOWN_TARGET",
                    finding.message(),
                    "failure-1-mapping-1",
                    "salesforce-create-task",
                    "$.preserved.executionId",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    Map.of(),
                    List.of(),
                    rawJson)),
            null,
            List.of());

    RecoveryEvidence restored = mapper.readValue(mapper.writeValueAsString(evidence), RecoveryEvidence.class);

    assertEquals("design-execution", restored.observingStageId());
    assertEquals("requirement-analysis", restored.producerStageId());
    assertEquals("MAPPING_CONTRACT", restored.observedCauseCode());
    SemanticFinding restoredFinding = restored.findings().getFirst();
    JsonNode payload = mapper.readTree(restoredFinding.rawValidatorJson());
    assertTrue(payload.isObject());
    assertEquals("call-1", payload.path("mappingDetails").path("targetSchemaOwner").asText());
    assertEquals(
        "sha-target", payload.path("mappingDetails").path("targetSchemaDigest").asText());
    assertEquals(
        "conversation-schema",
        payload.path("mappingDetails").path("targetSchemaProvenance").asText());
    assertEquals("brief-1", payload.path("mappingDetails").path("consumedBriefArtifactId").asText());
    assertEquals(
        "$.preserved.executionId", payload.path("mappingDetails").path("targetPath").asText());
  }
}
