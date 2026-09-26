package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;
import org.qubership.integration.platform.ai.plan.workdocument.binding.OfflineCatalog;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;

/**
 * Evidence the filling harness writes. These tests use the offline fixture and do not call a
 * provider or a live catalog.
 */
class FillingEvidenceContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void checklistDoesNotPassBecauseBehaviorMentionsAName() {
    ArrayNode checklist = FillingCheckpoint.checklist(proseOnlyDocument());
    assertFalse(passed(checklist, "subject-fallback"));
    assertFalse(passed(checklist, "priority-branches"));
    assertFalse(passed(checklist, "activity-date"));
    assertFalse(passed(checklist, "description-names"));
    assertTrue(expected(checklist, "description-names").contains("$.woOrderType"));
  }

  @Test
  void checksRecordSourcePathsRetainedIdsAndTaskStates() {
    ArrayNode checks = FillingCheckpoint.checks(proseOnlyDocument());
    JsonNode sources = row(checks, "sourcePaths");
    JsonNode retained = row(checks, "retainedIds");
    JsonNode tasks = row(checks, "taskStates");
    assertEquals("sourcePaths", sources.path("field").asText());
    assertEquals("retainedIds", retained.path("field").asText());
    assertEquals("taskStates", tasks.path("field").asText());
    JsonNode description = row(checks, "descriptionSources");
    assertEquals("descriptionSources", description.path("field").asText());
    assertFalse(description.path("passed").asBoolean());
  }

  @Test
  void namedPortAbsentReturnsEmpty() {
    ObjectNode step = JSON.createObjectNode();
    step.putObject("binding").putArray("exposedPorts").add("body");
    assertEquals("", FillingCheckpoint.portNamed(step, "payload"));
    assertEquals("", FillingCheckpoint.portNamed(step, "request"));
    assertEquals("body", FillingCheckpoint.portNamed(step, "body"));
  }

  @Test
  void committedMissingRetainedFindingIsNotReportedAgain() {
    ObjectNode document = successTransfer();
    document.putObject("progress").putArray("findings").addObject().put("issueCategory", "MISSING_RETAINED");
    SequentialFillingModel model = new SequentialFillingModel(() -> document, true);
    String response =
        model.complete(
            new WorkTaskRequest(
                "map-transfer-t1",
                "map-transfer:t1",
                WorkTaskKind.MAP_TRANSFER,
                "prompt",
                JsonObjectSchema.builder().build()));
    assertFalse(response.contains("MISSING_RETAINED"), response);
  }

  @Test
  void catalogDigestIsNotASyntheticContract() {
    assertFalse(FillingCheckpoint.syntheticContentHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
    assertTrue(FillingCheckpoint.syntheticContentHash("hash-payload-1"));
    assertTrue(FillingCheckpoint.syntheticContentHash(""));
  }

  @Test
  void fixtureHashesCountAsSyntheticWhenTheSessionCatalogIsOffline() throws Exception {
    Path report = temp.resolve("synthetic.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(temp.resolve("synthetic-store"));
    OfflineCatalog catalog = new OfflineCatalog();
    SequentialFillingModel model = new SequentialFillingModel(() -> latestDocument(store), false);
    int exit =
        WorkCheckpointHarness.run(
            new CheckpointRequest(
                "filling",
                "om-progressive",
                report,
                fixtureRoot(),
                true,
                store,
                "run-synthetic",
                false,
                null,
                40,
                null,
                false),
            new CheckpointSession("configured-provider", "configured-model", false, model, catalog));
    JsonNode body = JSON.readTree(report.toFile());
    assertTrue(exit == 3 || exit == 0, readReport(report));
    assertTrue(body.path("syntheticSchemaCount").asInt() > 0, readReport(report));
    JsonNode provenance = JSON.readTree(report.resolveSibling("contract-provenance.json").toFile());
    assertTrue(provenance.size() > 0);
    for (JsonNode row : provenance) {
      assertTrue(row.path("synthetic").asBoolean(), row.toString());
    }
  }

  private static ObjectNode proseOnlyDocument() {
    ObjectNode document = JSON.createObjectNode();
    document.put("schemaVersion", 2);
    ObjectNode transfer =
        document.putObject("flow").putArray("steps").addObject().putObject("data").putArray("transfers").addObject();
    ArrayNode rules = transfer.putArray("rules");
    ObjectNode prose = rules.addObject();
    prose.putObject("target").put("fieldPath", "$.Subject");
    prose.put("behavior", "formatted fallback low Normal order creation date woOrderType");
    prose.putArray("sources");
    prose.putArray("constants");
    ObjectNode status = rules.addObject();
    status.putObject("target").put("fieldPath", "$.Status");
    status.putArray("constants").addObject().put("value", "Not Started");
    status.put("behavior", "");
    status.putArray("sources");
    ObjectNode failure = rules.addObject();
    failure.putObject("target").put("fieldPath", "$.error.code");
    failure.putArray("constants").addObject().put("value", "SALESFORCE_TASK_CREATE_ERROR");
    failure.put("behavior", "");
    failure.putArray("sources");
    ObjectNode process = rules.addObject();
    process.putObject("target").put("fieldPath", "$.processId");
    process.put("behavior", "");
    process.putArray("sources");
    process.putArray("constants");
    return document;
  }

  private static ObjectNode successTransfer() {
    ObjectNode document = JSON.createObjectNode();
    document.putArray("sources").addObject().put("id", "src");
    ArrayNode steps = document.putObject("flow").putArray("steps");
    ObjectNode trigger = steps.addObject();
    trigger.put("id", "start");
    trigger.put("kind", "TRIGGER");
    ObjectNode reply = steps.addObject();
    reply.put("id", "reply");
    reply.put("kind", "REPLY");
    ObjectNode transfer = reply.putObject("data").putArray("transfers").addObject();
    transfer.put("id", "t1");
    transfer.putArray("requiredRetainedIds");
    transfer.putArray("sourcePorts").addObject().put("stepId", "call").put("portName", "success");
    ObjectNode call = steps.addObject();
    call.put("id", "call");
    call.put("kind", "SERVICE_CALL");
    return document;
  }

  private static boolean passed(ArrayNode checklist, String id) {
    return row(checklist, id).path("passed").asBoolean();
  }

  private static String expected(ArrayNode checklist, String id) {
    return row(checklist, id).path("expected").asText();
  }

  private static JsonNode row(ArrayNode rows, String id) {
    for (JsonNode row : rows) {
      if (id.equals(row.path("id").asText()) || id.equals(row.path("field").asText())) {
        return row;
      }
    }
    return JSON.missingNode();
  }

  private static Path fixtureRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    Path root = cursor.getFileName().toString().equals("ai-service") ? cursor.getParent() : cursor;
    return root.resolve("ai-service/e2e/product-pipeline/fixtures/work-checkpoints");
  }

  private static JsonNode latestDocument(ArtifactBlobStore store) {
    JsonNode found = JSON.createObjectNode();
    long sequence = -1L;
    for (String key : store.list("")) {
      if (!key.endsWith(".json") || key.startsWith("harness/")) {
        continue;
      }
      try {
        JsonNode tree = JSON.readTree(store.get(key).orElseThrow());
        JsonNode payload = tree.path("payload");
        if (!payload.path("flow").isObject()) {
          continue;
        }
        long candidate = tree.path("sequence").asLong(-1L);
        if (candidate >= sequence) {
          sequence = candidate;
          found = payload;
        }
      } catch (Exception ignored) {
        // A harness cursor is not a work document.
      }
    }
    return found;
  }

  private static String readReport(Path report) throws Exception {
    return java.nio.file.Files.readString(report);
  }
}
