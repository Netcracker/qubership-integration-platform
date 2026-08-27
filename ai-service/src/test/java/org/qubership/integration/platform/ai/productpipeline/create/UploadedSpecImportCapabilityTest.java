package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.qubership.integration.platform.ai.chat.attachment.SpecType;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;
import org.qubership.integration.platform.ai.plan.UploadedSpecCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

class UploadedSpecImportCapabilityTest {

  @Test
  void capabilityIdIsUploadedSpecImport() {
    assertEquals(
        "uploaded-spec-import",
        new UploadedSpecImportCapability(
                mock(CatalogMutationGateway.class), mock(RequirementDraftStore.class))
            .capabilityId());
  }

  @Test
  void skipsWhenNoCandidates() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    RequirementDraft draft =
        new RequirementDraft(
            true, "Create order", DraftDecision.READY_FOR_PLAN, List.of(), "x", "1");
    when(store.get("conv-1")).thenReturn(Optional.of(draft));

    CapabilitySignal.Completed completed = run(gateway, store, draft);

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals("uploaded-spec-import skipped", completed.outcome().message());
    verify(gateway, never()).importUploadedSpecifications(any(), any());
  }

  @Test
  void importsCandidatesAndClearsThemFromDraft() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    UploadedSpecCandidate candidate =
        new UploadedSpecCandidate("uploads/order.json", "Order API", SpecType.OPENAPI);
    RequirementDraft draft =
        new RequirementDraft(
            true,
            "Create order",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            List.of(),
            false,
            List.of(candidate),
            List.of());
    when(store.get("conv-1")).thenReturn(Optional.of(draft));
    ResolvedCatalogBinding binding =
        new ResolvedCatalogBinding("sys-1", "spec-1", "group-1", null, "INTERNAL");
    when(gateway.importUploadedSpecifications(eq("conv-1"), eq(List.of(candidate))))
        .thenReturn(Uni.createFrom().item(List.of(new UploadedSpecImportResult(candidate.s3Key(), binding))));

    CapabilitySignal.Completed completed = run(gateway, store, draft);

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(1, completed.outcome().candidates().size());
    assertEquals(
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
        completed.outcome().candidates().get(0).kind());
    RequirementDraft produced =
        (RequirementDraft) completed.outcome().candidates().get(0).payload();
    assertTrue(produced.uploadedSpecCandidates().isEmpty());
    assertEquals(1, produced.uploadedSpecImportResults().size());
    assertEquals("spec-1", produced.uploadedSpecImportResults().get(0).binding().specificationId());
  }

  @Test
  void importFailureReturnsNeedsInput() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    RequirementDraftStore store = mock(RequirementDraftStore.class);
    UploadedSpecCandidate candidate =
        new UploadedSpecCandidate("uploads/order.json", "Order API", SpecType.OPENAPI);
    RequirementDraft draft =
        new RequirementDraft(
            true,
            "Create order",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            List.of(),
            false,
            List.of(candidate),
            List.of());
    when(store.get("conv-1")).thenReturn(Optional.of(draft));
    when(gateway.importUploadedSpecifications(eq("conv-1"), any()))
        .thenReturn(Uni.createFrom().failure(new RuntimeException("catalog rejected")));

    CapabilitySignal.Completed completed = run(gateway, store, draft);

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    assertTrue(completed.outcome().message().contains("catalog rejected"));
  }

  private static CapabilitySignal.Completed run(
      CatalogMutationGateway gateway, RequirementDraftStore store, RequirementDraft draft) {
    StageExecutionContext context =
        new StageExecutionContext(
            "run-1",
            "conv-1",
            "uploaded-spec-import",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            Map.of("approvedDraft", draft));
    List<CapabilitySignal> signals =
        new UploadedSpecImportCapability(gateway, store).execute(context).collect().asList().await().indefinitely();
    return signals.stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .findFirst()
        .orElseThrow();
  }
}
