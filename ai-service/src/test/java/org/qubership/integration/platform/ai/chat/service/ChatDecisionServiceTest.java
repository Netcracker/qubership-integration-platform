package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.runtime.HaltRecoveryGuard;

class ChatDecisionServiceTest {

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
  void changeRequestRunsNoCommand() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);

    assertTrue(
        new ChatDecisionService(facade, questionStore(), new RequirementDraftStore())
            .apply(
                "conv-1",
                command(ChatEvent.REQUEST_CHANGES_ACTION, "implementation-plan", "sha256:abc", null))
            .collect()
            .asList()
            .await()
            .indefinitely()
            .isEmpty());
    verify(facade, never()).streamApprove(any(ApproveCreateChainArtifactCommand.class));
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
    assertEquals(List.of(ChatEvent.IMPORT_ACTION), decision.actions());
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
                    event instanceof ChatEvent.Error error
                        && error.message().contains("Catalog rejected")),
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
    assertEquals(List.of(ChatEvent.IMPORT_ACTION), decision.actions());
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
  void anOwnerChoiceGateOffersTheCandidateStageIds() {
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
    assertEquals(List.of("planning", "analysis"), decision.actions());
  }

  @Test
  void anInternalFailureGateOffersTheBoundStageIds() {
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
    assertEquals(List.of("analysis", "design"), decision.actions());
    assertFalse(decision.actions().contains(PipelineGates.RETRY_ACTION));
    assertFalse(decision.actions().contains(PipelineGates.REVISE_ACTION));
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
  void ownerChoiceContinuesTheOpenGateWithoutRoutingThroughTheModel() {
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
    ChatDecisionCommand command = command("analysis", null, null, null);
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
    assertEquals("analysis", input.getValue().clarificationText());
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

  @Test
  void idsPathNoContinuesTheOpenGateWithoutRoutingThroughTheModel() {
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
    ChatDecisionCommand command = command("no", null, null, null);
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
    assertEquals("no", input.getValue().clarificationText());
    assertTrue(
        events.stream()
            .anyMatch(event -> event instanceof ChatEvent.Token token && token.text().equals("Design derived.")),
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
}
