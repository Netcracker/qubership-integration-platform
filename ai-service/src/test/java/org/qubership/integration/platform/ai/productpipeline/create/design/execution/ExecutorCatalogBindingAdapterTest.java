package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass.DOMAIN_FAILURE;
import static org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE;
import static org.qubership.integration.platform.ai.productpipeline.create.design.execution.BindingResolutionResult.WAITING_FOR_INPUT;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;

class ExecutorCatalogBindingAdapterTest {

  private static final String CONVERSATION_ID = "conv-bind-1";
  private static final Instant FIXED = Instant.parse("2026-07-30T09:00:00Z");

  private CatalogSystemReadTool catalogReadTool;
  private ApiHubMcpTools apiHubMcpTools;
  private CatalogMutationGateway catalogMutationGateway;
  private ExecutorCatalogBindingAdapter adapter;

  @BeforeEach
  void setUp() {
    catalogReadTool = mock(CatalogSystemReadTool.class);
    apiHubMcpTools = mock(ApiHubMcpTools.class);
    catalogMutationGateway = mock(CatalogMutationGateway.class);
    adapter =
        new DefaultExecutorCatalogBindingAdapter(
            new CatalogBindingMatcher(catalogReadTool), apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void exactLocalMatchDoesNotTouchApiHubOrImport() {
    stubExactCatalogHit("Petstore Ext", "sys-1", "sg-1", "spec-1", "op-1", "GET", "/pets");

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(), approved());

    BindingResolutionResult.Resolved resolved =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.getFirst());
    assertEquals(CatalogBindingResolution.Source.EXISTING_CATALOG, resolved.binding().source());
    assertEquals("sys-1", resolved.binding().systemId());
    assertEquals("sg-1", resolved.binding().specificationGroupId());
    assertEquals("spec-1", resolved.binding().specificationId());
    assertEquals("op-1", resolved.binding().integrationOperationId());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void catalogMissImportsViaApiHubAfterApproval() {
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());
    when(apiHubMcpTools.searchApiOperations(
            eq("GET /pets"), eq("rest"), isNull(), eq(0), eq(100), isNull()))
        .thenReturn(singleApiHubHitJson());
    when(catalogMutationGateway.importApiHubSpecification(eq(CONVERSATION_ID), any()))
        .thenReturn(
            Uni.createFrom()
                .item(
                    new ApiHubSpecificationImportResult(
                        "sys-imported",
                        "spec-imported",
                        "sg-imported",
                        "import-1",
                        "Petstore",
                        Optional.of("op-imported"))));

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(), approved());

