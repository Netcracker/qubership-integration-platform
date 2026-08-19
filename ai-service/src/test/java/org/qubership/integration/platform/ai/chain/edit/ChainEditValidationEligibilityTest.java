package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerValidationPipeline;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.MaterializationRequirementsValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.QipSchemaYamlParser;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;

class ChainEditValidationEligibilityTest {

  private CompilerValidationPipeline pipeline;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper();
    DeterministicElementSchemaService schemaService =
        DeterministicElementSchemaService.createForUnitTests(mapper);
    SchemaResourceLoader schemaResourceLoader = new SchemaResourceLoader();
    SchemaRefResolver schemaRefResolver =
        new SchemaRefResolver(schemaResourceLoader, new QipSchemaYamlParser());
    MaterializationRequirementsValidator requirements =
        mock(MaterializationRequirementsValidator.class);
    when(requirements.validate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    pipeline =
        new CompilerValidationPipeline(
            schemaResourceLoader,
            schemaRefResolver,
            mapper,
            new ChainPlanGraphValidator(schemaService),
            schemaService,
            new CompilerSecurityValidator(),
            new CompilerQualityValidator(requirements));
  }

  @Test
  void catalogDefaultExternalRouteDoesNotBlockAnEndpointEdit() {
    ChainPlanGraph seed = liveCatalogGraph("test", "GET");
    ChainPlanGraph compiled = liveCatalogGraph("/test-test", "POST");
    CompilerValidationBundle bundle = pipeline.validate("digest", null, compiled);

    assertFalse(bundle.approvalEligible());
    assertTrue(ChainEditValidationEligibility.approvalEligible(seed, bundle, pipeline));
  }

  @Test
  void aNewElementBlockerStillRefusesApproval() {
    ChainPlanGraph seed = liveCatalogGraph("test", "GET");
    CompilerValidationBundle bundle =
        new CompilerValidationBundle(
            1,
            "digest",
            List.of(
                new CompilerValidationPass(
                    CompilerValidationPipeline.ELEMENT,
                    new ValidationResult(
                        false,
                        List.of(
                            new ValidationIssue(
                                "element-1",
                                ValidationSeverity.BLOCKER,
                                "Element properties violate schema for 'http-trigger'",
                                CompilerValidationPipeline.ELEMENT,
                                List.of("99cc8d01-8c81-4551-9155-33577535b8ab"),
                                List.of(),
                                "Fix node properties according to schema")),
                        "element validation failed with 1 blocker(s)"))));

    assertFalse(ChainEditValidationEligibility.approvalEligible(seed, bundle, pipeline));
    assertTrue(
        ChainEditValidationEligibility.failureMessage(seed, bundle, pipeline)
            .contains("Element properties violate schema"));
  }

  @Test
  void aNullBundleIsNotEligible() {
    assertFalse(
        ChainEditValidationEligibility.approvalEligible(liveCatalogGraph("test", "GET"), null, pipeline));
  }

  private static ChainPlanGraph liveCatalogGraph(String contextPath, String method) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("11715aba-751c-4e9b-95f1-24667b1b5d47", "Test chain 1"),
        List.of(
            new ChainPlanNode(
                "99cc8d01-8c81-4551-9155-33577535b8ab",
                "http-trigger",
                "HTTP Trigger",
                null,
                null,
                List.of(
                    new PlanProperty("accessControlType", "NONE"),
                    new PlanProperty("httpMethodRestrict", method),
                    new PlanProperty("contextPath", contextPath),
                    new PlanProperty("externalRoute", "true"))),
            new ChainPlanNode(
                "b978e8ff-c89e-462b-b512-25d46dae09e5",
                "service-call",
                "Service Call",
                null,
                null,
                List.of(
                    new PlanProperty(
                        "integrationOperationId",
                        "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger-1.0.7-getInventory"),
                    new PlanProperty("integrationOperationPath", "/store/inventory"),
                    new PlanProperty("systemType", "EXTERNAL"))),
            new ChainPlanNode(
                "cc0b0f34-e311-46f7-8a2e-073e1cdae353",
                "script",
                "Script",
                null,
                null,
                List.of(
                    new PlanProperty("propertiesToExportInSeparateFile", "script"),
                    new PlanProperty("exportFileExtension", "groovy")))),
        List.of(
            new ChainPlanEdge(
                "e1",
                "99cc8d01-8c81-4551-9155-33577535b8ab",
                "b978e8ff-c89e-462b-b512-25d46dae09e5",
                null),
            new ChainPlanEdge(
                "e2",
                "b978e8ff-c89e-462b-b512-25d46dae09e5",
                "cc0b0f34-e311-46f7-8a2e-073e1cdae353",
                null)));
  }
}
