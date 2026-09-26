package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;

/**
 * Replayable transport fake. Each response follows the committed document and the task kind.
 * This class does not call filling handlers.
 */
final class SequentialFillingModel implements WorkTaskModel {

  private static final List<String> RETAINED_FIELDS =
      List.of("executionId", "orderId", "processInstanceId", "executionNumber", "taskId");

  private final ObjectMapper json = new ObjectMapper();
  private final Supplier<JsonNode> document;
  private final boolean reportMissingRetained;
  private boolean reportedMissing;

  SequentialFillingModel(Supplier<JsonNode> document, boolean reportMissingRetained) {
    this(document, reportMissingRetained, false);
  }

  SequentialFillingModel(
      Supplier<JsonNode> document, boolean reportMissingRetained, boolean alreadyReported) {
    this.document = document;
    this.reportMissingRetained = reportMissingRetained;
    this.reportedMissing = alreadyReported;
  }

  @Override
  public String complete(WorkTaskRequest request) {
    JsonNode current = document.get();
    String recordId = recordId(request);
    return switch (request.kind()) {
      case LOGICAL_DESIGN -> logical(current);
      case SELECT_OPERATION -> selection(current, recordId);
      case DEFINE_TRANSFERS -> outline(current, recordId);
      case DESCRIBE_CONTEXT -> context(current, recordId);
      case MAP_TRANSFER, REPAIR_RULE -> mapping(current, recordId);
      default -> throw new IllegalStateException("Unexpected task " + request.kind());
    };
  }

  private static String recordId(WorkTaskRequest request) {
    String id = request.taskId();
    for (String prefix :
        java.util.List.of(
            "logical-design-",
            "select-operation-",
            "define-transfers-",
            "describe-context-",
            "map-transfer-",
            "repair-rule-")) {
      if (id.startsWith(prefix)) {
        return id.substring(prefix.length());
      }
    }
    return id;
  }

  private String logical(JsonNode document) {
    String source = document.path("sources").path(0).path("id").asText();
    JsonNode steps = document.path("flow").path("steps");
    JsonNode requirements = document.path("requirements");
    if (steps.size() > 0 && requirements.size() > 0) {
      JsonNode requirement = requirements.get(0);
      ObjectNode body = json.createObjectNode();
      body.put("outcome", "PREPARED");
      ObjectNode item = body.putArray("requirements").addObject();
      item.put("existingId", requirement.path("id").asText());
      item.put("alias", "");
      item.put("text", requirement.path("text").asText());
      item.putArray("sourceRefs").add(source);
      item.put("supersededRef", "");
      body.putArray("steps");
      body.putArray("connections");
      body.putArray("sequenceGroups");
      body.putArray("conditionGroups");
      body.putArray("splitGroups");
      body.putArray("loopGroups");
      body.putArray("retryGroups");
      body.putArray("errorScopeGroups");
      body.putArray("deletes");
      body.put("question", "");
      body.put("unresolvedChoice", "");
      body.putArray("clarificationEvidenceIds");
      body.put("defectRecordRef", "");
      body.put("contradiction", "");
      body.putArray("defectEvidenceIds");
      body.put("issueCategory", "");
      return body.toString();
    }
    return """
        {"outcome":"PREPARED",
        "requirements":[{"existingId":"","alias":"req","text":"onTaskStart createTask onTaskResult. commandType is the constant completeTask","sourceRefs":["%s"],"supersededRef":""}],
        "steps":[
          {"existingId":"","alias":"start","kind":"TRIGGER","label":"onTaskStart","intent":"Receive the task start","sourceRefs":["%s"],"requirementRefs":["req"]},
          {"existingId":"","alias":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceRefs":["%s"],"requirementRefs":["req"]},
          {"existingId":"","alias":"result","kind":"REPLY","label":"onTaskResult","intent":"Return the task result","sourceRefs":["%s"],"requirementRefs":["req"]}
        ],
        "connections":[
          {"existingId":"","alias":"go","sourceStepRef":"start","outcome":"success","targetStepRef":"create","routingIntent":"Then create the task","evidenceRefs":["%s"]},
          {"existingId":"","alias":"ok","sourceStepRef":"create","outcome":"success","targetStepRef":"result","routingIntent":"Return the synchronous success","evidenceRefs":["%s"]},
          {"existingId":"","alias":"bad","sourceStepRef":"create","outcome":"failure","targetStepRef":"result","routingIntent":"Return the synchronous failure","evidenceRefs":["%s"]}
        ],
        "sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"deletes":[],
        "question":"","unresolvedChoice":"","clarificationEvidenceIds":[],"defectRecordRef":"","contradiction":"","defectEvidenceIds":[],"issueCategory":""}
        """
        .formatted(source, source, source, source, source, source, source);
  }

