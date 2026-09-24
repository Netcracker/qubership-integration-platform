package org.qubership.integration.platform.ai.plan.workdocument.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;

/** Compact prompt for one server-owned scope. Unrelated rules and schemas stay out. */
public final class WorkTaskContext {

  private static final ObjectMapper JSON = new ObjectMapper();

  private WorkTaskContext() {}

  public static String prompt(
      WorkDocumentState state, WorkTaskScope scope, WorkTaskMaterials materials) {
    JsonNode document = JSON.valueToTree(state.document());
    Set<String> owned = Set.copyOf(scope.ownedRecordIds());
    JsonNode transfers = ownedTransfers(document, owned);
    Set<String> ownedSteps = ownedSteps(document, transfers);
    Set<String> ports = ports(transfers);
    Set<String> sources = governingSources(document, transfers);

    StringBuilder prompt = new StringBuilder();
    prompt.append("task ").append(scope.taskId()).append('\n');
    prompt.append("stage ").append(scope.stage()).append('\n');
    for (String constraint : materials.globalConstraints()) {
      prompt.append("constraint ").append(constraint).append('\n');
    }
    for (JsonNode step : document.path("flow").path("steps")) {
      prompt
          .append("step ")
          .append(step.path("id").asText())
          .append(' ')
          .append(step.path("kind").asText())
          .append(' ')
          .append(step.path("label").asText())
          .append('\n');
    }
    for (JsonNode connection : document.path("flow").path("connections")) {
      prompt
          .append("connection ")
          .append(connection.path("sourceStepId").asText())
          .append(' ')
          .append(connection.path("outcome").asText())
          .append(' ')
          .append(connection.path("targetStepId").asText())
          .append('\n');
    }
    JsonNode flow = document.path("flow");
    for (String groupName :
        List.of(
            "sequenceGroups",
            "conditionGroups",
            "splitGroups",
            "loopGroups",
            "retryGroups",
            "errorScopeGroups")) {
      for (JsonNode group : flow.path(groupName)) {
        if (encloses(group, ownedSteps)) {
          prompt.append("group ").append(group.path("id").asText()).append('\n');
        }
      }
    }
    for (JsonNode source : document.path("sources")) {
      if (!sources.contains(source.path("id").asText())) {
        continue;
      }
      String id = source.path("id").asText();
      prompt
          .append("source ")
          .append(id)
          .append(' ')
          .append(source.path("contentReference").asText())
          .append(' ')
          .append(source.path("contentHash").asText())
          .append(' ')
          .append(materials.sourceEvidence().getOrDefault(id, ""))
          .append('\n');
    }
    for (JsonNode transfer : transfers) {
      for (JsonNode rule : transfer.path("rules")) {
        prompt
            .append("rule ")
            .append(rule.path("id").asText())
            .append(' ')
            .append(rule.path("behavior").asText())
            .append('\n');
      }
    }
    for (SchemaFragment schema : materials.schemas()) {
      if (!ports.contains(schema.stepId() + "\n" + schema.portName())) {
        continue;
      }
      prompt
          .append("schema ")
          .append(schema.id())
          .append(' ')
          .append(schema.reference())
          .append(' ')
          .append(schema.contentHash())
          .append(' ')
          .append(schema.body())
          .append('\n');
    }
    return prompt.toString();
  }

  private static JsonNode ownedTransfers(JsonNode document, Set<String> owned) {
    var found = JSON.createArrayNode();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        if (owns(owned, transfer)) {
          found.add(transfer);
        }
      }
    }
    return found;
  }

  private static boolean owns(Set<String> owned, JsonNode transfer) {
    if (owned.contains(transfer.path("id").asText())) {
      return true;
    }
    for (JsonNode rule : transfer.path("rules")) {
      if (owned.contains(rule.path("id").asText())) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> ports(JsonNode transfers) {
    Set<String> ports = new LinkedHashSet<>();
    for (JsonNode transfer : transfers) {
      for (JsonNode source : transfer.path("sourcePorts")) {
        ports.add(source.path("stepId").asText() + "\n" + source.path("portName").asText());
      }
      JsonNode target = transfer.path("targetPort");
      ports.add(target.path("stepId").asText() + "\n" + target.path("portName").asText());
    }
    return ports;
  }

  private static Set<String> governingSources(JsonNode document, JsonNode transfers) {
    Set<String> requirementIds = new LinkedHashSet<>();
    Set<String> sources = new LinkedHashSet<>();
    for (JsonNode transfer : transfers) {
      for (JsonNode requirementId : transfer.path("requirementIds")) {
        requirementIds.add(requirementId.asText());
      }
      for (JsonNode rule : transfer.path("rules")) {
        for (JsonNode evidence : rule.path("evidenceIds")) {
          sources.add(evidence.asText());
        }
      }
    }
    for (JsonNode requirement : document.path("requirements")) {
      if (!requirementIds.contains(requirement.path("id").asText())) {
        continue;
      }
      for (JsonNode sourceId : requirement.path("sourceIds")) {
        sources.add(sourceId.asText());
      }
    }
    Map<String, JsonNode> byId = new LinkedHashMap<>();
    for (JsonNode source : document.path("sources")) {
      byId.put(source.path("id").asText(), source);
    }
    boolean grew = true;
    while (grew) {
      grew = false;
      for (String id : List.copyOf(sources)) {
        JsonNode source = byId.get(id);
        if (source == null) {
          continue;
        }
        for (JsonNode corrected : source.path("correctionOf")) {
          if (sources.add(corrected.asText())) {
            grew = true;
          }
        }
      }
    }
    return sources;
  }

  private static Set<String> ownedSteps(JsonNode document, JsonNode transfers) {
    Set<String> transferIds = new LinkedHashSet<>();
    for (JsonNode transfer : transfers) {
      transferIds.add(transfer.path("id").asText());
    }
    Set<String> steps = new LinkedHashSet<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        if (transferIds.contains(transfer.path("id").asText())) {
          steps.add(step.path("id").asText());
        }
      }
    }
    return steps;
  }

  private static boolean encloses(JsonNode group, Set<String> ownedSteps) {
    Iterator<Map.Entry<String, JsonNode>> fields = group.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if ("id".equals(field.getKey())) {
        continue;
      }
      if (mentions(field.getValue(), ownedSteps)) {
        return true;
      }
    }
    return false;
  }

  private static boolean mentions(JsonNode node, Set<String> ownedSteps) {
    if (node.isTextual()) {
      return ownedSteps.contains(node.asText());
    }
    if (node.isArray() || node.isObject()) {
      for (JsonNode child : node) {
        if (mentions(child, ownedSteps)) {
          return true;
        }
      }
    }
    return false;
  }
}
