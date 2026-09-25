package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.task.SchemaFragment;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkRetainedContextTest {

  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");
  private static final String RUN_ID = "run-context-1";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private WorkDocumentService documents;
  private WorkRetainedContext context;

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
            "conversation-context",
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
    context = new WorkRetainedContext(documents, new WorkTaskExecutor(documents, runs, clock));
  }

  @Test
  void describeUpdatesOnlyTheAssignedProducerPlaceholders() {
    WorkCommit commit =
        context.describe(RUN_ID, "trigger", materials(), request -> value("order-id", "$.orderId"));

    assertEquals("PREPARED", commit.outcome().name());
    assertEquals("$.orderId", path("order-id"));
    assertEquals("", path("note-id"));
    assertEquals("", path("task-id"));
    assertEquals("describe-context-trigger", requestTask(commit));
    assertTrue(commit.commandId().startsWith("describe-context-trigger"));
  }

  @Test
  void badFieldAndAnotherProducerAreTechnicalDefects() {
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> context.describe(RUN_ID, "trigger", materials(), request -> value("order-id", "$.missing")))
            .code());
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> context.describe(RUN_ID, "trigger", materials(), request -> value("task-id", "$.id")))
            .code());
    assertEquals(
        "EXTRA_PROPERTY",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    context.describe(
                        RUN_ID,
                        "trigger",
                        materials(),
                        request ->
                            "{\"outcome\":\"PREPARED\",\"rules\":[],\"values\":[{\"retainedId\":\"order-id\",\"fieldPath\":\"$.orderId\",\"evidenceRefs\":[\"source-1\"]}]}"))
            .code());
    assertEquals("", path("order-id"));
    assertTrue(JSON.valueToTree(documents.read(RUN_ID).document()).path("progress").path("questions").isEmpty());
  }

  @Test
  void fieldRelationshipQuestionStoresBothFields() {
    WorkCommit asked =
        context.describe(
            RUN_ID,
            "trigger",
            materials(),
            request ->
                """
                {"outcome":"NEEDS_CLARIFICATION","values":[],"question":{"text":"Does orderId become Subject?","choiceKind":"FIELD_RELATIONSHIP","sourceStepId":"trigger","sourcePort":"payload","sourceField":"orderId","sourceRetainedId":"order-id","targetStepId":"call","targetPort":"request","targetField":"Subject","targetRetainedId":"","evidenceRefs":["source-1"]}}
                """);
    assertEquals("NEEDS_CLARIFICATION", asked.outcome().name());
    JsonNode question =
        JSON.valueToTree(asked.state().document()).path("progress").path("questions").get(0);
    assertEquals("FIELD_RELATIONSHIP", question.path("choice").asText());
    assertTrue(question.toString().contains("orderId"));
    assertTrue(question.toString().contains("Subject"));
    assertEquals("", path("order-id"));
    assertFalse(question.path("question").asText().isBlank());
  }

  private String requestTask(WorkCommit commit) {
    for (JsonNode task : JSON.valueToTree(commit.state().document()).path("progress").path("tasks")) {
      if ("describe-context:trigger".equals(task.path("taskKey").asText())) {
        return task.path("taskId").asText();
      }
    }
    return "";
  }

  private String path(String id) {
    JsonNode document = JSON.valueToTree(documents.read(RUN_ID).document());
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode retained : step.path("data").path("retainedValues")) {
        if (id.equals(retained.path("id").asText())) {
          return retained.path("source").path("fieldPath").asText();
        }
      }
    }
    throw new AssertionError(id);
  }

  private static String value(String id, String path) {
    return """
        {"outcome":"PREPARED","values":[{"retainedId":"%s","fieldPath":"%s","evidenceRefs":["source-1"]}]}
        """
        .formatted(id, path);
  }

  private static WorkTaskMaterials materials() {
    return new WorkTaskMaterials(
        List.of(
            new SchemaFragment(
                "schema-trigger",
                "trigger",
                "payload",
                "hash",
                "ref",
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"note\":{\"type\":\"string\"}}}")),
        List.of(),
        Map.of("source-1", "order id"));
  }

  private static String document() {
    return """
        {
          "schemaVersion": 2,
          "documentId": "doc-context",
          "sources": [{
            "id": "source-1",
            "role": "request",
            "contentReference": "artifact://src",
            "contentHash": "hash",
            "originalName": "src.txt",
            "suppliedIdentifier": "S",
            "correctionOf": []
          }],
          "requirements": [],
          "flow": {
            "steps": [
              {"id":"trigger","kind":"TRIGGER","label":"Start","intent":"Receive","sourceIds":["source-1"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[
                {"id":"order-id","source":{"kind":"STEP_PORT","stepId":"trigger","port":"payload","fieldPath":"","retainedValueId":""},"intendedUse":"Order id","evidenceIds":["source-1"],"producerStepId":"trigger","resolution":"UNRESOLVED"},
                {"id":"note-id","source":{"kind":"STEP_PORT","stepId":"trigger","port":"payload","fieldPath":"","retainedValueId":""},"intendedUse":"Note","evidenceIds":["source-1"],"producerStepId":"trigger","resolution":"UNRESOLVED"}
              ]}},
              {"id":"other","kind":"SERVICE_CALL","label":"Other","intent":"Other call","sourceIds":["source-1"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[
                {"id":"task-id","source":{"kind":"STEP_PORT","stepId":"other","port":"success","fieldPath":"","retainedValueId":""},"intendedUse":"Task id","evidenceIds":["source-1"],"producerStepId":"other","resolution":"UNRESOLVED"}
              ]}},
              {"id":"call","kind":"SERVICE_CALL","label":"Call","intent":"Use the order","sourceIds":["source-1"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}}
            ],
            "connections": [],
            "sequenceGroups": [],
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
