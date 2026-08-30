package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerPlanningRunner;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerValidationPipeline;
import org.qubership.integration.platform.ai.qipknowledge.validation.MaterializationRequirementsValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.ElementPatchDefaultsApplicator;
import org.qubership.integration.platform.ai.schema.ElementPatchValidationMessages;
import org.qubership.integration.platform.ai.schema.ElementPatchValidator;
import org.qubership.integration.platform.ai.schema.ElementPropertiesSchemaModelBuilder;
import org.qubership.integration.platform.ai.schema.QipSchemaYamlParser;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;

class RecoveryEvidenceFactoryTest {

  @Test
  void fromElementValidationKeepsValidatorJsonAndStructuredKeys() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    SchemaResourceLoader loader = new SchemaResourceLoader();
    SchemaRefResolver resolver = new SchemaRefResolver(loader, new QipSchemaYamlParser());
    ChainPlanNode node = failingServiceCallNode();
    String validationJson = liveValidationJson(node, mapper, resolver);

    SemanticFinding finding =
        RecoveryEvidenceFactory.fromElementValidation(
            node, validationJson, "design-execution", mapper, resolver);

    assertTrue(
        !finding.missingKeys().isEmpty() || !finding.oneOfBranchHints().isEmpty(),
        "expected structured missing keys or oneOf hints");
    assertTrue(finding.rawValidatorJson().contains("\"valid\""));
    assertTrue(
        finding.rawValidatorJson().contains("\"errors\"")
            || finding.rawValidatorJson().contains("\"missingRequired\""));
    assertNotEquals(
        "Element properties violate schema for 'service-call'", finding.rawValidatorJson());
    assertFalse(finding.rawValidatorJson().contains("Element properties violate schema"));
    assertEquals("call-1", finding.nodeId());
    assertEquals("service-call", finding.elementType());
    assertTrue(finding.presentKeys().contains("integrationOperationId"));
  }

  @Test
  void elementValidatorBlockerCarriesCompactSummaryNotGenericSentence() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    DeterministicElementSchemaService schemaService =
        DeterministicElementSchemaService.createForUnitTests(mapper);
    SchemaResourceLoader schemaResourceLoader = new SchemaResourceLoader();
    SchemaRefResolver schemaRefResolver =
        new SchemaRefResolver(schemaResourceLoader, new QipSchemaYamlParser());
    MaterializationRequirementsValidator requirements =
        mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    CompilerValidationPipeline pipeline =
        new CompilerValidationPipeline(
            schemaResourceLoader,
            schemaRefResolver,
            mapper,
            new ChainPlanGraphValidator(schemaService),
            schemaService,
            new CompilerSecurityValidator(),
            new CompilerQualityValidator(requirements));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c1", "HealthProxy"),
            List.of(failingServiceCallNode()),
            List.of());

    ValidationResult elementResult =
        pipeline.validatePass("cip-element-validator", null, graph);

    assertFalse(elementResult.valid());
    ValidationIssue blocker =
        elementResult.issues().stream()
            .filter(issue -> issue.severity() == ValidationSeverity.BLOCKER)
            .findFirst()
            .orElseThrow();
    assertNotEquals(
        "Element properties violate schema for 'service-call'", blocker.message());
    assertTrue(
        blocker.message().contains("missingProperties=")
            || blocker.message().contains("missingRequired=")
            || blocker.message().contains("alternative "));

    String expectedSummary =
        ElementPatchValidationMessages.summarizeFailure(
            liveValidationJson(failingServiceCallNode(), mapper, schemaRefResolver), mapper);
    assertEquals(expectedSummary, blocker.message());

    var planValidation =
        CompilerPlanningRunner.buildValidationResult(elementResult, List.of());
    assertFalse(planValidation.findings().isEmpty());
    assertTrue(
        planValidation.findings().get(0).message().contains(blocker.message()));
    assertNotEquals(
        "Element properties violate schema for 'service-call'",
        planValidation.findings().get(0).message());
  }

  private static ChainPlanNode failingServiceCallNode() {
    return new ChainPlanNode(
        "call-1",
        "service-call",
        "Get inventory",
        null,
        null,
        List.of(
            new PlanProperty("integrationOperationProtocolType", "http"),
            new PlanProperty("integrationSystemId", "sys-1"),
            new PlanProperty("integrationSpecificationGroupId", "grp-1"),
            new PlanProperty("integrationSpecificationId", "spec-1"),
            new PlanProperty("integrationOperationId", "op-1"),
            new PlanProperty("integrationOperationMethod", "GET"),
            new PlanProperty("integrationOperationPath", "/store/inventory")));
  }

  private static String liveValidationJson(
      ChainPlanNode node, ObjectMapper mapper, SchemaRefResolver resolver) throws Exception {
    var model = ElementPropertiesSchemaModelBuilder.build(node.type(), resolver);
    var props = mapper.createObjectNode();
    for (PlanProperty property : node.properties()) {
      props.set(property.key(), mapper.valueToTree(property.value()));
    }
    var root = mapper.createObjectNode();
    root.set("properties", props);
    ElementPatchDefaultsApplicator.applyMissingPropertyDefaults(root, model, resolver, mapper, null);
    var result =
        ElementPatchValidator.validate(mapper.writeValueAsString(root), model, resolver, mapper);
    return result.toString();
  }
}
