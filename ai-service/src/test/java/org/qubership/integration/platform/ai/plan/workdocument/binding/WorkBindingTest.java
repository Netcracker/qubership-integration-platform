package org.qubership.integration.platform.ai.plan.workdocument.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkBindingTest {

  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");
  private static final String RUN_ID = "run-bind-1";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private WorkDocumentService documents;
  private ProductPipelineRunStore runs;
  private FakeResolution resolution;
  private WorkBinding binding;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, clock);
    runs = new ProductPipelineRunStore(blobs, JSON, clock);
    documents = new WorkDocumentService(runs, artifacts, JSON);
    runs.create(openRun());
    documents.intake(
        RUN_ID,
        new WorkDocumentState("pending", JSON.readValue(seededDocument(), ChainWorkDocument.class)),
        "cmd-seed",
        new WorkRepairBudget(3));
    resolution = new FakeResolution();
    binding = new WorkBinding(documents, runs, clock, resolution);
  }

  @Test
  void modelPathMethodAndCatalogIdCannotOverrideResolvedMetadata() {
    resolution.catalogHit("createTask", "sys-wfm", "2024.4", "op-create", "http", "POST", "/wfm/v1/tasks");

    WorkCommit commit =
        binding.select(RUN_ID, "create", materials(List.of()), prompt -> authoredOverride("createTask"));

    JsonNode step = step(commit.state(), "create");
    JsonNode stored = step.path("binding");
    assertEquals("sys-wfm", stored.path("catalogId").asText());
    assertEquals("2024.4", stored.path("version").asText());
    assertEquals("op-create", stored.path("operationId").asText());
    assertEquals("http", stored.path("protocol").asText());
    assertEquals("POST", stored.path("method").asText());
    assertEquals("/wfm/v1/tasks", stored.path("path").asText());
    assertFalse(stored.toString().contains("model-catalog"));
    assertFalse(stored.toString().contains("DELETE"));
    assertFalse(stored.toString().contains("/model/path"));
    assertTrue(stored.path("contractReferences").toString().contains("spec-create"));
  }

  @Test
  void sameStepIdSurvivesBindingAndRebinding() {
    resolution.catalogHit("createTask", "sys-wfm", "2024.4", "op-create", "http", "POST", "/wfm/v1/tasks");
    WorkCommit first = binding.select(RUN_ID, "create", materials(List.of()), prompt -> selection("createTask"));
    assertEquals("create", step(first.state(), "create").path("id").asText());

    resolution.catalogHit("createTask", "sys-wfm", "2024.4", "op-create-v2", "http", "POST", "/wfm/v2/tasks");
    WorkCommit second = binding.select(RUN_ID, "create", materials(List.of()), prompt -> selection("createTask"));

    JsonNode step = step(second.state(), "create");
    assertEquals("create", step.path("id").asText());
    assertEquals("op-create-v2", step.path("binding").path("operationId").asText());
    assertEquals("/wfm/v2/tasks", step.path("binding").path("path").asText());
    assertEquals(1, steps(second.state()).size() == 0 ? 0 : countId(second.state(), "create"));
  }

  @Test
  void noApiHubInputCausesZeroApiHubCalls() {
    resolution.miss("createTask");

    WorkCommit commit =
        binding.select(
            RUN_ID,
            "create",
            materials(List.of("runtime-catalog-only")),
            prompt -> selection("createTask"));

    assertEquals(0, resolution.apiHubCalls);
    assertEquals(0, resolution.catalogWrites);
    assertEquals("NEEDS_CLARIFICATION", commit.outcome().name());
    assertTrue(questions(commit.state()).toString().contains("create"));
    assertTrue(step(commit.state(), "create").path("binding").isNull());
  }

  @Test
  void idsOnlyRetainsExactApiHubVersionWithZeroCatalogWrites() {
    resolution.apiHubHit("createTask", "pkg.wfm", "2024.4", "op-hub", "http", "POST", "/wfm/v1/tasks");

    WorkCommit commit =
        binding.select(
            RUN_ID, "create", materials(List.of("ids-only")), prompt -> selection("createTask"));

    JsonNode stored = step(commit.state(), "create").path("binding");
    assertEquals("2024.4", stored.path("version").asText());
    assertEquals("op-hub", stored.path("operationId").asText());
    assertEquals("POST", stored.path("method").asText());
    assertEquals("/wfm/v1/tasks", stored.path("path").asText());
    assertEquals(1, resolution.apiHubCalls);
    assertEquals(0, resolution.catalogWrites);
    assertTrue(stored.path("contractReferences").toString().contains("apihub:pkg.wfm@2024.4"));
  }

  @Test
  void unavailablePinnedVersionStaysVisibleAndIsNotUpgraded() {
    resolution.pinnedUnavailable("createTask", "2024.1");
    resolution.apiHubHit("createTask", "pkg.wfm", "2025.1", "op-new", "http", "POST", "/wfm/v2/tasks");

    WorkCommit commit =
        binding.select(
            RUN_ID,
            "create",
            materials(List.of("pinned-version:2024.1")),
            prompt -> selection("createTask"));

    assertEquals(0, resolution.apiHubCalls);
    assertEquals(0, resolution.catalogWrites);
    assertTrue(step(commit.state(), "create").path("binding").isNull());
    String questions = questions(commit.state()).toString();
    assertTrue(questions.contains("2024.1"));
    assertFalse(questions.contains("2025.1"));
    assertEquals("NEEDS_CLARIFICATION", commit.outcome().name());
  }

  @Test
  void wrongOperationIsABindingDefectAndUnsatisfiedContractAsks() {
    WorkCommit defect =
        binding.select(RUN_ID, "create", materials(List.of()), prompt -> defect("create", "WRONG_OPERATION"));

    assertEquals("INPUT_DEFECT", defect.outcome().name());
    assertTrue(findings(defect.state()).toString().contains("WRONG_OPERATION"));
    assertEquals(0, resolution.apiHubCalls);

    WorkCommit ask =
        binding.select(
            RUN_ID,
            "create",
            materials(List.of()),
            prompt -> clarification("The createTask contract has no Subject field."));

    assertEquals("NEEDS_CLARIFICATION", ask.outcome().name());
    assertTrue(questions(ask.state()).toString().contains("Subject"));
  }

  private static WorkTaskMaterials materials(List<String> constraints) {
    return new WorkTaskMaterials(List.of(), constraints, Map.of("src-om", "Call Salesforce createTask."));
  }

  private static String selection(String candidateId) {
    return """
        {"outcome":"PREPARED","candidateId":"%s","stepId":"create"}
        """.formatted(candidateId);
  }

  private static String authoredOverride(String candidateId) {
    return """
        {"outcome":"PREPARED","candidateId":"%s","stepId":"create","catalogId":"model-catalog","method":"DELETE","path":"/model/path","protocol":"grpc"}
        """.formatted(candidateId);
  }

  private static String defect(String stepId, String category) {
    return """
        {"outcome":"INPUT_DEFECT","stepId":"%s","issueCategory":"%s","contradiction":"Selected operation does not match the step.","evidenceIds":["src-om"]}
        """.formatted(stepId, category);
  }

  private static String clarification(String question) {
    return """
        {"outcome":"NEEDS_CLARIFICATION","stepId":"create","question":"%s","unresolvedChoice":"contract","evidenceIds":["src-om"]}
        """.formatted(question);
  }

  private static JsonNode step(WorkDocumentState state, String id) {
    for (JsonNode candidate : steps(state)) {
      if (id.equals(candidate.path("id").asText())) {
        return candidate;
      }
    }
    return JSON.nullNode();
  }

  private static JsonNode steps(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("flow").path("steps");
  }

  private static int countId(WorkDocumentState state, String id) {
    int count = 0;
    for (JsonNode candidate : steps(state)) {
      if (id.equals(candidate.path("id").asText())) {
        count++;
      }
    }
    return count;
  }

  private static JsonNode questions(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("progress").path("questions");
  }

  private static JsonNode findings(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("progress").path("findings");
  }

  private static RunSnapshot openRun() {
    return new RunSnapshot(
        RUN_ID,
        "conversation-bind-1",
        1L,
        RunStatus.RUNNING,
        "SERVICES",
        List.of(new StageSnapshot("SERVICES", StageStatus.RUNNING, List.of(), null)),
        null);
  }

  private static String seededDocument() {
    return """
        {
          "schemaVersion": 1,
          "documentId": "doc-bind",
          "sources": [{
            "id": "src-om",
            "role": "request",
            "contentReference": "artifact://om",
            "contentHash": "hash-om",
            "originalName": "request.md",
            "suppliedIdentifier": "OM-1",
            "correctionOf": []
          }],
          "requirements": [],
          "flow": {
            "steps": [
              {"id":"create","kind":"SERVICE_CALL","label":"createTask","intent":"Create the Salesforce task","sourceIds":["src-om"],"requirementIds":[],"binding":null,"data":{"transfers":[],"retainedValues":[]}}
            ],
            "connections": [],
            "sequenceGroups": [],
            "conditionGroups": [],
            "splitGroups": [],
            "loopGroups": [],
            "retryGroups": [],
            "errorScopeGroups": []
          },
          "progress": {"tasks":[],"findings":[],"questions":[],"approvalReference":"","derivedResultReferences":[],"recheckStages":[]}
        }
        """;
  }

  /** In-memory catalog and APIHub. Counts searches and imports. */
  static final class FakeResolution implements CatalogResolution {
    int apiHubCalls;
    int catalogWrites;
    private final List<CatalogHit> catalog = new ArrayList<>();
    private final List<ApiHubHit> hub = new ArrayList<>();
    private String unavailableVersion = "";
    private String unavailableHint = "";

    void catalogHit(
        String hint, String catalogId, String version, String operationId, String protocol, String method, String path) {
      catalog.removeIf(hit -> hit.hint().equals(hint));
      catalog.add(new CatalogHit(hint, catalogId, "group-" + operationId, "spec-" + operationId.replace("op-", ""), version, operationId, protocol, method, path, List.of("request", "success", "failure")));
    }

    void apiHubHit(
        String hint, String packageId, String version, String operationId, String protocol, String method, String path) {
      hub.add(new ApiHubHit(hint, packageId, version, operationId, protocol, method, path, List.of("request", "success", "failure")));
    }

    void miss(String hint) {}

    void pinnedUnavailable(String hint, String version) {
      unavailableHint = hint;
      unavailableVersion = version;
    }

    @Override
    public CatalogLookup lookup(String operationHint, String pinnedVersion) {
      if (pinnedVersion != null
          && !pinnedVersion.isBlank()
          && pinnedVersion.equals(unavailableVersion)
          && operationHint.equals(unavailableHint)) {
        return new CatalogLookup.PinnedUnavailable(pinnedVersion);
      }
      for (CatalogHit hit : catalog) {
        if (hit.hint().equals(operationHint) && (pinnedVersion == null || pinnedVersion.isBlank() || pinnedVersion.equals(hit.version()))) {
          return new CatalogLookup.Hit(hit);
        }
      }
      return new CatalogLookup.Miss();
    }

    @Override
    public ApiHubHit searchApiHub(String operationHint, String pinnedVersion) {
      apiHubCalls++;
      for (ApiHubHit hit : hub) {
        if (hit.hint().equals(operationHint)
            && (pinnedVersion == null || pinnedVersion.isBlank() || pinnedVersion.equals(hit.version()))) {
          return hit;
        }
      }
      return null;
    }

    @Override
    public void importContract(ApiHubHit hit) {
      catalogWrites++;
    }
  }
}
