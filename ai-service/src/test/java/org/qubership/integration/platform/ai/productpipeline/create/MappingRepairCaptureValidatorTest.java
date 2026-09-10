package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.create.MappingRepairCaptureValidator.Result;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.SemanticFinding;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class MappingRepairCaptureValidatorTest {

  private static final String RUN_ID = "run-repair-capture";
  private static final String CONVERSATION_ID = "conversation-repair-capture";
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private ProductPipelineArtifactStore artifactStore;
  private RecordingSchemaLoader schemaLoader;
  private MappingRepairCaptureValidator validator;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(
            blobs,
            MAPPER,
            Clock.fixed(Instant.parse("2026-09-10T18:00:00Z"), ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    schemaLoader = new RecordingSchemaLoader();
    validator = new MappingRepairCaptureValidator(artifactStore, MAPPER, schemaLoader);
    persistSide(
        "trigger-http",
        "source-operation",
        MappingPort.OUTPUT,
        "source-digest",
        sourceSchema());
    persistSide(
        "call-1", "old-operation", MappingPort.REQUEST, "target-digest", oldTargetSchema());
  }

  @Test
  void rewordedExpressionWithTheSameInvalidTargetKeepsTheTypedFinding() throws Exception {
    String evidenceHash = persistRecoveryEvidence();
    RequirementBrief candidate =
        brief(
            intent(
                List.of(
                    new MappingIntentRule(
                        "$.subject",
                        "$.Subject",
                        null,
                        MappingRuleStatus.PROPOSED),
                    new MappingIntentRule(
                        "$.executionId",
                        "$.preserved.executionId",
                        "reworded preservation expression",
                        MappingRuleStatus.PROPOSED))));

    Result result = validator.validate(RUN_ID, CONVERSATION_ID, evidenceHash, candidate);

    assertEquals(Result.Status.BLOCKED, result.status());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "MAPPING_UNKNOWN_TARGET".equals(finding.code())
                        && "$.preserved.executionId"
                            .equals(finding.mappingDetails().targetPath())));
  }

  @Test
  void aDifferentRemainingViolationIsReturnedAsTheNewFinding() throws Exception {
    String evidenceHash = persistRecoveryEvidence();
    RequirementBrief candidate = brief(intent(List.of()));

    Result result = validator.validate(RUN_ID, CONVERSATION_ID, evidenceHash, candidate);

    assertEquals(Result.Status.BLOCKED, result.status());
    assertEquals(1, result.findings().size());
    assertEquals("MAPPING_MISSING_REQUIRED_TARGET", result.findings().getFirst().code());
  }

  @Test
  void changedOperationUsesTheNewContract() throws Exception {
    String evidenceHash = persistRecoveryEvidence();
    schemaLoader.schemasByOperation.set(
        Map.of(
            "old-operation",
            new OperationSchemaMaps(
                "old-operation", Map.of("application/json", oldTargetSchema()), Map.of()),
            "new-operation",
            new OperationSchemaMaps(
                "new-operation", Map.of("application/json", newTargetSchema()), Map.of())));
    RequirementBrief candidate =
        brief(
                intent(
                    "call-2",
                    List.of(
                        new MappingIntentRule(
                            "$.subject", "$.Name", null, MappingRuleStatus.PROPOSED))))
            .withCatalogBindings(
                List.of(
                    binding("call-1", "old-operation"),
                    binding("call-2", "new-operation")));

    Result result = validator.validate(RUN_ID, CONVERSATION_ID, evidenceHash, candidate);

    assertEquals(Result.Status.PASSED, result.status());
    assertEquals("new-operation", schemaLoader.lastOperationId.get());
  }

  @Test
  void sameBoundaryOperationChangeUsesTheNewContract() throws Exception {
    String evidenceHash = persistRecoveryEvidence("node-call");
    schemaLoader.schemasByOperation.set(
        Map.of(
            "old-operation",
            new OperationSchemaMaps(
                "old-operation", Map.of("application/json", oldTargetSchema()), Map.of()),
            "new-operation",
            new OperationSchemaMaps(
                "new-operation", Map.of("application/json", newTargetSchema()), Map.of())));
    RequirementBrief candidate =
        brief(
                intent(
                    "node-call",
                    List.of(
                        new MappingIntentRule(
                            "$.subject", "$.Name", null, MappingRuleStatus.PROPOSED))))
            .withCatalogBindings(
                List.of(
                    binding("call-1", "old-operation"),
                    binding("node-call", "new-operation")));

    Result result = validator.validate(RUN_ID, CONVERSATION_ID, evidenceHash, candidate);

    assertEquals(Result.Status.PASSED, result.status());
    assertEquals("new-operation", schemaLoader.lastOperationId.get());
  }

  @Test
  void duplicateBindingsKeepTheRepairUnresolved() throws Exception {
    String evidenceHash = persistRecoveryEvidence();
    RequirementBrief candidate =
        brief(
                intent(
                    List.of(
                        new MappingIntentRule(
                            "$.subject", "$.Subject", null, MappingRuleStatus.PROPOSED))))
            .withCatalogBindings(
                List.of(
                    binding("call-1", "old-operation"),
                    binding("call-1", "new-operation")));

    Result result = validator.validate(RUN_ID, CONVERSATION_ID, evidenceHash, candidate);

    assertEquals(Result.Status.UNRESOLVED, result.status());
  }

  @Test
  void unavailableChangedOperationContractKeepsTheRepairUnresolved() throws Exception {
    String evidenceHash = persistRecoveryEvidence();
    schemaLoader.schemasByOperation.set(
        Map.of("new-operation", new OperationSchemaMaps("new-operation", Map.of(), Map.of())));
    RequirementBrief candidate =
        brief(
                intent(
                    List.of(
                        new MappingIntentRule(
                            "$.subject", "$.Name", null, MappingRuleStatus.PROPOSED))))
            .withCatalogBindings(List.of(binding("new-operation")));

    Result result = validator.validate(RUN_ID, CONVERSATION_ID, evidenceHash, candidate);

    assertEquals(Result.Status.UNRESOLVED, result.status());
    assertTrue(result.message().contains("new-operation"), result.message());
  }

  private String persistRecoveryEvidence() throws Exception {
    return persistRecoveryEvidence("call-1");
  }

  private String persistRecoveryEvidence(String targetRef) throws Exception {
    PlanValidationFinding finding =
        new PlanValidationFinding(
            "MAPPING_UNKNOWN_TARGET",
            "message without a target path",
            true,
            new MappingValidationDetails(
                "salesforce-create-task",
                "trigger-http",
                "OUTPUT",
                targetRef,
                "REQUEST",
                "$.executionId",
                "$.preserved.executionId",
                "",
                "UNRESOLVED",
                "brief-1",
                "brief-hash-1",
                "trigger-http",
                "OUTPUT",
                "source-digest",
                "source-provenance",
                "call-1",
                "REQUEST",
                "target-digest",
                "target-provenance",
                "Subject",
                "$.preserved.executionId"));
    SemanticFinding semantic =
        new SemanticFinding(
            finding.code(),
            finding.message(),
            "salesforce-create-task",
            "call-1",
            "mapping",
            List.of(),
            List.of(),
            List.of(),
            "",
            Map.of(),
            List.of(),
            MAPPER.writeValueAsString(finding));
    RecoveryEvidence evidence =
        new RecoveryEvidence(
            1,
            "failure-1",
            "MAPPING_CONTRACT",
            "design-execution",
            "requirement-analysis",
            new Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "brief-hash-1"),
            null,
            List.of(),
            List.of(semantic),
            null,
            List.of());
    return artifactStore
        .append(
            new AppendCommand(
                RUN_ID,
                Kind.RECOVERY_EVIDENCE,
                "1",
                "test",
                "1",
                evidence,
                List.of(),
                null,
                provenance(RUN_ID)))
        .contentHash();
  }

  private void persistSide(
      String owner, String operationId, MappingPort port, String digest, JsonNode schema) {
    artifactStore.append(
        new AppendCommand(
            CONVERSATION_ID,
            Kind.MAPPING_SCHEMA_SIDE,
            "1",
            "test",
            "1",
            new MappingSchemaSide(
                "1",
                owner,
                operationId,
                port,
                "application/json",
                null,
                digest,
                owner + "-provenance",
                schema),
            List.of(),
            null,
            provenance(CONVERSATION_ID)));
  }

  private static ArtifactProvenance provenance(String scopeId) {
    return new ArtifactProvenance(
        scopeId,
        "requirement-analysis",
        "create-chain",
        "2",
        "profile-hash",
        "mapping-repair",
        "1",
        "closure-hash");
  }

  private static RequirementBrief brief(MappingIntent intent) {
    return new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary")
        .withMappingIntents(List.of(intent));
  }

  private static MappingIntent intent(List<MappingIntentRule> rules) {
    return intent("call-1", rules);
  }

  private static MappingIntent intent(String targetRef, List<MappingIntentRule> rules) {
    return new MappingIntent(
        "salesforce-create-task",
        "trigger-http",
        MappingPort.OUTPUT,
        targetRef,
        MappingPort.REQUEST,
        rules);
  }

  private static CatalogBindingHint binding(String operationId) {
    return binding("call-1", operationId);
  }

  private static CatalogBindingHint binding(String interactionId, String operationId) {
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        interactionId,
        interactionId,
        "Create task",
        "system-1",
        "group-1",
        "specification-1",
        operationId,
        "http",
        "POST",
        "/tasks",
        "2024.4",
        Instant.parse("2026-09-10T18:00:00Z"),
        "catalog-evidence");
  }

  private static JsonNode sourceSchema() throws Exception {
    return MAPPER.readTree(
        """
        {
          "type": "object",
          "properties": {
            "subject": { "type": "string" },
            "executionId": { "type": "string" }
          }
        }
        """);
  }

  private static JsonNode oldTargetSchema() throws Exception {
    return MAPPER.readTree(
        """
        {
          "type": "object",
          "properties": { "Subject": { "type": "string" } },
          "required": ["Subject"]
        }
        """);
  }

  private static JsonNode newTargetSchema() throws Exception {
    return MAPPER.readTree(
        """
        {
          "type": "object",
          "properties": { "Name": { "type": "string" } },
          "required": ["Name"]
        }
        """);
  }

  private static final class RecordingSchemaLoader implements OperationSchemaLoader {
    private final AtomicReference<String> lastOperationId = new AtomicReference<>();
    private final AtomicReference<Map<String, OperationSchemaMaps>> schemasByOperation =
        new AtomicReference<>(Map.of());

    @Override
    public OperationSchemaMaps load(String operationId) {
      lastOperationId.set(operationId);
      return schemasByOperation.get().get(operationId);
    }

    @Override
    public MappingSchemaSide persistRequest(
        String compilationId, String serviceCallId, String operationId, String contentType) {
      throw new UnsupportedOperationException();
    }

    @Override
    public MappingSchemaSide persistResponse(
        String compilationId,
        String serviceCallId,
        String operationId,
        String contentType,
        String responseCode) {
      throw new UnsupportedOperationException();
    }
  }
}
