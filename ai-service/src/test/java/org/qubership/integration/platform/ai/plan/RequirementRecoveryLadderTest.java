package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureResult.NextAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder;
import org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder.FindingClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder.Stage;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

/**
 * A repeated representation candidate escalates to projection rebuild. The rebuild is not a user
 * question, and the rejected candidate is not the next capture.
 */
class RequirementRecoveryLadderTest {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Test
  void repeatedRepresentationCaptureRebuildsAndDropsTheRejectedCandidate() throws Exception {
    var h = Harness.open();
    var invalid = duplicateGoal();
    try (var ignored = ToolSession.open(h.chat)) {
      assertEquals(NextAction.REPAIR_CAPTURE, h.capture(invalid).nextAction());
      var noticed = h.capture(invalid);
      assertEquals(NextAction.REPAIR_CAPTURE, noticed.nextAction());
      assertTrue(noticed.issues().stream().anyMatch(issue -> "UNCHANGED_PROPOSAL".equals(issue.code())));

      var rebuilt = assertDoesNotThrow(() -> h.capture(invalid));

      assertEquals(NextAction.PROJECTION_REBUILD, rebuilt.nextAction());
      assertFalse(rebuilt.issues().stream().anyMatch(issue -> "REPAIR_NO_PROGRESS".equals(issue.code())));
      assertTrue(h.store.pendingProposal(h.chat).isEmpty());
      assertTrue(h.store.rejectedCaptureRevision(h.chat).isEmpty());
      assertTrue(h.store.get(h.chat).isEmpty());
      assertTrue(h.store.semanticBaseline(h.chat).isEmpty());
    }
  }

