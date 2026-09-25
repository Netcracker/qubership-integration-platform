package org.qubership.integration.platform.ai.plan.workdocument.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.catalog.binding.CatalogOperationProjector;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.catalog.binding.ServiceCallCatalogIdentity;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding;
import org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding.PortContentHash;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.output.OutputParsingException;
import org.qubership.integration.platform.ai.plan.workdocument.QuestionChoiceKind;
import org.qubership.integration.platform.ai.plan.workdocument.QuestionFieldRef;
import org.qubership.integration.platform.ai.plan.workdocument.QuestionSubject;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentCaptureSchema;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkOutcome;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskKind;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskContext;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;
import org.qubership.integration.platform.ai.plan.workdocument.flow.WorkLogicalFlow;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

/**
 * Operation selection for one existing logical step. The model names a candidate. Java writes the
 * resolved catalog or APIHub identity onto that same step.
 */
public final class WorkBinding {

  public static final String SKILL_ID = "operation-selection";

  private static final String LISTS =
      """
      "requirements":[],"steps":[],"connections":[],"sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"transfers":[],"rules":[],"retainedValues":[],"deletes":[]
      """;

  private final WorkDocumentService documents;
  private final WorkTaskExecutor executor;
  private final CatalogResolution resolution;
  private final ObjectMapper json = new ObjectMapper();

  public WorkBinding(
      WorkDocumentService documents,
      ProductPipelineRunStore runs,
      Clock clock,
      CatalogResolution resolution) {
    this.documents = documents;
    this.resolution = resolution;
    if (runs == null || clock == null) {
      throw new IllegalArgumentException("Run store and clock are required.");
    }
    this.executor = new WorkTaskExecutor(documents, runs, clock);
  }

