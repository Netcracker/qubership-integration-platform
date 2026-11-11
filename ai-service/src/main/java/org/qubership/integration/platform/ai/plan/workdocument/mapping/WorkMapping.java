package org.qubership.integration.platform.ai.plan.workdocument.mapping;

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
import java.util.Set;
import org.qubership.integration.platform.ai.plan.workdocument.CaptureChoices;
import org.qubership.integration.platform.ai.plan.workdocument.CreationAllowance;
import org.qubership.integration.platform.ai.plan.workdocument.FixedTransferEndpoint;
import org.qubership.integration.platform.ai.plan.workdocument.QuestionChoiceKind;
import org.qubership.integration.platform.ai.plan.workdocument.QuestionFieldRef;
import org.qubership.integration.platform.ai.plan.workdocument.QuestionSubject;
import org.qubership.integration.platform.ai.plan.workdocument.TransferOutcome;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentCaptureSchema;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRecordKind;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskCapture;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
import org.qubership.integration.platform.ai.plan.workdocument.task.SchemaFragment;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskContext;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;

/**
 * One persisted transfer, or one named rule. The model returns rules for that assignment. Java
 * checks the source refs, paths, and constants, then publishes canonical field references.
 */
public final class WorkMapping {

  public static final String SKILL_ID = "data-mapping";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String INSTRUCTIONS = loadInstructions();

  private final WorkDocumentService documents;
  private final WorkTaskExecutor executor;

  public WorkMapping(WorkDocumentService documents, WorkTaskExecutor executor) {
    this.documents = documents;
    if (executor == null) {
      throw new IllegalArgumentException("A task executor is required.");
    }
    this.executor = executor;
  }

  public WorkCommit interpret(
      String runId, String transferId, WorkTaskMaterials materials, WorkTaskModel model) {
    if (transferId == null || transferId.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Initial mapping requires a persisted transfer id. Create the transfer before mapping it.");
    }
    return decide(runId, transferId, null, materials, model);
  }

