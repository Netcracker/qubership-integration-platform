package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.plan.ConversationApiResolutions;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.facade.*;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

class CatalogVersionDecisionTest {
  private final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
  private final RequirementDraftStore drafts = new RequirementDraftStore();
  private final CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
  private final ConversationApiResolutions resolutions = new ConversationApiResolutions();
  private final CatalogVersionSelection selections = new CatalogVersionSelection(blobs,
      new ObjectMapper().findAndRegisterModules(), drafts, resolutions);
  private final ChatDecisionService service = new ChatDecisionService(facade, new ApprovalQuestionStore(blobs), drafts);
  private final RequirementFlow.Interaction interaction = new RequirementFlow.Interaction("task",
      RequirementFlow.Direction.OUTBOUND, "Salesforce WFM", "createTask", "Create a task");

  CatalogVersionDecisionTest() {
    drafts.put("chat", new RequirementDraft(false, "Create tasks")
        .withFlow(new RequirementFlow(List.of(interaction), List.of())));
    selections.propose("chat", interaction, List.of(match("old", "1.0.0"), match("new", "2.0.0")));
    service.versionSelections = selections;
    service.promptAgent = mock(ApprovalPromptAgent.class);
    when(facade.responseLocale("chat")).thenReturn("ru");
    when(service.promptAgent.askStageApproval(any(), any(), any())).thenReturn("\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c \u0432\u0435\u0440\u0441\u0438\u044e 2.0.0?");
    when(facade.snapshot("chat")).thenReturn(Optional.of(new CreateChainExecutionSnapshot(
        "chat", "run", CreateChainExecutionStatus.INPUT_REQUIRED, 7,
        new CreateChainPendingAction.Clarify("Choose the operation version", List.of()), "")));
    when(facade.continueWithInput(any())).thenAnswer(call -> {
      when(facade.snapshot("chat")).thenReturn(Optional.empty());
      return Multi.createFrom().empty();
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"approve", "request-changes"})
  void theCardConfirmsTheExactVersionOrContinuesWithTheRequestedChange(String action) {
    var card = service.openDecision("chat").orElseThrow();
    assertEquals("\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c \u0432\u0435\u0440\u0441\u0438\u044e 2.0.0?", card.question());
    assertEquals(CatalogVersionSelection.ARTIFACT_TYPE, card.artifactType());
    assertEquals(card, service.openDecision("chat").orElseThrow());
    verify(service.promptAgent, times(1)).askStageApproval(any(), any(), any());
    var command = command(card, action);
    if (action.equals("request-changes")) command.setComment("Use version 1.0.0 instead.");

    service.apply("chat", command).collect().asList().await().indefinitely();

    var resumed = ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(resumed.capture());
    if (action.equals("approve")) {
      assertTrue(resumed.getValue().clarificationText().contains("op-new"));
      assertTrue(resumed.getValue().clarificationText().contains("specification new, version 2.0.0"));
      assertEquals("op-new", selections.confirmed("chat", interaction).orElseThrow().integrationOperationId());
    } else {
      assertEquals("Use version 1.0.0 instead.", resumed.getValue().clarificationText());
      assertTrue(selections.confirmed("chat", interaction).isEmpty());
    }
    assertTrue(selections.pending("chat").isEmpty());
  }

  @Test
  void aCardForAnOlderDraftReissuesTheCurrentProposal() {
    var card = service.openDecision("chat").orElseThrow();
    drafts.put("chat", new RequirementDraft(false, "Preserve the task ID")
        .withFlow(new RequirementFlow(List.of(interaction), List.of())));

    var events = service.apply("chat", command(card, "approve")).collect().asList().await().indefinitely();

    verify(facade, never()).continueWithInput(any());
    var replacement = assertInstanceOf(ChatEvent.Decision.class, events.getFirst());
    assertNotEquals(card.artifactHash(), replacement.artifactHash());
    assertTrue(selections.confirmed("chat", interaction).isEmpty());
  }

  private static ChatDecisionCommand command(ChatEvent.Decision card, String action) {
    var command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactType(card.artifactType());
    command.setArtifactHash(card.artifactHash());
    command.setRevision(card.revision());
    return command;
  }

  private static CatalogMatch match(String id, String version) {
    return new CatalogMatch("sf", "group", id, "op-" + id, "Salesforce WFM", "http", "POST", "/tasks",
        "createTask", "catalog-read:" + id, version);
  }
}
