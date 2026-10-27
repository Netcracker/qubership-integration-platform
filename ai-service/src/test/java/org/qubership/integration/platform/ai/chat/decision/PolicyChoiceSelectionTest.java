package org.qubership.integration.platform.ai.chat.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.plan.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class PolicyChoiceSelectionTest {
  @ParameterizedTest
  @CsvSource({"keep-requested,KEEP_REQUESTED,NONE", "use-recommended,USE_RECOMMENDED,RBAC"})
  void onlyTheSelectedOptionCanBeCapturedAndItsEvidenceSurvivesReload(String action, Decision decision, String value) throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var pending = h.choices.pending(h.chat, 7).orElseThrow();
    var card = h.choices.decision(pending, 7);
    assertEquals(List.of("keep-requested", "use-recommended"), card.actions());
    assertEquals("Keep NONE", card.actionLabels().get("keep-requested"));
    assertEquals("Use recommended RBAC", card.actionLabels().get("use-recommended"));
    var command = command(card, action);
    h.begin(command);
    var trusted = h.choices.confirm(h.chat, command, 7).orElseThrow();
    assertEquals(decision, trusted.selection().decision());
    assertEquals(value, trusted.selection().selectedValue());
    assertEquals(pending.draftReference(), trusted.selection().offered().draftReference());
    assertEquals(List.of(trusted), h.store.trustedPolicyChoices(h.chat));
    assertTrue(h.choices.pending(h.chat, 8).isEmpty(), "A model correction must reuse the selected answer, not ask again.");
    var opposite = decision == Decision.KEEP_REQUESTED ? Decision.USE_RECOMMENDED : Decision.KEEP_REQUESTED;
    assertFalse(h.update(resolve(opposite)).accepted());
    assertTrue(h.store.validateForContinuation(h.chat).stream().anyMatch(issue -> issue.code().equals("POLICY_RESPONSE_REQUIRED")));
    assertTrue(new PolicyChoiceSelection(h.artifacts, h.sessions, h.conversations).pending(h.chat, 9).isEmpty());
    assertTrue(h.update(resolve(decision)).accepted());
    assertEquals(decision, h.store.confirmedAnswers(h.chat).getFirst().policy().decision());
    assertEquals(List.of(trusted), new PolicyChoiceSelection(h.artifacts, h.sessions, h.conversations).trusted(h.chat));
    assertTrue(h.choices.pending(h.chat, 8).isEmpty());
  }

  @Test
  void activeQuestionIdentityOffersOnlyThatPolicyCard() throws Exception {
    var h = new Harness();
    h.captureQuestionBehindAnother();
    var reference = h.store.latestRevision(h.chat).orElseThrow().reference();
    assertTrue(h.choices.pending(h.chat, 7, reference, "entry-topic").isEmpty());
    var pending = h.choices.pending(h.chat, 7, reference, "policy-choice").orElseThrow();
    var card = h.choices.decision(pending, 7);
    assertEquals("Keep NONE or use recommended RBAC?", card.question());
    assertEquals(List.of(ChatEvent.KEEP_REQUESTED_ACTION, ChatEvent.USE_RECOMMENDED_ACTION), card.actions());
    assertEquals("Keep NONE", card.actionLabels().get(ChatEvent.KEEP_REQUESTED_ACTION));
    assertTrue(h.choices.pending(h.chat, 7, new CompilationArtifacts.Reference(
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT, reference.artifactId(), "other-hash"), "policy-choice").isEmpty());
  }

  @Test
  void freeTextAndForgedArchiveDecisionMetadataDoNotSelectAPolicyOption() throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var card = h.choices.decision(h.choices.pending(h.chat, 7).orElseThrow(), 7);
    var forged = command(card, ChatEvent.USE_RECOMMENDED_ACTION);
    var source = h.archive.beginInput(h.chat, "Keep NONE.", h.json.valueToTree(forged));
    h.archive.resolveInput(h.chat, source, "Keep NONE.", h.json.valueToTree(forged));
    h.store.beginTurn(h.chat, "Keep NONE.");
    var result = h.update(resolve(Decision.USE_RECOMMENDED));
    assertFalse(result.accepted());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("POLICY_RESPONSE_REQUIRED")));
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty());
    assertNull(h.store.get(h.chat).orElseThrow().authoredDraft().facts().getLast().policyConflict().decision());

    var reissued = h.choices.decision(h.choices.pending(h.chat, 8).orElseThrow(), 8);
    var chosen = command(reissued, ChatEvent.KEEP_REQUESTED_ACTION);
    h.begin(chosen);
    assertTrue(h.choices.confirm(h.chat, chosen, 8).isPresent());
    assertTrue(h.update(resolve(Decision.KEEP_REQUESTED)).accepted());
  }

  @Test
  void wrongOptionsStaleCardsForeignConversationsAndSupersededSourcesFailClosed() throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var card = h.choices.decision(h.choices.pending(h.chat, 7).orElseThrow(), 7);
    h.begin("Keep the requested policy value");
    assertTrue(h.choices.confirm(h.chat, command(card, "approve"), 7).isEmpty());
    var wrongHash = command(card, ChatEvent.KEEP_REQUESTED_ACTION);
    wrongHash.setArtifactHash("forged");
    assertTrue(h.choices.confirm(h.chat, wrongHash, 7).isEmpty());
    assertTrue(h.choices.confirm(h.chat, command(card, ChatEvent.KEEP_REQUESTED_ACTION), 8).isEmpty());
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty());

    var foreign = new Harness();
    foreign.captureQuestion();
    foreign.choices.pending(foreign.chat, 7).orElseThrow();
    foreign.begin("Keep the requested policy value");
    assertTrue(foreign.choices.confirm(foreign.chat, command(card, ChatEvent.KEEP_REQUESTED_ACTION), 7).isEmpty());
    h.begin("A later unrelated source.");
    assertTrue(h.choices.confirm(h.chat, command(card, ChatEvent.KEEP_REQUESTED_ACTION), 7).isEmpty());
    h.conversations.clearMessages(h.chat);
    h.begin("Keep the requested policy value");
    assertTrue(h.choices.confirm(h.chat, command(card, ChatEvent.KEEP_REQUESTED_ACTION), 7).isEmpty());
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty());
  }

  @Test
  void changingTheAcceptedBaseInvalidatesTheOfferedCard() throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var card = h.choices.decision(h.choices.pending(h.chat, 7).orElseThrow(), 7);
    h.begin("Also preserve identifiers.");
    var extra = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new FactInput("extra", List.of("entry"), FactKind.CONSTRAINT, Polarity.NEGATIVE,
            "Do not remove identifiers.")), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    assertTrue(h.update(extra).accepted());
    h.begin("Keep the requested policy value");
    assertTrue(h.choices.confirm(h.chat, command(card, ChatEvent.KEEP_REQUESTED_ACTION), 7).isEmpty());
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "stage-clarification"})
  void theChatCommandValidatesTheCurrentCardBeforeContinuing(String gateId) throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var facade = mock(CreateChainApplicationFacade.class);
    var snapshot = new CreateChainExecutionSnapshot(h.chat, "run-policy", CreateChainExecutionStatus.INPUT_REQUIRED, 7,
        new CreateChainPendingAction.Clarify("Choose access control.", List.of(), gateId), "");
    when(facade.snapshot(h.chat)).thenReturn(Optional.of(snapshot));
    when(facade.continueWithInput(any())).thenReturn(Multi.createFrom().empty());
    var service = new ChatDecisionService(facade, mock(ApprovalQuestionStore.class), h.store);
    field(service, "policyChoices", h.choices);
    field(service, "conversationService", h.conversations);
    var card = service.openDecision(h.chat).orElseThrow();
    assertEquals(PolicyChoiceSelection.ARTIFACT_TYPE, card.artifactType());
    var wrong = command(card, ChatEvent.KEEP_REQUESTED_ACTION);
    wrong.setArtifactHash("old-card");
    h.begin(wrong);
    service.apply(h.chat, wrong).collect().asList().await().indefinitely();
    verify(facade, never()).continueWithInput(any());
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty());
    var correct = command(card, ChatEvent.KEEP_REQUESTED_ACTION);
    service.apply(h.chat, correct).collect().asList().await().indefinitely();
    verify(facade, never()).continueWithInput(any());
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty(), "A different command cannot reuse the rejected command's archived source.");
    when(facade.snapshot(h.chat)).thenReturn(Optional.of(new CreateChainExecutionSnapshot(h.chat, "run-policy",
        CreateChainExecutionStatus.INPUT_REQUIRED, 8,
        new CreateChainPendingAction.Clarify("Choose access control.", List.of(), gateId), "")));
    correct = command(service.openDecision(h.chat).orElseThrow(), ChatEvent.KEEP_REQUESTED_ACTION);
    h.begin(correct);
    service.apply(h.chat, correct).collect().asList().await().indefinitely();
    var input = ArgumentCaptor.forClass(ContinueCreateChainCommand.class);
    verify(facade).continueWithInput(input.capture());
    assertTrue(input.getValue().clarificationText().contains("KEEP_REQUESTED (NONE)"));
    assertTrue(input.getValue().clarificationText().contains("In the same update"));
    assertTrue(input.getValue().clarificationText().contains("propertyKey=accessControlType on owner=[entry]"));
    var trusted = h.store.trustedPolicyChoices(h.chat).getFirst().selection();
    assertTrue(input.getValue().clarificationText().contains("sourceTurnId=" + trusted.sourceTurnId()));
    assertTrue(input.getValue().clarificationText().contains(ChatDecisionService.transcriptMarker(correct)));
    assertEquals(Decision.KEEP_REQUESTED, h.store.trustedPolicyChoices(h.chat).getFirst().selection().decision());
  }

  @ParameterizedTest
  @ValueSource(strings = {"action", "artifactType", "artifactHash", "revision", "missing"})
  void theArchivedSelectionMustMatchEveryCommandField(String mismatch) throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var card = h.choices.decision(h.choices.pending(h.chat, 7).orElseThrow(), 7);
    var chosen = command(card, ChatEvent.KEEP_REQUESTED_ACTION);
    var archived = h.json.valueToTree(chosen);
    if (!mismatch.equals("missing")) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) archived).put(mismatch,
          mismatch.equals("revision") ? "7" : "another-value");
    }
    String text = ChatDecisionService.transcriptMarker(chosen);
    var source = h.archive.beginInput(h.chat, text, null);
    h.archive.resolveInput(h.chat, source, text, mismatch.equals("missing") ? null : archived);
    h.store.beginTurn(h.chat, text);
    assertTrue(h.choices.confirm(h.chat, chosen, 7).isEmpty());
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty());
  }

  @Test
  void changingArchivedCommandMetadataInvalidatesPersistedSelectionAuthority() throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var card = h.choices.decision(h.choices.pending(h.chat, 7).orElseThrow(), 7);
    var chosen = command(card, ChatEvent.KEEP_REQUESTED_ACTION);
    var source = h.begin(chosen);
    assertTrue(h.choices.confirm(h.chat, chosen, 7).isPresent());
    h.archive.resolveInput(h.chat, source, ChatDecisionService.transcriptMarker(chosen),
        h.json.valueToTree(command(card, ChatEvent.USE_RECOMMENDED_ACTION)));
    assertTrue(new PolicyChoiceSelection(h.artifacts, h.sessions, h.conversations).trusted(h.chat).isEmpty());
    assertFalse(h.update(resolve(Decision.KEEP_REQUESTED)).accepted());
  }

  @Test
  void theConfirmedSelectionRemainsReadableFromTheArchivedTurnAfterReload() throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var card = h.choices.decision(h.choices.pending(h.chat, 7).orElseThrow(), 7);
    var chosen = command(card, ChatEvent.KEEP_REQUESTED_ACTION);
    var source = h.begin(chosen);
    var selected = h.choices.confirm(h.chat, chosen, 7).orElseThrow();
    var reloaded = new PolicyChoiceSelection(h.artifacts, h.sessions, h.conversations);
    var archived = h.archive.read(h.chat).activeTurns().stream()
        .filter(turn -> turn.id().equals(source.turnId())).findFirst().orElseThrow();
    assertEquals(h.json.readTree(h.json.writeValueAsBytes(chosen)),
        archived.metadata().path("resolvedDecision"));
    assertEquals(List.of(selected), h.store.trustedPolicyChoices(h.chat));
    assertEquals(List.of(selected), reloaded.trusted(h.chat));
    assertTrue(reloaded.pending(h.chat, 8).isEmpty());
    assertTrue(h.store.validateForContinuation(h.chat).stream()
        .anyMatch(issue -> issue.code().equals("POLICY_RESPONSE_REQUIRED")));
    assertTrue(h.update(resolve(Decision.KEEP_REQUESTED)).accepted());
    assertTrue(h.store.validateForContinuation(h.chat).isEmpty());
    assertEquals(List.of(selected), reloaded.trusted(h.chat));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aStaleTypedClickDoesNotBlockTheNextCurrentClickButSemanticInputRemainsProtected(boolean semanticInput) throws Exception {
    var h = new Harness();
    h.captureQuestion();
    var card = h.choices.decision(h.choices.pending(h.chat, 7).orElseThrow(), 7);
    var stale = command(card, ChatEvent.USE_RECOMMENDED_ACTION);
    stale.setRevision(6);
    var oldSource = h.begin(stale);
    assertTrue(h.choices.confirm(h.chat, stale, 7).isEmpty());
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty());
    if (semanticInput) h.begin("Also require a different route before proceeding.");
    var valid = command(card, ChatEvent.KEEP_REQUESTED_ACTION);
    var latest = h.begin(valid);
    var selected = h.choices.confirm(h.chat, valid, 7);
    assertEquals(!semanticInput, selected.isPresent());
    if (selected.isPresent()) assertEquals(latest.turnId(), selected.orElseThrow().selection().sourceTurnId());
    var archivedOld = h.archive.read(h.chat).activeTurns().stream()
        .filter(turn -> turn.id().equals(oldSource.turnId())).findFirst().orElseThrow();
    assertEquals("READY", archivedOld.status());
    assertNull(archivedOld.consumedDraftReference());
  }

  @Test
  void aRawAnswerAmendmentOffersACardAndAWrongModelAnswerCannotIgnoreTheSelection() throws Exception {
    var h = new Harness();
    h.captureQuestion();
    h.select(7, ChatEvent.KEEP_REQUESTED_ACTION);
    assertTrue(h.update(resolve(Decision.KEEP_REQUESTED)).accepted());
    var accepted = h.store.latestRevision(h.chat).orElseThrow().reference();
    var source = h.begin("Change my policy answer to RBAC.");
    var amendment = new AmendmentInput("/facts/policy/policyConflict/decision", source.turnId(), "Change my policy answer to RBAC.");
    var outcome = h.discover(answer(Decision.USE_RECOMMENDED, List.of(amendment)));
    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass());
    assertTrue(outcome.message().contains(PipelineGates.STAGE_CLARIFICATION));
    assertTrue(outcome.message().contains("Keep NONE or use recommended RBAC?"));
    assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
    assertEquals(Decision.KEEP_REQUESTED, h.store.confirmedAnswers(h.chat).getFirst().policy().decision());
    int history = h.artifacts.history(h.sessions.active(h.chat), CompilationArtifacts.Kind.REQUIREMENT_POLICY_CHOICE).size();
    assertTrue(h.store.pendingPolicyReplacement(h.chat).isPresent());
    assertEquals(history, h.artifacts.history(h.sessions.active(h.chat), CompilationArtifacts.Kind.REQUIREMENT_POLICY_CHOICE).size());

    var selectedSource = h.select(8, ChatEvent.USE_RECOMMENDED_ACTION);
    assertTrue(h.store.pendingPolicyReplacement(h.chat).isEmpty());
    assertTrue(h.store.validateForContinuation(h.chat).stream().anyMatch(issue ->
        issue.code().equals("POLICY_RESPONSE_REQUIRED") && issue.path().equals("/draft/facts/policy/policyConflict/decision")));
    var wrong = h.discover(answer(Decision.KEEP_REQUESTED, List.of()));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, wrong.outcomeClass());
    assertTrue(wrong.recoveryCause().findings().stream().anyMatch(finding -> finding.code().equals("POLICY_RESPONSE_REQUIRED")));
    assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
    assertTrue(new PolicyChoiceSelection(h.artifacts, h.sessions, h.conversations).pending(h.chat, 9).isEmpty());
    assertTrue(h.update(answer(Decision.USE_RECOMMENDED, List.of(new AmendmentInput(
        "/facts/policy/policyConflict/decision", selectedSource.turnId(),
        h.archive.read(h.chat).activeTurns().getLast().message().content())))).accepted());
    assertTrue(h.store.validateForContinuation(h.chat).isEmpty());
  }

  @Test
  void aNoOpSelectionDoesNotSuppressALaterExplicitAmendmentOnTheSameBase() throws Exception {
    var h = new Harness();
    h.captureQuestion();
    h.select(7, ChatEvent.KEEP_REQUESTED_ACTION);
    assertTrue(h.update(resolve(Decision.KEEP_REQUESTED)).accepted());
    var accepted = h.store.latestRevision(h.chat).orElseThrow().reference();
    var first = h.begin("Change my policy answer to RBAC.");
    assertFalse(h.update(answer(Decision.USE_RECOMMENDED, List.of(new AmendmentInput(
        "/facts/policy/policyConflict/decision", first.turnId(), "Change my policy answer to RBAC.")))).accepted());
    h.select(8, ChatEvent.KEEP_REQUESTED_ACTION);
    assertTrue(h.update(answer(Decision.KEEP_REQUESTED, List.of())).accepted());
    assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
    assertTrue(h.store.validateForContinuation(h.chat).isEmpty());
    assertTrue(h.choices.pending(h.chat, 9).isEmpty());

    var later = h.begin("Now change my policy answer to RBAC.");
    assertFalse(h.update(answer(Decision.USE_RECOMMENDED, List.of(new AmendmentInput(
        "/facts/policy/policyConflict/decision", later.turnId(), "Now change my policy answer to RBAC.")))).accepted());
    assertEquals(later.turnId(), h.store.pendingPolicyReplacement(h.chat).orElseThrow().sourceTurnId());
    var next = h.choices.pending(h.chat, 10).orElseThrow();
    assertEquals(later.turnId(), next.sourceTurnId());
    assertEquals(accepted, next.draftReference());
    var chosen = command(h.choices.decision(next, 10), ChatEvent.USE_RECOMMENDED_ACTION);
    var selectedSource = h.begin(chosen);
    assertTrue(h.choices.confirm(h.chat, chosen, 10).isPresent());
    assertTrue(h.update(answer(Decision.USE_RECOMMENDED, List.of(new AmendmentInput(
        "/facts/policy/policyConflict/decision", selectedSource.turnId(), ChatDecisionService.transcriptMarker(chosen))))).accepted());
  }

  private static void field(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static ChatDecisionCommand command(ChatEvent.Decision card, String action) {
    var command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactType(card.artifactType());
    command.setArtifactHash(card.artifactHash());
    command.setRevision(card.revision());
    return command;
  }

  private static FactInput policy(Decision decision) {
    return new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Requested NONE; corporate RBAC recommendation.", null,
        new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4", "accessControlType", "NONE", "RBAC", "policy-choice", decision));
  }

  private static DraftUpdate resolve(Decision decision) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(policy(decision)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of("policy-choice"), null);
  }

  private static DraftUpdate answer(Decision decision, List<AmendmentInput> amendments) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(policy(decision)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, amendments);
  }

  private static final class Harness {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    final ConversationService conversations = new ConversationService();
    final ConversationArchive archive = conversations.archive();
    final RequirementDraftStore store = new RequirementDraftStore(artifacts, sessions, conversations);
    final PolicyChoiceSelection choices = new PolicyChoiceSelection(artifacts, sessions, conversations);
    final String chat = "trusted-policy-" + UUID.randomUUID();
    final RequirementCaptureTools tools;
    final ToolCallFingerprintStore fingerprints = new ToolCallFingerprintStore();
    final CaptureAttemptFeedbackStore feedback = new CaptureAttemptFeedbackStore(fingerprints);

    Harness() throws Exception {
      tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), json,
          new CaptureToolOutcomeGateway(fingerprints, feedback), feedback, null, null, null, conversations);
      field(tools, "elementSchemas", org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService
          .createForUnitTests(json));
    }

    void captureQuestion() throws Exception {
      capture(false);
    }

    void captureQuestionBehindAnother() throws Exception {
      capture(true);
    }

    void capture(boolean earlierQuestion) throws Exception {
      var source = begin("Create external GET /policy with NONE. Proceed without IDS.");
      var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
          "Caller", "GET /policy", "External endpoint", null, null)), List.of()),
          List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Exercise policy choices."),
              new FactInput("native-entry", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
                  "Use the external HTTP endpoint.", null, null,
                  new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
                      "entry", null, null, null, List.of(new PlanProperty("externalRoute", "true")), List.of())),
              policy(null)),
          List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/policy", null, null, null)),
          earlierQuestion ? List.of(new QuestionInput("entry-topic", List.of("entry"), "Which topic should the trigger use?",
              new AuthorQuestionEvidence("entry", "authorNote", AuthorQuestionEvidence.Kind.MISSING_VALUE,
                  List.of(source.turnId()))),
              new QuestionInput("policy-choice", List.of("entry"), "Keep NONE or use recommended RBAC?",
                  new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
                      List.of(source.turnId()))))
              : List.of(new QuestionInput("policy-choice", List.of("entry"), "Keep NONE or use recommended RBAC?",
                  new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
                      List.of(source.turnId())))), new DraftSettings(false, null));
      try (var ignored = ToolSession.open(chat)) {
        var result = json.readValue(tools.captureRequirementDraft(input), RequirementCaptureResult.class);
        assertTrue(result.accepted(), result.toString());
      }
    }

    ConversationArchive.Input begin(String text) {
      var source = archive.beginInput(chat, text, null);
      archive.resolveInput(chat, source, text);
      store.beginTurn(chat, text);
      return source;
    }

    ConversationArchive.Input begin(ChatDecisionCommand command) {
      String text = ChatDecisionService.transcriptMarker(command);
      var source = archive.beginInput(chat, text, json.valueToTree(command));
      archive.resolveInput(chat, source, text, json.valueToTree(command));
      store.beginTurn(chat, text);
      return source;
    }

    ConversationArchive.Input select(long revision, String action) {
      var card = choices.decision(choices.pending(chat, revision).orElseThrow(), revision);
      var chosen = command(card, action);
      var source = begin(chosen);
      assertTrue(choices.confirm(chat, chosen, revision).isPresent());
      return source;
    }

    StageOutcome discover(DraftUpdate changes) throws Exception {
      var capability = new RequirementDiscoveryCapability(null, store, (cid, text) -> {
        try {
          update(changes);
          store.finishTurn(cid, RequirementDiscoveryDirective.STAY);
          return Multi.createFrom().empty();
        } catch (Exception failure) {
          return Multi.createFrom().failure(failure);
        }
      }, null);
      field(capability, "captureFeedback", feedback);
      return capability.execute(new StageExecutionContext("policy-run", chat, "requirement-discovery", "execution", "attempt",
          null, null, List.of(), Map.of("userText", "Apply the current policy answer."))).collect().asList().await().indefinitely()
          .stream().filter(CapabilitySignal.Completed.class::isInstance).map(CapabilitySignal.Completed.class::cast)
          .findFirst().orElseThrow().outcome();
    }

    RequirementCaptureResult update(DraftUpdate update) throws Exception {
      try (var ignored = ToolSession.open(chat)) {
        return json.readValue(tools.updateRequirementDraft(update), RequirementCaptureResult.class);
      }
    }
  }
}
