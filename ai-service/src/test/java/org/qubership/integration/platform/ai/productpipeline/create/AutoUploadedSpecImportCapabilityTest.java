package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecAttachment;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.storage.S3Service;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportOutcome;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;

class AutoUploadedSpecImportCapabilityTest {

  @Test
  void skipsWhenNoAttachments() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    when(conversationService.getAllowedAttachmentKeys("conv-1")).thenReturn(List.of());

    CapabilitySignal.Completed completed = run(capability, draft, List.of());

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(1, completed.outcome().candidates().size());
    assertEquals(
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
        completed.outcome().candidates().get(0).kind());
    verify(gateway, never()).importUploadedSpec(any(), any(), any());
  }

  @Test
  void needsInputWhenApprovalMissing() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/spec.yaml"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of());

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    assertEquals(0, completed.outcome().candidates().size());
    verify(gateway, never()).importUploadedSpec(any(), any(), any());
  }

  @Test
  void importsAllAttachmentsAfterApproval() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml", "uploads/notifications-async.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(1, completed.outcome().candidates().size());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")),
            eq("INTERNAL"));
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/notifications-async.yaml", "notifications-async.yaml")),
            eq("INTERNAL"));
  }

  @Test
  void importsWithPreferredExternalSystemType() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    RequirementDraftStore draftStore = new RequirementDraftStore();
    draftStore.put("conv-1", draft().withPreferredSystemType("EXTERNAL"));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway,
            conversationService,
            artifactStore,
            handler,
            mock(CatalogBindingMatcher.class),
            draftStore);
    RequirementDraft draft = draft().withPreferredSystemType("EXTERNAL");
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("EXTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")),
            eq("EXTERNAL"));
  }

  @Test
  void returnsNeedsInputWhenAllImportsFail() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml", "uploads/notifications-async.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(Uni.createFrom().failure(new RuntimeException("import failed")));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(approvalRef));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    assertEquals(0, completed.outcome().candidates().size());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")),
            eq("INTERNAL"));
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/notifications-async.yaml", "notifications-async.yaml")),
            eq("INTERNAL"));
  }

  @Test
  void importsFromApprovalRecordKeysWhenCurrentKeysEmpty() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    when(conversationService.getAllowedAttachmentKeys("conv-1")).thenReturn(List.of());
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(
        artifactStore,
        approvalRef,
        approvalRef.contentHash(),
        List.of("uploads/orders-api.yaml"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(1, completed.outcome().candidates().size());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")),
            eq("INTERNAL"));
  }

  @Test
  void importsSpecsAfterNormalizingMalformedKeys() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(
            List.of(
                "sessions/conv/a.json\n"
                    + "- http://localhost:8080/api/v1/storage/objects?key=sessions/conv/b.json"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"), eq(new UploadedSpecAttachment("sessions/conv/a.json", "a.json")), eq("INTERNAL"));
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("sessions/conv/b.json", "b.json")),
            eq("INTERNAL"));
  }

  @Test
  void needsInputWhenApprovalRecordHashDoesNotMatchCurrentAttachments() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml", "uploads/notifications-async.yaml"));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, "stale-hash");

    CapabilitySignal.Completed completed = run(capability, draft, List.of(approvalRef));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    assertEquals(0, completed.outcome().candidates().size());
    verify(gateway, never()).importUploadedSpec(any(), any(), any());
  }

  @Test
  void laterNonUploadedSpecsApprovalDoesNotSatisfyImportCheck() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml", "uploads/notifications-async.yaml"));
    when(artifactStore.findLatestApprovalRecord(
            "run-1",
            UploadedSpecsApprovalHandler.ARTIFACT_TYPE,
            handler.attachmentHash("conv-1")))
        .thenReturn(Optional.empty());

    CapabilitySignal.Completed completed = run(capability, draft, List.of());

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    assertEquals(0, completed.outcome().candidates().size());
    verify(gateway, never()).importUploadedSpec(any(), any(), any());
  }

  @Test
  void skipsImportWhenInputDraftHasCatalogBinding() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draftWithBinding();
    CompilationArtifacts.Reference draftRef = requirementDraftRef();
    stubRequirementDraft(artifactStore, draftRef, draft);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/spec.yaml"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(draftRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(1, completed.outcome().candidates().size());
    assertEquals(draft, completed.outcome().candidates().get(0).payload());
    verify(gateway, never()).importUploadedSpec(any(), any(), any());
  }

  @Test
  void importsWhenFlowHasPartialCatalogBinding() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, matcher, mock(RequirementDraftStore.class));
    RequirementDraft draft =
        omWfmDraft().withBoundInteraction("create-salesforce-task", restHint("create-salesforce-task"));
    CompilationArtifacts.Reference draftRef = requirementDraftRef();
    stubRequirementDraft(artifactStore, draftRef, draft);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/om-async.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    when(matcher.matchImported(eq("service-call"), any(), any(), any(), any(), eq("conv-1")))
        .thenReturn(new CatalogBindingMatcher.MatchResult.None());
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(draftRef, approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/om-async.yaml", "om-async.yaml")),
            eq("INTERNAL"));
  }

  @Test
  void skipsImportWhenEveryOutboundInteractionIsBound() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft =
        omWfmDraft()
            .withBoundInteraction("create-salesforce-task", restHint("create-salesforce-task"))
            .withBoundInteraction("return-task-result", restHint("return-task-result"));
    CompilationArtifacts.Reference draftRef = requirementDraftRef();
    stubRequirementDraft(artifactStore, draftRef, draft);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/om-async.yaml"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(draftRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    verify(gateway, never()).importUploadedSpec(any(), any(), any());
  }

  @Test
  void importsAttachmentsWhenInputDraftHasNoCatalogBinding() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, mock(CatalogBindingMatcher.class), mock(RequirementDraftStore.class));
    RequirementDraft draft = draft();
    CompilationArtifacts.Reference draftRef = requirementDraftRef();
    stubRequirementDraft(artifactStore, draftRef, draft);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(draftRef, approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(1, completed.outcome().candidates().size());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")),
            eq("INTERNAL"));
  }

  @Test
  void emitsCatalogBindingHintWhenImportMatchesServiceCallFact() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    RequirementDraftStore draftStore = mock(RequirementDraftStore.class);
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, matcher, draftStore);
    RequirementFact fact =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "stub-openapi",
            "call stubOperation on the uploaded Stub OpenAPI Service API (POST /stub/path)");
    RequirementDraft draft = draft().withFacts(List.of(fact));
    CompilationArtifacts.Reference draftRef = requirementDraftRef();
    stubRequirementDraft(artifactStore, draftRef, draft);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/stub-openapi.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    when(matcher.matchImported(
            eq("service-call"), eq("sys"), eq("group"), eq("spec"), any(), eq("conv-1")))
        .thenReturn(
            new CatalogBindingMatcher.MatchResult.Exact(
                new CatalogMatch(
                    "sys",
                    "group",
                    "spec",
                    "op-stub-operation",
                    "Stub OpenAPI Service",
                    "rest",
                    "POST",
                    "/stub/path",
                    "stubOperation",
                    "catalog-read:sys/spec/op-stub-operation")));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(draftRef, approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    List<ArtifactCandidate> hints =
        completed.outcome().candidates().stream()
            .filter(c -> c.kind() == CompilationArtifacts.Kind.CATALOG_BINDING_HINT)
            .toList();
    assertEquals(1, hints.size());
    CatalogBindingHint hint = (CatalogBindingHint) hints.get(0).payload();
    assertEquals("op-stub-operation", hint.integrationOperationId());
    assertEquals("sys", hint.systemId());
    assertEquals("spec", hint.specificationId());
    assertEquals(fact.sourceFactId(), hint.sourceFactId());
    RequirementDraft updated =
        (RequirementDraft)
            completed.outcome().candidates().stream()
                .filter(c -> c.kind() == CompilationArtifacts.Kind.REQUIREMENT_DRAFT)
                .findFirst()
                .orElseThrow()
                .payload();
    assertEquals(
        "Call catalog-bound Stub OpenAPI Service stubOperation operation, POST /stub/path",
        updated.facts().get(0).text());
    ArgumentCaptor<RequirementDraft> storeDraft = ArgumentCaptor.forClass(RequirementDraft.class);
    verify(draftStore).put(eq("conv-1"), storeDraft.capture());
    assertEquals(
        "Call catalog-bound Stub OpenAPI Service stubOperation operation, POST /stub/path",
        storeDraft.getValue().facts().get(0).text());
    verify(matcher)
        .matchImported(eq("service-call"), eq("sys"), eq("group"), eq("spec"), any(), eq("conv-1"));
  }

  @Test
  void emitsCatalogBindingHintWhenImportMatchesFlowInteraction() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    RequirementDraftStore draftStore = mock(RequirementDraftStore.class);
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, matcher, draftStore);
    RequirementDraft draft =
        draft()
            .withFacts(
                List.of(
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.GOAL,
                        "chain",
                        "Create OM to Salesforce WFM")))
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "on-task-start", Direction.INBOUND, "Caller", "POST /tasks", ""),
                        new Interaction(
                            "create-salesforce-task",
                            Direction.OUTBOUND,
                            "Salesforce WFM",
                            "createTask",
                            "")),
                    List.of(new Transition("on-task-start", "create-salesforce-task"))));
    CompilationArtifacts.Reference draftRef = requirementDraftRef();
    stubRequirementDraft(artifactStore, draftRef, draft);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/salesforce-wfm.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    when(matcher.matchImported(
            eq("service-call"), eq("sys"), eq("group"), eq("spec"), eq("createTask"), eq("conv-1")))
        .thenReturn(
            new CatalogBindingMatcher.MatchResult.Exact(
                new CatalogMatch(
                    "sys",
                    "group",
                    "spec",
                    "op-create-task",
                    "Salesforce WFM",
                    "rest",
                    "POST",
                    "/sobjects/Task",
                    "createTask",
                    "catalog-read:sys/spec/op-create-task")));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(draftRef, approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    List<ArtifactCandidate> hints =
        completed.outcome().candidates().stream()
            .filter(c -> c.kind() == CompilationArtifacts.Kind.CATALOG_BINDING_HINT)
            .toList();
    assertEquals(1, hints.size());
    CatalogBindingHint hint = (CatalogBindingHint) hints.get(0).payload();
    assertEquals("create-salesforce-task", hint.interactionId());
    assertEquals("op-create-task", hint.integrationOperationId());
    RequirementDraft updated =
        (RequirementDraft)
            completed.outcome().candidates().stream()
                .filter(c -> c.kind() == CompilationArtifacts.Kind.REQUIREMENT_DRAFT)
                .findFirst()
                .orElseThrow()
                .payload();
    assertTrue(updated.readyForPlan());
    assertEquals(1, updated.catalogBindings().size());
  }

  @Test
  void bindsFlowInteractionsByImportedSystemIdsWhenParticipantNamesDiffer() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    RequirementDraftStore draftStore = mock(RequirementDraftStore.class);
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, matcher, draftStore);
    RequirementDraft draft =
        draft()
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "on-task-start", Direction.INBOUND, "Caller", "POST /tasks", ""),
                        new Interaction(
                            "salesforce-create-task",
                            Direction.OUTBOUND,
                            "Salesforce WFM – Auth & Task API",
                            "createTask",
                            ""),
                        new Interaction(
                            "wfms-task-result",
                            Direction.OUTBOUND,
                            "WFMS Create Work Order",
                            "onTaskResult",
                            "")),
                    List.of(
                        new Transition("on-task-start", "salesforce-create-task"),
                        new Transition("salesforce-create-task", "wfms-task-result"))));
    CompilationArtifacts.Reference draftRef = requirementDraftRef();
    stubRequirementDraft(artifactStore, draftRef, draft);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/salesforce-wfm.yaml", "uploads/wfms.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenAnswer(
            invocation -> {
              UploadedSpecAttachment attachment = invocation.getArgument(1);
              if (attachment.s3Key().contains("salesforce")) {
                return Uni.createFrom()
                    .item(
                        new UploadedSpecImportOutcome(
                            "sf-key", "sys-sf", "group-sf", "spec-sf", false));
              }
              return Uni.createFrom()
                  .item(
                      new UploadedSpecImportOutcome(
                          "wfms-key", "sys-wfms", "group-wfms", "spec-wfms", false));
            });
    when(matcher.matchImported(
            eq("service-call"), any(), any(), any(), any(), eq("conv-1")))
        .thenAnswer(
            invocation -> {
              String systemId = invocation.getArgument(1);
              String query = invocation.getArgument(4);
              if ("sys-sf".equals(systemId) && "createTask".equals(query)) {
                return new CatalogBindingMatcher.MatchResult.Exact(
                    new CatalogMatch(
                        "sys-sf",
                        "group-sf",
                        "spec-sf",
                        "op-create-task",
                        "Salesforce WFM Auth Task API",
                        "http",
                        "POST",
                        "/sobjects/Task",
                        "createTask",
                        "catalog-read:sys-sf/spec-sf/op-create-task"));
              }
              if ("sys-wfms".equals(systemId) && "onTaskResult".equals(query)) {
                return new CatalogBindingMatcher.MatchResult.Exact(
                    new CatalogMatch(
                        "sys-wfms",
                        "group-wfms",
                        "spec-wfms",
                        "op-task-result",
                        "WFMS Create Work Order",
                        "rest",
                        "POST",
                        "/task-result",
                        "onTaskResult",
                        "catalog-read:sys-wfms/spec-wfms/op-task-result"));
              }
              return new CatalogBindingMatcher.MatchResult.None();
            });
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(draftRef, approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    RequirementDraft updated =
        (RequirementDraft)
            completed.outcome().candidates().stream()
                .filter(c -> c.kind() == CompilationArtifacts.Kind.REQUIREMENT_DRAFT)
                .findFirst()
                .orElseThrow()
                .payload();
    assertTrue(updated.readyForPlan());
    assertEquals(2, updated.catalogBindings().size());
    verify(matcher, never()).match(any(), any(), any(), any());
    verify(matcher)
        .matchImported(
            eq("service-call"),
            eq("sys-sf"),
            eq("group-sf"),
            eq("spec-sf"),
            eq("createTask"),
            eq("conv-1"));
    verify(matcher)
        .matchImported(
            eq("service-call"),
            eq("sys-wfms"),
            eq("group-wfms"),
            eq("spec-wfms"),
            eq("onTaskResult"),
            eq("conv-1"));
  }

  @Test
  void emitsCatalogBindingHintsForTwoUploadedSpecs() {
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);
    ConversationService conversationService = mock(ConversationService.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, mock(S3Service.class));
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    RequirementDraftStore draftStore = mock(RequirementDraftStore.class);
    AutoUploadedSpecImportCapability capability =
        new AutoUploadedSpecImportCapability(
            gateway, conversationService, artifactStore, handler, matcher, draftStore);

    RequirementFact openApiFact =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "stub-openapi",
            "Uploaded OPENAPI spec Stub OpenAPI Service operation stubOperation path POST /stub/path");
    RequirementFact asyncFact =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "stub-asyncapi",
            "Uploaded ASYNCAPI spec Stub AsyncAPI Service operation stubAsyncOperation channel stub-channel");
    RequirementDraft draft = draft().withFacts(List.of(openApiFact, asyncFact));

    CompilationArtifacts.Reference draftRef = requirementDraftRef();
    stubRequirementDraft(artifactStore, draftRef, draft);
    when(conversationService.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/stub-openapi.yaml", "uploads/stub-asyncapi.yaml"));
    when(gateway.importUploadedSpec(
            eq("conv-1"), any(UploadedSpecAttachment.class), eq("INTERNAL")))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    when(matcher.matchImported(
            eq("service-call"), eq("sys"), eq("group"), eq("spec"), any(), eq("conv-1")))
        .thenAnswer(
            invocation -> {
              String q = invocation.getArgument(4);
              if (q != null && q.contains("POST /stub/path")) {
                return new CatalogBindingMatcher.MatchResult.Exact(
                    new CatalogMatch(
                        "sys",
                        "group",
                        "spec",
                        "op-stub-operation",
                        "Stub OpenAPI Service",
                        "rest",
                        "POST",
                        "/stub/path",
                        "stubOperation",
                        "catalog-read:sys/spec/op-stub-operation"));
              }
              if (q != null && q.contains("stub-channel")) {
                return new CatalogBindingMatcher.MatchResult.Exact(
                    new CatalogMatch(
                        "sys",
                        "group",
                        "spec",
                        "op-stub-async-operation",
                        "Stub AsyncAPI Service",
                        "kafka",
                        "SUBSCRIBE",
                        "stub-channel",
                        "stubAsyncOperation",
                        "catalog-read:sys/spec/op-stub-async-operation"));
              }
              return new CatalogBindingMatcher.MatchResult.None();
            });
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed =
        run(capability, draft, List.of(draftRef, approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    List<ArtifactCandidate> hints =
        completed.outcome().candidates().stream()
            .filter(c -> c.kind() == CompilationArtifacts.Kind.CATALOG_BINDING_HINT)
            .toList();
    assertEquals(2, hints.size(), "expected one hint per uploaded spec fact");
    assertEquals(
        "op-stub-operation",
        ((CatalogBindingHint) hints.get(0).payload()).integrationOperationId());
    assertEquals(
        "op-stub-async-operation",
        ((CatalogBindingHint) hints.get(1).payload()).integrationOperationId());
  }

  private static CapabilitySignal.Completed run(
      AutoUploadedSpecImportCapability capability,
      RequirementDraft draft,
      List<CompilationArtifacts.Reference> inputRefs) {
    StageExecutionContext context =
        new StageExecutionContext(
            "run-1",
            "conv-1",
            "auto-uploaded-spec-import",
            "exec-1",
            "attempt-1",
            null,
            null,
            inputRefs,
            Map.of("approvedDraft", draft));
    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();
    return signals.stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static void stubApprovedRecord(
      ProductPipelineArtifactStore artifactStore,
      CompilationArtifacts.Reference approvalRef,
      String hash) {
    stubApprovedRecord(artifactStore, approvalRef, hash, List.of());
  }

  private static void stubApprovedRecord(
      ProductPipelineArtifactStore artifactStore,
      CompilationArtifacts.Reference approvalRef,
      String hash,
      List<String> attachmentKeys) {
    CompilationArtifacts.Revision revision = mock(CompilationArtifacts.Revision.class);
    when(artifactStore.get("run-1", approvalRef)).thenReturn(Optional.of(revision));
    when(artifactStore.payload(revision, ApprovalRecordV2.class))
        .thenReturn(
            new ApprovalRecordV2(
                new CompilationArtifacts.Reference(
                    CompilationArtifacts.Kind.APPROVAL_RECORD,
                    UploadedSpecsApprovalHandler.ARTIFACT_TYPE + ":" + hash,
                    hash),
                hash,
                List.of(),
                "user",
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                attachmentKeys));
  }

  private static CompilationArtifacts.Reference approvalRef() {
    return new CompilationArtifacts.Reference(
        CompilationArtifacts.Kind.APPROVAL_RECORD, "approval-1", "hash");
  }

  private static RequirementDraft draft() {
    return new RequirementDraft(
        true, "Build a chain", DraftDecision.READY_FOR_PLAN, List.of(), "brainstorming", "1");
  }

  private static RequirementDraft omWfmDraft() {
    return draft()
        .withFlow(
            new RequirementFlow(
                List.of(
                    new Interaction(
                        "on-task-start", Direction.INBOUND, "Caller", "POST /tasks", ""),
                    new Interaction(
                        "create-salesforce-task",
                        Direction.OUTBOUND,
                        "Salesforce WFM",
                        "createTask",
                        ""),
                    new Interaction(
                        "return-task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
                List.of(
                    new Transition("on-task-start", "create-salesforce-task"),
                    new Transition("create-salesforce-task", "return-task-result"))));
  }

  private static CatalogBindingHint restHint(String interactionId) {
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        interactionId,
        interactionId,
        "POST /ops/" + interactionId,
        "sys",
        "group",
        "spec",
        "op-" + interactionId,
        "rest",
        "POST",
        "/ops/" + interactionId,
        "catalog",
        Instant.EPOCH,
        "test");
  }

  private static RequirementDraft draftWithBinding() {
    RequirementFact call =
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "",
            "call stubOperation",
            "Stub OpenAPI Service",
            "stubOperation",
            "",
            "",
            "",
            "call-1");
    return draft()
        .withFacts(List.of(call))
        .withBoundServiceCall(
            "call-1",
            new CatalogBindingHint(
                "2",
                "call-1",
                "call-1",
                "stubOperation",
                "system-1",
                "group-1",
                "spec-1",
                "op-1",
                null,
                null,
                null,
                "catalog",
                Instant.EPOCH,
                "test"));
  }

  private static CompilationArtifacts.Reference requirementDraftRef() {
    return new CompilationArtifacts.Reference(
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT, "draft-1", "draft-hash");
  }

  private static void stubRequirementDraft(
      ProductPipelineArtifactStore artifactStore,
      CompilationArtifacts.Reference draftRef,
      RequirementDraft draft) {
    CompilationArtifacts.Revision revision = mock(CompilationArtifacts.Revision.class);
    when(revision.schemaVersion()).thenReturn("2");
    when(artifactStore.get("run-1", draftRef)).thenReturn(Optional.of(revision));
    when(artifactStore.payload(revision, RequirementDraft.class)).thenReturn(draft);
  }
}
