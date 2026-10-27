package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput;
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

  @Test
  void rejectedQuotedCaptureKeepsClaimsOffTheDisposableProjection() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    String author = "Submit one task before Friday.";
    h.begin(author);
    String turn = h.store.writeAuthority(CHAT).sourceTurnId();
    var fact = new FactInput("invalid id", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Submit one task",
        null, null, null, new ClaimEvidenceInput(turn, "Submit one task"));
    var candidate = new DraftInput(new RequirementCaptureInput.FlowInput(List.of(), List.of()),
        List.of(fact), List.of(), List.of(), new RequirementCaptureInput.DraftSettings(false, null));
    try (var ignored = ToolSession.open(CHAT)) {
      var refused = h.json.readValue(h.tools.captureRequirementDraft(candidate), RequirementCaptureResult.class);
      assertFalse(refused.accepted(), refused.toString());
    }
    var pending = h.store.pendingProposal(CHAT).orElseThrow();
    var attempt = h.artifacts.get(h.sessions.active(CHAT), pending.attempt()).orElseThrow();
    var stored = h.artifacts.payload(attempt, RequirementDraftStore.RequirementCaptureAttempt.class);

    assertTrue(h.store.semanticBaseline(CHAT).isEmpty(), "A rejected candidate does not create a claim");
    assertEquals(candidate, pending.draft());
    assertNull(h.store.repairConstraints(CHAT));
    assertTrue(h.artifacts.latest(h.sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_PROTECTION).isEmpty());
    assertNull(stored.semanticBaselineReference());
    assertEquals(RequirementRepairPolicy.Strategy.FOCUSED_REPAIR, stored.recoveryStrategy());
    assertEquals(CaptureProposal.digest(new RequirementDraftStore.PendingProposalBinding(
        attempt.reference(), stored.authorEvidenceHash(), null, null)), pending.token());
    assertTrue(h.store.get(CHAT).isEmpty());

    var reloaded = new Harness(h.blobs);
    reloaded.store.beginTurn(CHAT, author);
    assertTrue(reloaded.store.semanticBaseline(CHAT).isEmpty());
    assertEquals(pending.draft(), reloaded.store.pendingProposal(CHAT).orElseThrow().draft());
    assertEquals(pending.token(), reloaded.store.pendingProposal(CHAT).orElseThrow().token());
    assertNull(reloaded.store.repairConstraints(CHAT));
    assertTrue(reloaded.store.get(CHAT).isEmpty());

    var corrected = new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Submit one task",
        null, null, null, new ClaimEvidenceInput(turn, "Submit one task"));
    var correctedDraft = new DraftInput(candidate.flow(), List.of(corrected), candidate.capabilities(),
        candidate.openQuestions(), new RequirementCaptureInput.DraftSettings(null, null));
    try (var ignored = ToolSession.open(CHAT)) {
      var saved = h.json.readValue(h.tools.captureRequirementDraft(correctedDraft), RequirementCaptureResult.class);
      assertTrue(saved.accepted(), saved.toString());
    }
    assertEquals(List.of("goal"), h.store.semanticBaseline(CHAT).orElseThrow().claims().stream()
        .map(RequirementSemanticClaim::sourceFactId).toList());
  }

  @Test
  void rebuildDropsThePendingProjectionAndKeepsBaselineAndAcceptedRevision() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    String author = "Submit one task before Friday.";
    h.begin(author);
    String turn = h.store.writeAuthority(CHAT).sourceTurnId();
    var fact = new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Submit one task",
        null, null, null, new ClaimEvidenceInput(turn, "Submit one task"));
    var accepted = new DraftInput(new RequirementCaptureInput.FlowInput(List.of(), List.of()),
        List.of(fact), List.of(), List.of(), new RequirementCaptureInput.DraftSettings(null, null));
    try (var ignored = ToolSession.open(CHAT)) {
      var saved = h.json.readValue(h.tools.captureRequirementDraft(accepted), RequirementCaptureResult.class);
      assertTrue(saved.accepted(), saved.toString());
      var rejectedFact = new FactInput("invalid id", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Submit one task",
          null, null, null, new ClaimEvidenceInput(turn, "Submit one task"));
      var rejected = new DraftInput(accepted.flow(), List.of(fact, rejectedFact), accepted.capabilities(),
          accepted.openQuestions(), accepted.settings());
      var refusal = h.json.readValue(h.tools.captureRequirementDraft(rejected), RequirementCaptureResult.class);
      assertFalse(refusal.accepted(), refusal.toString());
    }
    var draft = h.store.latestRevision(CHAT).orElseThrow();
    var baseline = h.store.semanticBaseline(CHAT).orElseThrow();
    assertTrue(baseline.claims().stream().noneMatch(claim -> "invalid id".equals(claim.sourceFactId())));
    var protectionHistory = h.artifacts.history(h.sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_PROTECTION);
    assertTrue(h.store.pendingProposal(CHAT).isPresent());

    h.store.rebuildPendingProjection(CHAT);

    assertTrue(h.store.pendingProposal(CHAT).isEmpty());
    assertEquals(draft, h.store.latestRevision(CHAT).orElseThrow());
    assertEquals(accepted, h.store.get(CHAT).orElseThrow().authoredDraft());
    assertEquals(baseline, h.store.semanticBaseline(CHAT).orElseThrow());
    assertEquals(protectionHistory, h.artifacts.history(h.sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_PROTECTION));
    assertNull(h.store.repairConstraints(CHAT));

    var reloaded = new Harness(h.blobs);
    reloaded.store.beginTurn(CHAT, author);
    assertTrue(reloaded.store.pendingProposal(CHAT).isEmpty());
    assertEquals(draft.reference(), reloaded.store.latestRevision(CHAT).orElseThrow().reference());
    assertEquals(accepted, reloaded.store.get(CHAT).orElseThrow().authoredDraft());
    assertEquals(baseline, reloaded.store.semanticBaseline(CHAT).orElseThrow());
    assertNull(reloaded.store.repairConstraints(CHAT));
  }

  @Test
  void legacyProtectionRemainsReadableWhenTheAttemptHasNoRecoveryStrategy() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.begin(TEXT);
    var fact = new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Keep the legacy fact");
    var authority = h.store.writeAuthority(CHAT);
    h.artifacts.append(new CompilationArtifacts.AppendCommand(h.sessions.active(CHAT),
        Kind.REQUIREMENT_CAPTURE_PROTECTION, "1", "requirement-capture-protection", "1",
        new RequirementDraftStore.CaptureFactProtection(authority.sourceGeneration(), authority.sourceTurnId(),
            null, List.of(fact)), List.of(), null));
    var legacy = new RequirementDraftStore.RequirementCaptureAttempt(authority.sourceGeneration(),
        authority.sourceTurnId(), null, "scope", "captureRequirementDraft", input("goal", "Keep the legacy fact"),
        false, List.of(), null);
    var stored = h.json.valueToTree(legacy);
    assertTrue(stored.path("semanticBaselineReference").isMissingNode());
    assertTrue(stored.path("recoveryStrategy").isMissingNode());
    var reread = h.json.readValue(h.json.writeValueAsString(legacy),
        RequirementDraftStore.RequirementCaptureAttempt.class);
    assertNull(reread.semanticBaselineReference());
    assertNull(reread.recoveryStrategy());
    assertEquals(CaptureProposal.digest(legacy), CaptureProposal.digest(reread));
    h.artifacts.append(new CompilationArtifacts.AppendCommand(h.sessions.active(CHAT),
        Kind.REQUIREMENT_CAPTURE_ATTEMPT, "1", "requirement-capture-tools", "1", legacy, List.of(),
        h.artifacts.latest(h.sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_ATTEMPT)
            .map(CompilationArtifacts.Revision::artifactId).orElse(null)));

    var reloaded = new Harness(h.blobs);
    reloaded.store.beginTurn(CHAT, TEXT);
    assertEquals(List.of(fact), reloaded.store.repairConstraints(CHAT).facts());
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
