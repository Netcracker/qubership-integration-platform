package org.qubership.integration.platform.ai.productpipeline.create;

import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.nativeAnchorFact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftCapture;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.AmendmentInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.plan.RequirementDiscoveryDirective;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.CipKnowledgeCapabilityPhase;

class RequirementDiscoveryCapabilityTest {

  @Test
  void readyButtonCannotBypassMissingNativeAnchorMetadata() {
    var store = new RequirementDraftStore();
    var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", RequirementFlow.Direction.INBOUND,
        "Caller", "POST /work", null, null, null)), List.of()),
        List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Accept incoming work.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/work", null, null, null)), List.of(), new DraftSettings(null, null));
    store.put("anchor-button", org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.toDraft(
        input, null, List.of(), "1", "hash"));
    var capability = new RequirementDiscoveryCapability(null, store, (cid, text) -> {
      throw new AssertionError("The ready-button path must validate the saved state before dispatching a producer.");
    });
    var signals = capability.execute(new StageExecutionContext("anchor-run", "anchor-button", "requirement-discovery",
        "execution", "attempt", null, null, List.of(), Map.of("userText", ChatEvent.CONTINUE_TO_PLANNING_ACTION)))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.recoveryCause().findings().stream()
        .anyMatch(finding -> "MISSING_ANCHOR_INTENT".equals(finding.code())));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aPersistedPolicyQuestionSurvivesAFabricatedResponseButNotATechnicalDefect(boolean technical) throws Exception {
    var h = captureHarness(null);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var schemas = h.tools().getClass().getDeclaredField("elementSchemas");
    schemas.setAccessible(true);
    schemas.set(h.tools(), org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
        .createForUnitTests(mapper));
    String chat = "policy-discovery";
    var policy = new org.qubership.integration.platform.ai.plan.RequirementPolicyConflict(
        org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator.EXTERNAL_RBAC_POLICY,
        "accessControlType", "NONE", "RBAC", "security-choice", null);
    var input = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput(List.of(
            new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput("entry",
                RequirementFlow.Direction.INBOUND, "Caller", "GET /policy", null, null, null)), List.of()),
        List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput("access", List.of("entry"),
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind.CONSTRAINT,
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity.POSITIVE,
            "Request NONE; RBAC is the recommendation.", null, policy),
            new FactInput("external-route", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
                "Expose the HTTP endpoint externally.", null, null,
                new org.qubership.integration.platform.ai.plan.RequirementElementIntent("entry", "http-trigger",
                    org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.ANCHOR,
                    "entry", null, null, null,
                    List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("externalRoute", "true")),
                    List.of()))),
        List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput("entry", "http-trigger",
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode.CUSTOM,
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod.GET, "/policy", null, null, null)),
        List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput("security-choice",
            List.of("entry"), "Keep NONE after reviewing the RBAC recommendation?")),
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings(false, null));
    try (var ignored = org.qubership.integration.platform.ai.chat.ToolSession.open(chat)) {
      assertTrue(mapper.readValue(h.tools().captureRequirementDraft(input),
          org.qubership.integration.platform.ai.plan.RequirementCaptureResult.class).accepted());
    }
    var capability = new RequirementDiscoveryCapability(null, h.store(), (cid, text) -> {
      h.feedback().recordClassifiedRequirementFailure(cid,
          org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind.VALIDATION,
          org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass.CORRECTABLE, true,
          "Rejected response", List.of(new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue(
              technical ? "INVALID_TYPE" : "POLICY_RESPONSE_REQUIRED", "/draft/facts/access/policyConflict", "Rejected response")));
      h.store().finishTurn(cid, RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    var signals = capability.execute(new StageExecutionContext("policy-run", chat, "requirement-discovery",
        "execution", "attempt", null, null, List.of(), Map.of("userText", "Initial policy request")))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(technical ? StageOutcomeClass.CONTRACT_FAILURE : StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass());
    assertEquals(policy, h.store().get(chat).orElseThrow().facts().getFirst().policyConflict());
  }

  @Test
  void discussionAboutAPausedDraftDoesNotRequireAnAmendment() {
    var store = new RequirementDraftStore();
    var initial = new RequirementDraft(false, "Known requirements", DraftDecision.NEEDS_INPUT,
        List.of("Which endpoint should receive the request?"), "gather", "1", "hash", null, false,
        petstoreServiceCallDraft().facts(), false, null, false, petstoreServiceCallDraft().flow(), List.of(), null);
    store.put("discussion-chat", initial);
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      store.finishTurn(chat, RequirementDiscoveryDirective.DISCUSS);
      return Multi.createFrom().item(ChatEvent.token("This endpoint receives the result."));
    });
    var signals = capability.execute(new StageExecutionContext("discussion-run", "discussion-chat",
        "requirement-discovery", "execution", "attempt", null, null, List.of(),
        Map.of("userText", "What is this endpoint for?", "admittedInputGate", PipelineGates.STAGE_CLARIFICATION,
            "admittedInputRevision", 6L))).collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass());
    assertEquals(initial, store.get("discussion-chat").orElseThrow());
  }

  @Test
  void anAdmittedClarificationCannotRepeatTheUnchangedQuestion() {
    var store = new RequirementDraftStore();
    var initial = new RequirementDraft(false, "Known requirements", DraftDecision.NEEDS_INPUT,
        List.of("Which endpoint should receive the request?"), "gather", "1", "hash", null, false,
        petstoreServiceCallDraft().facts(), false, null, false, petstoreServiceCallDraft().flow(), List.of(), null);
    store.put("answered-chat", initial);
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      store.finishTurn(chat, RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    });
    var signals = capability.execute(new StageExecutionContext("answered-run", "answered-chat",
        "requirement-discovery", "execution", "attempt", null, null, List.of(),
        Map.of("userText", "Use the existing endpoint.", "admittedInputGate", PipelineGates.STAGE_CLARIFICATION,
            "admittedInputRevision", 6L))).collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.recoveryCause().findings().stream().anyMatch(f -> "UNAPPLIED_AUTHOR_ANSWER".equals(f.code())));
  }

  @ParameterizedTest
  @ValueSource(strings = {"yes", "no", "already-no"})
  void missingHttpSenderHostRemainsProducerWorkUntilAScopedAuthorQuestionIsSaved(String choice) throws Exception {
    var h = captureHarness(null);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    String chat = "missing-helper-host";
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /context", null, null, null),
        new InteractionInput("helper", Direction.OUTBOUND, "Get Context Headers", "GET /context-headers",
            null, null, null)), List.of(new TransitionInput("entry", "helper"))),
        List.of(new FactInput("goal", List.of("helper"), FactKind.GOAL, Polarity.POSITIVE,
            "Inspect the request context through the helper endpoint.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
                "/context", null, null, null),
            new CapabilityInput("helper", "http-sender", null, HttpMethod.GET,
                "/context-headers", null, null, null)),
        List.of(), new DraftSettings("already-no".equals(choice) ? false : null, null));
    try (var ignored = ToolSession.open(chat)) {
      h.store().beginTurn(chat, "Inspect the context through Get Context Headers."
          + ("already-no".equals(choice) ? " Do not show an IDS." : ""));
      assertTrue(mapper.readTree(h.tools().captureRequirementDraft(input)).path("accepted").asBoolean());
    }
    var calls = new AtomicInteger();
    var capability = new RequirementDiscoveryCapability(null, h.store(), (conversation, text) -> {
      calls.incrementAndGet();
      h.tools().readRequirementDraft();
      h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE);
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    var attrs = new java.util.HashMap<String, Object>();
    attrs.put("userText", "already-no".equals(choice) ? "Continue to planning" : choice);
    if (!"already-no".equals(choice)) {
      attrs.put("admittedInputGate", PipelineGates.IDS_PATH_CHOICE);
      attrs.put("admittedInputRevision", 2L);
    }
    var signals = capability.execute(new StageExecutionContext("host-run", chat, "requirement-discovery",
        "exec", "attempt", discoveryProfile(List.of(new ArtifactTypeRef("requirement-draft", 2)),
        List.of(new ArtifactTypeRef("ids-bypass", 1))), null, List.of(), attrs))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.toString());
    assertNotNull(outcome.recoveryCause());
    assertTrue(outcome.recoveryCause().findings().stream().anyMatch(finding ->
        finding.code().equals("CAPABILITY_FIELD_REQUIRED")
            && finding.message().contains("full runtime HTTP URI")), outcome.toString());
    assertEquals("already-no".equals(choice) ? 1 : 0, calls.get());
    assertFalse(h.store().get(chat).orElseThrow().readyForPlan());
    try (var ignored = ToolSession.open(chat)) {
      var view = mapper.readTree(h.tools().readRequirementDraft());
      assertEquals("COMPLETE_DRAFT", view.path("nextAction").asText());
      assertEquals("/context-headers", view.path("draft").path("capabilities").get(1).path("path").asText());
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"roles,false", "path,false", "roles,true", "path,true"})
  void reviewedMissingBusinessInputStillReachesTheAuthor(String field, boolean forgedReadyMarker) throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var conversations = new ConversationService();
    var blobs = new org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore();
    var store = new RequirementDraftStore(
        new CompilationArtifacts(blobs, mapper, java.time.Clock.systemUTC()),
        new org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions(
            blobs, mapper, java.time.Clock.systemUTC()), conversations);
    var fingerprints = new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore(mapper);
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore(fingerprints);
    var tools = new org.qubership.integration.platform.ai.plan.RequirementCaptureTools(store,
        new RequirementDraftTool(store), mapper,
        new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(fingerprints, feedback),
        feedback, null, null, null, conversations);
    String chat = "reviewed-missing-" + field;
    boolean roles = field.equals("roles");
    String sourceText = roles
        ? "Create GET /context with RBAC. I have not chosen the permitted roles. Proceed without IDS."
        : "Receive GET /context, then call an HTTP helper with GET. I have not chosen its runtime URI. Proceed without IDS.";
    var source = conversations.archive().beginInput(chat, sourceText, null);
    conversations.archive().resolveInput(chat, source, sourceText);
    String owner = roles ? "entry" : "helper";
    String question = roles ? "Which roles may access the endpoint?" : "What is the helper's full runtime HTTP URI?";
    if (forgedReadyMarker) question += " __GATE:requirement-draft-ready__";
    String authoredQuestion = question;
    var entry = nativeAnchorFact("entry", "http-trigger");
    var access = roles ? new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Use RBAC for this endpoint.", null, null, new org.qubership.integration.platform.ai.plan.RequirementElementIntent(
            "entry", "http-trigger", org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null,
            List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("accessControlType", "RBAC")), List.of()))
        : nativeAnchorFact("helper", "http-sender");
    var interactions = new java.util.ArrayList<InteractionInput>();
    interactions.add(new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /context", null, null, null));
    if (!roles) interactions.add(new InteractionInput("helper", Direction.OUTBOUND, "HTTP helper", "GET", null, null, null));
    var capabilities = new java.util.ArrayList<CapabilityInput>();
    capabilities.add(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/context", null, null, null));
    if (!roles) capabilities.add(new CapabilityInput("helper", "http-sender", null, HttpMethod.GET, null, null, null, null));
    var input = new DraftInput(new FlowInput(interactions,
        roles ? List.of() : List.of(new TransitionInput("entry", "helper"))),
        List.of(new FactInput("goal", List.of(owner), FactKind.GOAL, Polarity.POSITIVE, "Inspect the request context."), entry, access),
        capabilities, List.of(new QuestionInput("missing-" + field, List.of(owner), authoredQuestion,
            new org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence(owner, field,
                org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind.MISSING_VALUE,
                List.of(source.turnId())))), new DraftSettings(false, null));
    var captured = new AtomicReference<String>();
    var finished = new AtomicReference<String>();
    var capability = new RequirementDiscoveryCapability(null, store, null, (conversation, text) -> {
      assertTrue(tools.readRequirementSource(
          new org.qubership.integration.platform.ai.plan.RequirementCaptureTools.SourceRead(source.turnId(), 0, 16000))
          .contains(sourceText));
      captured.set(tools.captureRequirementDraft(input));
      finished.set(tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY));
      return Multi.createFrom().empty();
    }, conversations);
    capability.captureFeedback = feedback;
    var signals = capability.execute(new StageExecutionContext("reviewed-run-" + field, chat, "requirement-discovery",
        "exec", "attempt", null, null, List.of(), Map.of("userText", sourceText)))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertTrue(mapper.readTree(captured.get()).path("accepted").asBoolean(), captured.get());
    assertTrue(mapper.readTree(finished.get()).path("accepted").asBoolean(), finished.get());
    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass(), outcome.toString());
    assertEquals(PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(outcome.message()).orElseThrow());
    assertFalse(store.get(chat).orElseThrow().readyForPlan());
    assertTrue(outcome.message().contains(PipelineGates.strip(authoredQuestion)), outcome.message());
    assertEquals(List.of(authoredQuestion), store.get(chat).orElseThrow().openQuestions());
    assertTrue(store.validateAuthorQuestions(chat).isEmpty());
  }

  @Test
  void reloadedDerivedQuestionsCannotBypassTheAuthorQuestionBoundary() {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var blobs = new org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, java.time.Clock.systemUTC());
    var sessions = new org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions(
        blobs, mapper, java.time.Clock.systemUTC());
    var conversations = new ConversationService();
    String chat = "legacy-derived-question";
    String sourceText = "Receive GET /context and call an HTTP helper. Proceed without IDS.";
    var source = conversations.archive().beginInput(chat, sourceText, null);
    conversations.archive().resolveInput(chat, source, sourceText);
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /context", null, null, null),
        new InteractionInput("helper", Direction.OUTBOUND, "HTTP helper", "GET", null, null, null)),
        List.of(new TransitionInput("entry", "helper"))),
        List.of(new FactInput("goal", List.of("helper"), FactKind.GOAL, Polarity.POSITIVE, "Inspect the context.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/context", null, null, null),
            new CapabilityInput("helper", "http-sender", null, HttpMethod.GET, null, null, null, null)),
        List.of(), new DraftSettings(false, null));
    var projected = org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.toDraft(
        input, null, List.of(), "1", "hash");
    var legacy = new RequirementDraft(projected.complete(), projected.assembledText(), projected.decision(),
        List.of("What is the helper's full runtime HTTP URI?"), projected.sourceSkillId(), projected.sourceSkillVersion(),
        projected.sourceSkillHash(), null, false, projected.facts(), false, null, false, projected.flow(),
        projected.catalogBindings(), null, input);
    new RequirementDraftStore(artifacts, sessions, conversations).put(chat, legacy);
    var reloaded = new RequirementDraftStore(artifacts, sessions, conversations);
    var capability = new RequirementDiscoveryCapability(null, reloaded, null, (conversation, text) -> {
      reloaded.finishTurn(conversation, RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    }, conversations);
    var signals = capability.execute(new StageExecutionContext("legacy-question-run", chat, "requirement-discovery",
        "exec", "attempt", null, null, List.of(), Map.of("userText", "Continue to planning")))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.toString());
    assertTrue(outcome.recoveryCause().findings().stream().anyMatch(finding ->
        finding.code().equals("AUTHOR_QUESTION_NOT_SUPPORTED")), outcome.toString());
    assertEquals(legacy, reloaded.get(chat).orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeCompositionQuestionsRetainTheirResolutionState(boolean resolved) {
    var store = new RequirementDraftStore();
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /context", null, null, null),
        new InteractionInput("child", Direction.OUTBOUND, "Existing chain", "Run", null, null, null)),
        List.of(new TransitionInput("entry", "child"))),
        List.of(new FactInput("goal", List.of("child"), FactKind.GOAL, Polarity.POSITIVE, "Call the existing chain."),
            nativeAnchorFact("entry", "http-trigger"), nativeAnchorFact("child", "chain-call-2")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/context", null, null, null),
            new CapabilityInput("child", "chain-call-2", null, null, null, null, null, null)),
        List.of(), new DraftSettings(false, null));
    var projected = org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.toDraft(
        input, null, List.of(), "1", "hash", resolved
            ? Map.of("child", "11111111-1111-4111-8111-111111111111") : Map.of());
    assertEquals(resolved, projected.openQuestions().isEmpty());
    store.put("composition-choice", projected);
    var capability = new RequirementDiscoveryCapability(null, store, (conversation, text) -> {
      store.finishTurn(conversation, RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    });
    var signals = capability.execute(new StageExecutionContext("composition-choice-run", "composition-choice",
        "requirement-discovery", "exec", "attempt", null, null, List.of(), Map.of("userText", "Continue to planning")))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass(), outcome.toString());
    if (resolved) {
      assertEquals(PipelineGates.REQUIREMENT_DRAFT_READY, PipelineGates.gateOf(outcome.message()).orElseThrow());
      assertTrue(store.get("composition-choice").orElseThrow().openQuestions().isEmpty());
    } else {
      assertTrue(outcome.message().contains(projected.openQuestions().getFirst()), outcome.message());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void catalogResolutionAfterCaptureUsesTheSettledDraft(boolean idsUndecided) {
    var store = new RequirementDraftStore();
    var initial = readyDraftWithUnboundServiceCall();
    var settled = petstoreServiceCallDraft().withIdsRequested(idsUndecided ? null : false);
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      store.put(chat, initial);
      store.markCaptured(chat);
      ProductCapabilityCaptureContext.offerDraft(initial);
      store.put(chat, settled);
      store.finishTurn(chat, RequirementDiscoveryDirective.CONTINUE);
      return Multi.createFrom().item(ChatEvent.token("Would you like an IDS?"));
    });
    var signals = capability.execute(new StageExecutionContext("resolved-run", "resolved-chat",
        "requirement-discovery", "execution", "attempt",
        discoveryProfile(List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("ids-bypass", 1))), null, List.of(),
        Map.of("userText", "Call Petstore Ext GET /pets.")))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    if (idsUndecided) {
      assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass());
      assertEquals(PipelineGates.IDS_PATH_CHOICE,
          PipelineGates.gateOf(outcome.message()).orElseThrow());
    } else {
      assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass());
      assertEquals(settled, outcome.candidates().stream()
          .filter(candidate -> candidate.kind() == CompilationArtifacts.Kind.REQUIREMENT_DRAFT)
          .findFirst().orElseThrow().payload());
    }
  }

  @Test
  void settledReadyCaptureDoesNotPublishSpeculativeQuestions() {
    var store = new RequirementDraftStore();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      store.put(chat, RequirementFactFixtures.greetingsApprovedDraft());
      store.markCaptured(chat);
      store.finishTurn(chat, RequirementDiscoveryDirective.CONTINUE);
      return Multi.createFrom().item(ChatEvent.token("Should I correct the generated mapping notation?"));
    });
    var signals = capability.execute(new StageExecutionContext("settled-run", "settled-chat",
        "requirement-discovery", "execution", "attempt", null, null, List.of(),
        Map.of("userText", "Create the chain."))).collect().asList().await().indefinitely();
    assertEquals(StageOutcomeClass.CANDIDATE, signals.stream()
        .filter(CapabilitySignal.Completed.class::isInstance).map(CapabilitySignal.Completed.class::cast)
        .findFirst().orElseThrow().outcome().outcomeClass());
    assertTrue(signals.stream().noneMatch(CapabilitySignal.Message.class::isInstance),
        "A ready capture must not publish an unrecorded question");
  }

  @Test
  void rejectedAttemptDoesNotPublishRefusalBeforeAutomaticRecovery() {
    var h = captureHarness(null);
    var capability = new RequirementDiscoveryCapability(null, h.store(), (chat, text) -> {
      h.tools().captureRequirementDraft(new DraftInput(null, List.of(new FactInput("goal", List.of(),
          FactKind.GOAL, Polarity.POSITIVE, "Create a task.")), List.of(), List.of(), new DraftSettings(null, null)));
      return Multi.createFrom().item(ChatEvent.token("I could not save the draft. Construction stopped."));
    });
    capability.captureFeedback = h.feedback();
    var signals = capability.execute(new StageExecutionContext("refusal-run", "refusal-chat",
        "requirement-discovery", "execution", "attempt", null, null, List.of(),
        Map.of("userText", "Create a task."))).collect().asList().await().indefinitely();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, signals.stream()
        .filter(CapabilitySignal.Completed.class::isInstance).map(CapabilitySignal.Completed.class::cast)
        .findFirst().orElseThrow().outcome().outcomeClass());
    assertTrue(signals.stream().noneMatch(CapabilitySignal.Message.class::isInstance));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = RequirementDiscoveryDirective.class, names = {"STAY", "NONE"})
  void missingCaptureCannotBecomeAnEmptyWaitOrPublishAnInventedTimeout(RequirementDiscoveryDirective directive) {
    var store = new RequirementDraftStore();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      store.finishTurn(chat, directive);
      return Multi.createFrom().item(ChatEvent.token("The catalog lookup timed out. Try again later."));
    });
    var signals = capability.execute(new StageExecutionContext("missing-run", "missing-chat",
        "requirement-discovery", "execution", "attempt", null, null, List.of(),
        Map.of("userText", "Create the AsyncAPI chain."))).collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertEquals("CAPTURE_MISSING", outcome.recoveryCause().findings().getFirst().code());
    assertTrue(signals.stream().noneMatch(CapabilitySignal.Message.class::isInstance),
        "Failure explanations must come from the recorded outcome");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = RequirementDiscoveryDirective.class, names = {"STAY", "NONE", "CONTINUE"})
  void unresolvedCatalogWorkCannotBecomeAnAuthorWait(RequirementDiscoveryDirective directive) {
    var store = new RequirementDraftStore();
    var authored = new DraftInput(new FlowInput(List.of(
        new InteractionInput("asyncEvent", Direction.INBOUND, "Auto tests kafka service", "onTaskStart", null, null, null),
        new InteractionInput("commonVariables", Direction.OUTBOUND, "cloud-integration-platform-catalog",
            "GET /v1/common-variables", null, null, null)), List.of(new TransitionInput("asyncEvent", "commonVariables"))),
        List.of(new FactInput("topic", List.of("asyncEvent"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Use topic classifier cip-auto-tests-topic2.")),
        List.of(new CapabilityInput("asyncEvent", "async-api-trigger", null, null, null, null, null, null)),
        List.of(), new DraftSettings(null, null));
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      store.put(chat, org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.toDraft(
          authored, null, List.of(), "1", "hash"));
      store.markCaptured(chat);
      store.finishTurn(chat, directive);
      return Multi.createFrom().item(ChatEvent.token("The catalog lookup timed out. Try again later."));
    });
    var signals = capability.execute(new StageExecutionContext("lookup-run", "lookup-chat",
        "requirement-discovery", "execution", "attempt", null, null, List.of(),
        Map.of("userText", "Create the AsyncAPI chain."))).collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.recoveryCause().findings().stream().allMatch(finding ->
        "OPERATION_LOOKUP_REQUIRED".equals(finding.code())));
    assertEquals(2, outcome.recoveryCause().findings().size());
    assertTrue(signals.stream().noneMatch(CapabilitySignal.Message.class::isInstance));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void savedSourceResumesDiscoveryOnlyAfterTheRunHasInput(boolean inputRecorded) {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var archive = new org.qubership.integration.platform.ai.chat.conversation.ConversationArchive(
        new org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore(), mapper);
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    String chat = "source-retry";
    var source = archive.beginInput(chat, RequirementFactFixtures.GREETINGS_PROMPT, null);
    archive.resolveInput(chat, source, RequirementFactFixtures.GREETINGS_PROMPT);
    var calls = new AtomicInteger();
    var store = new RequirementDraftStore();
    var capability = new RequirementDiscoveryCapability(null, store, null, (cid, text) -> {
      assertEquals(RequirementFactFixtures.GREETINGS_PROMPT, text);
      calls.incrementAndGet();
      return Multi.createFrom().empty();
    }, conversations);
    var refs = inputRecorded ? List.of(new CompilationArtifacts.Reference(CompilationArtifacts.Kind.USER_INPUT,
        "saved-input", "source-hash")) : List.<CompilationArtifacts.Reference>of();
    capability.execute(new StageExecutionContext("saved-source-run", chat, "requirement-discovery",
        "execution", "attempt", null, null, refs, Map.of()))
        .collect().asList().await().indefinitely();
    assertEquals(inputRecorded ? 1 : 0, calls.get());
    assertEquals(1, archive.pendingInputs(chat).size());
  }

  @Test
  void savedReadyDraftOffersContinuationWhenRetryHasNoNewAuthorMessage() {
    var store = new RequirementDraftStore();
    store.put("ready-retry", RequirementFactFixtures.greetingsApprovedDraft());
    var calls = new AtomicInteger();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      calls.incrementAndGet();
      return Multi.createFrom().empty();
    });
    var outcome = capability.execute(new StageExecutionContext("ready-retry-run", "ready-retry",
        "requirement-discovery", "execution", "attempt", null, null, List.of(), Map.of()))
        .collect().asList().await().indefinitely().stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass());
    assertEquals(PipelineGates.REQUIREMENT_DRAFT_READY, PipelineGates.gateOf(outcome.message()).orElseThrow());
    assertEquals(0, calls.get());
  }

  @Test
  void technicalRetryWithoutAFieldPointerResumesTheRequirementsProducer() {
    var store = new RequirementDraftStore();
    var calls = new AtomicInteger();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      calls.incrementAndGet();
      assertTrue(ProductCapabilityCaptureContext.binding(chat).orElseThrow().requirementRepairPaths().isEmpty());
      store.put(chat, RequirementFactFixtures.greetingsApprovedDraft());
      store.markCaptured(chat);
      store.finishTurn(chat, RequirementDiscoveryDirective.CONTINUE);
      return Multi.createFrom().empty();
    });
    var attributes = new java.util.HashMap<String, Object>();
    attributes.put("userText", RequirementFactFixtures.GREETINGS_PROMPT);
    attributes.put(org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
        .STAGE_ERROR_CONTEXT_ATTR, "java.net.UnknownHostException: api.openai.com");
    attributes.put(org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
        .STAGE_ERROR_CAUSE_JSON_ATTR, """
        {"causeCode":"TECHNICAL_RETRY_EXHAUSTED","findings":[
          {"code":"FAILURE_SIGNATURE","message":"java.net.UnknownHostException: api.openai.com","blocker":true}
        ]}
        """);
    var outcome = capability.execute(new StageExecutionContext("technical-retry-run", "technical-retry-chat",
        "requirement-discovery", "execution", "attempt", null, null, List.of(), attributes))
        .collect().asList().await().indefinitely().stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(1, calls.get());
    assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void savedDiscoveryRepairWithoutANewAuthorMessageDispatchesTheProducer(boolean clean) {
    var store = new RequirementDraftStore();
    var calls = new AtomicInteger();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      calls.incrementAndGet();
      assertFalse(text.isBlank());
      assertTrue(text.contains("CONTRACT_SHAPE"));
      return Multi.createFrom().empty();
    });
    var attributes = new java.util.HashMap<String, Object>();
    attributes.put(org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
        .STAGE_ERROR_CONTEXT_ATTR, "CONTRACT_SHAPE: Invalid generated flow.");
    attributes.put(org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
        .STAGE_ERROR_CAUSE_CODE_ATTR, "CONTRACT_SHAPE");
    if (clean) {
      attributes.put(org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
          .PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR, "repair-evidence");
    }
    capability.execute(new StageExecutionContext("run-discovery-repair", "conv-discovery-repair",
        "requirement-discovery", "execution", "attempt", null, null, List.of(), attributes))
        .collect().asList().await().indefinitely();
    assertEquals(1, calls.get(), "Internal output repair must not wait for a new author message");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void automaticDiscoveryReentryCannotDropAcceptedOrRejectedFacts(boolean initialCaptureAccepted) {
    var h = captureHarness(null);
    var attempts = new AtomicInteger();
    var goal = new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Create one task.");
    var constraint = new FactInput("body-logging", List.of(), FactKind.CONSTRAINT,
        Polarity.NEGATIVE, "Do not log the input body.");
    var complete = new DraftInput(new FlowInput(List.of(), List.of()),
        List.of(goal, constraint), List.of(),
        List.of(new QuestionInput("entry", List.of(), "What starts the integration?")),
        new DraftSettings(null, null));
    var lost = new DraftInput(complete.flow(), List.of(goal), List.of(), List.of(), complete.settings());
    var capability = new RequirementDiscoveryCapability(null, h.store(), (chat, text) -> {
      int attempt = attempts.getAndIncrement();
      if (attempt == 0) {
        h.tools().captureRequirementDraft(initialCaptureAccepted ? complete
            : new DraftInput(null, complete.facts(), List.of(), List.of(), complete.settings()));
      } else if (initialCaptureAccepted && attempt == 1) {
        h.tools().updateRequirementDraft(new DraftUpdate(List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(constraint.sourceFactId()),
            List.of(), List.of(), List.of(), List.of(), List.of(), null));
      } else {
        h.tools().captureRequirementDraft(attempt == 1 ? lost : complete);
      }
      h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    assertEquals(initialCaptureAccepted ? StageOutcomeClass.NEEDS_INPUT : StageOutcomeClass.CONTRACT_FAILURE,
        discoveryOutcome(capability).outcomeClass());

    var rejected = discoveryOutcome(capability);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, rejected.outcomeClass(), rejected.toString());
    assertTrue(h.feedback().lastRequirementRejection("conv-rejected").orElseThrow().issues().stream()
        .anyMatch(issue -> "REPAIR_FACT_LOSS".equals(issue.code())));
    if (initialCaptureAccepted) {
      assertEquals(complete, h.store().get("conv-rejected").orElseThrow().authoredDraft());
    } else {
      assertTrue(h.store().get("conv-rejected").isEmpty());
    }

    assertEquals(StageOutcomeClass.NEEDS_INPUT, discoveryOutcome(capability).outcomeClass());
    assertEquals(complete, h.store().get("conv-rejected").orElseThrow().authoredDraft());
    assertTrue(h.feedback().lastRequirementRejection("conv-rejected").isEmpty());
  }

  @Test
  void ambiguousFolderRemainsAnAuthorChoice() {
    var folders = mock(org.qubership.integration.platform.ai.chain.folder.CatalogFolders.class);
    when(folders.tree()).thenReturn(List.of(
        new org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Folder("one", "Salesforce", null,
            "Salesforce"),
        new org.qubership.integration.platform.ai.chain.folder.CatalogFolders.Folder("two", "Salesforce", "parent",
            "Parent/Salesforce")));
    var h = captureHarness(folders);
    var input = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput(List.of(), List.of()),
        List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput("goal", List.of(),
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind.GOAL,
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity.POSITIVE, "Create one task.")),
        List.of(), List.of(),
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings(false, null, "Salesforce"));
    var capability = new RequirementDiscoveryCapability(null, h.store(), (chat, text) -> {
      h.tools().captureRequirementDraft(input);
      h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    assertEquals(StageOutcomeClass.NEEDS_INPUT, discoveryOutcome(capability).outcomeClass());
    assertEquals("AMBIGUOUS_TARGET_FOLDER", h.feedback().lastRequirementRejection("conv-rejected")
        .orElseThrow().issues().getFirst().code());
  }

  @Test
  void repairedCaptureClearsFailureAndCanContinueInTheSameConversation() {
    var h = captureHarness(null);
    var attempts = new AtomicInteger();
    var goal = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput("goal", List.of(),
        org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind.GOAL,
        org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity.POSITIVE, "Accept POST /tasks.");
    var input = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput(List.of(
            new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput("entry",
                Direction.INBOUND, "Client", "POST /tasks", null, null, null)), List.of()),
        List.of(goal, nativeAnchorFact("entry", "http-trigger")), List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput(
            "entry", "http-trigger", org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode.CUSTOM,
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod.POST, "/tasks", null, null, null)),
        List.of(), new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings(null, null));
    var capability = new RequirementDiscoveryCapability(null, h.store(), (chat, text) -> {
      if (attempts.getAndIncrement() == 0) {
        h.tools().captureRequirementDraft(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
            null, List.of(goal), List.of(), List.of(), null));
        h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY);
      } else {
        h.tools().captureRequirementDraft(input);
        h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE);
      }
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, discoveryOutcome(capability).outcomeClass());
    assertTrue(h.store().lastCaptureRejection("conv-rejected").orElseThrow().contains("MISSING_FIELD"));
    var repaired = discoveryOutcome(capability);
    assertEquals(StageOutcomeClass.CANDIDATE, repaired.outcomeClass(), repaired.toString());
    assertEquals(input, h.store().get("conv-rejected").orElseThrow().authoredDraft());
    assertTrue(h.store().lastCaptureRejection("conv-rejected").isEmpty());
    assertTrue(h.feedback().lastRequirementRejection("conv-rejected").isEmpty());
  }

  private static CaptureHarness captureHarness(
      org.qubership.integration.platform.ai.chain.folder.CatalogFolders folders) {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var store = new RequirementDraftStore();
    var adapter = mock(RequirementDraftTool.class);
    var fingerprints = new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore(mapper);
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore(fingerprints);
    var tools = new org.qubership.integration.platform.ai.plan.RequirementCaptureTools(store, adapter, mapper,
        new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(fingerprints, feedback),
        feedback, null, folders);
    return new CaptureHarness(store, feedback, tools);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeRejectionPrecedesPreservationConsequencesAndKeepsCandidateReferences(boolean repeated) {
    var h = captureHarness(null);
    var base = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /tasks", "Create tasks", null, null)), List.of()),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Create tasks.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/tasks", null, null, null)),
        List.of(), new DraftSettings(null, null));
    var changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new FactInput("retain-id", List.of(), FactKind.CONSTRAINT, Polarity.POSITIVE, "Preserve request IDs.")),
        List.of(), List.of(), List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
            "/tasks", null, null, null)), List.of(), List.of(), List.of(), List.of(), null,
        List.of(new AmendmentInput("/capabilities/entry/httpMethod", "obsolete-source", "Use GET")));
    var capability = new RequirementDiscoveryCapability(null, h.store(), (chat, text) -> {
      h.tools().captureRequirementDraft(base);
      h.tools().updateRequirementDraft(changes);
      if (repeated) h.tools().updateRequirementDraft(changes);
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    var outcome = discoveryOutcome(capability);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    var cause = outcome.recoveryCause();
    assertEquals("INVALID_AUTHOR_AMENDMENT", cause.findings().getFirst().code());
    assertEquals(repeated, cause.noProgress());
    assertFalse(cause.captureFailure().consequentialFindings().isEmpty());
    assertEquals(h.store().latestRevision("conv-rejected").orElseThrow().reference(), cause.captureFailure().acceptedBase());
    assertEquals(org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT,
        cause.captureFailure().rejectedCandidate().kind());
    assertEquals(base, h.store().get("conv-rejected").orElseThrow().authoredDraft());
  }

  @ParameterizedTest
  @ValueSource(strings = {"network", "direct", "wrapped", "synchronous"})
  void captureStopRetainsItsContractClassificationAndNativeEvidence(String kind) {
    var issue = new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue(
        "INVALID_AUTHOR_AMENDMENT", "/changes/amendments/0", "Use the current author source.", "goal");
    var feedbackReference = new CompilationArtifacts.Reference(CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_FEEDBACK,
        "feedback", "feedback-hash");
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback(
        org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind.VALIDATION, "Repeated candidate",
        org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass.IDENTICAL_SPAM,
        false, List.of(), "[]", true, feedbackReference, List.of(issue));
    var evidence = new org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence(
        feedback, new CompilationArtifacts.Reference(CompilationArtifacts.Kind.REQUIREMENT_DRAFT, "base", "base-hash"),
        new CompilationArtifacts.Reference(CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT, "candidate", "candidate-hash"),
        List.of(new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
            "AUTHOR_AMENDMENT_REQUIRED", "Retain the accepted goal.", true)));
    var stopped = new org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException("Repeated candidate", evidence);
    Throwable error = switch (kind) {
      case "network" -> new java.net.UnknownHostException("example.invalid");
      case "wrapped" -> new java.util.concurrent.CompletionException(stopped);
      default -> stopped;
    };
    var capability = new RequirementDiscoveryCapability(null, new RequirementDraftStore(),
        (chat, text) -> {
          if (kind.equals("synchronous")) throw stopped;
          return Multi.createFrom().failure(error);
        }, null);
    var outcome = discoveryOutcome(capability);
    assertEquals(kind.equals("network") ? StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE
        : StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    if (!kind.equals("network")) {
      assertTrue(outcome.recoveryCause().noProgress());
      assertFalse(outcome.recoveryCause().captureFailure().feedback().outerAllowed());
      assertEquals(evidence, outcome.recoveryCause().captureFailure());
      assertEquals(issue.code(), outcome.recoveryCause().findings().getFirst().code());
      assertEquals(org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()),
          outcome.recoveryCause().findings().getFirst().fieldPointer());
      assertEquals(List.of(issue), outcome.recoveryCause().captureFailure().feedback().issues());
    }
  }

  private record CaptureHarness(RequirementDraftStore store,
      org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore feedback,
      org.qubership.integration.platform.ai.plan.RequirementCaptureTools tools) {}

  private static StageOutcome discoveryOutcome(RequirementDiscoveryCapability capability) {
    var context = new StageExecutionContext("run-rejected", "conv-rejected", "requirement-discovery",
        "exec-rejected", "attempt-rejected", null, null, List.of(),
        Map.of("userText", "Create a POST /tasks HTTP trigger."));
    return capability.execute(context).collect().asList().await().indefinitely().stream()
        .filter(CapabilitySignal.Completed.class::isInstance).map(CapabilitySignal.Completed.class::cast)
        .findFirst().orElseThrow().outcome();
  }

  @Test
  void discoveryPreservesTheRejectedFactIdentityAcrossTheStageBoundary() {
    var h = captureHarness(null);
    var capability = new RequirementDiscoveryCapability(null, h.store(), (chat, text) -> {
      h.feedback().recordClassifiedRequirementFailure("conv-rejected",
        org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind.VALIDATION,
        org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass.CORRECTABLE,
        true, "Invalid reference", List.of(
            new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue(
                "UNKNOWN_INTERACTION_REFERENCE", "/draft/facts/1/interactionIds/0", "Invalid reference", "factB")));
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    var outcome = discoveryOutcome(capability);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    var finding = outcome.recoveryCause().findings().getFirst();
    assertEquals("factB", finding.originRecordId());
    assertEquals("/facts[]/interactionIds[]", finding.fieldPointer());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedModelCaptureIsAContractFailureInsteadOfMissingAuthorInput(boolean repeated) {
    var h = captureHarness(null);
    var input = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
        null, List.of(), List.of(), List.of(), null);
    var capability = new RequirementDiscoveryCapability(null, h.store(), (chat, text) -> {
      h.tools().captureRequirementDraft(input);
      if (repeated) {
        h.tools().captureRequirementDraft(input);
      }
      h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    var outcome = discoveryOutcome(capability);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertEquals(org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode.CONTRACT_SHAPE,
        outcome.recoveryCause().causeCode());
    assertFalse(outcome.recoveryCause().findings().isEmpty());
    assertTrue(outcome.recoveryCause().findings().stream()
        .anyMatch(finding -> "MISSING_FIELD".equals(finding.code())));
    var nativeIssues = outcome.recoveryCause().captureFailure().feedback().issues();
    assertTrue(nativeIssues.stream().anyMatch(issue ->
        "MISSING_FIELD".equals(issue.code()) && "/draft/flow".equals(issue.path())));
    assertTrue(nativeIssues.stream().anyMatch(issue ->
        "MISSING_FIELD".equals(issue.code()) && "/draft/settings".equals(issue.path())));
    assertTrue(h.store().get("conv-rejected").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void automaticMappingRepairUsesEvidenceWhileManualChangesKeepAuthorText(boolean automatic) throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var authored = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput(List.of(), List.of()),
        List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput("goal", List.of(),
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind.GOAL,
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity.POSITIVE, "Create tasks.")),
        List.of(), List.of(), new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings(null, null));
    var original = org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.toDraft(
        authored, null, List.of(), null, null);
    var store = new RequirementDraftStore();
    var seen = new AtomicReference<String>();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      seen.set(text);
      store.finishTurn(chat, RequirementDiscoveryDirective.CONTINUE);
      return Multi.createFrom().empty();
    });
    var attrs = Map.<String, Object>of(
        "userText", automatic ? "" : "Change the fallback to the newly requested value.",
        "requirementCorrectionDraft", original,
        "mappingRepairAutomatic", Boolean.toString(automatic),
        "mappingRepairPaths", Map.of(),
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
        "MAPPING_UNKNOWN_TARGET: $.Task.Subject is absent; $.Subject is required.",
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR,
        "MAPPING_CONTRACT");
    capability.execute(new StageExecutionContext("run", "chat", "requirement-discovery", "exec", "attempt",
        null, null, List.of(), attrs)).collect().asList().await().indefinitely();
    if (automatic) {
      assertTrue(seen.get().contains("$.Task.Subject"));
      assertTrue(seen.get().contains("updateRequirementDraft"));
    } else {
      var repair = org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence.from(
          new StageExecutionContext("run", "chat", "requirement-discovery", "exec", "attempt",
              null, null, List.of(), attrs));
      assertEquals(attrs.get("userText") + "\n\n<current-native-repair-evidence>\n"
          + "Current server validation for this repair turn; this is not an author message or amendment authority.\n"
          + repair.diagnosticText() + "\n</current-native-repair-evidence>\n", seen.get());
      assertEquals(attrs.get("userText"), store.currentAuthorText("chat").orElseThrow());
    }
  }

  @Test
  void missingSourceFactsResumeDiscoveryWithoutRequestingTheOriginalTextAgain() {
    var store = new RequirementDraftStore();
    var seen = new AtomicReference<String>();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      seen.set(text);
      return Multi.createFrom().empty();
    });
    var attrs = Map.<String, Object>of(
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
        "The draft omitted the supplied POST method.",
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR,
        "MISSING_BRIEF_FACTS");
    capability.execute(new StageExecutionContext("run", "chat", "requirement-discovery", "exec", "attempt",
        null, null, List.of(), attrs)).collect().asList().await().indefinitely();
    assertNotNull(seen.get(), "Internal repair must reach the producer without another author message.");
    assertTrue(seen.get().contains("readRequirementSource"));
    assertTrue(seen.get().contains("supplied POST method"));
    assertTrue(seen.get().contains("updateRequirementDraft"));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "true,true"})
  void automaticMappingRepairRetainsOnlyAuthorizedEditsAcrossRetries(boolean mixedUpdate, boolean missingTarget) throws Exception {
    var h = captureHarness(null);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var mapping = new FactInput("result-id", List.of("entry", "delivery"), FactKind.FIELD_MAPPING,
        Polarity.POSITIVE, "Return the saved request ID.",
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FieldMappingInput(
            "entry", "$.id", "delivery", "$.id", null));
    var corrected = new FactInput(mapping.sourceFactId(), mapping.interactionIds(), mapping.kind(),
        mapping.polarity(), mapping.text(),
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FieldMappingInput(
            "entry", "$.id", "delivery", "$.parameters.id", null));
    var addition = new FactInput("command", List.of("entry", "delivery"), FactKind.FIELD_MAPPING,
        Polarity.POSITIVE, "Set the delivery command.",
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FieldMappingInput(
            "entry", null, "delivery", "$.command", "Constant completeTask"));
    var initial = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /tasks", "Receive tasks", null, null),
        new InteractionInput("delivery", Direction.OUTBOUND, "Delivery", "POST /results", "Return results", null, null)),
        List.of(new TransitionInput("entry", "delivery"))),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Return task results."), mapping,
            nativeAnchorFact("entry", "http-trigger"), nativeAnchorFact("delivery", "http-sender")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/tasks", null, null, null),
            new CapabilityInput("delivery", "http-sender", null, HttpMethod.POST, "http://localhost/results", null, null, null)),
        List.of(), new DraftSettings(false, null));
    String chat = "automatic-repair-retry";
    try (var ignored = ToolSession.open(chat)) {
      h.store().beginTurn(chat, "Return task results without an IDS.");
      assertTrue(mapper.readTree(h.tools().captureRequirementDraft(initial)).path("accepted").asBoolean());
    }
    var original = h.store().get(chat).orElseThrow();
    var rejected = new AtomicReference<String>();
    var calls = new AtomicInteger();
    var capability = new RequirementDiscoveryCapability(null, h.store(), (cid, text) -> {
      if (calls.getAndIncrement() == 0 && mixedUpdate) {
        rejected.set(h.tools().updateRequirementDraft(new DraftUpdate(List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(addition), List.of(corrected), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), null)));
      }
      h.tools().updateRequirementDraft(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(corrected), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));
      h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE);
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    var targets = missingTarget ? List.of(mapper.convertValue(Map.of("sourceRef", "entry", "targetRef", "delivery",
        "targetPath", "$.command"), org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails.class))
        : List.of();
    var context = new StageExecutionContext("repair-run", chat, "requirement-discovery", "exec", "attempt",
        null, null, List.of(), Map.of("requirementCorrectionDraft", original, "missingMappingTargets", targets,
            "mappingRepairAutomatic", "true", "mappingRepairPaths", Map.of("result-id", java.util.Set.of("targetPath")),
            org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
            "MAPPING_UNKNOWN_TARGET: id must be parameters.id",
            org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR,
            "MAPPING_CONTRACT"));
    var first = capability.execute(context).collect().asList().await().indefinitely();
    if (mixedUpdate) {
      assertEquals(missingTarget, mapper.readTree(rejected.get()).path("accepted").asBoolean(), rejected.get());
      if (!missingTarget) assertTrue(rejected.get().contains("REPAIR_FACT_LOSS"), rejected.get());
    }
    for (var signals : List.of(first, capability.execute(context).collect().asList().await().indefinitely())) {
      var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
          .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
      assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass(), outcome.toString());
    }
    var expected = new java.util.ArrayList<>(List.of(initial.facts().getFirst(), corrected,
        nativeAnchorFact("entry", "http-trigger"), nativeAnchorFact("delivery", "http-sender")));
    if (missingTarget) expected.add(addition);
    assertEquals(expected, h.store().get(chat).orElseThrow().authoredDraft().facts());
    assertTrue(h.feedback().lastRequirementRejection(chat).isEmpty());
    assertEquals(2, calls.get());
  }

  @Test
  void detailedPreservationRefusalCannotAuthorizeItsForbiddenChangesOnReentry() throws Exception {
    var h = captureHarness(null);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    String chat = "mapping-preservation-reentry";
    var goal = new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Retain the original task.");
    var original = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /tasks", "Receive tasks", null, null)), List.of()),
        List.of(goal, nativeAnchorFact("entry", "http-trigger")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/tasks", null, null, null)),
        List.of(), new DraftSettings(false, null));
    try (var ignored = ToolSession.open(chat)) {
      h.store().beginTurn(chat, goal.text() + " Proceed without IDS.");
      assertTrue(mapper.readTree(h.tools().captureRequirementDraft(original)).path("accepted").asBoolean());
    }
    var accepted = h.store().get(chat).orElseThrow();
    var cause = new org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause(
        org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode.CONTRACT_SHAPE,
        List.of(new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
            "REPAIR_FACT_LOSS", "Retain the exact original task.", true,
            org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails.empty(),
            "goal", "/facts/goal/text")), "");
    var checked = new AtomicInteger();
    var capability = new RequirementDiscoveryCapability(null, h.store(), (cid, prompt) -> {
      assertTrue(h.store().activeRequirementRepairPaths(cid).isEmpty(),
          "A preservation refusal describes a prohibited edit; it must not grant that edit on reentry.");
      var rewritten = new FactInput(goal.sourceFactId(), goal.interactionIds(), goal.kind(), goal.polarity(), "Replace the task.");
      var refusal = h.tools().updateRequirementDraft(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(rewritten), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));
      assertTrue(refusal.contains("REPAIR_FACT_LOSS"), refusal);
      checked.incrementAndGet();
      h.tools().finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    });
    capability.captureFeedback = h.feedback();
    var attrs = Map.<String, Object>of("requirementCorrectionDraft", accepted, "mappingRepairAutomatic", "true",
        "mappingRepairFindings", "Retain the exact original task.",
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
        "REPAIR_FACT_LOSS: Retain the exact original task.",
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR,
        "CONTRACT_SHAPE",
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR,
        mapper.writeValueAsString(cause));
    capability.execute(new StageExecutionContext("same-run", chat, "requirement-discovery", "execution", "attempt",
        null, null, List.of(), attrs)).collect().asList().await().indefinitely();
    assertEquals(1, checked.get());
    assertEquals(original, h.store().get(chat).orElseThrow().authoredDraft());
  }

  @Test
  void catalogPathRepairPreservesFactsAndRejectsCollateralChanges() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var mapping = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput(
        "subject-map", List.of("start", "create-task"),
        org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind.FIELD_MAPPING,
        org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity.POSITIVE,
        "Use name, with the requested fallback.",
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FieldMappingInput(
            "start", "name", "create-task", "Task.Subject", "Use the original fallback."));
    var behavior = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput(
        "retain-context", List.of("start", "create-task"),
        org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind.BEHAVIOR,
        org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity.POSITIVE,
        "Save the original identifiers before the call.");
    var tree = mapper.valueToTree(RequirementFactFixtures.greetingsApprovedDraft());
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set("authoredDraft", mapper.valueToTree(
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
            null, List.of(mapping, behavior), List.of(), List.of(), null)));
    var original = mapper.treeToValue(tree, RequirementDraft.class);
    var context = new StageExecutionContext("run", "chat", "requirement-discovery", "exec", "attempt",
        null, null, List.of(), Map.of("mappingRepairPaths", Map.of("subject-map", java.util.Set.of("targetPath"))));
    var candidateTree = tree.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) candidateTree.at("/authoredDraft/facts/0/fieldMapping"))
        .put("targetPath", "Subject");
    assertTrue(RequirementDiscoveryCapability.preservesMappingRepair(context, original,
        mapper.treeToValue(candidateTree, RequirementDraft.class)));

    var changedSource = candidateTree.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changedSource.at("/authoredDraft/facts/0/fieldMapping"))
        .put("sourcePath", "differentName");
    assertFalse(RequirementDiscoveryCapability.preservesMappingRepair(context, original,
        mapper.treeToValue(changedSource, RequirementDraft.class)));
    var sourceContext = new StageExecutionContext("run", "chat", "requirement-discovery", "exec", "attempt",
        null, null, List.of(), Map.of("mappingRepairPaths", Map.of("subject-map", java.util.Set.of("sourcePath"))));
    assertFalse(RequirementDiscoveryCapability.preservesMappingRepair(sourceContext, original,
        mapper.treeToValue(candidateTree, RequirementDraft.class)));
    var sourceOnly = tree.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) sourceOnly.at("/authoredDraft/facts/0/fieldMapping"))
        .put("sourcePath", "differentName");
    assertTrue(RequirementDiscoveryCapability.preservesMappingRepair(sourceContext, original,
        mapper.treeToValue(sourceOnly, RequirementDraft.class)));

    var changedExpression = candidateTree.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changedExpression.at("/authoredDraft/facts/0/fieldMapping"))
        .put("expression", "Invent a different fallback.");
    assertFalse(RequirementDiscoveryCapability.preservesMappingRepair(context, original,
        mapper.treeToValue(changedExpression, RequirementDraft.class)));
    var authorizedExpression = candidateTree.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) authorizedExpression.at("/authoredDraft/facts/0/fieldMapping"))
        .put("expression", "Project the original name to this target field.");
    assertTrue(RequirementDiscoveryCapability.preservesMappingRepair(
        Map.of("subject-map", java.util.Set.of("targetPath", "expression")), List.of(), original,
        mapper.treeToValue(authorizedExpression, RequirementDraft.class)));
    ((com.fasterxml.jackson.databind.node.ArrayNode) candidateTree.at("/authoredDraft/facts")).remove(1);
    assertFalse(RequirementDiscoveryCapability.preservesMappingRepair(context, original,
        mapper.treeToValue(candidateTree, RequirementDraft.class)));
  }

  @Test
  void greetingsDraftPreservesThirteenNegativeFacts() {
    List<RequirementFact> negatives = RequirementFactFixtures.greetingsNegativeFacts();
    assertEquals(13, negatives.size());

    RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
    assertEquals(13, draft.facts().stream().filter(f -> f.polarity() == RequirementFactPolarity.NEGATIVE).count());

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, draft);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(draft);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-greetings-facts",
            "conv-greetings-facts",
            "requirement-discovery",
            "exec-greetings-facts",
            "attempt-greetings-facts",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementFactFixtures.GREETINGS_PROMPT));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    RequirementDraft candidate =
        (RequirementDraft) completed.get().outcome().candidates().get(0).payload();
    assertEquals(
        13,
        candidate.facts().stream().filter(f -> f.polarity() == RequirementFactPolarity.NEGATIVE).count());
  }

  @Test
  void conversationalAskWithParaphrasedReadyDraftAdvancesToCandidate() {
    String userAsk =
        "Hi, create a chain. It has to receive HTTP GET \"/hello\" and return string \"Good day\"";
    RequirementDraft draft =
        new RequirementDraft(
            true,
            "HTTP GET /hello returns string 'Good day'.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(
                new RequirementFact(
                    "hello",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.ENDPOINT,
                    "http-trigger",
                    "HTTP GET /hello",
                    "",
                    "",
                    "",
                    "GET",
                    "/hello"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.BEHAVIOR,
                    "response",
                    "returns string 'Good day'")))
            .withFlow(RequirementFactFixtures.nativeHttpInbound("hello", "GET /hello"));

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, draft);
              store.markCaptured(conversationId);
              store.finishTurn(conversationId, RequirementDiscoveryDirective.CONTINUE);
              ProductCapabilityCaptureContext.offerDraft(draft);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-hello",
            "conv-hello",
            "requirement-discovery",
            "exec-hello",
            "attempt-hello",
            null,
            null,
            List.of(),
            Map.of("userText", userAsk, "discoveryUserText", userAsk));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    assertFalse(completed.get().outcome().message().contains("transcript line absent"));
  }

  @Test
  void discoveryAdvancesWhenDraftReadyDespiteFollowUpClarificationText() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-clarify",
            "conv-clarify",
            "requirement-discovery",
            "exec-clarify",
            "attempt-clarify",
            null,
            null,
            List.of(),
            Map.of(
                "userText",
                "Yes, use a Groovy script for the response.",
                "discoveryUserText",
                RequirementFactFixtures.GREETINGS_PROMPT));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
  }

  @Test
  void readyDraftConsultationStaysInDiscoveryWithoutARequirementCandidate() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft ready = RequirementFactFixtures.greetingsApprovedDraft();
    store.put("conv-consult", ready);
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-consult",
            "conv-consult",
            "requirement-discovery",
            "exec-consult",
            "attempt-consult",
            null,
            null,
            List.of(),
            Map.of("userText", "Why is Kafka a better fit here?"));

    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();
    StageOutcome outcome =
        signals.stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow()
            .outcome();

    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass());
    assertTrue(outcome.candidates().isEmpty());
    assertEquals(
        PipelineGates.REQUIREMENT_DRAFT_READY,
        PipelineGates.gateOf(outcome.message()).orElseThrow());
    assertTrue(outcome.message().contains("Requirements draft saved. Continue to planning when ready."));
    assertEquals(ready, store.get("conv-consult").orElseThrow());
  }

  @Test
  void planningActionAdvancesAReadyDraftWithoutCallingTheGatherAgent() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft ready = RequirementFactFixtures.greetingsApprovedDraft();
    store.put("conv-plan", ready);
    AtomicInteger agentCalls = new AtomicInteger();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              agentCalls.incrementAndGet();
              return Multi.createFrom().empty();
            });
    StageExecutionContext context =
        new StageExecutionContext(
            "run-plan",
            "conv-plan",
            "requirement-discovery",
            "exec-plan",
            "attempt-plan",
            null,
            null,
            List.of(),
            Map.of("userText", ChatEvent.CONTINUE_TO_PLANNING_ACTION));

    StageOutcome outcome =
        capability.execute(context).collect().asList().await().indefinitely().stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow()
            .outcome();

    assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass());
    assertEquals(0, agentCalls.get());
  }

  @Test
  void readyDraftWithoutAnExplicitTurnDirectiveStaysInDiscovery() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft ready = RequirementFactFixtures.greetingsApprovedDraft();
    store.put("conv-no-directive", ready);
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> Multi.createFrom().empty());

    StageExecutionContext context =
        new StageExecutionContext(
            "run-no-directive",
            "conv-no-directive",
            "requirement-discovery",
            "exec-no-directive",
            "attempt-no-directive",
            null,
            null,
            List.of(),
            Map.of("userText", "Explain the current recommendation."));

    StageOutcome outcome =
        capability.execute(context).collect().asList().await().indefinitely().stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow()
            .outcome();

    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass());
    assertTrue(outcome.candidates().isEmpty());
    assertEquals(ready, store.get("conv-no-directive").orElseThrow());
  }

  @Test
  void discoveryCapabilityEmitsBrainstormingSkillProgress() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-skill-activity",
            "conv-skill-activity",
            "requirement-discovery",
            "exec-skill",
            "attempt-skill",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementFactFixtures.GREETINGS_PROMPT));

    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();

    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && "brainstorming".equals(sp.skillId())
                        && "running".equals(sp.status())));
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && "brainstorming".equals(sp.skillId())
                        && "completed".equals(sp.status())));
    assertTrue(
        signals.stream().anyMatch(CapabilitySignal.Completed.class::isInstance));
  }

  @Test
  void discoveryCapabilityEmitsDraftCandidateWithFacts() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-1",
            "conv-discovery",
            "requirement-discovery",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementFactFixtures.GREETINGS_PROMPT));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    RequirementDraft candidate =
        (RequirementDraft) completed.get().outcome().candidates().get(0).payload();
    assertEquals(13, candidate.facts().stream().filter(f -> f.polarity() == RequirementFactPolarity.NEGATIVE).count());
  }

  @Test
  void discoveryCapabilityWrapsUserTextWithBrainstormingAddon() {
    RequirementDraftStore store = new RequirementDraftStore();
    GatherRequirementsAgent agent = mock(GatherRequirementsAgent.class);
    CompilerSkillDocumentService skillDocumentService = mock(CompilerSkillDocumentService.class);
    CompilerSkillAddonRepository addonRepository = mock(CompilerSkillAddonRepository.class);
    when(skillDocumentService.loadByCapabilityId(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(
            new CompilerSkillDocument(
                "brainstorming",
                "brainstorming",
                "skills/brainstorming/SKILL.md",
                "Brainstorming Ideas Into Designs",
                CipKnowledgeCapabilityPhase.UNSUPPORTED,
                false,
                new CipKnowledgePackVersion("cip_compiler_v2", "cip_compiler_v2"),
                "# Brainstorming Ideas Into Designs\n"));
    when(addonRepository.loadForSkill(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(
            new CompilerSkillAddonContext(
                List.of(),
                new CompilerSkillAddonDocument(
                    "skills/brainstorming.addon.md",
                    "Every READY_FOR_PLAN capture must include explicit `facts`."),
                List.of()));
    when(agent.chat(any(), any(), any())).thenReturn(Multi.createFrom().empty());

    GatherRequirementsPromptBuilder promptBuilder =
        new GatherRequirementsPromptBuilder(skillDocumentService, addonRepository, store);
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(agent, store, promptBuilder);

    StageExecutionContext context =
        new StageExecutionContext(
            "run-wrap",
            "conv-wrap",
            "requirement-discovery",
            "exec-wrap",
            "attempt-wrap",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementFactFixtures.GREETINGS_PROMPT));

    capability.execute(context).subscribe().with(signal -> {});

    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(agent).chat(eq("conv-wrap"), input.capture(), any());
    assertTrue(input.getValue().contains("<compiler-process-skill id=\"brainstorming\""));
    assertTrue(input.getValue().contains("explicit `facts`"));
    assertTrue(input.getValue().contains(RequirementFactFixtures.GREETINGS_PROMPT));
  }

  @Test
  void completeDiscoveryWithoutDraftKeepsLastCaptureRejectionOnNextWrap() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool tool = new RequirementDraftTool(store);
    CompilerSkillDocumentService skillDocumentService = mock(CompilerSkillDocumentService.class);
    CompilerSkillAddonRepository addonRepository = mock(CompilerSkillAddonRepository.class);
    when(skillDocumentService.loadByCapabilityId(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(
            new CompilerSkillDocument(
                "brainstorming",
                "brainstorming",
                "skills/brainstorming/SKILL.md",
                "Brainstorming Ideas Into Designs",
                CipKnowledgeCapabilityPhase.UNSUPPORTED,
                false,
                new CipKnowledgePackVersion("cip_compiler_v2", "cip_compiler_v2"),
                "# Brainstorming Ideas Into Designs\n"));
    when(addonRepository.loadForSkill(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(
            new CompilerSkillAddonContext(
                List.of(),
                new CompilerSkillAddonDocument(
                    "skills/brainstorming.addon.md",
                    "Every READY_FOR_PLAN capture must include explicit `facts`."),
                List.of()));
    GatherRequirementsPromptBuilder promptBuilder =
        new GatherRequirementsPromptBuilder(skillDocumentService, addonRepository, store);

    store.beginTurn("conv-dup");
    try (ToolSession.Handle ignored = ToolSession.open("conv-dup")) {
      tool.captureRequirementDraft(
          new RequirementDraftCapture(
              true,
              "OM onTaskStart then Salesforce createTask",
              DraftDecision.READY_FOR_PLAN,
              List.of(),
              null,
              List.of(
                  new RequirementFact(
                      "om-on-task-start",
                      RequirementFactPolarity.POSITIVE,
                      RequirementFactKind.CAPABILITY,
                      "http-trigger",
                      "Receive OM onTaskStart",
                      "",
                      "",
                      "",
                      "POST",
                      "/tasks/start"),
                  new RequirementFact(
                      "om-on-task-start",
                      RequirementFactPolarity.POSITIVE,
                      RequirementFactKind.BEHAVIOR,
                      "",
                      "commandType is completeTask"))));
    }
    assertTrue(store.get("conv-dup").isEmpty());

    GatherRequirementsAgent agent = mock(GatherRequirementsAgent.class);
    when(agent.chat(any(), any(), any())).thenReturn(Multi.createFrom().empty());
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(agent, store, promptBuilder);

    StageExecutionContext context =
        new StageExecutionContext(
            "run-dup",
            "conv-dup",
            "requirement-discovery",
            "exec-dup",
            "attempt-dup",
            null,
            null,
            List.of(),
            Map.of("userText", RequirementDraftTool.CAPTURE_MISSING_USER_GUIDANCE));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability.execute(context).subscribe().with(signal -> {
      if (signal instanceof CapabilitySignal.Completed c) {
        completed.set(c);
      }
    });

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.get().outcome().outcomeClass());
    assertEquals(org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode.CONTRACT_SHAPE, completed.get().outcome().recoveryCause().causeCode());

    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(agent).chat(eq("conv-dup"), input.capture(), any());
    assertTrue(input.getValue().contains("CAPABILITY sourceFactId=om-on-task-start"), input.getValue());
    assertTrue(input.getValue().contains("Call captureRequirementDraft again"), input.getValue());

    String nextWrap =
        promptBuilder.wrap("conv-dup", RequirementDraftTool.CAPTURE_MISSING_USER_GUIDANCE, "en");
    assertTrue(nextWrap.contains("CAPABILITY sourceFactId=om-on-task-start"), nextWrap);
    assertTrue(nextWrap.contains("<last-capture-rejection"), nextWrap);
  }

  @Test
  void discoverySkipsCatalogBindingHintWithoutOptionalProducesDeclaration() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = petstoreServiceCallDraft();
    RequirementDiscoveryCapability capability = discovery(store, approved);

    // create-chain@1 shape: produces requirement-draft only — no catalog-binding-hint declaration.
    ProductPipelineProfile v1Like =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)), List.of());

    StageExecutionContext context =
        new StageExecutionContext(
            "run-v1-safe",
            "conv-v1-safe",
            "requirement-discovery",
            "exec-v1-safe",
            "attempt-v1-safe",
            v1Like,
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets for pending pets."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(1, candidates.size());
    assertEquals(CompilationArtifacts.Kind.REQUIREMENT_DRAFT, candidates.get(0).kind());
  }

  @Test
  void discoveryEmitsCatalogBindingHintOnlyForExactLocalMatch() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved = petstoreServiceCallDraft();
    RequirementDiscoveryCapability capability = discovery(store, approved);

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-hint",
            "conv-hint",
            "requirement-discovery",
            "exec-hint",
            "attempt-hint",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets for pending pets."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(2, candidates.size());
    assertEquals(CompilationArtifacts.Kind.REQUIREMENT_DRAFT, candidates.get(0).kind());
    assertEquals(CompilationArtifacts.Kind.CATALOG_BINDING_HINT, candidates.get(1).kind());
    CatalogBindingHint hint = (CatalogBindingHint) candidates.get(1).payload();
    assertEquals("sys-1", hint.systemId());
    assertEquals("op-1", hint.integrationOperationId());
  }

  @Test
  void discoveryDoesNotProbeCatalogForUnboundSingleCall() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft draft = readyDraftWithUnboundServiceCall();
    RequirementDiscoveryCapability capability = discovery(store, draft);

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));
    StageExecutionContext context =
        new StageExecutionContext(
            "run-unbound",
            "conv-unbound",
            "requirement-discovery",
            "exec-unbound",
            "attempt-unbound",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets for pending pets."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertTrue(
        candidates.stream()
            .noneMatch(c -> c.kind() == CompilationArtifacts.Kind.CATALOG_BINDING_HINT));
  }

  @Test
  void discoveryCatalogMissDoesNotCallApiHubOrEmitHint() {
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    CatalogMutationGateway gateway = mock(CatalogMutationGateway.class);

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Call UnknownSvc GET /x.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(
                RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.GOAL,
                    "chain",
                    "Create chain"),
                serviceCall("call-unknown", "UnknownSvc", "GET /x")));
    RequirementDiscoveryCapability capability = discovery(store, approved);

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-miss",
            "conv-miss",
            "requirement-discovery",
            "exec-miss",
            "attempt-miss",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Call UnknownSvc GET /x."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.get().outcome().outcomeClass());
    assertTrue(completed.get().outcome().candidates().isEmpty());
    verifyNoInteractions(apiHub, gateway);
  }

  @Test
  void incompleteDraftNeedsInputWithoutReadyForPlanLeak() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft incomplete =
        new RequirementDraft(
            false,
            "Check pending payments somehow",
            DraftDecision.NEEDS_INPUT,
            List.of("Which service or API should check pending payments?"),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of());
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, incomplete);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(incomplete);
              return Multi.createFrom()
                  .item(
                      org.qubership.integration.platform.ai.chat.ChatEvent.token(
                          "To proceed, could you please specify which service or API should be"
                              + " used to check for pending payments?"));
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-needs-input",
            "conv-needs-input",
            "requirement-discovery",
            "exec-needs-input",
            "attempt-needs-input",
            null,
            null,
            List.of(),
            Map.of("userText", "Check pending payments"));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.get().outcome().outcomeClass());
    assertEquals(PipelineGates.STAGE_CLARIFICATION,
        PipelineGates.gateOf(completed.get().outcome().message()).orElseThrow());
    assertFalse(completed.get().outcome().message().contains("READY_FOR_PLAN"));
  }

  @Test
  void approvedUploadedSpecsHandoffAdvancesNeedsInputDraftWithFacts() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft incomplete =
        new RequirementDraft(
            false,
            "Call OM onTaskStart then Salesforce WFM createTask.",
            DraftDecision.NEEDS_INPUT,
            List.of("Which catalog operation should Salesforce WFM createTask use?"),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(
                RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.GOAL,
                    "chain",
                    "Create OM to Salesforce WFM"),
                serviceCall("call-wfm-create-task", "Salesforce WFM", "createTask")),
            false,
            List.of(
                new RequirementServiceCall(
                    "call-wfm-create-task",
                    "call-wfm-create-task",
                    "Salesforce WFM",
                    "createTask",
                    null)));
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "conv-uploaded-spec", List.of("sessions/conv/salesforce-wfm.json"));
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            null,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, incomplete);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(incomplete);
              return Multi.createFrom().empty();
            },
            conversations);

    capability.uploadedSpecs = mock(org.qubership.integration.platform.ai.plan.UploadedSpecOperations.class);
    when(capability.uploadedSpecs.pendingInteractionIds(anyString(), any())).thenReturn(java.util.Set.of("call-wfm-create-task"));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-uploaded-spec",
            "conv-uploaded-spec",
            "requirement-discovery",
            "exec-uploaded-spec",
            "attempt-uploaded-spec",
            discoveryProfile(
                List.of(new ArtifactTypeRef("requirement-draft", 2)), List.of()),
            null,
            List.of(),
            Map.of("userText", "Create OM to Salesforce WFM with the attached spec"));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    assertEquals(1, completed.get().outcome().candidates().size());
    assertEquals(
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
        completed.get().outcome().candidates().get(0).kind());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = RequirementDiscoveryDirective.class, names = {"STAY", "CONTINUE"})
  void nativeUploadedDraftHandsOffOnlyOnContinue(RequirementDiscoveryDirective directive) {
    var store = new RequirementDraftStore();
    var conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys("uploaded-native", List.of("uploads/alpha.json", "uploads/beta.json"));
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /start", null, null, null),
        new InteractionInput("alpha", Direction.OUTBOUND, "Alpha API", "getProbe", null, null, null),
        new InteractionInput("beta", Direction.OUTBOUND, "Beta API", "getProbe", null, null, null)),
        List.of(new TransitionInput("entry", "alpha"), new TransitionInput("alpha", "beta"))),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Read both probes.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/start", null, null, null)),
        List.of(), new DraftSettings(false, null));
    var draft = org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash");
    var capability = new RequirementDiscoveryCapability(null, store, null, (chat, text) -> {
      store.put(chat, draft);
      store.markCaptured(chat);
      store.finishTurn(chat, directive);
      return Multi.createFrom().item(ChatEvent.token("The saved requirements are waiting for the uploaded specifications."));
    }, conversations);
    capability.uploadedSpecs = mock(org.qubership.integration.platform.ai.plan.UploadedSpecOperations.class);
    when(capability.uploadedSpecs.pendingInteractionIds(anyString(), any())).thenReturn(java.util.Set.of("alpha", "beta"));
    var signals = capability.execute(new StageExecutionContext("uploaded-native-run", "uploaded-native",
        "requirement-discovery", "execution", "attempt", null, null, List.of(), Map.of("userText", "Create both calls.")))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(directive == RequirementDiscoveryDirective.CONTINUE ? StageOutcomeClass.CANDIDATE : StageOutcomeClass.NEEDS_INPUT,
        outcome.outcomeClass(), outcome.toString());
    assertEquals(draft.authoredDraft(), store.get("uploaded-native").orElseThrow().authoredDraft());
    assertTrue(store.get("uploaded-native").orElseThrow().catalogBindings().isEmpty());
    if (directive == RequirementDiscoveryDirective.STAY) {
      assertTrue(signals.stream().anyMatch(CapabilitySignal.Message.class::isInstance)
          || !PipelineGates.strip(outcome.message()).isBlank(), "A discussion must retain its explanation or question");
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = RequirementDiscoveryDirective.class, names = {"STAY", "NONE", "CONTINUE"})
  void pendingUploadDoesNotHideUnfinishedCatalogWork(RequirementDiscoveryDirective directive) {
    var store = new RequirementDraftStore();
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /start", null, null, null),
        new InteractionInput("uploaded", Direction.OUTBOUND, "Uploaded API", "createTask", null, null, null),
        new InteractionInput("result", Direction.OUTBOUND, "Order Manager", "onTaskResult", null, null, null)),
        List.of(new TransitionInput("entry", "uploaded"), new TransitionInput("uploaded", "result"))),
        List.of(new FactInput("goal", List.of(), FactKind.GOAL, Polarity.POSITIVE, "Create a task and deliver its result.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/start", null, null, null)),
        List.of(), new DraftSettings(false, null));
    var draft = org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash");
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      store.put(chat, draft);
      store.markCaptured(chat);
      store.finishTurn(chat, directive);
      return Multi.createFrom().item(ChatEvent.token("The draft is partial. Import the API and resolve onTaskResult."));
    });
    capability.uploadedSpecs = mock(org.qubership.integration.platform.ai.plan.UploadedSpecOperations.class);
    when(capability.uploadedSpecs.pendingInteractionIds(anyString(), any())).thenReturn(java.util.Set.of("uploaded"));
    var signals = capability.execute(new StageExecutionContext("mixed-upload-run", "mixed-upload-chat",
        "requirement-discovery", "execution", "attempt", null, null, List.of(), Map.of("userText", "Import the API.")))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.toString());
    assertEquals(java.util.Set.of("UPLOADED_SPEC_IMPORT_REQUIRED", "OPERATION_LOOKUP_REQUIRED"),
        outcome.recoveryCause().findings().stream().map(finding -> finding.code()).collect(java.util.stream.Collectors.toSet()));
    assertEquals(draft, store.get("mixed-upload-chat").orElseThrow());
  }

  @Test
  void approvedUploadedSpecsHandoffWhenOutboundFlowIsUnboundWithoutServiceCallFact() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft incomplete =
        new RequirementDraft(
                false,
                "Call OM onTaskStart then Salesforce WFM createTask.",
                DraftDecision.NEEDS_INPUT,
                List.of("Which catalog createTask operation should be used?"),
                "brainstorming",
                "1",
                null,
                null,
                false,
                List.of(
                    RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.GOAL,
                        "chain",
                        "Create OM to Salesforce WFM"),
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "salesforce-create-task",
                        "Call Salesforce WFM createTask"),
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CONSTRAINT,
                        "salesforce-create-task",
                        "Reuse the existing Salesforce WFM specification")),
                false)
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction("on-task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
                        new Interaction(
                            "salesforce-create-task",
                            Direction.OUTBOUND,
                            "Salesforce WFM",
                            "createTask",
                            "")),
                    List.of(new Transition("on-task-start", "salesforce-create-task"))));
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "conv-uploaded-spec-unbound-flow", List.of("sessions/conv/salesforce-wfm.json"));
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            null,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, incomplete);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(incomplete);
              return Multi.createFrom().empty();
            },
            conversations);

    capability.uploadedSpecs = mock(org.qubership.integration.platform.ai.plan.UploadedSpecOperations.class);
    when(capability.uploadedSpecs.pendingInteractionIds(anyString(), any())).thenReturn(java.util.Set.of("salesforce-create-task"));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-uploaded-spec-unbound-flow",
            "conv-uploaded-spec-unbound-flow",
            "requirement-discovery",
            "exec-uploaded-spec-unbound-flow",
            "attempt-uploaded-spec-unbound-flow",
            discoveryProfile(
                List.of(new ArtifactTypeRef("requirement-draft", 2)), List.of()),
            null,
            List.of(),
            Map.of("userText", "Create OM to Salesforce WFM with the attached spec"));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    assertEquals(1, completed.get().outcome().candidates().size());
    assertEquals(
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
        completed.get().outcome().candidates().get(0).kind());
  }

  @Test
  void uploadedSpecsDoNotAuthorizeHandoffWithoutAMatchingOperation() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft incomplete =
        new RequirementDraft(
            false,
            "Create OM to Salesforce WFM with the attached spec.",
            DraftDecision.NEEDS_INPUT,
            List.of("Import the uploaded specification?"),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(
                RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.GOAL,
                    "chain",
                    "Create OM to Salesforce WFM")),
            false);
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "conv-uploaded-spec-goal", List.of("sessions/conv/salesforce-wfm.json"));
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            null,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, incomplete);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(incomplete);
              return Multi.createFrom().empty();
            },
            conversations);

    StageExecutionContext context =
        new StageExecutionContext(
            "run-uploaded-spec-goal",
            "conv-uploaded-spec-goal",
            "requirement-discovery",
            "exec-uploaded-spec-goal",
            "attempt-uploaded-spec-goal",
            discoveryProfile(
                List.of(new ArtifactTypeRef("requirement-draft", 2)), List.of()),
            null,
            List.of(),
            Map.of("userText", "Create OM to Salesforce WFM with the attached spec"));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.get().outcome().outcomeClass());
    assertTrue(completed.get().outcome().candidates().isEmpty());
  }

  @Test
  void discoveryBindsToolSessionBeforeGatherWithoutChatExecutionService() {
    RequirementDraftStore store = new RequirementDraftStore();
    AtomicReference<String> observedConversationId = new AtomicReference<>();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            (conversationId, userText) -> {
              observedConversationId.set(ToolSession.resolveConversationId());
              return Multi.createFrom().item(ChatEvent.token("gathering"));
            });

    StageExecutionContext context =
        new StageExecutionContext(
            "run-tool-session",
            "conv-tool-session",
            "requirement-discovery",
            "exec-tool-session",
            "attempt-tool-session",
            null,
            null,
            List.of(),
            Map.of("userText", "HTTP GET /hello returns Good day"));

    capability.execute(context).collect().asList().await().indefinitely();

    assertEquals("conv-tool-session", observedConversationId.get());
    assertNull(ToolSession.resolveConversationId());
  }

  /**
   * The approved binding reaches the hint even when no rule can read the fact that carries it.
   *
   * <p>Fact text and ids are the ones from the run that sent {@code GET /store/inventory.} — the
   * trailing period included — to APIHub and got a gateway timeout back.
   */
  @Test
  void discoveryEmitsApprovedDraftBindingWithoutReadingTheCatalog() {
    CatalogSystemReadTool catalogReadTool = mock(CatalogSystemReadTool.class);

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementFact serviceCall =
        serviceCall(
            "call-inventory",
            "Petstore Ext",
            "Call Petstore Ext catalog operation getInventory: GET /store/inventory.");
    RequirementDraft approved =
        new RequirementDraft(
                true,
                "Call the Petstore Ext getInventory operation.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                "brainstorming",
                "1",
                null,
                null,
                false,
                List.of(
                    RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.GOAL,
                        "chain",
                        "Create a chain named Pet Inventory Check"),
                    serviceCall))
            .withFlow(outboundFlow(serviceCall))
            .withBoundServiceCall(
                serviceCall.serviceCallId(),
                new CatalogBindingHint(
                    "3",
                    serviceCall.serviceCallId(),
                    serviceCall.sourceFactId(),
                    "getInventory",
                    "bbf14771-de8d-48e8-a2ed-2e691f7f6eff",
                    "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-sg",
                    "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger-1.0.7",
                    "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger-1.0.7-getInventory",
                    "http",
                    "GET",
                    "/store/inventory",
                    "catalog",
                    Instant.EPOCH,
                    "test"));
    RequirementDiscoveryCapability capability = discovery(store, approved);

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-approved-binding",
            "conv-approved-binding",
            "requirement-discovery",
            "exec-approved-binding",
            "attempt-approved-binding",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Call the Petstore Ext getInventory operation."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(2, candidates.size());
    assertEquals(CompilationArtifacts.Kind.CATALOG_BINDING_HINT, candidates.get(1).kind());
    CatalogBindingHint hint = (CatalogBindingHint) candidates.get(1).payload();
    assertEquals("bbf14771-de8d-48e8-a2ed-2e691f7f6eff", hint.systemId());
    assertEquals(
        "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger-1.0.7-getInventory",
        hint.integrationOperationId());
    // The step the hint binds to is found by fact id, so the wording of the fact never decides.
    assertEquals(serviceCall.serviceCallId(), hint.interactionId());
    verifyNoInteractions(catalogReadTool);
  }

  /**
   * Each outbound call keeps the binding stored on the draft, with no catalog read and no
   * conversation-memory lookup.
   */
  @Test
  void discoveryPairsEveryServiceCallWithItsOwnResolvedBinding() {
    CatalogSystemReadTool catalogReadTool = mock(CatalogSystemReadTool.class);
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");

    RequirementFact inventoryCall =
        serviceCall(
            "call-inventory",
            "Petstore Ext",
            "Call Petstore Ext catalog operation getInventory: GET /store/inventory.");
    RequirementFact invoiceCall =
        serviceCall(
            "call-invoice",
            "Billing",
            "Then post the invoice through Billing using POST /invoices.");
    CatalogBindingHint inventoryHint =
        new CatalogBindingHint(
            "3",
            inventoryCall.serviceCallId(),
            inventoryCall.sourceFactId(),
            "GET /store/inventory",
            "sys-pet",
            "sg-pet",
            "spec-pet",
            "op-inventory",
            "http",
            "GET",
            "/store/inventory",
            "2024.4",
            observedAt,
            "catalog-read:pet");
    CatalogBindingHint invoiceHint =
        new CatalogBindingHint(
            "3",
            invoiceCall.serviceCallId(),
            invoiceCall.sourceFactId(),
            "POST /invoices",
            "sys-bill",
            "sg-bill",
            "spec-bill",
            "op-invoice",
            "http",
            "POST",
            "/invoices",
            "2024.4",
            observedAt,
            "catalog-read:bill");

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Check inventory, then raise an invoice.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(
                RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.GOAL,
                    "chain",
                    "Create an inventory-to-invoice chain"),
                inventoryCall,
                invoiceCall),
            false,
            List.of(
                new RequirementServiceCall(
                    inventoryCall.serviceCallId(),
                    inventoryCall.sourceFactId(),
                    "Petstore Ext",
                    "getInventory",
                    inventoryHint),
                new RequirementServiceCall(
                    invoiceCall.serviceCallId(),
                    invoiceCall.sourceFactId(),
                    "Billing",
                    "createInvoice",
                    invoiceHint)))
            .withFlow(outboundFlow(inventoryCall, invoiceCall));

    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            null,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            });

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-two-services",
            "conv-two-services",
            "requirement-discovery",
            "exec-two-services",
            "attempt-two-services",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Check inventory, then raise an invoice."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(3, candidates.size());
    CatalogBindingHint first = (CatalogBindingHint) candidates.get(1).payload();
    CatalogBindingHint second = (CatalogBindingHint) candidates.get(2).payload();
    assertEquals(inventoryCall.serviceCallId(), first.interactionId());
    assertEquals("op-inventory", first.integrationOperationId());
    assertEquals("sys-pet", first.systemId());
    assertEquals(invoiceCall.serviceCallId(), second.interactionId());
    assertEquals("op-invoice", second.integrationOperationId());
    assertEquals("sys-bill", second.systemId());
    verifyNoInteractions(catalogReadTool);
  }

  @Test
  void discoveryCopiesStoredBindingsAfterResolutionCacheIsEmpty() {
    CatalogSystemReadTool catalogReadTool = mock(CatalogSystemReadTool.class);
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    RequirementFact omCall =
        new RequirementFact(
            "fact-om",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "",
            "Call Order Management onTaskResult",
            "Order Management",
            "onTaskResult",
            "",
            "",
            "",
            "call-om-result");
    RequirementFact wfmCall =
        new RequirementFact(
            "fact-wfm",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "",
            "Call Salesforce WFM createTask",
            "Salesforce WFM",
            "createTask",
            "",
            "",
            "",
            "call-wfm-create-task");
    CatalogBindingHint omHint =
        new CatalogBindingHint(
            "3",
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
            "3",
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
    RequirementDraft approved =
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
                RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.GOAL,
                    "chain",
                    "Create an OM to Salesforce WFM chain"),
                omCall,
                wfmCall),
            false,
            List.of(
                new RequirementServiceCall(
                    "call-om-result", "fact-om", "Order Management", "onTaskResult", omHint),
                new RequirementServiceCall(
                    "call-wfm-create-task",
                    "fact-wfm",
                    "Salesforce WFM",
                    "createTask",
                    wfmHint)))
            .withFlow(outboundFlow(omCall, wfmCall));

    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            null,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, approved);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(approved);
              return Multi.createFrom().empty();
            });

    ProductPipelineProfile withHint =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("catalog-binding-hint", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-frozen-after-restart",
            "conv-frozen-after-restart",
            "requirement-discovery",
            "exec-frozen-after-restart",
            "attempt-frozen-after-restart",
            withHint,
            null,
            List.of(),
            Map.of("userText", "Call OM then Salesforce WFM"));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertEquals(3, candidates.size());
    assertEquals(CompilationArtifacts.Kind.REQUIREMENT_DRAFT, candidates.get(0).kind());
    CatalogBindingHint first = (CatalogBindingHint) candidates.get(1).payload();
    CatalogBindingHint second = (CatalogBindingHint) candidates.get(2).payload();
    assertEquals("3", first.schemaVersion());
    assertEquals("3", second.schemaVersion());
    assertEquals("call-om-result", first.interactionId());
    assertEquals("call-wfm-create-task", second.interactionId());
    assertEquals("op-shared", first.integrationOperationId());
    assertEquals("op-shared", second.integrationOperationId());
    assertEquals(omHint, first);
    assertEquals(wfmHint, second);
    verifyNoInteractions(catalogReadTool);
  }

  @Test
  void discoveryEmitsIdsBypassWhenTheAuthorDeclinedTheSpecification() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft declined = petstoreServiceCallDraft().withIdsRequested(false);
    RequirementDiscoveryCapability capability = discovery(store, declined);

    ProductPipelineProfile withBypass =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("ids-bypass", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-ids-declined",
            "conv-ids-declined",
            "requirement-discovery",
            "exec-ids-declined",
            "attempt-ids-declined",
            withBypass,
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets. No IDS please."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    List<ArtifactCandidate> candidates = completed.get().outcome().candidates();
    assertTrue(
        candidates.stream()
            .anyMatch(candidate -> candidate.kind() == CompilationArtifacts.Kind.IDS_BYPASS),
        () -> "candidates=" + candidates);
  }

  @Test
  void discoveryWaitsAtIdsChoiceWhileTheAuthorHasNotSaid() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft ready = petstoreServiceCallDraft();
    RequirementDraft undecided =
        new RequirementDraft(
            false,
            ready.assembledText(),
            DraftDecision.NEEDS_INPUT,
            List.of(),
            ready.sourceSkillId(),
            ready.sourceSkillVersion(),
            ready.sourceSkillHash(),
            ready.apiHubCandidate(),
            ready.awaitingPlanContinuation(),
            ready.facts(),
            ready.importIntent(),
            ready.apiHubCandidateInteractionId(),
            null,
            ready.flow(),
            ready.catalogBindings(),
            ready.preferredSystemType());
    RequirementDiscoveryCapability capability = discovery(store, undecided);

    ProductPipelineProfile withBypass =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("ids-bypass", 1)));

    StageExecutionContext context =
        new StageExecutionContext(
            "run-ids-unset",
            "conv-ids-unset",
            "requirement-discovery",
            "exec-ids-unset",
            "attempt-ids-unset",
            withBypass,
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets for pending pets."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.get().outcome().outcomeClass());
    assertEquals(
        PipelineGates.IDS_PATH_CHOICE,
        PipelineGates.gateOf(completed.get().outcome().message()).orElseThrow());
    assertTrue(
        completed.get().outcome().candidates().stream()
            .noneMatch(candidate -> candidate.kind() == CompilationArtifacts.Kind.IDS_BYPASS));
  }

  @ParameterizedTest
  @ValueSource(strings = {"yes", "no"})
  void idsPathChoiceIsStoredWithoutAnotherDiscoveryTurn(String choice) {
    RequirementDraftStore store = new RequirementDraftStore();
    AtomicInteger discoveryTurns = new AtomicInteger();
    RequirementDraft undecided = petstoreServiceCallDraft();
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            null,
            (conversationId, userText) -> {
              discoveryTurns.incrementAndGet();
              store.beginTurn(conversationId);
              store.put(conversationId, undecided);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(undecided);
              return Multi.createFrom().empty();
            });
    ProductPipelineProfile withBypass =
        discoveryProfile(
            List.of(new ArtifactTypeRef("requirement-draft", 2)),
            List.of(new ArtifactTypeRef("ids-bypass", 1)));
    StageExecutionContext initial =
        new StageExecutionContext(
            "run-ids-choice",
            "conv-ids-choice",
            "requirement-discovery",
            "exec-ids-choice-1",
            "attempt-ids-choice-1",
            withBypass,
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets for pending pets."));
    capability.execute(initial).collect().asList().await().indefinitely();

    StageExecutionContext answer =
        new StageExecutionContext(
            "run-ids-choice",
            "conv-ids-choice",
            "requirement-discovery",
            "exec-ids-choice-2",
            "attempt-ids-choice-2",
            withBypass,
            null,
            List.of(),
            Map.of("userText", choice, "admittedInputGate", PipelineGates.IDS_PATH_CHOICE,
                "admittedInputRevision", 2L));
    List<CapabilitySignal> signals =
        capability.execute(answer).collect().asList().await().indefinitely();
    StageOutcome outcome =
        signals.stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow()
            .outcome();

    boolean requested = "yes".equals(choice);
    assertEquals(1, discoveryTurns.get());
    assertEquals(requested, store.get("conv-ids-choice").orElseThrow().idsRequested());
    assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass());
    assertEquals(
        !requested,
        outcome.candidates().stream()
            .anyMatch(candidate -> candidate.kind() == CompilationArtifacts.Kind.IDS_BYPASS));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"yes,partial", "no,partial", "yes,repair", "no,repair"})
  void admittedIdsChoiceIsSavedBeforeOtherRequirementsAreResolved(String answer, String scenario) {
    var store = new RequirementDraftStore();
    var initial = new RequirementDraft(false, "Known requirements", DraftDecision.NEEDS_INPUT,
        List.of("Which endpoint should receive the request?"), "gather", "1", "hash", null, false,
        petstoreServiceCallDraft().facts(), false, null, null, petstoreServiceCallDraft().flow(), List.of(), null);
    store.put("ids-partial-chat", initial);
    var calls = new AtomicInteger();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      calls.incrementAndGet();
      assertEquals(answer.equals("yes"), store.get(chat).orElseThrow().idsRequested());
      return Multi.createFrom().empty();
    });
    var attrs = new java.util.HashMap<String, Object>();
    attrs.put("userText", answer);
    attrs.put("admittedInputGate", PipelineGates.IDS_PATH_CHOICE);
    attrs.put("admittedInputRevision", 7L);
    if ("repair".equals(scenario)) {
      attrs.put(org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
          "Missing endpoint");
      attrs.put(org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR,
          "MISSING_BRIEF_FACTS");
    }
    var context = new StageExecutionContext("ids-partial-run", "ids-partial-chat", "requirement-discovery",
        "exec", "attempt", discoveryProfile(List.of(new ArtifactTypeRef("requirement-draft", 2)),
        List.of(new ArtifactTypeRef("ids-bypass", 1))), null, List.of(), attrs);
    var signals = capability.execute(context).collect().asList().await().indefinitely();
    var stored = store.get("ids-partial-chat").orElseThrow();
    assertEquals(answer.equals("yes"), stored.idsRequested());
    assertEquals(initial.facts(), stored.facts());
    assertEquals(initial.openQuestions(), stored.openQuestions());
    assertFalse(stored.readyForPlan());
    assertEquals("repair".equals(scenario) ? 1 : 0, calls.get());
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass());
  }

  @Test
  void stayOnAReadyDraftOffersIdsBeforePlanningContinuation() {
    var store = new RequirementDraftStore();
    var capability = new RequirementDiscoveryCapability(null, store, (chat, text) -> {
      store.put(chat, petstoreServiceCallDraft());
      store.markCaptured(chat);
      store.finishTurn(chat, RequirementDiscoveryDirective.STAY);
      return Multi.createFrom().empty();
    });
    var signals = capability.execute(new StageExecutionContext("ids-stay-run", "ids-stay-chat",
        "requirement-discovery", "exec", "attempt", discoveryProfile(
        List.of(new ArtifactTypeRef("requirement-draft", 2)), List.of(new ArtifactTypeRef("ids-bypass", 1))),
        null, List.of(), Map.of("userText", "Create the integration.")))
        .collect().asList().await().indefinitely();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(PipelineGates.IDS_PATH_CHOICE, PipelineGates.gateOf(outcome.message()).orElseThrow());
  }

  @Test
  void discoveryWaitsAtIdsChoiceAfterUploadedSpecsAreAlreadyBound() {
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraft ready = petstoreServiceCallDraft();
    RequirementDraft undecided =
        new RequirementDraft(
            false,
            ready.assembledText(),
            DraftDecision.NEEDS_INPUT,
            List.of(),
            ready.sourceSkillId(),
            ready.sourceSkillVersion(),
            ready.sourceSkillHash(),
            ready.apiHubCandidate(),
            ready.awaitingPlanContinuation(),
            ready.facts(),
            ready.importIntent(),
            ready.apiHubCandidateInteractionId(),
            null,
            ready.flow(),
            ready.catalogBindings(),
            ready.preferredSystemType());
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "conv-ids-uploaded", List.of("sessions/conv/petstore.json"));
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null,
            store,
            null,
            (conversationId, userText) -> {
              store.beginTurn(conversationId);
              store.put(conversationId, undecided);
              store.markCaptured(conversationId);
              ProductCapabilityCaptureContext.offerDraft(undecided);
              return Multi.createFrom().empty();
            },
            conversations);

    StageExecutionContext context =
        new StageExecutionContext(
            "run-ids-uploaded",
            "conv-ids-uploaded",
            "requirement-discovery",
            "exec-ids-uploaded",
            "attempt-ids-uploaded",
            discoveryProfile(
                List.of(new ArtifactTypeRef("requirement-draft", 2)),
                List.of(new ArtifactTypeRef("ids-bypass", 1))),
            null,
            List.of(),
            Map.of("userText", "Call Petstore Ext GET /pets using the attached specification."));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.NEEDS_INPUT, completed.get().outcome().outcomeClass());
    assertEquals(
        PipelineGates.IDS_PATH_CHOICE,
        PipelineGates.gateOf(completed.get().outcome().message()).orElseThrow());
  }

  private static RequirementDraft petstoreServiceCallDraft() {
    RequirementFact call =
        serviceCall("call-pets", "Petstore Ext", "GET /pets");
    CatalogBindingHint hint =
        new CatalogBindingHint(
            "3",
            call.serviceCallId(),
            call.sourceFactId(),
            "GET /pets",
            "sys-1",
            "sg-1",
            "spec-1",
            "op-1",
            "http",
            "GET",
            "/pets",
            "2024.4",
            Instant.parse("2026-08-27T12:00:00Z"),
            "catalog-read:pet");
    return new RequirementDraft(
        true,
        "Call Petstore Ext GET /pets for pending pets.",
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        false,
        List.of(
            RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.GOAL,
                "chain",
                "Create pending pets chain"),
            call),
        false,
        List.of(
            new RequirementServiceCall(
                call.serviceCallId(), call.sourceFactId(), "Petstore Ext", "GET /pets", hint)))
        .withFlow(outboundFlow(call));
  }

  private static RequirementDraft readyDraftWithUnboundServiceCall() {
    RequirementFact call =
        serviceCall("call-pets", "Petstore Ext", "GET /pets");
    return new RequirementDraft(
        true,
        "Call Petstore Ext GET /pets for pending pets.",
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        false,
        List.of(
            RequirementFactFixtures.httpTriggerFact("start", "POST", "/start"),
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.GOAL,
                "chain",
                "Create pending pets chain"),
            call),
        false,
        List.of(
            new RequirementServiceCall(
                call.serviceCallId(), call.sourceFactId(), "Petstore Ext", "GET /pets", null)));
  }

  private static RequirementFlow outboundFlow(RequirementFact... calls) {
    List<Interaction> interactions = new java.util.ArrayList<>();
    List<Transition> transitions = new java.util.ArrayList<>();
    interactions.add(new Interaction("start", Direction.INBOUND, "Caller", "start", ""));
    String previous = "start";
    for (RequirementFact call : calls) {
      interactions.add(
          new Interaction(
              call.serviceCallId(),
              Direction.OUTBOUND,
              call.participant(),
              call.operation(),
              ""));
      transitions.add(new Transition(previous, call.serviceCallId()));
      previous = call.serviceCallId();
    }
    return new RequirementFlow(interactions, transitions);
  }

  private static RequirementFact serviceCall(
      String serviceCallId, String participant, String operation) {
    return new RequirementFact(
        serviceCallId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "",
        operation,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
  }

  private static RequirementDiscoveryCapability discovery(
      RequirementDraftStore store, RequirementDraft approved) {
    return new RequirementDiscoveryCapability(
        null,
        store,
        null,
        (conversationId, userText) -> {
          store.beginTurn(conversationId);
          store.put(conversationId, approved);
          store.markCaptured(conversationId);
          ProductCapabilityCaptureContext.offerDraft(approved);
          return Multi.createFrom().empty();
        });
  }

  private static ProductPipelineProfile discoveryProfile(
      List<ArtifactTypeRef> produces, List<ArtifactTypeRef> optionalProduces) {
    return new ProductPipelineProfile(
        1,
        "fixture-discovery",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-discovery",
                "requirement-discovery",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(),
                produces,
                optionalProduces,
                new ApprovalPolicy(new ArtifactTypeRef("requirement-draft", 2)),
                null,
                new RetryPolicy(0, 1000L),
                null)),
        new TerminalPolicy("requirement-discovery", "PLAN_APPROVED"),
        List.of("requirement-discovery"));
  }

  @Test
  void bindMissingInteractionPersistsAnExactCatalogHit() {
    CatalogSystemReadTool catalog = mock(CatalogSystemReadTool.class);
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto("sys-wfms", "WFMS Create Work Order", "EXTERNAL", "http");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec-wfms", "WFMS v1", "sg-wfms", "sys-wfms");
    CatalogRestClient.OperationDto operation =
        new CatalogRestClient.OperationDto(
            "op-create-wo", "Create Work Order", "POST", "/work-orders", "spec-wfms");
    when(catalog.searchCatalogSystems("WFMS Create Work Order")).thenReturn(List.of(system));
    when(catalog.getApiSpecifications("sys-wfms")).thenReturn(List.of(spec));
    when(catalog.listCatalogOperations("conv-bind", "spec-wfms", "sys-wfms", null))
        .thenReturn(List.of(operation));
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null, new RequirementDraftStore(), null, new CatalogBindingMatcher(catalog));

    Optional<CatalogBindingHint> hint =
        capability.bindMissingInteraction(
            "conv-bind", "wfms-create-work-order", "WFMS Create Work Order");

    assertTrue(hint.isPresent());
    assertEquals("wfms-create-work-order", hint.get().interactionId());
    assertEquals("sys-wfms", hint.get().systemId());
    assertEquals("op-create-wo", hint.get().integrationOperationId());
  }

  @Test
  void bindMissingInteractionStaysEmptyWhenSeveralOperationsMatch() {
    CatalogSystemReadTool catalog = mock(CatalogSystemReadTool.class);
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto("sys-wfms", "WFMS Create Work Order", "EXTERNAL", "http");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec-wfms", "WFMS v1", "sg-wfms", "sys-wfms");
    CatalogRestClient.OperationDto create =
        new CatalogRestClient.OperationDto(
            "op-create-wo", "WFMS Create Work Order", "POST", "/work-orders", "spec-wfms");
    CatalogRestClient.OperationDto draft =
        new CatalogRestClient.OperationDto(
            "op-create-wo-draft",
            "WFMS Create Work Order Draft",
            "POST",
            "/work-orders/draft",
            "spec-wfms");
    when(catalog.searchCatalogSystems("WFMS Create Work Order")).thenReturn(List.of(system));
    when(catalog.getApiSpecifications("sys-wfms")).thenReturn(List.of(spec));
    when(catalog.listCatalogOperations("conv-bind", "spec-wfms", "sys-wfms", null))
        .thenReturn(List.of(create, draft));
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null, new RequirementDraftStore(), null, new CatalogBindingMatcher(catalog));

    CatalogBindingMatcher.MatchResult result =
        capability.matchMissingInteraction("conv-bind", "WFMS Create Work Order");
    Optional<CatalogBindingHint> hint =
        capability.bindMissingInteraction(
            "conv-bind", "wfms-create-work-order", "WFMS Create Work Order");

    assertInstanceOf(CatalogBindingMatcher.MatchResult.Ambiguous.class, result);
    assertTrue(hint.isEmpty());
  }

  @Test
  void matchMissingInteractionReturnsNoneForABlankAnswer() {
    CatalogSystemReadTool catalog = mock(CatalogSystemReadTool.class);
    RequirementDiscoveryCapability capability =
        new RequirementDiscoveryCapability(
            null, new RequirementDraftStore(), null, new CatalogBindingMatcher(catalog));

    CatalogBindingMatcher.MatchResult result =
        capability.matchMissingInteraction("conv-bind", "  ");

    assertInstanceOf(CatalogBindingMatcher.MatchResult.None.class, result);
    assertTrue(
        capability.bindMissingInteraction("conv-bind", "wfms-create-work-order", "  ").isEmpty());
  }
}
