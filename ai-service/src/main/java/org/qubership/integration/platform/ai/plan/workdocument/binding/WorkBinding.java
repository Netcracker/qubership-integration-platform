package org.qubership.integration.platform.ai.plan.workdocument.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import org.qubership.integration.platform.ai.catalog.binding.CatalogOperationProjector;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.catalog.binding.ServiceCallCatalogIdentity;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentCaptureSchema;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkOutcome;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
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
  }

  public WorkCommit select(
      String runId, String stepId, WorkTaskMaterials materials, WorkTaskModel model) {
    WorkDocumentState state = documents.read(runId);
    String output = model.complete(prompt(state, stepId, materials));
    JsonNode selection = readSelection(output);
    String outcome = selection.path("outcome").asText();
    if ("NEEDS_CLARIFICATION".equals(outcome)) {
      return ask(
          runId,
          state,
          stepId,
          selection.path("question").asText(),
          selection.path("unresolvedChoice").asText("contract"));
    }
    if ("INPUT_DEFECT".equals(outcome)) {
      return defect(runId, state, stepId, selection);
    }
    String candidateId = selection.path("candidateId").asText();
    String pinned = pinnedVersion(materials);
    boolean apiHubAllowed = apiHubAllowed(materials);
    CatalogLookup lookup = resolution.lookup(candidateId, pinned);
    if (lookup instanceof CatalogLookup.PinnedUnavailable unavailable) {
      return ask(
          runId,
          state,
          stepId,
          "Pinned version "
              + unavailable.version()
              + " is unavailable for step "
              + stepId
              + ". Choose another version before binding.",
          "pinned-version");
    }
    if (lookup instanceof CatalogLookup.Hit hit) {
      return publish(runId, state, stepId, fromCatalog(stepId, hit.hit()));
    }
    if (!apiHubAllowed) {
      return ask(
          runId,
          state,
          stepId,
          "No runtime catalog operation for step "
              + stepId
              + ". APIHub is not used for this request.",
          "catalog-operation");
    }
    if (!pinned.isBlank()) {
      return ask(
          runId,
          state,
          stepId,
          "Pinned version " + pinned + " is unavailable for step " + stepId + ".",
          "pinned-version");
    }
    ApiHubHit hub = resolution.searchApiHub(candidateId, pinned);
    if (hub == null) {
      return ask(
          runId,
          state,
          stepId,
          "No operation matches step " + stepId + ".",
          "catalog-operation");
    }
    return publish(runId, state, stepId, fromApiHub(stepId, hub));
  }

  private ResolvedWorkBinding fromCatalog(String stepId, CatalogHit hit) {
    ResolvedServiceCallBinding projected = project(stepId, hit);
    projectOntoGraph(stepId, projected);
    return new ResolvedWorkBinding(
        projected.systemId(),
        hit.version(),
        projected.operationId(),
        projected.protocolType(),
        projected.method(),
        projected.path(),
        List.of(hit.specificationId()),
        hit.exposedPorts());
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
    ResolvedServiceCallBinding projected =
        project(stepId, asCatalog, ResolvedServiceCallBinding.Source.APIHUB_IMPORT);
    projectOntoGraph(stepId, projected);
    return new ResolvedWorkBinding(
        projected.systemId(),
        hit.version(),
        projected.operationId(),
        projected.protocolType(),
        projected.method(),
        projected.path(),
        List.of("apihub:" + hit.packageId() + "@" + hit.version()),
        hit.exposedPorts());
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
    String commandId = "bind-" + stepId + "-" + state.revision();
    return documents.intake(
        runId, bound, commandId, null, hash(binding), List.of(stepId));
  }

  private WorkCommit ask(
      String runId, WorkDocumentState state, String stepId, String question, String choice) {
    String capture =
        """
        {"outcome":"NEEDS_CLARIFICATION",%s,"question":%s,"unresolvedChoice":%s,"clarificationEvidenceIds":["src-om"],"defectRecordRef":"","contradiction":"","defectEvidenceIds":[],"issueCategory":""}
        """
            .formatted(LISTS, jsonText(question), jsonText(choice));
    return documents.apply(runId, scope(state, stepId), WorkDocumentCaptureSchema.parse(capture), command(state, stepId, "ask"));
  }

  private WorkCommit defect(String runId, WorkDocumentState state, String stepId, JsonNode selection) {
    String record = selection.path("stepId").asText(stepId);
    String capture =
        """
        {"outcome":"INPUT_DEFECT",%s,"question":"","unresolvedChoice":"","clarificationEvidenceIds":[],"defectRecordRef":%s,"contradiction":%s,"defectEvidenceIds":["src-om"],"issueCategory":%s}
        """
            .formatted(
                LISTS,
                jsonText(record),
                jsonText(selection.path("contradiction").asText("Selected operation does not match the step.")),
                jsonText(selection.path("issueCategory").asText("WRONG_OPERATION")));
    return documents.apply(
        runId, scope(state, stepId), WorkDocumentCaptureSchema.parse(capture), command(state, stepId, "defect"));
  }

  private static WorkTaskScope scope(WorkDocumentState state, String stepId) {
    return new WorkTaskScope(
        "operation-selection-" + stepId,
        state.revision(),
        WorkStage.SERVICES,
        SKILL_ID,
        List.of(stepId),
        false,
        false,
        false,
        List.of(),
        List.of());
  }

  private static String command(WorkDocumentState state, String stepId, String kind) {
    return kind + "-" + stepId + "-" + state.revision();
  }

  private String prompt(WorkDocumentState state, String stepId, WorkTaskMaterials materials) {
    JsonNode flow = WorkLogicalFlow.planningTopology(state);
    return "Select one real operation for step "
        + stepId
        + ". Return candidateId. Do not send catalogId, protocol, method, or path. Constraints: "
        + materials.globalConstraints()
        + ". Flow: "
        + flow;
  }

  private JsonNode readSelection(String output) {
    try {
      return json.readTree(output);
    } catch (Exception failure) {
      ObjectNode rejected = json.createObjectNode();
      rejected.put("outcome", WorkOutcome.NEEDS_CLARIFICATION.name());
      rejected.put("question", "Operation selection was not a JSON object.");
      return rejected;
    }
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