  @Test
  void clearedFeedbackKeepsTheRebuildStageAndDropsTheRejectedBody() throws Exception {
    var feedback = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore());
    var h = Harness.open(feedback);
    var invalid = duplicateGoal();
    try (var ignored = ToolSession.open(h.chat)) {
      h.capture(invalid);
      h.capture(invalid);
      assertEquals(NextAction.PROJECTION_REBUILD, h.capture(invalid).nextAction());
      assertEquals("", h.store.rejectedProposalHistory(h.chat));
      h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY);
      feedback.clearRequirement(h.chat);
      var stopped = h.capture(invalid);
      assertEquals(NextAction.STOP, stopped.nextAction());
      assertFalse(stopped.issues().stream().anyMatch(issue -> "REPAIR_NO_PROGRESS".equals(issue.code())));
    }
  }

  @Test
  void repeatedIdentityConflictDoesNotBecomeAnAuthorQuestion() throws Exception {
    var h = Harness.open();
    var conflict = chainNameConflict();
    try (var ignored = ToolSession.open(h.chat)) {
      h.capture(conflict);
      h.capture(conflict);
      var rebuilt = assertDoesNotThrow(() -> h.capture(conflict));
      assertEquals(NextAction.PROJECTION_REBUILD, rebuilt.nextAction());
      assertTrue(h.store.rejectedCaptureRevision(h.chat).isEmpty());

      var stopped = assertDoesNotThrow(() -> h.capture(conflict));
      assertEquals(NextAction.STOP, stopped.nextAction());
      assertNotEquals(NextAction.ASK_USER, stopped.nextAction());
    }
  }

  @Test
  void semanticAmbiguityAsksOnlyAfterRebuild() {
    assertEquals(Stage.PROJECTION_REBUILD,
        RequirementRecoveryLadder.advance(Stage.FOCUSED_REPAIR, List.of("SEMANTIC_AMBIGUITY"), true));
    assertEquals(Stage.ASK_AUTHOR,
        RequirementRecoveryLadder.advance(Stage.PROJECTION_REBUILD, List.of("SEMANTIC_AMBIGUITY", "UNCHANGED_PROPOSAL"), true));
    assertTrue(RequirementRecoveryLadder.asksAuthor(List.of("POLICY_RESPONSE_REQUIRED")));
    assertFalse(RequirementRecoveryLadder.asksAuthor(List.of("SEMANTIC_AMBIGUITY", "CONFLICTING_ELEMENT_INTENT")));
    assertFalse(RequirementRecoveryLadder.asksAuthor(List.of("UNBOUND_REQUIRED_LITERAL")));
    assertEquals(FindingClass.UNBOUND_LITERAL, RequirementRecoveryLadder.classify("UNBOUND_REQUIRED_LITERAL"));
    assertEquals(Stage.FOCUSED_REPAIR,
        RequirementRecoveryLadder.advance(Stage.FOCUSED_REPAIR, List.of("UNBOUND_REQUIRED_LITERAL"), true));
    assertEquals(Stage.FOCUSED_REPAIR,
        RequirementRecoveryLadder.advance(Stage.FOCUSED_REPAIR, List.of("REPAIR_FACT_LOSS"), true));
    assertEquals(Stage.INTERNAL_FAILURE,
        RequirementRecoveryLadder.advance(Stage.PROJECTION_REBUILD, List.of("DUPLICATE_ENTITY"), true));
  }

  @Test
  void clarificationNoProgressRebuildsThenFailsInternally() {
    var codes = List.of("CLARIFICATION_ANSWER_NO_PROGRESS", "UNAPPLIED_AUTHOR_ANSWER");

    assertEquals(FindingClass.REPAIRABLE_REPRESENTATION,
        RequirementRecoveryLadder.classify("CLARIFICATION_ANSWER_NO_PROGRESS"));
    assertEquals(FindingClass.REPAIRABLE_REPRESENTATION,
        RequirementRecoveryLadder.classify("UNAPPLIED_AUTHOR_ANSWER"));
    assertFalse(RequirementRecoveryLadder.asksAuthor(codes));
    assertEquals(Stage.FOCUSED_REPAIR, RequirementRecoveryLadder.advance(Stage.FOCUSED_REPAIR, codes, false));
    assertEquals(Stage.PROJECTION_REBUILD, RequirementRecoveryLadder.advance(Stage.FOCUSED_REPAIR, codes, true));
    assertEquals(Stage.INTERNAL_FAILURE, RequirementRecoveryLadder.advance(Stage.PROJECTION_REBUILD, codes, true));
    assertNotEquals(Stage.ASK_AUTHOR, RequirementRecoveryLadder.advance(Stage.PROJECTION_REBUILD, codes, true));
  }

  @Test
  void legacyFeedbackWithoutLadderStageReadsNull() throws Exception {
    String json = "{\"kind\":\"VALIDATION\",\"summary\":\"old\",\"failureClass\":\"CORRECTABLE\","
        + "\"outerAllowed\":true,\"fieldHints\":[],\"repairHistoryJson\":\"\",\"noProgress\":false,\"issues\":[]}";
    var feedback = JSON.readValue(json, CaptureAttemptFeedback.class);
    assertNull(feedback.ladderStage());
    var stored = new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, "old", CaptureFailureClass.CORRECTABLE,
        true, List.of(), "", false, null, List.of(), null, Stage.PROJECTION_REBUILD);
    var roundTrip = JSON.readValue(JSON.writeValueAsString(stored), CaptureAttemptFeedback.class);
    assertEquals(Stage.PROJECTION_REBUILD, roundTrip.ladderStage());
  }

  @Test
  void promptShowsStrategyParticipantsAndEditablePaths() throws Exception {
    var h = Harness.open();
    try (var ignored = ToolSession.open(h.chat)) {
      var rejected = h.capture(chainNameConflict());
      assertFalse(rejected.accepted());
      var builder = new GatherRequirementsPromptBuilder(null, null, h.store, h.conversations);
      String prompt = builder.wrap(h.chat, "Name the chain.", "en");

      assertTrue(prompt.contains("Strategy: FOCUSED_REPAIR"), prompt);
      assertTrue(prompt.contains("chain-alpha"), prompt);
      assertTrue(prompt.contains("chain-beta"), prompt);
      assertTrue(prompt.contains("Editable paths:"), prompt);
      assertFalse(prompt.contains("preserve unaffected"));
      assertFalse(prompt.toLowerCase(java.util.Locale.ROOT).contains("preserve rejected"));
    }
  }

  private static DraftInput duplicateGoal() {
    var flow = new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Client",
        "POST /tasks", null, null, null)), List.of());
    var goal = new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Submit one task.");
    return new DraftInput(flow, List.of(goal, goal), List.of(), List.of(), new DraftSettings(false, null));
  }

  private static DraftInput chainNameConflict() {
    var flow = new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Client",
        "POST /tasks", null, null, null)), List.of());
    var first = chainFact("chain-alpha", "Alpha flow");
    var second = chainFact("chain-beta", "Beta flow");
    return new DraftInput(flow, List.of(first, second), List.of(new CapabilityInput("entry", "http-trigger",
        HttpMode.CUSTOM, HttpMethod.POST, "/tasks", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  private static FactInput chainFact(String id, String name) {
    return new FactInput(id, List.of(), FactKind.PARAMETER, Polarity.POSITIVE,
        "The chain name is " + name + ".", null, null,
        new RequirementElementIntent("chain", "chain", RequirementElementIntent.Placement.CHAIN,
            null, null, null, name, List.of(), List.of()));
  }

  private static final class Harness {
    final String chat = "recovery-ladder-" + UUID.randomUUID();
    final ConversationService conversations = new ConversationService();
    final RequirementDraftStore store;
    final RequirementCaptureTools tools;

    private Harness(CaptureAttemptFeedbackStore feedback) {
      var blobs = new InMemoryArtifactBlobStore();
      var clock = Clock.systemUTC();
      var artifacts = new CompilationArtifacts(blobs, JSON, clock);
      var sessions = new CompilationSessions(blobs, JSON, clock);
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      if (feedback == null) {
        tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), JSON);
      } else {
        var gateway = new CaptureToolOutcomeGateway(feedback.fingerprintStore(), feedback);
        tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), JSON, gateway, feedback, null);
      }
      tools.conversations = conversations;
      String author = "Submit one task. The chain name is Alpha flow. The chain name is Beta flow.";
      var input = conversations.archive().beginInput(chat, author, null);
      conversations.archive().resolveInput(chat, input, author);
      store.beginTurn(chat, author);
    }

    static Harness open() {
      return new Harness(null);
    }

    static Harness open(CaptureAttemptFeedbackStore feedback) {
      return new Harness(feedback);
    }

    RequirementCaptureResult capture(DraftInput draft) throws Exception {
      return JSON.readValue(tools.captureRequirementDraft(draft), RequirementCaptureResult.class);
    }
  }
}