  public WorkCommit repair(
      String runId, String ruleId, WorkTaskMaterials materials, WorkTaskModel model) {
    if (ruleId == null || ruleId.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE", "Rule repair requires the rule id. Name the assigned rule.");
    }
    WorkDocumentState state = documents.read(runId);
    JsonNode transfer = transferOfRule(JSON.valueToTree(state.document()), ruleId);
    if (transfer == null) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Rule " + ruleId + " is not on a transfer. Repair a rule that already exists.");
    }
    return decide(runId, transfer.path("id").asText(), ruleId, materials, model);
  }

  private WorkCommit decide(
      String runId,
      String transferId,
      String repairRuleId,
      WorkTaskMaterials materials,
      WorkTaskModel model) {
    WorkDocumentState state = documents.read(runId);
    JsonNode document = JSON.valueToTree(state.document());
    JsonNode transfer = findTransfer(document, transferId);
    if (transfer == null) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Transfer " + transferId + " does not exist. Map a persisted transfer.");
    }
    boolean repair = repairRuleId != null;
    WorkTaskKind kind = repair ? WorkTaskKind.REPAIR_RULE : WorkTaskKind.MAP_TRANSFER;
    String recordId = repair ? repairRuleId : transferId;
    String taskId = WorkTaskPlanner.taskId(kind, recordId);
    String taskKey = WorkTaskPlanner.taskKey(kind, recordId);
    List<String> sourceRefs = sourceRefs(document, transfer);
    List<String> evidenceRefs = evidenceRefs(document, transfer);
    List<String> ruleIds = repair ? List.of(repairRuleId) : List.of();
    CaptureChoices choices = new CaptureChoices(sourceRefs, evidenceRefs, ruleIds, List.of());
    JsonObjectSchema schema = WorkDocumentCaptureSchema.responseSchema(kind, choices);
    WorkTaskScope scope = ruleScope(state, transfer, taskId, taskKey, kind, repair, repairRuleId);
    Optional<WorkCommit> prior = executor.publishedResult(runId, scope);
    if (prior.isPresent()) {
      return prior.get();
    }
    executor.reserve(runId, scope);
    WorkTaskMaterials instructed =
        instructed(materials, transfer, sourceRefs, evidenceRefs, repairRuleId);
    String output = complete(model, new WorkTaskRequest(
        taskId, taskKey, kind, WorkTaskContext.prompt(state, scope, instructed), schema));
    JsonNode tree = WorkDocumentCaptureSchema.readObject(output, schema);
    String outcome = tree.path("outcome").asText();
    if ("NEEDS_CLARIFICATION".equals(outcome)) {
      return ask(runId, state, scope, tree, evidenceRefs, document, transfer);
    }
    if ("INPUT_DEFECT".equals(outcome)) {
      return defect(runId, state, scope, tree, evidenceRefs);
    }
    if (!"PREPARED".equals(outcome)) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE",
          "Outcome " + outcome + " is unknown. Use PREPARED, NEEDS_CLARIFICATION, or INPUT_DEFECT.");
    }
    rejectMixed(tree);
    if (repair && "NO_MAPPING".equals(tree.path("decision").asText())) {
      throw new WorkDocumentRejectedException(
          "OUTSIDE_SCOPE",
          "A rule repair cannot replace the transfer. Change only rule " + repairRuleId + ".");
    }
    if (tree.path("rules").isEmpty()) {
      if ("NO_MAPPING".equals(tree.path("decision").asText())) {
        requireEvidence(tree.path("evidenceRefs"), evidenceRefs);
        return documents.apply(
            runId,
            noMappingScope(state, transfer, taskId, taskKey),
            noMappingCapture(transfer, texts(tree.path("evidenceRefs"))),
            command(taskId, state));
      }
      throw new WorkDocumentRejectedException(
          "UNEVIDENCED_MAPPING",
          "Empty rules do not show that mapping is unnecessary. Supply the rules or send NO_MAPPING with evidence.");
    }
    if (!tree.path("decision").asText().isBlank()) {
      throw new WorkDocumentRejectedException(
          "CONTRADICTORY_OUTCOME",
          "A prepared rule list cannot also set a mapping decision. Send the rules or NO_MAPPING.");
    }
    ArrayNode rules = rules(document, materials, transfer, tree.path("rules"), choices, repairRuleId);
    return documents.apply(
        runId, scope, prepared(rules), command(taskId, state));
  }

  private static String complete(WorkTaskModel model, WorkTaskRequest request) {
    try {
      return model.complete(request);
    } catch (OutputParsingException failure) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "Mapping capture could not be parsed. The task was not completed.");
    }
  }

  private WorkCommit ask(
      String runId,
      WorkDocumentState state,
      WorkTaskScope scope,
      JsonNode tree,
      List<String> evidenceRefs,
      JsonNode document,
      JsonNode transfer) {
    if (!tree.path("rules").isEmpty() || !tree.path("decision").asText().isBlank()) {
      throw new WorkDocumentRejectedException(
          "CONTRADICTORY_OUTCOME",
          "A clarification needs one question and no rules. Remove the rules.");
    }
    JsonNode question = object(tree, "question");
    String text = question.path("text").asText();
    if (text.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "A clarification needs question text. Name the unresolved choice.");
    }
    QuestionChoiceKind choice = choice(question.path("choiceKind").asText());
    QuestionFieldRef source = field(question, "source");
    QuestionFieldRef target = field(question, "target");
    QuestionSubject subject;
    try {
      subject =
          choice == QuestionChoiceKind.FIELD_RELATIONSHIP
              ? QuestionSubject.fieldRelationship(source, target)
              : new QuestionSubject(choice, source, target);
    } catch (IllegalArgumentException failure) {
      throw new WorkDocumentRejectedException("MALFORMED_REFERENCE", failure.getMessage());
    }
    try {
      requireQuestionMembership(document, transfer, source, target);
    } catch (IllegalArgumentException failure) {
      throw new WorkDocumentRejectedException("MALFORMED_REFERENCE", failure.getMessage());
    }
    List<String> evidence = texts(question.path("evidenceRefs"));
    requireEvidence(question.path("evidenceRefs"), evidenceRefs);
    List<String> blocked =
        scope.fixedEndpoint() == null ? List.of() : List.of(scope.fixedEndpoint().transferId());
    return documents.recordQuestion(
        runId, scope, text, subject, blocked, evidence, command(scope.taskId(), state) + ":question");
  }

  private WorkCommit defect(
      String runId,
      WorkDocumentState state,
      WorkTaskScope scope,
      JsonNode tree,
      List<String> evidenceRefs) {
    if (!tree.path("rules").isEmpty() || !question(tree).path("text").asText().isBlank()) {
      throw new WorkDocumentRejectedException(
          "CONTRADICTORY_OUTCOME",
          "A defect capture cannot include rules or a question. Send the defect alone.");
    }
    JsonNode defect = object(tree, "defect");
    String record = defect.path("recordRef").asText();
    if (record.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "A defect needs the existing record. Name that record.");
    }
    requireEvidence(defect.path("evidenceRefs"), evidenceRefs);
    ObjectNode body = JSON.createObjectNode();
    body.put("outcome", "INPUT_DEFECT");
    body.put("defectRecordRef", record);
    body.put("contradiction", defect.path("contradiction").asText());
    body.put("issueCategory", defect.path("category").asText());
    body.set("defectEvidenceIds", textsNode(defect.path("evidenceRefs")));
    return documents.apply(
        runId,
        scope,
        WorkDocumentCaptureSchema.parse(WorkDocumentCaptureSchema.withUniversalLists(body)),
        command(scope.taskId(), state) + ":defect");
  }

  private static void rejectMixed(JsonNode tree) {
    if (!question(tree).path("text").asText().isBlank() || !object(tree, "defect").path("recordRef").asText().isBlank()) {
      throw new WorkDocumentRejectedException(
          "CONTRADICTORY_OUTCOME",
          "A prepared capture cannot also report a question or a defect. Send one outcome.");
    }
  }

  private static ArrayNode rules(
      JsonNode document,
      WorkTaskMaterials materials,
      JsonNode transfer,
      JsonNode proposed,
      CaptureChoices choices,
      String repairRuleId) {
    ArrayNode rules = JSON.createArrayNode();
    String targetStep = transfer.path("targetPort").path("stepId").asText();
    String targetPort = schemaPort(transfer.path("targetPort").path("portName").asText());
    for (JsonNode rule : proposed) {
      String alias = rule.path("alias").asText();
      String existingId = rule.path("existingId").asText();
      if (repairRuleId == null) {
        if (alias.isBlank()) {
          throw new WorkDocumentRejectedException(
              "MALFORMED_REFERENCE", "A new rule needs an alias. Name the rule for this transfer.");
        }
      } else if (!repairRuleId.equals(existingId)) {
        throw new WorkDocumentRejectedException(
            "OUTSIDE_SCOPE",
            "This repair can change only rule " + repairRuleId + ". Remove the other rules.");
      }
      String targetPath = canonical(document, materials, targetStep, targetPort, rule.path("targetPath").asText());
      ArrayNode sources = JSON.createArrayNode();
      for (JsonNode source : rule.path("sources")) {
        sources.add(source(document, materials, transfer, choices, source, targetPath, rule.path("relationship")));
      }
      checkConstants(materials, targetStep, targetPort, targetPath, rule.path("constants"));
      List<String> evidence = texts(rule.path("evidenceRefs"));
      if (evidence.isEmpty()) {
        throw new WorkDocumentRejectedException(
            "UNEVIDENCED_MAPPING",
            "Rule " + (alias.isBlank() ? existingId : alias) + " needs evidence. Cite a listed evidence ref.");
      }
      requireMembers(evidence, choices.evidenceRefs(), "Evidence ref ");
      ObjectNode stored = rules.addObject();
      stored.put("existingId", repairRuleId == null ? "" : existingId);
      stored.put("alias", repairRuleId == null ? alias : "");
      stored.put("transferRef", transfer.path("id").asText());
      stored.set("sources", sources);
      ObjectNode target = stored.putObject("target");
      target.put("kind", "STEP_PORT");
      target.put("stepId", targetStep);
      target.put("port", targetPort);
      target.put("fieldPath", targetPath);
      target.put("retainedValueId", "");
      stored.set(
          "constants",
          rule.path("constants").isArray() ? rule.path("constants").deepCopy() : JSON.createArrayNode());
      stored.put("behavior", rule.path("behavior").asText());
      stored.set("evidenceRefs", textsNode(rule.path("evidenceRefs")));
    }
    return rules;
  }

  private static ObjectNode source(
      JsonNode document,
      WorkTaskMaterials materials,
      JsonNode transfer,
      CaptureChoices choices,
      JsonNode source,
      String targetPath,
      JsonNode relationship) {
    String ref = source.path("sourceRef").asText();
    if (!choices.sourceRefs().contains(ref)) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Source ref " + ref + " is not an allowed source. Use a listed source ref.");
    }
    ObjectNode stored = JSON.createObjectNode();
    if (ref.startsWith("retained/")) {
      String retainedId = ref.substring("retained/".length());
      JsonNode retained = findRetained(document, retainedId);
      String sourcePath = source.path("fieldPath").asText();
      if (retained == null || !samePath(retained.path("source").path("fieldPath").asText(), sourcePath)) {
        throw new WorkDocumentRejectedException(
            "MALFORMED_REFERENCE",
            "Retained source " + retainedId + " does not have field " + sourcePath + ". Use the stored field path.");
      }
      requireRelationship(relationship, choices, leaf(sourcePath), leaf(targetPath));
      stored.put("kind", "RETAINED");
      stored.put("stepId", "");
      stored.putNull("port");
      stored.put("fieldPath", "");
      stored.put("retainedValueId", retainedId);
      return stored;
    }
    int slash = ref.indexOf('/');
    String stepId = ref.substring(0, slash);
    String port = ref.substring(slash + 1);
    if (!ownsPort(transfer, stepId, port) && !retainedProducerPort(document, transfer, stepId, port)) {
      throw new WorkDocumentRejectedException(
          "OUTSIDE_SCOPE",
          "Source " + ref + " is outside this transfer. Use an assigned source port.");
    }
    String path = canonical(document, materials, stepId, port, source.path("fieldPath").asText());
    stored.put("kind", "STEP_PORT");
    stored.put("stepId", stepId);
    stored.put("port", port);
    stored.put("fieldPath", path);
    stored.put("retainedValueId", "");
    return stored;
  }

  private static void requireRelationship(
      JsonNode relationship, CaptureChoices choices, String sourceLeaf, String targetLeaf) {
    if (sourceLeaf.equals(targetLeaf)) {
      return;
    }
    String sourceField = relationship.path("sourceField").asText();
    String targetField = relationship.path("targetField").asText();
    if (!leaf(sourceField).equals(sourceLeaf) || !leaf(targetField).equals(targetLeaf)) {
      throw new WorkDocumentRejectedException(
          "UNEVIDENCED_MAPPING",
          "Field "
              + targetLeaf
              + " does not match retained field "
              + sourceLeaf
              + ". Record the relationship with both fields and evidence, or ask.");
    }
    if (texts(relationship.path("evidenceRefs")).isEmpty()) {
      throw new WorkDocumentRejectedException(
          "UNEVIDENCED_MAPPING",
          "The relationship between "
              + sourceLeaf
              + " and "
              + targetLeaf
              + " needs evidence. Cite a listed evidence ref, or ask.");
    }
    requireMembers(texts(relationship.path("evidenceRefs")), choices.evidenceRefs(), "Evidence ref ");
  }

  private static String canonical(
      JsonNode document, WorkTaskMaterials materials, String stepId, String port, String path) {
    if (path == null || path.isBlank() || "$".equals(path)) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Field path " + path + " is not a field. Use a $.Property path from the selected schema.");
    }
    String first = firstSegment(path);
    SchemaFragment schema = schema(materials, stepId, port);
    if (schema == null) {
      throw new WorkDocumentRejectedException(
          "MISSING_SCHEMA",
          "Step " + stepId + " port " + port + " has no schema. Load that schema before mapping the field.");
    }
    if (!schema.containsPath(first) && (label(document, first) || operation(document, first))) {
      throw new WorkDocumentRejectedException(
          "FABRICATED_PREFIX",
          "Name " + first + " is a step or operation, not a JSON prefix. Use a field path from the selected schema.");
    }
    String canonical = path.startsWith("$.") ? path : "$." + path;
    if (!schema.containsPath(canonical)) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Field path " + path + " is not in the selected contract. Name a contract field.");
    }
    return canonical;
  }

  private static void checkConstants(
      WorkTaskMaterials materials, String stepId, String port, String path, JsonNode constants) {
    SchemaFragment schema = schema(materials, stepId, port);
    JsonNode property = schema == null ? null : schema.property(path);
    if (property == null || !property.path("enum").isArray()) {
      return;
    }
    for (JsonNode constant : constants) {
      JsonNode value = constant.path("value");
      boolean allowed = false;
      for (JsonNode choice : property.path("enum")) {
        if (choice.equals(value)) {
          allowed = true;
        }
      }
      if (!allowed) {
        throw new WorkDocumentRejectedException(
            "INVALID_CONSTANT",
            "Constant " + value + " is outside the target enum. Use a listed value.");
      }
    }
  }

  private static WorkTaskCapture prepared(ArrayNode rules) {
    ObjectNode body = JSON.createObjectNode();
    body.put("outcome", "PREPARED");
    body.set("rules", rules);
    return WorkDocumentCaptureSchema.parse(WorkDocumentCaptureSchema.withUniversalLists(body));
  }

  private static void requireQuestionMembership(
      JsonNode document, JsonNode transfer, QuestionFieldRef source, QuestionFieldRef target) {
    LinkedHashSet<String> steps = new LinkedHashSet<>();
    LinkedHashSet<String> ports = new LinkedHashSet<>();
    LinkedHashSet<String> retained = new LinkedHashSet<>();
    for (JsonNode port : transfer.path("sourcePorts")) {
      steps.add(port.path("stepId").asText());
      ports.add(schemaPort(port.path("portName").asText()));
    }
    steps.add(transfer.path("targetPort").path("stepId").asText());
    ports.add(schemaPort(transfer.path("targetPort").path("portName").asText()));
    for (JsonNode retainedId : transfer.path("requiredRetainedIds")) {
      retained.add(retainedId.asText());
      JsonNode value = findRetained(document, retainedId.asText());
      if (value == null) {
        continue;
      }
      steps.add(value.path("producerStepId").asText());
      String port = schemaPort(value.path("source").path("port").asText());
      if (!port.isBlank()) {
        ports.add(port);
      }
    }
    source.requireKnown(steps, ports, retained);
    target.requireKnown(steps, ports, retained);
  }

  private static WorkTaskCapture noMappingCapture(JsonNode transfer, List<String> evidenceRefs) {
    ObjectNode body = JSON.createObjectNode();
    body.put("outcome", "PREPARED");
    ObjectNode stored = body.putArray("transfers").addObject();
    stored.put("existingId", transfer.path("id").asText());
    stored.put("alias", "");
    stored.put("targetStepRef", transfer.path("targetPort").path("stepId").asText());
    ArrayNode sources = stored.putArray("sourcePorts");
    for (JsonNode source : transfer.path("sourcePorts")) {
      ObjectNode port = sources.addObject();
      port.put("stepId", source.path("stepId").asText());
      port.put("portName", schemaPort(source.path("portName").asText()));
    }
    ObjectNode target = stored.putObject("targetPort");
    target.put("stepId", transfer.path("targetPort").path("stepId").asText());
    target.put("portName", schemaPort(transfer.path("targetPort").path("portName").asText()));
    stored.set(
        "requirementRefs",
        transfer.path("requirementIds").isArray()
            ? transfer.path("requirementIds").deepCopy()
            : JSON.createArrayNode());
    stored.put("decision", "NO_MAPPING");
    ArrayNode cited = stored.putArray("evidenceRefs");
    for (String id : evidenceRefs) {
      cited.add(id);
    }
    return WorkDocumentCaptureSchema.parse(WorkDocumentCaptureSchema.withUniversalLists(body));
  }

  private static WorkTaskScope ruleScope(
      WorkDocumentState state,
      JsonNode transfer,
      String taskId,
      String taskKey,
      WorkTaskKind kind,
      boolean repair,
      String repairRuleId) {
    String transferId = transfer.path("id").asText();
    return new WorkTaskScope(
        taskId,
        state.revision(),
        WorkStage.DATA_BEHAVIOR,
        SKILL_ID,
        repair ? List.of(repairRuleId) : List.of(transferId),
        !repair,
        repair,
        false,
        List.of(),
        List.of(),
        List.of(new CreationAllowance(WorkRecordKind.RULE, transferId)),
        repair ? List.of(repairRuleId) : List.of(),
        taskKey,
        kind,
        "",
        FixedTransferEndpoint.of(
            transferId,
            transfer.path("targetPort").path("stepId").asText(),
            schemaPort(transfer.path("targetPort").path("portName").asText()),
            outcome(transfer.path("outcome").asText())));
  }

  private static WorkTaskScope noMappingScope(
      WorkDocumentState state, JsonNode transfer, String taskId, String taskKey) {
    String transferId = transfer.path("id").asText();
    return new WorkTaskScope(
        taskId,
        state.revision(),
        WorkStage.DATA_BEHAVIOR,
        SKILL_ID,
        List.of(transferId),
        false,
        true,
        false,
        List.of(),
        List.of(),
        List.of(),
        List.of(transferId),
        taskKey,
        WorkTaskKind.MAP_TRANSFER,
        "",
        null);
  }

  private static WorkTaskMaterials instructed(
      WorkTaskMaterials materials,
      JsonNode transfer,
      List<String> sourceRefs,
      List<String> evidenceRefs,
      String repairRuleId) {
    List<String> constraints = new ArrayList<>();
    constraints.add(INSTRUCTIONS);
    constraints.add("transfer " + transfer.path("id").asText());
    constraints.add(
        "target "
            + transfer.path("targetPort").path("stepId").asText()
            + " "
            + schemaPort(transfer.path("targetPort").path("portName").asText())
            + " "
            + transfer.path("outcome").asText("UNSPECIFIED"));
    if (repairRuleId != null) {
      constraints.add("rule " + repairRuleId);
    }
    for (String ref : sourceRefs) {
      constraints.add("source " + ref);
    }
    for (String evidence : evidenceRefs) {
      constraints.add("evidence " + evidence);
    }
    constraints.addAll(materials.globalConstraints());
    return new WorkTaskMaterials(materials.schemas(), constraints, materials.sourceEvidence());
  }

  private static List<String> sourceRefs(JsonNode document, JsonNode transfer) {
    LinkedHashSet<String> refs = new LinkedHashSet<>();
    for (JsonNode source : transfer.path("sourcePorts")) {
      refs.add(source.path("stepId").asText() + "/" + schemaPort(source.path("portName").asText()));
    }
    for (JsonNode retainedId : transfer.path("requiredRetainedIds")) {
      JsonNode retained = findRetained(document, retainedId.asText());
      if (retained != null) {
        refs.add("retained/" + retainedId.asText());
      }
    }
    return List.copyOf(refs);
  }

  private static List<String> evidenceRefs(JsonNode document, JsonNode transfer) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    LinkedHashSet<String> steps = new LinkedHashSet<>();
    for (JsonNode source : transfer.path("sourcePorts")) {
      steps.add(source.path("stepId").asText());
    }
    steps.add(transfer.path("targetPort").path("stepId").asText());
    for (JsonNode retainedId : transfer.path("requiredRetainedIds")) {
      JsonNode retained = findRetained(document, retainedId.asText());
      if (retained == null) {
        continue;
      }
      steps.add(retained.path("producerStepId").asText());
      addTexts(ids, retained.path("evidenceIds"));
    }
    for (JsonNode rule : transfer.path("rules")) {
      addTexts(ids, rule.path("evidenceIds"));
    }
    for (JsonNode step : document.path("flow").path("steps")) {
      if (steps.contains(step.path("id").asText())) {
        addTexts(ids, step.path("sourceIds"));
      }
    }
    LinkedHashSet<String> withCorrections = new LinkedHashSet<>(ids);
    boolean grew = true;
    while (grew) {
      grew = false;
      for (String id : List.copyOf(withCorrections)) {
        JsonNode source = findSource(document, id);
        if (source == null) {
          continue;
        }
        for (JsonNode corrected : source.path("correctionOf")) {
          if (withCorrections.add(corrected.asText())) {
            grew = true;
          }
        }
        for (JsonNode passage : source.path("passages")) {
          withCorrections.add(passage.path("id").asText());
        }
      }
    }
    withCorrections.remove("");
    return List.copyOf(withCorrections);
  }

  private static boolean ownsPort(JsonNode transfer, String stepId, String port) {
    for (JsonNode source : transfer.path("sourcePorts")) {
      if (stepId.equals(source.path("stepId").asText())
          && port.equals(schemaPort(source.path("portName").asText()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean retainedProducerPort(
      JsonNode document, JsonNode transfer, String stepId, String port) {
    for (JsonNode retainedId : transfer.path("requiredRetainedIds")) {
      JsonNode retained = findRetained(document, retainedId.asText());
      if (retained != null && stepId.equals(retained.path("producerStepId").asText())) {
        String retainedPort = schemaPort(retained.path("source").path("port").asText());
        if (retainedPort.isBlank()) {
          retainedPort = "payload";
        }
        if (port.equals(retainedPort)) {
          return true;
        }
      }
    }
    return false;
  }

  private static JsonNode findTransfer(JsonNode document, String transferId) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        if (transferId.equals(transfer.path("id").asText())) {
          return transfer;
        }
      }
    }
    return null;
  }

  private static JsonNode transferOfRule(JsonNode document, String ruleId) {
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          if (ruleId.equals(rule.path("id").asText())) {
            return transfer;
          }
        }
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

  private static JsonNode findSource(JsonNode document, String id) {
    for (JsonNode source : document.path("sources")) {
      if (id.equals(source.path("id").asText())) {
        return source;
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

  private static boolean label(JsonNode document, String name) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (name.equals(step.path("label").asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean operation(JsonNode document, String name) {
    for (JsonNode step : document.path("flow").path("steps")) {
      if (name.equals(step.path("binding").path("operationId").asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean samePath(String stored, String proposed) {
    if (stored == null || proposed == null || stored.isBlank() || proposed.isBlank()) {
      return false;
    }
    String left = stored.startsWith("$.") ? stored : "$." + stored;
    String right = proposed.startsWith("$.") ? proposed : "$." + proposed;
    return left.equals(right);
  }

  private static void requireEvidence(JsonNode node, List<String> allowed) {
    List<String> evidence = texts(node);
    if (evidence.isEmpty()) {
      throw new WorkDocumentRejectedException(
          "UNEVIDENCED_MAPPING", "This outcome needs evidence. Cite a listed evidence ref.");
    }
    requireMembers(evidence, allowed, "Evidence ref ");
  }

  private static void requireMembers(List<String> values, List<String> allowed, String label) {
    for (String value : values) {
      if (!allowed.contains(value)) {
        throw new WorkDocumentRejectedException(
            "MALFORMED_REFERENCE", label + value + " is not allowed. Use a listed value.");
      }
    }
  }

  private static JsonNode question(JsonNode tree) {
    return object(tree, "question");
  }

  private static JsonNode object(JsonNode tree, String name) {
    JsonNode node = tree.path(name);
    if (node.isMissingNode() || node.isNull()) {
      return JSON.createObjectNode();
    }
    if (!node.isObject()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE",
          "Capture property " + name + " must be an object. Send the object this task schema defines.");
    }
    return node;
  }

  private static QuestionChoiceKind choice(String raw) {
    if (raw == null || raw.isBlank() || "UNSPECIFIED".equals(raw)) {
      return QuestionChoiceKind.UNSPECIFIED;
    }
    if ("FIELD_RELATIONSHIP".equals(raw)) {
      return QuestionChoiceKind.FIELD_RELATIONSHIP;
    }
    throw new WorkDocumentRejectedException(
        "MALFORMED_REFERENCE",
        "Choice kind " + raw + " is unknown. Use UNSPECIFIED or FIELD_RELATIONSHIP.");
  }

  private static QuestionFieldRef field(JsonNode question, String side) {
    return new QuestionFieldRef(
        question.path(side + "StepId").asText(),
        schemaPort(question.path(side + "Port").asText()),
        question.path(side + "Field").asText(),
        question.path(side + "RetainedId").asText());
  }

  private static TransferOutcome outcome(String raw) {
    if (raw == null || raw.isBlank()) {
      return TransferOutcome.UNSPECIFIED;
    }
    for (TransferOutcome value : TransferOutcome.values()) {
      if (value.name().equals(raw)) {
        return value;
      }
    }
    return TransferOutcome.UNSPECIFIED;
  }

  private static String schemaPort(String port) {
    return switch (port) {
      case "INBOUND_PAYLOAD" -> "payload";
      case "OUTBOUND_REQUEST" -> "request";
      case "SUCCESS_RESPONSE" -> "success";
      case "FAILURE_OUTCOME" -> "failure";
      case "RETAINED_CONTEXT" -> "context";
      case null -> "";
      default -> port;
    };
  }

  private static String firstSegment(String path) {
    String rest = path.startsWith("$.") ? path.substring(2) : path;
    int dot = rest.indexOf('.');
    return dot < 0 ? rest : rest.substring(0, dot);
  }

  private static String leaf(String path) {
    if (path == null) {
      return "";
    }
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
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

  private static ArrayNode textsNode(JsonNode node) {
    ArrayNode array = JSON.createArrayNode();
    for (String value : texts(node)) {
      array.add(value);
    }
    return array;
  }

  private static void addTexts(Set<String> target, JsonNode node) {
    target.addAll(texts(node));
  }

  private static String command(String taskId, WorkDocumentState state) {
    return taskId + ":" + state.revision();
  }

  private static String loadInstructions() {
    try (InputStream in = WorkMapping.class.getResourceAsStream("data-mapping.md")) {
      if (in == null) {
        throw new IllegalStateException("Data mapping instructions are missing.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("Data mapping instructions could not be read.", failure);
    }
  }
}
