package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;

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
          public String askStageApproval(String stageId, String reference) {
            return null;
          }

          @Override
          public String askImplementContinuation(String reference) {
            return null;
          }

          @Override
          public String askImportConfirmation(String specification, String reference) {
            throw new IllegalStateException("model unavailable");
          }
        };

    ChatEvent.Decision decision = service.openDecision("conv-1").orElseThrow();

    assertTrue(decision.question().startsWith("Import the API Hub specification"), decision.question());
  }

  private static ApprovalPromptAgent authoringAgent(String question) {
    return new ApprovalPromptAgent() {
      @Override
      public String askStageApproval(String stageId, String reference) {
        return null;
      }

      @Override
      public String askImplementContinuation(String reference) {
        return null;
      }

      @Override
      public String askImportConfirmation(String specification, String reference) {
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