  private static String selection(JsonNode document, String stepId) {
    JsonNode step = step(document, stepId);
    String label = step == null ? stepId : step.path("label").asText(stepId);
    return "{\"outcome\":\"PREPARED\",\"candidateId\":\"" + label + "\"}";
  }

  private String outline(JsonNode document, String stepId) {
    JsonNode step = step(document, stepId);
    String kind = step == null ? "" : step.path("kind").asText();
    String requirement = step == null || step.path("requirementIds").isEmpty()
        ? ""
        : step.path("requirementIds").get(0).asText();
    String passage = document.path("sources").path(0).path("passages").path(0).path("id").asText();
    if ("TRIGGER".equals(kind)) {
      return """
          {"outcome":"PREPARED","transfers":[],"retainedPlaceholders":[],"coverage":[{"requirementId":"%s","passageId":"%s","disposition":"NO_MAPPING"}]}
          """
          .formatted(requirement, passage);
    }
    JsonNode trigger = byKind(document, "TRIGGER");
    JsonNode call = byKind(document, "SERVICE_CALL");
    String placeholders = retainedIds(document).isEmpty() ? placeholders(trigger.path("id").asText(), passage) : "";
    if ("SERVICE_CALL".equals(kind)) {
      String requestId = transferId(step, "payload");
      return """
          {"outcome":"PREPARED","transfers":[{"alias":"%s","existingId":"%s","sourceStepId":"%s","sourcePort":"payload","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["%s"],"requiredRetainedIds":[],"decision":""}],"retainedPlaceholders":[%s],"coverage":[{"requirementId":"%s","passageId":"%s","disposition":"ASSIGNED"}]}
          """
          .formatted(
              requestId.isBlank() ? "to-request" : "",
              requestId,
              trigger.path("id").asText(),
              requirement,
              placeholders,
              requirement,
              passage);
    }
    String successId = transferId(step, "success");
    String failureId = transferId(step, "failure");
    return """
        {"outcome":"PREPARED","transfers":[
          {"alias":"%s","existingId":"%s","sourceStepId":"%s","sourcePort":"success","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["%s"],"requiredRetainedIds":[%s],"decision":""},
          {"alias":"%s","existingId":"%s","sourceStepId":"%s","sourcePort":"failure","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":[],"requiredRetainedIds":[],"decision":""}
        ],"retainedPlaceholders":[%s],"coverage":[{"requirementId":"%s","passageId":"%s","disposition":"ASSIGNED"}]}
        """
        .formatted(
            successId.isBlank() ? "to-success" : "",
            successId,
            call.path("id").asText(),
            requirement,
            retainedRefs(document),
            failureId.isBlank() ? "to-failure" : "",
            failureId,
            call.path("id").asText(),
            placeholders,
            requirement,
            passage);
  }

  private static String transferId(JsonNode step, String sourcePort) {
    if (step == null) {
      return "";
    }
    for (JsonNode transfer : step.path("data").path("transfers")) {
      if (sourcePort.equals(transfer.path("sourcePorts").path(0).path("portName").asText())) {
        return transfer.path("id").asText();
      }
    }
    return "";
  }

