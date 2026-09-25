package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.output.OutputParsingException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.workdocument.task.SchemaFragment;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskContext;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;

/**
 * Resolves unresolved retained placeholders for one producer. The model names a field path. Java
 * writes the producer and the payload port.
 */
public final class WorkRetainedContext {

  public static final String SKILL_ID = WorkTaskPlanner.DESCRIBE_CONTEXT_SKILL;

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String INSTRUCTIONS = loadInstructions();

  private final WorkDocumentService documents;
  private final WorkTaskExecutor executor;

  public WorkRetainedContext(WorkDocumentService documents, WorkTaskExecutor executor) {
    this.documents = documents;
    if (executor == null) {
      throw new IllegalArgumentException("A task executor is required.");
    }
    this.executor = executor;
  }

  public WorkCommit describe(
      String runId, String producerStepId, WorkTaskMaterials materials, WorkTaskModel model) {
    if (producerStepId == null || producerStepId.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Context description requires the producer step. Name the assigned producer.");
    }
    WorkDocumentState state = documents.read(runId);
    JsonNode document = JSON.valueToTree(state.document());
    if (step(document, producerStepId) == null) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Producer " + producerStepId + " does not exist. Describe context for an existing step.");
    }
    List<String> retainedIds = unresolved(document, producerStepId);
    if (retainedIds.isEmpty()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Producer " + producerStepId + " has no unresolved placeholder. Assign a placeholder first.");
    }
    List<String> evidenceRefs = evidence(document, producerStepId);
    CaptureChoices choices = new CaptureChoices(List.of(), evidenceRefs, List.of(), retainedIds);
    String taskId = WorkTaskPlanner.taskId(WorkTaskKind.DESCRIBE_CONTEXT, producerStepId);
    String taskKey = WorkTaskPlanner.taskKey(WorkTaskKind.DESCRIBE_CONTEXT, producerStepId);
    WorkTaskScope scope = scope(state, producerStepId, taskId, taskKey, retainedIds);
    Optional<WorkCommit> prior = executor.publishedResult(runId, scope);
    if (prior.isPresent()) {
      return prior.get();
    }
    executor.reserve(runId, scope);
    JsonObjectSchema schema = WorkDocumentCaptureSchema.responseSchema(WorkTaskKind.DESCRIBE_CONTEXT, choices);
    String output;
    try {
      output =
          model.complete(
              new WorkTaskRequest(
                  taskId,
                  taskKey,
                  WorkTaskKind.DESCRIBE_CONTEXT,
                  WorkTaskContext.prompt(state, scope, instructed(materials, producerStepId, retainedIds)),
                  schema));
    } catch (OutputParsingException failure) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "Context capture could not be parsed. The task was not completed.");
    }
    JsonNode tree = WorkDocumentCaptureSchema.readObject(output, schema);
    String outcome = tree.path("outcome").asText();
    if ("NEEDS_CLARIFICATION".equals(outcome)) {
      return ask(runId, state, scope, tree, evidenceRefs);
    }
    if ("INPUT_DEFECT".equals(outcome)) {
      return defect(runId, state, scope, tree, evidenceRefs);
    }
    if (!"PREPARED".equals(outcome)) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE",
          "Outcome " + outcome + " is unknown. Use PREPARED, NEEDS_CLARIFICATION, or INPUT_DEFECT.");
    }
    if (!questionText(tree).isBlank() || !tree.path("defect").path("recordRef").asText().isBlank()) {
      throw new WorkDocumentRejectedException(
          "CONTRADICTORY_OUTCOME",
          "A prepared capture cannot also report a question or a defect. Send one outcome.");
    }
    if (tree.path("values").isEmpty()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Describe context needs a field path for an assigned placeholder. Name the retained id and the field.");
    }
    return documents.apply(
        runId,
        scope,
        capture(document, materials, producerStepId, tree.path("values"), choices),
        taskId + ":" + state.revision());
  }

  private WorkCommit ask(
      String runId, WorkDocumentState state, WorkTaskScope scope, JsonNode tree, List<String> evidenceRefs) {
    if (!tree.path("values").isEmpty()) {
      throw new WorkDocumentRejectedException(
          "CONTRADICTORY_OUTCOME",
          "A clarification needs one question and no values. Remove the values.");
    }
    JsonNode question = tree.path("question");
    String text = question.path("text").asText();
    if (text.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "A clarification needs question text. Name the unresolved choice.");
    }
    requireMembers(texts(question.path("evidenceRefs")), evidenceRefs);
    QuestionSubject subject = subject(question);
    return documents.recordQuestion(
        runId,
        scope,
        text,
        subject,
        List.of(),
        texts(question.path("evidenceRefs")),
        scope.taskId() + ":" + state.revision() + ":question");
  }

  private WorkCommit defect(
      String runId, WorkDocumentState state, WorkTaskScope scope, JsonNode tree, List<String> evidenceRefs) {
    if (!tree.path("values").isEmpty() || !questionText(tree).isBlank()) {
      throw new WorkDocumentRejectedException(
          "CONTRADICTORY_OUTCOME",
          "A defect capture cannot include values or a question. Send the defect alone.");
    }
    JsonNode defect = tree.path("defect");
    requireMembers(texts(defect.path("evidenceRefs")), evidenceRefs);
    ObjectNode body = JSON.createObjectNode();
    body.put("outcome", "INPUT_DEFECT");
    body.put("defectRecordRef", defect.path("recordRef").asText());
    body.put("contradiction", defect.path("contradiction").asText());
    body.put("issueCategory", defect.path("category").asText());
    body.set("defectEvidenceIds", array(texts(defect.path("evidenceRefs"))));
    return documents.apply(
        runId,
        scope,
        WorkDocumentCaptureSchema.parse(WorkDocumentCaptureSchema.withUniversalLists(body)),
        scope.taskId() + ":" + state.revision() + ":defect");
  }

  private static WorkTaskCapture capture(
      JsonNode document,
      WorkTaskMaterials materials,
      String producerStepId,
      JsonNode values,
      CaptureChoices choices) {
    ObjectNode body = JSON.createObjectNode();
    body.put("outcome", "PREPARED");
    ArrayNode retained = body.putArray("retainedValues");
    for (JsonNode value : values) {
      String id = value.path("retainedId").asText();
      if (!choices.retainedIds().contains(id)) {
        throw new WorkDocumentRejectedException(
            "MALFORMED_REFERENCE",
            "Retained id " + id + " is not assigned to this producer. Update only the listed placeholders.");
      }
      JsonNode existing = findRetained(document, id);
      String path = value.path("fieldPath").asText();
      SchemaFragment schema = schema(materials, producerStepId, "payload");
      if (schema == null) {
        throw new WorkDocumentRejectedException(
            "MISSING_SCHEMA",
            "Producer " + producerStepId + " has no payload schema. Load that schema before naming a field.");
      }
      if (path == null || path.isBlank() || "$".equals(path) || !schema.containsPath(path.startsWith("$.") ? path : "$." + path)) {
        throw new WorkDocumentRejectedException(
            "MALFORMED_REFERENCE",
            "Field path " + path + " is not in the producer schema. Name a field on " + producerStepId + ".");
      }
      requireMembers(texts(value.path("evidenceRefs")), choices.evidenceRefs());
      ObjectNode stored = retained.addObject();
      stored.put("existingId", id);
      stored.put("alias", "");
      stored.put("stepRef", producerStepId);
      ObjectNode source = stored.putObject("source");
      source.put("kind", "STEP_PORT");
      source.put("stepId", producerStepId);
      source.put("port", "payload");
      source.put("fieldPath", path.startsWith("$.") ? path : "$." + path);
      source.put("retainedValueId", "");
      stored.put("intendedUse", existing == null ? "" : existing.path("intendedUse").asText());
      stored.set("evidenceRefs", array(texts(value.path("evidenceRefs"))));
    }
    return WorkDocumentCaptureSchema.parse(WorkDocumentCaptureSchema.withUniversalLists(body));
  }

  private static QuestionSubject subject(JsonNode question) {
    QuestionFieldRef source =
        new QuestionFieldRef(
            question.path("sourceStepId").asText(),
            question.path("sourcePort").asText(),
            question.path("sourceField").asText(),
            question.path("sourceRetainedId").asText());
    QuestionFieldRef target =
        new QuestionFieldRef(
            question.path("targetStepId").asText(),
            question.path("targetPort").asText(),
            question.path("targetField").asText(),
            question.path("targetRetainedId").asText());
    String kind = question.path("choiceKind").asText();
    try {
      if ("FIELD_RELATIONSHIP".equals(kind)) {
        return QuestionSubject.fieldRelationship(source, target);
      }
      return new QuestionSubject(QuestionChoiceKind.UNSPECIFIED, source, target);
    } catch (IllegalArgumentException failure) {
      throw new WorkDocumentRejectedException("MALFORMED_REFERENCE", failure.getMessage());
    }
  }

  private static WorkTaskScope scope(
      WorkDocumentState state, String producerStepId, String taskId, String taskKey, List<String> retainedIds) {
    return new WorkTaskScope(
        taskId,
        state.revision(),
        WorkStage.DATA_BEHAVIOR,
        SKILL_ID,
        retainedIds,
        false,
        true,
        false,
        List.of(),
        List.of(),
        List.of(new CreationAllowance(WorkRecordKind.RETAINED_VALUE, producerStepId)),
        retainedIds,
        taskKey,
        WorkTaskKind.DESCRIBE_CONTEXT,
        "",
        null);
  }

  private static WorkTaskMaterials instructed(
      WorkTaskMaterials materials, String producerStepId, List<String> retainedIds) {
    List<String> constraints = new ArrayList<>();
    constraints.add(INSTRUCTIONS);
    constraints.add("producer " + producerStepId);
    for (String id : retainedIds) {
      constraints.add("retained " + id);
    }
    constraints.addAll(materials.globalConstraints());
    return new WorkTaskMaterials(materials.schemas(), constraints, materials.sourceEvidence());
  }

  private static List<String> unresolved(JsonNode document, String producerStepId) {
    List<String> ids = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode retained : step.path("data").path("retainedValues")) {
        String producer = retained.path("producerStepId").asText();
        if (producer.isBlank()) {
          producer = step.path("id").asText();
        }
        if (!producerStepId.equals(producer)) {
          continue;
        }
        String resolution = retained.path("resolution").asText();
        String path = retained.path("source").path("fieldPath").asText();
        if ("UNRESOLVED".equals(resolution) || path.isBlank()) {
          ids.add(retained.path("id").asText());
        }
      }
    }
    return List.copyOf(ids);
  }

  private static List<String> evidence(JsonNode document, String producerStepId) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    JsonNode step = step(document, producerStepId);
    if (step != null) {
      for (JsonNode sourceId : step.path("sourceIds")) {
        ids.add(sourceId.asText());
      }
    }
    for (JsonNode source : document.path("sources")) {
      if (!ids.contains(source.path("id").asText())) {
        continue;
      }
      for (JsonNode corrected : source.path("correctionOf")) {
        ids.add(corrected.asText());
      }
      for (JsonNode passage : source.path("passages")) {
        ids.add(passage.path("id").asText());
      }
    }
    ids.remove("");
    return List.copyOf(ids);
  }

  private static JsonNode step(JsonNode document, String stepId) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (stepId.equals(step.path("id").asText())) {
        return step;
      }
    }
    return null;
  }

  private static JsonNode findRetained(JsonNode document, String id) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode retained : step.path("data").path("retainedValues")) {
        if (id.equals(retained.path("id").asText())) {
          return retained;
        }
      }
    }
    return null;
  }

  private static SchemaFragment schema(WorkTaskMaterials materials, String stepId, String port) {
    for (SchemaFragment candidate : materials.schemas()) {
      if (stepId.equals(candidate.stepId()) && port.equals(candidate.portName())) {
        return candidate;
      }
    }
    return null;
  }

  private static String questionText(JsonNode tree) {
    return tree.path("question").path("text").asText();
  }

  private static void requireMembers(List<String> values, List<String> allowed) {
    if (values.isEmpty()) {
      throw new WorkDocumentRejectedException(
          "UNEVIDENCED_MAPPING", "This value needs evidence. Cite a listed evidence ref.");
    }
    for (String value : values) {
      if (!allowed.contains(value)) {
        throw new WorkDocumentRejectedException(
            "MALFORMED_REFERENCE", "Evidence ref " + value + " is not allowed. Use a listed value.");
      }
    }
  }

  private static List<String> texts(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode child : node) {
        if (!child.asText().isBlank()) {
          values.add(child.asText());
        }
      }
    }
    return values;
  }

  private static ArrayNode array(List<String> values) {
    ArrayNode array = JSON.createArrayNode();
    for (String value : values) {
      array.add(value);
    }
    return array;
  }

  private static String loadInstructions() {
    try (InputStream in = WorkRetainedContext.class.getResourceAsStream("context/describe-context.md")) {
      if (in == null) {
        throw new IllegalStateException("Context instructions are missing.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("Context instructions could not be read.", failure);
    }
  }
}
