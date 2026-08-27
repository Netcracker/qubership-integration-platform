package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class DesignPlanProjectorTest {

  private final DesignPlanProjector projector = new DesignPlanProjector();

  @Test
  void projectsCatalogDerivedDependenciesInReportOrder() {
    String reportMarkdown = validReport();
    DesignPlanReport report = new DesignPlanReport("1", reportMarkdown);
    NormalizedDesignFlow flow = sampleFlow();
    ResolvedCompilerDag dag = sampleDag();

    DesignExecutionPlan projected =
        projector.project(
            report,
            flow,
            dag,
            "catalog-hash",
            Map.of("cip-design-planner", "skill-hash"),
            Map.of("cip-design-planner", "addon-hash"));

    List<List<String>> expectedCatalogDependencies =
        List.of(
            List.of(),
            List.of(),
            List.of("step-2-search_rest_api_operations"),
            List.of("step-3-get_rest_api_operations_specification"),
            List.of(),
            List.of("step-5-cip-trigger-generator"),
            List.of("step-6-cip-service-call-generator"),
            List.of("step-6-cip-service-call-generator"),
            List.of(
                "step-5-cip-trigger-generator",
                "step-6-cip-service-call-generator",
                "step-7-cip-script-generator",
                "step-8-cip-script-generator"),
            List.of("step-9-cip-structure-generator"),
            List.of("step-9-cip-structure-generator"),
            List.of("step-11-cip-chain-assembler"));

    assertEquals(expectedCatalogDependencies.size(), projected.steps().size());
    for (int i = 0; i < expectedCatalogDependencies.size(); i++) {
      assertEquals(
          expectedCatalogDependencies.get(i),
          projected.steps().get(i).dependsOn(),
          "dependsOn mismatch at step " + (i + 1));
    }
    assertEquals(
        "Analyze requirements and name chain Orders"
            + " (cip-requirement-analyzer + cip-naming-generator)",
        projected.steps().getFirst().reportText());
    assertEquals("2024.4", projected.apiRelease());
    assertEquals("CATALOG_FIRST_V1", projected.bindingResolutionPolicy());
  }

  @Test
  void passThroughMappingsDoNotRequireScriptSteps() {
    String reportWithoutScripts =
        """
        1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
        2. Find API Orders API for Orders Service in APIHub for version 2024.4 (APIHub MCP search_rest_api_operations)
        3. Get API operation specification Orders API for Orders Service in APIHub (APIHub MCP get_rest_api_operations_specification)
        4. Resolve External integration target Orders Service from the retrieved spec (binding for cip-service-call-generator)
        5. Generate HTTP Trigger element with interface Orders API (cip-trigger-generator)
        6. Generate Service Call element for Orders Service.createOrder bound to the retrieved spec (cip-service-call-generator)
        7. Generate execution structure and element ordering (cip-structure-generator)
        8. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
        9. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        10. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    DesignExecutionPlan projected =
        projector.project(
            new DesignPlanReport("1", reportWithoutScripts),
            sampleFlow(),
            sampleDag(),
            "catalog-hash",
            Map.of(),
            Map.of());

    assertEquals(10, projected.steps().size());
  }

  @Test
  void emptyMappingsDoNotRequireInitializationConvertOrResponseScripts() {
    String reportWithoutStageScripts =
        """
        1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
        2. Generate HTTP Trigger element with interface Orders API (cip-trigger-generator)
        3. Generate Service Call element for Orders Service.createOrder (cip-service-call-generator)
        4. Generate execution structure and element ordering (cip-structure-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    NormalizedDesignFlow base = sampleFlow();
    NormalizedDesignFlow emptyMappings =
        new NormalizedDesignFlow(
            base.schemaVersion(),
            base.flowId(),
            base.chainName(),
            base.description(),
            base.trigger(),
            base.participants(),
            base.steps(),
            base.connections(),
            base.transformations(),
            List.of(),
            base.constraints(),
            base.assumptions());

    DesignExecutionPlan projected =
        projector.project(
            new DesignPlanReport("1", reportWithoutStageScripts),
            emptyMappings,
            sampleDag(),
            "catalog-hash",
            Map.of(),
            Map.of());

    assertEquals(6, projected.steps().size());
  }

  @Test
  void rejectsApiHubStepsForCatalogOnlyFlow() {
    NormalizedDesignFlow base = sampleFlow();
    NormalizedDesignFlow catalogOnly =
        new NormalizedDesignFlow(
            base.schemaVersion(),
            base.flowId(),
            base.chainName(),
            base.description(),
            base.trigger(),
            base.participants(),
            base.steps(),
            base.connections(),
            base.transformations(),
            base.dataMappings(),
            base.constraints(),
            base.assumptions(),
            NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_ONLY);

    PlannerReportFormatException ex =
        assertThrows(
            PlannerReportFormatException.class,
            () ->
                projector.project(
                    new DesignPlanReport("1", validReport()),
                    catalogOnly,
                    sampleDag(),
                    "catalog-hash",
                    Map.of(),
                    Map.of()));

    assertTrue(ex.getMessage().contains("CATALOG_ONLY forbids APIHub planner steps"));
  }

  @Test
  void rejectsUnknownOwningSkill() {
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Mystery element (cip-unknown-generator)
        3. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        4. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    PlannerContractException ex =
        assertThrows(
            PlannerContractException.class,
            () ->
                projector.project(
                    new DesignPlanReport("1", report),
                    sampleFlow(),
                    sampleDag(),
                    "catalog-hash",
                    Map.of(),
                    Map.of()));
    assertTrue(ex.getMessage().contains("unknown skill"));
  }

  @Test
  void rewritesChainValidatorOntoPinnedValidationProducers() {
    ResolvedCompilerDag dagWithoutChainValidator =
        new ResolvedCompilerDag(
            List.of(
                node(
                    "cip-requirement-analyzer",
                    List.of(SkillArtifactType.RAW_USER_REQUEST.name()),
                    List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                    List.of(),
                    0),
                node(
                    "cip-naming-generator",
                    List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                    List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                    List.of("cip-requirement-analyzer"),
                    1),
                node(
                    "cip-trigger-generator",
                    List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of(),
                    2),
                node(
                    "cip-service-call-generator",
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of("cip-trigger-generator"),
                    3),
                node(
                    "cip-script-generator",
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of("cip-service-call-generator"),
                    4),
                node(
                    "cip-structure-generator",
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                    List.of(
                        "cip-trigger-generator",
                        "cip-service-call-generator",
                        "cip-script-generator"),
                    5),
                node(
                    "cip-chain-assembler",
                    List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                    List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                    List.of("cip-structure-generator"),
                    6),
                validationNode("cip-configuration-validator", 7),
                validationNode("cip-element-validator", 8),
                validationNode("cip-quality-validator", 9),
                validationNode("cip-security-validator", 10),
                validationNode("cip-structural-validator", 11)),
            List.of(),
            "dag-without-chain-validator");

    DesignExecutionPlan projected =
        projector.project(
            new DesignPlanReport("1", validReport()),
            sampleFlow(),
            dagWithoutChainValidator,
            "catalog-hash",
            Map.of(),
            Map.of());

    DesignExecutionPlan.Step validateStep = projected.steps().getLast();
    assertEquals(
        List.of(
            "cip-configuration-validator",
            "cip-element-validator",
            "cip-quality-validator",
            "cip-security-validator",
            "cip-structural-validator"),
        validateStep.owningSkillIds());
    assertTrue(
        validateStep
            .producedArtifactTypes()
            .contains(SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name()));
    assertEquals(List.of("step-11-cip-chain-assembler"), validateStep.dependsOn());
  }

  @Test
  void stillRejectsChainValidatorWhenNoPinnedValidationProducers() {
    ResolvedCompilerDag dagWithoutValidators =
        new ResolvedCompilerDag(
            List.of(
                node(
                    "cip-trigger-generator",
                    List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of(),
                    0),
                node(
                    "cip-service-call-generator",
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of("cip-trigger-generator"),
                    1),
                node(
                    "cip-script-generator",
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of("cip-service-call-generator"),
                    2),
                node(
                    "cip-chain-assembler",
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                    List.of("cip-service-call-generator"),
                    3)),
            List.of(),
            "dag-no-validators");

    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element for Orders Service.createOrder (cip-service-call-generator)
        3. Generate Script element for Initialization (cip-script-generator)
        4. Generate Script element for Response (cip-script-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    PlannerContractException ex =
        assertThrows(
            PlannerContractException.class,
            () ->
                projector.project(
                    new DesignPlanReport("1", report),
                    sampleFlow(),
                    dagWithoutValidators,
                    "catalog-hash",
                    Map.of(),
                    Map.of()));
    assertTrue(ex.getMessage().contains("unknown skill"));
    assertTrue(ex.getMessage().contains("cip-chain-validator"));
  }

  @Test
  void rejectsMissingTriggerCoverage() {
    String report =
        """
        1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
        2. Generate Service Call element for Orders Service.createOrder (cip-service-call-generator)
        3. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        4. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    PlannerContractException ex =
        assertThrows(
            PlannerContractException.class,
            () ->
                projector.project(
                    new DesignPlanReport("1", report),
                    sampleFlow(),
                    sampleDag(),
                    "catalog-hash",
                    Map.of(),
                    Map.of()));
    assertTrue(ex.getMessage().contains("trigger"));
  }

  @Test
  void rejectsTriggerSubstringWithoutTriggerSkill() {
    String report =
        """
        1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
        2. Generate Service Call element for Orders Service.createOrder (cip-service-call-generator)
        3. Generate Script element for Initialization (cip-script-generator)
        4. Generate Script element for Response (cip-script-generator)
        5. Generate execution structure and element ordering (cip-structure-generator)
        6. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
        7. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        8. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    PlannerContractException ex =
        assertThrows(
            PlannerContractException.class,
            () ->
                projector.project(
                    new DesignPlanReport("1", report),
                    sampleFlow(),
                    sampleDag(),
                    "catalog-hash",
                    Map.of(),
                    Map.of()));
    assertTrue(ex.getMessage().contains("trigger coverage"));
    assertTrue(ex.getMessage().contains("cip-trigger-generator"));
  }

  @Test
  void rejectsMissingScriptMappingCoverage() {
    String report =
        """
        1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
        2. Generate HTTP Trigger element with interface Orders API (cip-trigger-generator)
        3. Generate Service Call element for Orders Service.createOrder (cip-service-call-generator)
        4. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        5. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    PlannerContractException ex =
        assertThrows(
            PlannerContractException.class,
            () ->
                projector.project(
                    new DesignPlanReport("1", report),
                    flowWithExplicitInitialization(),
                    sampleDag(),
                    "catalog-hash",
                    Map.of(),
                    Map.of()));
    assertTrue(ex.getMessage().contains("script"));
  }

  @Test
  void rejectsParticipantAbsentFromNormalizedFlow() {
    String report =
        """
        1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
        2. Find API Billing API for Billing Service in APIHub for version 2024.4 (APIHub MCP search_rest_api_operations)
        3. Get API operation specification Billing API for Billing Service in APIHub (APIHub MCP get_rest_api_operations_specification)
        4. Resolve External integration target Billing Service from the retrieved spec (binding for cip-service-call-generator)
        5. Generate HTTP Trigger element with interface Orders API (cip-trigger-generator)
        6. Generate Service Call element for Billing Service.createBill (cip-service-call-generator)
        7. Generate Script element for Initialization (cip-script-generator)
        8. Generate Script element for Response (cip-script-generator)
        9. Generate execution structure and element ordering (cip-structure-generator)
        10. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        11. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    PlannerContractException ex =
        assertThrows(
            PlannerContractException.class,
            () ->
                projector.project(
                    new DesignPlanReport("1", report),
                    sampleFlow(),
                    sampleDag(),
                    "catalog-hash",
                    Map.of(),
                    Map.of()));
    assertTrue(ex.getMessage().contains("participant"));
  }

  @Test
  void rejectsCatalogCycles() {
    ResolvedCompilerDag cyclic =
        new ResolvedCompilerDag(
            List.of(
                node(
                    "cip-trigger-generator",
                    List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of("cip-service-call-generator"),
                    0),
                node(
                    "cip-service-call-generator",
                    List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of("cip-trigger-generator"),
                    1),
                node(
                    "cip-script-generator",
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of("cip-service-call-generator"),
                    2),
                node(
                    "cip-chain-assembler",
                    List.of(SkillArtifactType.GRAPH_PATCH.name()),
                    List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                    List.of("cip-service-call-generator"),
                    3),
                node(
                    "cip-chain-validator",
                    List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                    List.of(SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name()),
                    List.of("cip-chain-assembler"),
                    4)),
            List.of(),
            "cyclic");

    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element for Orders Service.createOrder (cip-service-call-generator)
        3. Generate Script element for Initialization (cip-script-generator)
        4. Generate Script element for Response (cip-script-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    PlannerContractException ex =
        assertThrows(
            PlannerContractException.class,
            () ->
                projector.project(
                    new DesignPlanReport("1", report),
                    sampleFlow(),
                    cyclic,
                    "catalog-hash",
                    Map.of(),
                    Map.of()));
    assertTrue(ex.getMessage().contains("cycle"));
  }

  private static String validReport() {
    return """
        1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
        2. Find API Orders API for Orders Service in APIHub for version 2024.4 (APIHub MCP search_rest_api_operations)
        3. Get API operation specification Orders API for Orders Service in APIHub (APIHub MCP get_rest_api_operations_specification)
        4. Resolve External integration target Orders Service from the retrieved spec (binding for cip-service-call-generator)
        5. Generate HTTP Trigger element with interface Orders API (cip-trigger-generator)
        6. Generate Service Call element for Orders Service.createOrder bound to the retrieved spec (cip-service-call-generator)
        7. Generate Script element for Initialization (cip-script-generator)
        8. Generate Script element for Response (cip-script-generator)
        9. Generate execution structure and element ordering (cip-structure-generator)
        10. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
        11. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        12. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static NormalizedDesignFlow sampleFlow() {
    return new NormalizedDesignFlow(
        "1",
        "flow-1",
        "Orders",
        "Create order",
        new NormalizedDesignFlow.Trigger(
            "http",
            "p-client",
            "Orders API",
            "/orders",
            "createOrder",
            List.of("fact-trigger")),
        List.of(
            new NormalizedDesignFlow.Participant(
                "p-client", "Client", "EXTERNAL", List.of("fact-p")),
            new NormalizedDesignFlow.Participant(
                "p-orders", "Orders Service", "EXTERNAL", List.of("fact-p")),
            new NormalizedDesignFlow.Participant(
                "p-orders-api", "Orders API", "EXTERNAL", List.of("fact-p"))),
        List.of(
            new NormalizedDesignFlow.Step(
                "step-call",
                "service-call",
                "p-client",
                "p-orders",
                "createOrder",
                "Create order",
                List.of("fact-step"))),
        List.of(),
        List.of(),
        List.of(
            new NormalizedDesignFlow.DataMapping(
                "map-init",
                NormalizedDesignFlow.MappingStage.INITIALIZATION,
                "step-trigger",
                "step-call",
                NormalizedDesignFlow.MappingMode.PASS_THROUGH,
                List.of(),
                List.of("fact-map")),
            new NormalizedDesignFlow.DataMapping(
                "map-response",
                NormalizedDesignFlow.MappingStage.RESPONSE,
                "step-call",
                "step-response",
                NormalizedDesignFlow.MappingMode.PASS_THROUGH,
                List.of(),
                List.of("fact-map"))),
        List.of(),
        List.of());
  }

  private static NormalizedDesignFlow flowWithExplicitInitialization() {
    NormalizedDesignFlow flow = sampleFlow();
    NormalizedDesignFlow.DataMapping initialization = flow.dataMappings().getFirst();
    return new NormalizedDesignFlow(
        flow.schemaVersion(),
        flow.flowId(),
        flow.chainName(),
        flow.description(),
        flow.trigger(),
        flow.participants(),
        flow.steps(),
        flow.connections(),
        flow.transformations(),
        List.of(
            new NormalizedDesignFlow.DataMapping(
                initialization.mappingId(),
                initialization.stage(),
                initialization.fromStepId(),
                initialization.toStepId(),
                NormalizedDesignFlow.MappingMode.EXPLICIT,
                List.of(
                    new NormalizedDesignFlow.MappingRule(
                        "$.id", "$.customerId", null, List.of("fact-map"))),
                initialization.sourceFactIds()),
            flow.dataMappings().get(1)),
        flow.constraints(),
        flow.assumptions());
  }

  private static ResolvedCompilerDag sampleDag() {
    return new ResolvedCompilerDag(
        List.of(
            node(
                "cip-requirement-analyzer",
                List.of(SkillArtifactType.RAW_USER_REQUEST.name()),
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(),
                0),
            node(
                "cip-naming-generator",
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.NAMING_MANIFEST.name()),
                List.of("cip-requirement-analyzer"),
                1),
            node(
                "cip-trigger-generator",
                List.of(SkillArtifactType.REQUIREMENT_BRIEF.name()),
                List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                List.of(),
                2),
            node(
                "cip-service-call-generator",
                List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                List.of(SkillArtifactType.GRAPH_PATCH.name()),
                List.of("cip-trigger-generator"),
                3),
            node(
                "cip-script-generator",
                List.of(SkillArtifactType.GRAPH_PATCH.name()),
                List.of(SkillArtifactType.GRAPH_PATCH.name()),
                List.of("cip-service-call-generator"),
                4),
            node(
                "cip-structure-generator",
                List.of(SkillArtifactType.CONFIGURED_TRIGGER_SET.name()),
                List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                List.of(
                    "cip-trigger-generator",
                    "cip-service-call-generator",
                    "cip-script-generator"),
                5),
            node(
                "cip-chain-assembler",
                List.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of("cip-structure-generator"),
                6),
            node(
                "cip-chain-validator",
                List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
                List.of(SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name()),
                List.of("cip-chain-assembler"),
                7)),
        List.of(),
        "dag");
  }

  private static ResolvedCompilerNode node(
      String skillId,
      List<String> consumes,
      List<String> produces,
      List<String> dependsOn,
      int level) {
    return new ResolvedCompilerNode(
        skillId,
        "Planning",
        null,
        consumes,
        produces,
        dependsOn,
        null,
        List.of(),
        List.of(),
        true,
        List.of(),
        level,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null);
  }

  private static ResolvedCompilerNode validationNode(String skillId, int level) {
    return new ResolvedCompilerNode(
        skillId,
        "Validation",
        null,
        List.of(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name()),
        List.of(
            SkillArtifactType.PRE_BUILD_VALIDATION.name(),
            SkillArtifactType.COMPILER_VALIDATION_BUNDLE.name()),
        List.of("cip-chain-assembler"),
        null,
        List.of(),
        List.of(),
        true,
        List.of(),
        level,
        0,
        true,
        CompilerNodeExecutionMode.JAVA_ADAPTER,
        skillId);
  }
}
