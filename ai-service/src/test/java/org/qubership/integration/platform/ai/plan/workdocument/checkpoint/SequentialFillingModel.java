package org.qubership.integration.platform.ai.plan.workdocument.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;

/**
 * Replayable transport fake. Each response follows the committed document and the task kind.
 * This class does not call filling handlers.
 */
final class SequentialFillingModel implements WorkTaskModel {

  private final ObjectMapper json = new ObjectMapper();
  private final Supplier<JsonNode> document;
  private final boolean reportMissingRetained;
  private boolean reportedMissing;

  SequentialFillingModel(Supplier<JsonNode> document, boolean reportMissingRetained) {
    this.document = document;
    this.reportMissingRetained = reportMissingRetained;
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
    String retained = retainedId(document);
    String placeholder = "";
    if (retained.isBlank()) {
      placeholder =
          "{\"alias\":\"keep-process\",\"producerStepId\":\""
              + trigger.path("id").asText()
              + "\",\"intendedUse\":\"process id\",\"evidenceRefs\":[\""
              + passage
              + "\"]}";
    }
    if ("SERVICE_CALL".equals(kind)) {
      String retainedRef = retained.isBlank() ? "" : "\"" + retained + "\"";
      return """
          {"outcome":"PREPARED","transfers":[{"alias":"to-request","sourceStepId":"%s","sourcePort":"payload","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["%s"],"requiredRetainedIds":[%s],"decision":""}],"retainedPlaceholders":[%s],"coverage":[{"requirementId":"%s","passageId":"%s","disposition":"ASSIGNED"}]}
          """
          .formatted(
              trigger.path("id").asText(), requirement, retainedRef, placeholder, requirement, passage);
    }
    String retainedRef = retained.isBlank() ? "keep-process" : retained;
    return """
        {"outcome":"PREPARED","transfers":[
          {"alias":"to-success","sourceStepId":"%s","sourcePort":"success","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["%s"],"requiredRetainedIds":["%s"],"decision":""},
          {"alias":"to-failure","sourceStepId":"%s","sourcePort":"failure","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":[],"requiredRetainedIds":[],"decision":""}
        ],"retainedPlaceholders":[%s],"coverage":[{"requirementId":"%s","passageId":"%s","disposition":"ASSIGNED"}]}
        """
        .formatted(
            call.path("id").asText(),
            requirement,
            retainedRef,
            call.path("id").asText(),
            placeholder,
            requirement,
            passage);
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
        item.put("fieldPath", "$.processInstanceId");
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
        && !reportedMissing
        && "success".equals(sourcePort)
        && transfer.path("requiredRetainedIds").isEmpty()
        && retainedId(document).isBlank()) {
      reportedMissing = true;
      return """
          {"outcome":"INPUT_DEFECT","rules":[],"decision":"","evidenceRefs":[],"question":{"text":"","choiceKind":"UNSPECIFIED","sourceStepId":"","sourcePort":"","sourceField":"","sourceRetainedId":"","targetStepId":"","targetPort":"","targetField":"","targetRetainedId":"","evidenceRefs":[]},"defect":{"recordRef":"%s","category":"MISSING_RETAINED","contradiction":"The outline has no retained declaration for the process id.","evidenceRefs":["%s"]}}
          """
          .formatted(recordId, source);
    }
    if ("failure".equals(sourcePort)) {
      String call = transfer.path("sourcePorts").path(0).path("stepId").asText();
      return """
          {"outcome":"PREPARED","rules":[{"alias":"rule-failure","targetPath":"$.error.code","sources":[{"sourceRef":"%s/failure","fieldPath":"$.status"}],"constants":[{"name":"code","value":"SALESFORCE_TASK_CREATE_ERROR"}],"behavior":"failure code","evidenceRefs":["%s"]}],"decision":"","evidenceRefs":[]}
          """
          .formatted(call, source);
    }
    if ("success".equals(sourcePort)) {
      String retained = transfer.path("requiredRetainedIds").path(0).asText();
      JsonNode trigger = byKind(document, "TRIGGER");
      JsonNode reply = byKind(document, "REPLY");
      if (!answered(document)) {
        return """
            {"outcome":"NEEDS_CLARIFICATION","rules":[],"decision":"","evidenceRefs":[],"question":{"text":"Field processId does not match source processInstanceId. Record the relationship or choose another field.","choiceKind":"FIELD_RELATIONSHIP","sourceStepId":"%s","sourcePort":"payload","sourceField":"$.processInstanceId","sourceRetainedId":"%s","targetStepId":"%s","targetPort":"request","targetField":"$.processId","targetRetainedId":"","evidenceRefs":["%s"]}}
            """
            .formatted(trigger.path("id").asText(), retained, reply.path("id").asText(), source);
      }
      return """
          {"outcome":"PREPARED","rules":[{"alias":"rule-process","targetPath":"$.processId","sources":[{"sourceRef":"retained/%s","fieldPath":"$.processInstanceId"}],"constants":[],"behavior":"processId reads retained processInstanceId","evidenceRefs":["%s"],"relationship":{"sourceField":"processInstanceId","targetField":"processId","evidenceRefs":["%s"]}}],"decision":"","evidenceRefs":[]}
          """
          .formatted(retained, source, source);
    }
    String trigger = transfer.path("sourcePorts").path(0).path("stepId").asText();
    return """
        {"outcome":"PREPARED","rules":[
          {"alias":"rule-subject","targetPath":"$.Subject","sources":[{"sourceRef":"%s/payload","fieldPath":"$.name"},{"sourceRef":"%s/payload","fieldPath":"$.subRequestType"},{"sourceRef":"%s/payload","fieldPath":"$.orderId"}],"constants":[],"behavior":"name, or a formatted fallback","evidenceRefs":["%s"]},
          {"alias":"rule-priority","targetPath":"$.Priority","sources":[{"sourceRef":"%s/payload","fieldPath":"$.priority"}],"constants":[],"behavior":"high, urgent, or critical to High; low to Low; otherwise Normal","evidenceRefs":["%s"]},
          {"alias":"rule-status","targetPath":"$.Status","sources":[],"constants":[{"name":"status","value":"Not Started"}],"behavior":"constant Not Started","evidenceRefs":["%s"]},
          {"alias":"rule-activity","targetPath":"$.ActivityDate","sources":[{"sourceRef":"%s/payload","fieldPath":"$.parameters.orderCreationDate"}],"constants":[],"behavior":"order creation date, else today","evidenceRefs":["%s"]},
          {"alias":"rule-description","targetPath":"$.Description","sources":[{"sourceRef":"%s/payload","fieldPath":"$.taskId"}],"constants":[],"behavior":"JSON with taskId, executionId, executionNumber, orderType, subRequestType, woOrderType, parameters","evidenceRefs":["%s"]}
        ],"decision":"","evidenceRefs":[]}
        """
        .formatted(
            trigger, trigger, trigger, source, trigger, source, source, trigger, source, trigger, source);
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

  private static String retainedId(JsonNode document) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        String id = value.path("id").asText();
        if (!id.isBlank()) {
          return id;
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
