package org.qubership.integration.platform.ai.plan.workdocument.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.service.output.OutputParsingException;
import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
import org.qubership.integration.platform.ai.plan.workdocument.task.SchemaFragment;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;

/**
 * Initial mapping and a named-rule repair against the work document. The model proposes structured
 * references and behavior text. Java checks paths, renames, and retained fields before publication.
 */
public final class WorkMapping {

  public static final String SKILL_ID = "data-mapping";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final WorkDocumentService documents;
  private final WorkTaskExecutor executor;

  public WorkMapping(WorkDocumentService documents, WorkTaskExecutor executor) {
    this.documents = documents;
    this.executor = executor;
  }

  public WorkCommit interpret(String runId, WorkTaskMaterials materials, WorkTaskModel model) {
    return run(runId, null, materials, model);
  }

  public WorkCommit repair(
      String runId, String ruleId, WorkTaskMaterials materials, WorkTaskModel model) {
    return run(runId, ruleId, materials, model);
  }

  private WorkCommit run(
      String runId, String repairRuleId, WorkTaskMaterials materials, WorkTaskModel model) {
    WorkDocumentState state = documents.read(runId);
    WorkTaskScope scope =
        repairRuleId == null
            ? new WorkTaskScope(
                "mapping-initial",
                state.revision(),
                WorkStage.DATA_BEHAVIOR,
                SKILL_ID,
                List.of(),
                true,
                false,
                false,
                List.of(),
                List.of())
            : new WorkTaskScope(
                "mapping-repair-" + repairRuleId,
                state.revision(),
                WorkStage.DATA_BEHAVIOR,
                SKILL_ID,
                List.of(repairRuleId),
                false,
                true,
                false,
                List.of(),
                List.of());
    JsonNode document = JSON.valueToTree(state.document());
    return executor.execute(runId, scope, materials, checked(document, materials, repairRuleId, model));
  }

  private static WorkTaskModel checked(
      JsonNode document, WorkTaskMaterials materials, String repairRuleId, WorkTaskModel model) {
    return prompt -> {
      String output;
      try {
        output = model.complete(prompt);
      } catch (OutputParsingException failure) {
        throw new WorkDocumentRejectedException(
            "MALFORMED_CAPTURE",
            "Mapping capture could not be parsed. The task was not completed.");
      }
      JsonNode tree = read(output);
      if (tree.has("steps")) {
        for (JsonNode step : tree.path("steps")) {
          if ("SERVICE_CALL".equals(step.path("kind").asText())) {
            throw new WorkDocumentRejectedException(
                "DUPLICATE_SERVICE_CALL",
                "Mapping stays on the existing call. Remove the extra service call.");
          }
        }
      }
      String question = problem(document, materials, repairRuleId, tree);
      if (question != null) {
        return clarification(question, sourceId(document));
      }
      return complete(tree);
    };
  }