  private static String placeholders(String producerId, String passage) {
    StringBuilder body = new StringBuilder();
    for (String field : RETAINED_FIELDS) {
      if (body.length() > 0) {
        body.append(',');
      }
      body.append("{\"alias\":\"keep-")
          .append(field)
          .append("\",\"producerStepId\":\"")
          .append(producerId)
          .append("\",\"intendedUse\":\"")
          .append(field)
          .append("\",\"evidenceRefs\":[\"")
          .append(passage)
          .append("\"]}");
    }
    return body.toString();
  }

  private static String retainedRefs(JsonNode document) {
    List<String> ids = retainedIds(document);
    StringBuilder body = new StringBuilder();
    if (ids.isEmpty()) {
      for (String field : RETAINED_FIELDS) {
        if (body.length() > 0) {
          body.append(',');
        }
        body.append("\"keep-").append(field).append('"');
      }
      return body.toString();
    }
    for (String id : ids) {
      if (body.length() > 0) {
        body.append(',');
      }
      body.append('"').append(id).append('"');
    }
    return body.toString();
  }

  private String context(JsonNode document, String producerId) {
    String source = document.path("sources").path(0).path("id").asText();
    JsonNode step = step(document, producerId);
    ArrayNode values = json.createArrayNode();
    if (step != null) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        if ("RESOLVED".equals(value.path("resolution").asText())) {
          continue;
        }
        ObjectNode item = values.addObject();
        item.put("retainedId", value.path("id").asText());
        item.put("fieldPath", fieldPath(value.path("intendedUse").asText()));
        item.putArray("evidenceRefs").add(source);
      }
    }
    ObjectNode body = json.createObjectNode();
    body.put("outcome", "PREPARED");
    body.set("values", values);
    return body.toString();
  }

  private String mapping(JsonNode document, String recordId) {
    JsonNode transfer = transfer(document, recordId);
    String source = document.path("sources").path(0).path("id").asText();
    String sourcePort = transfer.path("sourcePorts").path(0).path("portName").asText();
    if (reportMissingRetained
        && !alreadyReported(document)
        && "success".equals(sourcePort)
        && transfer.path("requiredRetainedIds").isEmpty()
        && retainedIds(document).isEmpty()) {
      reportedMissing = true;
      return """
          {"outcome":"INPUT_DEFECT","rules":[],"decision":"","evidenceRefs":[],"question":{"text":"","choiceKind":"UNSPECIFIED","sourceStepId":"","sourcePort":"","sourceField":"","sourceRetainedId":"","targetStepId":"","targetPort":"","targetField":"","targetRetainedId":"","evidenceRefs":[]},"defect":{"recordRef":"%s","category":"MISSING_RETAINED","contradiction":"The outline has no retained declaration for the process id.","evidenceRefs":["%s"]}}
          """
          .formatted(recordId, source);
    }
    if ("failure".equals(sourcePort)) {
      String call = transfer.path("sourcePorts").path(0).path("stepId").asText();
      return """
          {"outcome":"PREPARED","rules":[
            {"alias":"rule-failure","targetPath":"$.error.code","sources":[{"sourceRef":"%s/failure","fieldPath":"$.status"}],"constants":[{"name":"code","value":"SALESFORCE_TASK_CREATE_ERROR"}],"behavior":"failure code","evidenceRefs":["%s"]},
            {"alias":"rule-error-text","targetPath":"$.error.message","sources":[{"sourceRef":"%s/failure","fieldPath":"$.status"}],"constants":[],"behavior":"Salesforce error text","evidenceRefs":["%s"]}
          ],"decision":"","evidenceRefs":[]}
          """
          .formatted(call, source, call, source);
    }
    if ("success".equals(sourcePort)) {
      String retained = retainedIdFor(document, "$.processInstanceId");
      if (retained.isBlank()) {
        retained = transfer.path("requiredRetainedIds").path(0).asText();
      }
      JsonNode trigger = byKind(document, "TRIGGER");
      JsonNode reply = byKind(document, "REPLY");
      if (!answered(document)) {
        return """
            {"outcome":"NEEDS_CLARIFICATION","rules":[],"decision":"","evidenceRefs":[],"question":{"text":"Field processId does not match source processInstanceId. Record the relationship or choose another field.","choiceKind":"FIELD_RELATIONSHIP","sourceStepId":"%s","sourcePort":"payload","sourceField":"$.processInstanceId","sourceRetainedId":"%s","targetStepId":"%s","targetPort":"request","targetField":"$.processId","targetRetainedId":"","evidenceRefs":["%s"]}}
            """
            .formatted(trigger.path("id").asText(), retained, reply.path("id").asText(), source);
      }
      String call = transfer.path("sourcePorts").path(0).path("stepId").asText();
      return successRules(document, call, source);
    }
    String trigger = transfer.path("sourcePorts").path(0).path("stepId").asText();
    return requestRules(trigger, source);
  }

  private String requestRules(String trigger, String source) {
    ObjectNode body = json.createObjectNode();
    body.put("outcome", "PREPARED");
    ArrayNode rules = body.putArray("rules");
    rule(
        rules,
        "rule-subject",
        "$.Subject",
        List.of("$.name", "$.subRequestType", "$.orderId"),
        trigger,
        "payload",
        "",
        "",
        source,
        "name, or {subRequestType} task for order {orderId}");
    rule(
        rules,
        "rule-priority",
        "$.Priority",
        List.of("$.priority"),
        trigger,
        "payload",
        "",
        "",
        source,
        "high, urgent, or critical to High; low to Low; otherwise Normal");
    rule(rules, "rule-status", "$.Status", List.of(), trigger, "payload", "status", "Not Started", source, "constant Not Started");
    rule(
        rules,
        "rule-activity",
        "$.ActivityDate",
        List.of("$.parameters.orderCreationDate"),
        trigger,
        "payload",
        "",
        "",
        source,
        "specified order creation date, else today");
    rule(
        rules,
        "rule-description",
        "$.Description",
        List.of(
            "$.taskId",
            "$.executionId",
            "$.executionNumber",
            "$.orderType",
            "$.subRequestType",
            "$.woOrderType",
            "$.parameters"),
        trigger,
        "payload",
        "",
        "",
        source,
        "JSON object of the seven named inputs");
    body.put("decision", "");
    body.putArray("evidenceRefs");
    return body.toString();
  }

  private String successRules(JsonNode document, String call, String source) {
    ObjectNode body = json.createObjectNode();
    body.put("outcome", "PREPARED");
    ArrayNode rules = body.putArray("rules");
    rule(rules, "rule-command", "$.commandType", List.of(), call, "success", "commandType", "completeTask", source, "constant completeTask");
    rule(rules, "rule-source-app", "$.sourceAppName", List.of(), call, "success", "sourceAppName", "salesforce", source, "constant salesforce");
    for (String field : List.of("executionId", "orderId", "executionNumber", "taskId")) {
      String retained = retainedIdFor(document, "$." + field);
      ObjectNode item = rules.addObject();
      item.put("alias", "rule-echo-" + field);
      item.put("targetPath", "$." + field);
      ObjectNode from = item.putArray("sources").addObject();
      from.put("sourceRef", "retained/" + retained);
      from.put("fieldPath", "$." + field);
      item.putArray("constants");
      item.put("behavior", "echo " + field);
      item.putArray("evidenceRefs").add(source);
    }
    String process = retainedIdFor(document, "$.processInstanceId");
    ObjectNode relationship = rules.addObject();
    relationship.put("alias", "rule-process");
    relationship.put("targetPath", "$.processId");
    ObjectNode from = relationship.putArray("sources").addObject();
    from.put("sourceRef", "retained/" + process);
    from.put("fieldPath", "$.processInstanceId");
    relationship.putArray("constants");
    relationship.put("behavior", "processId reads retained processInstanceId");
    relationship.putArray("evidenceRefs").add(source);
    ObjectNode link = relationship.putObject("relationship");
    link.put("sourceField", "processInstanceId");
    link.put("targetField", "processId");
    link.putArray("evidenceRefs").add(source);
    rule(
        rules,
        "rule-salesforce-id",
        "$.parameters.salesforceTaskId",
        List.of("$.id"),
        call,
        "success",
        "",
        "",
        source,
        "Salesforce id");
    body.put("decision", "");
    body.putArray("evidenceRefs");
    return body.toString();
  }

  private static void rule(
      ArrayNode rules,
      String alias,
      String target,
      List<String> paths,
      String stepId,
      String port,
      String constantName,
      String constantValue,
      String evidence,
      String behavior) {
    ObjectNode item = rules.addObject();
    item.put("alias", alias);
    item.put("targetPath", target);
    ArrayNode sources = item.putArray("sources");
    for (String path : paths) {
      ObjectNode source = sources.addObject();
      source.put("sourceRef", stepId + "/" + port);
      source.put("fieldPath", path);
    }
    ArrayNode constants = item.putArray("constants");
    if (!constantName.isBlank()) {
      ObjectNode constant = constants.addObject();
      constant.put("name", constantName);
      constant.put("value", constantValue);
    }
    item.put("behavior", behavior);
    item.putArray("evidenceRefs").add(evidence);
  }

  private boolean alreadyReported(JsonNode document) {
    if (reportedMissing) {
      return true;
    }
    for (JsonNode finding : document.path("progress").path("findings")) {
      if ("MISSING_RETAINED".equals(finding.path("issueCategory").asText())) {
        return true;
      }
    }
    return false;
  }

  private static String fieldPath(String intendedUse) {
    for (String field : RETAINED_FIELDS) {
      if (field.equals(intendedUse)) {
        return "$." + field;
      }
    }
    return "$.processInstanceId";
  }

  private static boolean answered(JsonNode document) {
    for (JsonNode question : document.path("progress").path("questions")) {
      if ("ANSWERED".equals(question.path("resolution").asText())
          && question.path("answerSourceIds").size() > 0) {
        return true;
      }
    }
    for (JsonNode source : document.path("sources")) {
      if ("answer".equals(source.path("role").asText())) {
        return true;
      }
    }
    return false;
  }

  private static List<String> retainedIds(JsonNode document) {
    List<String> ids = new java.util.ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        String id = value.path("id").asText();
        if (!id.isBlank()) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  private static String retainedIdFor(JsonNode document, String fieldPath) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        if (fieldPath.equals(value.path("source").path("fieldPath").asText())
            || fieldPath.equals(fieldPath(value.path("intendedUse").asText()))) {
          String id = value.path("id").asText();
          if (!id.isBlank() && fieldPath.equals(value.path("source").path("fieldPath").asText())) {
            return id;
          }
        }
      }
    }
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        if (fieldPath.equals(fieldPath(value.path("intendedUse").asText()))) {
          String id = value.path("id").asText();
          if (!id.isBlank()) {
            return id;
          }
        }
      }
    }
    return "";
  }

  private static JsonNode transfer(JsonNode document, String recordId) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        if (recordId.equals(transfer.path("id").asText())) {
          return transfer;
        }
        for (JsonNode rule : transfer.path("rules")) {
          if (recordId.equals(rule.path("id").asText())) {
            return transfer;
          }
        }
      }
    }
    throw new IllegalStateException("Transfer " + recordId + " is not on the document.");
  }

  private static JsonNode step(JsonNode document, String id) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (id.equals(step.path("id").asText())) {
        return step;
      }
    }
    return null;
  }

  private static JsonNode byKind(JsonNode document, String kind) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (kind.equals(step.path("kind").asText())) {
        return step;
      }
    }
    throw new IllegalStateException("Missing step " + kind);
  }
}
