package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class RequirementDraftStoreLifecycleTest {

  @Test
  void storedLegacyProtectionRestoresAndRejectedFactsDoNotWriteOne() {
    var blobs = new InMemoryArtifactBlobStore();
    var first = runtime(blobs);
    String text = "Create one task.";
    first.store().beginTurn("chat", text);
    var fact = new RequirementCaptureInput.FactInput("goal", List.of(),
        RequirementCaptureInput.FactKind.GOAL, RequirementCaptureInput.Polarity.POSITIVE, text);
    String compilationId = first.store().activeCompilationId("chat");
    int before = first.artifacts().history(compilationId, Kind.REQUIREMENT_CAPTURE_PROTECTION).size();
    first.store().rememberRejectedCaptureFacts("chat", List.of(fact));
    assertEquals(before, first.artifacts().history(compilationId, Kind.REQUIREMENT_CAPTURE_PROTECTION).size());
    first.artifacts().append(new CompilationArtifacts.AppendCommand(compilationId,
        Kind.REQUIREMENT_CAPTURE_PROTECTION, "1", "requirement-capture-protection", "1",
        new RequirementDraftStore.CaptureFactProtection(null, null, text, List.of(fact)), List.of(), null));

    var restored = runtime(blobs).store();
    restored.beginTurn("chat", text);

    assertEquals(List.of(fact), restored.rejectedCaptureFacts("chat"));
  }

  @Test
  void authorSourceIdentityKeepsRepairProtectionAndDistinguishesIdenticalNewMessages() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = org.mockito.Mockito.mock(ConversationService.class);
    org.mockito.Mockito.when(conversations.archive()).thenReturn(archive);
    var store = new RequirementDraftStore(new CompilationArtifacts(blobs, mapper, Clock.systemUTC()),
        new CompilationSessions(blobs, mapper, Clock.systemUTC()), conversations);
    String authorText = "Create one task.";
    var input = archive.beginInput("chat", authorText, null);
    archive.resolveInput("chat", input, authorText);
    store.beginTurn("chat", authorText);
    var fact = new RequirementCaptureInput.FactInput("goal", List.of(),
        RequirementCaptureInput.FactKind.GOAL, RequirementCaptureInput.Polarity.POSITIVE, authorText);
    store.rememberRejectedCaptureFacts("chat", List.of(fact));
    store.markCaptured("chat");

    store.beginTurn("chat", "Repair the generated draft.");

    assertEquals(List.of(fact), store.rejectedCaptureFacts("chat"));
    assertTrue(store.wasCapturedThisTurn("chat"));

    var next = archive.beginInput("chat", authorText, null);
    archive.resolveInput("chat", next, authorText);
    store.beginTurn("chat", authorText);

    assertTrue(store.rejectedCaptureFacts("chat").isEmpty());
    assertFalse(store.wasCapturedThisTurn("chat"));
  }

  @Test
  void beginTurnKeepsCaptureRejectionUntilPutOrClear() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.recordCaptureRejection("conversation-1", "duplicate sourceFactId in facts: x");
    store.beginTurn("conversation-1");
    assertEquals(
        "duplicate sourceFactId in facts: x",
        store.lastCaptureRejection("conversation-1").orElseThrow());

    store.put("conversation-1", new RequirementDraft(false, "first draft"));
    assertTrue(store.lastCaptureRejection("conversation-1").isEmpty());
  }

  @Test
  void beginTurnClearsThePreviousDiscoveryDirective() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.finishTurn("conversation-1", RequirementDiscoveryDirective.STAY);

    store.beginTurn("conversation-1");

    assertEquals(RequirementDiscoveryDirective.NONE, store.turnDirective("conversation-1"));
  }

  @Test
  void clearTurnFlagsClearsCaptureRejection() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.recordCaptureRejection("conversation-1", "duplicate sourceFactId in facts: x");

    store.clearTurnFlags("conversation-1");

    assertTrue(store.lastCaptureRejection("conversation-1").isEmpty());
  }

  @Test
  void removeClearsCaptureRejection() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.recordCaptureRejection("conversation-1", "duplicate sourceFactId in facts: x");

    store.remove("conversation-1");

    assertTrue(store.lastCaptureRejection("conversation-1").isEmpty());
  }

  @Test
  void resetStartsNewCompilationWithoutReusingItsDraft() {
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    TestRuntime runtime = runtime(blobStore);
    CompilationArtifacts artifacts = runtime.artifacts();
    CompilationSessions sessions = runtime.sessions();
    RequirementDraftStore store = runtime.store();
    store.put("conversation-1", new RequirementDraft(false, "first draft"));
    String firstCompilationId = store.activeCompilationId("conversation-1");

    store.remove("conversation-1");

    String secondCompilationId = store.activeCompilationId("conversation-1");
    assertNotEquals(firstCompilationId, secondCompilationId);
    assertTrue(store.get("conversation-1").isEmpty());
    assertEquals(1, artifacts.history(firstCompilationId, Kind.REQUIREMENT_DRAFT).size());
    assertEquals(2, sessions.history("conversation-1").size());

    sessions.activate("conversation-1", firstCompilationId);
    assertEquals("first draft", store.get("conversation-1").orElseThrow().assembledText());
  }

  @Test
  void updatesProduceReadableDraftRevisions() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.put("conversation-1", new RequirementDraft(false, "first draft"));
    store.put("conversation-1", new RequirementDraft(true, "second draft"));

    RequirementDraft latest = store.get("conversation-1").orElseThrow();

    assertEquals("second draft", latest.assembledText());
    assertTrue(latest.complete());
  }

  @Test
  void reconstructingStoreRecoversPerCallBindings() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    CatalogBindingHint omHint =
        new CatalogBindingHint(
            "2",
            "call-om-result",
            "fact-om",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-shared",
            "http",
            "POST",
            "/tasks/result",
            "2024.4",
            observedAt,
            "evidence-om");
    CatalogBindingHint wfmHint =
        new CatalogBindingHint(
            "2",
            "call-wfm-create-task",
            "fact-wfm",
            "createTask",
            "sys-wfm",
            "sg-wfm",
            "spec-wfm",
            "op-shared",
            "http",
            "POST",
            "/tasks",
            "2024.4",
            observedAt,
            "evidence-wfm");
    RequirementDraft draft =
        new RequirementDraft(
            true,
            "Call OM then Salesforce WFM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(
                serviceCallFact("fact-om", "call-om-result", "Order Management", "onTaskResult"),
                serviceCallFact(
                    "fact-wfm", "call-wfm-create-task", "Salesforce WFM", "createTask")),
            false,
            List.of(
                new RequirementServiceCall(
                    "call-om-result", "fact-om", "Order Management", "onTaskResult", omHint),
                new RequirementServiceCall(
                    "call-wfm-create-task",
                    "fact-wfm",
                    "Salesforce WFM",
                    "createTask",
                    wfmHint)));

    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    TestRuntime first = runtime(blobStore);
    first.store().put("conversation-1", draft);
    assertEquals(
        "2",
        first
            .store()
            .latestRevision("conversation-1")
            .orElseThrow()
            .schemaVersion());

    RequirementDraft recovered = runtime(blobStore).store().get("conversation-1").orElseThrow();

    assertEquals(2, recovered.catalogBindings().size());
    assertEquals("call-om-result", recovered.catalogBindings().get(0).interactionId());
    assertEquals("call-wfm-create-task", recovered.catalogBindings().get(1).interactionId());
    assertEquals("op-shared", recovered.catalogBindings().get(0).integrationOperationId());
    assertEquals("op-shared", recovered.catalogBindings().get(1).integrationOperationId());
    assertEquals("sys-om", recovered.catalogBindings().get(0).systemId());
    assertEquals("sys-wfm", recovered.catalogBindings().get(1).systemId());
  }

  @Test
  void reconstructingStoreRecoversActiveDraft() {
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    runtime(blobStore).store().put("conversation-1", new RequirementDraft(true, "saved draft"));

    RequirementDraft recovered = runtime(blobStore).store().get("conversation-1").orElseThrow();

    assertEquals("saved draft", recovered.assembledText());
  }

  @Test
  void applyImportResultDoesNotBindOneCallDraftForUnknownOwner() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft draft =
        draftWithCalls(serviceCallFact("fact-om", "call-om-result", "OM", "onTaskResult"));
    store.put("conversation-1", draft);

    store.applyImportResult(
        "conversation-1",
        "unknown-call",
        new ResolvedCatalogBinding("sys-imported", "spec-imported", "group-imported", "op-imported"));

    RequirementDraft unchanged = store.get("conversation-1").orElseThrow();
    assertTrue(unchanged.catalogBindings().isEmpty());
  }

  @Test
  void applyImportResultDoesNotBindTwoCallDraftForUnknownOwner() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft draft =
        draftWithCalls(
            serviceCallFact("fact-om", "call-om-result", "OM", "onTaskResult"),
            serviceCallFact(
                "fact-wfm", "call-wfm-create-task", "Salesforce WFM", "createTask"));
    store.put("conversation-1", draft);

    store.applyImportResult(
        "conversation-1",
        "unknown-call",
        new ResolvedCatalogBinding("sys-imported", "spec-imported", "group-imported", "op-imported"));

    RequirementDraft unchanged = store.get("conversation-1").orElseThrow();
    assertTrue(unchanged.catalogBindings().isEmpty());
  }

  @Test
  void withBoundServiceCallReturnsSameDraftForUnknownOwner() {
    RequirementDraft draft =
        draftWithCalls(serviceCallFact("fact-om", "call-om-result", "OM", "onTaskResult"))
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction("start", Direction.INBOUND, "Caller", "start", ""),
                        new Interaction(
                            "call-om-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
                    List.of(new Transition("start", "call-om-result"))));
    CatalogBindingHint hint =
        new CatalogBindingHint(
            "2",
            "unknown-call",
            "fact-unknown",
            "unknown",
            "sys-imported",
            "group-imported",
            "spec-imported",
            "op-imported",
            "http",
            "POST",
            "/unknown",
            "2024.4",
            Instant.EPOCH,
            "test");

    RequirementDraft unchanged = draft.withBoundInteraction("unknown-call", hint);

    assertTrue(unchanged.catalogBindings().isEmpty());
  }

  private static RequirementDraft draftWithCalls(RequirementFact... calls) {
    return new RequirementDraft(
        false,
        "Call external systems",
        DraftDecision.NEEDS_INPUT,
        List.of("Resolve calls"),
        "brainstorming",
        "1",
        null,
        null,
        false,
        List.of(calls),
        false);
  }

  private static RequirementFact serviceCallFact(
      String sourceFactId, String serviceCallId, String participant, String operation) {
    return new RequirementFact(
        sourceFactId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "",
        "Call " + participant + " " + operation,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
  }

  private static TestRuntime runtime(InMemoryArtifactBlobStore blobStore) {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.systemUTC());
    CompilationSessions sessions =
        new CompilationSessions(blobStore, mapper, Clock.systemUTC());
    return new TestRuntime(
        artifacts, sessions, new RequirementDraftStore(artifacts, sessions));
  }

  private record TestRuntime(
      CompilationArtifacts artifacts,
      CompilationSessions sessions,
      RequirementDraftStore store) {}
}
