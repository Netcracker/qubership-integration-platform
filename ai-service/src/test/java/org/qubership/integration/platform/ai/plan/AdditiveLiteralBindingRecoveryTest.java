package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Exercises additive native binding and continuation, without changing an existing literal obligation. */
class AdditiveLiteralBindingRecoveryTest {
  private static final String LITERAL = "audit-event";
  /** Present only on a rejected candidate's exactValues, never as a validated quote. */
  private static final String REJECTED_ONLY = "qip-auditor";
  private static final String SOURCE = """
      Receive POST /work through the HTTP trigger named Receive request on a private route.
      Use RBAC with exactly the role qip-auditor.
      Then log exactly audit-event with log level Info in a Log Record named Audit request.
      Then run a local Groovy Script named Preserve request that leaves the body unchanged.
      Preserve the complete original request body; do not replace it with the audit message.
      Use exactly these three elements in this order. Proceed without IDS.
      """;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void addingTheSameOwnerMessageRepairsBindingWithoutRemovingProtectedContent(boolean reload) throws Exception {
    var h = new Harness();
    var original = draft(false, null);
    h.rejectOwnerThenBinding(original);
    var protectedFields = h.mapper.valueToTree(h.store.repairConstraints(h.chat));
    var immutable = h.immutableArtifacts();
    if (reload) h.reload();
    assertEquals(protectedFields, h.mapper.valueToTree(h.store.repairConstraints(h.chat)));
    assertEquals(h.originalSource, h.conversations.archive().read(h.chat));

    var corrected = draft(true, LITERAL);
    assertOnlyOwnerAndMessageChanged(h.mapper, original, corrected);
    var accepted = h.capture(corrected);

    assertTrue(accepted.accepted(), accepted.toString());
    assertTrue(accepted.issues().isEmpty(), accepted.toString());
    assertTrue(accepted.pendingWork().isEmpty(), accepted.toString());
    assertEquals(RequirementCaptureResult.Readiness.READY, accepted.readiness());
    assertEquals(corrected, accepted.draft());
    assertEquals(corrected, h.store.get(h.chat).orElseThrow().authoredDraft());
    assertNotNull(accepted.revision());
    h.assertAttempt(corrected, true);
    h.assertUnchanged(immutable);
    h.assertSource(true, accepted.revision().artifactId());

    var reference = accepted.revision();
    var acceptedArtifacts = h.immutableArtifacts();
    h.reload();
    var read = h.call("readRequirementDraft", Map.of());
    assertTrue(read.accepted(), read.toString());
    assertEquals(reference, read.revision());
    assertEquals(corrected, read.draft());
    var continued = h.call("finishRequirementDiscoveryTurn", Map.of("directive", "CONTINUE"));
    assertTrue(continued.accepted(), continued.toString());
    assertEquals(RequirementCaptureResult.NextAction.HANDOFF, continued.nextAction());
    assertEquals(reference, continued.revision());
    h.assertUnchanged(acceptedArtifacts);
    h.assertSource(true, reference.artifactId());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceBackedLiteralCanLeaveExactValues(boolean reload) throws Exception {
    var h = new Harness();
    var rejectedOnly = h.capture(replaceAudit(draft(true, null),
        auditIntent(List.of(new PlanProperty("logLevel", "Info")), List.of(REJECTED_ONLY)), null));
    assertFalse(rejectedOnly.accepted(), rejectedOnly.toString());
    assertTrue(h.store.latestRevision(h.chat).isEmpty());
    if (reload) {
      h.reload();
    }

    var uncovered = quoteAudit(clearExactValues(h.mapper, draft(true, null)), h.source.turnId());
    assertEquals(List.of(), uncovered.facts().get(1).elementIntent().exactValues());
    assertEquals(LITERAL, uncovered.facts().get(1).evidence().quote());
    var missing = h.capture(uncovered);
    assertFalse(missing.accepted(), missing.toString());
    assertTrue(unbound(missing, LITERAL), missing.toString());
    assertFalse(unbound(missing, REJECTED_ONLY), missing.toString());

    var moved = quoteAudit(clearExactValues(h.mapper, draft(true, LITERAL)), h.source.turnId());
    var audit = moved.facts().get(1);
    assertEquals(List.of(), audit.elementIntent().exactValues());
    assertTrue(audit.elementIntent().requestedProperties().stream()
        .anyMatch(property -> "message".equals(property.key()) && LITERAL.equals(property.value())));

    var accepted = h.capture(moved);

    assertTrue(accepted.accepted(), accepted.toString());
    assertFalse(unbound(accepted, LITERAL), accepted.toString());
    assertFalse(unbound(accepted, REJECTED_ONLY), accepted.toString());
    assertFalse(accepted.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())),
        accepted.toString());
    assertEquals(List.of(), h.store.get(h.chat).orElseThrow().authoredDraft().facts().get(1)
        .elementIntent().exactValues());
  }

  @ParameterizedTest
  @ValueSource(strings = {"literal-deletion", "property-loss", "source-text-loss"})
  void rejectedProjectionAllowsCoveredLiteralPropertyAndUnclaimedTextChanges(String mutation) throws Exception {
    var h = new Harness();
    h.rejectOwnerThenBinding(draft(false, null));
    var candidate = mutated(h.mapper, draft(true, LITERAL), mutation);
    if ("literal-deletion".equals(mutation)) {
      candidate = quoteAudit(candidate, h.source.turnId());
      var rejected = h.capture(candidate);
      assertFalse(rejected.accepted(), rejected.toString());
      assertTrue(unbound(rejected, LITERAL), rejected.toString());
      assertEquals("audit", rejected.issues().stream()
          .filter(issue -> "UNBOUND_REQUIRED_LITERAL".equals(issue.code())).findFirst().orElseThrow().entityId());
      assertTrue(h.store.get(h.chat).isEmpty());
      assertTrue(h.store.latestRevision(h.chat).isEmpty());
      return;
    }

    var accepted = h.capture(candidate);

    assertTrue(accepted.accepted(), accepted.toString());
    assertFalse(accepted.issues().stream().anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())),
        accepted.toString());
    var saved = h.store.get(h.chat).orElseThrow().authoredDraft();
    switch (mutation) {
      case "property-loss" -> assertTrue(saved.facts().getFirst().elementIntent().requestedProperties().stream()
          .noneMatch(property -> "externalRoute".equals(property.key())));
      case "source-text-loss" -> assertEquals("Preserve the body.", saved.facts().get(3).text());
      default -> throw new IllegalArgumentException(mutation);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-value"})
  void bindingRepairRefusesWrongValuesAndIndependentRequirementLoss(String mutation) throws Exception {
    for (boolean reload : List.of(false, true)) {
      var h = new Harness();
      h.rejectOwnerThenBinding(draft(false, null));
      var immutable = h.immutableArtifacts();
      if (reload) h.reload();
      var candidate = mutated(h.mapper, draft(true, LITERAL), mutation);

      var rejected = h.capture(candidate);

      assertFalse(rejected.accepted(), rejected.toString());
      String code = mutation.equals("wrong-value") ? "UNBOUND_REQUIRED_LITERAL" : "REPAIR_FACT_LOSS";
      String factId = mutation.equals("source-text-loss") ? "body-retention"
          : mutation.equals("property-loss") ? "entry-anchor" : "audit";
      String path = "/draft/facts/" + factId
          + (mutation.equals("wrong-value") ? "/elementIntent/requestedProperties" : "");
      assertTrue(rejected.issues().stream().anyMatch(issue -> issue.code().equals(code)
          && issue.path().equals(path) && factId.equals(issue.entityId())), rejected.toString());
      if (mutation.equals("wrong-value")) {
        assertTrue(rejected.issues().stream().anyMatch(issue -> issue.code().equals(code)
            && issue.message().contains("[\"" + LITERAL + "\"]")), rejected.toString());
      }
      assertTrue(h.store.get(h.chat).isEmpty());
      assertTrue(h.store.latestRevision(h.chat).isEmpty());
      h.assertAttempt(candidate, false);
      h.assertSource(false, null);
      h.assertUnchanged(immutable);
      var rejectedArtifacts = h.immutableArtifacts();
      h.reload();
      h.assertAttempt(candidate, false);
      h.assertSource(false, null);
      h.assertUnchanged(rejectedArtifacts);
    }
  }

  private static DraftInput draft(boolean correctOwner, String message) {
    var properties = message == null ? List.of(new PlanProperty("logLevel", "Info"))
        : List.of(new PlanProperty("logLevel", "Info"), new PlanProperty("message", message));
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /work", "Receive the original request body.", null, null)), List.of()),
        List.of(
            fact("entry-anchor", "Receive POST /work through Receive request on a private route."
                    + " Use RBAC with exactly the role qip-auditor.",
                new RequirementElementIntent("entry", "http-trigger", Placement.ANCHOR, "entry",
                    "audit", null, null, "Receive request", List.of(
                        new PlanProperty("externalRoute", "false"), new PlanProperty("accessControlType", "RBAC"),
                        new PlanProperty("roles", "[\"qip-auditor\"]")), List.of("/work", "qip-auditor"))),
            fact("audit", "Log exactly audit-event with log level Info in a Log Record named Audit request.",
                new RequirementElementIntent("audit", "log-record", Placement.LOCAL_NODE, "entry",
                    "preserve", "entry", null, "Audit request", properties, List.of(LITERAL))),
            fact("preserve-script", "Run a local Groovy Script named Preserve request that leaves the body unchanged.",
                new RequirementElementIntent("preserve", "script", Placement.LOCAL_NODE,
                    correctOwner ? "entry" : "unrelated-owner", null, "audit", null,
                    "Preserve request", List.of(), List.of())),
            fact("body-retention", "Preserve the complete original request body; do not replace it with the audit message.", null)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/work", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  private static FactInput fact(String id, String text, RequirementElementIntent intent) {
    return new FactInput(id, List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, text, null, null, intent);
  }

  private static RequirementElementIntent auditIntent(List<PlanProperty> properties, List<String> exactValues) {
    return new RequirementElementIntent("audit", "log-record", Placement.LOCAL_NODE, "entry",
        "preserve", "entry", null, "Audit request", properties, exactValues);
  }

  private static DraftInput replaceAudit(DraftInput draft, RequirementElementIntent intent, ClaimEvidenceInput evidence) {
    var facts = new java.util.ArrayList<>(draft.facts());
    var audit = facts.get(1);
    facts.set(1, new FactInput(audit.sourceFactId(), audit.interactionIds(), audit.kind(), audit.polarity(),
        audit.text(), audit.fieldMapping(), audit.policyConflict(), intent, evidence));
    return new DraftInput(draft.flow(), List.copyOf(facts), draft.capabilities(), draft.openQuestions(), draft.settings());
  }

  /** The obligation is the quote {@code audit-event}, an exact substring of the author turn. */
  private static DraftInput quoteAudit(DraftInput draft, String turnId) {
    var audit = draft.facts().get(1);
    return replaceAudit(draft, audit.elementIntent(), new ClaimEvidenceInput(turnId, LITERAL));
  }

  private static boolean unbound(RequirementCaptureResult result, String literal) {
    String token = "[\"" + literal + "\"]";
    return result.issues().stream().anyMatch(issue -> "UNBOUND_REQUIRED_LITERAL".equals(issue.code())
        && issue.message() != null && issue.message().contains(token));
  }

  private static void assertOnlyOwnerAndMessageChanged(ObjectMapper mapper, DraftInput before, DraftInput after) {
    var expected = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(before);
    ((com.fasterxml.jackson.databind.node.ObjectNode) expected.at("/facts/2/elementIntent")).put("ownerInteractionId", "entry");
    ((com.fasterxml.jackson.databind.node.ArrayNode) expected.at("/facts/1/elementIntent/requestedProperties"))
        .add(mapper.valueToTree(new PlanProperty("message", LITERAL)));
    assertEquals(expected, mapper.valueToTree(after), "All original facts, literals, names, properties, and settings remain exact");
  }

  private static DraftInput clearExactValues(ObjectMapper mapper, DraftInput draft) throws Exception {
    var value = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(draft);
    ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/facts/1/elementIntent")).putArray("exactValues");
    return mapper.treeToValue(value, DraftInput.class);
  }

  private static DraftInput mutated(ObjectMapper mapper, DraftInput draft, String mutation) throws Exception {
    var value = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(draft);
    switch (mutation) {
      case "wrong-value" -> ((com.fasterxml.jackson.databind.node.ObjectNode)
          value.at("/facts/1/elementIntent/requestedProperties/1")).put("value", "different-event");
      case "literal-deletion" -> {
        var intent = (com.fasterxml.jackson.databind.node.ObjectNode) value.at("/facts/1/elementIntent");
        intent.putArray("exactValues");
        var properties = (com.fasterxml.jackson.databind.node.ArrayNode) intent.get("requestedProperties");
        for (int i = properties.size() - 1; i >= 0; i--) {
          if ("message".equals(properties.get(i).path("key").asText())) {
            properties.remove(i);
          }
        }
      }
      case "property-loss" -> ((com.fasterxml.jackson.databind.node.ArrayNode)
          value.at("/facts/0/elementIntent/requestedProperties")).remove(0);
      case "source-text-loss" -> ((com.fasterxml.jackson.databind.node.ObjectNode)
          value.at("/facts/3")).put("text", "Preserve the body.");
      default -> throw new IllegalArgumentException(mutation);
    }
    return mapper.treeToValue(value, DraftInput.class);
  }

  private static final class Harness {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    final ConversationService conversations = new ConversationService();
    final String chat = "additive-literal-binding-" + UUID.randomUUID();
    final ConversationArchive.Input source;
    final ConversationArchive.Document originalSource;
    RequirementDraftStore store;
    ToolProviderResult provider;

    Harness() {
      source = conversations.archive().beginInput(chat, SOURCE, null);
      conversations.archive().resolveInput(chat, source, SOURCE);
      originalSource = conversations.archive().read(chat);
      reload();
    }

    void reload() {
      var schemas = DeterministicElementSchemaService.createForUnitTests(mapper);
      assertTrue(schemas.allowedPatchPropertyKeys("log-record").containsAll(List.of("message", "logLevel")));
      assertTrue(schemas.validateCapturePropertyValue("log-record", "message", mapper.valueToTree(LITERAL)).isEmpty());
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      store.elementSchemas = schemas;
      var fingerprints = new ToolCallFingerprintStore(mapper, artifacts, sessions);
      var feedback = new CaptureAttemptFeedbackStore(fingerprints, artifacts, sessions);
      var tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), mapper,
          new CaptureToolOutcomeGateway(fingerprints, feedback), feedback, null, null, null, conversations);
      tools.elementSchemas = schemas;
      provider = new RequirementCaptureToolProvider(tools, mapper, conversations).provideTools(null);
      store.beginTurn(chat, SOURCE);
    }

    void rejectOwnerThenBinding(DraftInput original) throws Exception {
      var owner = capture(original);
      assertFalse(owner.accepted(), owner.toString());
      assertEquals(List.of("INVALID_ELEMENT_OWNER"), owner.issues().stream()
          .map(RequirementCaptureResult.CaptureIssue::code).toList(), owner.toString());
      assertEquals("/draft/facts/preserve-script/elementIntent", owner.issues().getFirst().path());
      var binding = capture(draft(true, null));
      assertFalse(binding.accepted(), binding.toString());
      assertEquals(List.of("UNBOUND_REQUIRED_LITERAL"), binding.issues().stream()
          .map(RequirementCaptureResult.CaptureIssue::code).toList(), binding.toString());
      assertEquals("audit", binding.issues().getFirst().entityId());
      assertEquals("/draft/facts/audit/elementIntent/requestedProperties", binding.issues().getFirst().path());
      assertTrue(binding.issues().getFirst().message().contains("[\"" + LITERAL + "\"]"));
      assertTrue(store.latestRevision(chat).isEmpty());
      assertSource(false, null);
    }

    RequirementCaptureResult capture(DraftInput draft) throws Exception {
      return call("captureRequirementDraft", Map.of("draft", draft));
    }

    RequirementCaptureResult call(String name, Map<String, ?> arguments) throws Exception {
      String result = provider.toolExecutorByName(name).execute(ToolExecutionRequest.builder()
          .id(UUID.randomUUID().toString()).name(name).arguments(mapper.writeValueAsString(arguments)).build(), chat);
      return mapper.readValue(result, RequirementCaptureResult.class);
    }

    void assertSource(boolean consumed, String draftId) {
      var current = conversations.archive().read(chat);
      assertEquals(originalSource.generation(), current.generation());
      assertEquals(originalSource.activeTurnIds(), current.activeTurnIds());
      var turn = current.activeTurns().getFirst();
      assertEquals(source.turnId(), turn.id());
      assertEquals(SOURCE, turn.rawText());
      assertEquals(SOURCE, turn.message().content());
      if (consumed) assertEquals(draftId, turn.consumedDraftReference());
      else assertEquals(originalSource, current);
    }

    void assertAttempt(DraftInput candidate, boolean accepted) {
      var revision = artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      var attempt = artifacts.payload(revision, RequirementDraftStore.RequirementCaptureAttempt.class);
      assertEquals(accepted, attempt.accepted());
      assertEquals(originalSource.generation(), attempt.sourceGeneration());
      assertEquals(source.turnId(), attempt.sourceTurnId());
      assertEquals(mapper.valueToTree(candidate), mapper.valueToTree(attempt.arguments()));
    }

    Map<String, byte[]> immutableArtifacts() {
      var snapshot = new LinkedHashMap<String, byte[]>();
      for (String key : blobs.list("compiler-artifacts/")) snapshot.put(key, blobs.get(key).orElseThrow());
      return snapshot;
    }

    void assertUnchanged(Map<String, byte[]> snapshot) {
      snapshot.forEach((key, bytes) -> assertArrayEquals(bytes, blobs.get(key).orElseThrow(), key));
    }
  }
}
