package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;

class CompilerQualityValidatorTest {
  @Test
  void anAssignmentShapedResponseRemainsAnExactExecutableLiteral() {
    var brief = requiredScriptLiteral("a=b");
    assertTrue(CompilerQualityValidator.validateApprovedValues(
        graph().withNodeProperty("script", "script", "exchange.in.body = 'a=b'"), brief).isEmpty());
    for (String body : List.of("exchange.in.body = 'a=b changed'", "// a=b\nexchange.in.body = 'different'")) {
      assertTrue(CompilerQualityValidator.validateApprovedValues(
          graph().withNodeProperty("script", "script", body), brief).stream()
          .anyMatch(issue -> "MISSING_REQUIRED_SCRIPT_LITERAL".equals(issue.findingCode())), body);
    }
  }

  @Test
  void composedOutputKeepsItsApprovedConstantSeparateFromRuntimeValues() {
    var brief = requiredScriptLiteral("Record");
    assertTrue(CompilerQualityValidator.validateApprovedValues(graph().withNodeProperty("script", "script",
        "exchange.in.body = 'Record' + ' ' + exchange.getProperty('recordId')"), brief).isEmpty());
    for (String body : List.of("exchange.in.body = 'Record changed'",
        "exchange.in.body = \"Record ${exchange.getProperty('recordId')}\"")) {
      assertTrue(CompilerQualityValidator.validateApprovedValues(
          graph().withNodeProperty("script", "script", body), brief).stream()
          .anyMatch(issue -> "MISSING_REQUIRED_SCRIPT_LITERAL".equals(issue.findingCode())), body);
    }
  }

  @Test
  void requiredWhitespaceIsPartOfTheExecutableConstant() {
    var brief = requiredScriptLiteral("Ready ");
    assertTrue(CompilerQualityValidator.validateApprovedValues(
        graph().withNodeProperty("script", "script", "exchange.in.body = 'Ready '"), brief).isEmpty());
    assertTrue(CompilerQualityValidator.validateApprovedValues(
        graph().withNodeProperty("script", "script", "exchange.in.body = 'Ready'"), brief).stream()
        .anyMatch(issue -> "MISSING_REQUIRED_SCRIPT_LITERAL".equals(issue.findingCode())));
  }

  private static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief requiredScriptLiteral(
      String literal) {
    var intent = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("script", "script",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE,
        "trigger", null, null, null, List.of(), List.of(literal));
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("response",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.BEHAVIOR, "", "Produce the requested value.",
        "", "", "", "", "", "", List.of("trigger"), null, intent);
    return new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief("goal", List.of(),
        List.of(), List.of(), List.of(), "").withFacts(List.of(fact));
  }

  @Test
  void actualQuoteCharactersRemainRequiredByTheNativeScriptValidator() {
    var intent = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("script", "script",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE,
        "trigger", null, null, null, List.of(), List.of("\"quoted-content\""));
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("quoted-response",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.BEHAVIOR, "", "Include the quotation marks.",
        "", "", "", "", "", "", List.of("trigger"), null, intent);
    var brief = new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief("goal", List.of(),
        List.of(), List.of(), List.of(), "").withFacts(List.of(fact));
    assertFalse(CompilerQualityValidator.validateApprovedValues(
        graph().withNodeProperty("script", "script", "exchange.in.body = 'quoted-content'"), brief).isEmpty());
    assertTrue(CompilerQualityValidator.validateApprovedValues(
        graph().withNodeProperty("script", "script", "exchange.in.body = '\"quoted-content\"'"), brief).isEmpty());
  }

  @Test
  void anOwnedScriptLiteralIsCheckedAfterGenerationInsteadOfReopeningRequirements() {
    var intent = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("script", "script",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE,
        "trigger", null, null, null, List.of(), List.of("accepted-body"));
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("reply",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.BEHAVIOR, "", "Respond with accepted-body.",
        "", "", "", "", "", "", List.of("trigger"), null, intent);
    var brief = new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief("goal", List.of(),
        List.of(), List.of(), List.of(), "summary", "ref", "source", List.of(fact), List.of());
    for (String script : List.of("exchange.in.body = 'different'", "// accepted-body\nexchange.in.body = 'different'")) {
      var candidate = graph().withNodeProperty("script", "script", script);
      var nodes = new java.util.ArrayList<>(candidate.nodes());
      nodes.add(new ChainPlanNode("unrelated", "script", "accepted-body", null, null,
          List.of(new PlanProperty("script", "exchange.in.body = 'accepted-body'"))));
      candidate = new ChainPlanGraph(candidate.schemaVersion(), candidate.chain(), nodes, candidate.edges());
      var findings = CompilerQualityValidator.validateApprovedValues(candidate, brief);
      assertTrue(findings.stream().anyMatch(issue -> "MISSING_REQUIRED_SCRIPT_LITERAL".equals(issue.findingCode())
          && "/elementIntent/exactValues".equals(issue.fieldPointer())
          && "reply".equals(issue.originRecordId())), findings.toString());
    }
    assertTrue(CompilerQualityValidator.validateApprovedValues(
        graph().withNodeProperty("script", "script", "exchange.in.body = 'accepted-body'"), brief).isEmpty());
    assertTrue(org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause.fromFindingCode(
        "MISSING_REQUIRED_SCRIPT_LITERAL")
        == org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode.MISSING_REQUIRED_PROPERTY);
  }