    BindingResolutionResult.Resolved resolved =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.getFirst());
    assertEquals(CatalogBindingResolution.Source.APIHUB_IMPORT, resolved.binding().source());
    assertEquals("sys-imported", resolved.binding().systemId());
    assertEquals("pkg.petstore", resolved.binding().packageId());
    assertEquals("2024.4", resolved.binding().release());
    assertTrue(resolved.binding().evidenceRef().contains("apihub-import"));
    verify(catalogMutationGateway).importApiHubSpecification(eq(CONVERSATION_ID), any());
    verify(apiHubMcpTools, times(1))
        .searchApiOperations(eq("GET /pets"), eq("rest"), isNull(), eq(0), eq(100), isNull());
  }

  @Test
  void catalogOnlyMissFailsWithoutCallingApiHub() {
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleCatalogOnlyFlow(), List.of(), approved());

    BindingResolutionResult.Failed failed =
        assertInstanceOf(BindingResolutionResult.Failed.class, results.getFirst());
    assertEquals(DOMAIN_FAILURE, failed.outcomeClass());
    assertTrue(failed.reason().contains("local catalog"), failed.reason());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
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
        adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(), approved());

    BindingResolutionResult.NeedsInput needsInput =
        assertInstanceOf(BindingResolutionResult.NeedsInput.class, results.getFirst());
    assertEquals(WAITING_FOR_INPUT, needsInput.outcomeClass());
    assertEquals(List.of("op-a", "op-b"), needsInput.candidateIds());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void twoServiceCallsResolveIndependently() {
    when(catalogReadTool.searchCatalogSystems("Orders"))
        .thenReturn(List.of(new CatalogRestClient.SystemDto("sys-o", "Orders", "EXTERNAL", "http")));
    when(catalogReadTool.searchCatalogSystems("Billing"))
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
        adapter.resolve(CONVERSATION_ID, sampleFlowTwoCalls(), List.of(), approved());

    assertEquals(2, results.size());
    BindingResolutionResult.Resolved first =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.get(0));
    BindingResolutionResult.Resolved second =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.get(1));
    assertEquals("call-orders", first.binding().serviceCallStepId());
    assertEquals("sys-o", first.binding().systemId());
    assertEquals("call-billing", second.binding().serviceCallStepId());
    assertEquals("sys-b", second.binding().systemId());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void staleHintReReadsCatalogInsteadOfTrustingHint() {
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
    // Hint IDs are gone; live catalog has the real match.
    when(catalogReadTool.searchCatalogSystems(anyString()))
        .thenReturn(List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    when(catalogReadTool.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.SpecificationDto("spec-1", "2024.4", "sg-1", "sys-1")));
    when(catalogReadTool.listCatalogOperations("spec-1", "sys-1", null))
        .thenReturn(
            List.of(new CatalogRestClient.OperationDto("op-1", "findPets", "GET", "/pets", "spec-1")));

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(stale), approved());

    BindingResolutionResult.Resolved resolved =
        assertInstanceOf(BindingResolutionResult.Resolved.class, results.getFirst());
    assertEquals("sys-1", resolved.binding().systemId());
    assertEquals("op-1", resolved.binding().integrationOperationId());
    verify(catalogReadTool, org.mockito.Mockito.atLeastOnce()).searchCatalogSystems(anyString());
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
  }

  @Test
  void transientApiHubFailureRetriesOnceThenExhausts() {
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());
    AtomicInteger searches = new AtomicInteger();
    when(apiHubMcpTools.searchApiOperations(
            anyString(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenAnswer(
            invocation -> {
              searches.incrementAndGet();
              throw new IllegalStateException("API Hub MCP rejected session (Invalid session ID)");
            });

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(), approved());

    BindingResolutionResult.Failed failed =
        assertInstanceOf(BindingResolutionResult.Failed.class, results.getFirst());
    assertEquals(RETRYABLE_TECHNICAL_FAILURE, failed.outcomeClass());
    assertEquals(2, searches.get());
    verify(catalogMutationGateway, never()).importApiHubSpecification(anyString(), any());
  }

  @Test
  void operationNotFoundIsDomainFailureWithoutRetry() {
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());
    when(apiHubMcpTools.searchApiOperations(
            anyString(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn("{\"operations\":[]}");

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(), approved());

    BindingResolutionResult.Failed failed =
        assertInstanceOf(BindingResolutionResult.Failed.class, results.getFirst());
    assertEquals(DOMAIN_FAILURE, failed.outcomeClass());
    verify(apiHubMcpTools, times(1))
        .searchApiOperations(anyString(), anyString(), any(), anyInt(), anyInt(), any());
    verifyNoInteractions(catalogMutationGateway);
  }

  @Test
  void ambiguousApiHubMatchIsWaitingForInputWithoutRetry() {
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());
    when(apiHubMcpTools.searchApiOperations(
            anyString(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(
            """
            {"operations":[
              {"packageId":"pkg.a","version":"2024.4","operationId":"op-a","documentId":"api","apiType":"rest"},
              {"packageId":"pkg.b","version":"2024.4","operationId":"op-b","documentId":"api","apiType":"rest"}
            ]}
            """);

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(), approved());

    BindingResolutionResult.NeedsInput needsInput =
        assertInstanceOf(BindingResolutionResult.NeedsInput.class, results.getFirst());
    assertEquals(WAITING_FOR_INPUT, needsInput.outcomeClass());
    verify(apiHubMcpTools, times(1))
        .searchApiOperations(anyString(), anyString(), any(), anyInt(), anyInt(), any());
    verifyNoInteractions(catalogMutationGateway);
  }

  @Test
  void importRejectionIsDomainFailureWithoutRetry() {
    when(catalogReadTool.searchCatalogSystems(anyString())).thenReturn(List.of());
    when(apiHubMcpTools.searchApiOperations(
            anyString(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(singleApiHubHitJson());
    when(catalogMutationGateway.importApiHubSpecification(eq(CONVERSATION_ID), any()))
        .thenReturn(Uni.createFrom().failure(new IllegalStateException("import rejected by catalog")));

    List<BindingResolutionResult> results =
        adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(), approved());

    BindingResolutionResult.Failed failed =
        assertInstanceOf(BindingResolutionResult.Failed.class, results.getFirst());
    assertEquals(DOMAIN_FAILURE, failed.outcomeClass());
    verify(catalogMutationGateway, times(1)).importApiHubSpecification(eq(CONVERSATION_ID), any());
    verify(apiHubMcpTools, times(1))
        .searchApiOperations(anyString(), anyString(), any(), anyInt(), anyInt(), any());
  }

  @Test
  void rejectsResolveWithoutMatchingApproval() {
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.resolve(CONVERSATION_ID, sampleFlowOneCall(), List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.resolve(
                CONVERSATION_ID,
                sampleFlowOneCall(),
                List.of(),
                new ApprovalRecordV2(
                    new CompilationArtifacts.Reference(
                        CompilationArtifacts.Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-1"),
                    "hash-1",
                    List.of(),
                    "tester",
                    "ok",
                    FIXED)));
    verifyNoInteractions(apiHubMcpTools, catalogMutationGateway);
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
        ApprovalPolicy.CATALOG_FIRST_V1_HASH);
  }

  private static NormalizedDesignFlow sampleFlowOneCall() {
    return new NormalizedDesignFlow(
        "1",
        "flow-1",
        "Pending pets",
        "",
        new NormalizedDesignFlow.Trigger(
            "http", "client", "HTTP", "/get-pending-pets", "GET", List.of("fact-t")),
        List.of(
            new NormalizedDesignFlow.Participant("client", "Client", "EXTERNAL", List.of("fact-t")),
            new NormalizedDesignFlow.Participant(
                "petstore", "Petstore Ext", "EXTERNAL", List.of("fact-1"))),
        List.of(
            new NormalizedDesignFlow.Step(
                "call-1",
                "service-call",
                "client",
                "petstore",
                "GET /pets",
                "",
                List.of("fact-1"))),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static NormalizedDesignFlow sampleFlowTwoCalls() {
    return new NormalizedDesignFlow(
        "1",
        "flow-2",
        "Orders and billing",
        "",
        new NormalizedDesignFlow.Trigger(
            "http", "client", "HTTP", "/sync", "POST", List.of("fact-t")),
        List.of(
            new NormalizedDesignFlow.Participant("client", "Client", "EXTERNAL", List.of("fact-t")),
            new NormalizedDesignFlow.Participant("orders", "Orders", "EXTERNAL", List.of("fact-o")),
            new NormalizedDesignFlow.Participant(
                "billing", "Billing", "EXTERNAL", List.of("fact-b"))),
        List.of(
            new NormalizedDesignFlow.Step(
                "call-orders",
                "service-call",
                "client",
                "orders",
                "GET /orders/{id}",
                "",
                List.of("fact-o")),
            new NormalizedDesignFlow.Step(
                "call-billing",
                "service-call",
                "client",
                "billing",
                "GET /invoices/{id}",
                "",
                List.of("fact-b"))),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static NormalizedDesignFlow sampleCatalogOnlyFlow() {
    NormalizedDesignFlow flow = sampleFlowOneCall();
    return new NormalizedDesignFlow(
        flow.schemaVersion(),
        flow.flowId(),
        flow.chainName(),
        flow.description(),
        flow.trigger(),
        flow.participants(),
        flow.steps(),
        flow.connections(),
        flow.transformations(),
        flow.dataMappings(),
        flow.constraints(),
        flow.assumptions(),
        NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_ONLY);
  }

  private static String singleApiHubHitJson() {
    return """
        {"operations":[
          {"packageId":"pkg.petstore","version":"2024.4","operationId":"findPets","documentId":"api","apiType":"rest","packageName":"Petstore","title":"Petstore"}
        ]}
        """;
  }
}
