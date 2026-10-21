package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class PolicyChoiceAmendmentCaptureTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void typedChoiceAndItsExistingPropertyCanBeCapturedTogether(boolean intentOnPolicyFact) throws Exception {
    var h = new Harness(intentOnPolicyFact);
    var source = h.select();
    var result = h.update(h.correction(), List.of(h.amendment(h.propertyPath(), source)));

    assertTrue(result.accepted(), result.toString());
    var saved = h.store.get(h.chat).orElseThrow().authoredDraft();
    assertEquals(Decision.USE_RECOMMENDED, saved.facts().stream().filter(fact -> fact.sourceFactId().equals("policy"))
        .findFirst().orElseThrow().policyConflict().decision());
    assertEquals("RBAC", saved.facts().stream().filter(fact -> fact.sourceFactId().equals(h.intentFactId()))
        .findFirst().orElseThrow().elementIntent().requestedProperties().getFirst().value());
    assertEquals(h.initial.facts().stream().filter(fact -> fact.sourceFactId().equals("sibling")).findFirst().orElseThrow(),
        saved.facts().stream().filter(fact -> fact.sourceFactId().equals("sibling")).findFirst().orElseThrow());
    assertTrue(h.archive.pendingInputs(h.chat).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void decisionOnlyCaptureRetainsTheSourceUntilExistingPropertiesMatch(boolean intentOnPolicyFact) throws Exception {
    var h = new Harness(intentOnPolicyFact);
    var source = h.select();
    var selected = h.correction().getFirst();
    var answerOnly = new FactInput(selected.sourceFactId(), selected.interactionIds(), selected.kind(), selected.polarity(),
        selected.text(), selected.fieldMapping(), selected.policyConflict(), h.initial.facts().getFirst().elementIntent());
    var accepted = h.store.latestRevision(h.chat).orElseThrow().reference();
    var result = h.update(List.of(answerOnly), List.of());

    assertFalse(result.accepted(), result.toString());
    assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
    assertEquals(source.turnId(), h.archive.pendingInputs(h.chat).getFirst().id());
    assertTrue(h.update(h.correction(), List.of(h.amendment(h.propertyPath(), source))).accepted());
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-owner", "wrong-options", "unamended-sibling", "amended-sibling",
      "parent-intent", "parent-fact", "wrong-value", "wrong-key", "partial-quote",
      "wrapped-amended-sibling", "wrapped-parent-intent", "wrapped-partial-quote"})
  void typedPolicyChoiceDoesNotAuthorizeOtherChanges(String change) throws Exception {
    var h = new Harness(false);
    var source = h.select();
    if (change.startsWith("wrapped-")) {
      h.reloadAfterClarification();
      change = change.substring("wrapped-".length());
    }
    var updates = new ArrayList<>(h.correction());
    var amendments = new ArrayList<>(List.of(h.amendment(h.propertyPath(), source)));
    var selected = updates.getFirst();
    if (change.startsWith("parent-")) {
      amendments.set(0, h.amendment("/facts/http-settings" + (change.equals("parent-intent") ? "/elementIntent" : ""), source));
    } else if (change.equals("wrong-value")) {
      updates.set(1, Harness.config("ABAC"));
    } else if (change.equals("wrong-key")) {
      var config = updates.get(1);
      var intent = config.elementIntent();
      var properties = new ArrayList<>(intent.requestedProperties());
      properties.set(1, new PlanProperty("roles", "[]"));
      updates.set(1, new FactInput(config.sourceFactId(), config.interactionIds(), config.kind(), config.polarity(),
          config.text(), null, null, new RequirementElementIntent(intent.elementRef(), intent.elementType(), intent.placement(),
              intent.ownerInteractionId(), null, null, null, properties, intent.exactValues())));
      amendments.add(h.amendment("/facts/http-settings/elementIntent/requestedProperties/1/value", source));
    } else if (change.equals("partial-quote")) {
      amendments.set(0, new AmendmentInput(h.propertyPath(), source.turnId(), "recommended"));
    } else if (change.equals("wrong-owner")) {
      updates.set(0, new FactInput(selected.sourceFactId(), List.of("audit"), selected.kind(), selected.polarity(),
          selected.text(), selected.fieldMapping(), selected.policyConflict(), selected.elementIntent()));
    } else if (change.equals("wrong-options")) {
      var policy = selected.policyConflict();
      updates.set(0, new FactInput(selected.sourceFactId(), selected.interactionIds(), selected.kind(), selected.polarity(),
          selected.text(), selected.fieldMapping(), new RequirementPolicyConflict(policy.policyId(), policy.propertyKey(),
              "ABAC", policy.recommendedValue(), policy.questionId(), policy.decision()), selected.elementIntent()));
    } else {
      updates.add(new FactInput("sibling", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, "Drop request IDs."));
      if (change.equals("amended-sibling")) amendments.add(h.amendment("/facts/sibling/text", source));
    }
    var accepted = h.store.latestRevision(h.chat).orElseThrow().reference();
    var result = h.update(updates, amendments);

    assertFalse(result.accepted(), result.toString());
    assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
    assertEquals(h.initial, h.store.get(h.chat).orElseThrow().authoredDraft());
    assertEquals(source.turnId(), h.archive.pendingInputs(h.chat).getFirst().id());
  }

  private static final class Harness {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    final ConversationService conversations = new ConversationService();
    final ConversationArchive archive = conversations.archive();
    RequirementDraftStore store = new RequirementDraftStore(artifacts, sessions, conversations);
    RequirementCaptureTools tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), json);
    final String chat = "policy-amendment-" + UUID.randomUUID();
    final boolean intentOnPolicyFact;
    final DraftInput initial;

    Harness(boolean intentOnPolicyFact) throws Exception {
      this.intentOnPolicyFact = intentOnPolicyFact;
      tools.elementSchemas = DeterministicElementSchemaService.createForUnitTests(json);
      tools.conversations = conversations;
      var facts = new ArrayList<FactInput>();
      facts.add(new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
          "Requested NONE; company policy recommends RBAC.", null,
          new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY,
              "accessControlType", "NONE", "RBAC", "choice", null), intentOnPolicyFact ? intent("NONE") : null));
      if (!intentOnPolicyFact) facts.add(config("NONE"));
      facts.add(new FactInput("sibling", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, "Preserve request IDs."));
      initial = withNativeAnchors(new DraftInput(new FlowInput(List.of(
          new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /tasks", null, null, null),
          new InteractionInput("audit", Direction.INBOUND, "Caller", "POST /audit", null, null, null)), List.of()),
          facts, List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
              "/tasks", null, null, null), new CapabilityInput("audit", "http-trigger", HttpMode.CUSTOM,
              HttpMethod.POST, "/audit", null, null, null)),
          List.of(new QuestionInput("choice", List.of("entry"), "Keep NONE or use recommended RBAC?")),
          new DraftSettings(false, null)));
      String text = "Expose external POST /tasks with access control NONE. If recommended RBAC is selected, use role qip-viewer."
          + " Also receive internal POST /audit. Preserve request IDs. Proceed without IDS.";
      var source = archive.beginInput(chat, text, null);
      archive.resolveInput(chat, source, text);
      store.beginTurn(chat, text);
      try (var ignored = ToolSession.open(chat)) {
        var captured = json.readValue(tools.captureRequirementDraft(initial), RequirementCaptureResult.class);
        assertTrue(captured.accepted(), captured.toString());
      }
    }

    ConversationArchive.Input select() {
      var choices = new PolicyChoiceSelection(artifacts, sessions, conversations);
      var offered = choices.pending(chat, 7).orElseThrow();
      var command = new ChatDecisionCommand();
      command.setAction(ChatEvent.USE_RECOMMENDED_ACTION);
      command.setArtifactType(PolicyChoiceSelection.ARTIFACT_TYPE);
      command.setArtifactHash(offered.hash());
      command.setRevision(7);
      String marker = ChatDecisionService.transcriptMarker(command);
      var source = archive.beginInput(chat, marker, json.valueToTree(command));
      archive.resolveInput(chat, source, marker, json.valueToTree(command));
      store.beginTurn(chat, marker);
      assertTrue(choices.confirm(chat, command, 7).isPresent());
      return source;
    }

    List<FactInput> correction() {
      var old = initial.facts().getFirst();
      var policy = old.policyConflict();
      var selected = new FactInput(old.sourceFactId(), old.interactionIds(), old.kind(), old.polarity(), old.text(),
          old.fieldMapping(), new RequirementPolicyConflict(policy.policyId(), policy.propertyKey(), policy.requestedValue(),
              policy.recommendedValue(), policy.questionId(), Decision.USE_RECOMMENDED), intentOnPolicyFact ? intent("RBAC") : null);
      return intentOnPolicyFact ? List.of(selected) : List.of(selected, config("RBAC"));
    }

    void reloadAfterClarification() {
      store.associateClarification(chat);
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), json);
      tools.elementSchemas = DeterministicElementSchemaService.createForUnitTests(json);
      tools.conversations = conversations;
      store.beginTurn(chat, archive.pendingInputs(chat).getFirst().message().content());
    }

    String intentFactId() { return intentOnPolicyFact ? "policy" : "http-settings"; }
    String propertyPath() { return "/facts/" + intentFactId() + "/elementIntent/requestedProperties/0/value"; }

    AmendmentInput amendment(String path, ConversationArchive.Input source) {
      String quote = archive.read(chat).activeTurns().stream().filter(turn -> turn.id().equals(source.turnId()))
          .findFirst().orElseThrow().message().content();
      return new AmendmentInput(path, source.turnId(), quote);
    }

    RequirementCaptureResult update(List<FactInput> facts, List<AmendmentInput> amendments) throws Exception {
      try (var ignored = ToolSession.open(chat)) {
        return json.readValue(tools.updateRequirementDraft(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), facts, List.of(), List.of(), List.of(), List.of(), List.of(), List.of("choice"), null, amendments)),
            RequirementCaptureResult.class);
      }
    }

    private static FactInput config(String access) {
      return new FactInput("http-settings", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
          "Configure the external endpoint.", null, null, intent(access));
    }

    private static RequirementElementIntent intent(String access) {
      return new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
          "entry", null, null, null, List.of(new PlanProperty("accessControlType", access),
              new PlanProperty("roles", "[\"qip-viewer\"]"), new PlanProperty("externalRoute", "true")), List.of());
    }
  }
}