  @Test
  void approvedInlineHooksCannotBeOmittedOrReplacedByStandaloneScripts() {
    var requirements = mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    var validator = new CompilerQualityValidator(requirements);
    for (var placement : List.of(
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.BEFORE_HOOK,
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.AFTER_HOOK)) {
      String key = placement.name().startsWith("BEFORE") ? "before" : "after";
      var intent = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("call-hook", "script",
          placement, "call", null, null, null, List.of(), List.of());
      var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("hook",
          org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
          org.qubership.integration.platform.ai.plan.RequirementFactKind.BEHAVIOR, "", "Run the inline script.",
          "", "", "", "", "", "", List.of("call"), null, intent);
      var brief = new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief("goal", List.of(),
          List.of(), List.of(), List.of(), "summary", "ref", "source", List.of(fact), List.of());
      var nodes = new java.util.ArrayList<>(graph().nodes());
      nodes.add(new ChainPlanNode("call", "service-call", "Call", null, null, List.of()));
      var candidate = new ChainPlanGraph("1.0", graph().chain(), nodes, List.of());
      for (String value : List.of("null", "{}", "[]", "{\"type\":\"none\"}",
          "{\"type\":\"script\",\"script\":\"\"}", "invalid")) {
        var result = validator.validate(null, candidate.withNodeProperty("call", key, value), brief);
        assertFalse(result.valid(), key + "=" + value);
        assertTrue(result.issues().stream().anyMatch(issue ->
            "cip-service-call-generator".equals(issue.ownerCapabilityId())
                && ("/properties/" + key + ".script").equals(issue.fieldPointer())));
      }
      String hook = "{\"type\":\"script\",\"script\":\"exchange.message.body = '{}'\"}";
      var complete = candidate.withNodeProperty("call", key, "after".equals(key) ? "[" + hook + "]" : hook);
      assertTrue(validator.validate(null, complete, brief).valid());
    }
  }

  @Test
  void approvedNamesCannotBeParaphrasedInTheAssembledGraph() {
    var requirements = mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    var intent = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("script", "script",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.LOCAL_NODE,
        "trigger", null, null, "Set the exact response", List.of(), List.of());
    var fact = new org.qubership.integration.platform.ai.plan.RequirementFact("script-name",
        org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.PARAMETER, "", "Requested name", "", "", "", "", "", "",
        List.of("trigger"), null, intent);
    var brief = new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief("goal", List.of(),
        List.of(), List.of(), List.of(), "summary", "ref", "source", List.of(fact), List.of());
    var validator = new CompilerQualityValidator(requirements);
    assertFalse(validator.validate(null, graph(), brief).valid());
    assertTrue(validator.validate(null, RequestedElementValues.apply(graph(), brief.facts()), brief).valid());
  }

  @Test
  void namingViolationsAreWarnings() {
    MaterializationRequirementsValidator requirements = mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    CompilerQualityValidator validator = new CompilerQualityValidator(requirements);

    ValidationResult result =
        validator.validate(
            new NamingManifest(1, "123Invalid", Map.of("role-1", "TICKET-123"), List.of(), List.of()),
            graph());

    assertTrue(result.valid());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.severity() == ValidationSeverity.WARNING));
  }

  @Test
  void missingMaterializationPropertiesAreBlockers() {
    MaterializationRequirementsValidator requirements = mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new ValidationIssue(
                    "validation-1",
                    ValidationSeverity.BLOCKER,
                    "missing required property",
                    "cip-structure-generator",
                    List.of("script"),
                    List.of(),
                    "fix", "MISSING_REQUIRED_PROPERTY", "script", "/properties/script")));
    CompilerQualityValidator validator = new CompilerQualityValidator(requirements);

    ValidationResult result =
        validator.validate(
            new NamingManifest(1, "Sales.Inbound.Create", Map.of("role-1", "Script"), List.of(), List.of()),
            graph());

    assertFalse(result.valid());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER));
    assertTrue(result.issues().stream().anyMatch(issue ->
        "MISSING_REQUIRED_PROPERTY".equals(issue.findingCode())
            && "script".equals(issue.originRecordId()) && "/properties/script".equals(issue.fieldPointer())));
  }

  @Test
  void matchingNamingManifestPasses() {
    MaterializationRequirementsValidator requirements = mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    CompilerQualityValidator validator = new CompilerQualityValidator(requirements);

    ValidationResult result =
        validator.validate(
            new NamingManifest(1, "Sales.Inbound.Create", Map.of("role-1", "ScriptNode"), List.of(), List.of()),
            graph());

    assertTrue(result.valid());
    assertTrue(result.issues().isEmpty());
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("sales", "Sales"),
        List.of(
            new ChainPlanNode(
                "trigger",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(
                    new PlanProperty("contextPath", "/sales"),
                    new PlanProperty("httpMethodRestrict", "POST"))),
            new ChainPlanNode(
                "script",
                "script",
                "ScriptNode",
                null,
                null,
                List.of(new PlanProperty("scriptBody", "noop")))),
        List.of());
  }
}
