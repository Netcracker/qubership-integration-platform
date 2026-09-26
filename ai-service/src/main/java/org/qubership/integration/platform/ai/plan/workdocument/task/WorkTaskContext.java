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
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;

/** Compact prompt for one server-owned scope. Unrelated rules and schemas stay out. */
public final class WorkTaskContext {

  private static final ObjectMapper JSON = new ObjectMapper();

  private WorkTaskContext() {}

  public static String prompt(
      WorkDocumentState state, WorkTaskScope scope, WorkTaskMaterials materials) {
    JsonNode document = JSON.valueToTree(state.document());
    boolean outline = scope.taskKind() == WorkTaskKind.DEFINE_TRANSFERS;
    Set<String> owned = Set.copyOf(scope.ownedRecordIds());
    String outlineTarget = outline ? outlineTarget(scope) : "";
    JsonNode transfers = outline ? transfersOn(document, outlineTarget) : ownedTransfers(document, owned);
    Set<String> ownedSteps = outline ? owned : ownedSteps(document, transfers);
    Set<String> ports = outline ? exposedPorts(document, ownedSteps) : ports(document, transfers);
    Set<String> sources =
        outline ? sourcesForSteps(document, ownedSteps) : governingSources(document, transfers);
    Set<String> requirementIds = outline ? requirementIds(document, ownedSteps) : Set.of();

    StringBuilder prompt = new StringBuilder();
    prompt.append("task ").append(scope.taskId()).append('\n');
    prompt.append("stage ").append(scope.stage()).append('\n');
    for (String constraint : materials.globalConstraints()) {
      prompt.append("constraint ").append(constraint).append('\n');
    }
    for (JsonNode step : document.path("flow").path("steps")) {
      prompt
          .append("id ")
          .append(step.path("id").asText())
          .append(" kind ")
          .append(step.path("kind").asText())
          .append(" label ")
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
      for (JsonNode passage : source.path("passages")) {
        prompt
            .append("passage ")
            .append(passage.path("id").asText())
            .append(' ')
            .append(passage.path("contentHash").asText())
            .append(' ')
            .append(passage.path("text").asText())
            .append('\n');
      }
    }
    for (JsonNode requirement : document.path("requirements")) {
      if (!requirementIds.contains(requirement.path("id").asText())) {
        continue;
      }
      prompt
          .append("requirement ")
          .append(requirement.path("id").asText())
          .append(' ')
          .append(requirement.path("text").asText())
          .append('\n');
    }
    for (JsonNode transfer : transfers) {
      if (outline) {
        prompt.append("transfer ").append(transfer.path("id").asText());
        prompt.append(" source");
        for (JsonNode source : transfer.path("sourcePorts")) {
          prompt
              .append(' ')
              .append(source.path("stepId").asText())
              .append('/')
              .append(source.path("portName").asText());
        }
        prompt
            .append(" target ")
            .append(transfer.path("targetPort").path("portName").asText())
            .append(" outcome ")
            .append(transfer.path("outcome").asText())
            .append(" retained");
        for (JsonNode retainedId : transfer.path("requiredRetainedIds")) {
          prompt.append(' ').append(retainedId.asText());
        }
        prompt.append(" requirements");
        for (JsonNode requirementId : transfer.path("requirementIds")) {
          prompt.append(' ').append(requirementId.asText());
        }
        prompt.append('\n');
      }
      for (JsonNode rule : transfer.path("rules")) {
        prompt
            .append("rule ")
            .append(rule.path("id").asText())
            .append(' ')
            .append(rule.path("behavior").asText())
            .append('\n');
      }
    }
    if (outline) {
      for (JsonNode step : document.path("flow").path("steps")) {
        for (JsonNode value : step.path("data").path("retainedValues")) {
          String producer = value.path("producerStepId").asText();
          if (!owned.contains(producer) && !outlineTarget.equals(step.path("id").asText())) {
            continue;
          }
          prompt
              .append("retained ")
              .append(value.path("id").asText())
              .append(" producer ")
              .append(producer)
              .append(" use ")
              .append(value.path("intendedUse").asText())
              .append('\n');
        }
      }
      for (JsonNode finding : document.path("progress").path("findings")) {
        String record = finding.path("recordRef").asText();
        if (!mentions(transfers, record) && !outlineTarget.equals(record)) {
          continue;
        }
        prompt
            .append("finding ")
            .append(finding.path("id").asText())
            .append(" category ")
            .append(finding.path("issueCategory").asText())
            .append(" record ")
            .append(record)
            .append(" pointer ")
            .append(finding.path("canonicalFieldPointer").asText())
            .append(' ')
            .append(finding.path("contradiction").asText())
            .append('\n');
      }
      for (String id : scope.replacementIds()) {
        prompt.append("allowed-update ").append(id).append('\n');
      }
      for (var allowance : scope.creationAllowances()) {
        prompt
            .append("allowed-create ")
            .append(allowance.kind())
            .append(' ')
            .append(allowance.parentId())
            .append('\n');
      }
    }
    for (JsonNode repair : document.path("progress").path("repairs")) {
      if (!scope.taskKey().equals(repair.path("consumerTaskKey").asText())) {
        continue;
      }
      if (!"VERIFY".equals(repair.path("phase").asText())) {
        continue;
      }
      prompt
          .append("finding ")
          .append(repair.path("findingId").asText())
          .append(" record ")
          .append(repair.path("recordRef").asText())
          .append(' ')
          .append(repair.path("contradiction").asText())
          .append('\n');
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

  private static String outlineTarget(WorkTaskScope scope) {
    String prefix = "define-transfers-";
    if (scope.taskId() != null && scope.taskId().startsWith(prefix)) {
      return scope.taskId().substring(prefix.length());
    }
    return scope.ownedRecordIds().isEmpty() ? "" : scope.ownedRecordIds().get(0);
  }

  private static JsonNode transfersOn(JsonNode document, String stepId) {
    var found = JSON.createArrayNode();
    for (JsonNode step : document.path("flow").path("steps")) {
      if (!stepId.equals(step.path("id").asText())) {
        continue;
      }
      for (JsonNode transfer : step.path("data").path("transfers")) {
        found.add(transfer);
      }
    }
    return found;
  }

  private static boolean mentions(JsonNode transfers, String recordId) {
    for (JsonNode transfer : transfers) {
      if (recordId.equals(transfer.path("id").asText())) {
        return true;
      }
    }
    return false;
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

  private static Set<String> ports(JsonNode document, JsonNode transfers) {
    Set<String> ports = new LinkedHashSet<>();
    for (JsonNode transfer : transfers) {
      for (JsonNode source : transfer.path("sourcePorts")) {
        ports.add(portKey(source.path("stepId").asText(), source.path("portName").asText()));
      }
      JsonNode target = transfer.path("targetPort");
      ports.add(portKey(target.path("stepId").asText(), target.path("portName").asText()));
      for (JsonNode retainedId : transfer.path("requiredRetainedIds")) {
        JsonNode retained = findRetained(document, retainedId.asText());
        if (retained == null) {
          continue;
        }
        String producer = retained.path("producerStepId").asText();
        String port = retained.path("source").path("port").asText();
        if (port.isBlank()) {
          port = "payload";
        }
        ports.add(portKey(producer, port));
      }
    }
    return ports;
  }

  private static String portKey(String stepId, String port) {
    return stepId + "\n" + schemaPort(port);
  }

  private static String schemaPort(String port) {
    return switch (port) {
      case "INBOUND_PAYLOAD" -> "payload";
      case "OUTBOUND_REQUEST" -> "request";
      case "SUCCESS_RESPONSE" -> "success";
      case "FAILURE_OUTCOME" -> "failure";
      case "RETAINED_CONTEXT" -> "context";
      default -> port;
    };
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

  private static Set<String> exposedPorts(JsonNode document, Set<String> stepIds) {
    Set<String> ports = new LinkedHashSet<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      if (!stepIds.contains(step.path("id").asText())) {
        continue;
      }
      for (JsonNode port : step.path("binding").path("exposedPorts")) {
        ports.add(portKey(step.path("id").asText(), port.asText()));
      }
    }
    return ports;
  }

  private static Set<String> requirementIds(JsonNode document, Set<String> stepIds) {
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      if (!stepIds.contains(step.path("id").asText())) {
        continue;
      }
      for (JsonNode requirementId : step.path("requirementIds")) {
        ids.add(requirementId.asText());
      }
    }
    ids.remove("");
    return ids;
  }

  private static Set<String> sourcesForSteps(JsonNode document, Set<String> stepIds) {
    Set<String> sources = new LinkedHashSet<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      if (!stepIds.contains(step.path("id").asText())) {
        continue;
      }
      for (JsonNode sourceId : step.path("sourceIds")) {
        sources.add(sourceId.asText());
      }
    }
    for (JsonNode requirement : document.path("requirements")) {
      if (!requirementIds(document, stepIds).contains(requirement.path("id").asText())) {
        continue;
      }
      for (JsonNode sourceId : requirement.path("sourceIds")) {
        sources.add(sourceId.asText());
      }
    }
    return withCorrections(document, sources);
  }

  private static Set<String> governingSources(JsonNode document, JsonNode transfers) {
    Set<String> requirementIds = new LinkedHashSet<>();
    Set<String> sources = new LinkedHashSet<>();
    Set<String> portSteps = new LinkedHashSet<>();
    for (JsonNode transfer : transfers) {
      for (JsonNode requirementId : transfer.path("requirementIds")) {
        requirementIds.add(requirementId.asText());
      }
      for (JsonNode rule : transfer.path("rules")) {
        for (JsonNode evidence : rule.path("evidenceIds")) {
          sources.add(evidence.asText());
        }
      }
      for (JsonNode sourcePort : transfer.path("sourcePorts")) {
        portSteps.add(sourcePort.path("stepId").asText());
      }
      portSteps.add(transfer.path("targetPort").path("stepId").asText());
      for (JsonNode retainedId : transfer.path("requiredRetainedIds")) {
        JsonNode retained = findRetained(document, retainedId.asText());
        if (retained == null) {
          continue;
        }
        portSteps.add(retained.path("producerStepId").asText());
        for (JsonNode evidence : retained.path("evidenceIds")) {
          sources.add(evidence.asText());
        }
      }
    }
    for (JsonNode step : document.path("flow").path("steps")) {
      if (!portSteps.contains(step.path("id").asText())) {
        continue;
      }
      for (JsonNode sourceId : step.path("sourceIds")) {
        sources.add(sourceId.asText());
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
    return withCorrections(document, sources);
  }

  private static Set<String> withCorrections(JsonNode document, Set<String> sources) {
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
    sources.remove("");
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
