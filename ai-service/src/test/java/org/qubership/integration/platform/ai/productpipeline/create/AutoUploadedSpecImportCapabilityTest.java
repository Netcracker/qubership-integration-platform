package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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
    verify(gateway, never()).importUploadedSpec(any(), any());
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
    verify(gateway, never()).importUploadedSpec(any(), any());
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
    when(gateway.importUploadedSpec(eq("conv-1"), any(UploadedSpecAttachment.class)))
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
            eq("conv-1"), eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")));
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/notifications-async.yaml", "notifications-async.yaml")));
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
    when(gateway.importUploadedSpec(eq("conv-1"), any(UploadedSpecAttachment.class)))
        .thenReturn(Uni.createFrom().failure(new RuntimeException("import failed")));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(approvalRef));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.outcome().outcomeClass());
    assertEquals(0, completed.outcome().candidates().size());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"), eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")));
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"),
            eq(new UploadedSpecAttachment("uploads/notifications-async.yaml", "notifications-async.yaml")));
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
    when(gateway.importUploadedSpec(eq("conv-1"), any(UploadedSpecAttachment.class)))
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
            eq("conv-1"), eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")));
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
    when(gateway.importUploadedSpec(eq("conv-1"), any(UploadedSpecAttachment.class)))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    CompilationArtifacts.Reference approvalRef = approvalRef();
    stubApprovedRecord(artifactStore, approvalRef, handler.attachmentHash("conv-1"));

    CapabilitySignal.Completed completed = run(capability, draft, List.of(approvalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"), eq(new UploadedSpecAttachment("sessions/conv/a.json", "a.json")));
    verify(gateway)
        .importUploadedSpec(
            eq("conv-1"), eq(new UploadedSpecAttachment("sessions/conv/b.json", "b.json")));
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
    verify(gateway, never()).importUploadedSpec(any(), any());
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
    verify(gateway, never()).importUploadedSpec(any(), any());
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
    verify(gateway, never()).importUploadedSpec(any(), any());
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
    when(gateway.importUploadedSpec(eq("conv-1"), any(UploadedSpecAttachment.class)))
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
            eq("conv-1"), eq(new UploadedSpecAttachment("uploads/orders-api.yaml", "orders-api.yaml")));
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
    when(gateway.importUploadedSpec(eq("conv-1"), any(UploadedSpecAttachment.class)))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    when(matcher.match(any(), nullable(String.class), nullable(String.class), eq("conv-1")))
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
    verify(matcher).match(eq("service-call"), any(), any(), eq("conv-1"));
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
    when(gateway.importUploadedSpec(eq("conv-1"), any(UploadedSpecAttachment.class)))
        .thenReturn(
            Uni.createFrom()
                .item(new UploadedSpecImportOutcome("key", "sys", "group", "spec", false)));
    when(matcher.match(any(), nullable(String.class), nullable(String.class), eq("conv-1")))
        .thenAnswer(
            invocation -> {
              String q = invocation.getArgument(2);
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
