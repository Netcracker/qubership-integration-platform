package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCapabilityConsistencyTest {
  private static final String SOURCE = "Receive POST /work. Invoke the existing catalog operation. "
      + "Process work. Do not add native senders. No IDS.";
  private static final String QUOTE = "Do not add native senders.";
  private static final String CONFLICT = "CAPABILITY_ANCHOR_CONFLICT";
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void nativeCaptureRejectsTheContradictionBeforeSavingAndRetainsTheSourceAnchor() throws Exception {
    var h = new Harness(); h.begin();
    try (var ignored = ToolSession.open(h.chat)) {
      var refused = h.capture(input(true));
      assertFalse(refused.accepted(), refused.toString());
      assertTrue(refused.issues().stream().anyMatch(issue -> CONFLICT.equals(issue.code())
          && "/draft/capabilities/remote/capabilityKey".equals(issue.path())
          && issue.message().contains("remoteAnchor") && issue.message().contains("/capabilities/remote")), refused.toString());
      assertTrue(h.store.get(h.chat).isEmpty());
      var attempt = h.artifacts.latest(h.compilation(), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      assertEquals("http-sender", attempt.payload().at("/arguments/capabilities/1/capabilityKey").asText());
      assertNull(h.store.repairConstraints(h.chat));
      assertTrue(h.capture(input(false)).accepted());
      assertEquals(input(true).facts(), h.store.get(h.chat).orElseThrow().authoredDraft().facts());
      h.resolveRemote();
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
      assertEquals(attempt, h.artifacts.get(h.compilation(), attempt.reference()).orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"matched", "missing", "negative", "local", "foreign", "wrong-ref", "contradictory-types"})
  void missingOrAmbiguousPositiveOwnedAnchorTypesCannotSelectAContradictionRepair(String variant) throws Exception {
    var tree = json.<ObjectNode>valueToTree(input(true));
    switch (variant) {
      case "matched" -> ((ObjectNode) tree.at("/facts/2/elementIntent")).put("elementType", "http-sender");
      case "missing" -> ((ObjectNode) tree.at("/facts/2")).putNull("elementIntent");
      case "negative" -> ((ObjectNode) tree.at("/facts/2")).put("polarity", "NEGATIVE");
      case "local" -> ((ObjectNode) tree.at("/facts/2/elementIntent")).put("placement", "LOCAL_NODE").put("elementRef", "helper");
      case "foreign" -> ((ObjectNode) tree.at("/facts/2/elementIntent")).put("ownerInteractionId", "entry");
      case "wrong-ref" -> ((ObjectNode) tree.at("/facts/2/elementIntent")).put("elementRef", "helper");
      case "contradictory-types" -> {
        var other = ((ObjectNode) tree.at("/facts/2")).deepCopy(); other.put("sourceFactId", "otherAnchor");
        ((ObjectNode) other.path("elementIntent")).put("elementType", "http-sender");
        ((com.fasterxml.jackson.databind.node.ArrayNode) tree.path("facts")).add(other);
      }
      default -> throw new AssertionError(variant);
    }
    assertTrue(RequirementCaptureEditor.validate(json.treeToValue(tree, DraftInput.class)).stream()
        .noneMatch(issue -> CONFLICT.equals(issue.code())), variant);
  }

  @Test
  void multipleAgreeingAnchorFactsCannotHideTheContradictoryCapability() throws Exception {
    var h = new Harness(); h.begin();
    var input = agreeingInput();
    try (var ignored = ToolSession.open(h.chat)) {
      var refused = h.capture(input);
      assertFalse(refused.accepted());
      assertTrue(refused.issues().stream().anyMatch(issue -> CONFLICT.equals(issue.code())), refused.toString());
      assertNull(h.store.repairConstraints(h.chat));
      var corrected = new DraftInput(input.flow(), input.facts(), input(false).capabilities(), input.openQuestions(), input.settings());
      assertTrue(h.capture(corrected).accepted());
      assertEquals(input.facts(), h.store.get(h.chat).orElseThrow().authoredDraft().facts());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void legacyAcceptedContradictionUsesTheExistingSourceLinkedAmendmentWithoutRewritingFacts(boolean reload) throws Exception {
    var h = new Harness(); var source = h.begin();
    var original = input(true);
    h.store.put(h.chat, RequirementCaptureProjection.toDraft(original, null, List.of(), "1", "hash"));
    var accepted = h.store.latestRevision(h.chat).orElseThrow();
    if (reload) { h.reload(); h.store.beginTurn(h.chat, SOURCE); }
    try (var ignored = ToolSession.open(h.chat)) {
      var continued = h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE));
      assertFalse(continued.accepted());
      assertTrue(continued.issues().stream().anyMatch(issue -> CONFLICT.equals(issue.code())), continued.toString());
      assertTrue(continued.issues().stream().noneMatch(issue -> "MISSING_ANCHOR_INTENT".equals(issue.code())), continued.toString());
      var nulling = new CapabilityInput("remote", "http-sender", null, null, null, null, null, null);
      var cleanup = h.update(update(List.of(nulling), List.of(), List.of()));
      assertFalse(cleanup.accepted());
      assertTrue(cleanup.issues().stream().anyMatch(issue -> CONFLICT.equals(issue.code())), cleanup.toString());
      var unamended = h.update(update(List.of(), List.of("remote"), List.of()));
      assertFalse(unamended.accepted());
      assertTrue(unamended.issues().stream().anyMatch(issue -> "AUTHOR_AMENDMENT_REQUIRED".equals(issue.code())));
      assertTrue(unamended.issues().stream().anyMatch(issue -> CONFLICT.equals(issue.code())
          && issue.message().contains("/capabilities/remote")), unamended.toString());
      assertEquals(accepted.reference(), h.store.latestRevision(h.chat).orElseThrow().reference());
      var amended = h.update(update(List.of(), List.of("remote"), List.of(
          new AmendmentInput("/capabilities/remote", source.turnId(), QUOTE))));
      assertTrue(amended.accepted(), amended.toString());
      assertEquals(input(false), h.store.get(h.chat).orElseThrow().authoredDraft());
      assertEquals(accepted, h.artifacts.get(h.compilation(), accepted.reference()).orElseThrow());
      h.resolveRemote();
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"quote", "consumed", "foreign-path", "fact", "method", "source"})
  void legacyCorrectionHasNoImplicitAuthorityOverAcceptedOrUnrelatedRequirements(String variant) throws Exception {
    var h = new Harness(); var source = h.begin();
    h.store.put(h.chat, RequirementCaptureProjection.toDraft(input(true), null, List.of(), "1", "hash"));
    if (variant.equals("consumed")) {
      h.archive.consume(h.chat, h.archive.read(h.chat).generation(), List.of(source.turnId()), "old");
      h.reload(); h.store.beginTurn(h.chat, SOURCE);
    }
    if (variant.equals("source")) {
      var newer = h.archive.beginInput(h.chat, "Unrelated source.", null); h.archive.resolveInput(h.chat, newer, "Unrelated source.");
    }
    var changes = json.<ObjectNode>valueToTree(update(List.of(), List.of("remote"), List.of(new AmendmentInput(
        variant.equals("foreign-path") ? "/capabilities/entry" : "/capabilities/remote", source.turnId(),
        variant.equals("quote") ? "An invented quote." : QUOTE))));
    if (variant.equals("fact")) {
      var fact = json.<ObjectNode>valueToTree(input(true).facts().getLast()); fact.put("text", "Invented behavior.");
      changes.withArray("updateFacts").add(fact);
    }
    if (variant.equals("method")) changes.withArray("setCapabilities").add(json.valueToTree(
        new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/work", null, null, null)));
    try (var ignored = ToolSession.open(h.chat)) {
      var refused = h.update(json.treeToValue(changes, DraftUpdate.class));
      assertFalse(refused.accepted(), variant + ": " + refused);
      assertEquals(input(true), h.store.get(h.chat).orElseThrow().authoredDraft());
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void storedProtectionLoadsWithoutRewritingARejectedCapability(boolean reload, boolean agreeing) throws Exception {
    var h = new Harness(); h.begin();
    var input = agreeing ? agreeingInput() : input(true);
    var legacy = h.seedLegacyPending(agreeing ? "agreeing" : "valid");
    var planted = h.artifacts.latest(h.compilation(), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION).orElseThrow();
    h.reload(); h.store.beginTurn(h.chat, SOURCE);
    if (reload) { h.reload(); h.store.beginTurn(h.chat, SOURCE); }
    assertTrue(h.store.repairConstraints(h.chat).values().path("capabilities").has("remote"),
        "A stored protection payload is loaded without rewriting a rejected capability");
    assertTrue(h.store.repairConstraints(h.chat).facts().contains(input(true).facts().getLast()));
    var protection = h.artifacts.latest(h.compilation(), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION).orElseThrow();
    assertEquals(planted.artifactId(), protection.artifactId());
    assertEquals(planted.payload(), protection.payload());
    assertEquals(legacy, h.artifacts.get(h.compilation(), legacy.reference()).orElseThrow());
    try (var ignored = ToolSession.open(h.chat)) {
      var corrected = new DraftInput(input.flow(), input.facts(), input(false).capabilities(), input.openQuestions(), input.settings());
      assertTrue(h.capture(corrected).accepted());
      assertEquals(input.facts(), h.store.get(h.chat).orElseThrow().authoredDraft().facts());
      h.resolveRemote();
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pendingCorrectionRetainsAnAcceptedPartialBaseAndItsOriginalHistory(boolean reload) throws Exception {
    var h = new Harness(); var source = h.begin();
    var original = input(false);
    var initial = new DraftInput(original.flow(), original.facts().stream().map(fact ->
        fact.sourceFactId().equals("entryAnchor") ? fact : new FactInput(fact.sourceFactId(), fact.interactionIds(),
            fact.kind(), fact.polarity(), fact.text(), fact.fieldMapping(), fact.policyConflict(), fact.elementIntent(),
            new ClaimEvidenceInput(source.turnId(), fact.text()))).toList(), original.capabilities(),
        original.openQuestions(), original.settings());
    var partial = new DraftInput(new FlowInput(List.of(initial.flow().interactions().getFirst()), List.of()),
        List.of(initial.facts().get(1)), initial.capabilities(), List.of(), initial.settings());
    h.store.put(h.chat, RequirementCaptureProjection.toDraft(partial, null, List.of(), "1", "hash"));
    var base = h.store.latestRevision(h.chat).orElseThrow();
    h.seedLegacyPending("valid");
    h.reload(); h.store.beginTurn(h.chat, SOURCE);
    if (reload) { h.reload(); h.store.beginTurn(h.chat, SOURCE); }
    assertTrue(h.store.repairConstraints(h.chat).values().path("capabilities").has("remote"));
    var changes = new DraftUpdate(List.of(initial.flow().interactions().getLast()), List.of(), List.of(),
        initial.flow().transitions(), List.of(), List.of(initial.facts().getFirst(), initial.facts().getLast()),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    try (var ignored = ToolSession.open(h.chat)) {
      var accepted = h.update(changes);
      assertTrue(accepted.accepted(), accepted.toString());
      assertEquals(base, h.artifacts.get(h.compilation(), base.reference()).orElseThrow());
      assertTrue(h.store.get(h.chat).orElseThrow().authoredDraft().facts().containsAll(partial.facts()));
      assertEquals(initial.flow(), h.store.get(h.chat).orElseThrow().authoredDraft().flow());
      h.resolveRemote();
      assertTrue(h.result(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE)).accepted());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hashless", "hash", "base", "source", "owner", "value", "anchor"})
  void incompleteOrMismatchedPendingProofCannotReleaseValues(String variant) throws Exception {
    var h = new Harness(); h.begin(); h.seedLegacyPending(variant);
    h.reload(); h.store.beginTurn(h.chat, SOURCE);
    assertTrue(h.store.repairConstraints(h.chat).values().path("capabilities").has("remote"), variant);
  }

  private static DraftInput agreeingInput() {
    var input = input(true);
    var facts = new java.util.ArrayList<>(input.facts());
    var anchor = input.facts().getLast().elementIntent();
    facts.add(new FactInput("remoteBehavior", List.of("remote"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Preserve the call response.", null, null, new RequirementElementIntent(anchor.elementRef(), anchor.elementType(),
            anchor.placement(), anchor.ownerInteractionId(), null, null, null, null,
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("errorThrowing", "false")), List.of())));
    return new DraftInput(input.flow(), facts, input.capabilities(), input.openQuestions(), input.settings());
  }

  static DraftInput input(boolean conflict) {
    var entry = new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /work", null, null, null);
    var remote = new InteractionInput("remote", Direction.OUTBOUND, "Remote Catalog", "Process work", null, null, null);
    var anchor = new RequirementElementIntent("remote", "service-call", RequirementElementIntent.Placement.ANCHOR,
        "remote", null, null, null, null, List.of(), List.of());
    return new DraftInput(new FlowInput(List.of(entry, remote), List.of(new TransitionInput("entry", "remote"))), List.of(
        new FactInput("goal", List.of("entry", "remote"), FactKind.BEHAVIOR, Polarity.POSITIVE, "Process work.", null, null),
        new FactInput("entryAnchor", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE, "Receive POST /work.", null, null,
            new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
                "entry", null, null, null, null, List.of(), List.of())),
        new FactInput("remoteAnchor", List.of("remote"), FactKind.PARAMETER, Polarity.POSITIVE,
            "Invoke the existing catalog operation.", null, null, anchor)), conflict ? List.of(
        new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/work", null, null, null),
        new CapabilityInput("remote", "http-sender", null, HttpMethod.POST, "/derived", null, null, null)) : List.of(
        new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/work", null, null, null)),
        List.of(), new DraftSettings(false, null));
  }

  static DraftUpdate update(List<CapabilityInput> capabilities, List<String> remove, List<AmendmentInput> amendments) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        capabilities, remove, List.of(), List.of(), List.of(), null, amendments);
  }

  private static final class Harness {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final ConversationArchive archive = new ConversationArchive(blobs, json);
    final ConversationService conversations = mock(ConversationService.class);
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    final String chat = UUID.randomUUID().toString();
    RequirementDraftStore store;
    RequirementDraftTool bindings;
    RequirementCaptureTools tools;

    Harness() { when(conversations.archive()).thenReturn(archive); reload(); }
    void reload() {
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      bindings = mock(RequirementDraftTool.class);
      when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
      tools = new RequirementCaptureTools(store, bindings, json);
    }
    ConversationArchive.Input begin() {
      var input = archive.beginInput(chat, SOURCE, null); archive.resolveInput(chat, input, SOURCE);
      store.beginTurn(chat, SOURCE); return input;
    }
    String compilation() { return store.activeCompilationId(chat); }
    RequirementCaptureResult result(String value) throws Exception { return json.readValue(value, RequirementCaptureResult.class); }
    RequirementCaptureResult capture(DraftInput input) throws Exception { return result(tools.captureRequirementDraft(input)); }
    RequirementCaptureResult update(DraftUpdate input) throws Exception { return result(tools.updateRequirementDraft(input)); }
    void resolveRemote() throws Exception {
      when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of(
          new CatalogBindingHint("3", "remote", "remote", "Process work", "system", "group", "spec", "operation",
              "http", "POST", "/process", "2024.4", Instant.EPOCH, "catalog-evidence")));
      assertTrue(result(tools.readRequirementDraft()).accepted());
    }
    CompilationArtifacts.Revision seedLegacyPending(String variant) {
      var source = archive.read(chat); var turn = ConversationArchive.latestInputId(source);
      var input = variant.equals("agreeing") ? agreeingInput() : input(true);
      var values = RequirementRevisionAdmission.valuesToPreserve(input, List.of());
      if (variant.equals("value")) values.withObject("/capabilities/remote").put("path", "/different");
      var facts = input.facts();
      if (variant.equals("anchor")) facts = facts.subList(0, facts.size() - 1);
      artifacts.append(new CompilationArtifacts.AppendCommand(compilation(), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION,
          "1", "requirement-capture-protection", "1", new RequirementDraftStore.CaptureFactProtection(
              source.generation(), turn, null, facts, values,
              store.get(chat).map(RequirementDraft::authoredDraft).map(CaptureProposal::digest).orElse(null)), List.of(), null));
      var raw = json.<ObjectNode>valueToTree(input);
      if (variant.equals("owner")) ((ObjectNode) raw.at("/facts/2/elementIntent")).put("ownerInteractionId", "entry");
      return artifacts.append(new CompilationArtifacts.AppendCommand(compilation(), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT,
          "1", "requirement-capture-tools", "1", new RequirementDraftStore.RequirementCaptureAttempt(
              source.generation(), variant.equals("source") ? "foreign-source" : turn, null, store.captureMutationScope(chat),
              "captureRequirementDraft", raw, false,
              List.of(new RequirementCaptureResult.CaptureIssue("MISSING_FIELD", "/draft/openQuestions", null, null,
                  "A different legacy finding.")),
              variant.equals("base") ? new CompilationArtifacts.Reference(CompilationArtifacts.Kind.REQUIREMENT_DRAFT, "foreign", "hash")
                  : store.latestRevision(chat).map(CompilationArtifacts.Revision::reference).orElse(null),
              CaptureProposal.digest(Map.of()), null, variant.equals("hashless") ? null : variant.equals("hash") ? "wrong"
                  : CaptureProposal.digest(AuthorSourceEvidence.packet(source))), List.of(), null));
    }
  }
}
