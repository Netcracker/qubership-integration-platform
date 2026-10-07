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
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
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
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureResult.NextAction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementCaptureToolsTest {

  @ParameterizedTest
  @ValueSource(strings = {"456", "flow"})
  void namedPropertyCorrectionsKeepTheirCreditsAcrossStoreReconstruction(String propertyKey) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "named-property-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(mapper);
    first.store().beginTurn(chat, text);
    var base = readyHttpDraft();
    java.util.function.Function<String, DraftInput> candidate = key -> {
      var intent = new RequirementElementIntent("entry", "http-trigger",
          RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
          List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty(key, "POST")), List.of());
      var fact = new FactInput("http-config", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
          "Accept POST /tasks.", null, null, intent);
      return new DraftInput(base.flow(), List.of(base.facts().getFirst(), fact),
          base.capabilities(), base.openQuestions(), base.settings());
    };
    try (var ignored = ToolSession.open(chat)) {
      var initial = mapper.readValue(first.tools().captureRequirementDraft(candidate.apply("123")),
          RequirementCaptureResult.class);
      assertEquals(NextAction.REPAIR_CAPTURE, initial.nextAction());
      assertTrue(initial.issues().stream().allMatch(issue -> "INVALID_REQUESTED_PROPERTY".equals(issue.code())));
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.tools().elementSchemas = first.tools().elementSchemas;
      restored.store().beginTurn(chat, "Repair the generated property.");
      assertEquals(NextAction.REPAIR_CAPTURE, mapper.readValue(
          restored.tools().captureRequirementDraft(candidate.apply(propertyKey)), RequirementCaptureResult.class).nextAction());
      var again = persistentHarness(blobs, mapper, conversations, chat);
      again.tools().elementSchemas = first.tools().elementSchemas;
      again.store().beginTurn(chat, "Repair the generated property.");
      assertEquals(NextAction.STOP, mapper.readValue(
          again.tools().captureRequirementDraft(candidate.apply(propertyKey)), RequirementCaptureResult.class).nextAction());
    }
  }

  @Test
  void differentFactReferenceErrorsKeepIndependentCreditsAfterReconstruction() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "fact-reference-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var factA = new FactInput("factA", List.of("missingA"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Keep the request context.");
    var factB = new FactInput("factB", List.of("entry"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Prepare the request body.");
    var initial = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), factA, factB),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    var correctedA = new FactInput("factA", List.of("entry"), factA.kind(), factA.polarity(), factA.text());
    var invalidB = new FactInput("factB", List.of("missingB"), factB.kind(), factB.polarity(), factB.text());
    var second = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), correctedA, invalidB),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      var resultA = mapper.readValue(first.tools().captureRequirementDraft(initial), RequirementCaptureResult.class);
      assertEquals(List.of("factA"), resultA.issues().stream().map(issue -> issue.entityId()).toList());
      assertEquals(NextAction.REPAIR_CAPTURE, resultA.nextAction());
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      var resultB = mapper.readValue(restored.tools().captureRequirementDraft(second), RequirementCaptureResult.class);
      assertEquals(List.of("factB"), resultB.issues().stream()
          .filter(issue -> "UNKNOWN_INTERACTION_REFERENCE".equals(issue.code()))
          .map(issue -> issue.entityId()).toList());
      assertEquals(NextAction.REPAIR_CAPTURE, resultB.nextAction());
      var reordered = new DraftInput(ready.flow(), List.of(invalidB, correctedA, ready.facts().getFirst()),
          ready.capabilities(), ready.openQuestions(), ready.settings());
      assertEquals(NextAction.STOP,
          mapper.readValue(restored.tools().captureRequirementDraft(reordered), RequirementCaptureResult.class).nextAction());
    }
  }

  @Test
  void unchangedCaptureCannotGainCreditByRestartingButANewAuthorInputCan() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "credit-restart-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text())), ready.capabilities(), ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      assertEquals(NextAction.REPAIR_CAPTURE,
          mapper.readValue(first.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class).nextAction());
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      assertTrue(restored.store().lastCaptureRejection(chat).orElseThrow().contains("INVALID_VALUE"));
      assertEquals(NextAction.STOP,
          mapper.readValue(restored.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class).nextAction());
      restored.store().recordCaptureRejection(chat, "Repeated invalid sourceFactId.");

      var next = archive.beginInput(chat, text, null);
      archive.resolveInput(chat, next, text);
      restored.store().beginTurn(chat, text);
      assertTrue(restored.store().lastCaptureRejection(chat).isEmpty());
      assertEquals(NextAction.REPAIR_CAPTURE,
          mapper.readValue(restored.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class).nextAction());
      assertTrue(mapper.readValue(restored.tools().captureRequirementDraft(ready), RequirementCaptureResult.class).accepted());
      var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
      assertEquals(4, artifacts.history(first.store().activeCompilationId(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void creditWriteFailureRetainsCandidateAndHonorsTheStoredOutcome(boolean commitBeforeFailure) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = org.mockito.Mockito.spy(new InMemoryArtifactBlobStore());
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "credit-write-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text())), ready.capabilities(), ready.openQuestions(), ready.settings());
    var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(call -> {
      if ("CAPTURE_CREDITS".equals(mapper.readTree((byte[]) call.getArgument(1)).path("kind").asText())
          && failOnce.getAndSet(false)) {
        if (commitBeforeFailure) {
          call.callRealMethod();
        }
        throw new IllegalStateException("Capture credit acknowledgment unavailable");
      }
      return call.callRealMethod();
    }).when(blobs).put(anyString(), any(byte[].class));
    try (var ignored = ToolSession.open(chat)) {
      org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
          () -> first.tools().captureRequirementDraft(invalid));
      var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
      var candidate = artifacts.latest(first.store().activeCompilationId(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload();
      assertEquals(mapper.valueToTree(invalid), candidate.get("arguments"));
      assertEquals("INVALID_VALUE", candidate.path("issues").get(0).path("code").asText());
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      var repeated = mapper.readValue(restored.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
      assertEquals(commitBeforeFailure ? NextAction.STOP : NextAction.REPAIR_CAPTURE, repeated.nextAction());
      assertTrue(mapper.readValue(restored.tools().captureRequirementDraft(ready), RequirementCaptureResult.class).accepted());
      assertEquals(1, new ToolCallFingerprintStore(mapper, artifacts,
          new CompilationSessions(blobs, mapper, Clock.systemUTC())).softCreditsUsed(chat));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedCaptureRetainsItsCompleteCandidateFindingsAndMutationScope(boolean verifyScope) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "candidate-restart-" + UUID.randomUUID();
    String text = "Accept POST /tasks and submit the task.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text())), ready.capabilities(), ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      var rejected = mapper.readValue(first.tools().captureRequirementDraft(invalid), RequirementCaptureResult.class);
      assertFalse(rejected.accepted());
      String scope = first.store().captureMutationScope(chat);
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      if (verifyScope) {
        assertEquals(scope, restored.store().captureMutationScope(chat));
        return;
      }
      var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
      var saved = artifacts.latest(first.store().activeCompilationId(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT);
      assertTrue(saved.isPresent(), "The complete rejected candidate and findings must be retained.");
      var attempt = saved.orElseThrow().payload();
      assertEquals(mapper.valueToTree(invalid), attempt.get("arguments"));
      assertEquals(mapper.valueToTree(rejected.issues()), attempt.get("issues"));
      assertEquals("captureRequirementDraft", attempt.path("toolName").asText());
      assertEquals(source.turnId(), attempt.path("sourceTurnId").asText());
      assertTrue(mapper.readValue(restored.tools().captureRequirementDraft(ready), RequirementCaptureResult.class).accepted());
      assertEquals(scope, artifacts.latest(first.store().activeCompilationId(chat),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload().path("mutationScope").asText());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedInitializationProtectsFactsAfterStoreReconstruction(boolean clearPriorAttempt) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "rejected-restart-" + UUID.randomUUID();
    String text = "Create an external route with RBAC.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var goal = ready.facts().getFirst();
    var visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
        Polarity.POSITIVE, "External HTTP route with RBAC.");
    var invalid = new DraftInput(ready.flow(), List.of(new FactInput("1", goal.interactionIds(),
        goal.kind(), goal.polarity(), goal.text()), visibility), ready.capabilities(),
        ready.openQuestions(), ready.settings());
    try (var ignored = ToolSession.open(chat)) {
      assertFalse(mapper.readValue(first.tools().captureRequirementDraft(invalid),
          RequirementCaptureResult.class).accepted());
      if (clearPriorAttempt) {
        first.store().clearTurnFlags(chat);
        first.store().beginTurn(chat, text);
        assertFalse(mapper.readValue(first.tools().captureRequirementDraft(invalid),
            RequirementCaptureResult.class).accepted());
      }
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      var loss = mapper.readValue(restored.tools().captureRequirementDraft(ready), RequirementCaptureResult.class);
      assertFalse(loss.accepted(), loss.toString());
      assertEquals("REPAIR_FACT_LOSS", loss.issues().getFirst().code());
      var corrected = new DraftInput(ready.flow(), List.of(goal, visibility), ready.capabilities(),
          ready.openQuestions(), ready.settings());
      assertTrue(mapper.readValue(restored.tools().captureRequirementDraft(corrected),
          RequirementCaptureResult.class).accepted());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedCaptureProtectionSurvivesRestartUntilANewAuthorTurn(boolean sourceAcknowledgmentFails) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var archive = org.mockito.Mockito.spy(
        new ConversationArchive(blobs, mapper));
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "accepted-restart-" + UUID.randomUUID();
    String text = "Create an external route with RBAC.";
    var source = archive.beginInput(chat, text, null);
    archive.resolveInput(chat, source, text);
    var first = persistentHarness(blobs, mapper, conversations, chat);
    first.store().beginTurn(chat, text);
    var ready = readyHttpDraft();
    var visibility = new FactInput("visibility", List.of("entry"), FactKind.VISIBILITY,
        Polarity.POSITIVE, "External HTTP route with RBAC.");
    var input = new DraftInput(ready.flow(), List.of(ready.facts().getFirst(), visibility),
        ready.capabilities(), ready.openQuestions(), ready.settings());
    var removal = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of("visibility"), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    try (var ignored = ToolSession.open(chat)) {
      if (sourceAcknowledgmentFails) {
        org.mockito.Mockito.doThrow(new IllegalStateException("source acknowledgment unavailable"))
            .when(archive).consume(anyString(), anyString(), any(), anyString());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> first.tools().captureRequirementDraft(input));
        org.mockito.Mockito.doCallRealMethod().when(archive).consume(anyString(), anyString(), any(), anyString());
        var sameProcessLoss = mapper.readValue(first.tools().updateRequirementDraft(removal),
            RequirementCaptureResult.class);
        assertFalse(sameProcessLoss.accepted(), sameProcessLoss.toString());
        assertEquals("REPAIR_FACT_LOSS", sameProcessLoss.issues().getFirst().code());
      } else {
        assertTrue(mapper.readValue(first.tools().captureRequirementDraft(input), RequirementCaptureResult.class).accepted());
      }
      var restored = persistentHarness(blobs, mapper, conversations, chat);
      restored.store().beginTurn(chat, "Repair the generated draft.");
      var loss = mapper.readValue(restored.tools().updateRequirementDraft(removal), RequirementCaptureResult.class);
      assertFalse(loss.accepted(), loss.toString());
      assertEquals("REPAIR_FACT_LOSS", loss.issues().getFirst().code());
      assertEquals(input.facts(), restored.store().get(chat).orElseThrow().authoredDraft().facts());

      var next = archive.beginInput(chat, "Remove the external route requirement.", null);
      archive.resolveInput(chat, next, "Remove the external route requirement.");
      restored.store().beginTurn(chat, "Remove the external route requirement.");
      assertTrue(mapper.readValue(restored.tools().updateRequirementDraft(removal), RequirementCaptureResult.class).accepted());
    }
  }

  private static Harness persistentHarness(
      InMemoryArtifactBlobStore blobs,
      ObjectMapper mapper,
      ConversationService conversations,
      String chat) {
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var store = new RequirementDraftStore(artifacts, sessions, conversations);
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var fingerprints = new ToolCallFingerprintStore(mapper, artifacts, sessions);
    var feedback = new CaptureAttemptFeedbackStore(fingerprints, artifacts, sessions);
    return new Harness(store, new RequirementCaptureTools(store, adapter, mapper,
        new CaptureToolOutcomeGateway(fingerprints, feedback), feedback, null), mapper, chat);
  }

  @Test
  void legacySchemaCannotAuthorizeAnUnsupportedCompilerType() throws Exception {
    assertTrue(new org.qubership.integration.platform.ai.schema.SchemaResourceLoader()
        .existsElementSchema("split-async"));
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var mapper = new ObjectMapper().findAndRegisterModules();
    var tools = new RequirementCaptureTools(store, adapter, mapper);
    String chat = "compiler-type-" + UUID.randomUUID();
    store.beginTurn(chat, "Create one asynchronous split.");
    try (var ignored = ToolSession.open(chat)) {
      for (String type : List.of("split-async", "if", "else", "async-split-element-2", "catch-2", "split-async-2")) {
        var intent = new RequirementElementIntent("split", type, RequirementElementIntent.Placement.LOCAL_NODE,
            "entry", null, "entry", null, List.of(), List.of());
        var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
            "Caller", "GET /check", null, null, null)), List.of()),
            List.of(new FactInput("split-config", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
                "Create one asynchronous split.", null, null, intent)),
            List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/check",
                null, null, null)), List.of(), new DraftSettings(null, null));
        var result = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
        assertEquals(type.equals("split-async-2"), result.accepted(), result.issues().toString());
        if (!result.accepted()) {
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("UNSUPPORTED_COMPILER_ELEMENT_TYPE")));
          assertTrue(store.get(chat).isEmpty());
        } else {
          assertEquals(intent, store.get(chat).orElseThrow().authoredDraft().facts().getFirst().elementIntent());
        }
      }
    }
  }

  @Test
  void differentInvalidPropertiesCanBeRepairedInTheSameAuthorTurn() throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Accept POST /tasks and submit the task.");
    var base = readyHttpDraft();
    var method = new org.qubership.integration.platform.ai.plan.model.PlanProperty("httpMethodRestrict", "POST");
    var attempts = List.of(
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("method", "POST")),
        List.of(method, new org.qubership.integration.platform.ai.plan.model.PlanProperty("route", "/tasks")),
        List.of(method));
    try (var ignored = ToolSession.open(h.conversationId())) {
      for (int attempt = 0; attempt < attempts.size(); attempt++) {
        var intent = new RequirementElementIntent("entry", "http-trigger",
            RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
            attempts.get(attempt), List.of());
        var fact = new FactInput("http-config", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
            "Accept POST /tasks.", null, null, intent);
        var input = new DraftInput(base.flow(), List.of(base.facts().getFirst(), fact),
            base.capabilities(), base.openQuestions(), base.settings());
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(input), RequirementCaptureResult.class);
        assertFalse(result.issues().stream().anyMatch(issue -> issue.code().equals("REPEATED_REJECTION")),
            result.toString());
        assertEquals(attempt == attempts.size() - 1, result.accepted(), result.toString());
        if (result.accepted()) {
          assertEquals(input, h.store().get(h.conversationId()).orElseThrow().authoredDraft());
          var finished = h.mapper().readValue(
              h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
              RequirementCaptureResult.class);
          assertTrue(finished.accepted(), finished.toString());
        } else {
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_REQUESTED_PROPERTY")),
              result.toString());
          assertTrue(h.store().get(h.conversationId()).isEmpty());
        }
      }
    }
  }

  @Test
  void invalidConsumerKeyCanBeRepairedWithoutLosingTheRequestedGroup() throws Exception {
    var h = harness();
    h.tools().elementSchemas = org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(h.mapper());
    h.store().beginTurn(h.conversationId(), "Consume tasks using Kafka consumer group CIP.");
    try (var ignored = ToolSession.open(h.conversationId())) {
      for (String key : List.of("consumerGroupId", "integrationOperationAsyncProperties")) {
        var intent = new RequirementElementIntent("entry", "async-api-trigger",
            RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty(key,
                key.equals("consumerGroupId") ? "CIP" : "{\"groupId\":\"CIP\"}")), List.of());
        var draft = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
            "Kafka", "onTaskStart", null, null, null)), List.of()),
            List.of(new FactInput("consumer", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
                "Consume tasks using Kafka consumer group CIP.", null, null, intent)),
            List.of(new CapabilityInput("entry", "async-api-trigger", null, null, null,
                null, null, null)), List.of(), new DraftSettings(null, null));
        var result = h.mapper().readValue(h.tools().captureRequirementDraft(draft), RequirementCaptureResult.class);
        if (key.equals("consumerGroupId")) {
          assertFalse(result.accepted());
          assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_REQUESTED_PROPERTY")
              && issue.message().contains("integrationOperationAsyncProperties")), result.issues().toString());
        } else {
          assertTrue(result.accepted(), result.issues().toString());
          assertEquals(intent, h.store().get(h.conversationId()).orElseThrow()
              .authoredDraft().facts().getFirst().elementIntent());
        }
      }
    }
  }

  @Test
  void alteredLiteralCannotBeCapturedAsAnExactAuthorValue() throws Exception {
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var mapper = new ObjectMapper().findAndRegisterModules();
    var tools = new RequirementCaptureTools(store, adapter, mapper);
    String chat = "literal-source-" + UUID.randomUUID();
    store.beginTurn(chat, "Respond with {\"error\":\"test-header incorrect value\"}.");
    var wrong = new RequirementElementIntent("set-error", "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, null, null, List.of(), List.of("{\"error\":\"test-header incorrect values\"}"));
    var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller",
        "GET /check", null, null, null)), List.of()), List.of(new FactInput("error-body", List.of("entry"),
            FactKind.BEHAVIOR, Polarity.POSITIVE, "Respond with {\"error\":\"test-header incorrect values\"}", null, null, wrong)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/check",
            null, null, null)), List.of(), new DraftSettings(null, null));
    try (var ignored = ToolSession.open(chat)) {
      var result = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertFalse(result.accepted());
      assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("ELEMENT_LITERAL_SOURCE_MISMATCH")));
      assertTrue(store.get(chat).isEmpty());
      String literal = "{\"error\":\"test-header incorrect value\"}";
      var correct = new RequirementElementIntent(wrong.elementRef(), wrong.elementType(), wrong.placement(),
          wrong.ownerInteractionId(), null, null, null, List.of(), List.of(literal));
      var accepted = mapper.readValue(tools.captureRequirementDraft(new DraftInput(input.flow(),
          List.of(new FactInput("error-body", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
              "Respond with " + literal, null, null, correct)), input.capabilities(), input.openQuestions(),
          input.settings())), RequirementCaptureResult.class);
      assertTrue(accepted.accepted(), accepted.issues().toString());
      assertEquals(correct, store.get(chat).orElseThrow().authoredDraft().facts().getFirst().elementIntent());
    }
  }

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

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = RequirementDiscoveryDirective.class,
      names = {"STAY", "CONTINUE"})
  void finishRefreshesTheCatalogBindingResolvedAfterCapture(RequirementDiscoveryDirective directive) throws Exception {
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    var mapper = new ObjectMapper().findAndRegisterModules();
    var tools = new RequirementCaptureTools(store, adapter, mapper);
    var base = readyHttpDraft();
    var input = new DraftInput(base.flow(), base.facts(), List.of(base.capabilities().getFirst()),
        base.openQuestions(), base.settings());
    var binding = new org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint(
        "3", "submit", "submit", "Task API submit", "sys", "group", "spec", "operation",
        "http", "POST", "/tasks", "2026.1", java.time.Instant.now(), "catalog-read:operation");
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(List.of(), List.of(binding));
    String chat = "finish-resolved-" + UUID.randomUUID();
    try (var ignored = ToolSession.open(chat)) {
      var captured = mapper.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertEquals(RequirementCaptureResult.Readiness.PARTIAL, captured.readiness());
      assertEquals(NextAction.LOOKUP, captured.nextAction());

      var finished = mapper.readValue(tools.finishRequirementDiscoveryTurn(directive), RequirementCaptureResult.class);

      assertTrue(finished.accepted(), finished.toString());
      assertEquals(RequirementCaptureResult.Readiness.READY, finished.readiness());
      assertTrue(finished.pendingWork().isEmpty());
      assertEquals(List.of(binding), store.get(chat).orElseThrow().catalogBindings());
      assertEquals(input, store.get(chat).orElseThrow().authoredDraft());
      assertEquals(directive, store.turnDirective(chat));
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