  public WorkCommit select(
      String runId, String stepId, WorkTaskMaterials materials, WorkTaskModel model) {
    WorkDocumentState state = documents.read(runId);
    if ("LOCAL".equals(stepKind(state, stepId))) {
      return new WorkCommit(
          state.revision(), List.of(stepId), WorkOutcome.PREPARED, "local-" + stepId, state, java.util.Map.of());
    }
    WorkTaskScope task = scope(state, stepId);
    Optional<WorkCommit> prior = executor.publishedResult(runId, task);
    if (prior.isPresent()) {
      return prior.get();
    }
    executor.reserve(runId, task);
    JsonObjectSchema schema =
        WorkDocumentCaptureSchema.responseSchema(WorkTaskKind.SELECT_OPERATION, null);
    String output;
    try {
      output =
          model.complete(
              new WorkTaskRequest(
                  task.taskId(),
                  task.taskKey(),
                  WorkTaskKind.SELECT_OPERATION,
                  WorkTaskContext.prompt(state, task, materials) + "\n" + prompt(state, stepId, materials),
                  schema));
    } catch (OutputParsingException failure) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE",
          "Operation selection could not be parsed. The task was not completed.");
    }
    JsonNode selection = readSelection(output, schema);
    String outcome = selection.path("outcome").asText();
    if ("NEEDS_CLARIFICATION".equals(outcome)) {
      if (!selection.path("candidateId").asText("").isBlank()
          || !selection.path("defectRecordRef").asText("").isBlank()) {
        throw rejected(
            "CONTRADICTORY_OUTCOME",
            "A clarification needs one question and no candidate. Send one outcome.");
      }
      String question = selection.path("question").asText("");
      if (question.isBlank()) {
        throw rejected(
            "MALFORMED_CAPTURE", "A clarification needs question text. Name the unresolved choice.");
      }
      return ask(runId, state, stepId, question, texts(selection.path("evidenceRefs")));
    }
    if ("INPUT_DEFECT".equals(outcome)) {
      if (!selection.path("question").asText("").isBlank() || !selection.path("candidateId").asText("").isBlank()) {
        throw rejected(
            "CONTRADICTORY_OUTCOME",
            "A defect capture cannot include a question or a candidate. Send the defect alone.");
      }
      return defect(runId, state, stepId, selection);
    }
    if (!"PREPARED".equals(outcome)) {
      throw rejected(
          "MALFORMED_CAPTURE",
          "Outcome " + outcome + " is unknown. Use PREPARED, NEEDS_CLARIFICATION, or INPUT_DEFECT.");
    }
    if (!selection.path("question").asText("").isBlank()
        || !selection.path("defectRecordRef").asText("").isBlank()) {
      throw rejected(
          "CONTRADICTORY_OUTCOME",
          "A prepared selection cannot also ask a question or report a defect. Send one outcome.");
    }
    String candidateId = selection.path("candidateId").asText("");
    if (candidateId.isBlank()) {
      throw rejected(
          "MALFORMED_CAPTURE",
          "A prepared selection needs a candidate id. Name the operation before lookup.");
    }
    String requiredOperation = requiredOperation(state, stepId);
    if (!requiredOperation.isBlank() && !requiredOperation.equals(candidateId)) {
      return defect(
          runId,
          state,
          stepId,
          selection,
          "WRONG_OPERATION",
          "Selected operation "
              + candidateId
              + " does not match the step requirement "
              + requiredOperation
              + ".");
    }
    String pinned = pinnedVersion(materials);
    boolean apiHubAllowed = apiHubAllowed(materials);
    CatalogLookup lookup = resolution.lookup(candidateId, pinned, systemHint(state, stepId));
    if (lookup instanceof CatalogLookup.Ambiguous ambiguous) {
      return ask(
          runId,
          state,
          stepId,
          "Catalog matches more than one operation for step "
              + stepId
              + ". Choose one of "
              + String.join(", ", ambiguous.candidateIds())
              + ".",
          List.of());
    }
    if (lookup instanceof CatalogLookup.VersionAbsent) {
      return ask(
          runId,
          state,
          stepId,
          "Catalog operation for step " + stepId + " has no version. The binding is unresolved.",
          List.of());
    }
    if (lookup instanceof CatalogLookup.Hit hit) {
      if (hit.hit().version() == null || hit.hit().version().isBlank()) {
        return ask(
            runId,
            state,
            stepId,
            "Catalog operation for step " + stepId + " has no version. The binding is unresolved.",
            List.of());
      }
      return publish(runId, state, stepId, fromCatalog(stepId, hit.hit()));
    }
    if (!apiHubAllowed) {
      String question =
          pinned.isBlank()
              ? "No runtime catalog operation for step " + stepId + ". APIHub is not used for this request."
              : "Pinned version " + pinned + " is unavailable for step " + stepId + ". APIHub is not used for this request.";
      return ask(runId, state, stepId, question, List.of());
    }
    ApiHubHit hub = resolution.searchApiHub(stepId, candidateId, pinned);
    if (hub == null || (!pinned.isBlank() && !pinned.equals(hub.version())) || hub.version().isBlank()) {
      String question =
          pinned.isBlank()
              ? "No operation matches step " + stepId + "."
              : "Pinned version " + pinned + " is unavailable for step " + stepId + ".";
      return ask(runId, state, stepId, question, List.of());
    }
    return publish(runId, state, stepId, fromApiHub(stepId, hub));
  }

  private ResolvedWorkBinding fromCatalog(String stepId, CatalogHit hit) {
    ResolvedServiceCallBinding projected = project(stepId, hit);
    projectOntoGraph(stepId, projected);
    String version = hit.version() == null ? "" : hit.version();
    return withSchemaHashes(
        new ResolvedWorkBinding(
            projected.systemId(),
            version,
            projected.operationId(),
            projected.protocolType(),
            projected.method(),
            projected.path(),
            List.of(hit.specificationId()),
            hit.exposedPorts()));
  }

  private ResolvedWorkBinding fromApiHub(String stepId, ApiHubHit hit) {
    CatalogHit asCatalog =
        new CatalogHit(
            hit.hint(),
            hit.packageId(),
            "apihub-" + hit.packageId(),
            "apihub:" + hit.packageId() + "@" + hit.version(),
            hit.version(),
            hit.operationId(),
            hit.protocol(),
            hit.method(),
            hit.path(),
            hit.exposedPorts());
    if (hit.method() == null || hit.method().isBlank()) {
      return withSchemaHashes(
          new ResolvedWorkBinding(
              hit.packageId(),
              hit.version(),
              hit.operationId(),
              hit.protocol() == null ? "" : hit.protocol(),
              "",
              hit.path() == null ? "" : hit.path(),
              List.of("apihub:" + hit.packageId() + "@" + hit.version()),
              hit.exposedPorts()));
    }
    ResolvedServiceCallBinding projected =
        project(stepId, asCatalog, ResolvedServiceCallBinding.Source.APIHUB_IMPORT);
    projectOntoGraph(stepId, projected);
    return withSchemaHashes(
        new ResolvedWorkBinding(
            projected.systemId(),
            hit.version(),
            projected.operationId(),
            projected.protocolType(),
            projected.method(),
            projected.path(),
            List.of("apihub:" + hit.packageId() + "@" + hit.version()),
            hit.exposedPorts()));
  }

  private ResolvedWorkBinding withSchemaHashes(ResolvedWorkBinding binding) {
    ContractMaterial material = resolution.loadContract(binding);
    if (!(material instanceof ContractMaterial.Ready ready)) {
      return binding;
    }
    List<PortContentHash> hashes = new ArrayList<>();
    for (PortSchemaMaterial port : ready.ports()) {
      if (port.contentHash() == null || port.contentHash().isBlank()) {
        continue;
      }
      hashes.add(new PortContentHash(port.port(), port.contentHash()));
    }
    return binding.withPortContentHashes(hashes);
  }

  private static ResolvedServiceCallBinding project(String stepId, CatalogHit hit) {
    return project(stepId, hit, ResolvedServiceCallBinding.Source.EXISTING_CATALOG);
  }

  private static ResolvedServiceCallBinding project(
      String stepId, CatalogHit hit, ResolvedServiceCallBinding.Source source) {
    return CatalogOperationProjector.project(
        stepId,
        stepId,
        new CatalogRestClient.SystemDto(hit.catalogId(), hit.catalogId(), "EXTERNAL", hit.protocol()),
        hit.specificationGroupId(),
        hit.specificationId(),
        new CatalogRestClient.OperationDto(
            hit.operationId(), hit.operationId(), hit.method(), hit.path(), null),
        source,
        hit.version(),
        hit.specificationId(),
        "");
  }

  private static void projectOntoGraph(String stepId, ResolvedServiceCallBinding projected) {
    ChainPlanNode node = new ChainPlanNode(stepId, "service-call", stepId, null, 0, List.of());
    ChainPlanGraph graph =
        new ChainPlanGraph("1.0", new ChainSection(stepId, ""), List.of(node), List.of());
    ServiceCallCatalogIdentity.upsert(graph, projected);
  }

  private WorkCommit publish(
      String runId, WorkDocumentState state, String stepId, ResolvedWorkBinding binding) {
    WorkDocumentState bound = documents.attachResolvedBinding(state, stepId, binding);
    return documents.intake(
        runId, bound, invocation(state, stepId), null, hash(binding), List.of(stepId));
  }

  private WorkCommit ask(
      String runId, WorkDocumentState state, String stepId, String question, List<String> evidenceRefs) {
    List<String> evidence = evidenceRefs == null || evidenceRefs.isEmpty() ? sourceIds(state, stepId) : evidenceRefs;
    QuestionSubject subject =
        new QuestionSubject(
            QuestionChoiceKind.UNSPECIFIED,
            new QuestionFieldRef(stepId, "", "", ""),
            QuestionFieldRef.empty());
    return documents.recordQuestion(
        runId,
        scope(state, stepId),
        question,
        subject,
        List.of(),
        evidence,
        invocation(state, stepId));
  }

  private WorkCommit defect(String runId, WorkDocumentState state, String stepId, JsonNode selection) {
    return defect(
        runId,
        state,
        stepId,
        selection,
        selection.path("issueCategory").asText("WRONG_OPERATION"),
        selection.path("contradiction").asText("Selected operation does not match the step."));
  }

  private WorkCommit defect(
      String runId,
      WorkDocumentState state,
      String stepId,
      JsonNode selection,
      String category,
      String contradiction) {
    String record = selection.path("defectRecordRef").asText(stepId);
    if (record.isBlank()) {
      record = stepId;
    }
    String capture =
        """
        {"outcome":"INPUT_DEFECT",%s,"question":"","unresolvedChoice":"","clarificationEvidenceIds":[],"defectRecordRef":%s,"contradiction":%s,"defectEvidenceIds":%s,"issueCategory":%s}
        """
            .formatted(
                LISTS,
                jsonText(record),
                jsonText(contradiction),
                evidenceJson(state, stepId),
                jsonText(category));
    return documents.apply(
        runId, scope(state, stepId), WorkDocumentCaptureSchema.parse(capture), invocation(state, stepId));
  }

  private String evidenceJson(WorkDocumentState state, String stepId) {
    return json.valueToTree(sourceIds(state, stepId)).toString();
  }

  private static List<String> sourceIds(WorkDocumentState state, String stepId) {
    JsonNode step = stepNode(state, stepId);
    List<String> sources = new java.util.ArrayList<>();
    for (JsonNode source : step.path("sourceIds")) {
      if (!source.asText().isBlank()) {
        sources.add(source.asText());
      }
    }
    return List.copyOf(sources);
  }

  private static String stepKind(WorkDocumentState state, String stepId) {
    return stepNode(state, stepId).path("kind").asText();
  }

  private static String systemHint(WorkDocumentState state, String stepId) {
    JsonNode step = stepNode(state, stepId);
    return step.path("intent").asText("") + " " + step.path("label").asText("");
  }

  private static String requiredOperation(WorkDocumentState state, String stepId) {
    JsonNode step = stepNode(state, stepId);
    String label = step.path("label").asText();
    if (label.isBlank()) {
      return "";
    }
    JsonNode requirements = jsonTree(state).path("requirements");
    for (JsonNode requirementId : step.path("requirementIds")) {
      for (JsonNode requirement : requirements) {
        if (!requirementId.asText().equals(requirement.path("id").asText())) {
          continue;
        }
        if (requirement.path("text").asText().contains(label)) {
          return label;
        }
      }
    }
    return "";
  }

  private static JsonNode stepNode(WorkDocumentState state, String stepId) {
    for (JsonNode step : jsonTree(state).path("flow").path("steps")) {
      if (stepId.equals(step.path("id").asText())) {
        return step;
      }
    }
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private static JsonNode jsonTree(WorkDocumentState state) {
    return new ObjectMapper().valueToTree(state.document());
  }

  private static WorkTaskScope scope(WorkDocumentState state, String stepId) {
    return new WorkTaskScope(
        WorkTaskPlanner.taskId(WorkTaskKind.SELECT_OPERATION, stepId),
        state.revision(),
        WorkStage.SERVICES,
        SKILL_ID,
        List.of(stepId),
        false,
        false,
        false,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        WorkTaskPlanner.taskKey(WorkTaskKind.SELECT_OPERATION, stepId),
        WorkTaskKind.SELECT_OPERATION,
        "",
        null);
  }

  private static String invocation(WorkDocumentState state, String stepId) {
    return WorkTaskPlanner.taskId(WorkTaskKind.SELECT_OPERATION, stepId) + ":" + state.revision();
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

  private static WorkDocumentRejectedException rejected(String code, String message) {
    return new WorkDocumentRejectedException(code, message);
  }

  private String prompt(WorkDocumentState state, String stepId, WorkTaskMaterials materials) {
    JsonNode flow = WorkLogicalFlow.planningTopology(state);
    return "Select one real operation for step "
        + stepId
        + ". Set outcome to PREPARED and candidateId to the operation. Java reads the catalog. Do not ask for a catalog listing. Do not send catalogId, protocol, method, or path. Constraints: "
        + materials.globalConstraints()
        + ". Flow: "
        + flow;
  }

  private static JsonNode readSelection(String output, JsonObjectSchema schema) {
    return WorkDocumentCaptureSchema.readObject(output, schema);
  }

  private static boolean apiHubAllowed(WorkTaskMaterials materials) {
    for (String constraint : materials.globalConstraints()) {
      if ("runtime-catalog-only".equals(constraint) || "no-apihub".equals(constraint)) {
        return false;
      }
    }
    return true;
  }

  private static String pinnedVersion(WorkTaskMaterials materials) {
    for (String constraint : materials.globalConstraints()) {
      if (constraint.startsWith("pinned-version:")) {
        return constraint.substring("pinned-version:".length());
      }
    }
    return "";
  }

  private String jsonText(String value) {
    return json.valueToTree(value == null ? "" : value).toString();
  }

  private static String hash(ResolvedWorkBinding binding) {
    String payload =
        binding.catalogId()
            + "|"
            + binding.version()
            + "|"
            + binding.operationId()
            + "|"
            + binding.protocol()
            + "|"
            + binding.method()
            + "|"
            + binding.path();
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }
}
