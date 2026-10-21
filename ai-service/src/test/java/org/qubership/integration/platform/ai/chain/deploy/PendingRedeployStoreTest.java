package org.qubership.integration.platform.ai.chain.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;

class PendingRedeployStoreTest {

  @ParameterizedTest
  @MethodSource("deploymentDecisions")
  void statusAndOperationCardsSurviveRepeatedReadsUntilAnswered(ChatEvent.Decision card) {
    PendingRedeployStore store = new PendingRedeployStore();
    store.rememberDecision("conversation", card);

    assertEquals(card, store.openDecision("conversation").orElseThrow());
    assertEquals(card, store.openDecision("conversation").orElseThrow());
    assertTrue(store.openDecision("another-conversation").isEmpty());
    store.clearDecision("conversation", answer(card, card.actions().get(0)));
    assertTrue(store.openDecision("conversation").isEmpty());
  }

  @Test
  void replacingAnOperationInvalidatesItsOldCardAndAnswer() {
    PendingRedeployStore store = new PendingRedeployStore();
    ChatEvent.Decision oldCard = (ChatEvent.Decision) ChatEvent.sessionLoggingDecision("old", "Old question");
    store.put("conversation", PendingRedeploy.loggingWait("chain", "default", null, "old", null));
    store.rememberDecision("conversation", oldCard);
    PendingRedeploy replacement = PendingRedeploy.loggingWait("chain", "default", null, "new", null);

    store.put("conversation", replacement);
    assertTrue(store.openDecision("conversation").isEmpty());
    ChatEvent.Decision newCard = (ChatEvent.Decision) ChatEvent.sessionLoggingDecision("new", "New question");
    store.rememberDecision("conversation", newCard);
    store.clearDecision("conversation", answer(oldCard, ChatEvent.SESSION_LOGGING_INFO_ACTION));

    assertEquals(newCard, store.openDecision("conversation").orElseThrow());
    assertEquals(replacement, store.find("conversation").orElseThrow());
  }

  @Test
  void wrongArtifactOrActionCannotConsumeTheOpenCard() {
    PendingRedeployStore store = new PendingRedeployStore();
    ChatEvent.Decision card = (ChatEvent.Decision) ChatEvent.deploymentProcessingDecision("deployment", "Refresh?");
    store.rememberDecision("conversation", card);
    ChatDecisionCommand wrongArtifact = answer(card, ChatEvent.REFRESH_DEPLOYMENT_ACTION);
    wrongArtifact.setArtifactType(ChatEvent.SESSION_LOGGING_ARTIFACT);
    store.clearDecision("conversation", wrongArtifact);
    store.clearDecision("conversation", answer(card, ChatEvent.SESSION_LOGGING_INFO_ACTION));

    assertEquals(card, store.openDecision("conversation").orElseThrow());
  }

  @Test
  void clearingTheConversationRemovesBothTheOperationAndItsCard() {
    PendingRedeployStore store = new PendingRedeployStore();
    store.put("conversation", PendingRedeploy.loggingWait("chain", "default", null, "operation", null));
    store.rememberDecision("conversation", (ChatEvent.Decision) ChatEvent.sessionLoggingDecision("operation", "Level?"));

    store.clear("conversation");

    assertTrue(store.find("conversation").isEmpty());
    assertTrue(store.openDecision("conversation").isEmpty());
  }

  private static Stream<ChatEvent.Decision> deploymentDecisions() {
    return Stream.of(
        ChatEvent.deployDecision("operation", "Deploy?"),
        ChatEvent.redeployDecision("operation", "Redeploy?"),
        ChatEvent.undeployDecision("operation", "Undeploy?"),
        ChatEvent.sessionLoggingDecision("operation", "Level?"),
        ChatEvent.maasKafkaTopicsDecision("operation", "Create topics?"),
        ChatEvent.deploymentProcessingDecision("deployment", "Refresh?"),
        ChatEvent.deploymentFailureDecision("deployment", "Propose a fix?"))
        .map(ChatEvent.Decision.class::cast);
  }

  private static ChatDecisionCommand answer(ChatEvent.Decision card, String action) {
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setArtifactType(card.artifactType());
    command.setArtifactHash(card.artifactHash());
    command.setAction(action);
    return command;
  }
}
