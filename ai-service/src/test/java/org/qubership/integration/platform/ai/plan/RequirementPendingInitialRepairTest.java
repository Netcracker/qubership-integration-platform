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
    assertEquals(CaptureProposal.digest(h.json.treeToValue(pending.path("attempt"), CompilationArtifacts.Reference.class)),
        pending.path("token").asText());
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

  @Test
  void repeatedResolvedCandidateCannotRenewCreditByChangingToolOrToken() throws Exception {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var original = input("missing-owner");
    h.call("captureRequirementDraft", Map.of("draft", original));
    int credits = h.fingerprints.softCreditsUsed(h.chat);
    String scope = h.store.captureMutationScope(h.chat);
    var initial = h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token");
    assertThrows(CaptureNoProgressException.class, () -> h.call("updateRequirementDraft",
        Map.of("changes", h.changes(original.facts(), initial))));
    var latest = h.call("readRequirementDraft", Map.of()).path("pendingProposal").path("token");
    assertNotEquals(initial, latest);
    assertThrows(CaptureNoProgressException.class, () -> h.call("updateRequirementDraft",
        Map.of("changes", h.changes(original.facts(), latest))));
    assertThrows(CaptureNoProgressException.class, () -> h.call("captureRequirementDraft", Map.of("draft", original)));
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
    var facts = new java.util.ArrayList<>(input("entry").facts());
    var old = facts.getFirst();
    facts.set(0, new FactInput(old.sourceFactId(), old.interactionIds(), old.kind(), old.polarity(),
        old.text() + " Changed.", old.fieldMapping(), old.policyConflict(), old.elementIntent()));
    var patch = h.changes(facts, pending.path("token"));
    var rejected = h.call("updateRequirementDraft", Map.of("changes", patch));
    assertFalse(rejected.path("accepted").asBoolean());
    assertTrue(rejected.path("issues").findValuesAsText("code").contains("REPAIR_FACT_LOSS"), rejected.toString());
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
  void schemaKeepsEveryLegacyRequiredFieldAndTheNewTokenIsOptionalNullable() {
    var h = new Harness(new InMemoryArtifactBlobStore());
    var changes = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class,
        h.provider.toolSpecificationByName("updateRequirementDraft").parameters().properties().get("changes"));
    var expected = new java.util.HashSet<>(changes.properties().keySet());
    expected.remove("pendingProposalToken");
    assertEquals(expected, java.util.Set.copyOf(changes.required()));
    var pending = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonAnyOfSchema.class,
        changes.properties().get("pendingProposalToken"));
    assertTrue(pending.anyOf().stream().anyMatch(dev.langchain4j.model.chat.request.json.JsonNullSchema.class::isInstance));
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
      if (update) keys.remove("pendingProposalToken");
      assertEquals(keys, java.util.Set.copyOf(object.required()));
      object.properties().values().forEach(child -> assertRequiredFields(child, update));
    } else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonArraySchema array) {
      assertRequiredFields(array.items(), update);
    } else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonAnyOfSchema any) {
      any.anyOf().forEach(child -> assertRequiredFields(child, update));
    }
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
