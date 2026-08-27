package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass.DOMAIN_FAILURE;
import static org.qubership.integration.platform.ai.productpipeline.create.design.execution.BindingResolutionResult.WAITING_FOR_INPUT;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;

class ExecutorCatalogBindingAdapterTest {

  private static final String CONVERSATION_ID = "conv-bind-1";
  private static final Instant FIXED = Instant.parse("2026-07-30T09:00:00Z");

  private CatalogSystemReadTool catalogReadTool;
  private ExecutorCatalogBindingAdapter adapter;

  @BeforeEach
  void setUp() {
    catalogReadTool = mock(CatalogSystemReadTool.class);
    adapter =
        new DefaultExecutorCatalogBindingAdapter(
            mock(CatalogBindingMatcher.class), catalogReadTool);
  }

  @Test
  void matchesV2HintByServiceCallId() {
    stubExactCatalogHit("Salesforce WFM", "sys-wfm", "sg-wfm", "spec-wfm", "op-create", "POST", "/tasks");
    CatalogBindingHint omHint =
        v2Hint("call-om-result", "fact-om", "onTaskResult", "sys-om", "op-result");
    CatalogBindingHint wfmHint = v2Hint("call-wfm", "fact-wfm", "onTaskResult", "sys-wfm", "op-create");
    ChainSemanticRevision revision =
        SemanticFixtures.linear(
            "WFM",
            "revision-wfm",
            "trigger-http",
            "node-wfm",
            "call-wfm",
            "onTaskResult",
            "Salesforce WFM",
            List.of(),
            List.of());

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, revision, List.of(omHint, wfmHint), approved());

