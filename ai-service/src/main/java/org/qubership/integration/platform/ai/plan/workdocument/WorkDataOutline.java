package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.output.OutputParsingException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.workdocument.binding.ContractMaterial;
import org.qubership.integration.platform.ai.plan.workdocument.binding.PortSchemaMaterial;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskContext;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;

/**
 * DEFINE_TRANSFERS for one assigned target. {@link #define} takes a typed proposal and does not
 * call a model. {@link #propose} asks for one outline, then calls {@link #define}. Neither invents
 * transfers from operation names.
 */
public final class WorkDataOutline {

  public static final String SKILL_ID = "define-transfers";

  private final WorkDocumentService documents;
  private final WorkTaskExecutor executor;

  public WorkDataOutline(WorkDocumentService documents) {
    this(documents, null);
  }

  public WorkDataOutline(WorkDocumentService documents, WorkTaskExecutor executor) {
    this.documents = documents;
    this.executor = executor;
  }

  public static String instructions() {
    try (InputStream in = WorkDataOutline.class.getResourceAsStream("outline/define-transfers.md")) {
      if (in == null) {
        throw new IllegalStateException("define-transfers.md is missing from the classpath.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("define-transfers.md could not be read.", failure);
    }
  }

  public WorkCommit define(
      String runId,
      String targetStepId,
      OutlineProposal proposal,
      List<ContractMaterial> contracts,
      String commandId) {
    WorkDocumentState state = documents.read(runId);
    LogicalStep target = step(state, targetStepId);
    List<ContractMaterial> loaded = contracts == null ? List.of() : contracts;
    if (proposal == null || !targetStepId.equals(proposal.targetStepId())) {
      String proposed = proposal == null ? "" : proposal.targetStepId();
      throw reject(
          "MALFORMED_REFERENCE",
          "Outline target "
              + proposed
              + " is not the assigned step "
              + targetStepId
              + ". Submit the outline for the assigned step.");
    }
    Set<String> producers = producers(state, targetStepId);
    checkContracts(state, proposal, loaded);
    checkCoverage(state, target, proposal);
    checkTransfers(state, targetStepId, producers, proposal);
    checkRetained(state, producers, proposal);
    WorkDocumentState stamped = withPortHashes(state, loaded);
    return documents.applyOutline(
        runId,
        stamped,
        scope(stamped, target, producers, proposal, loaded),
        proposal,
        commandId);
  }

  /**
   * One outline call for an assigned target. Java injects the target step and then runs
   * {@link #define}. A clarification does not publish transfers.
   */
  public WorkCommit propose(
      String runId,
      String targetStepId,
      WorkTaskMaterials materials,
      List<ContractMaterial> contracts,
      WorkTaskModel model,
      String commandId) {
    WorkDocumentState state = documents.read(runId);
    step(state, targetStepId);
    String taskId = WorkTaskPlanner.taskId(WorkTaskKind.DEFINE_TRANSFERS, targetStepId);
    String taskKey = WorkTaskPlanner.taskKey(WorkTaskKind.DEFINE_TRANSFERS, targetStepId);
    JsonObjectSchema schema = WorkDocumentCaptureSchema.responseSchema(WorkTaskKind.DEFINE_TRANSFERS, null);
    WorkTaskScope promptScope =
        new WorkTaskScope(
            taskId,
            state.revision(),
            WorkStage.DATA_BEHAVIOR,
            SKILL_ID,
            List.of(targetStepId),
            false,
            false,
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            taskKey,
            WorkTaskKind.DEFINE_TRANSFERS,
            "",
            null);
    List<String> constraints = new ArrayList<>();
    constraints.add(instructions());
    if (materials != null) {
      constraints.addAll(materials.globalConstraints());
    }
    if (executor == null) {
      throw new IllegalStateException(
          "An outline model call requires a task executor. Construct WorkDataOutline with one.");
    }
    executor.reserve(runId, promptScope);
    WorkTaskMaterials instructed =
        new WorkTaskMaterials(
            materials == null ? List.of() : materials.schemas(),
            constraints,
            materials == null ? java.util.Map.of() : materials.sourceEvidence());
    String output;
    try {
      output =
          model.complete(
              new WorkTaskRequest(
                  taskId,
                  taskKey,
                  WorkTaskKind.DEFINE_TRANSFERS,
                  WorkTaskContext.prompt(state, promptScope, instructed),
                  schema));
    } catch (OutputParsingException failure) {
      throw reject(
          "MALFORMED_CAPTURE", "Outline capture could not be parsed. The task was not completed.");
    }
    JsonNode tree = WorkDocumentCaptureSchema.readObject(output, schema);
    String outcome = tree.path("outcome").asText();
    if ("NEEDS_CLARIFICATION".equals(outcome)) {
      if (!tree.path("transfers").isEmpty() || !tree.path("retainedPlaceholders").isEmpty()) {
        throw reject(
            "CONTRADICTORY_OUTCOME",
            "A clarification needs one question and no transfers. Remove the design records.");
      }
      JsonNode question = tree.path("question");
      return documents.recordQuestion(
          runId,
          promptScope,
          question.path("text").asText(),
          questionSubject(question),
          List.of(),
          texts(question.path("evidenceRefs")),
          commandId);
    }
    if ("INPUT_DEFECT".equals(outcome)) {
      return documents.apply(runId, promptScope, defectCapture(tree), commandId);
    }
    if (!"PREPARED".equals(outcome)) {
      throw reject(
          "MALFORMED_CAPTURE",
          "Outcome " + outcome + " is unknown. Use PREPARED, NEEDS_CLARIFICATION, or INPUT_DEFECT.");
    }
    if (!tree.path("question").path("text").asText().isBlank()) {
      throw reject(
          "CONTRADICTORY_OUTCOME",
          "A prepared outline cannot also ask a question. Send one outcome.");
    }
    return define(runId, targetStepId, proposal(targetStepId, tree), contracts, commandId);
  }

  private static WorkDocumentState withPortHashes(WorkDocumentState state, List<ContractMaterial> contracts) {
    List<LogicalStep> steps = new ArrayList<>();
    for (LogicalStep step : state.document().flow().steps()) {
      steps.add(stampHashes(step, contracts));
    }
    LogicalFlow flow = state.document().flow();
    ChainWorkDocument document = state.document();
    return WorkDocumentState.of(
        new ChainWorkDocument(
            document.schemaVersion(),
            document.documentId(),
            document.sources(),
            document.requirements(),
            new LogicalFlow(
                steps,
                flow.connections(),
                flow.sequenceGroups(),
                flow.conditionGroups(),
                flow.splitGroups(),
                flow.loopGroups(),
                flow.retryGroups(),
                flow.errorScopeGroups()),
            document.progress()));
  }

  private static LogicalStep stampHashes(LogicalStep step, List<ContractMaterial> contracts) {
    if (step.binding() == null) {
      return step;
    }
    List<ResolvedWorkBinding.PortContentHash> hashes = new ArrayList<>();
    for (ContractMaterial material : matches(step.binding(), contracts)) {
      if (!(material instanceof ContractMaterial.Ready ready)) {
        continue;
      }
      for (PortSchemaMaterial port : ready.ports()) {
        if (port.contentHash() == null || port.contentHash().isBlank()) {
          continue;
        }
        hashes.add(new ResolvedWorkBinding.PortContentHash(port.port(), port.contentHash()));
      }
    }
    return new LogicalStep(
        step.id(),
        step.kind(),
        step.label(),
        step.intent(),
        step.sourceIds(),
        step.requirementIds(),
        step.binding().withPortContentHashes(hashes),
        step.data());
  }

  private void checkContracts(
      WorkDocumentState state, OutlineProposal proposal, List<ContractMaterial> contracts) {
    for (String stepId : referencedSteps(proposal)) {
      LogicalStep step = step(state, stepId);
      if (step.kind() == StepKind.LOCAL || step.binding() == null) {
        if (step.binding() == null && step.kind() != StepKind.LOCAL) {
          throw reject(
              "UNAVAILABLE_CONTRACT",
              "Step "
                  + stepId
                  + " has no pinned contract. Select an operation before defining transfers.");
        }
        continue;
      }
      List<ContractMaterial> matches = matches(step.binding(), contracts);
      if (matches.isEmpty()) {
        throw reject(
            "UNAVAILABLE_CONTRACT",
            "Step "
                + stepId
                + " has no schema load for operation "
                + step.binding().operationId()
                + " version "
                + step.binding().version()
                + ". Load that contract before defining transfers.");
      }
      for (ContractMaterial material : matches) {
        rejectFailed(material);
      }
      ContractMaterial.Ready ready = (ContractMaterial.Ready) matches.get(0);
      for (String port : usedPorts(proposal, stepId)) {
        if (ready.ports().stream().noneMatch(item -> port.equals(item.port()) && usable(item.schema()))) {
          throw reject(
              "MISSING_SCHEMA",
              "Operation "
                  + step.binding().operationId()
                  + " version "
                  + step.binding().version()
                  + " has no "
                  + port
                  + " schema. Load the pinned contract before using that port.");
        }
      }
    }
  }

  private void checkCoverage(WorkDocumentState state, LogicalStep target, OutlineProposal proposal) {
    List<WorkRequirement> relevant = relevant(state, target);
    Set<String> seen = new LinkedHashSet<>();
    for (OutlineCoverage entry : proposal.coverage()) {
      if (!seen.add(entry.requirementId())) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Requirement " + entry.requirementId() + " is covered more than once. Keep one disposition.");
      }
      if (relevant.stream().noneMatch(requirement -> requirement.id().equals(entry.requirementId()))) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Requirement "
                + entry.requirementId()
                + " is not assigned to step "
                + target.id()
                + ". Cover only that step's requirements.");
      }
    }
    for (WorkRequirement requirement : relevant) {
      OutlineCoverage entry =
          proposal.coverage().stream()
              .filter(candidate -> requirement.id().equals(candidate.requirementId()))
              .findFirst()
              .orElse(null);
      if (entry == null) {
        throw reject(
            "MISSING_COVERAGE",
            "Requirement "
                + requirement.id()
                + " has no outline disposition. Assign it, record NO_MAPPING with a passage, or ask a question.");
      }
      if (entry.passageId().isBlank()) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Requirement "
                + requirement.id()
                + " needs a source passage. Cite the indexed passage, not a summary.");
      }
      WorkSource source = documents.passageSource(state, entry.passageId());
      if (!requirement.sourceIds().contains(source.id())) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Passage "
                + entry.passageId()
                + " is not evidence for requirement "
                + requirement.id()
                + ". Cite a passage from that requirement's source.");
      }
      boolean onTransfer =
          proposal.transfers().stream()
              .anyMatch(transfer -> transfer.requirementIds().contains(requirement.id()));
      if (entry.disposition() == CoverageDisposition.ASSIGNED && !onTransfer) {
        throw reject(
            "MISSING_COVERAGE",
            "Requirement "
                + requirement.id()
                + " is assigned but no transfer lists it. Add the transfer or record NO_MAPPING.");
      }
      if (entry.disposition() != CoverageDisposition.ASSIGNED && onTransfer) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Requirement "
                + requirement.id()
                + " is "
                + entry.disposition()
                + " and is also listed on a transfer. Keep one disposition.");
      }
      if (entry.disposition() == CoverageDisposition.QUESTION) {
        throw reject(
            "OPEN_QUESTION",
            "Requirement "
                + requirement.id()
                + " is still a question. Record that question before publishing the outline.");
      }
    }
  }

  private void checkTransfers(
      WorkDocumentState state, String targetStepId, Set<String> producers, OutlineProposal proposal) {
    for (OutlineTransfer transfer : proposal.transfers()) {
      if (transfer.targetPort() == null || !targetStepId.equals(transfer.targetPort().stepId())) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Transfer target is outside step " + targetStepId + ". Keep the assigned target.");
      }
      checkOutcome(transfer.targetPort().portName(), transfer.outcome());
      if (!transfer.decision().isBlank() && !"NO_MAPPING".equals(transfer.decision())) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Outline decision "
                + transfer.decision()
                + " is a field rule. Leave field rules for mapping.");
      }
      for (PortRef source : transfer.sourcePorts()) {
        step(state, source.stepId());
        if (!producers.contains(source.stepId())) {
          throw reject(
              "OUTSIDE_SCOPE",
              "Source step "
                  + source.stepId()
                  + " is not available to "
                  + targetStepId
                  + ". Choose a predecessor from the accepted flow.");
        }
        knownPort(source.portName());
      }
      knownPort(transfer.targetPort().portName());
    }
  }

  private void checkRetained(WorkDocumentState state, Set<String> producers, OutlineProposal proposal) {
    for (OutlineRetained retained : proposal.retainedPlaceholders()) {
      if (!producers.contains(retained.producerStepId())) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Producer "
                + retained.producerStepId()
                + " is not available to this target. Choose a predecessor from the accepted flow.");
      }
      for (String evidenceId : retained.evidenceIds()) {
        documents.passageSource(state, evidenceId);
      }
    }
  }

  private static void checkOutcome(String port, TransferOutcome outcome) {
    if ("success".equals(port) && outcome != TransferOutcome.SUCCESS) {
      throw reject(
          "OUTCOME_MISMATCH",
          "Port success requires outcome SUCCESS. Set the outcome that applies to that port.");
    }
    if ("failure".equals(port) && outcome != TransferOutcome.FAILURE) {
      throw reject(
          "OUTCOME_MISMATCH",
          "Port failure requires outcome FAILURE. Set the outcome that applies to that port.");
    }
    if (("request".equals(port) || "payload".equals(port)) && outcome != TransferOutcome.UNSPECIFIED) {
      throw reject(
          "OUTCOME_MISMATCH",
          "Port "
              + port
              + " is not a success or failure outcome. Leave the outcome unspecified.");
    }
  }

  private static void knownPort(String port) {
    if (!"payload".equals(port) && !"request".equals(port) && !"success".equals(port) && !"failure".equals(port)) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Port " + port + " is unknown. Use payload, request, success, or failure.");
    }
  }

  private static void rejectFailed(ContractMaterial material) {
    switch (material) {
      case ContractMaterial.MissingSchema missing -> throw reject("MISSING_SCHEMA", missing.reason());
      case ContractMaterial.Unavailable unavailable ->
          throw reject("UNAVAILABLE_CONTRACT", unavailable.reason());
      case ContractMaterial.ReadFailed failed -> throw reject("SCHEMA_READ_FAILED", failed.reason());
      case ContractMaterial.Incompatible incompatible ->
          throw reject("INCOMPATIBLE_CONTRACT", incompatible.reason());
      case ContractMaterial.Gap gap -> throw reject("SCHEMA_GAP", gap.reason());
      case ContractMaterial.Ready ignored -> {
        // A ready load is checked against the ports the proposal uses.
      }
    }
  }

  private static List<ContractMaterial> matches(
      ResolvedWorkBinding binding, List<ContractMaterial> contracts) {
    List<ContractMaterial> matches = new ArrayList<>();
    for (ContractMaterial material : contracts) {
      if (binding.operationId().equals(material.operationId())
          && binding.version().equals(material.version())
          && binding.contractReferences().contains(material.contractReference())) {
        matches.add(material);
      }
    }
    return matches;
  }

  private static Set<String> referencedSteps(OutlineProposal proposal) {
    Set<String> steps = new LinkedHashSet<>();
    steps.add(proposal.targetStepId());
    for (OutlineTransfer transfer : proposal.transfers()) {
      for (PortRef source : transfer.sourcePorts()) {
        steps.add(source.stepId());
      }
    }
    for (OutlineRetained retained : proposal.retainedPlaceholders()) {
      steps.add(retained.producerStepId());
    }
    return steps;
  }

  private static Set<String> usedPorts(OutlineProposal proposal, String stepId) {
    Set<String> ports = new LinkedHashSet<>();
    for (OutlineTransfer transfer : proposal.transfers()) {
      if (transfer.targetPort() != null && stepId.equals(transfer.targetPort().stepId())) {
        ports.add(transfer.targetPort().portName());
      }
      for (PortRef source : transfer.sourcePorts()) {
        if (stepId.equals(source.stepId())) {
          ports.add(source.portName());
        }
      }
    }
    return ports;
  }

  private static List<WorkRequirement> relevant(WorkDocumentState state, LogicalStep target) {
    List<WorkRequirement> requirements = state.document().requirements();
    Set<String> replaced = new LinkedHashSet<>();
    for (WorkRequirement requirement : requirements) {
      if (!requirement.supersededRequirementId().isBlank()) {
        replaced.add(requirement.supersededRequirementId());
      }
    }
    List<WorkRequirement> relevant = new ArrayList<>();
    for (WorkRequirement requirement : requirements) {
      if (replaced.contains(requirement.id())) {
        continue;
      }
      boolean listed = target.requirementIds().contains(requirement.id());
      boolean replacesListed =
          !requirement.supersededRequirementId().isBlank()
              && target.requirementIds().contains(requirement.supersededRequirementId());
      if (listed || replacesListed) {
        relevant.add(requirement);
      }
    }
    return relevant;
  }

  private static Set<String> producers(WorkDocumentState state, String targetStepId) {
    Set<String> reached = new LinkedHashSet<>();
    ArrayDeque<String> pending = new ArrayDeque<>();
    pending.add(targetStepId);
    while (!pending.isEmpty()) {
      String current = pending.removeFirst();
      for (LogicalConnection connection : state.document().flow().connections()) {
        if (current.equals(connection.targetStepId()) && reached.add(connection.sourceStepId())) {
          pending.add(connection.sourceStepId());
        }
      }
    }
    reached.remove(targetStepId);
    return reached;
  }

  private static WorkTaskScope scope(
      WorkDocumentState state,
      LogicalStep target,
      Set<String> producers,
      OutlineProposal proposal,
      List<ContractMaterial> contracts) {
    List<CreationAllowance> allowances = new ArrayList<>();
    allowances.add(new CreationAllowance(WorkRecordKind.OUTLINE, target.id()));
    allowances.add(new CreationAllowance(WorkRecordKind.TRANSFER, target.id()));
    for (String producer : producers) {
      allowances.add(new CreationAllowance(WorkRecordKind.RETAINED_VALUE, producer));
    }
    return new WorkTaskScope(
        "define-transfers-" + target.id(),
        state.revision(),
        WorkStage.DATA_BEHAVIOR,
        SKILL_ID,
        List.of(target.id()),
        true,
        false,
        false,
        List.of(),
        List.of(),
        allowances,
        List.of(),
        "define-transfers:" + target.id(),
        WorkTaskKind.DEFINE_TRANSFERS,
        fingerprint(state, proposal, contracts),
        null);
  }

  private static String fingerprint(
      WorkDocumentState state, OutlineProposal proposal, List<ContractMaterial> contracts) {
    StringBuilder payload = new StringBuilder();
    payload.append(proposal.targetStepId());
    for (String stepId : referencedSteps(proposal)) {
      LogicalStep step = step(state, stepId);
      payload.append('|').append(stepId);
      if (step.binding() == null) {
        continue;
      }
      payload.append('|').append(step.binding().operationId()).append('|').append(step.binding().version());
      for (ContractMaterial material : matches(step.binding(), contracts)) {
        if (!(material instanceof ContractMaterial.Ready ready)) {
          continue;
        }
        List<String> ports = new ArrayList<>();
        for (PortSchemaMaterial port : ready.ports()) {
          ports.add(port.port() + "=" + port.contentHash());
        }
        ports.sort(String::compareTo);
        for (String port : ports) {
          payload.append('|').append(port);
        }
      }
    }
    for (OutlineCoverage entry : proposal.coverage()) {
      payload.append('|').append(entry.requirementId()).append(':').append(entry.passageId());
    }
    return sha256(payload.toString());
  }

  private static LogicalStep step(WorkDocumentState state, String stepId) {
    for (LogicalStep candidate : state.document().flow().steps()) {
      if (candidate.id().equals(stepId)) {
        return candidate;
      }
    }
    throw reject(
        "MALFORMED_REFERENCE",
        "Step " + stepId + " does not exist. Outline an existing target step.");
  }

  private static boolean usable(JsonNode schema) {
    return schema != null
        && !schema.isNull()
        && (schema.has("type")
            || schema.has("$ref")
            || schema.has("properties")
            || schema.has("items")
            || schema.has("allOf")
            || schema.has("oneOf")
            || schema.has("anyOf")
            || schema.has("enum"));
  }

  private static String sha256(String content) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static OutlineProposal proposal(String targetStepId, JsonNode tree) {
    List<OutlineTransfer> transfers = new ArrayList<>();
    for (JsonNode node : tree.path("transfers")) {
      transfers.add(
          new OutlineTransfer(
              node.path("alias").asText(),
              "",
              List.of(
                  new PortRef(
                      node.path("sourceStepId").asText(), schemaPort(node.path("sourcePort").asText()))),
              new PortRef(targetStepId, schemaPort(node.path("targetPort").asText())),
              outcome(node.path("outcome").asText()),
              texts(node.path("requirementIds")),
              texts(node.path("requiredRetainedIds")),
              node.path("decision").asText()));
    }
    List<OutlineRetained> retained = new ArrayList<>();
    for (JsonNode node : tree.path("retainedPlaceholders")) {
      retained.add(
          new OutlineRetained(
              node.path("alias").asText(),
              "",
              node.path("producerStepId").asText(),
              node.path("intendedUse").asText(),
              texts(node.path("evidenceRefs"))));
    }
    List<OutlineCoverage> coverage = new ArrayList<>();
    for (JsonNode node : tree.path("coverage")) {
      coverage.add(
          new OutlineCoverage(
              node.path("requirementId").asText(),
              node.path("passageId").asText(),
              disposition(node.path("disposition").asText())));
    }
    return new OutlineProposal(targetStepId, transfers, retained, coverage);
  }

  private static QuestionSubject questionSubject(JsonNode question) {
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
    try {
      if ("FIELD_RELATIONSHIP".equals(question.path("choiceKind").asText())) {
        return QuestionSubject.fieldRelationship(source, target);
      }
      return new QuestionSubject(QuestionChoiceKind.UNSPECIFIED, source, target);
    } catch (IllegalArgumentException failure) {
      throw reject("MALFORMED_REFERENCE", failure.getMessage());
    }
  }

  private static WorkTaskCapture defectCapture(JsonNode tree) {
    JsonNode defect = tree.path("defect");
    com.fasterxml.jackson.databind.node.ObjectNode body =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    body.put("outcome", "INPUT_DEFECT");
    body.put("defectRecordRef", defect.path("recordRef").asText());
    body.put("contradiction", defect.path("contradiction").asText());
    body.put("issueCategory", defect.path("category").asText());
    com.fasterxml.jackson.databind.node.ArrayNode evidence = body.putArray("defectEvidenceIds");
    for (String id : texts(defect.path("evidenceRefs"))) {
      evidence.add(id);
    }
    return WorkDocumentCaptureSchema.parse(WorkDocumentCaptureSchema.withUniversalLists(body));
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

  private static TransferOutcome outcome(String raw) {
    if (raw == null || raw.isBlank()) {
      return TransferOutcome.UNSPECIFIED;
    }
    return TransferOutcome.valueOf(raw);
  }

  private static CoverageDisposition disposition(String raw) {
    if (raw == null || raw.isBlank()) {
      return CoverageDisposition.ASSIGNED;
    }
    return CoverageDisposition.valueOf(raw);
  }

  private static WorkDocumentRejectedException reject(String code, String message) {
    return new WorkDocumentRejectedException(code, message);
  }
}
