package org.qubership.integration.platform.ai.plan.workdocument.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.qubership.integration.platform.ai.plan.workdocument.CreationAllowance;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentCaptureSchema;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRecordKind;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;

/**
 * Initial logical design and a bounded repair. The model cannot turn a synchronous result into
 * another interaction or collapse two intentional calls.
 */
public final class WorkLogicalFlow {

  public static final String SKILL_ID = "logical-design";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String INSTRUCTIONS = loadInstructions();

  private final WorkDocumentService documents;
  private final WorkTaskExecutor executor;

  public WorkLogicalFlow(WorkDocumentService documents, WorkTaskExecutor executor) {
    this.documents = documents;
    this.executor = executor;
  }

  public WorkCommit design(String runId, WorkTaskMaterials materials, WorkTaskModel model) {
    WorkDocumentState state = documents.read(runId);
    String recordId = state.document().documentId();
    WorkTaskScope scope =
        new WorkTaskScope(
            WorkTaskPlanner.taskId(WorkTaskKind.LOGICAL_DESIGN, recordId),
            state.revision(),
            WorkStage.LOGICAL_FLOW,
            SKILL_ID,
            List.of(),
            true,
            false,
            false,
            List.of(),
            List.of(),
            CreationAllowance.anyParent(
                WorkRecordKind.REQUIREMENT,
                WorkRecordKind.STEP,
                WorkRecordKind.CONNECTION,
                WorkRecordKind.SEQUENCE_GROUP,
                WorkRecordKind.CONDITION_GROUP,
                WorkRecordKind.SPLIT_GROUP,
                WorkRecordKind.LOOP_GROUP,
                WorkRecordKind.RETRY_GROUP,
                WorkRecordKind.ERROR_SCOPE),
            List.of(),
            WorkTaskPlanner.taskKey(WorkTaskKind.LOGICAL_DESIGN, recordId),
            WorkTaskKind.LOGICAL_DESIGN,
            "",
            null);
    return execute(runId, scope, materials, model);
  }

  public WorkCommit repair(
      String runId, String recordId, WorkTaskMaterials materials, WorkTaskModel model) {
    WorkDocumentState state = documents.read(runId);
    return execute(runId, repairScope(state, recordId), materials, model);
  }

  public static WorkTaskScope repairScope(WorkDocumentState state, String defectRecordId) {
    return new WorkTaskScope(
        WorkTaskPlanner.taskId(WorkTaskKind.LOGICAL_DESIGN, defectRecordId),
        state.revision(),
        WorkStage.LOGICAL_FLOW,
        SKILL_ID,
        List.of(defectRecordId),
        false,
        true,
        false,
        List.of(),
        List.of(),
        List.of(),
        List.of(defectRecordId),
        WorkTaskPlanner.taskKey(WorkTaskKind.LOGICAL_DESIGN, defectRecordId),
        WorkTaskKind.LOGICAL_DESIGN,
        "",
        null);
  }

  public static JsonNode planningTopology(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("flow");
  }

  private static WorkTaskMaterials withInstructions(WorkTaskMaterials materials) {
    List<String> constraints = new ArrayList<>();
    constraints.add(INSTRUCTIONS);
    constraints.addAll(materials.globalConstraints());
    materials.sourceEvidence().forEach((id, text) -> constraints.add("source " + id + " " + text));
    return new WorkTaskMaterials(materials.schemas(), constraints, materials.sourceEvidence());
  }

  private WorkCommit execute(
      String runId, WorkTaskScope scope, WorkTaskMaterials materials, WorkTaskModel model) {
    JsonObjectSchema schema =
        WorkDocumentCaptureSchema.responseSchema(WorkTaskKind.LOGICAL_DESIGN, null);
    return executor.execute(
        runId,
        scope,
        withInstructions(materials),
        schema,
        request -> {
          String output = model.complete(request);
          JsonNode tree = WorkDocumentCaptureSchema.readObject(output, request.responseSchema());
          rejectSynchronousResult(tree);
          return WorkDocumentCaptureSchema.withUniversalLists(tree);
        });
  }

  private static void rejectSynchronousResult(JsonNode tree) {
    if (!"PREPARED".equals(tree.path("outcome").asText())) {
      return;
    }
    for (JsonNode step : tree.path("steps")) {
      if (!receivesSynchronousResult(step, tree.path("steps"), tree.path("connections"))) {
        continue;
      }
      throw new WorkDocumentRejectedException(
          "SYNCHRONOUS_RESULT",
          "A synchronous result stays on the call as success and failure outcomes. Remove the extra receive step.");
    }
  }

  private static boolean receivesSynchronousResult(JsonNode step, JsonNode steps, JsonNode connections) {
    if (!"TRIGGER".equals(step.path("kind").asText()) || step.path("sourceRefs").size() > 0) {
      return false;
    }
    String ref = ref(step);
    Set<String> calls = new LinkedHashSet<>();
    for (JsonNode candidate : steps) {
      if ("SERVICE_CALL".equals(candidate.path("kind").asText())) {
        calls.add(ref(candidate));
      }
    }
    Map<String, Set<String>> outcomes = new LinkedHashMap<>();
    for (JsonNode connection : connections) {
      boolean touches = ref.equals(connection.path("sourceStepRef").asText())
          || ref.equals(connection.path("targetStepRef").asText());
      if (touches && "correlation".equals(connection.path("outcome").asText())) {
        return false;
      }
      String source = connection.path("sourceStepRef").asText();
      String outcome = connection.path("outcome").asText();
      if (ref.equals(connection.path("targetStepRef").asText())
          && calls.contains(source)
          && ("success".equals(outcome) || "failure".equals(outcome))) {
        outcomes.computeIfAbsent(source, key -> new LinkedHashSet<>()).add(outcome);
      }
    }
    for (Set<String> fromOneCall : outcomes.values()) {
      if (fromOneCall.contains("success") && fromOneCall.contains("failure")) {
        return true;
      }
    }
    return false;
  }

  private static String ref(JsonNode step) {
    String alias = step.path("alias").asText();
    return alias.isBlank() ? step.path("existingId").asText() : alias;
  }

  private static String loadInstructions() {
    try (InputStream in = WorkLogicalFlow.class.getResourceAsStream("logical-design.md")) {
      if (in == null) {
        throw new IllegalStateException("Logical design instructions are missing.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("Logical design instructions could not be read.", failure);
    }
  }
}
