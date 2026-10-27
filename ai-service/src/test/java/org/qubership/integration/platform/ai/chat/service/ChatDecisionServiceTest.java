package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chain.folder.ChainListAction;
import org.qubership.integration.platform.ai.chain.folder.ChainListCommand;
import org.qubership.integration.platform.ai.chain.folder.ChainTable;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchStore;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeployStore;
import org.qubership.integration.platform.ai.chain.deploy.MaasTopicsCreatedStore;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DomainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainLoggingPropertiesSetDto;
import org.qubership.integration.platform.ai.llm.scenario.DeployChainScenario;
import org.qubership.integration.platform.ai.llm.scenario.ChainListScenario;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.chain.patch.ProposedChainPatch;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.llm.agent.ChainListIntentAgent;
import org.qubership.integration.platform.ai.llm.agent.ChainListReplyAgent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.RestartCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.runtime.HaltRecoveryGuard;
import org.qubership.integration.platform.ai.productpipeline.runtime.RestartCheckpoint;
import org.qubership.integration.platform.ai.storage.S3Service;

class ChatDecisionServiceTest {

  @Test
  void requirementClarificationDecisionUsesThePersistedOfferId() {
    long revision = 6L;
    var clarify = new CreateChainPendingAction.Clarify(
        "Which chain-trigger element id should this chain call use?",
        List.of(),
        PipelineGates.STAGE_CLARIFICATION,
        "",
        null,
        "run-offer",
        "requirement-discovery",
        "offer-chain-target");
    var facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-offer")).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        "conv-offer", "run-offer", CreateChainExecutionStatus.INPUT_REQUIRED, revision, clarify, "")));
    var decision = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .openDecision("conv-offer")
        .orElseThrow();

    assertEquals("offer-chain-target", decision.id());
  }

  @Test
  void blankClarificationOfferKeepsTheRevisionDecisionId() {
    long revision = 4L;
    var clarify = new CreateChainPendingAction.Clarify(
        "Show the Integration Design Specification (IDS) for review?",
        List.of(),
        PipelineGates.IDS_PATH_CHOICE);
    var facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-ids")).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        "conv-ids", "run-ids", CreateChainExecutionStatus.INPUT_REQUIRED, revision, clarify, "")));
    var decision = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .openDecision("conv-ids")
        .orElseThrow();

    assertEquals("clarify:" + revision, decision.id());
  }

