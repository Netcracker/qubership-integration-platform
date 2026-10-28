package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.ai.plan.RequirementRevisionAdmission.PreservationOutcome.UNPROVEN;
import static org.qubership.integration.platform.ai.plan.RequirementRevisionAdmission.PreservationOutcome.VERIFIED;
import static org.qubership.integration.platform.ai.plan.RequirementRevisionAdmission.PreservationOutcome.VIOLATED;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.CatalogVersionSelection;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.plan.RequirementRevisionAdmission.NativeInputs;
import org.qubership.integration.platform.ai.plan.RequirementRevisionAdmission.PreservationReport;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class RequirementPreservationCheckTest {

  @ParameterizedTest
  @ValueSource(strings = {"Use recommended RBAC.", "Keep NONE despite the RBAC recommendation."})
  void aModelRecordedPolicyAnswerDoesNotEstablishTheAuthorsSelectedOption(String reply) throws Exception {
    var h = new Harness();
    h.begin("Create external POST /policy-check with NONE. If RBAC is selected, use role qip-viewer."
        + " Proceed without IDS.");
    assertTrue(h.capture(policyDraft(null, "NONE")).accepted());
    var answerSource = h.begin(reply);
    var captured = h.update(updateFacts(List.of(policyFact(Decision.USE_RECOMMENDED)), List.of("policy-choice")));
    assertFalse(captured.accepted());
    assertTrue(captured.issues().stream().anyMatch(issue -> issue.code().equals("POLICY_RESPONSE_REQUIRED")));
    assertTrue(h.store.confirmedAnswers(h.chat).isEmpty());
    assertTrue(h.store.trustedPolicyChoices(h.chat).isEmpty());
    assertTrue(h.archive.readSource(h.chat, answerSource.turnId(), 0, 4096).contains(reply));
    assertNotEquals(VERIFIED, h.evaluate(h.lastAttempt(), null).outcome());
    assertEquals(policyDraft(null, "NONE"), h.store.get(h.chat).orElseThrow().authoredDraft());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anIndependentSelectedValueCanProveOnlyItsExactAtomicPolicyCorrection(boolean intentOnPolicyFact) throws Exception {
    var h = new Harness();
    h.begin("Create external POST /policy-check with NONE. If RBAC is selected, use role qip-viewer."
        + " Proceed without IDS.");
    var base = policyDraft(null, "NONE", intentOnPolicyFact);
    assertTrue(h.capture(base).accepted());
    var accepted = h.store.latestRevision(h.chat).orElseThrow().reference();
    h.selectPolicy("use-recommended");
    var expected = policyDraft(Decision.USE_RECOMMENDED, "RBAC", intentOnPolicyFact);
    var refused = h.update(updateFacts(expected.facts().stream().filter(fact -> !fact.sourceFactId().equals("goal")).toList(),
        List.of("policy-choice")));
    assertFalse(refused.accepted(), refused.toString());
    assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
    assertEquals(base, h.store.get(h.chat).orElseThrow().authoredDraft());
    assertTrue(h.store.confirmedAnswers(h.chat).isEmpty());
    assertEquals(h.store.trustedPolicyChoices(h.chat).getFirst().selection().sourceTurnId(),
        h.archive.pendingInputs(h.chat).getFirst().id());
    var before = h.documents();
    var result = h.evaluate(h.lastAttempt(), new NativeInputs(brief(expected), policyGraph("RBAC")));
    assertEquals(VERIFIED, result.outcome(), result.toString());
    assertTrue(result.changedPaths().containsAll(List.of(
        "/facts/" + (intentOnPolicyFact ? "policy" : "access") + "/elementIntent/requestedProperties/1/value",
        "/facts/policy/policyConflict/decision", "/openQuestions/policy-choice/text", "/recordOrder/openQuestions/0")),
        result.changedPaths().toString());
    assertTrue(result.findings().stream().anyMatch(finding -> finding.checker().equals("author-origin")
        && finding.outcome() == VERIFIED && finding.evidenceReferences().contains(
            h.store.trustedPolicyChoices(h.chat).getFirst().reference())));
    assertEquals(before, h.documents(), "The checker must remain read-only.");
    assertEquals(UNPROVEN, h.evaluate(h.lastAttempt(), null).outcome(), "Consumer inputs remain mandatory.");
    h.restartStore();
    assertEquals(result, h.evaluate(h.lastAttempt(), new NativeInputs(brief(expected), policyGraph("RBAC"))));
    assertEquals(base, h.store.get(h.chat).orElseThrow().authoredDraft(), "Diagnostic proof alone must not grant admission.");
  }

  @Test
  void theSamePropertyCorrectionCannotOverrideTheOppositeRecordedPolicyDecision() throws Exception {
    var h = new Harness();
    h.begin("Create external POST /policy-check with NONE. If RBAC is selected, use role qip-viewer."
        + " Proceed without IDS.");
    assertTrue(h.capture(policyDraft(null, "NONE")).accepted());
    h.selectPolicy("keep-requested");
    assertTrue(h.update(updateFacts(List.of(policyFact(Decision.KEEP_REQUESTED)), List.of("policy-choice")))
        .accepted());
    var candidate = policyDraft(Decision.KEEP_REQUESTED, "RBAC");
    var brief = brief(candidate);
    var nativeResult = new CompilerSecurityValidator().validate(policyGraph("RBAC"), brief);
    assertFalse(nativeResult.valid());
    assertTrue(nativeResult.issues().stream().anyMatch(issue -> "security-policy-confirmed".equals(issue.findingCode())));
    assertFalse(h.update(updateFacts(List.of(accessFact("RBAC")), List.of())).accepted());
    assertNotEquals(VERIFIED, h.evaluate(h.lastAttempt(), new NativeInputs(brief, policyGraph("RBAC"))).outcome());
    assertFalse(h.update(updateFacts(List.of(policyFact(Decision.USE_RECOMMENDED)), List.of())).accepted());
    var changedAnswer = h.evaluate(h.lastAttempt(), null);
    assertEquals(VIOLATED, changedAnswer.outcome());
    assertTrue(changedAnswer.findings().stream().anyMatch(finding -> finding.outcome() == VIOLATED
        && finding.path().contains("policyConflict")));
    assertEquals(Decision.KEEP_REQUESTED, h.store.confirmedAnswers(h.chat).getFirst().policy().decision());
  }

  @ParameterizedTest
  @ValueSource(strings = {"name", "exact-value", "formula", "exclusion", "type", "count", "order"})
  void changingAnAuthorObligationCannotBeVerifiedFromItsEarlierCapture(String field) throws Exception {
    var h = new Harness();
    var base = protectedDraft();
    h.begin("Create POST /policy-check. Name the chain Preservation.Check. Preserve invoiceType."
        + " Calculate total = amount * 0.2. Do not send credentials. Use exactly two scripts;"
        + " first-script executes before second-script. Proceed without IDS.");
    assertTrue(h.capture(base).accepted());
    var accepted = h.store.latestRevision(h.chat).orElseThrow().reference();
    var changed = h.mapper.<ObjectNode>valueToTree(base.facts().stream()
        .filter(fact -> fact.sourceFactId().equals(field)).findFirst().orElseThrow());
    switch (field) {
      case "name" -> ((ObjectNode) changed.path("elementIntent")).put("requestedName", "Replacement.Check");
      case "exact-value" -> ((ObjectNode) changed.path("elementIntent")).putArray("exactValues").add("otherType");
      case "formula" -> changed.put("text", "Calculate total = amount * 0.3.");
      case "exclusion" -> changed.put("polarity", "POSITIVE");
      case "type" -> ((ObjectNode) changed.path("elementIntent")).put("elementType", "mapper-2");
      case "count" -> changed.put("text", "Use exactly one script.");
      case "order" -> ((ObjectNode) changed.path("elementIntent")).putNull("predecessorElementRef");
      default -> throw new AssertionError(field);
    }
    var result = h.update(updateFacts(List.of(h.mapper.treeToValue(changed, FactInput.class)), List.of()));
    assertFalse(result.accepted());
    var report = h.evaluate(h.lastAttempt(), null);
    assertNotEquals(VERIFIED, report.outcome(), report.toString());
    assertTrue(report.changedPaths().stream().anyMatch(path -> path.startsWith("/facts/" + field)), report.toString());
    assertEquals(accepted, h.store.latestRevision(h.chat).orElseThrow().reference());
    assertEquals(base, h.store.get(h.chat).orElseThrow().authoredDraft());
  }

  @Test
  void aCompleteDiffRetainsAllCoupledLossesAndNativeFindings() throws Exception {
    var h = new Harness();
    h.begin("Create POST /policy-check and preserve identifiers. Proceed without IDS.");
    var base = simpleDraft(List.of(goal(), new FactInput("keep-id", List.of("entry"), FactKind.CONSTRAINT,
        Polarity.NEGATIVE, "Do not remove identifiers.")));
    assertTrue(h.capture(base).accepted());
    var update = new DraftUpdate(List.of(), List.of(new InteractionInput("entry", Direction.INBOUND,
        "Different caller", "POST /changed", null, null, null)), List.of(), List.of(), List.of(),
        List.of(new FactInput("renamed-goal", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            goal().text())), List.of(), List.of("goal", "keep-id"), List.of(), List.of("entry"),
        List.of(), List.of(), List.of(), new DraftSettings(true, null));
    var refused = h.update(update);
    assertFalse(refused.accepted());
    var report = h.evaluate(h.lastAttempt(), null);
    assertNotEquals(VERIFIED, report.outcome());
    for (String prefix : List.of("/facts/goal", "/facts/keep-id", "/facts/renamed-goal",
        "/capabilities/entry", "/interactions/entry", "/settings/idsRequested")) {
      assertTrue(report.changedPaths().stream().anyMatch(path -> path.startsWith(prefix)), prefix + ": " + report);
    }
    assertTrue(refused.issues().stream().allMatch(nativeIssue -> report.nativeFindings().stream()
        .anyMatch(issue -> nativeIssue.code().equals(issue.code()) && nativeIssue.path().equals(issue.path()))));
    assertTrue(report.findings().size() > 1, report.toString());
    assertTrue(report.pendingWork().stream().anyMatch(work -> work.code().equals("CAPABILITY_CHOICE_REQUIRED")));
  }

  @Test
  void changingInteractionOrderIsVisibleEvenWhenEveryStableIdAndValueIsRetained() throws Exception {
    var h = new Harness();
    h.begin("Receive POST /policy-check and POST /other. Proceed without IDS.");
    var entry = simpleDraft(List.of(goal()));
    var interactions = List.of(entry.flow().interactions().getFirst(), new InteractionInput("other",
        Direction.INBOUND, "Other caller", "POST /other", "Other endpoint", null, null));
    var base = new DraftInput(new FlowInput(interactions, List.of()), entry.facts(),
        List.of(entry.capabilities().getFirst(), new CapabilityInput("other", "http-trigger", HttpMode.CUSTOM,
            HttpMethod.POST, "/other", null, null, null)), List.of(), entry.settings());
    assertTrue(h.capture(base).accepted());
    var reversed = new DraftInput(new FlowInput(List.of(interactions.getLast(), interactions.getFirst()), List.of()),
        base.facts(), base.capabilities(), base.openQuestions(), base.settings());
    assertNotEquals(RequirementCaptureProjection.flow(base), RequirementCaptureProjection.flow(reversed));
    assertFalse(h.capture(reversed).accepted());
    var report = h.evaluate(h.lastAttempt(), null);
    assertNotEquals(VERIFIED, report.outcome());
    assertFalse(report.changedPaths().isEmpty(), report.toString());
  }

  @Test
  void reclassifyingANameCannotUseTheDisappearanceOfFactsRequiredAsProof() throws Exception {
    var h = new Harness();
    h.begin("Create POST /policy-check named Preservation.Check. Proceed without IDS.");
    var name = protectedDraft().facts().stream().filter(fact -> fact.sourceFactId().equals("name"))
        .findFirst().orElseThrow();
    assertTrue(h.capture(simpleDraft(List.of(name))).accepted());
    var proposed = new FactInput(name.sourceFactId(), name.interactionIds(), FactKind.BEHAVIOR,
        name.polarity(), name.text(), name.fieldMapping(), name.policyConflict(), name.elementIntent());
    assertTrue(RequirementCaptureProjection.pending(simpleDraft(List.of(name)), List.of()).stream()
        .anyMatch(work -> work.code().equals("FACTS_REQUIRED")));
    assertFalse(RequirementCaptureProjection.pending(simpleDraft(List.of(proposed)), List.of()).stream()
        .anyMatch(work -> work.code().equals("FACTS_REQUIRED")));
    assertFalse(h.update(updateFacts(List.of(proposed), List.of())).accepted());
    assertNotEquals(VERIFIED, h.evaluate(h.lastAttempt(), null).outcome());
  }

  @Test
  void mappingAndTransitionChangesRemainCoupledWithoutFormulaEquivalenceEvidence() throws Exception {
    var h = new Harness();
    h.begin("Receive POST /policy-check and then POST https://example.test/out."
        + " Map amount to total using amount * 0.2. Proceed without IDS.");
    var entry = simpleDraft(List.of(goal()));
    var mapping = new FactInput("mapping", List.of("entry", "delivery"), FactKind.FIELD_MAPPING,
        Polarity.POSITIVE, "Map amount to total using amount * 0.2.",
        new FieldMappingInput("entry", "$.amount", "delivery", "$.total", "$.amount * 0.2"));
    var base = new DraftInput(new FlowInput(List.of(entry.flow().interactions().getFirst(),
        new InteractionInput("delivery", Direction.OUTBOUND, "https://example.test/out", "POST /out", null, null, null)),
        List.of(new TransitionInput("entry", "delivery"))), List.of(goal(), mapping),
        List.of(entry.capabilities().getFirst(), new CapabilityInput("delivery", "http-sender", null,
            HttpMethod.POST, "https://example.test/out", null, null, null)), List.of(), entry.settings());
    assertTrue(h.capture(base).accepted());
    var changed = new FactInput(mapping.sourceFactId(), mapping.interactionIds(), mapping.kind(), mapping.polarity(),
        mapping.text(), new FieldMappingInput("entry", "$.amount", "delivery", "$.total", "$.amount * 0.3"));
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), base.flow().transitions(),
        List.of(), List.of(changed), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    assertFalse(h.update(update).accepted());
    var report = h.evaluate(h.lastAttempt(), null);
    assertNotEquals(VERIFIED, report.outcome());
    assertTrue(report.changedPaths().stream().anyMatch(path -> path.startsWith("/facts/mapping/fieldMapping")));
    assertTrue(report.changedPaths().stream().anyMatch(path -> path.startsWith("/transitions/entry")));
    assertTrue(report.findings().stream().anyMatch(finding -> finding.path().startsWith("/facts/mapping")
        && finding.ownerInteractionIds().containsAll(List.of("entry", "delivery"))));
    assertTrue(report.nativeFindings().stream().anyMatch(issue -> issue.code().equals("MAPPING_TRANSITION_REQUIRED")));
    assertEquals(VIOLATED, report.outcome());
    assertTrue(report.findings().stream().anyMatch(finding -> finding.outcome() == UNPROVEN));
    assertTrue(report.findings().stream().anyMatch(finding -> finding.outcome() == VIOLATED));
  }

  @Test
  void staleSourceAndForeignAttemptReferencesFailClosed() throws Exception {
    var h = new Harness();
    h.begin("Create POST /policy-check. Proceed without IDS.");
    assertTrue(h.capture(simpleDraft(List.of(goal()))).accepted());
    assertFalse(h.update(updateFacts(List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL,
        Polarity.POSITIVE, "Discard the requested result.")), List.of())).accepted());
    var attempt = h.lastAttempt();
    h.begin("Preserve the existing endpoint.");
    assertEquals(UNPROVEN, h.evaluate(attempt, null).outcome());
    var foreign = new Harness();
    foreign.begin("Another conversation.");
    assertEquals(UNPROVEN, foreign.evaluate(attempt, null).outcome());
  }

  @Test
  void aNewAcceptedBaseMakesAnEarlierRejectedAttemptStale() throws Exception {
    var h = new Harness();
    var source = h.begin("Create POST /policy-check and preserve identifiers. Proceed without IDS.");
    assertTrue(h.capture(simpleDraft(List.of(goal()))).accepted());
    assertFalse(h.update(updateFacts(List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL,
        Polarity.POSITIVE, "Discard the requested result.")), List.of())).accepted());
    var attempt = h.lastAttempt();
    var add = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new FactInput("retain-id", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
            "Preserve identifiers.", null, null, null,
            new RequirementCaptureInput.ClaimEvidenceInput(source.turnId(), "preserve identifiers"))), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    assertTrue(h.update(add).accepted());
    assertEquals(UNPROVEN, h.evaluate(attempt, null).outcome());
  }

  @Test
  void archivedDecisionCommandMetadataDoesNotProveTheCommandWasValidated() throws Exception {
    var h = new Harness();
    h.begin("Create POST /policy-check. Proceed without IDS.");
    assertTrue(h.capture(simpleDraft(List.of(goal()))).accepted());
    var accepted = h.store.latestRevision(h.chat).orElseThrow();
    var command = new ChatDecisionCommand();
    command.setAction("approve");
    command.setArtifactType(CatalogVersionSelection.ARTIFACT_TYPE);
    command.setArtifactHash(accepted.contentHash());
    command.setRevision(accepted.sequence());
    var input = h.archive.beginInput(h.chat, "Typed decision", null);
    h.archive.resolveInput(h.chat, input, "Approved catalog-operation-version " + accepted.contentHash(),
        h.mapper.valueToTree(command));
    h.store.beginTurn(h.chat, "Typed decision");
    assertEquals("approve", h.archive.read(h.chat).activeTurns().getLast().metadata()
        .path("resolvedDecision").path("action").asText());
    var reclassified = new FactInput("goal", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, goal().text());
    assertFalse(h.update(updateFacts(List.of(reclassified), List.of())).accepted());
    assertEquals(UNPROVEN, h.evaluate(h.lastAttempt(), null).outcome());
  }

  @Test
  void evaluationIsReadOnlyAndReproducibleAfterReload() throws Exception {
    var h = new Harness();
    h.begin("Create POST /policy-check. Proceed without IDS.");
    assertTrue(h.capture(simpleDraft(List.of(goal()))).accepted());
    assertFalse(h.update(updateFacts(List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL,
        Polarity.POSITIVE, "Discard the requested result.")), List.of())).accepted());
    var attempt = h.lastAttempt();
    var documents = h.documents();
    var source = h.archive.read(h.chat);
    var accepted = h.store.latestRevision(h.chat);
    var first = h.evaluate(attempt, null);
    assertEquals(documents, h.documents());
    assertEquals(source, h.archive.read(h.chat));
    assertEquals(accepted, h.store.latestRevision(h.chat));
    h.restartStore();
    assertEquals(first, h.evaluate(attempt, null));
    assertEquals(documents, h.documents());
    assertEquals(source, h.archive.read(h.chat));
  }

  private static DraftInput protectedDraft() {
    var chain = new RequirementElementIntent("chain", "chain", RequirementElementIntent.Placement.CHAIN,
        null, null, null, "Preservation.Check", List.of(), List.of());
    var first = new RequirementElementIntent("first-script", "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, null, null, List.of(), List.of("invoiceType"));
    var second = new RequirementElementIntent("second-script", "script", RequirementElementIntent.Placement.LOCAL_NODE,
        "entry", null, "first-script", null, List.of(), List.of());
    return simpleDraft(List.of(goal(),
        new FactInput("name", List.of(), FactKind.PARAMETER, Polarity.POSITIVE,
            "Name the chain Preservation.Check.", null, null, chain),
        new FactInput("exact-value", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Preserve invoiceType.", null, null, first),
        new FactInput("formula", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Calculate total = amount * 0.2."),
        new FactInput("exclusion", List.of("entry"), FactKind.CONSTRAINT, Polarity.NEGATIVE,
            "Do not send credentials."),
        new FactInput("type", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
            "Use script for first-script and preserve invoiceType.", null, null, first),
        new FactInput("count", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
            "Use exactly two scripts."),
        new FactInput("order", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "first-script executes before second-script.", null, null, second)));
  }

  private static DraftInput policyDraft(Decision decision, String access) {
    var base = simpleDraft(List.of(goal(), accessFact(access), policyFact(decision)));
    return new DraftInput(base.flow(), base.facts(), base.capabilities(), decision == null
        ? List.of(new QuestionInput("policy-choice", List.of("entry"), "Keep NONE or use recommended RBAC?"))
        : List.of(), base.settings());
  }

  private static DraftInput policyDraft(Decision decision, String access, boolean intentOnPolicyFact) {
    var draft = policyDraft(decision, access);
    if (!intentOnPolicyFact) return draft;
    var policy = policyFact(decision);
    var combined = new FactInput(policy.sourceFactId(), policy.interactionIds(), policy.kind(), policy.polarity(),
        policy.text(), policy.fieldMapping(), policy.policyConflict(), accessFact(access).elementIntent());
    return new DraftInput(draft.flow(), List.of(goal(), combined), draft.capabilities(), draft.openQuestions(), draft.settings());
  }

  private static FactInput policyFact(Decision decision) {
    return new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Requested NONE; corporate RBAC recommendation.", null, new RequirementPolicyConflict(
            CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType", "NONE", "RBAC", "policy-choice", decision));
  }

  private static FactInput accessFact(String access) {
    return new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "External route uses NONE; if RBAC is selected use qip-viewer.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, policyProperties(access), List.of()));
  }

  private static List<PlanProperty> policyProperties(String access) {
    return List.of(new PlanProperty("externalRoute", "true"), new PlanProperty("accessControlType", access),
        new PlanProperty("roles", "[\"qip-viewer\"]"));
  }

  private static ChainPlanGraph policyGraph(String access) {
    return new ChainPlanGraph("1.0", new ChainSection("Policy.Check", ""), List.of(
        new ChainPlanNode("entry", "http-trigger", "Entry", null, null, policyProperties(access))), List.of());
  }

  private static RequirementBrief brief(DraftInput input) {
    return RequirementBriefProjector.project(new RequirementBrief("Check policy handling", List.of(), List.of(),
        List.of(), List.of(), "", null, "", RequirementCaptureProjection.facts(input))
        .withFlow(RequirementCaptureProjection.flow(input)));
  }

  private static DraftInput simpleDraft(List<FactInput> facts) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /policy-check", "External policy endpoint", null, null)), List.of()), facts,
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/policy-check", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  private static FactInput goal() {
    return new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Check policy handling.");
  }

  private static DraftUpdate updateFacts(List<FactInput> facts, List<String> removeQuestions) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), facts, List.of(),
        List.of(), List.of(), List.of(), List.of(), removeQuestions, null);
  }

  private static final class Harness {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    final CompilationSessions sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    final ConversationService conversations = new ConversationService();
    final ConversationArchive archive = conversations.archive();
    final DeterministicElementSchemaService schemas = DeterministicElementSchemaService.createForUnitTests(mapper);
    final String chat = UUID.randomUUID().toString();
    RequirementDraftStore store;
    RequirementCaptureTools tools;

    Harness() {
      restartStore();
    }

    void restartStore() {
      store = new RequirementDraftStore(artifacts, sessions, conversations);
      tools = new RequirementCaptureTools(store, new RequirementDraftTool(store), mapper);
      tools.elementSchemas = schemas;
      tools.conversations = conversations;
    }

    ConversationArchive.Input begin(String text) {
      var input = archive.beginInput(chat, text, null);
      archive.resolveInput(chat, input, text);
      store.beginTurn(chat, text);
      return input;
    }

    RequirementCaptureResult capture(DraftInput draft) throws Exception {
      try (var ignored = ToolSession.open(chat)) {
        return mapper.readValue(tools.captureRequirementDraft(draft), RequirementCaptureResult.class);
      }
    }

    RequirementCaptureResult update(DraftUpdate update) throws Exception {
      try (var ignored = ToolSession.open(chat)) {
        return mapper.readValue(tools.updateRequirementDraft(update), RequirementCaptureResult.class);
      }
    }

    void selectPolicy(String action) {
      var choices = new PolicyChoiceSelection(artifacts, sessions, conversations);
      var offered = choices.pending(chat, 7).orElseThrow();
      var command = new ChatDecisionCommand();
      command.setArtifactType(PolicyChoiceSelection.ARTIFACT_TYPE);
      command.setArtifactHash(offered.hash());
      command.setRevision(7);
      command.setAction(action);
      String marker = ChatDecisionService.transcriptMarker(command);
      var decision = mapper.valueToTree(command);
      var source = archive.beginInput(chat, marker, decision);
      archive.resolveInput(chat, source, marker, decision);
      store.beginTurn(chat, marker);
      assertTrue(choices.confirm(chat, command, 7).isPresent());
    }

    Reference lastAttempt() {
      return artifacts.latest(store.activeCompilationId(chat), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().reference();
    }

    PreservationReport evaluate(Reference attempt, NativeInputs inputs) {
      return RequirementRevisionAdmission.evaluate(chat, attempt, store, artifacts, conversations, schemas, inputs);
    }

    Map<String, JsonNode> documents() throws Exception {
      Map<String, JsonNode> result = new LinkedHashMap<>();
      for (String key : blobs.list("")) result.put(key, mapper.readTree(blobs.get(key).orElseThrow()));
      return result;
    }
  }
}
