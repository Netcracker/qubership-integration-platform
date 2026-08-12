package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

class SpecificationImportCapabilityTest {

  @Test
  void capabilityIdIsSpecificationImport() {
    assertEquals(
        "specification-import",
        new SpecificationImportCapability(mock(CatalogMutationGateway.class), mock(RequirementDraftStore.class), mock(ConversationCatalogCache.class))
            .capabilityId());
  }

  @Test
  void skipsWithoutCallingCatalogWhenNoCandidate() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    RequirementDraft draft = readyDraft(null, null);
    when(store.get("conv-1")).thenReturn(Optional.of(draft));

    CapabilitySignal.Completed completed =
        run(capability(gateway, store), Map.of("approvedDraft", draft));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(1, completed.outcome().candidates().size());
    assertEquals(Kind.REQUIREMENT_DRAFT, completed.outcome().candidates().get(0).kind());
    verify(gateway, never()).importApiHubSpecification(any(), any());
  }

  @Test
  void skipsWithoutCallingCatalogWhenBindingAlreadyPresent() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    ResolvedCatalogBinding binding =
        new ResolvedCatalogBinding("sys", "spec", "group", "op");
    RequirementDraft draft = readyDraft(candidate(), binding);
    when(store.get("conv-1")).thenReturn(Optional.of(draft));

    CapabilitySignal.Completed completed =
        run(capability(gateway, store), Map.of("approvedDraft", draft));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    verify(gateway, never()).importApiHubSpecification(any(), any());
  }

  @Test
  void waitsForExplicitConfirmBeforeImport() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    RequirementDraft draft = pendingDraft();
    when(store.get("conv-1")).thenReturn(Optional.of(draft));

    CapabilitySignal.Completed completed =
        run(capability(gateway, store), Map.of("approvedDraft", draft));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    verify(gateway, never()).importApiHubSpecification(any(), any());
  }

  @Test
  void readerWordingAloneDoesNotConfirmImport() {
    // Mirror of the deleted phrase-matching contract: "Agree" typed as prose no longer confirms
    // the import. Only ChatEvent.IMPORT_MARKER, written by the server when the decision card is
    // clicked, does.
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    RequirementDraft draft = pendingDraft();
    when(store.get("conv-1")).thenReturn(Optional.of(draft));

    CapabilitySignal.Completed completed =
        run(
            capability(gateway, store),
            Map.of("approvedDraft", draft, "userText", "Agree"));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    verify(gateway, never()).importApiHubSpecification(any(), any());
  }

  @Test
  void importsOnDecisionMarkerAndMutatesDraft() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    ConversationCatalogCache catalogCache = mock(ConversationCatalogCache.class);
    RequirementDraft draft = pendingDraft();
    ResolvedCatalogBinding binding =
        new ResolvedCatalogBinding("sys", "spec", "group", "op", "INTERNAL");
    RequirementDraft bound = draft.withCatalogBinding(binding);
    // resolveDraft prefers the store (ADR SoT); return pending first, then bound after apply.
    when(store.get("conv-1")).thenReturn(Optional.of(draft), Optional.of(bound));
    ApiHubSpecificationImportResult result =
        new ApiHubSpecificationImportResult(
            "sys", "spec", "group", "imp-1", "GeoSite", Optional.of("op"));
    when(gateway.importApiHubSpecification(eq("conv-1"), any(ApiHubRequirementRefs.class)))
        .thenReturn(Uni.createFrom().item(result));

    CapabilitySignal.Completed completed =
        run(
            new SpecificationImportCapability(gateway, store, catalogCache),
            Map.of("approvedDraft", draft, "userText", ChatEvent.IMPORT_MARKER));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    RequirementDraft produced =
        (RequirementDraft) completed.outcome().candidates().get(0).payload();
    assertNull(produced.apiHubCandidate());
    assertFalse(produced.importIntent());
    assertEquals("sys", produced.catalogBinding().systemId());
    verify(gateway).importApiHubSpecification(eq("conv-1"), any(ApiHubRequirementRefs.class));
    verify(store).applyImportResult(eq("conv-1"), any());
  }

  @Test
  void importFailureSoftRecoversWithNeedsInput() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    RequirementDraft draft = pendingDraft();
    RequirementDraft afterFail = draft.clearApiHubCandidate().withImportIntent(true);
    when(store.get("conv-1")).thenReturn(Optional.of(draft), Optional.of(afterFail));
    when(gateway.importApiHubSpecification(eq("conv-1"), any(ApiHubRequirementRefs.class)))
        .thenReturn(Uni.createFrom().failure(new RuntimeException("catalog rejected")));

    CapabilitySignal.Completed completed =
        run(
            capability(gateway, store),
            Map.of("approvedDraft", draft, "userText", ChatEvent.IMPORT_MARKER));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    assertTrue(
        completed.outcome().message().contains("import intent is kept")
            || completed.outcome().message().contains("Import intent"));
    verify(store).recordImportFailure("conv-1");
    RequirementDraft produced =
        (RequirementDraft) completed.outcome().candidates().get(0).payload();
    assertNull(produced.apiHubCandidate());
    assertTrue(produced.importIntent());
  }

  private static SpecificationImportCapability capability(
      CatalogMutationGateway gateway, RequirementDraftStore store) {
    return new SpecificationImportCapability(gateway, store, mock(ConversationCatalogCache.class));
  }

  private static CapabilitySignal.Completed run(
      SpecificationImportCapability capability, Map<String, Object> attributes) {
    StageExecutionContext context =
        new StageExecutionContext(
            "run-1",
            "conv-1",
            "import-stage",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            attributes);
    List<CapabilitySignal> signals = capability.execute(context).collect().asList().await().indefinitely();
    return signals.stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static RequirementDraft readyDraft(
      ApiHubRequirementRefs candidate, ResolvedCatalogBinding binding) {
    return new RequirementDraft(
        true,
        "HTTP GET /greetings",
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        candidate,
        binding,
        false,
        List.of(
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.ENDPOINT,
                "http",
                "GET /greetings")),
        false);
  }

  private static RequirementDraft pendingDraft() {
    return new RequirementDraft(
        false,
        "Import GeoSite",
        DraftDecision.NEEDS_INPUT,
        List.of(),
        "brainstorming",
        "1",
        null,
        candidate(),
        null,
        false,
        List.of(
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.SERVICE_CALL,
                "geosite",
                "GeoSite catalog")),
        true);
  }

  private static ApiHubRequirementRefs candidate() {
    return new ApiHubRequirementRefs(
        "pkg.geosite", "2024.4", "op-1", null, "rest", "GeoSite", "GeoSite API");
  }
}
