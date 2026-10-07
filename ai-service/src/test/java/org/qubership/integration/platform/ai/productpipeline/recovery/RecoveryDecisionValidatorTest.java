package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class RecoveryDecisionValidatorTest {

  private RecoveryEvidence evidence;
  private RecoveryContext context;
  private Reference briefRef;
  private Reference planRef;
  private Reference semanticRef;
  private Reference graphRef;

  @BeforeEach
  void setUp() {
    briefRef = new Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-brief");
    planRef = new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan");
    semanticRef = new Reference(Kind.CHAIN_PLAN_GRAPH, "graph-1", "hash-graph");
    graphRef = semanticRef;
    evidence =
        new RecoveryEvidence(
            1,
            "failure-1",
            "MISSING_BRIEF_FACTS",
            "design-execution",
            briefRef,
            semanticRef,
            List.of(planRef),
            List.of(),
            null,
            List.of());
    RequirementBrief brief =
        new RequirementBrief("Proxy inventory", List.of(), List.of(), List.of(), List.of(), "");
    ChainPlanGraph graph =
        new ChainPlanGraph("1.0", new ChainSection("c1", "HealthProxy"), List.of(), List.of());
    context = new RecoveryContext(evidence, brief, graph, "en");
  }

  @Test
  void documentRecordReferenceIsAcceptedEvidence() {
    RecoveryEvidence withRecord =
        new RecoveryEvidence(
            1,
            "failure-1",
            "WRONG_OPERATION",
            "DATA_BEHAVIOR",
            "",
            null,
            null,
            List.of(planRef),
            List.of(),
            null,
            List.of(),
            List.of("create"));
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            planRef,
            List.of("create"),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Revise record create and submit the correction.");
    assertTrue(
        RecoveryDecisionValidator.validate(decision, new RecoveryContext(withRecord, null, null, "en"))
            .accepted());

    RecoveryDecision unknown =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            planRef,
            List.of("missing-record"),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Revise record create and submit the correction.");
    assertFalse(
        RecoveryDecisionValidator.validate(decision, new RecoveryContext(evidence, null, null, "en"))
            .accepted());
    assertFalse(
        RecoveryDecisionValidator.validate(unknown, new RecoveryContext(withRecord, null, null, "en"))
            .accepted());
  }

  @Test
  void nullDecisionOrContextIsRejected() {
    RecoveryDecision decision = validReviseBrief();
    assertFalse(RecoveryDecisionValidator.validate(null, context).accepted());
    assertFalse(RecoveryDecisionValidator.validate(decision, null).accepted());
  }

  @Test
  void reviseBriefMustTargetTheApprovedBrief() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            planRef,
            List.of(evidence.failureId()),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Fix the brief.");
    RecoveryDecisionValidator.Result result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(result.findings().stream().anyMatch(f -> f.contains("approved brief")));
  }

  @Test
  void derivationDefectCannotAskTheAuthorToRepairGeneratedTopology() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            planRef,
            List.of(evidence.failureId()),
            RecoveryAction.ASK_USER,
            List.of(),
            "Should onTaskResult be a produce instead of a trigger?",
            "The captured topology still treats an outbound Kafka operation as a trigger.");
    var result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(result.findings().stream().anyMatch(finding ->
        finding.contains("ASK_USER") && finding.contains("DERIVATION_DEFECT")));
    var repaired = new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, planRef,
        List.of(evidence.failureId()), RecoveryAction.REGENERATE_ARTIFACT, List.of(), "",
        "Correct the generated topology using the approved requirements.");
    assertTrue(RecoveryDecisionValidator.validate(repaired, context).accepted());
  }

  @ParameterizedTest
  @ValueSource(strings = {"httpMethod", "httpMethodRestrict", "path", "contextPath"})
  void missingBriefLabelCannotAskAgainForAnAcceptedEndpointValue(String propertyKey) {
    var endpoint = new org.qubership.integration.platform.ai.plan.RequirementFact("endpoint",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.ENDPOINT,
        "http-trigger", "Accept POST /orders", "", "", "", "POST", "/orders");
    var decision = new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT, briefRef,
        List.of(evidence.failureId()), RecoveryAction.ASK_USER, List.of(),
        "Which HTTP method should the trigger use?", "The trigger method is missing.",
        new AuthorQuestionEvidence("endpoint", propertyKey, AuthorQuestionEvidence.Kind.MISSING_VALUE,
            List.of("input-1")));

    var result = RecoveryDecisionValidator.validate(decision,
        new RecoveryContext(evidence, context.approvedBrief().withFacts(List.of(endpoint)),
            context.rejectedArtifact(), "en", List.of(authorInput("input-1", "Accept POST /orders"))));

    assertFalse(result.accepted(), "A failure label does not prove that the author omitted the supplied method.");
  }

  @Test
  void briefDefectMayAskForAnUnresolvedProductChoice() {
    var missingChoice = new RecoveryEvidence(1, evidence.failureId(), "MISSING_BRIEF_FACTS",
        "requirement-analysis", briefRef, semanticRef, List.of(planRef), List.of(), null, List.of());
    var decision = new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT, briefRef,
        List.of(missingChoice.failureId()), RecoveryAction.ASK_USER, List.of(),
        "Which consumer group should receive the events?", "The consumer group is unspecified.",
        new AuthorQuestionEvidence("consume", "groupId", AuthorQuestionEvidence.Kind.MISSING_VALUE,
            List.of("input-1")));
    var endpoint = new RequirementFact("consume", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT, "kafka-trigger-2", "Receive events");
    assertTrue(RecoveryDecisionValidator.validate(decision, new RecoveryContext(missingChoice,
        context.approvedBrief().withFacts(List.of(endpoint)), context.rejectedArtifact(), "en",
        List.of(authorInput("input-1", "Receive events")))).accepted());
  }

  @Test
  void sourceReviewCannotSkipAnEarlierMessageOrInventASourceId() {
    var endpoint = new RequirementFact("consume", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT, "kafka-trigger-2", "Receive events");
    var inputs = List.of(authorInput("first", "Use groupId=orders"), authorInput("second", "Use topic=changes"));
    for (var ids : List.of(List.of("second"), List.of("first", "second", "invented"))) {
      var decision = ask("consume", "groupId", AuthorQuestionEvidence.Kind.MISSING_VALUE, ids);
      var result = RecoveryDecisionValidator.validate(decision, new RecoveryContext(evidence,
          context.approvedBrief().withFacts(List.of(endpoint)), null, "en", inputs));
      assertFalse(result.accepted());
      assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("every saved author input")));
    }
  }

  @Test
  void applicableDefaultAnswersAMissingPropertyQuestionOnlyForItsOwner() {
    var endpoint = new RequirementFact("consume", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT, "kafka-trigger-2", "Receive events");
    var decision = ask("consume", "groupId", AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of("input-1"));
    for (String owner : List.of("consume", "other")) {
      var finding = new SemanticFinding("MISSING_BRIEF_FACTS", "required", "missing-group", owner,
          "kafka-trigger-2", List.of("groupId"), List.of(), List.of(), "",
          java.util.Map.of("groupId", "default-group"), List.of(), "{}");
      var observed = new RecoveryEvidence(1, evidence.failureId(), "MISSING_BRIEF_FACTS", "requirement-analysis",
          briefRef, semanticRef, List.of(planRef), List.of(finding), null, List.of());
      var result = RecoveryDecisionValidator.validate(decision, new RecoveryContext(observed,
          context.approvedBrief().withFacts(List.of(endpoint)), null, "en",
          List.of(authorInput("input-1", "Receive events"))));
      org.junit.jupiter.api.Assertions.assertEquals(!"consume".equals(owner), result.accepted());
    }
  }

  @Test
  void policyQuestionIsAllowedOnlyBeforeThePersistedAuthorChoice() {
    for (var choice : java.util.Arrays.asList(null, RequirementPolicyConflict.Decision.KEEP_REQUESTED,
        RequirementPolicyConflict.Decision.USE_RECOMMENDED)) {
      var policy = new RequirementPolicyConflict(org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType", "NONE", "RBAC",
          "access-choice", choice);
      var fact = new RequirementFact("access", RequirementFactPolarity.POSITIVE, RequirementFactKind.CONSTRAINT,
          "http-trigger", "External access", "", "", "", "", "", "", List.of("entry"), policy);
      var decision = ask("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE, List.of("input-1"));
      var result = RecoveryDecisionValidator.validate(decision, new RecoveryContext(policyEvidence("entry"),
          context.approvedBrief().withFacts(List.of(fact)), null, "en",
          List.of(authorInput("input-1", "Use NONE for external access"))));
      org.junit.jupiter.api.Assertions.assertEquals(choice == null, result.accepted());
    }
  }

  @Test
  void policyQuestionUsesTheFindingOwnerAndExactRule() {
    String corporate = org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator.EXTERNAL_RBAC_POLICY;
    var unrelatedConfirmed = policyFact("other-rule", "OTHER_POLICY", "entry", RequirementPolicyConflict.Decision.KEEP_REQUESTED);
    var decision = ask("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE, List.of("input-1"));
    for (String owner : List.of("entry", "entry-2")) {
      for (String rule : List.of(corporate, "OTHER_POLICY")) {
        var pending = policyFact("pending", rule, owner, null);
        var result = RecoveryDecisionValidator.validate(decision, new RecoveryContext(policyEvidence("entry"),
            context.approvedBrief().withFacts(List.of(pending, unrelatedConfirmed)), null, "en",
            List.of(authorInput("input-1", "Use external access"))));
        org.junit.jupiter.api.Assertions.assertEquals("entry".equals(owner) && corporate.equals(rule), result.accepted());
      }
    }
    var unrelatedQuestion = ask("entry-2", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE, List.of("input-1"));
    var unrelated = RecoveryDecisionValidator.validate(unrelatedQuestion, new RecoveryContext(policyEvidence("entry"),
        context.approvedBrief().withFacts(List.of(policyFact("pending", corporate, "entry-2", null))), null, "en",
        List.of(authorInput("input-1", "Use external access"))));
    assertFalse(unrelated.accepted());
  }

  private RecoveryEvidence policyEvidence(String owner) {
    var finding = new SemanticFinding("security-policy-choice", "Confirm external access", "policy-finding", owner,
        "http-trigger", List.of(), List.of(), List.of(), "", java.util.Map.of(), List.of(), "{}");
    return new RecoveryEvidence(1, evidence.failureId(), "SECURITY_POLICY", "design-execution", briefRef,
        semanticRef, List.of(planRef), List.of(finding), null, List.of());
  }

  private static RequirementFact policyFact(String id, String rule, String owner, RequirementPolicyConflict.Decision choice) {
    return new RequirementFact(id, RequirementFactPolarity.POSITIVE, RequirementFactKind.CONSTRAINT, "http-trigger",
        "External access", "", "", "", "", "", "", List.of(owner),
        new RequirementPolicyConflict(rule, "accessControlType", "NONE", "RBAC", id + "-question", choice));
  }

  @Test
  void aClaimedConflictRequiresDifferentValuesForTheSameOwnerAndKey() {
    var post = new RequirementFact("post", RequirementFactPolarity.POSITIVE, RequirementFactKind.ENDPOINT,
        "http-trigger", "Use POST", "", "", "", "POST", "", "", List.of("entry"));
    var get = new RequirementFact("get", RequirementFactPolarity.POSITIVE, RequirementFactKind.ENDPOINT,
        "http-trigger", "Use GET", "", "", "", "GET", "", "", List.of("entry"));
    var decision = ask("entry", "httpMethod", AuthorQuestionEvidence.Kind.CONFLICTING_VALUES, List.of("input-1"));
    for (var facts : List.of(List.of(post), List.of(post, get))) {
      var result = RecoveryDecisionValidator.validate(decision, new RecoveryContext(evidence,
          context.approvedBrief().withFacts(facts), null, "en", List.of(authorInput("input-1", "Resolve method"))));
      org.junit.jupiter.api.Assertions.assertEquals(facts.size() == 2, result.accepted());
    }
  }

  private RecoveryDecision ask(String owner, String key, AuthorQuestionEvidence.Kind kind, List<String> ids) {
    return new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT, briefRef, List.of(evidence.failureId()),
        RecoveryAction.ASK_USER, List.of(), "Which choice should be used?", "An author choice is needed.",
        new AuthorQuestionEvidence(owner, key, kind, ids));
  }

  @ParameterizedTest
  @ValueSource(strings = {"integrationSystemId", "integrationSpecificationGroupId", "integrationSpecificationId",
      "integrationOperationId", "integrationOperationMethod", "integrationOperationPath"})
  void nativeCatalogKeysCannotBeAskedAgainWhenTheApprovedBindingSuppliesThem(String key) {
    var call = new RequirementFact("call", RequirementFactPolarity.POSITIVE, RequirementFactKind.SERVICE_CALL,
        "service-call", "Call the approved catalog operation");
    var binding = new org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint(
        "3", "call", "call", "create", "system", "group", "specification", "operation",
        "http", "POST", "/items", "1", java.time.Instant.EPOCH, "catalog-evidence");
    var brief = context.approvedBrief().withFacts(List.of(call)).withCatalogBindings(List.of(binding));
    var decision = ask("call", key, AuthorQuestionEvidence.Kind.MISSING_VALUE, List.of("input-1"));
    var result = RecoveryDecisionValidator.validate(decision, new RecoveryContext(evidence, brief, null, "en",
        List.of(authorInput("input-1", "Use the approved catalog operation"))));
    assertFalse(result.accepted());
    assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("supplied value")));
  }

  @Test
  void aSourceCitationCannotAuthorizeSilentReplacementOfAConfirmedPolicyChoice() {
    var policy = new RequirementPolicyConflict("corporate-access", "accessControlType", "NONE", "RBAC",
        "access-choice", RequirementPolicyConflict.Decision.KEEP_REQUESTED);
    var fact = new RequirementFact("access", RequirementFactPolarity.POSITIVE, RequirementFactKind.CONSTRAINT,
        "http-trigger", "External access", "", "", "", "", "", "", List.of("entry"), policy);
    var decision = new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT, briefRef,
        List.of(evidence.failureId(), "input-1"), RecoveryAction.REVISE_BRIEF,
        List.of(new ProposedBriefChange("access", "accessControlType", "NONE", "RBAC", "SECURITY_POLICY", false)),
        "", "Apply RBAC.");
    assertFalse(RecoveryDecisionValidator.validate(decision, new RecoveryContext(evidence,
        context.approvedBrief().withFacts(List.of(fact)), null, "en",
        List.of(authorInput("input-1", "Keep NONE")))).accepted());
  }

  private static UserInput authorInput(String id, String text) {
    return new UserInput(id, "conversation-source", text, java.time.Instant.EPOCH);
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN_PROPERTY", "MISSING_REQUIRED_PROPERTY", "CONTRACT_SHAPE", "GRAPH_STRUCTURE"})
  void generatedContractFailureCannotBecomeAnAuthorQuestionByRelabeling(String observedCauseCode) {
    var observed = new RecoveryEvidence(1, evidence.failureId(), observedCauseCode,
        evidence.observingStageId(), briefRef, semanticRef, List.of(planRef), List.of(), null, List.of());
    var supplied = new org.qubership.integration.platform.ai.plan.RequirementFact("endpoint",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.ENDPOINT,
        "http-trigger", "Accept POST /orders", "", "", "", "POST", "/orders");
    var brief = context.approvedBrief().withFacts(List.of(supplied));
    var decision = new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT, briefRef,
        List.of(observed.failureId()), RecoveryAction.ASK_USER, List.of(),
        "Which HTTP method should the trigger use?", "The trigger method is missing.");

    var result = RecoveryDecisionValidator.validate(decision,
        new RecoveryContext(observed, brief, context.rejectedArtifact(), "en"));
    assertFalse(result.accepted(), result.toString());
    assertTrue(result.findings().stream().anyMatch(finding -> finding.contains(observedCauseCode)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN_PROPERTY", "MISSING_REQUIRED_PROPERTY", "CONTRACT_SHAPE", "GRAPH_STRUCTURE"})
  void generatedContractFailureCannotReopenTheApprovedBriefByRelabeling(String observedCauseCode) {
    var observed = new RecoveryEvidence(1, evidence.failureId(), observedCauseCode,
        evidence.observingStageId(), briefRef, semanticRef, List.of(planRef), List.of(), null, List.of());
    var decision = new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT, briefRef,
        List.of(observed.failureId()), RecoveryAction.REVISE_BRIEF, List.of(), "",
        "Rewrite the requirements to add the omitted generated property.");
    assertFalse(RecoveryDecisionValidator.validate(decision,
        new RecoveryContext(observed, context.approvedBrief(), context.rejectedArtifact(), "en")).accepted());
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNKNOWN_PROPERTY", "MISSING_REQUIRED_PROPERTY", "CONTRACT_SHAPE", "GRAPH_STRUCTURE"})
  void generatedContractFailureCannotParkBeforeTheRuntimeChecksTheBudget(String observedCauseCode) {
    var observed = new RecoveryEvidence(1, evidence.failureId(), observedCauseCode,
        evidence.observingStageId(), briefRef, semanticRef, List.of(planRef), List.of(), null, List.of());
    var decision = new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED, null,
        List.of(observed.failureId()), RecoveryAction.PARK, List.of(), "",
        "The generated artifact cannot be repaired.");
    assertFalse(RecoveryDecisionValidator.validate(decision,
        new RecoveryContext(observed, context.approvedBrief(), context.rejectedArtifact(), "en")).accepted());
  }

  @Test
  void derivationDefectCannotReviseTheBrief() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            evidence.approvedBriefRef(),
            List.of(evidence.failureId()),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Rewrite requirements.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void askUserMustBeAProductQuestion() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            evidence.approvedBriefRef(),
            List.of(evidence.failureId()),
            RecoveryAction.ASK_USER,
            List.of(),
            "Pick design-execution or requirement-analysis",
            "Choose a stage.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void missingEvidenceRefIsRejected() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            briefRef,
            List.of(),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Fix the brief.");
    RecoveryDecisionValidator.Result result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(result.findings().stream().anyMatch(f -> f.toLowerCase().contains("missing evidence")));
  }

  @Test
  void unknownEvidenceRefIsRejected() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            briefRef,
            List.of("unknown-ref"),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Fix the brief.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @ParameterizedTest
  @ValueSource(strings = {"plan-1", "hash-plan", "known-fact"})
  void referencesExposedInTheTypedContextAreValidSupportingEvidence(String id) {
    var fact = new RequirementFact("known-fact", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CONSTRAINT, "", "Known value", "", "", "", "", "");
    var decision = new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, planRef,
        List.of(evidence.failureId(), id), RecoveryAction.REGENERATE_ARTIFACT, List.of(), "", "Repair generated output.");
    assertTrue(RecoveryDecisionValidator.validate(decision,
        new RecoveryContext(evidence, context.approvedBrief().withFacts(List.of(fact)),
            context.rejectedArtifact(), "en")).accepted());
  }

  @Test
  void retryOperationWithUnknownSideEffectIsRejected() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.TECHNICAL_FAILURE,
            semanticRef,
            List.of(evidence.failureId()),
            RecoveryAction.RETRY_OPERATION,
            List.of(),
            "",
            "Retry the catalog lookup.");
    RecoveryDecisionValidator.Result result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                f -> {
                  String lower = f.toLowerCase();
                  return lower.contains("park") || lower.contains("side-effect");
                }));
  }

  @Test
  void retryOperationAcceptsRetryableTechnicalFailure() {
    RecoveryEvidence retryableEvidence =
        new RecoveryEvidence(
            1,
            "failure-1",
            "TIMEOUT",
            "design-execution",
            briefRef,
            semanticRef,
            List.of(planRef),
            List.of(),
            new TechnicalFailureRecord(
                true, 1, "catalog", "lookup", "30s", "corr-1", "TimeoutException", "timed out", "", ""),
            List.of());
    RecoveryContext retryableContext =
        new RecoveryContext(
            retryableEvidence,
            context.approvedBrief(),
            context.rejectedArtifact(),
            "en");
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.TECHNICAL_FAILURE,
            semanticRef,
            List.of(retryableEvidence.failureId()),
            RecoveryAction.RETRY_OPERATION,
            List.of(),
            "",
            "Retry the catalog lookup.");
    assertTrue(RecoveryDecisionValidator.validate(decision, retryableContext).accepted());
  }

  @Test
  void unclassifiedOnlyAllowsPark() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.UNCLASSIFIED,
            null,
            List.of(evidence.failureId()),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Try again.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void unclassifiedParkIsAccepted() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.UNCLASSIFIED,
            null,
            List.of(evidence.failureId()),
            RecoveryAction.PARK,
            List.of(),
            "",
            "We need more information.");
    assertTrue(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void regenerateArtifactCannotTargetTheApprovedBrief() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            briefRef,
            List.of(evidence.failureId()),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Regenerate the plan.");
    RecoveryDecisionValidator.Result result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(result.findings().stream().anyMatch(f -> f.toLowerCase().contains("approved brief")));
  }

  @Test
  void askUserRejectsBlankQuestion() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            briefRef,
            List.of(evidence.failureId()),
            RecoveryAction.ASK_USER,
            List.of(),
            "   ",
            "Which inventory API should we call?");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void userSummaryMustNotContainStageIds() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            briefRef,
            List.of(evidence.failureId()),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Return to design-planning and fix the trigger.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void parkMayOmitFaultArtifactRef() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            null,
            List.of(evidence.failureId()),
            RecoveryAction.PARK,
            List.of(),
            "",
            "The plan cannot be repaired automatically.");
    assertTrue(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void nonParkActionRequiresFaultArtifactRef() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            null,
            List.of(evidence.failureId()),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Regenerate the plan.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void validReviseBriefIsAccepted() {
    assertTrue(RecoveryDecisionValidator.validate(validReviseBrief(), context).accepted());
  }

  private RecoveryDecision validReviseBrief() {
    return new RecoveryDecision(
        RecoveryCauseClass.BRIEF_DEFECT,
        briefRef,
        List.of(evidence.failureId()),
        RecoveryAction.REVISE_BRIEF,
        List.of(),
        "",
        "Add the missing scheduler to the brief.");
  }
}
