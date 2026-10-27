package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.nativeAnchorFact;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureResult;
import org.qubership.integration.platform.ai.plan.RequirementCaptureTools;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementNoProgressDiagnosisProviderTest {
  @Inject ObjectMapper json;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject ConversationService conversations;
  @InjectMock RateLimitChatModel provider;

  @Test
  void repeatedUnknownInteractionRebuildsWithoutANoProgressTerminal() throws Exception {
    assertNotNull(provider);
    String chat = "no-progress-diagnosis-" + UUID.randomUUID();
    String sourceText = "Accept private POST /receipts. Keep the receipt request context.";
    var source = conversations.archive().beginInput(chat, sourceText, null);
    conversations.archive().resolveInput(chat, source, sourceText);
    drafts.beginTurn(chat, sourceText);
    var base = new DraftInput(new FlowInput(List.of(new InteractionInput(
        "entry", Direction.INBOUND, "Caller", "POST /receipts", null, null, null)), List.of()),
        List.of(nativeAnchorFact("entry", "http-trigger"),
            new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, sourceText)),
        List.of(new CapabilityInput(
            "entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/receipts", null, null, null)),
        List.of(), new DraftSettings(null, null));
    var changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new FactInput("context", List.of("missing"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Keep the receipt request context.")), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), null);
    RequirementCaptureResult rebuilt;
    try (var ignored = ToolSession.open(chat)) {
      var initial = json.readValue(tools.captureRequirementDraft(base), RequirementCaptureResult.class);
      assertTrue(initial.accepted(), initial.toString());
      assertFalse(json.readValue(tools.updateRequirementDraft(changes), RequirementCaptureResult.class).accepted());
      var noticed = json.readValue(tools.updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertEquals(RequirementCaptureResult.NextAction.REPAIR_CAPTURE, noticed.nextAction());
      assertTrue(noticed.issues().stream().anyMatch(issue -> "UNCHANGED_PROPOSAL".equals(issue.code())));
      var creditsBefore = feedback.fingerprintStore().softFingerprints(chat);
      rebuilt = json.readValue(tools.updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertEquals(creditsBefore, feedback.fingerprintStore().softFingerprints(chat));
    }
    assertEquals(RequirementCaptureResult.NextAction.PROJECTION_REBUILD, rebuilt.nextAction());
    assertFalse(rebuilt.issues().stream().anyMatch(issue -> "REPAIR_NO_PROGRESS".equals(issue.code())));
    var stored = feedback.lastRequirementRejection(chat).orElseThrow().feedback();
    assertEquals(org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder.Stage
        .PROJECTION_REBUILD, stored.ladderStage());
    assertTrue(stored.issues().stream().anyMatch(issue -> "UNKNOWN_INTERACTION_REFERENCE".equals(issue.code())
        && "context".equals(issue.entityId())));
    assertFalse(stored.noProgress());
    assertTrue(drafts.rejectedCaptureRevision(chat).isEmpty());
    assertEquals(base, drafts.get(chat).orElseThrow().authoredDraft());
  }
}
