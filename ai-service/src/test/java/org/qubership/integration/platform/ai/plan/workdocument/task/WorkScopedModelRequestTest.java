package org.qubership.integration.platform.ai.plan.workdocument.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentCaptureSchema;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;
import org.qubership.integration.platform.ai.plan.workdocument.mapping.WorkMapping;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkScopedModelRequestTest {

  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");
  private static final String RUN_ID = "run-scope-1";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private WorkDocumentService documents;
  private WorkMapping mapping;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, clock);
    ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, JSON, clock);
    documents = new WorkDocumentService(runs, artifacts, JSON);
    runs.create(
        new RunSnapshot(
            RUN_ID,
            "conversation-scope",
            1L,
            RunStatus.RUNNING,
            "DATA_BEHAVIOR",
            List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(
        RUN_ID,
        new WorkDocumentState("pending", JSON.readValue(document(), ChainWorkDocument.class)),
        "cmd-seed",
        new WorkRepairBudget(3));
    mapping = new WorkMapping(documents, new WorkTaskExecutor(documents, runs, clock));
  }

  @Test
  void mappingRequestUsesTheNarrowSchemaAndTheAssignedTransfer() {
    List<WorkTaskRequest> seen = new ArrayList<>();
    mapping.interpret(RUN_ID, "xfer-a", materials(), request -> {
      seen.add(request);
      return "{\"outcome\":\"PREPARED\",\"rules\":[{\"alias\":\"priority\",\"targetPath\":\"$.Priority\",\"sources\":[{\"sourceRef\":\"task-a/payload\",\"fieldPath\":\"$.priority\"}],\"constants\":[],\"behavior\":\"high to High\",\"evidenceRefs\":[\"source-gov\"]}],\"decision\":\"\",\"evidenceRefs\":[]}";
    });

    WorkTaskRequest request = seen.getFirst();
    assertEquals("map-transfer-xfer-a", request.taskId());
    assertEquals("map-transfer:xfer-a", request.taskKey());
    assertEquals(WorkTaskKind.MAP_TRANSFER, request.kind());
    JsonObjectSchema schema = request.responseSchema();
    assertEquals(Boolean.FALSE, schema.additionalProperties());
    assertTrue(schema.properties().containsKey("rules"));
    assertFalse(schema.properties().containsKey("sequenceGroups"));
    assertFalse(schema.properties().containsKey("steps"));
    assertFalse(schema.properties().containsKey("transfers"));
    assertTrue(request.prompt().contains("transfer xfer-a"));
    assertTrue(request.prompt().contains("source task-a/payload"));
    assertTrue(request.prompt().contains("ASSIGNED_PRIORITY"));
    assertFalse(request.prompt().contains("UNRELATED_SUCCESS"));
    assertTrue(request.prompt().contains("runtime-catalog-only"));
    assertTrue(request.prompt().contains("PRIOR_SOURCE"));
    assertEquals(1, rules());
  }

  @Test
  void initialTransferWithoutRulesStillSelectsItsSchemas() {
    List<String> prompts = new ArrayList<>();
    mapping.interpret(
        RUN_ID,
        "xfer-a",
        materials(),
        request -> {
          prompts.add(request.prompt());
          return "{\"outcome\":\"PREPARED\",\"rules\":[],\"decision\":\"NO_MAPPING\",\"evidenceRefs\":[\"source-gov\"]}";
        });
    assertTrue(prompts.getFirst().contains("ASSIGNED_PRIORITY"));
    assertFalse(prompts.getFirst().contains("UNRELATED_SUCCESS"));
    assertFalse(prompts.getFirst().contains("constraint schema "));
  }

  @Test
  void sameLabelIsNotSubstitutedAndInvalidJsonCreatesNoQuestion() {
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    mapping.interpret(
                        RUN_ID,
                        "xfer-a",
                        materials(),
                        request ->
                            "{\"outcome\":\"PREPARED\",\"rules\":[{\"alias\":\"priority\",\"targetPath\":\"$.Priority\",\"sources\":[{\"sourceRef\":\"Task/payload\",\"fieldPath\":\"$.priority\"}],\"constants\":[],\"behavior\":\"high\",\"evidenceRefs\":[\"source-gov\"]}],\"decision\":\"\",\"evidenceRefs\":[]}"))
            .code());
    assertEquals(
        "MALFORMED_CAPTURE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> mapping.interpret(RUN_ID, "xfer-a", materials(), request -> "{"))
            .code());
    assertEquals(
        "INVALID_CONSTANT",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    mapping.interpret(
                        RUN_ID,
                        "xfer-a",
                        materials(),
                        request ->
                            "{\"outcome\":\"PREPARED\",\"rules\":[{\"alias\":\"priority\",\"targetPath\":\"$.Priority\",\"sources\":[{\"sourceRef\":\"task-a/payload\",\"fieldPath\":\"$.priority\"}],\"constants\":[{\"name\":\"priority\",\"value\":\"Banana\"}],\"behavior\":\"bad\",\"evidenceRefs\":[\"source-gov\"]}],\"decision\":\"\",\"evidenceRefs\":[]}"))
            .code());
    assertEquals(
        "EXTRA_PROPERTY",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    mapping.interpret(
                        RUN_ID,
                        "xfer-a",
                        materials(),
                        request ->
                            "{\"outcome\":\"PREPARED\",\"retainedValues\":[],\"rules\":[],\"decision\":\"\",\"evidenceRefs\":[]}"))
            .code());
    assertEquals(0, rules());
    assertTrue(
        JSON.valueToTree(documents.read(RUN_ID).document()).path("progress").path("questions").isEmpty());
  }

  @Test
  void blankTransferIdIsRejectedBeforeTheModelRuns() {
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    mapping.interpret(
                        RUN_ID,
                        " ",
                        materials(),
                        request -> {
                          throw new AssertionError("model");
                        }))
            .code());
  }

  @Test
  void universalCaptureIsNotTheMappingResponseSchema() {
    JsonObjectSchema mapping =
        WorkDocumentCaptureSchema.responseSchema(WorkTaskKind.MAP_TRANSFER, null);
    JsonObjectSchema universal = WorkDocumentCaptureSchema.captureSchema();
    assertFalse(mapping.properties().containsKey("sequenceGroups"));
    assertTrue(universal.properties().containsKey("sequenceGroups"));
  }

  private int rules() {
    int count = 0;
    var document = JSON.valueToTree(documents.read(RUN_ID).document());
    for (var step : document.path("flow").path("steps")) {
      for (var transfer : step.path("data").path("transfers")) {
        count += transfer.path("rules").size();
      }
    }
    return count;
  }

  private static WorkTaskMaterials materials() {
    return new WorkTaskMaterials(
        List.of(
            new SchemaFragment(
                "schema-a",
                "task-a",
                "payload",
                "hash-a",
                "ref-a",
                "{\"type\":\"object\",\"properties\":{\"priority\":{\"type\":\"string\",\"enum\":[\"High\",\"Normal\",\"Low\"]},\"name\":{\"type\":\"string\"}}}"),
            new SchemaFragment(
                "schema-b",
                "task-b",
                "request",
                "hash-b",
                "ref-b",
                "{\"type\":\"object\",\"properties\":{\"Priority\":{\"type\":\"string\",\"enum\":[\"High\",\"Normal\",\"Low\"]},\"Subject\":{\"type\":\"string\"},\"marker\":{\"type\":\"string\",\"const\":\"ASSIGNED_PRIORITY\"}}}"),
            new SchemaFragment(
                "schema-success",
                "task-b",
                "success",
                "hash-s",
                "ref-s",
                "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"const\":\"UNRELATED_SUCCESS\"}}}")),
        List.of("runtime-catalog-only"),
        Map.of("source-gov", "GOVERNING", "source-prior", "PRIOR_SOURCE"));
  }

  private static String document() {
    return """
        {
          "schemaVersion": 2,
          "documentId": "doc-scope",
          "sources": [
            {"id":"source-gov","role":"MAPPING","contentReference":"artifact://gov","contentHash":"hash-gov","originalName":"gov.txt","suppliedIdentifier":"G","correctionOf":["source-prior"]},
            {"id":"source-prior","role":"MAPPING","contentReference":"artifact://prior","contentHash":"hash-prior","originalName":"prior.txt","suppliedIdentifier":"P","correctionOf":[]}
          ],
          "requirements": [],
          "flow": {
            "steps": [
              {"id":"task-a","kind":"TRIGGER","label":"Task","intent":"Start","sourceIds":["source-gov"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}},
              {"id":"task-b","kind":"SERVICE_CALL","label":"Task","intent":"Call","sourceIds":["source-gov"],"requirementIds":[],"binding":null,"data":{"transfers":[
                {"id":"xfer-a","sourcePorts":[{"stepId":"task-a","portName":"payload"}],"targetPort":{"stepId":"task-b","portName":"request"},"requirementIds":[],"rules":[],"decision":"UNSPECIFIED","outcome":"UNSPECIFIED","requiredRetainedIds":[]}
              ],"retainedValues":[]}}
            ],
            "connections": [],
            "sequenceGroups": [{"id":"seq-unrelated","memberStepIds":["task-b"]}],
            "conditionGroups": [],
            "splitGroups": [],
            "loopGroups": [],
            "retryGroups": [],
            "errorScopeGroups": []
          },
          "progress": {"tasks":[],"findings":[],"questions":[],"approvalReference":"","derivedResultReferences":[],"recheckStages":[]}
        }
        """;
  }
}
