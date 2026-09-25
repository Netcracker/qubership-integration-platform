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

    assertEquals(1, resolution.apiHubCalls);
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

  @Test
  void questionUsesTheStepSourceWhenDocumentHasNoSrcOm() throws Exception {
    String runId = "run-bind-source";
    runs.create(
        new RunSnapshot(
            runId,
            "conversation-bind-source",
            1L,
            RunStatus.RUNNING,
            "SERVICES",
            List.of(new StageSnapshot("SERVICES", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(
        runId,
        new WorkDocumentState("pending", JSON.readValue(sourcedDocument("src-brief"), ChainWorkDocument.class)),
        "cmd-source",
        new WorkRepairBudget(3));
    resolution.miss("createTask");

    WorkCommit commit =
        binding.select(
            runId,
            "create",
            materials(List.of("runtime-catalog-only")),
            prompt -> selection("createTask"));

    assertEquals("NEEDS_CLARIFICATION", commit.outcome().name());
    assertTrue(questions(commit.state()).toString().contains("src-brief"));
    assertFalse(questions(commit.state()).toString().contains("src-om"));
  }

  @Test
  void localStepIsNotLookedUp() throws Exception {
    String runId = "run-bind-local";
    runs.create(
        new RunSnapshot(
            runId,
            "conversation-bind-local",
            1L,
            RunStatus.RUNNING,
            "SERVICES",
            List.of(new StageSnapshot("SERVICES", StageStatus.RUNNING, List.of(), null)),
            null));
    String body = sourcedDocument("src-om").replace("\"kind\":\"SERVICE_CALL\"", "\"kind\":\"LOCAL\"");
    documents.intake(
        runId,
        new WorkDocumentState("pending", JSON.readValue(body, ChainWorkDocument.class)),
        "cmd-local",
        new WorkRepairBudget(3));

    WorkCommit commit = binding.select(runId, "create", materials(List.of()), prompt -> selection("createTask"));

    assertEquals(0, resolution.lookupCalls);
    assertEquals(0, resolution.apiHubCalls);
    assertTrue(step(commit.state(), "create").path("binding").isNull());
  }

  @Test
  void exactCatalogHitWithoutPinKeepsCatalogVersion() {
    resolution.catalogHit("createTask", "sys-wfm", "2024.4", "op-create", "http", "POST", "/wfm/v1/tasks");

    WorkCommit commit =
        binding.select(RUN_ID, "create", materials(List.of("pinned-version:")), prompt -> selection("createTask"));

    assertEquals("2024.4", step(commit.state(), "create").path("binding").path("version").asText());
  }

  @Test
  void ambiguousCatalogResultNamesCandidates() {
    resolution.ambiguous("createTask", List.of("op-a", "op-b"));

    WorkCommit commit = binding.select(RUN_ID, "create", materials(List.of()), prompt -> selection("createTask"));

    String questions = questions(commit.state()).toString();
    assertEquals("NEEDS_CLARIFICATION", commit.outcome().name());
    assertTrue(questions.contains("op-a"));
    assertTrue(questions.contains("op-b"));
    assertFalse(questions.contains("unavailable"));
    assertEquals(0, resolution.apiHubCalls);
  }

  @Test
  void selectedOperationThatMissesTheRequirementIsABindingDefect() throws Exception {
    String runId = "run-bind-wrong";
    runs.create(
        new RunSnapshot(
            runId,
            "conversation-bind-wrong",
            1L,
            RunStatus.RUNNING,
            "SERVICES",
            List.of(new StageSnapshot("SERVICES", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(
        runId,
        new WorkDocumentState("pending", JSON.readValue(requirementDocument(), ChainWorkDocument.class)),
        "cmd-wrong",
        new WorkRepairBudget(3));

    WorkCommit commit =
        binding.select(runId, "create", materials(List.of()), prompt -> selection("deleteTask"));

    assertEquals("INPUT_DEFECT", commit.outcome().name());
    assertTrue(findings(commit.state()).toString().contains("WRONG_OPERATION"));
    assertEquals(0, resolution.lookupCalls);
    assertTrue(findings(commit.state()).toString().contains("src-brief"));
  }

  @Test
  void pinnedVersionIsLookedUpInApiHubWhenAllowed() {
    resolution.miss("createTask");
    resolution.apiHubHit("createTask", "pkg.wfm", "2024.1", "op-pin", "http", "POST", "/wfm/v1/tasks");

    WorkCommit commit =
        binding.select(
            RUN_ID,
            "create",
            materials(List.of("pinned-version:2024.1", "ids-only")),
            prompt -> selection("createTask"));

    assertEquals(1, resolution.apiHubCalls);
    assertEquals("2024.1", step(commit.state(), "create").path("binding").path("version").asText());
    assertEquals(0, resolution.catalogWrites);
  }

  @Test
  void resolveApiOperationPayloadSuppliesTheApiHubVersion() {
    org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup lookup =
        org.mockito.Mockito.mock(
            org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup.class);
    org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool discovery =
        org.mockito.Mockito.mock(
            org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool.class);
    org.mockito.Mockito.when(lookup.resolve(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult.None());
    org.mockito.Mockito.when(
            discovery.resolveApiOperation(
                org.mockito.ArgumentMatchers.eq("create"),
                org.mockito.ArgumentMatchers.eq(""),
                org.mockito.ArgumentMatchers.eq(""),
                org.mockito.ArgumentMatchers.eq("createTask"),
                org.mockito.ArgumentMatchers.eq(""),
                org.mockito.ArgumentMatchers.eq("")))
        .thenReturn(
            """
            {"operations":[{"operationId":"geographicSiteManagement-v4-geographicSite-_id_-get","packageId":"S.CustParty.Care.GeoSite","packageName":"Geographic Site","version":"2026.2@1","documentId":"api","title":"Retrieve geographicSite by ID"}]}
            """);
    WorkBinding seamBinding =
        new WorkBinding(
            documents,
            runs,
            Clock.fixed(FIXED, ZoneOffset.UTC),
            new ResolveApiOperationSeam(lookup, discovery));

    WorkCommit commit =
        seamBinding.select(RUN_ID, "create", materials(List.of("ids-only")), prompt -> selection("createTask"));

    JsonNode stored = step(commit.state(), "create").path("binding");
    assertEquals("2026.2@1", stored.path("version").asText());
    assertEquals("geographicSiteManagement-v4-geographicSite-_id_-get", stored.path("operationId").asText());
    assertTrue(stored.path("contractReferences").toString().contains("apihub:S.CustParty.Care.GeoSite@2026.2@1"));
    org.mockito.Mockito.verify(discovery)
        .resolveApiOperation("create", "", "", "createTask", "", "");
    assertEquals(
        null,
        new ResolveApiOperationSeam(lookup, discovery)
            .parse(
                """
                {"interactionId":"create","status":"CATALOG_BOUND","catalogBinding":{"systemId":"sys-wfm","specificationId":"spec-create","specificationGroupId":"group-create","integrationOperationId":"op-create","systemName":"WFM","protocol":"http","method":"POST","path":"/wfm/v1/tasks","evidenceRef":"catalog-read:sys-wfm/spec-create/op-create"}}
                """,
                "createTask",
                ""));
  }

  @Test
  void seamLeavesAnUnpinnedExactHitUnresolvedWhenTheCatalogMatchHasNoVersion() {
    org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup lookup =
        org.mockito.Mockito.mock(
            org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup.class);
    org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool discovery =
        org.mockito.Mockito.mock(
            org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool.class);
    org.mockito.Mockito.when(lookup.resolve(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult.Exact(
                new org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch(
                    "sys-wfm",
                    "group-create",
                    "spec-create",
                    "op-create",
                    "WFM",
                    "http",
                    "POST",
                    "/wfm/v1/tasks",
                    "createTask",
                    "catalog-read:sys-wfm/spec-create/op-create")));
    ResolveApiOperationSeam seam = new ResolveApiOperationSeam(lookup, discovery);

    CatalogLookup result = seam.lookup("createTask", "");

    assertTrue(result instanceof CatalogLookup.VersionAbsent);
    WorkBinding seamBinding =
        new WorkBinding(documents, runs, Clock.fixed(FIXED, ZoneOffset.UTC), seam);
    WorkCommit commit = seamBinding.select(RUN_ID, "create", materials(List.of()), prompt -> selection("createTask"));
    assertTrue(step(commit.state(), "create").path("binding").isNull());
    assertTrue(questions(commit.state()).toString().contains("unresolved"));
  }

  @Test
  void seamKeepsTheSpecificationVersionWhenThePinIsEmpty() {
    org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup lookup =
        org.mockito.Mockito.mock(
            org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup.class);
    org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool discovery =
        org.mockito.Mockito.mock(
            org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool.class);
    org.mockito.Mockito.when(lookup.resolve(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult.Exact(
                new org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch(
                    "sys-wfm",
                    "group-create",
                    "spec-create",
                    "op-create",
                    "WFM",
                    "http",
                    "POST",
                    "/wfm/v1/tasks",
                    "createTask",
                    "catalog-read:sys-wfm/spec-create/op-create",
                    "1.0.0")));
    ResolveApiOperationSeam seam = new ResolveApiOperationSeam(lookup, discovery);

    CatalogLookup result = seam.lookup("createTask", "", "Create the Salesforce task");

    assertTrue(result instanceof CatalogLookup.Hit);
    assertEquals("1.0.0", ((CatalogLookup.Hit) result).hit().version());
    WorkBinding seamBinding =
        new WorkBinding(documents, runs, Clock.fixed(FIXED, ZoneOffset.UTC), seam);
    WorkCommit commit = seamBinding.select(RUN_ID, "create", materials(List.of()), prompt -> selection("createTask"));
    assertEquals("1.0.0", step(commit.state(), "create").path("binding").path("version").asText());
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

  private static String sourcedDocument(String sourceId) {
    return seededDocument().replace("src-om", sourceId);
  }

  private static String requirementDocument() {
    return sourcedDocument("src-brief")
        .replace(
            "\"requirements\": []",
            """
            "requirements": [{"id":"req-1","text":"Use createTask for the Salesforce task","sourceIds":["src-brief"],"supersededRequirementId":""}]""")
        .replace("\"requirementIds\":[]", "\"requirementIds\":[\"req-1\"]");
  }

  /** In-memory catalog and APIHub. Counts searches and imports. */
  static final class FakeResolution implements CatalogResolution {
    int apiHubCalls;
    int lookupCalls;
    int catalogWrites;
    private final List<CatalogHit> catalog = new ArrayList<>();
    private final List<ApiHubHit> hub = new ArrayList<>();
    private String unavailableVersion = "";
    private String unavailableHint = "";
    private String ambiguousHint = "";
    private List<String> ambiguousIds = List.of();

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

    void ambiguous(String hint, List<String> ids) {
      ambiguousHint = hint;
      ambiguousIds = ids;
    }

    @Override
    public CatalogLookup lookup(String operationHint, String pinnedVersion) {
      lookupCalls++;
      if (operationHint.equals(ambiguousHint) && !ambiguousIds.isEmpty()) {
        return new CatalogLookup.Ambiguous(ambiguousIds);
      }
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
    public ApiHubHit searchApiHub(String interactionId, String operationHint, String pinnedVersion) {
      apiHubCalls++;
      for (ApiHubHit hit : hub) {
        if (hit.hint().equals(operationHint)) {
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
