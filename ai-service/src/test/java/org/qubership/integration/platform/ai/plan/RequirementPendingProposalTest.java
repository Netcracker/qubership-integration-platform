package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;

class RequirementPendingProposalTest {
  private static final String CHAT = "pending-proposal";
  private static final String TEXT = "Preserve the complete proposal " + "x".repeat(2_300);

  @Test
  void initialCandidateRetainsExactIdentityAndFindingsAcrossReloadWithoutWrites() {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.begin(TEXT);
    var candidate = input("invalid id", TEXT);
    var result = refusal(null, null, RequirementCaptureEditor.initialize(candidate).issues());
    h.store.recordCaptureOutcome(CHAT, "captureRequirementDraft", candidate, result);
    var attempt = h.artifacts.latest(h.sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
    var source = h.json.valueToTree(h.archive.read(CHAT));
    var history = h.artifacts.history(h.sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_ATTEMPT);
    var expected = h.store.pendingProposal(CHAT).orElseThrow();

    assertEquals(candidate, expected.draft());
    assertEquals(attempt.reference(), expected.attempt());
    assertEquals(h.store.writeAuthority(CHAT).sourceGeneration(), expected.sourceGeneration());
    assertEquals(h.store.writeAuthority(CHAT).sourceTurnId(), expected.sourceTurnId());
    assertEquals(null, expected.base());
    assertEquals("captureRequirementDraft", expected.toolName());
    assertFalse(expected.issues().isEmpty());
    assertEquals(result.issues(), expected.issues());
    var reloaded = new Harness(h.blobs);
    assertEquals(expected, reloaded.store.pendingProposal(CHAT).orElseThrow());
    assertEquals(expected, reloaded.store.pendingProposal(CHAT).orElseThrow());
    assertTrue(reloaded.store.get(CHAT).isEmpty());
    assertEquals(history, reloaded.artifacts.history(reloaded.sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_ATTEMPT));
    assertEquals(source, reloaded.json.valueToTree(reloaded.archive.read(CHAT)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void updateWithoutAnAcceptedBaseDoesNotHideTheCompleteRejectedProposal(boolean reload) throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.begin(TEXT);
    var draft = input("invalid id", TEXT);
    var candidate = new DraftInput(draft.flow(), draft.facts(), draft.capabilities(), draft.openQuestions(),
        new RequirementCaptureInput.DraftSettings(null, null));
    try (var ignored = ToolSession.open(CHAT)) {
      var initial = h.json.readValue(h.tools.captureRequirementDraft(candidate), RequirementCaptureResult.class);
      assertFalse(initial.accepted());
      assertTrue(initial.issues().stream().anyMatch(issue -> "INVALID_VALUE".equals(issue.code())));
      var expected = h.store.pendingProposal(CHAT).orElseThrow();
      var original = h.artifacts.get(h.sessions.active(CHAT), expected.attempt()).orElseThrow();
      var protection = h.json.valueToTree(h.store.repairConstraints(CHAT));
      var source = h.json.valueToTree(h.archive.read(CHAT));
      var scope = h.store.captureMutationScope(CHAT);
      var changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
      var failed = h.json.readValue(h.tools.updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertFalse(failed.accepted());
      assertTrue(failed.issues().stream().anyMatch(issue -> "DRAFT_NOT_FOUND".equals(issue.code())));
      assertEquals(protection, h.json.valueToTree(h.store.repairConstraints(CHAT)));
      var diagnostic = h.artifacts.latest(h.sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      assertFalse(diagnostic.reference().equals(expected.attempt()));
      assertEquals("updateRequirementDraft", diagnostic.payload().path("toolName").asText());
      assertEquals(h.json.valueToTree(changes), diagnostic.payload().path("arguments"));
      assertEquals(h.json.valueToTree(failed.issues()), diagnostic.payload().path("issues"));
      var current = reload ? new Harness(h.blobs) : h;
      if (reload) current.store.beginTurn(CHAT, TEXT); // Reattach the same archived turn; no new input.
      var beforeRead = current.persisted();
      int credits = current.fingerprints.softCreditsUsed(CHAT);
      assertTrue(credits > 0);
      var read = current.json.readValue(current.tools.readRequirementDraft(), RequirementCaptureResult.class);
      assertAll("reload=" + reload,
          () -> assertEquals(expected, read.pendingProposal()),
          () -> assertEquals(RequirementCaptureResult.Readiness.EMPTY, read.readiness()),
          () -> assertEquals(null, read.draft()),
          () -> assertEquals(null, read.revision()),
          () -> assertFalse(read.changed()),
          () -> assertTrue(current.store.get(CHAT).isEmpty()),
          () -> assertFalse(current.store.wasCapturedThisTurn(CHAT)),
          () -> assertEquals(protection, current.json.valueToTree(current.store.repairConstraints(CHAT))),
          () -> assertEquals(source, current.json.valueToTree(current.archive.read(CHAT))),
          () -> assertEquals(scope, current.store.captureMutationScope(CHAT)),
          () -> assertEquals(credits, current.fingerprints.softCreditsUsed(CHAT)),
          () -> assertEquals(original, current.artifacts.get(current.sessions.active(CHAT), expected.attempt()).orElseThrow()),
          () -> assertEquals(diagnostic, current.artifacts.latest(current.sessions.active(CHAT),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow()),
          () -> assertEquals(beforeRead, current.persisted()));
    }
  }

  @Test
  void rejectedUpdateUsesItsExactPersistedBaseAndCandidateInsteadOfAcceptedFallback() {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.begin(TEXT);
    var original = input("goal", "Keep the accepted requirement.");
    assertTrue(RequirementCaptureEditor.initialize(original).accepted());
    h.store.put(CHAT, RequirementCaptureProjection.toDraft(original, null, List.of(), "test", "test"));
    var base = h.store.latestRevision(CHAT).orElseThrow();
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        input("invalid id", TEXT).facts(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    var edit = RequirementCaptureEditor.apply(original, update);
    assertFalse(edit.accepted());
    assertEquals(original, edit.draft());
    h.store.recordCaptureOutcome(CHAT, "updateRequirementDraft", update,
        refusal(h.store.get(CHAT).orElseThrow(), base.reference(), edit.issues()));
    var history = h.artifacts.history(h.sessions.active(CHAT), Kind.REQUIREMENT_DRAFT);

    var restored = new Harness(h.blobs);
    var pending = restored.store.pendingProposal(CHAT).orElseThrow();
    assertEquals(edit.candidate(), pending.draft());
    assertEquals(base.reference(), pending.base());
    assertEquals("updateRequirementDraft", pending.toolName());
    assertEquals(original, restored.store.get(CHAT).orElseThrow().authoredDraft());
    assertEquals(history, restored.artifacts.history(restored.sessions.active(CHAT), Kind.REQUIREMENT_DRAFT));
  }

  @ParameterizedTest
  @ValueSource(strings = {"source-without-begin", "same-text-new-turn", "reset-then-finish", "base-with-same-content",
      "accepted-capture", "malformed-arguments", "new-compilation", "foreign-conversation", "foreign-base"})
  void staleResetForeignAndUnusableAttemptsNeverBecomeCurrentCandidates(String boundary) {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.begin(TEXT);
    var candidate = input("invalid id", TEXT);
    var rejected = refusal(null, null, RequirementCaptureEditor.initialize(candidate).issues());
    h.store.recordCaptureOutcome(CHAT, "captureRequirementDraft", candidate, rejected);
    assertTrue(h.store.pendingProposal(CHAT).isPresent());
    switch (boundary) {
      case "source-without-begin", "same-text-new-turn" -> {
        String next = boundary.equals("same-text-new-turn") ? TEXT : "A different source.";
        var input = h.archive.beginInput(CHAT, next, null);
        h.archive.resolveInput(CHAT, input, next);
      }
      case "reset-then-finish" -> {
        h.store.clearTurnFlags(CHAT);
        h.store.beginTurn(CHAT, TEXT);
        h.store.recordCaptureOutcome(CHAT, "finishRequirementDiscoveryTurn", RequirementDiscoveryDirective.CONTINUE, rejected);
      }
      case "base-with-same-content" -> {
        var original = input("goal", "Keep the accepted requirement.");
        var draft = RequirementCaptureProjection.toDraft(original, null, List.of(), "test", "test");
        h.store.put(CHAT, draft);
        h.store.recordCaptureOutcome(CHAT, "captureRequirementDraft", candidate,
            refusal(draft, h.store.latestRevision(CHAT).orElseThrow().reference(), RequirementCaptureEditor.initialize(candidate).issues()));
        assertTrue(h.store.pendingProposal(CHAT).isPresent());
        h.store.put(CHAT, draft);
      }
      case "accepted-capture" -> h.store.recordCaptureOutcome(CHAT, "captureRequirementDraft", candidate,
          RequirementCaptureResult.view(null, null, true, false, List.of(), RequirementDiscoveryDirective.NONE));
      case "malformed-arguments" -> h.store.recordCaptureOutcome(CHAT, "captureRequirementDraft", "invalid JSON", rejected);
      case "new-compilation" -> h.store.remove(CHAT);
      case "foreign-conversation" -> {
        assertTrue(h.store.pendingProposal("another-conversation").isEmpty());
        return;
      }
      case "foreign-base" -> {
        var foreignInput = h.archive.beginInput("another-conversation", TEXT, null);
        h.archive.resolveInput("another-conversation", foreignInput, TEXT);
        h.store.beginTurn("another-conversation", TEXT);
        h.store.put("another-conversation", RequirementCaptureProjection.toDraft(input("goal", TEXT), null, List.of(), "test", "test"));
        var foreign = h.store.latestRevision("another-conversation").orElseThrow().reference();
        var previous = h.store.pendingProposal(CHAT).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> h.store.recordCaptureOutcome(CHAT, "captureRequirementDraft", candidate,
            refusal(null, foreign, RequirementCaptureEditor.initialize(candidate).issues())));
        assertEquals(previous, h.store.pendingProposal(CHAT).orElseThrow());
        return;
      }
      default -> throw new AssertionError(boundary);
    }
    assertTrue(h.store.pendingProposal(CHAT).isEmpty(), boundary);
    var restored = new Harness(h.blobs);
    assertTrue(restored.store.pendingProposal(CHAT).isEmpty(), boundary + " after reload");
  }

  private static DraftInput input(String id, String text) {
    return new DraftInput(new RequirementCaptureInput.FlowInput(List.of(), List.of()),
        List.of(new FactInput(id, List.of(), FactKind.GOAL, Polarity.POSITIVE, text)), List.of(), List.of(),
        new RequirementCaptureInput.DraftSettings(false, null));
  }

  private static RequirementCaptureResult refusal(RequirementDraft base,
      CompilationArtifacts.Reference reference, List<RequirementCaptureEditor.Issue> issues) {
    return RequirementCaptureResult.view(base, reference, false, false, issues, RequirementDiscoveryDirective.NONE);
  }

  private static final class Harness {
    final InMemoryArtifactBlobStore blobs;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final CompilationArtifacts artifacts;
    final CompilationSessions sessions;
    final ConversationArchive archive;
    final RequirementDraftStore store;
    final org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore fingerprints;
    final RequirementCaptureTools tools;

    Harness(InMemoryArtifactBlobStore blobs) {
      this.blobs = blobs;
      artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
      sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
      archive = new ConversationArchive(blobs, json);
      var conversations = mock(ConversationService.class);
      when(conversations.archive()).thenReturn(archive);
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      fingerprints = new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore(
          json, artifacts, sessions);
      var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore(
          fingerprints, artifacts, sessions);
      tools = new RequirementCaptureTools(store, mock(RequirementDraftTool.class), json,
          new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(fingerprints, feedback),
          feedback, null);
    }

    java.util.Map<String, String> persisted() {
      var snapshot = new java.util.LinkedHashMap<String, String>();
      for (String key : blobs.list("")) {
        var document = blobs.getVersioned(key).orElseThrow();
        snapshot.put(key, document.version() + ":" + java.util.Base64.getEncoder().encodeToString(document.content()));
      }
      return snapshot;
    }

    void begin(String source) {
      var input = archive.beginInput(CHAT, source, null);
      archive.resolveInput(CHAT, input, source);
      store.beginTurn(CHAT, source);
    }
  }
}
