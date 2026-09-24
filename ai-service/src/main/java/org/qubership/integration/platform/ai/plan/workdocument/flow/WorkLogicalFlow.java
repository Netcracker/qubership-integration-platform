package org.qubership.integration.platform.ai.plan.workdocument.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
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
    WorkTaskScope scope =
        new WorkTaskScope(
            "logical-design",
            state.revision(),
            WorkStage.LOGICAL_FLOW,
            SKILL_ID,
            List.of(),
            true,
            false,
            false,
            List.of(),
            List.of());
    return executor.execute(runId, scope, withInstructions(materials), checked(model));
  }

  public WorkCommit repair(
      String runId, String recordId, WorkTaskMaterials materials, WorkTaskModel model) {
    WorkDocumentState state = documents.read(runId);
    return executor.execute(
        runId, repairScope(state, recordId), withInstructions(materials), checked(model));
  }

  public static WorkTaskScope repairScope(WorkDocumentState state, String defectRecordId) {
    return new WorkTaskScope(
        "logical-repair-" + defectRecordId,
        state.revision(),
        WorkStage.LOGICAL_FLOW,
        SKILL_ID,
        List.of(defectRecordId),
        false,
        true,
        false,
        List.of(),
        List.of());
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

  private static WorkTaskModel checked(WorkTaskModel model) {
    return prompt -> {
      String output = model.complete(prompt);
      rejectSynchronousResult(output);
      return output;
    };
  }

  private static void rejectSynchronousResult(String output) {
    JsonNode tree;
    try {
      tree = JSON.readTree(output);
    } catch (Exception failure) {
      return;
    }
    if (!"PREPARED".equals(tree.path("outcome").asText())) {
      return;
    }
    for (JsonNode step : tree.path("steps")) {
      if (!receivesSynchronousResult(step, tree.path("connections"))) {
        continue;
      }
      throw new WorkDocumentRejectedException(
          "SYNCHRONOUS_RESULT",
          "A synchronous result stays on the call as success and failure outcomes. Remove the extra receive step.");
    }
  }

  private static boolean receivesSynchronousResult(JsonNode step, JsonNode connections) {
    String kind = step.path("kind").asText();
    if (!"LOCAL".equals(kind) && !"TRIGGER".equals(kind)) {
      return false;
    }
    if (step.path("sourceRefs").size() > 0) {
      return false;
    }
    String ref = ref(step);
    boolean targeted = false;
    for (JsonNode connection : connections) {
      boolean touches = ref.equals(connection.path("sourceStepRef").asText())
          || ref.equals(connection.path("targetStepRef").asText());
      if (touches && "correlation".equals(connection.path("outcome").asText())) {
        return false;
      }
      if (ref.equals(connection.path("targetStepRef").asText())
          && ("success".equals(connection.path("outcome").asText())
              || "failure".equals(connection.path("outcome").asText()))) {
        targeted = true;
      }
    }
    return targeted;
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
