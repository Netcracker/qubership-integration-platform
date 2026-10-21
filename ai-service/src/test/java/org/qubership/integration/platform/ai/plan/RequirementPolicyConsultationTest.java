package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
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
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class RequirementPolicyConsultationTest {

  @Test
  void policyChoiceRequiresASeparateUserReplyAndSurvivesProjection() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementDraftTool bindings = mock(RequirementDraftTool.class);
    when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    RequirementCaptureTools tools = new RequirementCaptureTools(store, bindings, mapper);
    String conversationId = "policy-consultation";
    store.beginTurn(conversationId, "Create an external endpoint with NONE. Proceed without IDS.");
    try (ToolSession.Handle ignored = ToolSession.open(conversationId)) {
      assertFalse(RequirementCaptureEditor.initialize(draft(Decision.KEEP_REQUESTED)).accepted());
      var captured = mapper.readValue(tools.captureRequirementDraft(draft(null)), RequirementCaptureResult.class);
      assertTrue(captured.accepted(), captured.toString());
      assertEquals(RequirementCaptureResult.NextAction.ASK_USER, captured.nextAction());
      var refused = mapper.readValue(tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.CONTINUE),
          RequirementCaptureResult.class);
      assertFalse(refused.accepted());
      assertFalse(mapper.readValue(tools.updateRequirementDraft(resolve(Decision.KEEP_REQUESTED)),
          RequirementCaptureResult.class).accepted());

      store.beginTurn(conversationId, "Keep NONE despite the RBAC recommendation.");
      var extra = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(new FactInput("detail", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
              "Keep the endpoint unchanged.")), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(), null);
      assertTrue(mapper.readValue(tools.updateRequirementDraft(extra), RequirementCaptureResult.class).accepted());
      var resolved = mapper.readValue(tools.updateRequirementDraft(resolve(Decision.KEEP_REQUESTED)),
          RequirementCaptureResult.class);
      assertTrue(resolved.accepted(), resolved.toString());
      assertEquals(RequirementCaptureResult.Readiness.READY, resolved.readiness());
      var saved = store.get(conversationId).orElseThrow();
      assertEquals(Decision.KEEP_REQUESTED, saved.facts().stream()
          .filter(f -> f.sourceFactId().equals("policy")).findFirst().orElseThrow().policyConflict().decision());
      var brief = new RequirementBrief("Policy test", List.of(), List.of(), List.of(), List.of(), "",
          "approved", saved.planningText(), saved.facts());
      var result = new CompilerSecurityValidator().validate(graph("entry", "NONE"), brief);
      assertTrue(result.valid(), result.summary());
      assertTrue(result.issues().stream().anyMatch(i -> i.severity() == ValidationSeverity.WARNING));
      assertFalse(new CompilerSecurityValidator().validate(graph("other", "NONE"), brief).valid());
      assertFalse(new CompilerSecurityValidator().validate(graph("entry", "ABAC"), brief).valid());
    }
  }

  @Test
  void anAnsweredPersistedQuestionCanAcquireMissingPolicyMetadata() throws Exception {
    DraftInput typed = draft(null);
    FactInput policy = policyFact(null);
    DraftInput legacy = new DraftInput(typed.flow(),
        List.of(typed.facts().getFirst(), new FactInput(policy.sourceFactId(), policy.interactionIds(),
            policy.kind(), policy.polarity(), policy.text())),
        typed.capabilities(), typed.openQuestions(), new DraftSettings(null, null));
    RequirementDraftStore store = new RequirementDraftStore();
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RequirementDraftTool bindings = mock(RequirementDraftTool.class);
    when(bindings.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    RequirementCaptureTools tools = new RequirementCaptureTools(store, bindings, mapper);
    String id = "missing-policy-metadata";
    store.beginTurn(id, "Create the endpoint with NONE.");
    try (ToolSession.Handle ignored = ToolSession.open(id)) {
      assertTrue(mapper.readValue(tools.captureRequirementDraft(legacy), RequirementCaptureResult.class).accepted());
    }
    assertFalse(store.canResolvePolicyChoice(id, policyFact(Decision.KEEP_REQUESTED)));
    store.beginTurn(id, "Keep NONE despite the RBAC recommendation.");
    assertTrue(store.canResolvePolicyChoice(id, policyFact(Decision.KEEP_REQUESTED)));
    assertTrue(RequirementCaptureEditor.apply(legacy, resolve(Decision.KEEP_REQUESTED)).accepted());
    try (ToolSession.Handle ignored = ToolSession.open(id)) {
      assertTrue(mapper.readValue(tools.updateRequirementDraft(resolve(Decision.KEEP_REQUESTED)),
          RequirementCaptureResult.class).accepted());
    }
    var wrongQuestion = new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        policy.text(), null, new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4",
            "accessControlType", "NONE", "RBAC", "unasked-question", Decision.KEEP_REQUESTED));
    assertFalse(store.canResolvePolicyChoice(id, wrongQuestion));
  }

  @Test
  void changedValuesCannotReuseAnEarlierConfirmation() {
    var old = draft(Decision.KEEP_REQUESTED);
    var changed = new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Use ABAC instead of NONE.", null,
        new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4", "accessControlType", "ABAC", "RBAC",
            "policy-choice", Decision.KEEP_REQUESTED));
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(changed), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    var result = RequirementCaptureEditor.apply(old, update);
    assertTrue(result.accepted());
    var issues = RequirementRevisionAdmission.validate(old, result.draft(), List.of(),
        java.util.Set.of(), java.util.Map.of(), fact -> true);
    assertTrue(issues.stream().anyMatch(i -> i.code().equals("POLICY_RESPONSE_REQUIRED")));
  }

  @Test
  void recommendingRbacDoesNotAuthorizeSilentChangesOrMissingRoles() {
    var facts = RequirementCaptureProjection.facts(draft(Decision.USE_RECOMMENDED));
    var brief = new RequirementBrief("Policy test", List.of(), List.of(), List.of(), List.of(), "",
        "approved", "", facts);
    CompilerSecurityValidator validator = new CompilerSecurityValidator();
    assertFalse(validator.validate(graph("entry", "NONE"), brief).valid());
    assertFalse(validator.validate(graph("entry", "RBAC"), brief).valid());
    var node = graph("entry", "RBAC").nodes().getFirst();
    var properties = new java.util.ArrayList<>(node.properties());
    properties.add(new PlanProperty("roles", "[\"test-role\"]"));
    assertTrue(validator.validate(new ChainPlanGraph("1.0", new ChainSection("Policy", ""),
        List.of(new ChainPlanNode("entry", "http-trigger", "Entry", null, null, properties)), List.of()), brief).valid());
    var keepNone = new RequirementBrief("Policy test", List.of(), List.of(), List.of(), List.of(), "",
        "approved", "", RequirementCaptureProjection.facts(draft(Decision.KEEP_REQUESTED)));
    assertFalse(validator.validate(new ChainPlanGraph("1.0", new ChainSection("Policy", ""),
        List.of(new ChainPlanNode("entry", "http-trigger", "Entry", null, null, properties)), List.of()), keepNone).valid());
  }

  private static DraftInput draft(Decision decision) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "GET /policy-test", "External endpoint", null, null)), List.of()),
        List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Verify policy consultation."),
            policyFact(decision)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET,
            "/policy-test", null, null, null)),
        decision == null ? List.of(new QuestionInput("policy-choice", List.of("entry"),
            "NONE has no access control. Corporate section 12.4 recommends RBAC for external routes. Keep NONE or use RBAC?")) : List.of(),
        new DraftSettings(false, null));
  }

  private static FactInput policyFact(Decision decision) {
    return new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "External access control: requested NONE; corporate section 12.4 recommends RBAC.", null,
        new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4", "accessControlType", "NONE", "RBAC",
            "policy-choice", decision));
  }

  private static DraftUpdate resolve(Decision decision) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(policyFact(decision)), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of("policy-choice"), null);
  }

  private static ChainPlanGraph graph(String id, String accessControl) {
    return new ChainPlanGraph("1.0", new ChainSection("Policy", ""),
        List.of(new ChainPlanNode(id, "http-trigger", "Entry", null, null,
            List.of(new PlanProperty("externalRoute", "true"), new PlanProperty("accessControlType", accessControl)))),
        List.of());
  }
}