    BindingResolutionResult.Resolved resolved =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.getFirst());
    assertEquals("call-wfm", resolved.binding().serviceCallId());
    assertEquals("sys-wfm", resolved.binding().systemId());
    assertEquals("op-create", resolved.binding().integrationOperationId());
  }

  @Test
  void doesNotMatchDuplicateOperationByQuery() {
    when(catalogReadTool.searchCatalogSystems("sys-om"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SystemDto("sys-om", "Order Management", "EXTERNAL", "http")));
    when(catalogReadTool.searchCatalogSystems("sys-wfm"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SystemDto(
                    "sys-wfm", "Salesforce WFM", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-om"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-om", "2024.4", "sg-om", "sys-om")));
    when(catalogReadTool.getApiSpecifications("sys-wfm"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto("spec-wfm", "2024.4", "sg-wfm", "sys-wfm")));
    when(catalogReadTool.listCatalogOperations("spec-om", "sys-om", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-result", "onTaskResult", "POST", "/tasks/result", "spec-om")));
    when(catalogReadTool.listCatalogOperations("spec-wfm", "sys-wfm", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-create", "onTaskResult", "POST", "/tasks", "spec-wfm")));
    CatalogBindingHint omHint =
        v2Hint("step-om", "fact-om", "onTaskResult", "sys-om", "op-result");
    CatalogBindingHint wfmHint =
        v2Hint("step-wfm", "fact-wfm", "onTaskResult", "sys-wfm", "op-create");

    List<BindingResolutionResult> results =
        adapter.resolve(
            CONVERSATION_ID,
            twoCalls("step-om", "onTaskResult", "step-wfm", "onTaskResult"),
            List.of(omHint, wfmHint),
            approved());

    assertEquals(2, results.size());
    BindingResolutionResult.Resolved first =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.get(0));
    BindingResolutionResult.Resolved second =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.get(1));
    assertEquals("step-om", first.binding().serviceCallId());
    assertEquals("op-result", first.binding().integrationOperationId());
    assertEquals("step-wfm", second.binding().serviceCallId());
    assertEquals("op-create", second.binding().integrationOperationId());
  }

  @Test
  void exactLocalMatchDoesNotTouchApiHubOrImport() {
    stubExactCatalogHit("Petstore Ext", "sys-1", "sg-1", "spec-1", "op-1", "GET", "/pets");

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleOneCall(), List.of(), approved());

    BindingResolutionResult.Resolved resolved =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.getFirst());
    assertEquals(CatalogBindingResolution.Source.EXISTING_CATALOG, resolved.binding().source());
    assertEquals("sys-1", resolved.binding().systemId());
    assertEquals("sg-1", resolved.binding().specificationGroupId());
    assertEquals("spec-1", resolved.binding().specificationId());
    assertEquals("op-1", resolved.binding().integrationOperationId());
  }

  @Test
  void catalogMissStopsInsteadOfImporting() {
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleOneCall(), List.of(), approved());

    BindingResolutionResult.Failed failed =
        assertInstanceOf(BindingResolutionResult.Failed.class, results.getFirst());
    assertEquals(DOMAIN_FAILURE, failed.outcomeClass());
    assertEquals("call-1", failed.serviceCallId());
    assertTrue(failed.reason().contains("requirement gathering"), failed.reason());
  }

  @Test
  void ambiguousCatalogMatchWaitsForInputWithoutApiHub() {
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
    when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto("op-a", "findPetsA", "GET", "/pets", "spec-1"),
                new CatalogRestClient.OperationDto("op-b", "findPetsB", "GET", "/pets", "spec-1")));

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleOneCall(), List.of(), approved());

    BindingResolutionResult.NeedsInput needsInput =
        assertInstanceOf(BindingResolutionResult.NeedsInput.class, results.getFirst());
    assertEquals(WAITING_FOR_INPUT, needsInput.outcomeClass());
    assertEquals("call-1", needsInput.serviceCallId());
    assertEquals(List.of("op-a", "op-b"), needsInput.candidateIds());
  }

  @Test
  void twoServiceCallsResolveIndependently() {
    when(catalogReadTool.searchCatalogSystems("GET /orders/{id}"))
        .thenReturn(List.of(new CatalogRestClient.SystemDto("sys-o", "Orders", "EXTERNAL", "http")));
    when(catalogReadTool.searchCatalogSystems("GET /invoices/{id}"))
        .thenReturn(List.of(new CatalogRestClient.SystemDto("sys-b", "Billing", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-o"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-o", "orders", "sg-o", "sys-o")));
    when(catalogReadTool.getApiSpecifications("sys-b"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-b", "billing", "sg-b", "sys-b")));
    when(catalogReadTool.listCatalogOperations("spec-o", "sys-o", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-o", "getOrder", "GET", "/orders/{id}", "spec-o")));
    when(catalogReadTool.listCatalogOperations("spec-b", "sys-b", null))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-b", "getInvoice", "GET", "/invoices/{id}", "spec-b")));

    List<BindingResolutionResult> results =
        adapter.resolve(
            CONVERSATION_ID,
            twoCalls("call-orders", "GET /orders/{id}", "call-billing", "GET /invoices/{id}"),
            List.of(),
            approved());

    assertEquals(2, results.size());
    BindingResolutionResult.Resolved first =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.get(0));
    BindingResolutionResult.Resolved second =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.get(1));
    assertEquals("call-orders", first.binding().serviceCallId());
    assertEquals("sys-o", first.binding().systemId());
    assertEquals("call-billing", second.binding().serviceCallId());
    assertEquals("sys-b", second.binding().systemId());
  }

  @Test
  void staleHintStopsInsteadOfSelectingAnotherOperation() {
    CatalogBindingHint stale =
        new CatalogBindingHint(
            "1",
            "fact-1",
            "GET /pets",
            "sys-stale",
            "sg-stale",
            "spec-stale",
            "op-stale",
            "2024.4",
            FIXED,
            "hint-stale");
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
    when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
        .thenReturn(
            List.of(new CatalogRestClient.OperationDto("op-1", "findPets", "GET", "/pets", "spec-1")));

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleOneCall(), List.of(stale), approved());

    BindingResolutionResult.Failed failed =
        assertInstanceOf(BindingResolutionResult.Failed.class, results.getFirst());
    assertEquals(DOMAIN_FAILURE, failed.outcomeClass());
    assertTrue(failed.reason().contains("op-stale"), failed.reason());
  }

  @Test
  void rejectsResolveWithoutMatchingApproval() {
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.resolve(CONVERSATION_ID, sampleOneCall(), List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.resolve(
                CONVERSATION_ID,
                sampleOneCall(),
                List.of(),
                new ApprovalRecordV2(
                    new CompilationArtifacts.Reference(
                        CompilationArtifacts.Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-1"),
                    "hash-1",
                    List.of(),
                    "tester",
                    "ok",
                    FIXED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  private void stubExactCatalogHit(
      String systemName,
      String systemId,
      String groupId,
      String specId,
      String opId,
      String method,
      String path) {
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(List.of(new CatalogRestClient.SystemDto(systemId, systemName, "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications(systemId))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto(specId, "2024.4", groupId, systemId)));
    when(catalogReadTool.listCatalogOperations(specId, systemId, null))
        .thenReturn(
            List.of(new CatalogRestClient.OperationDto(opId, "findPets", method, path, specId)));
  }

  private static ApprovalRecordV2 approved() {
    return new ApprovalRecordV2(
        new CompilationArtifacts.Reference(
            CompilationArtifacts.Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan"),
        "hash-plan",
        List.of(),
        "tester",
        "approved",
        FIXED,
        ApprovalPolicy.CATALOG_FIRST_V1,
        ApprovalPolicy.CATALOG_FIRST_V1_HASH,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static CatalogBindingHint v2Hint(
      String serviceCallId,
      String sourceFactId,
      String operationQuery,
      String systemId,
      String integrationOperationId) {
    String suffix = systemId.startsWith("sys-") ? systemId.substring("sys-".length()) : systemId;
    return new CatalogBindingHint(
        "2",
        serviceCallId,
        sourceFactId,
        operationQuery,
        systemId,
        "sg-" + suffix,
        "spec-" + suffix,
        integrationOperationId,
        "http",
        "POST",
        "/tasks",
        "2024.4",
        FIXED,
        "evidence-" + serviceCallId);
  }

  private static ChainSemanticRevision sampleOneCall() {
    return SemanticFixtures.linear(
        "Pets",
        "revision-pets",
        "trigger-http",
        "node-call",
        "call-1",
        "GET /pets",
        "Petstore Ext",
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision twoCalls(
      String firstCallId, String firstOperation, String secondCallId, String secondOperation) {
    ChainSemanticRevision template = SemanticFixtures.linearOrders();
    return new ChainSemanticRevision(
        template.schemaVersion(),
        "revision-two-calls",
        "Orders and billing",
        template.compilerContractVersion(),
        List.of(
            new SemanticEntryPoint(
                "entry-1",
                "trigger-http",
                "node-first",
                0,
                new SemanticProvenance(List.of()),
                new SemanticEntryPoint.Presentation("Orders", null))),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-first", firstCallId, firstOperation, new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-second", secondCallId, secondOperation, new SemanticProvenance(List.of()))),
        List.of(),
        List.of(
            new SemanticExecutionEdge("edge-1", "trigger-http", "node-first", null, null, null),
            new SemanticExecutionEdge("edge-2", "node-first", "node-second", null, null, null)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
