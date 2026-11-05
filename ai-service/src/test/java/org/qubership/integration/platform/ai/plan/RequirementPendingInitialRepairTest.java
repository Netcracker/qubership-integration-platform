package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.compiler.capture.*;
import org.qubership.integration.platform.ai.compiler.capture.policy.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementPendingInitialRepairTest {
  @Test
  void advertisedRejectedInitialTokenAllowsOnlyTheFocusedNativeCorrection() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var original = input("missing-owner");
    var refused = h.call("captureRequirementDraft", Map.of("draft", original));
    assertFalse(refused.path("accepted").asBoolean(), refused.toString());
    var pending = h.call("readRequirementDraft", Map.of()).path("pendingProposal");
    assertEquals(h.json.valueToTree(original), pending.path("draft"));
    var attemptRef = h.json.treeToValue(pending.path("attempt"), CompilationArtifacts.Reference.class);
    var storedAttempt = h.artifacts.payload(
        h.artifacts.get(h.sessions.active(h.chat), attemptRef).orElseThrow(),
        RequirementDraftStore.RequirementCaptureAttempt.class);
    assertEquals(CaptureProposal.digest(new RequirementDraftStore.PendingProposalBinding(attemptRef,
        storedAttempt.authorEvidenceHash(), storedAttempt.acceptedDraftReference(),
        storedAttempt.semanticBaselineReference())), pending.path("token").asText());
    assertTrue(pending.path("base").isNull());
    var changed = h.changes(input("entry").facts(), pending.path("token"));
    var accepted = h.call("updateRequirementDraft", Map.of("changes", changed));
    assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
    var saved = h.artifacts.latest(h.sessions.active(h.chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
    assertTrue(saved.inputs().contains(h.json.treeToValue(pending.path("attempt"), CompilationArtifacts.Reference.class)));
    assertEquals(changed, saved.payload().path("arguments"));
    assertFalse(saved.payload().path("arguments").has("pendingAttempt"));
    assertEquals(input("entry"), h.store.get(h.chat).orElseThrow().authoredDraft());
    assertTrue(h.call("finishRequirementDiscoveryTurn", Map.of("directive", "CONTINUE"))
        .path("accepted").asBoolean());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"hash", "kind", "foreign", "source", "reset", "accepted-base", "authority", "real-wrong-kind", "source-packet"})
  void staleOrForeignPendingTokensNeverSupplyAnEditingBase(String variant) throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.call("captureRequirementDraft", Map.of("draft", input("missing-owner")));
    var reference = h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token").deepCopy();
    switch (variant) {
      case "hash" -> reference = h.json.valueToTree("wrong-token");
      case "kind" -> reference = h.json.valueToTree(CaptureProposal.digest(
          new CompilationArtifacts.Reference(CompilationArtifacts.Kind.REQUIREMENT_DRAFT, "foreign", "hash")));
      case "foreign" -> {
        var other = new Harness(h.blobs);
        other.call("captureRequirementDraft", Map.of("draft", input("missing-owner")));
        reference = other.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token");
      }
      case "real-wrong-kind" -> {
        var foreign = h.artifacts.append(new CompilationArtifacts.AppendCommand(h.sessions.active(h.chat),
            CompilationArtifacts.Kind.USER_INPUT, "1", "test", "1", Map.of("foreign", "payload"), List.of(), null));
        reference = h.json.valueToTree(CaptureProposal.digest(foreign.reference()));
      }
      case "source-packet" -> {
        var document = h.archive.read(h.chat);
        var source = new ConversationArchive.Input(document.activeTurnIds().getFirst(), document.generation(), 0);
        h.archive.resolveInput(h.chat, source, "Changed effective source in the same native turn.");
      }
      case "source" -> {
        var source = h.archive.beginInput(h.chat, "A new author source.", null);
        h.archive.resolveInput(h.chat, source, "A new author source.");
      }
      case "reset" -> { h.store.clearTurnFlags(h.chat); h.store.beginTurn(h.chat); }
      case "accepted-base" -> h.store.put(h.chat,
          RequirementCaptureProjection.toDraft(input("entry"), null, List.of(), "test", "test"));
      case "authority" -> {
        org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.bindDiscovery(
            h.chat, h.chat, null, Map.of(), java.util.Set.of("/facts/local/elementIntent"), null);
        try {
          var rejected = h.call("updateRequirementDraft", Map.of("changes", h.changes(input("entry").facts(), reference)));
          assertFalse(rejected.path("accepted").asBoolean());
          assertEquals("PENDING_PROPOSAL_NOT_CURRENT", rejected.path("issues").get(0).path("code").asText());
        } finally {
          org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind();
        }
        return;
      }
      default -> throw new AssertionError(variant);
    }
    var base = h.store.latestRevision(h.chat);
    var result = h.call("updateRequirementDraft", Map.of("changes", h.changes(input("entry").facts(), reference)));
    assertFalse(result.path("accepted").asBoolean(), result.toString());
    assertEquals("PENDING_PROPOSAL_NOT_CURRENT", result.path("issues").get(0).path("code").asText());
    assertEquals(base, h.store.latestRevision(h.chat));
  }

  @Test
  void nativeReferenceCannotBeSubmittedAsTheScalarToken() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.call("captureRequirementDraft", Map.of("draft", input("missing-owner")));
    var pending = h.call("readRequirementDraft", Map.of()).path("pendingProposal");
    var rejected = h.call("updateRequirementDraft", Map.of("changes", h.changes(input("entry").facts(), pending.path("attempt"))));
    assertFalse(rejected.path("accepted").asBoolean());
    assertTrue(rejected.path("issues").findValuesAsText("code").contains("INVALID_TYPE"), rejected.toString());
    assertTrue(h.store.latestRevision(h.chat).isEmpty());
  }

  @Test
  void priorTokenCannotSelectAnOlderInitialAttempt() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.call("captureRequirementDraft", Map.of("draft", input("missing-owner")));
    var token = h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token");
    h.call("captureRequirementDraft", Map.of("draft", input("other-missing-owner")));
    assertNotEquals(token, h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token"));
    var rejected = h.call("updateRequirementDraft", Map.of("changes", h.changes(input("entry").facts(), token)));
    assertFalse(rejected.path("accepted").asBoolean());
    assertEquals("PENDING_PROPOSAL_NOT_CURRENT", rejected.path("issues").get(0).path("code").asText());
    assertTrue(h.store.latestRevision(h.chat).isEmpty());
  }

  @Test
  void acceptedBaseKeepsLegacyIdempotentCaptureAfterARejectedFocusedUpdate() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var original = input("entry");
    assertTrue(h.call("captureRequirementDraft", Map.of("draft", original)).path("accepted").asBoolean());
    var base = h.store.latestRevision(h.chat);
    var invalid = h.json.<ObjectNode>valueToTree(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of("unknown"), List.of(), List.of(), List.of(), List.of(), List.of(), null));
    assertFalse(h.call("updateRequirementDraft", Map.of("changes", invalid)).path("accepted").asBoolean());
    var accepted = h.call("captureRequirementDraft", Map.of("draft", original));
    assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
    assertFalse(accepted.path("changed").asBoolean());
    assertEquals(base, h.store.latestRevision(h.chat));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"current-store", "reloaded-store"})
  void rejectedAcceptedDraftUpdateKeepsEvidenceWithoutAdvertisingAnInitialRepairToken(String readState)
      throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    assertTrue(h.call("captureRequirementDraft", Map.of("draft", input("entry")))
        .path("accepted").asBoolean(), "readState=" + readState);
    var accepted = h.store.latestRevision(h.chat).orElseThrow();
    var rejectedCandidate = input("missing-owner");
    var rejectedChanges = h.changes(rejectedCandidate.facts(),
        com.fasterxml.jackson.databind.node.NullNode.getInstance());
    var rejected = h.call("updateRequirementDraft", Map.of("changes", rejectedChanges));
    assertFalse(rejected.path("accepted").asBoolean(), "readState=" + readState + ": " + rejected);
    var attempt = h.artifacts.latest(h.sessions.active(h.chat),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
    var storedAttempt = h.artifacts.payload(attempt, RequirementDraftStore.RequirementCaptureAttempt.class);
    var source = h.json.valueToTree(h.archive.read(h.chat));
    var reader = "reloaded-store".equals(readState) ? new Harness(h.blobs, h.chat, false) : h;

    var view = reader.call("readRequirementDraft", Map.of());
    var pending = view.path("pendingProposal");

    assertAll("readState=" + readState,
        () -> assertEquals(h.json.valueToTree(rejectedCandidate), pending.path("draft")),
        () -> assertEquals(h.json.valueToTree(attempt.reference()), pending.path("attempt")),
        () -> assertEquals(h.json.valueToTree(accepted.reference()), pending.path("base")),
        () -> assertEquals("updateRequirementDraft", pending.path("toolName").asText()),
        () -> assertEquals(rejected.path("issues"), pending.path("issues")),
        () -> assertEquals(storedAttempt.sourceGeneration(), pending.path("sourceGeneration").asText()),
        () -> assertEquals(storedAttempt.sourceTurnId(), pending.path("sourceTurnId").asText()),
        () -> assertFalse(pending.has("token"), "Initial-only token must be absent from accepted-draft updates."),
        () -> assertEquals(accepted, reader.store.latestRevision(h.chat).orElseThrow()),
        () -> assertEquals(attempt, reader.artifacts.latest(reader.sessions.active(h.chat),
            CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow()),
        () -> assertEquals(source, reader.json.valueToTree(reader.archive.read(h.chat))));
  }

  @Test
  void oldInitialTokenRefusalDirectsTheCallerToUpdateTheAcceptedDraftWithoutAToken() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.call("captureRequirementDraft", Map.of("draft", input("missing-owner")));
    var oldToken = h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token").deepCopy();
    assertTrue(h.call("updateRequirementDraft", Map.of("changes", h.changes(input("entry").facts(), oldToken)))
        .path("accepted").asBoolean());
    var accepted = h.store.latestRevision(h.chat).orElseThrow();
    var acceptedDraft = h.store.get(h.chat).orElseThrow();
    var rejected = h.call("updateRequirementDraft", Map.of("changes", h.changes(input("missing-owner").facts(),
        com.fasterxml.jackson.databind.node.NullNode.getInstance())));
    assertFalse(rejected.path("accepted").asBoolean(), rejected.toString());
    var rejectedAttempt = h.artifacts.latest(h.sessions.active(h.chat),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();

    var refusal = h.call("updateRequirementDraft", Map.of("changes", h.changes(input("entry").facts(), oldToken)));
    var issue = refusal.path("issues").get(0);
    String guidance = issue.path("message").asText();

    assertAll("Initial token submitted with an accepted base",
        () -> assertFalse(refusal.path("accepted").asBoolean(), refusal.toString()),
        () -> assertEquals("PENDING_PROPOSAL_NOT_CURRENT", issue.path("code").asText()),
        () -> assertTrue(guidance.contains("pendingProposalToken"), guidance),
        () -> assertTrue(guidance.toLowerCase(java.util.Locale.ROOT).contains("omit"), guidance),
        () -> assertTrue(guidance.contains("null"), guidance),
        () -> assertTrue(guidance.toLowerCase(java.util.Locale.ROOT).contains("accepted draft"), guidance),
        () -> assertFalse(guidance.contains("Use the exact current rejected initial"), guidance),
        () -> assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow()),
        () -> assertEquals(acceptedDraft, h.store.get(h.chat).orElseThrow()),
        () -> assertEquals(rejectedAttempt,
            h.artifacts.get(rejectedAttempt.compilationId(), rejectedAttempt.reference()).orElseThrow()));
  }

  @Test
  void shouldRepairRejectedCallAliasThroughPendingProposal() throws Exception {
    var h = callHarness();
    var original = callDraft("task-alias", "task", "Task dispatch", false);
    var refused = h.call("captureRequirementDraft", Map.of("draft", original));
    assertEquals(List.of("BUSINESS_ANCHOR_REQUIRED"), refused.path("issues").findValuesAsText("code"));
    var pending = h.call("readRequirementDraft", Map.of()).path("pendingProposal");
    assertEquals(h.json.valueToTree(original), pending.path("draft"));
    var corrected = callDraft("task", "task", "Task dispatch", false);
    var result = h.call("updateRequirementDraft", Map.of("changes", h.changes(
        List.of(corrected.facts().getLast()), pending.path("token"))));
    assertTrue(result.path("accepted").asBoolean(), result.toString());
    assertEquals(corrected, h.store.get(h.chat).orElseThrow().authoredDraft());
  }

  @Test
  void shouldRecoverPersistedCallAliasWithoutGrantingOtherChanges() throws Exception {
    var h = callHarness();
    var original = linkedCallDraft(h, "task-alias", "Task dispatch");
    h.store.put(h.chat, RequirementCaptureProjection.toDraft(original, null, List.of(), "test", "test"));
    var restored = new Harness(h.blobs, h.chat, false);
    var continued = restored.call("finishRequirementDiscoveryTurn", Map.of("directive", "CONTINUE"));
    assertTrue(continued.path("issues").findValuesAsText("code").contains("BUSINESS_ANCHOR_REQUIRED"),
        continued.toString());
    var unchanged = restored.call("updateRequirementDraft", Map.of("changes",
        restored.changes(List.of(), restored.json.nullNode())));
    var messages = unchanged.path("issues").findValuesAsText("message");
    assertTrue(messages.stream().anyMatch(message -> message.contains("Preserve the saved evidence")),
        unchanged.toString());
    assertTrue(messages.stream().filter(message -> message.contains("keep accepted elementIntent unchanged"))
        .allMatch(message -> message.contains("outside authorized repair paths")), unchanged.toString());
    var injected = linkedCallDraft(h, "task", "Alternate dispatch");
    var denied = restored.call("updateRequirementDraft", Map.of("changes", restored.changes(
        injected.facts(), restored.json.nullNode())));
    assertFalse(denied.path("accepted").asBoolean(), denied.toString());
    assertEquals(original, restored.store.get(h.chat).orElseThrow().authoredDraft());
    var corrected = linkedCallDraft(h, "task", "Task dispatch");
    var call = corrected.facts().getLast();
    var withoutEvidence = new FactInput(call.sourceFactId(), call.interactionIds(), call.kind(), call.polarity(),
        call.text(), null, null, call.elementIntent());
    var withExtraTarget = new FactInput(call.sourceFactId(), List.of("task", "entry"), call.kind(), call.polarity(),
        call.text(), null, null, call.elementIntent(), call.evidence());
    for (var mutated : List.of(withoutEvidence, withExtraTarget)) {
      var updates = new java.util.ArrayList<>(corrected.facts());
      updates.set(updates.size() - 1, mutated);
      var rejected = restored.call("updateRequirementDraft", Map.of("changes", restored.changes(
          updates, restored.json.nullNode())));
      assertTrue(rejected.path("issues").findValuesAsText("code").contains("REPAIR_FACT_LOSS"),
          rejected.toString());
      assertEquals(original, restored.store.get(h.chat).orElseThrow().authoredDraft());
    }
    var repaired = restored.call("updateRequirementDraft", Map.of("changes", restored.changes(
        corrected.facts(), restored.json.nullNode())));
    assertTrue(repaired.path("accepted").asBoolean(), repaired.toString());
    assertEquals(corrected, restored.store.get(h.chat).orElseThrow().authoredDraft());
    assertEquals(List.of(), restored.store.validateForContinuation(h.chat));
  }

  @Test
  void shouldRebuildProjectionWhenTheCallAliasRepeats() throws Exception {
    var h = callHarness();
    var arguments = Map.of("draft", callDraft("task-alias", "task", "Task dispatch", false));
    var refused = h.call("captureRequirementDraft", arguments);
    assertEquals(List.of("BUSINESS_ANCHOR_REQUIRED"), refused.path("issues").findValuesAsText("code"));
    int credits = h.fingerprints.softCreditsUsed(h.chat);
    h.call("captureRequirementDraft", arguments);
    var repeated = h.call("captureRequirementDraft", arguments);
    assertEquals("PROJECTION_REBUILD", repeated.path("nextAction").asText(), repeated.toString());
    assertEquals(credits, h.fingerprints.softCreditsUsed(h.chat));
    assertTrue(h.store.get(h.chat).isEmpty(), repeated.toString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullSource
  @org.junit.jupiter.params.provider.ValueSource(strings = {"unknown-owner", "entry"})
  void shouldReportOnlyTheOwnerProblemWhenCallOwnerIsInvalid(String owner) throws Exception {
    var h = callHarness();
    var refused = h.call("captureRequirementDraft", Map.of("draft", callDraft("task-alias", owner,
        "Task dispatch", false)));
    assertEquals(List.of("INVALID_ELEMENT_OWNER"), refused.path("issues").findValuesAsText("code"),
        refused.toString());
  }

  @Test
  void shouldRepairCallWithoutRetargetingAnAliasSharedWithAHook() throws Exception {
    var h = callHarness();
    var bad = linkedCallDraft(h, "task-alias", "Task dispatch");
    var entry = bad.facts().getFirst();
    var hook = new FactInput("hook", List.of("task"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Log the result.", null, null, new RequirementElementIntent("task-alias", "script",
            RequirementElementIntent.Placement.AFTER_HOOK, "task", null, null, null, List.of(), List.of()));
    var original = new DraftInput(bad.flow(), List.of(entry, hook, bad.facts().getLast()),
        bad.capabilities(), bad.openQuestions(), bad.settings());
    h.store.put(h.chat, RequirementCaptureProjection.toDraft(original, null, List.of(), "test", "test"));
    var restored = new Harness(h.blobs, h.chat, false);
    var corrected = linkedCallDraft(h, "task", "Task dispatch");
    var fixed = corrected.facts().getLast();
    var rejected = restored.call("updateRequirementDraft", Map.of("changes", restored.changes(
        List.of(corrected.facts().getFirst(), fixed), restored.json.nullNode())));
    assertAll(
        () -> assertFalse(rejected.path("accepted").asBoolean(), rejected::toString),
        () -> assertEquals(List.of("REPAIR_FACT_LOSS"), rejected.path("issues").findValuesAsText("code")),
        () -> assertEquals(List.of("/changes/facts/" + entry.sourceFactId()),
            rejected.path("issues").findValuesAsText("path")),
        () -> assertEquals(original, restored.store.get(h.chat).orElseThrow().authoredDraft()));

    var result = restored.call("updateRequirementDraft", Map.of("changes", restored.changes(
        List.of(fixed), restored.json.nullNode())));
    assertTrue(result.path("accepted").asBoolean(), result.toString());
    assertEquals(List.of(entry, hook, fixed), restored.store.get(h.chat).orElseThrow().authoredDraft().facts());
  }

  private static DraftInput linkedCallDraft(Harness h, String ref, String name) {
    var base = callDraft(ref, "task", name, true);
    var entry = base.facts().getFirst();
    var linkedEntry = new FactInput(entry.sourceFactId(), entry.interactionIds(), entry.kind(), entry.polarity(),
        entry.text(), null, null, new RequirementElementIntent("entry", "http-trigger",
            RequirementElementIntent.Placement.ANCHOR, "entry", ref, null, null, List.of(), List.of()));
    var call = base.facts().getLast();
    var configured = new FactInput(call.sourceFactId(), call.interactionIds(), call.kind(), call.polarity(),
        call.text(), null, null, new RequirementElementIntent(ref, "service-call", call.elementIntent().placement(),
            "task", null, null, name,
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("retryCount", "1")), List.of()),
        new ClaimEvidenceInput(ConversationArchive.latestInputId(h.archive.read(h.chat)), call.text()));
    return new DraftInput(base.flow(), List.of(linkedEntry, base.facts().get(1), configured),
        base.capabilities(), base.openQuestions(), base.settings());
  }

  private static Harness callHarness() {
    var h = new Harness(new InMemoryArtifactBlobStore());
    String source = "Receive POST /focused with externalRoute=false." + "\nCreate a Salesforce task with the call named Task dispatch. Alternate dispatch is another name. Retry once. "
        + "Log the result. Proceed without IDS.";
    var input = h.archive.beginInput(h.chat, source, null);
    h.archive.resolveInput(h.chat, input, source);
    h.store.beginTurn(h.chat, source);
    return h;
  }

  private static DraftInput callDraft(String ref, String owner, String name, boolean linked) {
    var base = input("entry");
    var flow = new FlowInput(List.of(base.flow().interactions().getFirst(),
        new InteractionInput("task", Direction.OUTBOUND, "Salesforce", "createTask", null, null, null)),
        List.of(new TransitionInput("entry", "task")));
    var facts = new java.util.ArrayList<FactInput>();
    facts.add(base.facts().getFirst());
    if (linked) {
      facts.add(new FactInput("audit", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
          "Log the result.", null, null, new RequirementElementIntent("audit", "log-record",
              RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, ref, null, List.of(), List.of())));
    }
    facts.add(new FactInput("call-name", List.of("task"), FactKind.PARAMETER, Polarity.POSITIVE,
        "Create a Salesforce task with the call named Task dispatch.", null, null,
        new RequirementElementIntent(ref, "service-call", ref.equals(owner)
            ? RequirementElementIntent.Placement.ANCHOR : RequirementElementIntent.Placement.LOCAL_NODE,
            owner, null, null, name, List.of(), List.of())));
    return new DraftInput(flow, facts, base.capabilities(), base.openQuestions(), base.settings());
  }

  @Test
  void repeatedResolvedCandidateCannotRenewCreditByChangingToolOrToken() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var original = input("missing-owner");
    h.call("captureRequirementDraft", Map.of("draft", original));
    int credits = h.fingerprints.softCreditsUsed(h.chat);
    String scope = h.store.captureMutationScope(h.chat);
    var initial = h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token");
    var noticed = h.call("updateRequirementDraft", Map.of("changes", h.changes(original.facts(), initial)));
    assertFalse(noticed.path("accepted").asBoolean());
    assertEquals("REPAIR_CAPTURE", noticed.path("nextAction").asText());
    assertTrue(noticed.path("issues").toString().contains("UNCHANGED_PROPOSAL"));
    var latest = h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token");
    assertNotEquals(initial, latest);
    var rebuilt = h.call("updateRequirementDraft", Map.of("changes", h.changes(original.facts(), latest)));
    assertEquals("PROJECTION_REBUILD", rebuilt.path("nextAction").asText());
    assertThrows(org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException.class,
        () -> h.call("captureRequirementDraft", Map.of("draft", original)));
    assertEquals(credits, h.fingerprints.softCreditsUsed(h.chat));
    assertEquals(scope, h.store.captureMutationScope(h.chat));
    assertTrue(h.store.get(h.chat).isEmpty());
    assertFalse(h.feedback.lastRequirementRejection(h.chat).orElseThrow().feedback().outerAllowed());
  }

  @Test
  void focusedCandidateReloadsWithoutChangingTheRawPatchOrPromotingIt() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.call("captureRequirementDraft", Map.of("draft", input("missing-owner")));
    var pending = h.call("readRequirementDraft", Map.of()).path("pendingProposal");
    var facts = new java.util.ArrayList<>(input("missing-owner").facts());
    var old = facts.getFirst();
    facts.set(0, new FactInput(old.sourceFactId(), old.interactionIds(), old.kind(), old.polarity(),
        old.text() + " Changed.", old.fieldMapping(), old.policyConflict(), old.elementIntent()));
    var patch = h.changes(facts, pending.path("token"));
    var rejected = h.call("updateRequirementDraft", Map.of("changes", patch));
    assertFalse(rejected.path("accepted").asBoolean(), rejected.toString());
    assertTrue(rejected.path("issues").findValuesAsText("code").contains("INVALID_ELEMENT_OWNER"), rejected.toString());
    assertFalse(rejected.path("issues").findValuesAsText("code").contains("REPAIR_FACT_LOSS"), rejected.toString());
    var attempt = h.artifacts.latest(h.sessions.active(h.chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
    assertEquals(patch, attempt.payload().path("arguments"));
    assertEquals(h.json.valueToTree(facts), attempt.payload().path("resolvedCandidate").path("facts"));
    assertEquals(List.of(h.json.treeToValue(pending.path("attempt"), CompilationArtifacts.Reference.class)), attempt.inputs());
    var restored = new Harness(h.blobs, h.chat, false);
    assertEquals(attempt.payload().path("resolvedCandidate"), restored.call("readRequirementDraft", Map.of())
        .path("pendingProposal").path("draft"));
    assertEquals(h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token"),
        restored.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token"));
    assertTrue(restored.store.get(h.chat).isEmpty());
    assertEquals(attempt, restored.artifacts.get(attempt.compilationId(), attempt.reference()).orElseThrow());
    assertEquals(h.fingerprints.softCreditsUsed(h.chat), restored.fingerprints.softCreditsUsed(h.chat));
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(restored.archive);
    var report = RequirementRevisionAdmission.evaluate(h.chat, attempt.reference(), restored.store,
        restored.artifacts, conversations, restored.tools.elementSchemas,
        new RequirementRevisionAdmission.NativeInputs(null, null));
    assertEquals(CaptureProposal.digest(restored.json.treeToValue(attempt.payload().path("resolvedCandidate"),
        DraftInput.class)), report.candidateHash());
    assertTrue(restored.store.get(h.chat).isEmpty(), "Read-only preservation diagnostics cannot admit the snapshot.");
    assertEquals(attempt, restored.artifacts.get(attempt.compilationId(), attempt.reference()).orElseThrow());
    assertEquals(h.fingerprints.softCreditsUsed(h.chat), restored.fingerprints.softCreditsUsed(h.chat));
  }

  @Test
  void initialPolicyDecisionsStillRequirePersistedQuestionAndNewAuthorAnswer() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    h.call("captureRequirementDraft", Map.of("draft", input("missing-owner")));
    var pending = h.call("readRequirementDraft", Map.of()).path("pendingProposal");
    var patch = h.changes(input("entry").facts(), pending.path("token"));
    var policy = new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Retain the requested access policy.", null,
        new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4", "accessControlType", "NONE", "RBAC", "choice",
            RequirementPolicyConflict.Decision.KEEP_REQUESTED), null);
    patch.set("addFacts", h.json.valueToTree(List.of(policy)));
    var result = h.call("updateRequirementDraft", Map.of("changes", patch));
    assertFalse(result.path("accepted").asBoolean());
    assertTrue(result.path("issues").findValuesAsText("code").contains("POLICY_RESPONSE_REQUIRED"), result.toString());
    assertTrue(h.store.get(h.chat).isEmpty());
  }

  @Test
  void providerDescribesTypedCopyOriginAndExposesItsRequiredSourceFields() {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var spec = h.provider.toolSpecificationByName("captureRequirementDraft");
    String description = spec.description();
    assertAll("Typed COPY provider contract",
        () -> assertTrue(description.contains("fieldMapping.value"), description),
        () -> assertTrue(description.contains("source.interactionId"), description),
        () -> assertTrue(description.contains("source.port"), description),
        () -> assertTrue(description.contains("source.path"), description),
        () -> assertTrue(description.contains("compiler derives retention"), description),
        () -> assertFalse(description.contains("Retain earlier event fields through BEHAVIOR"), description),
        () -> assertFalse(description.contains("expression explicitly reading the saved original field"), description));
    var draft = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class,
        spec.parameters().properties().get("draft"));
    var facts = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonArraySchema.class,
        draft.properties().get("facts"));
    var fact = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class, facts.items());
    var nullableMapping = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonAnyOfSchema.class,
        fact.properties().get("fieldMapping"));
    var mapping = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class,
        nullableMapping.anyOf().getFirst());
    var nullableValue = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonAnyOfSchema.class,
        mapping.properties().get("value"));
    var value = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class,
        nullableValue.anyOf().getFirst());
    var source = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class,
        value.properties().get("source"));
    assertAll("Typed COPY provider schema",
        () -> assertEquals(List.of("operation", "source"), value.required()),
        () -> assertEquals(List.of("kind", "interactionId", "port", "path"), source.required()),
        () -> assertFalse(mapping.required().contains("value"), mapping.required().toString()),
        () -> assertFalse(mapping.properties().containsKey("missingSourcePolicy"), mapping.properties().toString()));
  }

  @Test
  void schemaKeepsLegacyRequiredFieldsWithOptionalTokensAndTypedMappingValue() {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var changes = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class,
        h.provider.toolSpecificationByName("updateRequirementDraft").parameters().properties().get("changes"));
    var expected = new java.util.HashSet<>(changes.properties().keySet());
    expected.remove("pendingProposalToken");
    expected.remove("clarificationDisposition");
    assertEquals(expected, java.util.Set.copyOf(changes.required()));
    var pending = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonAnyOfSchema.class,
        changes.properties().get("pendingProposalToken"));
    assertTrue(pending.anyOf().stream().anyMatch(dev.langchain4j.model.chat.request.json.JsonNullSchema.class::isInstance));
    var disposition = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonAnyOfSchema.class,
        changes.properties().get("clarificationDisposition"));
    assertTrue(disposition.anyOf().stream()
        .anyMatch(dev.langchain4j.model.chat.request.json.JsonNullSchema.class::isInstance));
    assertTrue(disposition.anyOf().stream()
        .anyMatch(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class::isInstance));
    assertEquals(1, pending.anyOf().stream()
        .filter(dev.langchain4j.model.chat.request.json.JsonStringSchema.class::isInstance).count());
    assertFalse(changes.properties().containsKey("pendingAttempt"));
    assertFalse(h.json.valueToTree(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null)).has("pendingProposalToken"));
    assertFalse(changes.properties().containsKey("resolvedCandidate"));
    var legacy = new RequirementDraftStore.RequirementCaptureAttempt(null, null, null, "scope", "captureRequirementDraft",
        input("entry"), false, List.of(), null);
    assertFalse(h.json.valueToTree(legacy).has("resolvedCandidate"));
    assertRequiredFields(h.provider.toolSpecificationByName("captureRequirementDraft").parameters(), false);
    assertRequiredFields(h.provider.toolSpecificationByName("updateRequirementDraft").parameters(), true);
  }

  private static void assertRequiredFields(dev.langchain4j.model.chat.request.json.JsonSchemaElement schema,
      boolean update) {
    if (schema instanceof dev.langchain4j.model.chat.request.json.JsonObjectSchema object) {
      var keys = new java.util.HashSet<>(object.properties().keySet());
      if (update) {
        keys.remove("pendingProposalToken");
        keys.remove("clarificationDisposition");
      }
      var required = java.util.Set.copyOf(object.required());
      if (keys.containsAll(java.util.Set.of("sourceInteractionId", "targetInteractionId", "targetPath", "value"))) {
        assertFalse(required.contains("value"), "Legacy field mappings may omit the typed value.");
        keys.remove("value");
      }
      if (!required.contains("evidence")) keys.remove("evidence");
      if (!required.contains("ambiguity")) keys.remove("ambiguity");
      assertEquals(keys, required);
      object.properties().values().forEach(child -> assertRequiredFields(child, update));
    } else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonArraySchema array) {
      assertRequiredFields(array.items(), update);
    } else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonAnyOfSchema any) {
      any.anyOf().forEach(child -> assertRequiredFields(child, update));
    }
  }

  @Test
  void chainIntentCanLeaveEntryWithoutRepairFactLoss() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var rejected = h.call("captureRequirementDraft", Map.of("draft", chainDraft("entry", "missing-owner")));
    assertFalse(rejected.path("accepted").asBoolean(), rejected.toString());
    assertTrue(rejected.path("issues").findValuesAsText("code").contains("INVALID_ELEMENT_OWNER"), rejected.toString());
    var repaired = h.call("captureRequirementDraft", Map.of("draft", chainDraft("chain", "entry")));
    assertTrue(repaired.path("accepted").asBoolean(), repaired.toString());
    assertFalse(repaired.path("issues").findValuesAsText("code").contains("REPAIR_FACT_LOSS"), repaired.toString());
    assertEquals("chain", h.store.get(h.chat).orElseThrow().authoredDraft().facts().stream()
        .filter(fact -> "chain-name".equals(fact.sourceFactId())).findFirst().orElseThrow()
        .elementIntent().elementRef());
  }

  @Test
  void rejectedLogRecordPredecessorCanChangeWithoutRepairFactLoss() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var original = logDraft("entry", "missing-owner");
    assertFalse(h.json.writeValueAsString(original).contains("chain-call"), original.toString());
    assertFalse(h.json.writeValueAsString(original).contains("service-call"), original.toString());
    var rejected = h.call("captureRequirementDraft", Map.of("draft", original));
    assertFalse(rejected.path("accepted").asBoolean(), rejected.toString());
    assertTrue(rejected.path("issues").findValuesAsText("code").contains("INVALID_ELEMENT_OWNER"), rejected.toString());
    var repaired = h.call("captureRequirementDraft", Map.of("draft", logDraft(null, "entry")));
    assertTrue(repaired.path("accepted").asBoolean(), repaired.toString());
    assertFalse(repaired.path("issues").findValuesAsText("code").contains("REPAIR_FACT_LOSS"), repaired.toString());
  }

  static DraftInput chainDraft(String chainRef, String localOwner) {
    var chain = new FactInput("chain-name", List.of(), FactKind.PARAMETER, Polarity.POSITIVE,
        "The chain name is entry.", null, null,
        new RequirementElementIntent(chainRef, "chain", RequirementElementIntent.Placement.CHAIN,
            null, null, null, null, "entry", List.of(), List.of()));
    var facts = new java.util.ArrayList<>(input(localOwner).facts());
    facts.add(chain);
    var base = input(localOwner);
    return new DraftInput(base.flow(), facts, base.capabilities(), base.openQuestions(), base.settings());
  }

  static DraftInput logDraft(String predecessor, String brokenOwner) {
    var audit = new FactInput("audit", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "After entry, log the result without changing the body.", null, null,
        new RequirementElementIntent("audit", "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
            "entry", null, predecessor, null, null, List.of(), List.of()));
    var broken = new FactInput("local", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "After entry, log the result without changing the body.", null, null,
        new RequirementElementIntent("local", "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
            brokenOwner, null, "entry", null, null, List.of(), List.of()));
    var base = input("entry");
    return new DraftInput(base.flow(), List.of(base.facts().getFirst(), audit, broken),
        base.capabilities(), base.openQuestions(), base.settings());
  }

  static DraftInput input(String owner) {
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Receive POST /focused with externalRoute=false.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(), List.of()));
    var local = new FactInput("local", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "After entry, log the result without changing the body.", null, null,
        new RequirementElementIntent("local", "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
            owner, null, "entry", null, List.of(), List.of()));
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /focused", null, null, null)), List.of()), List.of(endpoint, local),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/focused", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  static final class Harness {
    final String chat;
    final InMemoryArtifactBlobStore blobs;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final CompilationArtifacts artifacts;
    final CompilationSessions sessions;
    final ConversationArchive archive;
    final RequirementDraftStore store;
    final ToolCallFingerprintStore fingerprints;
    final CaptureAttemptFeedbackStore feedback;
    final RequirementCaptureTools tools;
    final ToolProviderResult provider;
    Harness(InMemoryArtifactBlobStore blobs) { this(blobs, "focused-initial-" + UUID.randomUUID(), true); }
    Harness(InMemoryArtifactBlobStore blobs, String chat, boolean begin) {
      this.blobs = blobs; this.chat = chat;
      artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
      sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
      archive = new ConversationArchive(blobs, json);
      var conversations = mock(ConversationService.class); when(conversations.archive()).thenReturn(archive);
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      fingerprints = new ToolCallFingerprintStore(json, artifacts, sessions);
      feedback = new CaptureAttemptFeedbackStore(fingerprints, artifacts, sessions);
      tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), json,
          new CaptureToolOutcomeGateway(fingerprints, feedback), feedback, null);
      tools.elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService.createForUnitTests(json);
      provider = new RequirementCaptureToolProvider(tools, json).provideTools(null);
      String source = input("entry").facts().stream().map(FactInput::text).collect(java.util.stream.Collectors.joining("\n"))
          + "\nProceed without IDS.";
      if (begin) { var ref = archive.beginInput(chat, source, null); archive.resolveInput(chat, ref, source); }
      store.beginTurn(chat, source);
    }
    JsonNode call(String tool, Object arguments) throws Exception {
      return json.readTree(provider.toolExecutorByName(tool).execute(ToolExecutionRequest.builder()
          .id("call-" + UUID.randomUUID()).name(tool).arguments(json.writeValueAsString(arguments)).build(), chat));
    }
    ObjectNode changes(List<FactInput> facts, JsonNode attempt) {
      var changes = json.<ObjectNode>valueToTree(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), facts, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of()));
      changes.set("pendingProposalToken", attempt);
      return changes;
    }
  }
}
