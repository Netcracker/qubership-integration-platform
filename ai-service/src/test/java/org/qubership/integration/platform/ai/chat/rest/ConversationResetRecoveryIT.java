package org.qubership.integration.platform.ai.chat.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.a2a.A2aSdkBootDisabledProfile;
import org.qubership.integration.platform.ai.chain.edit.ChainEditClarificationStore;
import org.qubership.integration.platform.ai.chain.edit.ChainEditEscalationStore;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchStore;
import org.qubership.integration.platform.ai.chain.patch.ProposedChainPatch;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.scenario.ChainPatchScenario;
import org.qubership.integration.platform.ai.llm.scenario.ForScenario;
import org.qubership.integration.platform.ai.model.ScenarioType;

@QuarkusTest
@TestProfile(A2aSdkBootDisabledProfile.class)
class ConversationResetRecoveryIT {
  @Inject ConversationService conversations;
  @Inject ChainPatchStore patches;
  @Inject ChainEditClarificationStore clarifications;
  @Inject ChainEditEscalationStore escalations;
  @Inject ObjectMapper mapper;
  @Inject @ForScenario(ScenarioType.COMPARE_AND_PATCH) ChainPatchScenario patchScenario;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void resetRemovesDiscardedCardsAndRejectsTheirPatchApproval(boolean truncate) {
    String id = UUID.randomUUID().toString();
    String other = UUID.randomUUID().toString();
    var metadata = mapper.createObjectNode();
    metadata.putArray("attachmentObjectKeys").add("uploads/api.json");
    var input = conversations.archive().beginInput(id, "Import and edit", metadata);
    conversations.registerAllowedAttachmentKeys(id, List.of("uploads/api.json"));
    conversations.registerAllowedAttachmentKeys(other, List.of("uploads/other.json"));
    var proposal = new ProposedChainPatch("chain", null, null, "patch-hash", "base-digest", "Edit");
    patches.putProposal(id, proposal);
    patches.putProposal(other, proposal);
    clarifications.put(id, new ChainEditClarificationStore.PendingClarification("chain", null, "Which element?"));
    escalations.put(id, new ChainEditEscalationStore.PendingChainEdit("chain", "Edit", null, null, "candidate"));
    given().get("/api/v1/chat/{id}/decision", id).then().statusCode(200)
        .body("artifactType", equalTo(UploadedSpecsApprovalHandler.ARTIFACT_TYPE));

    var reset = given().contentType("application/json");
    if (truncate) reset.body("{\"afterMessageIndex\":-1}");
    reset.post("/api/v1/chat/conversations/{id}/" + (truncate ? "truncate" : "reset"), id)
        .then().statusCode(204);

    given().get("/api/v1/chat/{id}/decision", id).then().statusCode(204);
    assertTrue(conversations.getAllowedAttachmentKeys(id).isEmpty());
    assertTrue(clarifications.take(id).isEmpty());
    assertTrue(escalations.take(id).isEmpty());
    assertTrue(patches.findProposal(other).isPresent());
    assertTrue(conversations.getAllowedAttachmentKeys(other).contains("uploads/other.json"));
    assertTrue(conversations.archive().readSource(id, input.turnId(), 0, 16000).contains("uploads/api.json"));

    var command = new ChatDecisionCommand();
    command.setAction(ChatEvent.APPLY_CHAIN_PATCH_ACTION);
    command.setArtifactType(ChatEvent.CHAIN_PATCH_ARTIFACT);
    command.setArtifactHash(proposal.patchHash());
    var request = new ChatRequest();
    request.setDecision(command);
    var events = patchScenario.handle(request, id, ScenarioType.COMPARE_AND_PATCH)
        .collect().asList().await().indefinitely();
    assertTrue(events.stream().filter(ChatEvent.Token.class::isInstance)
        .map(ChatEvent.Token.class::cast).anyMatch(token -> token.text().contains("no longer the one on offer")));
  }
}
