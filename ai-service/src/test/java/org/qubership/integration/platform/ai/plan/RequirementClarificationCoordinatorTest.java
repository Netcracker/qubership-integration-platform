package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.capture.CaptureProposal;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementClarificationCoordinatorTest {
  private final RequirementClarificationCoordinator coordinator = new RequirementClarificationCoordinator();

  @Test
  void twoAuthoredQuestionsReturnOnlyTheFirst() {
    var first = question("path-question", "What is the helper URI?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "path");
    var second = question("retry-question", "How many retries should the helper use?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "retryCount");
    var active = coordinator.activeQuestion(revision(), draft(List.of(first, second), List.of(), List.of()), "source-gen-1")
        .orElseThrow();

    assertEquals(first, active.question());
    assertEquals("What is the helper URI?", active.question().text());
    assertFalse(active.question().text().contains("How many retries"));
  }

  @Test
  void resolvingTheFirstQuestionExposesTheSecondWithoutReordering() {
    var first = question("path-question", "What is the helper URI?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "path");
    var second = question("retry-question", "How many retries should the helper use?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "retryCount");
    var third = question("note-question", "Which note should the helper keep?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "authorNote");
    var open = coordinator.activeQuestion(revision(), draft(List.of(first, second, third), List.of(), List.of()), "source-gen-1")
        .orElseThrow();
    var resolved = coordinator.activeQuestion(revision(), draft(List.of(second, third), List.of(), List.of()), "source-gen-1")
        .orElseThrow();
    var last = coordinator.activeQuestion(revision(), draft(List.of(third), List.of(), List.of()), "source-gen-1")
        .orElseThrow();

    assertEquals("path-question", open.question().questionId());
    assertEquals("retry-question", resolved.question().questionId());
    assertEquals("How many retries should the helper use?", resolved.question().text());
    assertEquals("note-question", last.question().questionId());
  }

  @Test
  void updatingTheActiveQuestionReplacesItInPlace() {
    var revised = question("path-question", "Which full runtime URI should the helper use?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "path");
    var second = question("retry-question", "How many retries should the helper use?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "retryCount");
    var appended = question("note-question", "Which note should the helper keep?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "authorNote");
    var active = coordinator.activeQuestion(revision(), draft(List.of(revised, second, appended), List.of(), List.of()),
        "source-gen-1").orElseThrow();
    var afterHead = coordinator.activeQuestion(revision(), draft(List.of(second, appended), List.of(), List.of()),
        "source-gen-1").orElseThrow();

    assertEquals("path-question", active.question().questionId());
    assertEquals("Which full runtime URI should the helper use?", active.question().text());
    assertEquals("retry-question", afterHead.question().questionId());
    assertEquals("note-question", coordinator.activeQuestion(revision(),
        draft(List.of(appended), List.of(), List.of()), "source-gen-1").orElseThrow().question().questionId());
  }

  @Test
  void policyChoiceHeadProjectsThePolicyDecisionAndALaterPolicyCannotJumpAhead() {
    var ordinary = question("path-question", "What is the helper URI?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "path");
    var policyQuestion = question("policy-choice", "Keep NONE or use recommended RBAC?", AuthorQuestionEvidence.Kind.POLICY_CHOICE, "entry",
        "accessControlType");
    var blocked = coordinator.activeQuestion(revision(), draft(List.of(ordinary, policyQuestion),
        List.of(policy(null)), List.of()), "source-gen-1").orElseThrow();
    var head = coordinator.activeQuestion(revision(), draft(List.of(policyQuestion), List.of(policy(Decision.KEEP_REQUESTED)),
        List.of()), "source-gen-1").orElseThrow();

    assertEquals("path-question", blocked.question().questionId());
    assertEquals("policy-choice", head.question().questionId());
    assertEquals("KEEP_REQUESTED", head.targetProjection().path("policyDecision").asText());
    assertEquals("NONE", head.targetProjection().path("selectedValue").asText());
    assertEquals("entry", head.targetProjection().path("interactionId").asText());
    assertEquals("accessControlType", head.targetProjection().path("propertyKey").asText());
  }

  @Test
  void unresolvedPolicyProjectionKeepsDecisionAndSelectedValueAbsent() {
    var policyQuestion = question("policy-choice", "Keep NONE or use recommended RBAC?", AuthorQuestionEvidence.Kind.POLICY_CHOICE, "entry",
        "accessControlType");
    var projection = coordinator.targetProjection(draft(List.of(policyQuestion), List.of(policy(null)), List.of()),
        policyQuestion);

    assertTrue(projection.path("policyDecision").isNull());
    assertTrue(projection.path("selectedValue").isNull());
  }

  @Test
  void ordinaryQuestionProjectionListsRequestedPropertyValues() {
    var asked = question("path-question", "What is the helper URI?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "path");
    var projection = coordinator.targetProjection(draft(List.of(asked), List.of(), List.of()), asked);

    assertEquals("helper", projection.path("interactionId").asText());
    assertEquals("path", projection.path("propertyKey").asText());
    assertEquals(List.of("https://hooks.example/run"),
        List.of(projection.path("requestedValues").get(0).asText()));
  }

  @Test
  void chainCallCatalogChoiceProjectsTargetReferenceAndResolvedTarget() {
    var asked = question("chain-target", "Which catalog chain should the call use?", AuthorQuestionEvidence.Kind.CATALOG_CHOICE, "child",
        "targetReference");
    var input = chainCall(List.of(asked), "trigger-element");
    var draft = RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash",
        java.util.Map.of("child", "resolved-trigger"));
    var projection = coordinator.targetProjection(draft, asked);

    assertEquals("child", projection.path("interactionId").asText());
    assertEquals("targetReference", projection.path("propertyKey").asText());
    assertEquals("trigger-element", projection.path("targetReference").asText());
    assertEquals("resolved-trigger", projection.path("resolvedTarget").asText());
  }

  @Test
  void authoredChainCallQuestionStaysActiveWhenTargetIsResolved() {
    String chainQuestion = "Which chain-trigger element id should this chain call use?";
    var asked = question("chain-target", chainQuestion, AuthorQuestionEvidence.Kind.CATALOG_CHOICE, "child",
        "targetReference");
    var draft = RequirementCaptureProjection.toDraft(chainCall(List.of(asked), "trigger-element"), null, List.of(),
        "1", "hash", java.util.Map.of("child", "resolved-trigger"));
    var active = coordinator.activeQuestion(revision(), draft, "source-gen-1").orElseThrow();

    assertEquals(java.util.Map.of("child", "resolved-trigger"),
        RequirementCaptureProjection.resolvedTargets(draft));
    assertEquals(asked, active.question());
    assertEquals(chainQuestion, active.question().text());
    assertEquals(List.of(chainQuestion), draft.openQuestions());
  }

  @Test
  void offerIdDigestsIdentityWithoutQuestionText() {
    var asked = question("path-question", "What is the helper URI?", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "path");
    var active = coordinator.activeQuestion(revision(), draft(List.of(asked), List.of(), List.of()), "source-gen-1")
        .orElseThrow();
    ClarificationTransition.Offer offer = coordinator.createOffer(active, 7L);
    var renamed = new RequirementClarificationCoordinator.ActiveQuestion(active.draftReference(),
        question("path-question", "A different question sentence.", AuthorQuestionEvidence.Kind.MISSING_VALUE, "helper", "path"),
        active.sourceGeneration(), active.targetProjection());

    assertEquals(offer, coordinator.createOffer(active, 7L));
    assertEquals(offer.offerId(), coordinator.createOffer(renamed, 7L).offerId());
    assertNotEquals(offer.offerId(), coordinator.createOffer(active, 8L).offerId());
    assertEquals(CaptureProposal.digest(List.of(
        Kind.REQUIREMENT_DRAFT.name(), "draft-1", "hash-1", "path-question", "7", "source-gen-1")), offer.offerId());
    assertEquals(active.draftReference(), offer.draftReference());
    assertEquals("path-question", offer.questionId());
    assertEquals(7L, offer.runRevision());
    assertEquals("source-gen-1", offer.sourceGeneration());
  }

  @Test
  void projectedStringWithoutATypedQuestionIsNotAnActiveQuestion() {
    var legacy = new RequirementDraft(false, "Known requirements", DraftDecision.NEEDS_INPUT,
        List.of("Which endpoint should receive the request?"), "gather", "1", "hash", null, false, List.of(),
        false, null, false, RequirementFlow.EMPTY);

    assertTrue(coordinator.activeQuestion(revision(), legacy, "source-gen-1").isEmpty());
  }

  private static Revision revision() {
    return new Revision("draft-1", "compilation-1", Kind.REQUIREMENT_DRAFT, "lineage-1", 1L, "1",
        Instant.EPOCH, "test", "1", "hash-1", List.of(), null, null, null);
  }

  private static QuestionInput question(String id, String text, AuthorQuestionEvidence.Kind kind, String interactionId,
      String propertyKey) {
    return new QuestionInput(id, List.of(interactionId), text,
        new AuthorQuestionEvidence(interactionId, propertyKey, kind, List.of("turn-1")));
  }

  private static FactInput policy(Decision decision) {
    return new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Requested NONE; corporate RBAC recommendation.", null,
        new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4", "accessControlType", "NONE", "RBAC",
            "policy-choice", decision));
  }

  private static RequirementDraft draft(List<QuestionInput> questions, List<FactInput> extraFacts,
      List<CapabilityInput> extraCapabilities) {
    var facts = new java.util.ArrayList<FactInput>();
    facts.add(new FactInput("goal", List.of("helper"), FactKind.GOAL, Polarity.POSITIVE, "Call the helper."));
    facts.add(new FactInput("uri", List.of("helper"), FactKind.PARAMETER, Polarity.POSITIVE, "The helper URI is known.",
        null, null, new RequirementElementIntent("helper", "http-sender", Placement.ANCHOR, "helper", null, null, null,
            List.of(new PlanProperty("path", "https://hooks.example/run")), List.of())));
    facts.addAll(extraFacts);
    var capabilities = new java.util.ArrayList<CapabilityInput>();
    capabilities.add(new CapabilityInput("helper", "http-sender", null, HttpMethod.GET, null, null, null, null));
    capabilities.addAll(extraCapabilities);
    var input = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /start", null, null, null),
        new InteractionInput("helper", Direction.OUTBOUND, "HTTP helper", "GET", null, null, null)),
        List.of(new TransitionInput("entry", "helper"))),
        facts, capabilities, questions, new DraftSettings(false, null));
    return RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "hash");
  }

  private static DraftInput chainCall(List<QuestionInput> questions, String targetReference) {
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /context", null, null, null),
        new InteractionInput("child", Direction.OUTBOUND, "Existing chain", "Run", null, null, null)),
        List.of(new TransitionInput("entry", "child"))),
        List.of(new FactInput("goal", List.of("child"), FactKind.GOAL, Polarity.POSITIVE, "Call the existing chain.")),
        List.of(new CapabilityInput("entry", "http-trigger", null, HttpMethod.GET, "/context", null, null, null),
            new CapabilityInput("child", "chain-call-2", null, null, null, null, null, targetReference)),
        questions, new DraftSettings(false, null));
  }
}
