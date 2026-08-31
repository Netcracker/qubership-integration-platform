package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.qipknowledge.validation.MaterializationRequirementsValidator;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.QipSchemaYamlParser;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class CompilerValidationPipelineTest {

  @Test
  void bundleContainsEveryCompilerValidationPass() {
    CompilerValidationPipeline pipeline =
        new CompilerValidationPipeline(
            graph -> valid("elements"),
            graph -> valid("structure"),
            graph -> valid("configuration"),
            security(valid("security")),
            quality(valid("quality")));

    CompilerValidationBundle bundle =
        pipeline.validate("digest-1", new NamingManifest(1, "Sales.Inbound.Create", java.util.Map.of(), List.of(), List.of()), graph());

    assertEquals("digest-1", bundle.graphDigest());
    assertEquals(
        Set.of(
            "cip-element-validator",
            "cip-structural-validator",
            "cip-configuration-validator",
            "cip-security-validator",
            "cip-quality-validator"),
        bundle.passes().stream().map(pass -> pass.validatorSkillId()).collect(java.util.stream.Collectors.toSet()));
    assertTrue(bundle.approvalEligible());
  }

  @Test
  void bundleFailsWhenAnyPassHasBlocker() {
    CompilerValidationPipeline pipeline =
        new CompilerValidationPipeline(
            graph -> valid("elements"),
            graph ->
                new ValidationResult(
                    false,
                    List.of(
                        new ValidationIssue(
                            "validation-1",
                            ValidationSeverity.BLOCKER,
                            "broken structure",
                            "cip-structural-validator",
                            List.of(),
                            List.of(),
                            "fix")),
                    "blocked"),
            graph -> valid("configuration"),
            security(valid("security")),
            quality(valid("quality")));

    CompilerValidationBundle bundle =
        pipeline.validate("digest-1", new NamingManifest(1, "Sales.Inbound.Create", java.util.Map.of(), List.of(), List.of()), graph());

    assertFalse(bundle.approvalEligible());
    assertTrue(
        bundle.passes().stream()
            .anyMatch(pass -> "cip-structural-validator".equals(pass.validatorSkillId()) && !pass.result().valid()));
  }

  @Test
  void elementValidatorAcceptsServiceCallAfterRetryDefaults() {
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
            List.of(
                new ChainPlanNode(
                    "call-1",
                    "service-call",
                    "Get inventory",
                    null,
                    null,
                    List.of(
                        new PlanProperty("systemType", "EXTERNAL"),
                        new PlanProperty("integrationOperationProtocolType", "http"),
                        new PlanProperty("integrationSystemId", "sys-1"),
                        new PlanProperty("integrationSpecificationGroupId", "grp-1"),
                        new PlanProperty("integrationSpecificationId", "spec-1"),
                        new PlanProperty("integrationOperationId", "op-1"),
                        new PlanProperty("integrationOperationMethod", "GET"),
                        new PlanProperty("integrationOperationPath", "/store/inventory")))),
            List.of());

    ValidationResult elementResult =
        pipeline.validatePass("cip-element-validator", null, graph);

    assertTrue(elementResult.valid(), elementResult.summary());
  }

  @Test
  void elementValidatorAcceptsScriptWithCompilerMappingMetadata() {
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
            new ChainSection("c1", "MapRequest"),
            List.of(
                new ChainPlanNode(
                    "script-1",
                    "script",
                    "Map request",
                    null,
                    null,
                    List.of(
                        new PlanProperty("script", "return body"),
                        new PlanProperty(
                            MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY,
                            "request-onTaskStart-to-createTask"),
                        new PlanProperty(
                            MappingExecutionSite.SEMANTIC_EDGE_ID_PROPERTY, "edge-1"),
                        new PlanProperty(MappingExecutionSite.MAPPING_ID_PROPERTY, "map-1"),
                        new PlanProperty(
                            MappingExecutionSite.MAPPING_COVERAGE_PROPERTY,
                            "[\"Subject\",\"Description\"]")))),
            List.of());

    ValidationResult elementResult =
        pipeline.validatePass("cip-element-validator", null, graph);

    assertTrue(elementResult.valid(), elementResult.summary());
  }

  @Test
  void catalogDefaultHttpTriggerFailsSecurityAfterEndpointPatch() {
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

    CompilerValidationBundle bundle =
        pipeline.validate("digest-live", null, liveCatalogEditGraph());

    java.util.Map<String, Boolean> passValid =
        bundle.passes().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    pass -> pass.validatorSkillId(), pass -> pass.result().valid()));

    assertFalse(bundle.approvalEligible());
    assertFalse(passValid.get("cip-security-validator"));
    assertTrue(passValid.get("cip-element-validator"));
    assertTrue(passValid.get("cip-structural-validator"));
    assertTrue(passValid.get("cip-configuration-validator"));
    assertTrue(passValid.get("cip-quality-validator"));
    assertTrue(
        bundle.passes().stream()
            .filter(pass -> "cip-security-validator".equals(pass.validatorSkillId()))
            .flatMap(pass -> pass.result().issues().stream())
            .anyMatch(
                issue ->
                    issue.severity() == ValidationSeverity.BLOCKER
                        && issue.message().contains("External route requires accessControlType=RBAC")));
  }

  private static ChainPlanGraph liveCatalogEditGraph() {
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
                    new PlanProperty(
                        "idempotency",
                        "{\"keyExpiry\":600,\"actionOnDuplicate\":\"ignore\",\"enabled\":false}"),
                    new PlanProperty("handleChainFailureAction", "default"),
                    new PlanProperty("accessControlType", "NONE"),
                    new PlanProperty("httpMethodRestrict", "POST"),
                    new PlanProperty("httpBinding", "handlingHttpBinding"),
                    new PlanProperty("rejectRequestIfNonNullBodyGetDelete", "true"),
                    new PlanProperty("contextPath", "/test-test"),
                    new PlanProperty("chunked", "true"),
                    new PlanProperty("receiveCorrelationId", "false"),
                    new PlanProperty("externalRoute", "true"),
                    new PlanProperty("privateRoute", "false"),
                    new PlanProperty("connectTimeout", "120000"),
                    new PlanProperty("handleValidationAction", "default"),
                    new PlanProperty("killSessionOnTimeout", "false"))),
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
                    new PlanProperty("before", "{\"type\":\"none\"}"),
                    new PlanProperty("integrationOperationPath", "/store/inventory"),
                    new PlanProperty("retryCount", "0"),
                    new PlanProperty(
                        "integrationSystemId", "bbf14771-de8d-48e8-a2ed-2e691f7f6eff"),
                    new PlanProperty("receiveCorrelationId", "false"),
                    new PlanProperty(
                        "integrationSpecificationGroupId",
                        "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger"),
                    new PlanProperty("retryDelay", "5000"),
                    new PlanProperty("authorizationConfiguration", "{\"type\":\"inherit\"}"),
                    new PlanProperty("propagateContext", "true"),
                    new PlanProperty("systemType", "EXTERNAL"),
                    new PlanProperty("errorThrowing", "true"),
                    new PlanProperty("integrationOperationMethod", "GET"),
                    new PlanProperty("handleValidationAction", "default"),
                    new PlanProperty("integrationOperationProtocolType", "http"),
                    new PlanProperty(
                        "integrationSpecificationId",
                        "bbf14771-de8d-48e8-a2ed-2e691f7f6eff-swagger-1.0.7"),
                    new PlanProperty("integrationOperationSkipEmptyQueryParameters", "false"))),
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
                "99cc8d01-8c81-4551-9155-33577535b8ab->b978e8ff-c89e-462b-b512-25d46dae09e5",
                "99cc8d01-8c81-4551-9155-33577535b8ab",
                "b978e8ff-c89e-462b-b512-25d46dae09e5",
                null),
            new ChainPlanEdge(
                "b978e8ff-c89e-462b-b512-25d46dae09e5->cc0b0f34-e311-46f7-8a2e-073e1cdae353",
                "b978e8ff-c89e-462b-b512-25d46dae09e5",
                "cc0b0f34-e311-46f7-8a2e-073e1cdae353",
                null)));
  }

  private static ValidationResult valid(String summary) {
    return new ValidationResult(true, List.of(), summary);
  }

  private static CompilerSecurityValidator security(ValidationResult result) {
    CompilerSecurityValidator validator = mock(CompilerSecurityValidator.class);
    when(validator.validate(org.mockito.ArgumentMatchers.any())).thenReturn(result);
    return validator;
  }

  private static CompilerQualityValidator quality(ValidationResult result) {
    CompilerQualityValidator validator = mock(CompilerQualityValidator.class);
    when(validator.validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(result);
    return validator;
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
                    new PlanProperty("httpMethodRestrict", "POST"),
                    new PlanProperty("externalRoute", "false"))),
            new ChainPlanNode(
                "script",
                "script",
                "MapPayload",
                null,
                null,
                List.of(new PlanProperty("script", "return body")))),
        List.of());
  }
}
