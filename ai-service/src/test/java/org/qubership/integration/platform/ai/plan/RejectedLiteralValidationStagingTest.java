package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Portable reproduction of native validation staging; no catalog or runtime behavior is inferred. */
class RejectedLiteralValidationStagingTest {
  private static final String SOURCE = """
      Consume only OM onTaskStart. Call createTask with POST /sobjects/Task, then publish onTaskResult.
      Use display names onTaskStart, createTask, and onTaskResult for the bound catalog elements.
      Return createTask errors inline with errorThrowing=false.
      After createTask and before onTaskResult, run the script named Build outcome.
      Its outgoing commandType is exactly completeTask; retain the original processInstanceId as processId.
      Proceed without IDS.
      """;
  private static final String CALL_TEXT = "Use display name createTask for the bound catalog call."
      + " Call createTask with POST /sobjects/Task. Return errors inline with errorThrowing=false.";
  private static final String SCRIPT_TEXT = "After createTask and before onTaskResult, run the script named Build outcome."
      + " Its outgoing commandType is exactly completeTask; retain the original processInstanceId as processId.";

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unrelatedOwnerFailureProtectsLiteralListsBeforeTheirBindingCheck(boolean reload) throws Exception {
    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      var rejected = h.capture(input(false, false));
      assertEquals(List.of("INVALID_ELEMENT_OWNER"), rejected.issues().stream()
          .map(RequirementCaptureResult.CaptureIssue::code).toList(), rejected.toString());
      assertEquals("/draft/facts/build-script/elementIntent", rejected.issues().getFirst().path());
      assertEquals(List.of("createTask", "POST /sobjects/Task", "false"),
          protectedFact(rejected, "create-anchor").elementIntent().exactValues());
      assertEquals(List.of("onTaskResult"), protectedFact(rejected, "result-anchor").elementIntent().exactValues());
      var protection = h.mapper.valueToTree(rejected.repairConstraints());
      var source = h.mapper.valueToTree(h.archive.read(h.chat));
      var immutable = h.immutableArtifacts();
      if (reload) h.reload();
      assertEquals(protection, h.mapper.valueToTree(h.store.repairConstraints(h.chat)));
      assertEquals(source, h.mapper.valueToTree(h.archive.read(h.chat)));
      assertTrue(h.store.latestRevision(h.chat).isEmpty());
      h.assertUnchanged(immutable);

      var bindingRefusal = h.capture(input(true, false));
      assertFalse(bindingRefusal.accepted(), bindingRefusal.toString());
      assertEquals(List.of("UNBOUND_REQUIRED_LITERAL"), bindingRefusal.issues().stream()
          .map(RequirementCaptureResult.CaptureIssue::code).toList(), bindingRefusal.toString());
      assertTrue(bindingRefusal.issues().stream().anyMatch(issue -> issue.entityId().equals("create-anchor")
          && issue.path().equals("/draft/facts/create-anchor/elementIntent/requestedProperties")
          && issue.message().contains("[\"POST /sobjects/Task\"]")), bindingRefusal.toString());
      assertTrue(bindingRefusal.issues().stream().allMatch(issue -> issue.message().contains("preserve the fact, exactValues")));
      assertTrue(h.store.latestRevision(h.chat).isEmpty());
      h.assertUnchanged(immutable);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshAdmissibilityCannotAuthorizeChangingRejectedLiteralsWithoutSemanticProof(boolean reload) throws Exception {
    var corrected = input(true, true);
    assertSelectiveCorrection(input(true, false), corrected);
    // This establishes native capture admissibility only, not semantic equivalence or catalog resolution.
    var fresh = new Harness();
    try (var ignored = ToolSession.open(fresh.chat)) {
      var accepted = fresh.capture(corrected);
      assertTrue(accepted.accepted(), accepted.toString());
      assertEquals(corrected, fresh.store.get(fresh.chat).orElseThrow().authoredDraft());
      assertNotNull(accepted.revision());
    }

    var h = new Harness();
    try (var ignored = ToolSession.open(h.chat)) {
      assertFalse(h.capture(input(false, false)).accepted());
      var refusal = h.capture(input(true, false));
      assertTrue(refusal.issues().stream().allMatch(issue -> issue.code().equals("UNBOUND_REQUIRED_LITERAL")));
      var immutable = h.immutableArtifacts();
      var source = h.mapper.valueToTree(h.archive.read(h.chat));
      var authority = h.store.writeAuthority(h.chat);
      if (reload) h.reload();
      assertEquals(source, h.mapper.valueToTree(h.archive.read(h.chat)));
      var result = h.capture(corrected);
      h.assertUnchanged(immutable);
      // Method and path properties do not cover the combined source literal.
      assertFalse(result.accepted(), result.toString());
      assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("UNBOUND_REQUIRED_LITERAL")
          && issue.entityId().equals("create-anchor") && issue.message().contains("POST /sobjects/Task")),
          result.toString());
      assertFalse(result.issues().stream().anyMatch(issue -> issue.code().equals("REPAIR_FACT_LOSS")),
          result.toString());
      assertEquals(authority, h.store.writeAuthority(h.chat));
      assertEquals(source, h.mapper.valueToTree(h.archive.read(h.chat)));
      assertTrue(h.store.get(h.chat).isEmpty());
      assertTrue(h.store.latestRevision(h.chat).isEmpty());

      var attempt = h.artifacts.latest(h.sessions.active(h.chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
      var retained = h.artifacts.payload(attempt, RequirementDraftStore.RequirementCaptureAttempt.class);
      assertFalse(retained.accepted());
      assertEquals(h.mapper.valueToTree(corrected), h.mapper.valueToTree(retained.arguments()));
      assertEquals(authority.sourceGeneration(), retained.sourceGeneration());
      assertEquals(authority.sourceTurnId(), retained.sourceTurnId());
      assertEquals(authority.base(), retained.acceptedDraftReference());
      var candidateBytes = h.mapper.writeValueAsBytes(attempt.payload().path("arguments"));
      var allAttempts = h.immutableArtifacts();
      h.reload();
      var reloaded = h.artifacts.get(h.sessions.active(h.chat), attempt.reference()).orElseThrow();
      assertArrayEquals(candidateBytes, h.mapper.writeValueAsBytes(reloaded.payload().path("arguments")));
      assertEquals(authority, h.store.writeAuthority(h.chat));
      assertEquals(source, h.mapper.valueToTree(h.archive.read(h.chat)));
      assertTrue(h.store.latestRevision(h.chat).isEmpty());
      h.assertUnchanged(allAttempts);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "text", "literal"})
  void representationRepairRejectsUncoveredLiteralsAndAcceptedSettingLoss(String removed) throws Exception {
    for (boolean reload : List.of(false, true)) {
      var h = new Harness();
      try (var ignored = ToolSession.open(h.chat)) {
        var original = input(true, true);
        if (removed.equals("false")) {
          // Isolate the real setting from the unrelated unbound metadata on the same anchor.
          assertTrue(h.capture(original).accepted());
        } else {
          assertFalse(h.capture(input(false, false)).accepted());
          assertFalse(h.capture(input(true, false)).accepted());
        }
        var accepted = h.store.latestRevision(h.chat).map(CompilationArtifacts.Revision::reference);
        var facts = original.facts().stream().map(fact -> {
          if (removed.equals("false") && fact.sourceFactId().equals("create-anchor")) {
            var intent = fact.elementIntent();
            return replace(fact, fact.text(), new RequirementElementIntent(intent.elementRef(), intent.elementType(),
                intent.placement(), intent.ownerInteractionId(), intent.successorElementRef(), intent.predecessorElementRef(),
                intent.parentElementRef(), intent.requestedName(), intent.requestedProperties().stream()
                    .filter(property -> !property.key().equals("errorThrowing")).toList(), List.of()));
          }
          if (removed.equals("text") && fact.sourceFactId().equals("result-context")) {
            return replace(fact, "Retain processId.", fact.elementIntent());
          }
          if (removed.equals("literal") && fact.sourceFactId().equals("build-script")) {
            var intent = fact.elementIntent();
            return replace(fact, fact.text(), new RequirementElementIntent(intent.elementRef(), intent.elementType(),
                intent.placement(), intent.ownerInteractionId(), intent.successorElementRef(), intent.predecessorElementRef(),
                intent.parentElementRef(), intent.requestedName(), intent.requestedProperties(), List.of()));
          }
          return fact;
        }).toList();
        var candidate = new DraftInput(original.flow(), facts, original.capabilities(), original.openQuestions(), original.settings());
        var immutable = h.immutableArtifacts();
        if (reload) h.reload();
        var result = removed.equals("false") ? h.update(new DraftUpdate(List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), facts, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null))
            : h.capture(candidate);
        assertFalse(result.accepted(), result.toString());
        if (removed.equals("false")) {
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("REPAIR_FACT_LOSS")
              && issue.path().equals("/changes/facts/create-anchor") && issue.entityId().equals("create-anchor")),
              result.toString());
        } else if (removed.equals("text")) {
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("UNBOUND_REQUIRED_LITERAL")
              && issue.entityId().equals("create-anchor") && issue.message().contains("POST /sobjects/Task")),
              result.toString());
          assertFalse(result.issues().stream().anyMatch(issue -> issue.code().equals("REPAIR_FACT_LOSS")
              && issue.entityId().equals("result-context")), result.toString());
        } else {
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("UNBOUND_REQUIRED_LITERAL")
              && issue.path().equals("/draft/facts/build-script/elementIntent")
              && issue.entityId().equals("build-script") && issue.message().contains("completeTask")),
              result.toString());
        }
        assertEquals(accepted, h.store.latestRevision(h.chat).map(CompilationArtifacts.Revision::reference));
        if (removed.equals("false")) assertEquals(original, h.store.get(h.chat).orElseThrow().authoredDraft());
        h.assertUnchanged(immutable);
      }
    }
  }

  private static void assertSelectiveCorrection(DraftInput before, DraftInput after) {
    assertEquals(before.flow(), after.flow());
    assertEquals(before.capabilities(), after.capabilities());
    assertEquals(before.openQuestions(), after.openQuestions());
    assertEquals(before.settings(), after.settings());
    assertEquals(before.facts().size(), after.facts().size());
    for (int i = 0; i < before.facts().size(); i++) {
      var old = before.facts().get(i);
      var updated = after.facts().get(i);
      assertEquals(old.sourceFactId(), updated.sourceFactId());
      assertEquals(old.interactionIds(), updated.interactionIds());
      assertEquals(old.kind(), updated.kind());
      assertEquals(old.polarity(), updated.polarity());
      assertEquals(old.text(), updated.text());
      assertEquals(old.fieldMapping(), updated.fieldMapping());
      assertEquals(old.policyConflict(), updated.policyConflict());
      if (old.sourceFactId().equals("create-anchor")) {
        assertEquals(List.of("false"), updated.elementIntent().exactValues());
        assertEquals("createTask", updated.elementIntent().requestedName());
        assertTrue(updated.elementIntent().requestedProperties().containsAll(old.elementIntent().requestedProperties()));
        assertTrue(updated.elementIntent().requestedProperties().containsAll(List.of(
            new PlanProperty("integrationOperationMethod", "POST"), new PlanProperty("integrationOperationPath", "/sobjects/Task"))));
      } else if (old.sourceFactId().equals("result-anchor")) {
        assertEquals(List.of(), updated.elementIntent().exactValues());
        assertEquals("onTaskResult", updated.elementIntent().requestedName());
      } else {
        assertEquals(old, updated);
      }
    }
  }

  private static DraftInput input(boolean correctOwner, boolean correctRepresentation) {
    var flow = new FlowInput(List.of(
        new InteractionInput("onTaskStart", Direction.INBOUND, "OM", "onTaskStart", null, null, null),
        new InteractionInput("createTask", Direction.OUTBOUND, "Salesforce WFM", "createTask", null,
            ServiceCallFailureMode.INLINE_RESPONSE, null),
        new InteractionInput("onTaskResult", Direction.OUTBOUND, "OM", "onTaskResult", null, null, null)),
        List.of(new TransitionInput("onTaskStart", "createTask"), new TransitionInput("createTask", "onTaskResult")));
    var properties = correctRepresentation ? List.of(new PlanProperty("errorThrowing", "false"),
        new PlanProperty("integrationOperationMethod", "POST"), new PlanProperty("integrationOperationPath", "/sobjects/Task"))
        : List.of(new PlanProperty("errorThrowing", "false"));
    return new DraftInput(flow, List.of(
        behavior("start-anchor", List.of("onTaskStart"), "Use display name onTaskStart for the bound catalog entry.",
            new RequirementElementIntent("onTaskStart", "async-api-trigger", Placement.ANCHOR, "onTaskStart",
                null, null, null, "onTaskStart", List.of(), List.of("onTaskStart"))),
        behavior("create-anchor", List.of("createTask"), CALL_TEXT,
            new RequirementElementIntent("createTask", "service-call", Placement.ANCHOR, "createTask",
                "build-outcome", "onTaskStart", null, "createTask", properties,
                correctRepresentation ? List.of("false") : List.of("createTask", "POST /sobjects/Task", "false"))),
        behavior("build-script", List.of("createTask"), SCRIPT_TEXT,
            new RequirementElementIntent("build-outcome", "script", Placement.LOCAL_NODE,
                correctOwner ? "createTask" : "onTaskResult", "onTaskResult", "createTask", null,
                "Build outcome", List.of(), List.of("completeTask"))),
        behavior("result-anchor", List.of("onTaskResult"), "Use display name onTaskResult for the bound catalog publish element.",
            new RequirementElementIntent("onTaskResult", "service-call", Placement.ANCHOR, "onTaskResult",
                null, "build-outcome", null, "onTaskResult", List.of(),
                correctRepresentation ? List.of() : List.of("onTaskResult"))),
        behavior("result-context", List.of("createTask", "onTaskResult"),
            "Retain the original processInstanceId across createTask and use it as outgoing processId.", null)),
        List.of(new CapabilityInput("onTaskStart", "async-api-trigger", null, null, null, null, null, null)),
        List.of(), new DraftSettings(false, null));
  }

  private static FactInput behavior(String id, List<String> owners, String text, RequirementElementIntent intent) {
    return new FactInput(id, owners, FactKind.BEHAVIOR, Polarity.POSITIVE, text, null, null, intent);
  }

  private static FactInput replace(FactInput fact, String text, RequirementElementIntent intent) {
    return new FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(), text,
        fact.fieldMapping(), fact.policyConflict(), intent);
  }

  private static FactInput protectedFact(RequirementCaptureResult result, String id) {
    return result.repairConstraints().facts().stream().filter(fact -> fact.sourceFactId().equals(id)).findFirst().orElseThrow();
  }

  private static final class Harness {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final ConversationArchive archive = new ConversationArchive(blobs, mapper);
    final ConversationService conversations = mock(ConversationService.class);
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    final String chat = "literal-staging-" + UUID.randomUUID();
    RequirementDraftStore store;
    RequirementCaptureTools tools;

    Harness() {
      when(conversations.archive()).thenReturn(archive);
      var source = archive.beginInput(chat, SOURCE, null);
      archive.resolveInput(chat, source, SOURCE);
      reload();
    }

    void reload() {
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      var schemas = DeterministicElementSchemaService.createForUnitTests(mapper);
      store.elementSchemas = schemas;
      var bindings = mock(RequirementDraftTool.class);
      when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
      var fingerprints = new ToolCallFingerprintStore(mapper, artifacts, sessions);
      var feedback = new CaptureAttemptFeedbackStore(fingerprints, artifacts, sessions);
      tools = new RequirementCaptureTools(store, bindings, mapper,
          new CaptureToolOutcomeGateway(fingerprints, feedback), feedback, null, null, null, conversations);
      tools.elementSchemas = schemas;
      store.beginTurn(chat, SOURCE);
    }

    RequirementCaptureResult capture(DraftInput input) throws Exception {
      return mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
    }

    RequirementCaptureResult update(DraftUpdate input) throws Exception {
      return mapper.readValue(tools.updateRequirementDraft(input), RequirementCaptureResult.class);
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
