package org.qubership.integration.platform.ai.plan;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chat.ToolSession;
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
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureResult.NextAction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureToolsTest {

  @Test
  void asksWhichFolderWhenTheNameMatchesSeveral() throws Exception {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool bindingAdapter = mock(RequirementDraftTool.class);
    when(bindingAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of());
    CatalogFolders folders = mock(CatalogFolders.class);
    when(folders.tree())
        .thenReturn(List.of(
            new CatalogFolders.Folder("f1", "A", null, "A"),
            new CatalogFolders.Folder("f2", "Salesforce", "f1", "A/Salesforce"),
            new CatalogFolders.Folder("f3", "Salesforce", null, "Salesforce")));
    CaptureAttemptFeedbackStore feedback =
        new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore());
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementCaptureTools tools = new RequirementCaptureTools(store, bindingAdapter, mapper,
        new CaptureToolOutcomeGateway(feedback.fingerprintStore(), feedback), feedback, null,
        folders);
    List<FactInput> facts = List.of(new FactInput("goal", List.of(), FactKind.GOAL,
        Polarity.POSITIVE, "Create a task."));

    try (ToolSession.Handle ignored = ToolSession.open("capture-folder-" + UUID.randomUUID())) {
      RequirementCaptureResult ambiguous = mapper.readValue(
          tools.captureRequirementDraft(new DraftInput(new FlowInput(List.of(), List.of()), facts,
              List.of(), List.of(), new DraftSettings(null, null, "salesforce"))),
          RequirementCaptureResult.class);
      assertFalse(ambiguous.accepted());
      assertEquals("AMBIGUOUS_TARGET_FOLDER", ambiguous.issues().getFirst().code());
      assertTrue(ambiguous.issues().getFirst().message().contains("A/Salesforce, Salesforce"));

      RequirementCaptureResult exact = mapper.readValue(
          tools.captureRequirementDraft(new DraftInput(new FlowInput(List.of(), List.of()), facts,
              List.of(), List.of(), new DraftSettings(null, null, "A/Salesforce"))),
          RequirementCaptureResult.class);
      assertTrue(exact.accepted());
    }
  }

  @Test
  void savesPartialDraftAndAppliesOneFocusedUpdate() throws Exception {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool bindingAdapter = mock(RequirementDraftTool.class);
    when(bindingAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of());
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementCaptureTools tools = new RequirementCaptureTools(store, bindingAdapter, mapper);
    String conversationId = "capture-test-" + UUID.randomUUID();
    DraftInput first = new DraftInput(new FlowInput(List.of(), List.of()),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE,
            "Create a task.")), List.of(), List.of(), new DraftSettings(false, null));

    try (ToolSession.Handle ignored = ToolSession.open(conversationId)) {
      RequirementCaptureResult captured = mapper.readValue(
          tools.captureRequirementDraft(first), RequirementCaptureResult.class);
      assertTrue(captured.accepted());
      assertTrue(captured.changed());
      assertEquals(RequirementCaptureResult.Readiness.PARTIAL, captured.readiness());
      assertNotNull(captured.revision());
      assertEquals(first, store.get(conversationId).orElseThrow().authoredDraft());

      RequirementCaptureResult duplicate = mapper.readValue(
          tools.captureRequirementDraft(first), RequirementCaptureResult.class);
      assertTrue(duplicate.accepted());
      assertFalse(duplicate.changed());
      assertEquals(captured.revision(), duplicate.revision());

      store.beginTurn(conversationId, "Create one task for each input.");
      FactInput changedFact = new FactInput("goal", List.of(), FactKind.GOAL,
          Polarity.POSITIVE, "Create one task for each input.");
      DraftUpdate change = new DraftUpdate(List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(changedFact), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null);
      RequirementCaptureResult updated = mapper.readValue(
          tools.updateRequirementDraft(change), RequirementCaptureResult.class);
      assertTrue(updated.accepted());
      assertTrue(updated.changed());
      assertEquals(changedFact, updated.draft().facts().getFirst());
      assertEquals(changedFact, store.get(conversationId).orElseThrow().authoredDraft()
          .facts().getFirst());
      assertEquals(updated.revision(), mapper.readValue(
          tools.readRequirementDraft(), RequirementCaptureResult.class).revision());
    }
  }

  @Test
  void continueIsRefusedWhileInteractionFieldsAreBlank() throws Exception {
    Harness harness = harness();
    try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
      harness.tools().captureRequirementDraft(partialKafkaSenderDraft());

      RequirementCaptureResult finished = harness.mapper().readValue(
          harness.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);

      assertFalse(finished.accepted());
      assertEquals(NextAction.COMPLETE_DRAFT, finished.nextAction());
      assertEquals(
          RequirementDiscoveryDirective.STAY, harness.store().turnDirective(harness.conversationId()));
      String message = finished.issues().getFirst().message();
      assertTrue(message.contains("http-trigger is missing operation"), message);
      assertTrue(message.contains("script-set-body is missing direction and participant"), message);
      assertTrue(message.contains("kafka-sender is missing operation"), message);
      assertTrue(message.contains("Use updateRequirementDraft"), message);
      assertTrue(message.contains("Do not tell the user the draft is ready"), message);
      assertNotNull(harness.store().get(harness.conversationId()).orElseThrow().authoredDraft());

      RequirementCaptureResult repeated = harness.mapper().readValue(
          harness.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      String repeatedMessage = repeated.issues().getFirst().message();
      assertFalse(repeated.accepted());
      assertEquals(NextAction.COMPLETE_DRAFT, repeated.nextAction());
      assertEquals(1, repeated.issues().size(), repeated.issues().toString());
      assertTrue(repeatedMessage.contains("http-trigger is missing operation"), repeatedMessage);
      assertFalse(repeatedMessage.toLowerCase().contains("repeated rejection"), repeatedMessage);
    }
  }

  @Test
  void continueIsAcceptedWhenTheSavedDraftIsReady() throws Exception {
    Harness harness = harness();
    try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
      RequirementCaptureResult captured = harness.mapper().readValue(
          harness.tools().captureRequirementDraft(readyHttpDraft()), RequirementCaptureResult.class);
      assertEquals(RequirementCaptureResult.Readiness.READY, captured.readiness(), captured.toString());

      RequirementCaptureResult finished = harness.mapper().readValue(
          harness.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);

      assertTrue(finished.accepted(), finished.toString());
      assertTrue(finished.issues().isEmpty(), finished.issues().toString());
      assertEquals(
          RequirementDiscoveryDirective.CONTINUE,
          harness.store().turnDirective(harness.conversationId()));
    }
  }

  @Test
  void completionRejectsFactLossWithoutChangingTheRevision() throws Exception {
    for (String loss : List.of("delete", "text", "scope")) {
      boolean delete = loss.equals("delete");
      Harness harness = harness();
      harness.store().beginTurn(harness.conversationId(), "Create an external route with RBAC.");
      DraftInput ready = readyHttpDraft();
      FactInput visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
          Polarity.POSITIVE, "External HTTP route with RBAC.");
      DraftInput partial = new DraftInput(
          new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Client",
              null, null, null, null), ready.flow().interactions().getLast()),
              ready.flow().transitions()),
          List.of(ready.facts().getFirst(), visibility), ready.capabilities(), List.of(), ready.settings());
      try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
        RequirementCaptureResult captured = harness.mapper().readValue(
            harness.tools().captureRequirementDraft(partial), RequirementCaptureResult.class);
        assertTrue(captured.accepted(), captured.toString());
        FactInput shortened = new FactInput("visibility",
            loss.equals("scope") ? List.of("submit") : List.of("entry"), FactKind.VISIBILITY,
            Polarity.POSITIVE,
            loss.equals("text") ? "HTTP route with RBAC." : "External HTTP route with RBAC.");
        DraftUpdate change = new DraftUpdate(List.of(), List.of(ready.flow().interactions().getFirst()),
            List.of(), List.of(), List.of(), List.of(), delete ? List.of() : List.of(shortened),
            delete ? List.of("visibility") : List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), null);
        RequirementCaptureResult rejected = harness.mapper().readValue(
            harness.tools().updateRequirementDraft(change), RequirementCaptureResult.class);
        assertFalse(rejected.accepted(), rejected.toString());
        assertEquals("REPAIR_FACT_LOSS", rejected.issues().getFirst().code());
        assertEquals(captured.revision(), rejected.revision());
        assertEquals(partial, harness.store().get(harness.conversationId()).orElseThrow().authoredDraft());

        DraftUpdate correction = new DraftUpdate(List.of(),
            List.of(ready.flow().interactions().getFirst()), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        RequirementCaptureResult repaired = harness.mapper().readValue(
            harness.tools().updateRequirementDraft(correction), RequirementCaptureResult.class);
        assertTrue(repaired.accepted(), repaired.toString());
        assertEquals(RequirementCaptureResult.Readiness.READY, repaired.readiness());
        assertEquals(partial.facts(), repaired.draft().facts());

        harness.store().beginTurn(harness.conversationId(), "Remove the external route requirement.");
        DraftUpdate authorRemoval = new DraftUpdate(List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of("visibility"), List.of(), List.of(), List.of(),
            List.of(), List.of(), null);
        RequirementCaptureResult removed = harness.mapper().readValue(
            harness.tools().updateRequirementDraft(authorRemoval), RequirementCaptureResult.class);
        assertTrue(removed.accepted(), removed.toString());
        assertEquals(List.of(ready.facts().getFirst()), removed.draft().facts());
      }
    }
  }

  @Test
  void completionMovesLocalBehaviorWhenRemovingAnIncorrectInteraction() throws Exception {
    Harness harness = harness();
    harness.store().beginTurn(harness.conversationId(), "Set body, then send the message to Kafka.");
    DraftInput initial = partialKafkaSenderDraft();
    FactInput behavior = new FactInput("set-body", List.of("script-set-body"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Set body script sets body to {\"message\":\"test\"} before sending to Kafka.");
    DraftInput partial = new DraftInput(initial.flow(), List.of(initial.facts().getFirst(), behavior),
        initial.capabilities(), List.of(), initial.settings());
    try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
      harness.tools().captureRequirementDraft(partial);
      FactInput moved = new FactInput(behavior.sourceFactId(), List.of("kafka-sender"), behavior.kind(),
          behavior.polarity(), behavior.text());
      DraftUpdate changes = new DraftUpdate(List.of(), List.of(
          new InteractionInput("http-trigger", Direction.INBOUND, "Caller",
              "POST /auto-tests/kafka-sender", null, null, null),
          new InteractionInput("kafka-sender", Direction.OUTBOUND, "Kafka", "Send message",
              null, null, null)), List.of("script-set-body"),
          List.of(new TransitionInput("http-trigger", "kafka-sender")),
          initial.flow().transitions(), List.of(), List.of(moved), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null);
      RequirementCaptureResult repaired = harness.mapper().readValue(
          harness.tools().updateRequirementDraft(changes), RequirementCaptureResult.class);
      assertTrue(repaired.accepted(), repaired.toString());
      assertEquals(RequirementCaptureResult.Readiness.READY, repaired.readiness());
      assertEquals(List.of(initial.facts().getFirst(), moved), repaired.draft().facts());
    }
  }

  @Test
  void rejectedInitializationPreservesKnownFactsWhileAllowingAnInvalidIdToChange() throws Exception {
    Harness harness = harness();
    harness.store().beginTurn(harness.conversationId(), "Create an external route with RBAC.");
    DraftInput ready = readyHttpDraft();
    FactInput goal = ready.facts().getFirst();
    FactInput invalidId = new FactInput("1", goal.interactionIds(), goal.kind(), goal.polarity(), goal.text());
    FactInput visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
        Polarity.POSITIVE, "External HTTP route with RBAC.");
    DraftInput invalid = new DraftInput(ready.flow(), List.of(invalidId, visibility),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
      RequirementCaptureResult first = harness.mapper().readValue(
          harness.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
      assertFalse(first.accepted());
      assertTrue(harness.store().get(harness.conversationId()).isEmpty());

      RequirementCaptureResult lost = harness.mapper().readValue(
          harness.tools().captureRequirementDraft(ready), RequirementCaptureResult.class);
      assertFalse(lost.accepted(), lost.toString());
      assertEquals("REPAIR_FACT_LOSS", lost.issues().getFirst().code());
      assertTrue(harness.store().get(harness.conversationId()).isEmpty());

      DraftInput corrected = new DraftInput(ready.flow(), List.of(goal, visibility),
          ready.capabilities(), ready.openQuestions(), ready.settings());
      RequirementCaptureResult repaired = harness.mapper().readValue(
          harness.tools().captureRequirementDraft(corrected), RequirementCaptureResult.class);
      assertTrue(repaired.accepted(), repaired.toString());
      assertEquals(corrected.facts(), repaired.draft().facts());
    }
  }

  @Test
  void authorLanguageDoesNotChangeCaptureValidation() throws Exception {
    DraftInput ready = readyHttpDraft();
    FactInput behavior = new FactInput("forward-body", List.of("submit"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Forward the request body unchanged.");
    DraftInput draft = new DraftInput(ready.flow(), List.of(behavior), ready.capabilities(),
        ready.openQuestions(), ready.settings());
    for (String authorText : List.of(
        "Purpose: Forward the request body unchanged.",
        "Objectif: Transmettre le corps de la requete sans modification.",
        "Objetivo: Reenviar el cuerpo de la solicitud sin cambios.",
        "Forward the request body unchanged.")) {
      Harness harness = harness();
      harness.store().beginTurn(harness.conversationId(), authorText);
      try (ToolSession.Handle ignored = ToolSession.open(harness.conversationId())) {
        RequirementCaptureResult captured = harness.mapper().readValue(
            harness.tools().captureRequirementDraft(draft), RequirementCaptureResult.class);
        assertTrue(captured.accepted(), captured.toString());
        assertEquals(RequirementCaptureResult.Readiness.READY, captured.readiness());
        assertEquals(draft, captured.draft());
      }
    }
  }

  private static Harness harness() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool bindingAdapter = mock(RequirementDraftTool.class);
    when(bindingAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of());
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    return new Harness(
        store,
        new RequirementCaptureTools(store, bindingAdapter, mapper),
        mapper,
        "capture-test-" + UUID.randomUUID());
  }

  private record Harness(
      RequirementDraftStore store,
      RequirementCaptureTools tools,
      ObjectMapper mapper,
      String conversationId) {}

  private static DraftInput partialKafkaSenderDraft() {
    return new DraftInput(
        new FlowInput(
            List.of(
                new InteractionInput(
                    "http-trigger", Direction.INBOUND, "Caller", null, null, null, null),
                new InteractionInput(
                    "script-set-body", null, null, "Set body", null, null, null),
                new InteractionInput(
                    "kafka-sender", Direction.OUTBOUND, "Kafka", null, null, null, null)),
            List.of(
                new TransitionInput("http-trigger", "script-set-body"),
                new TransitionInput("script-set-body", "kafka-sender"))),
        List.of(new FactInput(
            "goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Send a Kafka message.")),
        List.of(
            new CapabilityInput(
                "http-trigger", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
                "/auto-tests/kafka-sender", null, null, null),
            new CapabilityInput(
                "kafka-sender", "kafka-sender-2", null, null, null,
                "cip-auto-tests-topic1", null, null)),
        List.of(),
        new DraftSettings(null, null));
  }

  private static DraftInput readyHttpDraft() {
    return new DraftInput(
        new FlowInput(
            List.of(
                new InteractionInput(
                    "entry", Direction.INBOUND, "Client", "POST /tasks", null, null, null),
                new InteractionInput(
                    "submit", Direction.OUTBOUND, "Task API", "submit", null, null, null)),
            List.of(new TransitionInput("entry", "submit"))),
        List.of(new FactInput(
            "goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, "Submit one task.")),
        List.of(
            new CapabilityInput(
                "entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/tasks", null, null, null),
            new CapabilityInput(
                "submit", "http-sender", null, HttpMethod.POST, "https://example.com/tasks",
                null, null, null)),
        List.of(),
        new DraftSettings(null, null));
  }
}
