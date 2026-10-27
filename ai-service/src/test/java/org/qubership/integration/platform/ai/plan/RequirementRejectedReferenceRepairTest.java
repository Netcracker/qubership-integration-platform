package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementRejectedReferenceRepairTest {
  static final String SOURCE = "Accept POST /work with trigger Receive work. Then Prepare work sets the response body. "
      + "Audit work logs audit-item before Prepare work when requested. Keep internal route externalRoute=false and exact ready literal, names, settings, and sequence. No IDS.";
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void rejectedReferenceHistoryDoesNotCreateProtection(boolean predecessor, boolean reload) throws Exception {
    var h = new Harness();
    var fixture = new Fixture(predecessor);
    try (var ignored = ToolSession.open(h.chat)) {
      h.reject(fixture.original());
      assertTrue(h.protectionHistory().isEmpty());
      var credits = h.artifacts.latest(h.sessions.active(h.chat), CompilationArtifacts.Kind.CAPTURE_CREDITS).orElseThrow();
      var refusal = h.reject(fixture.proof());
      assertTrue(refusal.path("issues").toString().contains("UNKNOWN_ELEMENT_REFERENCE"), refusal.toString());
      assertTrue(h.protectionHistory().isEmpty(), "A rejected reference does not become repair authority");
      var retainedCredits = h.artifacts.latest(h.sessions.active(h.chat), CompilationArtifacts.Kind.CAPTURE_CREDITS)
          .orElseThrow().payload().path("fingerprints");
      for (var fingerprint : credits.payload().path("fingerprints"))
        assertTrue(java.util.stream.StreamSupport.stream(retainedCredits.spliterator(), false).anyMatch(fingerprint::equals));
      var raw = h.artifacts.latest(h.sessions.active(h.chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      assertEquals(JSON.valueToTree(fixture.proof()), raw.payload().path("arguments"));
      assertFalse(raw.payload().path("accepted").asBoolean());
      if (reload) h.reload();
      var accepted = h.call(fixture.corrected());
      assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
      assertEquals(fixture.corrected(), h.store.get(h.chat).orElseThrow().authoredDraft());
      assertTrue(h.protectionHistory().isEmpty());
      assertTrue(JSON.readTree(h.tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE))
          .path("accepted").asBoolean());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "foreign-target", "name", "text", "property", "literal", "target-id", "fact-id", "order"})
  void rejectedHistoryDoesNotRewriteTheSubmittedCandidate(String mutation) throws Exception {
    var h = new Harness();
    var fixture = new Fixture(false);
    try (var ignored = ToolSession.open(h.chat)) {
      h.reject(fixture.original()); h.reject(fixture.proof());
      var changed = (ObjectNode) JSON.valueToTree(fixture.corrected());
      var owner = (ObjectNode) changed.at("/facts/1");
      var target = (ObjectNode) changed.at("/facts/2");
      switch (mutation) {
        case "null" -> ((ObjectNode) owner.path("elementIntent")).putNull("successorElementRef");
        case "foreign-target" -> ((ObjectNode) owner.path("elementIntent")).put("successorElementRef", "entry");
        case "name" -> ((ObjectNode) target.path("elementIntent")).put("requestedName", "Receive work");
        case "text" -> target.put("text", "Prepare work sets a different body.");
        case "property" -> ((ObjectNode) owner.at("/elementIntent/requestedProperties/0")).put("value", "true");
        case "literal" -> ((com.fasterxml.jackson.databind.node.ArrayNode) target.at("/elementIntent/exactValues")).set(0, JSON.getNodeFactory().textNode("work"));
        case "target-id" -> ((ObjectNode) target.path("elementIntent")).put("elementRef", "replacement");
        case "fact-id" -> target.put("sourceFactId", "replacement");
        case "order" -> ((ObjectNode) target.path("elementIntent")).putNull("predecessorElementRef");
        default -> throw new AssertionError(mutation);
      }
      var submitted = JSON.convertValue(changed, DraftInput.class);
      var result = h.call(submitted);
      assertTrue(h.protectionHistory().isEmpty());
      if (result.path("accepted").asBoolean()) {
        assertEquals(submitted, h.store.get(h.chat).orElseThrow().authoredDraft());
      } else {
        assertTrue(h.store.get(h.chat).isEmpty());
      }
    }
  }

  @org.junit.jupiter.api.Test
  void rejectedHistoryDoesNotRewriteSuppliedFacts() {
    var facts = new Fixture(false).proof().facts();
    var repair = RejectedReferenceRepair.repair(facts);
    assertEquals(facts, repair.facts());
    assertTrue(repair.evidenceSequences().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"turn", "generation", "source-content", "base", "authority"})
  void staleSourceBaseOrAuthorityCannotReuseNativeReferenceEvidence(String mutation) throws Exception {
    var h = new Harness(); var fixture = new Fixture(false);
    try (var ignored = ToolSession.open(h.chat)) {
      h.reject(fixture.original());
      assertTrue(h.protectionHistory().isEmpty());
      if (mutation.equals("turn")) {
        var input = h.archive.beginInput(h.chat, "Retain the requirements.", null);
        h.archive.resolveInput(h.chat, input, "Retain the requirements.");
      } else if (mutation.equals("generation")) {
        var config = mock(org.qubership.integration.platform.ai.configuration.AppConfig.class, RETURNS_DEEP_STUBS);
        when(config.conversation().idleTimeout()).thenReturn(java.time.Duration.ofHours(1));
        new ConversationService(config, h.archive).clearMessages(h.chat);
        var input = h.archive.beginInput(h.chat, SOURCE, null); h.archive.resolveInput(h.chat, input, SOURCE);
      } else if (mutation.equals("source-content")) {
        var current = h.archive.read(h.chat);
        h.archive.resolveInput(h.chat, new ConversationArchive.Input(ConversationArchive.latestInputId(current),
            current.generation(), 0), SOURCE + " Preserve one additional source condition.");
      } else if (mutation.equals("authority")) {
        org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
            .bindDiscovery("other-repair-authority", h.chat, value -> {});
      } else {
        var accepted = RequirementCaptureProjection.toDraft(fixture.corrected(), null, List.of(), null, null);
        h.artifacts.append(new CompilationArtifacts.AppendCommand(h.sessions.active(h.chat),
            CompilationArtifacts.Kind.REQUIREMENT_DRAFT, "2", "requirement-draft-store", "1", accepted, List.of(), null));
        var current = h.store.latestRevision(h.chat).orElseThrow();
        var oldProof = new RequirementCaptureResult(false, false, null, RequirementCaptureResult.Readiness.EMPTY,
            RequirementCaptureResult.NextAction.REPAIR_CAPTURE,
            List.of(issue("UNKNOWN_ELEMENT_REFERENCE", "/draft/facts/owner/elementIntent/successorElementRef", "owner")),
            List.of(), null, List.of(), List.of());
        h.store.recordCaptureOutcome(h.chat, "captureRequirementDraft", fixture.proof(), oldProof);
        assertEquals(current.reference(), h.store.latestRevision(h.chat).orElseThrow().reference());
        assertTrue(h.protectionHistory().isEmpty());
        return;
      }
      h.reject(fixture.proof());
      assertTrue(h.protectionHistory().isEmpty());
      assertTrue(h.store.get(h.chat).isEmpty());
    } finally {
      org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void accumulatedReferenceConstraintsRetainVerifiedBodyBinding(boolean alreadyBound) {
    var fixture = new Fixture(false);
    var body = new RequirementBodyBinding(RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY, "prepare-step", "script",
        RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT, "generation", "turn", 0, 10, RequirementBodyBinding.Encoding.RAW);
    var old = (ObjectNode) JSON.valueToTree(fixture.corrected().facts().get(2));
    var next = old.deepCopy();
    if (alreadyBound) ((ObjectNode) old.path("elementIntent")).set("bodyBinding", JSON.valueToTree(body));
    ((ObjectNode) next.path("elementIntent")).set("bodyBinding", JSON.valueToTree(alreadyBound
        ? new RequirementBodyBinding(RequirementBodyBinding.Authority.GENERATED_CANDIDATE, "prepare-step", "script",
            RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT, null, null, null, null, null) : body));
    var retained = RequirementRevisionAdmission.accumulate(List.of(JSON.convertValue(old, FactInput.class)),
        List.of(JSON.convertValue(next, FactInput.class)));
    assertEquals(body, retained.getFirst().elementIntent().bodyBinding());
  }

  @org.junit.jupiter.api.Test
  void hashlessHistoricalAttemptsCannotAuthorizeANewReferenceRepair() throws Exception {
    var h = new Harness(); var fixture = new Fixture(false);
    var source = h.archive.read(h.chat);
    h.store.rememberRejectedCaptureFacts(h.chat, fixture.proof().facts());
    var history = List.of(fixture.original(), fixture.renamed());
    for (int index = 0; index < history.size(); index++) {
      var attempt = new RequirementDraftStore.RequirementCaptureAttempt(source.generation(),
          ConversationArchive.latestInputId(source), null, h.store.captureMutationScope(h.chat), "captureRequirementDraft",
          history.get(index), false, index == 0
              ? List.of(issue("INVALID_ELEMENT_REFERENCE", "/draft/facts/owner/elementIntent/elementRef", "owner"))
              : List.of(issue("REPAIR_FACT_LOSS", "/draft/facts/step", "step")), null,
          org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(Map.of()));
      assertNull(attempt.authorEvidenceHash());
      h.artifacts.append(new CompilationArtifacts.AppendCommand(h.sessions.active(h.chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT, "1", "requirement-capture-tools", "1", attempt, List.of(), null));
    }
    try (var ignored = ToolSession.open(h.chat)) {
      var refusal = h.reject(fixture.proof());
      assertTrue(refusal.path("issues").toString().contains("UNKNOWN_ELEMENT_REFERENCE"));
      assertTrue(h.protectionHistory().isEmpty());
      var accepted = h.call(fixture.corrected());
      assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
      assertEquals(fixture.corrected(), h.store.get(h.chat).orElseThrow().authoredDraft());
    }
  }

  static RequirementCaptureResult.CaptureIssue issue(String code, String path, String id) {
    return new RequirementCaptureResult.CaptureIssue(code, path, id, null, code);
  }

  static final class Fixture {
    final boolean predecessor;
    Fixture(boolean predecessor) { this.predecessor = predecessor; }
    String field() { return predecessor ? "predecessorElementRef" : "successorElementRef"; }
    DraftInput original() { return input(predecessor ? "invalid owner" : "wrong-entry", "prepare-step", "prepare-step"); }
    DraftInput renamed() { return input(predecessor ? "audit-step" : "entry", "temporary-step", "temporary-step"); }
    DraftInput corrected() { return input(predecessor ? "audit-step" : "entry", "prepare-step", "prepare-step"); }
    DraftInput proof() { return input(predecessor ? "audit-step" : "entry", "prepare-step", "temporary-step"); }
    private DraftInput input(String ownerRef, String targetRef, String relativeRef) {
      var owner = new RequirementElementIntent(ownerRef, predecessor ? "log-record" : "http-trigger",
          predecessor ? RequirementElementIntent.Placement.LOCAL_NODE : RequirementElementIntent.Placement.ANCHOR,
          "entry", predecessor ? null : relativeRef, predecessor ? relativeRef : null, null,
          predecessor ? "Audit work" : "Receive work", predecessor ? List.of(new PlanProperty("message", "audit-item")) : List.of(new PlanProperty("externalRoute", "false")), List.of());
      var target = new RequirementElementIntent(targetRef, "script", RequirementElementIntent.Placement.LOCAL_NODE,
          "entry", predecessor ? ownerRef : null, predecessor ? null : ownerRef, null, "Prepare work", List.of(), List.of("ready"));
      var facts = new ArrayList<FactInput>(List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE,
          "Preserve the requested response and sequence."), new FactInput("owner", List.of("entry"), FactKind.BEHAVIOR,
          Polarity.POSITIVE, predecessor ? "Audit work logs audit-item." : "Receive work accepts POST /work.", null, null, owner),
          new FactInput("step", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
              "Prepare work sets the response body with ready.", null, null, target)));
      if (predecessor) facts.add(new FactInput("trigger", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
          "Receive work accepts POST /work.", null, null, new RequirementElementIntent("entry", "http-trigger",
              RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null, "Receive work", List.of(), List.of())));
      return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /work",
          null, null, null)), List.of()), facts, List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM,
              HttpMethod.POST, "/work", null, null, null)), List.of(), new DraftSettings(false, null));
    }
  }

  static final class Harness {
    final ObjectMapper json = JSON;
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final ConversationArchive archive = new ConversationArchive(blobs, json);
    final ConversationService conversations = mock(ConversationService.class);
    final String chat = "reference-protection-" + UUID.randomUUID();
    CompilationArtifacts artifacts; CompilationSessions sessions; RequirementDraftStore store; RequirementCaptureTools tools;
    Harness() {
      when(conversations.archive()).thenReturn(archive);
      var input = archive.beginInput(chat, SOURCE, null); archive.resolveInput(chat, input, SOURCE);
      reload();
    }
    void reload() {
      artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
      sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      var adapter = mock(RequirementDraftTool.class);
      when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
      var fingerprints = new ToolCallFingerprintStore(json, artifacts, sessions);
      var feedback = new CaptureAttemptFeedbackStore(fingerprints, artifacts, sessions);
      tools = new RequirementCaptureTools(store, adapter, json, new CaptureToolOutcomeGateway(fingerprints, feedback),
          feedback, null, null, null, conversations);
      tools.elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService.createForUnitTests(json);
      store.elementSchemas = tools.elementSchemas; store.beginTurn(chat, SOURCE);
    }
    JsonNode call(DraftInput draft) throws Exception { return json.readTree(tools.captureRequirementDraft(draft)); }
    JsonNode reject(DraftInput draft) throws Exception {
      var result = call(draft); assertFalse(result.path("accepted").asBoolean(), result.toString()); return result;
    }
    List<CompilationArtifacts.Revision> protectionHistory() {
      return artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION);
    }
  }
}