  private static JsonNode read(String output) {
    if (output == null || output.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "Model output is empty. Send one capture object for this task.");
    }
    try {
      return JSON.readTree(output);
    } catch (Exception failure) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "Model output is not a capture object. The task was not completed.");
    }
  }

  private static String problem(
      JsonNode document, WorkTaskMaterials materials, String repairRuleId, JsonNode tree) {
    if (!"PREPARED".equals(tree.path("outcome").asText())) {
      return null;
    }
    if (repairRuleId != null) {
      for (JsonNode rule : tree.path("rules")) {
        if (!repairRuleId.equals(rule.path("existingId").asText())) {
          return "Repair may replace only rule " + repairRuleId + ".";
        }
      }
    }
    if (tree.path("rules").isEmpty() && tree.path("retainedValues").isEmpty()) {
      boolean explicit = false;
      for (JsonNode transfer : tree.path("transfers")) {
        String decision = transfer.path("decision").asText();
        if ("NO_MAPPING".equals(decision)) {
          if (!transfer.path("requirementRefs").isArray() || transfer.path("requirementRefs").isEmpty()) {
            return "An explicit no-mapping decision needs evidence. Name the record that supports it.";
          }
          explicit = true;
        } else if (!decision.isBlank()) {
          return "Mapping decision " + decision + " is unknown. Use an empty decision or NO_MAPPING.";
        }
      }
      if (!explicit) {
        return "Empty rules do not show that mapping is unnecessary. Supply the rules or record an explicit no-mapping decision with evidence.";
      }
    }
    List<String> retained = new ArrayList<>();
    for (JsonNode value : tree.path("retainedValues")) {
      retained.add(leaf(value.path("source").path("fieldPath").asText()));
    }
    for (JsonNode rule : tree.path("rules")) {
      JsonNode target = rule.path("target");
      String path = target.path("fieldPath").asText();
      String targetLeaf = leaf(path);
      if ("OUTBOUND_REQUEST".equals(target.path("port").asText())
          && serviceCall(document, target.path("stepId").asText())
          && retained.contains(targetLeaf)) {
        throw new WorkDocumentRejectedException(
            "RETAINED_ON_REQUEST",
            "Retained field "
                + targetLeaf
                + " stays in context. Do not add it to the service request.");
      }
      String prefixed = inventedPrefix(document, materials, target.path("stepId").asText(), portName(target.path("port").asText()), path);
      if (prefixed != null) {
        return prefixed;
      }
      String missing = unknownPath(materials, target.path("stepId").asText(), portName(target.path("port").asText()), path);
      if (missing != null) {
        return missing;
      }
      for (JsonNode source : rule.path("sources")) {
        if (!"RETAINED".equals(source.path("kind").asText())) {
          continue;
        }
        String sourceLeaf = sourceLeaf(tree, source);
        if (sourceLeaf.isBlank() || sourceLeaf.equalsIgnoreCase(targetLeaf)) {
          continue;
        }
        if (!renameEvidence(materials, sourceLeaf, targetLeaf)) {
          return "Field "
              + targetLeaf
              + " does not match source "
              + sourceLeaf
              + ". Provide context evidence for that relationship.";
        }
      }
    }
    return null;
  }

  private static boolean serviceCall(JsonNode document, String stepId) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (stepId.equals(step.path("id").asText())) {
        return "SERVICE_CALL".equals(step.path("kind").asText());
      }
    }
    return false;
  }

  private static String inventedPrefix(
      JsonNode document, WorkTaskMaterials materials, String stepId, String port, String path) {
    String first = firstSegment(path);
    if (first.isBlank() || schemaHas(materials, stepId, port, first)) {
      return null;
    }
    for (JsonNode step : document.path("flow").path("steps")) {
      if (!stepId.equals(step.path("id").asText()) && !first.equals(step.path("label").asText())) {
        continue;
      }
      if (first.equals(step.path("label").asText())
          || first.equals(step.path("binding").path("operationId").asText())) {
        return "Name "
            + first
            + " is the contract, not a JSON prefix. Use the field path from the selected schema.";
      }
    }
    return null;
  }

  private static String unknownPath(WorkTaskMaterials materials, String stepId, String port, String path) {
    if (path.isBlank() || schemaHasPath(materials, stepId, port, path)) {
      return null;
    }
    boolean covered = false;
    for (SchemaFragment schema : materials.schemas()) {
      if (stepId.equals(schema.stepId()) && port.equals(schema.portName())) {
        covered = true;
      }
    }
    if (!covered) {
      return null;
    }
    return "Field path " + path + " is not in the selected contract. Name a contract field or ask.";
  }

  private static boolean schemaHas(WorkTaskMaterials materials, String stepId, String port, String name) {
    JsonNode properties = properties(materials, stepId, port);
    return properties != null && properties.has(name);
  }

  private static boolean schemaHasPath(WorkTaskMaterials materials, String stepId, String port, String path) {
    JsonNode properties = properties(materials, stepId, port);
    if (properties == null) {
      return false;
    }
    String rest = path.startsWith("$.") ? path.substring(2) : path;
    JsonNode current = properties;
    int start = 0;
    while (start <= rest.length()) {
      int dot = rest.indexOf('.', start);
      String name = dot < 0 ? rest.substring(start) : rest.substring(start, dot);
      if (name.isBlank() || !current.has(name)) {
        return false;
      }
      if (dot < 0) {
        return true;
      }
      current = current.path(name).path("properties");
      start = dot + 1;
    }
    return false;
  }

  private static JsonNode properties(WorkTaskMaterials materials, String stepId, String port) {
    for (SchemaFragment schema : materials.schemas()) {
      if (!stepId.equals(schema.stepId()) || !port.equals(schema.portName())) {
        continue;
      }
      try {
        return JSON.readTree(schema.body()).path("properties");
      } catch (Exception failure) {
        return null;
      }
    }
    return null;
  }

  private static String sourceLeaf(JsonNode tree, JsonNode source) {
    if ("RETAINED".equals(source.path("kind").asText())) {
      String id = source.path("retainedValueId").asText();
      for (JsonNode value : tree.path("retainedValues")) {
        if (id.equals(value.path("alias").asText()) || id.equals(value.path("existingId").asText())) {
          return leaf(value.path("source").path("fieldPath").asText());
        }
      }
      return "";
    }
    return leaf(source.path("fieldPath").asText());
  }

  private static boolean renameEvidence(WorkTaskMaterials materials, String sourceLeaf, String targetLeaf) {
    for (String constraint : materials.globalConstraints()) {
      if (constraint.contains(sourceLeaf) && constraint.contains(targetLeaf)) {
        return true;
      }
    }
    return false;
  }

  private static String portName(String port) {
    return switch (port) {
      case "INBOUND_PAYLOAD" -> "payload";
      case "OUTBOUND_REQUEST" -> "request";
      case "SUCCESS_RESPONSE" -> "success";
      case "FAILURE_OUTCOME" -> "failure";
      case "RETAINED_CONTEXT" -> "context";
      default -> port;
    };
  }

  private static String firstSegment(String path) {
    String rest = path.startsWith("$.") ? path.substring(2) : path;
    int dot = rest.indexOf('.');
    return dot < 0 ? rest : rest.substring(0, dot);
  }

  private static String leaf(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
  }

  private static String sourceId(JsonNode document) {
    String id = document.path("sources").path(0).path("id").asText();
    return id.isBlank() ? "src-map" : id;
  }

  private static String complete(JsonNode tree) {
    ObjectNode body = tree.deepCopy();
    for (String name :
        List.of(
            "requirements",
            "steps",
            "connections",
            "sequenceGroups",
            "conditionGroups",
            "splitGroups",
            "loopGroups",
            "retryGroups",
            "errorScopeGroups",
            "transfers",
            "rules",
            "retainedValues",
            "deletes",
            "clarificationEvidenceIds",
            "defectEvidenceIds")) {
      if (!body.has(name)) {
        body.set(name, JSON.createArrayNode());
      }
    }
    for (String name :
        List.of("question", "unresolvedChoice", "defectRecordRef", "contradiction", "issueCategory")) {
      if (!body.has(name)) {
        body.put(name, "");
      }
    }
    return body.toString();
  }

  private static String clarification(String question, String sourceId) {
    ObjectNode body = JSON.createObjectNode();
    body.put("outcome", "NEEDS_CLARIFICATION");
    for (String name :
        List.of(
            "requirements",
            "steps",
            "connections",
            "sequenceGroups",
            "conditionGroups",
            "splitGroups",
            "loopGroups",
            "retryGroups",
            "errorScopeGroups",
            "transfers",
            "rules",
            "retainedValues",
            "deletes",
            "clarificationEvidenceIds",
            "defectEvidenceIds")) {
      body.set(name, JSON.createArrayNode());
    }
    ((ArrayNode) body.get("clarificationEvidenceIds")).add(sourceId);
    body.put("question", question);
    body.put("unresolvedChoice", "mapping-field");
    body.put("defectRecordRef", "");
    body.put("contradiction", "");
    body.put("issueCategory", "");
    return body.toString();
  }
}