  @Test
  void specializedGatesDoNotConsumeTheRequirementOffer() {
    long revision = 8L;
    for (String gate : List.of(
        PipelineGates.TARGET_FOLDER_CHOICE,
        PipelineGates.REQUIREMENT_RECOVERY_CLARIFICATION,
        PipelineGates.IDS_PATH_CHOICE)) {
      var clarify = new CreateChainPendingAction.Clarify(
          "Choose one.",
          List.of(),
          gate,
          "",
          null,
          "run-specialized",
          "requirement-discovery",
          "offer-generic");
      var facade = mock(CreateChainApplicationFacade.class);
      when(facade.snapshot("conv-" + gate)).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
          "conv-" + gate, "run-specialized", CreateChainExecutionStatus.INPUT_REQUIRED, revision, clarify, "")));
      var decision = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
          .openDecision("conv-" + gate).orElseThrow();
      assertEquals("clarify:" + revision, decision.id(), "openDecision(" + gate + ")");
      assertNotEquals("offer-generic", decision.id(), "openDecision(" + gate + ")");
    }
  }

  @Test
  void runtimeBindingDoesNotConsumeTheRequirementOffer() {
    long revision = 7L;
    String conversationId = "conv-version";
    var interaction = new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction(
        "task", org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.OUTBOUND,
        "Salesforce", "createTask", "Create a task");
    var match = new org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch(
        "system", "group", "spec", "operation", "Salesforce", "http", "POST", "/tasks", "createTask",
        "evidence", "2.0.0");
    var pending = new org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection.Pending(
        new org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection.Proposal(
            interaction, match, false), "version-hash");
    var versions = mock(org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection.class);
    when(versions.pending(conversationId)).thenReturn(Optional.of(pending));
    when(versions.decision(eq(pending), eq(revision), any())).thenReturn(new ChatEvent.Decision(
        org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection.ARTIFACT_TYPE + ":version-hash",
        "approve", "Use version 2.0.0?",
        org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection.ARTIFACT_TYPE,
        "version-hash", revision, null, List.of(), List.of()));
    var clarify = new CreateChainPendingAction.Clarify(
        "Which operation version?",
        List.of(),
        PipelineGates.STAGE_CLARIFICATION,
        "",
        null,
        "run-version",
        "requirement-discovery",
        "offer-generic");
    var facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot(conversationId)).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        conversationId, "run-version", CreateChainExecutionStatus.INPUT_REQUIRED, revision, clarify, "")));
    var service = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());
    service.versionSelections = versions;
    var decision = service.openDecision(conversationId).orElseThrow();
    assertEquals("catalog-operation-version:version-hash", decision.id(), "openDecision(runtime binding)");
    assertNotEquals("offer-generic", decision.id(), "openDecision(runtime binding)");
  }

  @Test
  void policyCardUsesTheActiveOfferAndKeepsTheArtifactHashCheck() {
    String conversationId = "conv-policy";
    long revision = 4L;
    var draftRef = new CompilationArtifacts.Reference(
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT, "draft-1", "hash-1");
    var offer = new ClarificationTransition.Offer(
        "offer-policy", draftRef, "policy-choice", revision, "source-gen");
    var clarify = new CreateChainPendingAction.Clarify(
        "Keep NONE or use recommended RBAC?",
        List.of(),
        PipelineGates.STAGE_CLARIFICATION,
        "",
        null,
        "run-policy",
        "requirement-discovery",
        offer.offerId());
    var facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot(conversationId)).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        conversationId, "run-policy", CreateChainExecutionStatus.INPUT_REQUIRED, revision, clarify, "")));
    when(facade.activeClarificationOffer(conversationId)).thenReturn(Optional.of(offer));
    var choices = mock(PolicyChoiceSelection.class);
    var pending = new PolicyChoiceSelection.Pending(
        draftRef, draftRef, "source-gen", "turn-1", null, null, revision, "policy-hash");
    when(choices.pending(conversationId, revision, draftRef, "policy-choice")).thenReturn(Optional.of(pending));
    when(choices.decision(pending, revision)).thenReturn(new ChatEvent.Decision(
        PolicyChoiceSelection.ARTIFACT_TYPE + ":policy-hash",
        "clarify",
        "Keep NONE or use recommended RBAC?",
        PolicyChoiceSelection.ARTIFACT_TYPE,
        "policy-hash",
        revision,
        null,
        List.of(),
        List.of(ChatEvent.KEEP_REQUESTED_ACTION, ChatEvent.USE_RECOMMENDED_ACTION)));
    var service = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());
    service.policyChoices = choices;

    var opened = service.openDecision(conversationId).orElseThrow();

    verify(choices).pending(conversationId, revision, draftRef, "policy-choice");
    assertEquals(PolicyChoiceSelection.ARTIFACT_TYPE + ":policy-hash", opened.id(), "openDecision(policy)");
    assertNotEquals(offer.offerId(), opened.id(), "openDecision(policy)");
    assertEquals("policy-hash", opened.artifactHash());

    var mismatch = command(
        ChatEvent.USE_RECOMMENDED_ACTION, PolicyChoiceSelection.ARTIFACT_TYPE, "other-hash", "");
    mismatch.setRevision(revision);
    service.apply(conversationId, mismatch).collect().asList().await().indefinitely();
    verify(choices, never()).confirm(any(), any(), anyLong());

    var match = command(
        ChatEvent.USE_RECOMMENDED_ACTION, PolicyChoiceSelection.ARTIFACT_TYPE, "policy-hash", "");
    match.setRevision(revision);
    when(choices.confirm(eq(conversationId), eq(match), eq(revision))).thenReturn(Optional.empty());
    service.apply(conversationId, match).collect().asList().await().indefinitely();
    verify(choices).confirm(eq(conversationId), eq(match), eq(revision));
  }

  @Test
  void standaloneImportApprovalRemainsAvailableWithoutPrivateSkillsOrCreateState() {
    var facade = mock(CreateChainApplicationFacade.class);
    var service = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());
    service.rockySkills = mock(org.qubership.integration.platform.ai.skills.RockySkills.class);
    service.specificationImports = mock(
        org.qubership.integration.platform.ai.productpipeline.importspec.SpecificationImportPipeline.class);
    service.importScenario = mock(org.qubership.integration.platform.ai.llm.scenario.ImportSpecificationScenario.class);
    when(service.specificationImports.standaloneRequest("chat")).thenReturn(Optional.of(
        new org.qubership.integration.platform.ai.productpipeline.importspec.SpecificationImportPipeline.StandaloneRequest(
            "attachment", "Import", false)));
    var command = new ChatDecisionCommand();
    command.setAction(ChatEvent.APPROVE_ACTION);
    command.setArtifactType(UploadedSpecsApprovalHandler.ARTIFACT_TYPE);
    when(service.importScenario.approve("chat", command)).thenReturn(Multi.createFrom().item(ChatEvent.token("Imported")));

    assertEquals(List.of(ChatEvent.token("Imported")),
        service.apply("chat", command).collect().asList().await().indefinitely());
    verify(service.importScenario).approve("chat", command);
    org.mockito.Mockito.verifyNoInteractions(facade);
  }

  @ParameterizedTest
  @ValueSource(strings = {"approve", "approve-and-create", "create-chain", "request-changes", "restart-from-beginning"})
  void savedPrivateCommandsAreRefusedBeforeReadingCreateState(String action) {
    var facade = mock(CreateChainApplicationFacade.class);
    var service = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());
    service.rockySkills = mock(org.qubership.integration.platform.ai.skills.RockySkills.class);
    var command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactType("old-private-artifact");

    assertEquals(List.of(ChatEvent.token(org.qubership.integration.platform.ai.skills.RockySkills.UNAVAILABLE)),
        service.apply("chat", command).collect().asList().await().indefinitely());
    assertTrue(service.openDecision("chat").isEmpty());
    org.mockito.Mockito.verifyNoInteractions(facade);
  }

  @ParameterizedTest
  @CsvSource({"stage-clarification,false", "stage-clarification,true",
      "recovery-retry-technical,false", "recovery-retry-technical,true"})
  void explicitGateSurvivesAnEmptyPromptAndDecisionReload(String gateId, boolean storedQuestion) {
    String conversationId = "conv-empty-gate";
    String runId = conversationId + "-create-chain-2";
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var blobs = new InMemoryArtifactBlobStore();
    var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    var runs = new ProductPipelineRunStore(blobs, mapper, clock);
    runs.create(new RunSnapshot(runId, conversationId, 3L, RunStatus.RUNNING,
        "requirement-discovery", List.of(), null, "flow-1"));
    runs.commit(3L, new org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit(
        runId, 3L, RunStatus.WAITING_FOR_INPUT, "requirement-discovery", List.of(),
        new org.qubership.integration.platform.ai.productpipeline.store.StageAttempt("attempt", "requirement-discovery", 4L,
            org.qubership.integration.platform.ai.productpipeline.store.StageStatus.WAITING_FOR_INPUT,
            Instant.EPOCH, Instant.EPOCH, List.of(), null),
        new RunTransition(3L, 4L, RunStatus.RUNNING, RunStatus.WAITING_FOR_INPUT,
            "requirement-discovery", Instant.EPOCH, PipelineGates.tag(gateId, ""))));
    ChatEvent.Decision first = null;
    for (int reload = 0; reload < 2; reload++) {
      var restoredRuns = new ProductPipelineRunStore(blobs, mapper, clock);
      var drafts = new RequirementDraftStore();
      if (storedQuestion) {
        drafts.put(conversationId, new RequirementDraft(false, "Incomplete requirements",
            org.qubership.integration.platform.ai.plan.DraftDecision.NEEDS_INPUT,
            List.of("Which catalog operation should deliver the result?"), "brainstorming", "1"));
      }
      var facade = new CreateChainApplicationFacade(mock(CreateRunSelectionService.class),
          mock(CreateRunBindingStore.class), mock(CreateChainOrchestrator.class), restoredRuns,
          mock(ProductPipelineProfileCatalog.class), inMemoryArtifactStore(), drafts);
      var pending = assertInstanceOf(CreateChainPendingAction.Clarify.class,
          facade.snapshot(conversationId).orElseThrow().pendingAction());
      assertEquals(gateId, pending.gateId());
      assertFalse(ChatEvent.isSilentClarification(pending));
      assertEquals(storedQuestion ? 1 : 0, pending.missingEvidence().size());
      var decision = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
          .openDecision(conversationId).orElseThrow();
      assertFalse(decision.question().isBlank());
      if (PipelineGates.RECOVERY_RETRY_TECHNICAL.equals(gateId)) {
        assertTrue(decision.actions().contains(ChatEvent.RETRY_CREATION_ACTION));
        assertEquals(runId, pending.runId());
      }
      if (first != null) assertEquals(first, decision);
      first = decision;
    }
  }


  @Test
  void activeCreateOwnsImportDecisionsAfterAnEarlierStandaloneRequest() {
    var facade = mock(CreateChainApplicationFacade.class);
    var snapshot = mock(CreateChainExecutionSnapshot.class);
    when(snapshot.finished()).thenReturn(false);
    when(facade.snapshot("chat")).thenReturn(Optional.of(snapshot));
    var drafts = mock(RequirementDraftStore.class);
    var service = new ChatDecisionService(facade, mock(ApprovalQuestionStore.class), drafts);
    service.specificationImports = mock(org.qubership.integration.platform.ai.productpipeline.importspec.SpecificationImportPipeline.class);
    when(service.specificationImports.standaloneRequest("chat")).thenReturn(Optional.of(
        new org.qubership.integration.platform.ai.productpipeline.importspec.SpecificationImportPipeline.StandaloneRequest("old", "Import", false)));
    var command = new ChatDecisionCommand();
    command.setAction(ChatEvent.IMPORT_INTERNAL_ACTION);
    command.setArtifactType(UploadedSpecsApprovalHandler.ARTIFACT_TYPE);

    service.rememberImportChoice("chat", command);

    verify(drafts).rememberPreferredSystemType("chat", "INTERNAL");
  }

  @ParameterizedTest
  @ValueSource(strings = {ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION, ChatEvent.CANCEL_CHAIN_LIST_CHANGE_ACTION})
  void openDecisionKeepsTheChainListCardUntilItIsAnswered(String action) {
    String conversationId = "conv-chain-list";
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    ChainTable table = mock(ChainTable.class);
    when(table.find(List.of())).thenReturn(
        List.of(new ChainTable.Row("chain-a", "Orders", "", null, "", null, "")));
    ChainListIntentAgent agent = mock(ChainListIntentAgent.class);
    when(agent.resolve(any(), any(), any(), any())).thenReturn(
        new ChainListCommand(ChainListAction.DELETE_CHAINS, null, null, null,
            List.of(), List.of("Orders"), List.of(), null));
    ChainListReplyAgent replies = mock(ChainListReplyAgent.class);
    when(replies.reply(any(), any(), any())).thenReturn("Confirm deletion of Orders.");
    ChainContextExtractor context = mock(ChainContextExtractor.class);
    when(context.resolveChainId(any(), anyString())).thenReturn(Optional.empty());
    ChainListScenario scenario = new ChainListScenario(agent, replies, mock(CatalogFolders.class),
        table, catalog, mock(ConversationService.class), context);
    ChatDecisionService service = new ChatDecisionService(
        mock(CreateChainApplicationFacade.class), questionStore(), new RequirementDraftStore());
    service.chainListScenario = scenario;

    ChatEvent.Decision emitted = assertInstanceOf(ChatEvent.Decision.class,
        scenario.handle(new ChatRequest(), conversationId, ScenarioType.MANAGE_CHAIN_LIST)
            .collect().asList().await().indefinitely().getFirst());

    assertEquals(emitted, service.openDecision(conversationId).orElse(null));
    assertEquals(emitted, service.openDecision(conversationId).orElse(null));
    assertTrue(service.openDecision("other-conversation").isEmpty());
    verify(replies, times(1)).reply(any(), any(), any());
    verify(catalog, never()).deleteChain(anyString());

    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactHash(emitted.artifactHash());
    ChatRequest answer = new ChatRequest();
    answer.setDecision(command);
    scenario.handle(answer, conversationId, ScenarioType.MANAGE_CHAIN_LIST)
        .collect().asList().await().indefinitely();

    assertTrue(service.openDecision(conversationId).isEmpty());
    verify(catalog, times(ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION.equals(action) ? 1 : 0))
        .deleteChain("chain-a");
  }

  @Test
  void openDecisionKeepsTheAutomaticLoggingQuestionAfterTheDeployStream() {
    String conversationId = "conv-deploy-logging";
    PendingRedeployStore pending = new PendingRedeployStore();
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listDomains()).thenReturn(List.of(new DomainDto("default", "")));
    when(catalog.listDeployments("chain-1")).thenReturn(List.of());
    when(catalog.getLoggingProperties("chain-1"))
        .thenReturn(new ChainLoggingPropertiesSetDto(null, null, null));
    ChainContextExtractor context = mock(ChainContextExtractor.class);
    when(context.resolveChainId(any(), org.mockito.ArgumentMatchers.eq(conversationId)))
        .thenReturn(Optional.of("chain-1"));
    DeployChainScenario scenario = new DeployChainScenario(
        context, catalog, pending, new MaasTopicsCreatedStore(), new KnownFailureMapper(),
        new PinnedFailureStore(), Optional.empty(), Optional.empty());
    ChatDecisionService decisions = new ChatDecisionService(
        mock(CreateChainApplicationFacade.class), questionStore(), new RequirementDraftStore());
    decisions.pendingRedeployStore = pending;
    ChatRequest request = new ChatRequest();
    request.setMessage("Deploy this chain");

    ChatEvent.Decision emitted = assertInstanceOf(ChatEvent.Decision.class,
        scenario.handle(request, conversationId, ScenarioType.DEPLOY_CHAIN)
            .collect().asList().await().indefinitely().get(0));

    assertEquals(ChatEvent.SESSION_LOGGING_ACTIONS, emitted.actions());
    assertTrue(emitted.question().contains("Which session logging level"));
    assertEquals(emitted, decisions.openDecision(conversationId).orElse(null));
    verify(catalog, never()).createSnapshot(any());
    verify(catalog, never()).createDeployment(any(), any());
    verify(catalog, never()).updateLoggingProperties(any(), any());
  }

  @ParameterizedTest
  @CsvSource(
      textBlock = """
          '  Start   over!!!  ', restart-from-beginning
          Restart from the beginning, restart-from-beginning
          Restart from beginning., restart-from-beginning
          Restart from approved requirements, restart-from-approved-requirements
          Go back to approved requirements?, restart-from-approved-requirements
          Restart from approved plan, restart-from-approved-plan
          Go back to approved plan!, restart-from-approved-plan
          """)
  void supportedRestartMessagesMapToTypedActions(String message, String expectedAction) {
    CreateChainApplicationFacade facade = restartFacade();
    ChatDecisionService service =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());

    ChatDecisionCommand command =
        service.restartCommandForMessage("conv-restart", message).orElseThrow();

    assertEquals(expectedAction, command.getAction());
    assertEquals(11L, command.getRevision());
  }

  @ParameterizedTest
  @CsvSource({
    "please start over",
    "restart the process",
    "go back",
    "restart from an approved plan",
    "start over and keep the plan"
  })
  void ambiguousRestartMessagesRemainOrdinaryInput(String message) {
    ChatDecisionService service =
        new ChatDecisionService(
            restartFacade(), questionStore(), new RequirementDraftStore());

    assertTrue(service.restartCommandForMessage("conv-restart", message).isEmpty());
  }

  @Test
  void exactRestartMessageUsesTheTypedCheckpointCommand() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-restart"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-restart",
                    "run-parent",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    11L,
                    new CreateChainPendingAction.Clarify(
                        "Creation is paused.",
                        List.of(),
                        PipelineGates.RECOVERY_RETRY_TECHNICAL,
                        "timeout",
                        null,
                        "run-parent",
                        "design-execution"),
                    "")));
    when(facade.restartCheckpoints("conv-restart"))
        .thenReturn(
            List.of(RestartCheckpoint.BEGINNING, RestartCheckpoint.APPROVED_REQUIREMENTS));
    ChatDecisionService service =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());

    ChatDecisionCommand command =
        service
            .restartCommandForMessage(
                "conv-restart", "Go back to approved requirements.")
            .orElseThrow();

    assertEquals(ChatEvent.RESTART_FROM_APPROVED_REQUIREMENTS_ACTION, command.getAction());
    assertEquals(11L, command.getRevision());
  }

  @Test
  void restartMessageCannotSelectAnUnavailableCheckpoint() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-restart"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-restart",
                    "run-parent",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    11L,
                    new CreateChainPendingAction.Clarify(
                        "Creation is paused.",
                        List.of(),
                        PipelineGates.RECOVERY_RETRY_TECHNICAL,
                        "timeout",
                        null,
                        "run-parent",
                        "design-execution"),
                    "")));
    when(facade.restartCheckpoints("conv-restart"))
        .thenReturn(List.of(RestartCheckpoint.BEGINNING));
    ChatDecisionService service =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());

    assertTrue(
        service
            .restartCommandForMessage("conv-restart", "Restart from approved plan")
            .isEmpty());
  }

  @Test
  void restartActionDispatchesTheBoundCheckpointCommand() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    CreateChainExecutionSnapshot snapshot =
        new CreateChainExecutionSnapshot(
            "conv-restart",
            "run-parent",
            CreateChainExecutionStatus.INPUT_REQUIRED,
            11L,
            new CreateChainPendingAction.Clarify(
                "Creation is paused.",
                List.of(),
                PipelineGates.RECOVERY_RETRY_TECHNICAL,
                "timeout",
                null,
                "run-parent",
                "design-execution"),
            "");
    when(facade.snapshot("conv-restart")).thenReturn(Optional.of(snapshot));
    when(facade.restartCheckpoints("conv-restart"))
        .thenReturn(
            List.of(RestartCheckpoint.BEGINNING, RestartCheckpoint.APPROVED_REQUIREMENTS));
    when(facade.restart(any())).thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.RESTART_FROM_APPROVED_REQUIREMENTS_ACTION);
    command.setRevision(11L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-restart", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<RestartCreateChainCommand> restart =
        ArgumentCaptor.forClass(RestartCreateChainCommand.class);
    verify(facade).restart(restart.capture());
    assertEquals(RestartCheckpoint.APPROVED_REQUIREMENTS, restart.getValue().checkpoint());
    assertEquals(11L, restart.getValue().expectedRunRevision());
  }

  @ParameterizedTest
  @CsvSource({
    "restart-from-beginning, BEGINNING",
    "restart-from-approved-requirements, APPROVED_REQUIREMENTS",
    "restart-from-approved-plan, APPROVED_PLAN"
  })
  void everyRestartActionDispatchesItsBoundCheckpoint(
      String action, RestartCheckpoint expectedCheckpoint) {
    CreateChainApplicationFacade facade = restartFacade();
    when(facade.restart(any())).thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(action);
    command.setRevision(11L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-restart", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<RestartCreateChainCommand> restart =
        ArgumentCaptor.forClass(RestartCreateChainCommand.class);
    verify(facade).restart(restart.capture());
    assertEquals(expectedCheckpoint, restart.getValue().checkpoint());
  }

  @Test
  void staleRestartActionReissuesTheCurrentGateWithoutRestarting() {
    CreateChainApplicationFacade facade = restartFacade();
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.RESTART_FROM_BEGINNING_ACTION);
    command.setRevision(10L);

    List<ChatEvent> events =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply("conv-restart", command)
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(1, events.size());
    assertInstanceOf(ChatEvent.Decision.class, events.get(0));
    verify(facade, never()).restart(any());
  }

  private static CreateChainApplicationFacade restartFacade() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-restart"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-restart",
                    "run-parent",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    11L,
                    new CreateChainPendingAction.Clarify(
                        "Creation is paused.",
                        List.of(),
                        PipelineGates.RECOVERY_RETRY_TECHNICAL,
                        "timeout",
                        null,
                        "run-parent",
                        "design-execution"),
                    "")));
    when(facade.restartCheckpoints("conv-restart"))
        .thenReturn(List.of(RestartCheckpoint.values()));
    return facade;
  }

  @Test
  void openDecisionProjectsAContextualRetryFromServerOwnedState() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-retry"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-retry",
                    "run-retry",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    7L,
                    new CreateChainPendingAction.Clarify(
                        "The provider temporarily limited requests.",
                        List.of(),
                        PipelineGates.RECOVERY_RETRY_TECHNICAL,
                        "rate_limit_exceeded",
                        2_000L,
                        "run-retry",
                        "design-execution"),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-retry")
            .orElseThrow();

    assertEquals(
        List.of(ChatEvent.RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        decision.actions());
    assertEquals("temporary-technical-failure", decision.recovery().category());
    assertEquals("The provider temporarily limited requests.", decision.recovery().summary());
    assertEquals("rate_limit_exceeded", decision.recovery().technicalDetails());
    assertEquals(2_000L, decision.recovery().retryDelayMs());
    assertEquals("run-retry", decision.recovery().runId());
    assertEquals("design-execution", decision.recovery().failedStageId());
  }

  @Test
  void semanticRetrySubmissionMapsToTheInternalRetryCommand() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    CreateChainPendingAction.Clarify pending =
        new CreateChainPendingAction.Clarify(
            "The provider temporarily limited requests.",
            List.of(),
            PipelineGates.RECOVERY_RETRY_TECHNICAL,
            "rate_limit_exceeded",
            null,
            "run-retry",
            "design-execution");
    when(facade.snapshot("conv-retry"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-retry",
                    "run-retry",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    7L,
                    pending,
                    "")));
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.RETRY_CREATION_ACTION);
    command.setRevision(7L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-retry", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(PipelineGates.RETRY_ACTION, input.getValue().clarificationText());
  }

  @Test
  void aHaltEmitsOneDecisionAfterRestartCheckpointsAreStored() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    CreateChainPendingAction.Clarify pending =
        new CreateChainPendingAction.Clarify(
            "The same problem came back.",
            List.of(),
            PipelineGates.RECOVERY_REPEATED,
            "multiple catalog binding hints",
            null,
            "run-repeated",
            "design-execution");
    when(facade.snapshot("conv-repeated"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-repeated",
                    "run-repeated",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    9L,
                    pending,
                    "")));
    when(facade.restartCheckpoints("conv-repeated"))
        .thenReturn(List.of(RestartCheckpoint.BEGINNING, RestartCheckpoint.APPROVED_PLAN));
    when(facade.continueWithInput(any()))
        .thenReturn(Multi.createFrom().item(new CreateChainEvent.Waiting(pending)));
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(PipelineGates.STOP_WITH_REPORT_ACTION);
    command.setRevision(9L);

    List<ChatEvent.Decision> decisions =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply("conv-repeated", command)
            .collect()
            .asList()
            .await()
            .indefinitely()
            .stream()
            .filter(ChatEvent.Decision.class::isInstance)
            .map(ChatEvent.Decision.class::cast)
            .toList();

    assertEquals(1, decisions.size());
    assertEquals(
        List.of(
            PipelineGates.STOP_WITH_REPORT_ACTION,
            ChatEvent.RESTART_FROM_BEGINNING_ACTION,
            ChatEvent.RESTART_FROM_APPROVED_PLAN_ACTION),
        decisions.getFirst().actions());
  }

  @Test
  void openDecisionProjectsAContextualBriefDefectFromServerOwnedState() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-brief"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-brief",
                    "run-brief",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    7L,
                    new CreateChainPendingAction.Clarify(
                        "The approved requirements need correction.",
                        List.of(),
                        PipelineGates.RECOVERY_REVISE_BRIEF,
                        "PLAN_BLOCKER: missing quartz",
                        null,
                        "run-brief",
                        "planning"),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-brief")
            .orElseThrow();

    assertEquals(
        List.of(ChatEvent.EDIT_REQUIREMENTS_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        decision.actions());
    assertEquals("requirement-brief-defect", decision.recovery().category());
    assertEquals("The approved requirements need correction.", decision.recovery().summary());
    assertEquals("PLAN_BLOCKER: missing quartz", decision.recovery().technicalDetails());
    assertEquals("run-brief", decision.recovery().runId());
    assertEquals("planning", decision.recovery().failedStageId());
    assertFalse(decision.actions().contains("planning"));
    assertFalse(decision.actions().contains("requirement-analysis"));
  }

  @Test
  void semanticEditRequirementsSubmissionMapsToTheInternalReviseCommand() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    CreateChainPendingAction.Clarify pending =
        new CreateChainPendingAction.Clarify(
            "The approved requirements need correction.",
            List.of(),
            PipelineGates.RECOVERY_REVISE_BRIEF,
            "PLAN_BLOCKER: missing quartz",
            null,
            "run-brief",
            "planning");
    when(facade.snapshot("conv-brief"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-brief",
                    "run-brief",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    7L,
                    pending,
                    "")));
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.EDIT_REQUIREMENTS_ACTION);
    command.setRevision(7L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-brief", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(PipelineGates.REVISE_ACTION, input.getValue().clarificationText());
  }

  @Test
  void openDecisionProjectsAContextualPlanDefectFromServerOwnedState() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-plan"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-plan",
                    "run-plan",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    7L,
                    new CreateChainPendingAction.Clarify(
                        "The plan is missing information required to create the chain.",
                        List.of(),
                        PipelineGates.RECOVERY_REBUILD_PLAN,
                        "PLAN_BLOCKER: invalid graph edge",
                        null,
                        "run-plan",
                        "design-execution"),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-plan")
            .orElseThrow();

    assertEquals(
        List.of(ChatEvent.REBUILD_PLAN_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION),
        decision.actions());
    assertEquals("plan-artifact-defect", decision.recovery().category());
    assertEquals(
        "The plan is missing information required to create the chain.",
        decision.recovery().summary());
    assertFalse(decision.actions().contains("design-planning"));
    assertFalse(decision.actions().contains("design-execution"));
  }

  @Test
  void semanticRebuildPlanSubmissionMapsToTheInternalReviseCommand() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-plan"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-plan",
                    "run-plan",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    7L,
                    new CreateChainPendingAction.Clarify(
                        "The plan is missing information required to create the chain.",
                        List.of(),
                        PipelineGates.RECOVERY_REBUILD_PLAN,
                        "PLAN_BLOCKER: invalid graph edge",
                        null,
                        "run-plan",
                        "design-execution"),
                    "")));
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.REBUILD_PLAN_ACTION);
    command.setRevision(7L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-plan", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(PipelineGates.REVISE_ACTION, input.getValue().clarificationText());
  }

  @Test
  void semanticRebuildDesignSubmissionMapsToTheInternalReviseCommand() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-design"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-design",
                    "run-design",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    8L,
                    new CreateChainPendingAction.Clarify(
                        "The semantic design cannot be used.",
                        List.of(),
                        PipelineGates.RECOVERY_REBUILD_DESIGN,
                        "CONTRACT_SHAPE: invalid topology",
                        null,
                        "run-design",
                        "design-input"),
                    "")));
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.REBUILD_DESIGN_ACTION);
    command.setRevision(8L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-design", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(PipelineGates.REVISE_ACTION, input.getValue().clarificationText());
  }

  @Test
  void openDecisionProjectsAContextualEnvironmentFailureFromServerOwnedState() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-env"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-env",
                    "run-env",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    7L,
                    new CreateChainPendingAction.Clarify(
                        "This region is not supported for chain creation.",
                        List.of(),
                        PipelineGates.RECOVERY_ENVIRONMENT,
                        "PKIX path building failed",
                        null,
                        "run-env",
                        "design-execution"),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-env")
            .orElseThrow();

    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), decision.actions());
    assertEquals("permanent-environment-failure", decision.recovery().category());
    assertEquals(
        "This region is not supported for chain creation.", decision.recovery().summary());
    assertFalse(decision.actions().contains(ChatEvent.RETRY_CREATION_ACTION));
    assertFalse(decision.actions().contains("design-execution"));
  }

  @Test
  void openDecisionProjectsAContextualInternalFailureFromServerOwnedState() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-internal-recovery"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-internal-recovery",
                    "run-internal",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    8L,
                    new CreateChainPendingAction.Clarify(
                        "A step inside the service broke. Repeating the same request will not help.",
                        List.of(),
                        PipelineGates.RECOVERY_INTERNAL,
                        "java.lang.IllegalStateException: catalog lookup broke (runId=run-internal)",
                        null,
                        "run-internal",
                        "design-execution"),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-internal-recovery")
            .orElseThrow();

    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), decision.actions());
    assertEquals("internal-service-failure", decision.recovery().category());
    assertEquals("Creation hit an internal problem", decision.recovery().title());
    assertFalse(decision.actions().contains(ChatEvent.RETRY_CREATION_ACTION));
    assertFalse(decision.actions().contains("design-execution"));
  }

  @Test
  void openDecisionProjectsAContextualRepeatedFailureFromServerOwnedState() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-repeated"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-repeated",
                    "run-repeated",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    9L,
                    new CreateChainPendingAction.Clarify(
                        "The same problem came back. Repeating the same request will not help.",
                        List.of(),
                        PipelineGates.RECOVERY_REPEATED,
                        "same failure identity=abc progress=none",
                        null,
                        "run-repeated",
                        "work"),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-repeated")
            .orElseThrow();

    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), decision.actions());
    assertEquals("repeated-identical-failure", decision.recovery().category());
    assertEquals("The same problem came back", decision.recovery().title());
    assertFalse(decision.actions().contains("work"));
  }

  @Test
  void markerNamesTheApprovedArtifactInEnglish() {
    assertEquals(
        "Approved implementation-plan sha256:abc",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.APPROVE_ACTION, "implementation-plan", "sha256:abc", null)));
  }

  @Test
  void markerKeepsTheReadersComment() {
    String marker =
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.APPROVE_ACTION, "requirement-brief", "sha256:def", "keep the timeout"));

    assertTrue(marker.startsWith("Approved requirement-brief sha256:def"), marker);
    assertTrue(marker.endsWith("keep the timeout"), marker);
  }

  @Test
  void markerNamesAChangeRequestWithoutTheHash() {
    assertEquals(
        "Requested changes to implementation-plan",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.REQUEST_CHANGES_ACTION, "implementation-plan", "sha256:abc", null)));
  }

  @Test
  void changeRequestContinuesTheRunWithTheComment() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.continueWithInput(any()))
        .thenReturn(Multi.createFrom().item(new CreateChainEvent.Progress("revising")));
    when(facade.snapshot("conv-1")).thenReturn(Optional.empty());

    List<ChatEvent> events =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply(
                "conv-1",
                command(
                    ChatEvent.REQUEST_CHANGES_ACTION,
                    "requirement-brief",
                    "sha256:abc",
                    "bind onTaskStart to the start topic"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertFalse(events.isEmpty(), "request-changes must not close the stream empty");
    ArgumentCaptor<ContinueCreateChainCommand> captor =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(captor.capture());
    assertEquals("bind onTaskStart to the start topic", captor.getValue().clarificationText());
    verify(facade, never()).streamApprove(any(ApproveCreateChainArtifactCommand.class));
  }

  @Test
  void changeRequestWithoutCommentStillContinuesTheRun() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().empty());
    when(facade.snapshot("conv-1")).thenReturn(Optional.empty());

    List<ChatEvent> events =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply(
                "conv-1",
                command(ChatEvent.REQUEST_CHANGES_ACTION, "implementation-plan", "sha256:abc", null))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertFalse(events.isEmpty(), "an empty comment must still produce a chat event");
    ArgumentCaptor<ContinueCreateChainCommand> captor =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(captor.capture());
    assertEquals(
        "Requested changes to implementation-plan", captor.getValue().clarificationText());
  }

  @Test
  void openDecisionFillsInTheStoredQuestionWhenTheWaitCarriesNone() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    5L,
                    new CreateChainPendingAction.Approve(
                        "implementation-plan", "sha256:abc", 5L, ""),
                    "")));
    ApprovalQuestionStore questions = questionStore();
    questions.save("conv-1", "sha256:abc", "Approve the plan?");

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questions, new RequirementDraftStore()).openDecision("conv-1").orElseThrow();

    assertEquals("approve:sha256:abc", decision.id());
    assertEquals("Approve the plan?", decision.question());
    assertEquals("sha256:abc", decision.artifactHash());
    assertEquals(5L, decision.revision());
  }

  @Test
  void typedContinuationKeepsTheFreshApprovalQuestionOnOneDurableCard() {
    var facade = mock(CreateChainApplicationFacade.class);
    var blank = new CreateChainPendingAction.Approve("requirement-brief", "sha256:brief", 5L, "");
    when(facade.snapshot("conv-1")).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        "conv-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 5L, blank, "")));
    when(facade.restartCheckpoints("conv-1")).thenReturn(List.of(RestartCheckpoint.BEGINNING));
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().items(
        new CreateChainEvent.Waiting(new CreateChainPendingAction.Approve(
            "requirement-brief", "sha256:brief", 5L, "Approve the selected RBAC endpoint requirements?")),
        new CreateChainEvent.Waiting(blank)));
    var blobs = new InMemoryArtifactBlobStore();
    var questions = new ApprovalQuestionStore(blobs);
    var service = new ChatDecisionService(facade, questions, new RequirementDraftStore());
    service.promptAgent = mock(ApprovalPromptAgent.class);

    var events = service.apply("conv-1",
        command(ChatEvent.REQUEST_CHANGES_ACTION, "requirement-brief", "sha256:previous", "Use the selected policy."))
        .collect().asList().await().indefinitely();

    assertEquals(1, events.size());
    var emitted = assertInstanceOf(ChatEvent.Decision.class, events.getFirst());
    assertEquals("approve:sha256:brief", emitted.id());
    assertEquals("approve", emitted.kind());
    assertEquals("requirement-brief", emitted.artifactType());
    assertEquals("sha256:brief", emitted.artifactHash());
    assertEquals(5L, emitted.revision());
    assertEquals("Approve the selected RBAC endpoint requirements?", emitted.question());
    assertEquals(List.of(ChatEvent.APPROVE_ACTION, ChatEvent.REQUEST_CHANGES_ACTION,
        ChatEvent.RESTART_FROM_BEGINNING_ACTION), emitted.actions());
    assertEquals(Optional.of(emitted.question()), questions.find("conv-1", "sha256:brief"));
    assertTrue(questions.find("conv-1", "sha256:previous").isEmpty());
    assertTrue(questions.find("other-conversation", "sha256:brief").isEmpty());
    var restored = new ChatDecisionService(facade, new ApprovalQuestionStore(blobs), new RequirementDraftStore());
    assertEquals(emitted, restored.openDecision("conv-1").orElseThrow());
    org.mockito.Mockito.verifyNoInteractions(service.promptAgent);
  }

  @Test
  void openDecisionKeepsApprovalActionsWhenRestartCheckpointsAreAvailable() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    5L,
                    new CreateChainPendingAction.Approve(
                        "requirement-brief", "sha256:brief", 5L, "Approve the brief?"),
                    "")));
    when(facade.restartCheckpoints("conv-1"))
        .thenReturn(List.of(RestartCheckpoint.BEGINNING));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-1")
            .orElseThrow();

    assertEquals(
        List.of(
            ChatEvent.APPROVE_ACTION,
            ChatEvent.REQUEST_CHANGES_ACTION,
            ChatEvent.RESTART_FROM_BEGINNING_ACTION),
        decision.actions());
  }

  @Test
  void openDecisionKeepsThePendingChainPatchUntilItIsCleared() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    ChatDecisionService service =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());
    service.patchStore = new ChainPatchStore();
    ProposedChainPatch proposal = mock(ProposedChainPatch.class);
    when(proposal.patchHash()).thenReturn("sha256:patch");
    when(proposal.summary()).thenReturn("Insert a delay script. Apply this to the chain?");
    service.patchStore.putProposal("conv-patch", proposal);

    ChatEvent.Decision expected =
        (ChatEvent.Decision) ChatEvent.chainPatchDecision(proposal.patchHash(), proposal.summary());
    assertEquals(expected, service.openDecision("conv-patch").orElseThrow());
    assertEquals(expected, service.openDecision("conv-patch").orElseThrow());
    assertTrue(service.openDecision("other-conversation").isEmpty());

    service.patchStore.clearProposal("conv-patch");
    assertTrue(service.openDecision("conv-patch").isEmpty());
  }

  @Test
  void openDecisionReportsNothingWhenTheRunWaitsForNothing() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-1", "run-1", CreateChainExecutionStatus.WORKING, 5L, null, "")));

    assertTrue(
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore()).openDecision("conv-1").isEmpty());
  }

  @Test
  void openDecisionHidesTheGenericAdditionalInputPlaceholder() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    5L,
                    new CreateChainPendingAction.Clarify(
                        "Additional input is required.", List.of()),
                    "")));

    assertTrue(
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-1")
            .isEmpty());
  }

  @Test
  void openDecisionOffersImportWhenACandidateIsPending() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1")).thenReturn(Optional.empty());
    when(facade.pendingCreationHash("conv-1")).thenReturn(Optional.empty());
    RequirementDraftStore drafts = new RequirementDraftStore();
    drafts.put(
        "conv-1",
        new RequirementDraft(false, "GeoSite proxy")
            .withApiHubCandidate(
                new ApiHubRequirementRefs(
                    "pkg.geosite",
                    "2024.4",
                    "op-1",
                    null,
                    "rest",
                    "GeoSite",
                    "GeoSite API")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), drafts).openDecision("conv-1").orElseThrow();

    assertEquals("import:pkg.geosite", decision.id());
    assertEquals(
        List.of(ChatEvent.IMPORT_INTERNAL_ACTION, ChatEvent.IMPORT_EXTERNAL_ACTION),
        decision.actions());
    assertTrue(decision.question().contains("GeoSite"), decision.question());
  }

  @Test
  void markerNamesTheImportWithoutGuessingAtWording() {
    assertEquals(
        ChatEvent.IMPORT_MARKER,
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.IMPORT_ACTION, null, null, null)));
  }

  @Test
  void markerNamesExternalImportWithoutGuessingAtWording() {
    assertEquals(
        ChatEvent.IMPORT_EXTERNAL_MARKER,
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.IMPORT_EXTERNAL_ACTION, null, null, null)));
  }

  @Test
  void uploadedImportMarkerDoesNotNameApiHub() {
    assertEquals(
        ChatEvent.UPLOADED_IMPORT_EXTERNAL_MARKER,
        ChatDecisionService.transcriptMarker(
            command(
                ChatEvent.IMPORT_EXTERNAL_ACTION,
                UploadedSpecsApprovalHandler.ARTIFACT_TYPE,
                "hash",
                null)));
    assertEquals(
        ChatEvent.UPLOADED_IMPORT_MARKER,
        ChatDecisionService.transcriptMarker(
            command(
                ChatEvent.IMPORT_INTERNAL_ACTION,
                UploadedSpecsApprovalHandler.ARTIFACT_TYPE,
                "hash",
                null)));
  }

  @Test
  void rememberImportChoiceWritesExternalOnTheDraft() {
    RequirementDraftStore drafts = new RequirementDraftStore();
    drafts.put("conv-1", new RequirementDraft(false, "GeoSite").withImportIntent(true));
    ChatDecisionService service =
        new ChatDecisionService(mock(CreateChainApplicationFacade.class), questionStore(), drafts);
    ChatDecisionCommand command = command(ChatEvent.IMPORT_EXTERNAL_ACTION, null, null, null);
    service.rememberImportChoice("conv-1", command);
    assertEquals("EXTERNAL", drafts.get("conv-1").orElseThrow().preferredSystemType());
  }

  @Test
  void replacementSpecRestoresImportApprovalFromTheDurableWait() {
    String conversationId = "conv-1";
    String runId = "conv-1-create-chain-2";
    ConversationService conversations = mock(ConversationService.class);
    when(conversations.getAllowedAttachmentKeys(conversationId))
        .thenReturn(List.of("uploads/old-api.yaml"));
    when(conversations.getMessages(conversationId)).thenReturn(List.of());
    ProductPipelineArtifactStore artifacts = inMemoryArtifactStore();
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversations, mock(S3Service.class));
    ChatEvent.Decision old = handler.createDecision(conversationId);
    handler.appendApprovalRecord(runId, conversationId, old, artifacts);
    when(conversations.getAllowedAttachmentKeys(conversationId))
        .thenReturn(List.of("uploads/replacement-api.yaml"));
    CreateChainApplicationFacade facade = waitingFacade(conversationId, "uploaded-spec-import", artifacts);
    doReturn(Multi.createFrom().empty()).when(facade).start(any());
    ChatDecisionService service = uploadedSpecsDecisionService(facade, conversations, artifacts, handler);

    ChatEvent.Decision current = service.openDecision(conversationId).orElseThrow();

    assertEquals(UploadedSpecsApprovalHandler.ARTIFACT_TYPE, current.artifactType());
    assertEquals(handler.attachmentHash(conversationId), current.artifactHash());
    assertFalse(old.artifactHash().equals(current.artifactHash()));
    assertEquals(List.of(ChatEvent.IMPORT_ACTION, "clarify"), current.actions());
    assertEquals(List.of(new ChatEvent.UploadedSpecItem("uploads/replacement-api.yaml", "replacement-api")), current.specs());
    ChatDecisionService reloaded = uploadedSpecsDecisionService(facade, conversations, artifacts, handler);
    assertEquals(current, reloaded.openDecision(conversationId).orElseThrow());
    ChatDecisionCommand answer = command(ChatEvent.IMPORT_ACTION, current.artifactType(), current.artifactHash(), null);
    answer.setSpecSystemTypes(Map.of("uploads/replacement-api.yaml", "EXTERNAL"));

    reloaded.apply(conversationId, answer).collect().asList().await().indefinitely();

    ApprovalRecordV2 approved = latestUploadedSpecsApproval(artifacts, conversationId);
    assertEquals(current.artifactHash(), approved.targetContentHash());
    assertEquals(Map.of("uploads/replacement-api.yaml", "EXTERNAL"), approved.specSystemTypes());
    assertFalse(reloaded.openDecision(conversationId).orElseThrow().actions().contains(ChatEvent.IMPORT_ACTION));
    verify(facade).start(any(StartCreateChainCommand.class));
  }

  @Test
  void pendingAttachmentsDoNotReplaceAnUnrelatedClarification() {
    ConversationService conversations = mock(ConversationService.class);
    when(conversations.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/replacement-api.yaml"));
    ProductPipelineArtifactStore artifacts = inMemoryArtifactStore();
    ChatDecisionService service = uploadedSpecsDecisionService(
        waitingFacade("conv-1", "requirement-discovery", artifacts), conversations, artifacts,
        new UploadedSpecsApprovalHandler(conversations, mock(S3Service.class)));

    ChatEvent.Decision decision = service.openDecision("conv-1").orElseThrow();

    assertEquals("clarify", decision.kind());
    assertFalse(decision.actions().contains(ChatEvent.IMPORT_ACTION));
  }

  private static CreateChainApplicationFacade waitingFacade(
      String conversationId, String stage, ProductPipelineArtifactStore artifacts) {
    String runId = conversationId + "-create-chain-2";
    ProductPipelineRunStore runs = mock(ProductPipelineRunStore.class);
    when(runs.loadByConversation(conversationId)).thenReturn(Optional.of(new ProductPipelineRunDocument(
        new RunSnapshot(runId, conversationId, 4L, RunStatus.WAITING_FOR_INPUT, stage, List.of(), null, "flow-1"),
        List.of(), List.of(new RunTransition(3L, 4L, RunStatus.RUNNING, RunStatus.WAITING_FOR_INPUT,
            stage, Instant.EPOCH, "Approval required to import uploaded API specifications")), "v1")));
    return spy(new CreateChainApplicationFacade(mock(CreateRunSelectionService.class),
        mock(CreateRunBindingStore.class), mock(CreateChainOrchestrator.class), runs,
        mock(ProductPipelineProfileCatalog.class), artifacts));
  }

  @Test
  void uploadedSpecImportPersistsPerKeySystemTypes() {
    ConversationService conversations = mock(ConversationService.class);
    when(conversations.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml", "uploads/partner-events.yaml"));
    when(conversations.getMessages("conv-1")).thenReturn(List.of());
    ProductPipelineArtifactStore artifactStore = inMemoryArtifactStore();
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversations, mock(S3Service.class));
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    String runId = "conv-1-create-chain-2-after-reset";
    when(facade.importApprovalRunId("conv-1")).thenReturn(runId);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenReturn(Multi.createFrom().empty());
    when(facade.snapshot("conv-1")).thenReturn(Optional.empty());
    ChatDecisionService service =
        uploadedSpecsDecisionService(facade, conversations, artifactStore, handler);
    ChatDecisionCommand command =
        command(
            ChatEvent.IMPORT_ACTION,
            UploadedSpecsApprovalHandler.ARTIFACT_TYPE,
            "hash",
            null);
    command.setSpecSystemTypes(
        Map.of(
            "uploads/orders-api.yaml", "INTERNAL",
            "uploads/partner-events.yaml", "EXTERNAL",
            "uploads/extra.yaml", "EXTERNAL"));

    service.apply("conv-1", command).collect().asList().await().indefinitely();

    ApprovalRecordV2 stored = artifactStore.payload(
        artifactStore.findLatestApprovalRecord(runId, UploadedSpecsApprovalHandler.ARTIFACT_TYPE, null)
            .orElseThrow(), ApprovalRecordV2.class);
    assertEquals(
        Map.of(
            "uploads/orders-api.yaml", "INTERNAL",
            "uploads/partner-events.yaml", "EXTERNAL"),
        stored.specSystemTypes());
  }

  @Test
  void uploadedSpecLegacyExternalActionTypesEveryKey() {
    ConversationService conversations = mock(ConversationService.class);
    when(conversations.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml", "uploads/partner-events.yaml"));
    when(conversations.getMessages("conv-1")).thenReturn(List.of());
    ProductPipelineArtifactStore artifactStore = inMemoryArtifactStore();
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversations, mock(S3Service.class));
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.importApprovalRunId("conv-1")).thenReturn("conv-1-create-chain-2");
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenReturn(Multi.createFrom().empty());
    when(facade.snapshot("conv-1")).thenReturn(Optional.empty());
    ChatDecisionService service =
        uploadedSpecsDecisionService(facade, conversations, artifactStore, handler);
    ChatDecisionCommand command =
        command(
            ChatEvent.IMPORT_EXTERNAL_ACTION,
            UploadedSpecsApprovalHandler.ARTIFACT_TYPE,
            "hash",
            null);

    service.apply("conv-1", command).collect().asList().await().indefinitely();

    ApprovalRecordV2 stored = latestUploadedSpecsApproval(artifactStore, "conv-1");
    assertEquals(
        Map.of(
            "uploads/orders-api.yaml", "EXTERNAL",
            "uploads/partner-events.yaml", "EXTERNAL"),
        stored.specSystemTypes());
  }

  @Test
  void uploadedSpecImportRejectsInvalidSystemType() {
    ConversationService conversations = mock(ConversationService.class);
    when(conversations.getAllowedAttachmentKeys("conv-1"))
        .thenReturn(List.of("uploads/orders-api.yaml"));
    ProductPipelineArtifactStore artifactStore = inMemoryArtifactStore();
    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversations, mock(S3Service.class));
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.importApprovalRunId("conv-1")).thenReturn("conv-1-create-chain-2");
    ChatDecisionService service =
        uploadedSpecsDecisionService(
            facade, conversations, artifactStore, handler);
    ChatDecisionCommand command =
        command(
            ChatEvent.IMPORT_ACTION,
            UploadedSpecsApprovalHandler.ARTIFACT_TYPE,
            "hash",
            null);
    command.setSpecSystemTypes(Map.of("uploads/orders-api.yaml", "PUBLIC"));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.apply("conv-1", command).collect().asList().await().indefinitely());
  }

  @Test
  void markerNamesRedeployWithoutGuessingAtWording() {
    assertEquals(
        "Redeploy the chain on domain default",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.REDEPLOY_ACTION, "REDEPLOY", "op-1", null)));
  }

  @Test
  void markerNamesCancelRedeployWithoutGuessingAtWording() {
    assertEquals(
        "Leave the live deployment unchanged",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.CANCEL_REDEPLOY_ACTION, "REDEPLOY", "op-1", null)));
  }

  @Test
  void markerNamesDeployWithoutGuessingAtWording() {
    assertEquals(
        "Deploy the chain on domain default",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.DEPLOY_ACTION, "DEPLOY", "op-1", null)));
  }

  @Test
  void markerNamesDeployOnPendingDomain() {
    assertEquals(
        "Deploy the chain on domain prod",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.DEPLOY_ACTION, "DEPLOY", "op-1", null), "prod"));
    assertEquals(
        "Redeploy the chain on domain prod",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.REDEPLOY_ACTION, "REDEPLOY", "op-1", null), "prod"));
  }

  @Test
  void markerNamesCancelDeployWithoutGuessingAtWording() {
    assertEquals(
        "Do not deploy the chain",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.CANCEL_DEPLOY_ACTION, "DEPLOY", "op-1", null)));
  }

  @Test
  void markerNamesUndeployWithoutGuessingAtWording() {
    assertEquals(
        "Undeploy the chain from domain default",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.UNDEPLOY_ACTION, "UNDEPLOY", "op-1", null)));
  }

  @Test
  void markerNamesUndeployOnPendingDomain() {
    assertEquals(
        "Undeploy the chain from domain prod",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.UNDEPLOY_ACTION, "UNDEPLOY", "op-1", null), "prod"));
  }

  @Test
  void markerNamesCancelUndeployWithoutGuessingAtWording() {
    assertEquals(
        "Leave the live deployment in place",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.CANCEL_UNDEPLOY_ACTION, "UNDEPLOY", "op-1", null)));
  }

  @Test
  void markerNamesSessionLoggingWithoutGuessingAtWording() {
    assertEquals(
        "Set session logging to Off",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.SESSION_LOGGING_OFF_ACTION, "SESSION_LOGGING", "op-1", null)));
    assertEquals(
        "Set session logging to Error",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.SESSION_LOGGING_ERROR_ACTION, "SESSION_LOGGING", "op-1", null)));
    assertEquals(
        "Set session logging to Info",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.SESSION_LOGGING_INFO_ACTION, "SESSION_LOGGING", "op-1", null)));
    assertEquals(
        "Set session logging to Debug",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.SESSION_LOGGING_DEBUG_ACTION, "SESSION_LOGGING", "op-1", null)));
  }

  @Test
  void markerNamesCreateMaasKafkaTopicsWithoutGuessingAtWording() {
    assertEquals(
        "Create the missing Kafka MaaS topics",
        ChatDecisionService.transcriptMarker(
            command(
                ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION,
                "MAAS_KAFKA_TOPICS",
                "op-1",
                null)));
  }

  @Test
  void markerNamesDismissMaasKafkaTopicsWithoutGuessingAtWording() {
    assertEquals(
        "Leave the chain and MaaS topics unchanged",
        ChatDecisionService.transcriptMarker(
            command(
                ChatEvent.DISMISS_MAAS_KAFKA_TOPICS_ACTION,
                "MAAS_KAFKA_TOPICS",
                "op-1",
                null)));
  }

  /**
   * Creating the chain can fail after the plan was approved. The run must stay at the
   * implementation gate with creation as its only action, never in a half-state the reader cannot
   * act on.
   */
  @Test
  void creationFailureAfterApprovalLeavesACreationOnlyCard() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    CreateChainExecutionSnapshot atGate =
        new CreateChainExecutionSnapshot(
            "conv-1", "run-1", CreateChainExecutionStatus.WORKING, 7L, null, "");
    when(facade.validateApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenReturn(Optional.empty());
    when(facade.streamApproveOnly(any(ApproveCreateChainArtifactCommand.class)))
        .thenReturn(Multi.createFrom().item(new CreateChainEvent.Message("Plan approved.")));
    when(facade.pendingCreationHash("conv-1")).thenReturn(Optional.of("sha256:plan"));
    when(facade.snapshot("conv-1")).thenReturn(Optional.of(atGate));
    when(facade.streamCreateChain("conv-1", "sha256:plan", 7L))
        .thenReturn(
            Multi.createFrom()
                .item(new CreateChainEvent.Failed("Catalog rejected the chain.", atGate)));
    ApprovalQuestionStore questions = questionStore();
    questions.save("conv-1", "sha256:plan", "Create the chain?");

    List<ChatEvent> events =
        new ChatDecisionService(facade, questions, new RequirementDraftStore())
            .apply(
                "conv-1",
                command(
                    ChatEvent.APPROVE_AND_CREATE_ACTION, "implementation-plan", "sha256:plan", null))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Token token
                        && token.text().contains("Catalog rejected")),
        () -> "expected the creation failure to be surfaced, got: " + events);
    ChatEvent last = events.get(events.size() - 1);
    ChatEvent.Decision reissued = assertInstanceOf(ChatEvent.Decision.class, last);
    assertEquals(List.of(ChatEvent.CREATE_ACTION), reissued.actions());
    assertEquals("create:sha256:plan", reissued.id());
    assertEquals("Create the chain?", reissued.question());
  }

  @Test
  void approveForwardsSkillProgressAsChatSkillSteps() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.validateApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenReturn(Optional.empty());
    when(facade.streamApproveOnly(any(ApproveCreateChainArtifactCommand.class)))
        .thenReturn(
            Multi.createFrom()
                .items(
                    new CreateChainEvent.SkillProgress("cip-requirement-analyzer", "running"),
                    new CreateChainEvent.SkillProgress("cip-requirement-analyzer", "completed"),
                    new CreateChainEvent.Message("Brief captured.")));
    when(facade.snapshot("conv-skill"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-skill",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    3L,
                    new CreateChainPendingAction.Approve(
                        "requirement-brief", "sha256:brief", 3L, ""),
                    "")));
    when(facade.pendingCreationHash("conv-skill")).thenReturn(Optional.empty());

    List<ChatEvent> events =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply(
                "conv-skill",
                command(ChatEvent.APPROVE_ACTION, "requirement-brief", "sha256:brief", null))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Step step
                        && "skill".equals(step.kind())
                        && "Parsing requirements".equals(step.label())
                        && "running".equals(step.status())),
        () -> "expected a kind=skill running step, got: " + events);
  }

  @Test
  void createChainForwardsSkillProgressAsChatSkillSteps() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.pendingCreationHash("conv-create"))
        .thenReturn(Optional.of("sha256:plan"));
    when(facade.snapshot("conv-create"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-create",
                    "run-1",
                    CreateChainExecutionStatus.WORKING,
                    7L,
                    null,
                    "")));
    when(facade.streamCreateChain("conv-create", "sha256:plan", 7L))
        .thenReturn(
            Multi.createFrom()
                .items(
                    new CreateChainEvent.SkillProgress("materialization", "running"),
                    new CreateChainEvent.SkillProgress("materialization", "completed"),
                    new CreateChainEvent.Message("Chain is ready.")));

    ChatDecisionCommand command = command(ChatEvent.CREATE_ACTION, "implementation-plan", "sha256:plan", null);
    command.setRevision(7L);

    List<ChatEvent> events =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply("conv-create", command)
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Step step
                        && "skill".equals(step.kind())
                        && "Creating the chain".equals(step.label())
                        && "running".equals(step.status())),
        () -> "expected a kind=skill running step on create-chain, got: " + events);
  }

  /**
   * The import gate inside a run must offer the button that produces its marker.
   *
   * <p>Without it the reader gets a text field, and the stage accepts only the marker a click
   * writes — the run would sit at the gate with no way through.
   */
  @Test
  void anImportGateInsideARunOffersTheImportAction() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    2L,
                    new CreateChainPendingAction.Clarify(
                        "Import the API Hub specification into the runtime catalog before planning?",
                        List.of(),
                        PipelineGates.IMPORT_SPECIFICATION),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-1")
            .orElseThrow();

    assertEquals("clarify", decision.kind());
    assertEquals(
        List.of(ChatEvent.IMPORT_INTERNAL_ACTION, ChatEvent.IMPORT_EXTERNAL_ACTION),
        decision.actions());
  }

  @Test
  void aStageRetryGateShowsTheNarrativeAndOffersRetry() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    String narrative = "The catalog could not find that service.";
    when(facade.snapshot("conv-retry"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-retry",
                    "run-retry",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        narrative, List.of(), PipelineGates.STAGE_RETRY),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-retry")
            .orElseThrow();

    assertEquals("clarify", decision.kind());
    assertEquals(narrative, decision.reason());
    assertEquals(List.of(PipelineGates.RETRY_ACTION), decision.actions());
  }

  @Test
  void additionalRepairClicksCarryTheSameOfferDespiteFreshTransportIds() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-repair"))
        .thenReturn(Optional.of(new CreateChainExecutionSnapshot("conv-repair", "run-repair",
            CreateChainExecutionStatus.INPUT_REQUIRED, 6L,
            new CreateChainPendingAction.Clarify("Grant two corrections per strategy.", List.of(),
                PipelineGates.RECOVERY_REPAIR_ALLOWANCE, "Invalid generated value.", null,
                "run-repair", "design-execution"), "")));
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().empty());
    when(facade.pendingRepairOffer("conv-repair", 6L)).thenReturn(Optional.of("continue-repair:bound-defect"));
    var service = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());
    ChatDecisionCommand command = command(PipelineGates.CONTINUE_REPAIR_ACTION, null, null, null);
    command.setRevision(6L);
    for (int click = 0; click < 2; click++) {
      service.apply("conv-repair", command).collect().asList().await().indefinitely();
    }
    ArgumentCaptor<ContinueCreateChainCommand> inputs = ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade, times(2)).continueWithInput(inputs.capture());
    assertEquals("continue-repair:bound-defect",
        inputs.getAllValues().getFirst().clarificationText());
    assertEquals(inputs.getAllValues().getFirst().clarificationText(),
        inputs.getAllValues().getLast().clarificationText());
    assertFalse(inputs.getAllValues().getFirst().commandId().equals(inputs.getAllValues().getLast().commandId()));
    command.setRevision(5L);
    service.apply("conv-repair", command).collect().asList().await().indefinitely();
    verify(facade, times(2)).continueWithInput(any());
  }

  @Test
  void retryContinuesTheOpenGateWithoutRoutingThroughTheModel() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-retry"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-retry",
                    "run-retry",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        "The catalog could not find that service.",
                        List.of(),
                        PipelineGates.STAGE_RETRY),
                    "")));
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = command(PipelineGates.RETRY_ACTION, null, null, null);
    command.setRevision(6L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-retry", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(PipelineGates.RETRY_ACTION, input.getValue().clarificationText());
  }

  @Test
  void retryProgressNamesGoingBackOneStep() {
    assertEquals(
        "Going back one step.",
        ChatDecisionService.haltResumeProgress(PipelineGates.RETRY_ACTION));
  }

  @Test
  void aStageReviseGateShowsTheNarrativeAndOffersRetryAndRevise() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    String narrative = "The brief omitted the scheduler.";
    when(facade.snapshot("conv-revise"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-revise",
                    "run-revise",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        narrative, List.of(), PipelineGates.STAGE_REVISE),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-revise")
            .orElseThrow();

    assertEquals("clarify", decision.kind());
    assertEquals(narrative, decision.reason());
    assertEquals(
        List.of(PipelineGates.RETRY_ACTION, PipelineGates.REVISE_ACTION), decision.actions());
  }

  @Test
  void reviseContinuesTheOpenGateWithoutRoutingThroughTheModel() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-revise"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-revise",
                    "run-revise",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        "The brief omitted the scheduler.",
                        List.of(),
                        PipelineGates.STAGE_REVISE),
                    "")));
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = command(PipelineGates.REVISE_ACTION, null, null, null);
    command.setRevision(6L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-revise", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(PipelineGates.REVISE_ACTION, input.getValue().clarificationText());
  }

  @Test
  void anOwnerChoiceGateOffersOnlyEndRun() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-choice"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-choice",
                    "run-choice",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        "Either artifact could be wrong.",
                        List.of("planning", "analysis"),
                        PipelineGates.OWNER_CHOICE),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-choice")
            .orElseThrow();

    assertEquals("clarify", decision.kind());
    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), decision.actions());
  }

  @Test
  void anInternalFailureGateOffersOnlyEndRun() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-internal"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-internal",
                    "run-internal",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        "A step inside the service broke.",
                        List.of("analysis", "design"),
                        PipelineGates.STAGE_INTERNAL_FAILURE),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-internal")
            .orElseThrow();

    assertEquals("clarify", decision.kind());
    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), decision.actions());
    assertFalse(decision.actions().contains(PipelineGates.RETRY_ACTION));
    assertFalse(decision.actions().contains(PipelineGates.REVISE_ACTION));
    assertFalse(decision.actions().contains("analysis"));
    assertFalse(decision.actions().contains("design"));
  }

  @Test
  void anInternalFailureGateWithNoProducerOffersStopWithReport() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-internal-empty"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-internal-empty",
                    "run-internal-empty",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        "A step inside the service broke.",
                        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
                        PipelineGates.STAGE_INTERNAL_FAILURE),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-internal-empty")
            .orElseThrow();

    assertEquals("clarify", decision.kind());
    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), decision.actions());
  }

  @Test
  void anEscalatedGateOffersOnlyTheActionsTheGuardStillAccepts() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-escalated"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-escalated",
                    "run-escalated",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN.cardSentence(),
                        List.of(PipelineGates.STOP_WITH_REPORT_ACTION),
                        PipelineGates.STAGE_ESCALATED),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-escalated")
            .orElseThrow();

    assertEquals("clarify", decision.kind());
    assertEquals(List.of(PipelineGates.STOP_WITH_REPORT_ACTION), decision.actions());
    assertFalse(decision.actions().contains(PipelineGates.RETRY_ACTION));
    assertFalse(decision.actions().contains(PipelineGates.REVISE_ACTION));
  }

  @Test
  void leftoverOwnerChoiceContinuesOnlyThroughEndRun() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-choice"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-choice",
                    "run-choice",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        "Either artifact could be wrong.",
                        List.of("planning", "analysis"),
                        PipelineGates.OWNER_CHOICE),
                    "")));
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenReturn(Multi.createFrom().empty());
    ChatDecisionCommand command = command(PipelineGates.STOP_WITH_REPORT_ACTION, null, null, null);
    command.setRevision(6L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-choice", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(PipelineGates.STOP_WITH_REPORT_ACTION, input.getValue().clarificationText());
  }

  @Test
  void aTypedFollowUpIsNotADecisionCardAction() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-retry"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-retry",
                    "run-retry",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    6L,
                    new CreateChainPendingAction.Clarify(
                        "The catalog could not find that service.",
                        List.of(),
                        PipelineGates.STAGE_RETRY),
                    "")));
    ChatDecisionCommand command = command("use a different service", null, null, null);
    command.setRevision(6L);

    new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("conv-retry", command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    verify(facade, never()).continueWithInput(any(ContinueCreateChainCommand.class));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"yes", "no"})
  void idsPathChoiceContinuesTheOpenGateWithoutRoutingThroughTheModel(String action) {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-ids"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-ids",
                    "run-ids",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    4L,
                    new CreateChainPendingAction.Clarify(
                        "Use an IDS?", List.of(), PipelineGates.IDS_PATH_CHOICE),
                    "")));
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenReturn(Multi.createFrom().item(new CreateChainEvent.Message("Design derived.")));
    ChatDecisionCommand command = command(action, null, null, null);
    command.setRevision(4L);

    List<ChatEvent> events =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply("conv-ids", command)
            .collect()
            .asList()
            .await()
            .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(action, input.getValue().clarificationText());
    assertTrue(
        events.stream()
            .anyMatch(event -> event instanceof ChatEvent.Token token && token.text().equals("Design derived.")),
        () -> "expected the direct gate result, got: " + events);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"stale", "continue-to-planning"})
  void idsGateRejectsAStaleAnswerOrPlanningShortcut(String scenario) {
    var facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("ids-stale")).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        "ids-stale", "run", CreateChainExecutionStatus.INPUT_REQUIRED, 4L,
        new CreateChainPendingAction.Clarify("Show IDS?", List.of(), PipelineGates.IDS_PATH_CHOICE), "")));
    var command = command("stale".equals(scenario) ? "yes" : scenario, null, null, null);
    command.setRevision("stale".equals(scenario) ? 3L : 4L);
    var events = new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
        .apply("ids-stale", command).collect().asList().await().indefinitely();
    verify(facade, never()).continueWithInput(any());
    assertTrue(events.stream().anyMatch(event -> event instanceof ChatEvent.Decision decision
        && decision.actions().equals(List.of("yes", "no")) && decision.revision() == 4L));
  }

  @Test
  void planningButtonContinuesTheReadyRequirementGateWithoutRoutingThroughTheModel() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-plan"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-plan",
                    "run-plan",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    8L,
                    new CreateChainPendingAction.Clarify(
                        "Requirements are ready. Continue to planning?",
                        List.of(),
                        PipelineGates.REQUIREMENT_DRAFT_READY),
                    "")));
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenReturn(Multi.createFrom().item(new CreateChainEvent.Message("Planning started.")));
    ChatDecisionCommand command =
        command(ChatEvent.CONTINUE_TO_PLANNING_ACTION, null, null, null);
    command.setRevision(8L);

    List<ChatEvent> events =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply("conv-plan", command)
            .collect()
            .asList()
            .await()
            .indefinitely();

    ArgumentCaptor<ContinueCreateChainCommand> input =
        ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertEquals(
        ChatEvent.CONTINUE_TO_PLANNING_ACTION, input.getValue().clarificationText());
    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Token token
                        && token.text().equals("Planning started.")),
        () -> "expected the direct gate result, got: " + events);
  }

  /** A clarification the run does not name stays free text. */
  @Test
  void anUnnamedClarificationOffersNoActions() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    2L,
                    new CreateChainPendingAction.Clarify("Which system?", List.of()),
                    "")));

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .openDecision("conv-1")
            .orElseThrow();

    assertEquals("Which system?", decision.question());
    assertTrue(decision.actions().isEmpty());
  }

  /** The question is authored in the language the reader is using, not composed in English. */
  @Test
  void theImportQuestionIsAuthoredInTheLanguageOfTheConversation() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1")).thenReturn(Optional.empty());
    when(facade.pendingCreationHash("conv-1")).thenReturn(Optional.empty());
    ChatDecisionService service =
        new ChatDecisionService(facade, questionStore(), draftsWithCandidate("Crea una cadena"));
    service.promptAgent = authoringAgent("Importar la especificacion GeoSite API al catalogo?");

    ChatEvent.Decision decision = service.openDecision("conv-1").orElseThrow();

    assertEquals("Importar la especificacion GeoSite API al catalogo?", decision.question());
  }

  /** A re-fetch shows the wording the reader already saw, not a fresh variant of it. */
  @Test
  void theImportQuestionIsStableAcrossReFetches() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1")).thenReturn(Optional.empty());
    when(facade.pendingCreationHash("conv-1")).thenReturn(Optional.empty());
    ChatDecisionService service =
        new ChatDecisionService(facade, questionStore(), draftsWithCandidate("Crea una cadena"));
    service.promptAgent = authoringAgent("Primera redaccion?");

    String first = service.openDecision("conv-1").orElseThrow().question();
    service.promptAgent = authoringAgent("Segunda redaccion?");
    String second = service.openDecision("conv-1").orElseThrow().question();

    assertEquals(first, second);
  }

  @Test
  void aFailingPromptModelLeavesTheEnglishQuestion() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1")).thenReturn(Optional.empty());
    when(facade.pendingCreationHash("conv-1")).thenReturn(Optional.empty());
    ChatDecisionService service =
        new ChatDecisionService(facade, questionStore(), draftsWithCandidate("Create a chain"));
    service.promptAgent =
        new ApprovalPromptAgent() {
          @Override
          public String askStageApproval(String stageId, String responseLocale, String reference) {
            return null;
          }

          @Override
          public String askImplementContinuation(String responseLocale, String reference) {
            return null;
          }

          @Override
          public String askImportConfirmation(
              String specification, String responseLocale, String reference) {
            throw new IllegalStateException("model unavailable");
          }
        };

    ChatEvent.Decision decision = service.openDecision("conv-1").orElseThrow();

    assertTrue(decision.question().startsWith("Import the API Hub specification"), decision.question());
  }

  private static ApprovalPromptAgent authoringAgent(String question) {
    return new ApprovalPromptAgent() {
      @Override
      public String askStageApproval(String stageId, String responseLocale, String reference) {
        return null;
      }

      @Override
      public String askImplementContinuation(String responseLocale, String reference) {
        return null;
      }

      @Override
      public String askImportConfirmation(
          String specification, String responseLocale, String reference) {
        return question;
      }
    };
  }

  private static RequirementDraftStore draftsWithCandidate(String assembledText) {
    RequirementDraftStore drafts = new RequirementDraftStore();
    drafts.put(
        "conv-1",
        new RequirementDraft(false, assembledText)
            .withApiHubCandidate(
                new ApiHubRequirementRefs(
                    "pkg.geosite", "2024.4", "op-1", null, "rest", "GeoSite", "GeoSite API")));
    return drafts;
  }

  private static ApprovalQuestionStore questionStore() {
    return new ApprovalQuestionStore(new InMemoryArtifactBlobStore());
  }

  private static ChatDecisionCommand command(
      String action, String artifactType, String artifactHash, String comment) {
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactType(artifactType);
    command.setArtifactHash(artifactHash);
    command.setRevision(3L);
    command.setComment(comment);
    return command;
  }

  private static ChatDecisionService uploadedSpecsDecisionService(
      CreateChainApplicationFacade facade,
      ConversationService conversations,
      ProductPipelineArtifactStore artifactStore,
      UploadedSpecsApprovalHandler handler) {
    ChatDecisionService service =
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore());
    service.conversationService = conversations;
    service.artifactStore = artifactStore;
    service.uploadedSpecsApprovalHandler = handler;
    return service;
  }

  private static ProductPipelineArtifactStore inMemoryArtifactStore() {
    return new ProductPipelineArtifactStore(
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            new ObjectMapper().registerModule(new JavaTimeModule()),
            Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC)));
  }

  private static ApprovalRecordV2 latestUploadedSpecsApproval(
      ProductPipelineArtifactStore artifactStore, String conversationId) {
    String runId =
        conversationId
            + "-"
            + CreateRunSelectionService.CREATE_PROFILE_ID
            + "-"
            + CreateRunSelectionService.CREATE_PROFILE_VERSION;
    CompilationArtifacts.Revision revision =
        artifactStore
            .findLatestApprovalRecord(
                runId, UploadedSpecsApprovalHandler.ARTIFACT_TYPE, null)
            .orElseThrow();
    return artifactStore.payload(revision, ApprovalRecordV2.class);
  }
}
