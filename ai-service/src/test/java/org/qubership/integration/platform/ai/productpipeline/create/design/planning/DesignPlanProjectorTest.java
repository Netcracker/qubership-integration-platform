package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class DesignPlanProjectorTest {

  private final DesignPlanProjector projector = new DesignPlanProjector();

  @Test
  void projectsCatalogDerivedDependenciesInReportOrder() {
    String reportMarkdown = validReport();
    DesignPlanReport report = new DesignPlanReport("1", reportMarkdown);
    ChainSemanticRevision revision = SemanticFixtures.linearOrders();
    ResolvedCompilerDag dag = sampleDag();

    DesignExecutionPlan projected =
        projector.project(
            report,
            revision,
            samplePin(
                revision,
                dag,
                Map.of("cip-design-planner", "skill-hash"),
                Map.of("cip-design-planner", "addon-hash")));

    List<List<String>> expectedCatalogDependencies =
        List.of(
            List.of(),
            List.of(),
            List.of("step-2-search_rest_api_operations"),
            List.of("step-3-get_rest_api_operations_specification"),
            List.of(),
            List.of("step-5-cip-trigger-generator"),
            List.of("step-5-cip-trigger-generator", "step-6-cip-service-call-generator"),
            List.of("step-7-cip-structure-generator"),
            List.of("step-7-cip-structure-generator"),
            List.of("step-9-cip-chain-assembler"));

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
            SemanticFixtures.linearOrders(),
            samplePin(SemanticFixtures.linearOrders(), sampleDag()));

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

    DesignExecutionPlan projected =
        projector.project(
            new DesignPlanReport("1", reportWithoutStageScripts),
            SemanticFixtures.linearOrders(),
            samplePin(SemanticFixtures.linearOrders(), sampleDag()));

    assertEquals(6, projected.steps().size());
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
                    SemanticFixtures.linearOrders(),
                    samplePin(SemanticFixtures.linearOrders(), sampleDag())));
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
            SemanticFixtures.linearOrders(),
            samplePin(SemanticFixtures.linearOrders(), dagWithoutChainValidator));

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
    assertEquals(List.of("step-9-cip-chain-assembler"), validateStep.dependsOn());
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
                    SemanticFixtures.linearOrders(),
                    samplePin(SemanticFixtures.linearOrders(), dagWithoutValidators)));
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
                    SemanticFixtures.linearOrders(),
                    samplePin(SemanticFixtures.linearOrders(), sampleDag())));
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
                    SemanticFixtures.linearOrders(),
                    samplePin(SemanticFixtures.linearOrders(), sampleDag())));
    assertTrue(ex.getMessage().contains("trigger coverage"));
    assertTrue(ex.getMessage().contains("cip-trigger-generator"));
  }

  @Test
  void twoIntentsNeedTwoNamedSteps() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithTwoIdentityMappings();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Encode mapping map-a (cip-script-generator mappingIntentId=map-a)
        4. Encode mapping map-b (cip-script-generator mappingIntentId=map-b)
        5. Generate execution structure (cip-structure-generator)
        6. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        7. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    DesignExecutionPlan projected =
        projector.project(new DesignPlanReport("1", report), revision, pin(revision));
    assertEquals("map-a", projected.steps().get(2).mappingIntentId());
    assertEquals("map-b", projected.steps().get(3).mappingIntentId());
  }

  @Test
  void unnamedMappingStepsBindInRevisionOrder() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithTwoIdentityMappings();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Encode request mapping (cip-script-generator)
        4. Encode response mapping (cip-script-generator)
        5. Generate execution structure (cip-structure-generator)
        6. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        7. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    DesignExecutionPlan projected =
        projector.project(new DesignPlanReport("1", report), revision, pin(revision));
    assertEquals("map-a", projected.steps().get(2).mappingIntentId());
    assertEquals("map-b", projected.steps().get(3).mappingIntentId());
  }

  @Test
  void extraUnnamedMappingStepIsDroppedAfterBinding() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithTwoIdentityMappings();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Encode request mapping (cip-script-generator)
        4. Encode response mapping (cip-script-generator)
        5. Encode leftover mapping (cip-script-generator)
        6. Generate execution structure (cip-structure-generator)
        7. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        8. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    DesignExecutionPlan projected =
        projector.project(new DesignPlanReport("1", report), revision, pin(revision));
    List<String> mappingIds =
        projected.steps().stream()
            .map(DesignExecutionPlan.Step::mappingIntentId)
            .filter(id -> id != null && !id.isBlank())
            .toList();
    assertEquals(List.of("map-a", "map-b"), mappingIds);
  }

  @Test
  void extraNamedMappingStepForUnknownIntentIsDropped() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithTwoIdentityMappings();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Encode mapping map-a (cip-script-generator mappingIntentId=map-a)
        4. Encode mapping map-b (cip-script-generator mappingIntentId=map-b)
        5. Encode process-instance-to-process-id mapping (cip-script-generator mappingIntentId=process-instance-to-process-id)
        6. Generate execution structure (cip-structure-generator)
        7. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        8. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    DesignExecutionPlan projected =
        projector.project(new DesignPlanReport("1", report), revision, pin(revision));
    List<String> mappingIds =
        projected.steps().stream()
            .map(DesignExecutionPlan.Step::mappingIntentId)
            .filter(id -> id != null && !id.isBlank())
            .toList();
    assertEquals(List.of("map-a", "map-b"), mappingIds);
  }

  @Test
  void oneSharedScriptStepFails() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithTwoIdentityMappings();
    String reportWithOneScriptStep =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Encode mapping map-a (cip-transformation-generator mappingIntentId=map-a)
        4. Generate execution structure (cip-structure-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    assertThrows(
        PlannerContractException.class,
        () ->
            projector.project(
                new DesignPlanReport("1", reportWithOneScriptStep), revision, pin(revision)));
  }

  @Test
  void skillMustMatchMechanism() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithMapping();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Encode mapping map-init (cip-transformation-generator mappingIntentId=map-init)
        4. Generate execution structure (cip-structure-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    assertThrows(
        PlannerContractException.class,
        () -> projector.project(new DesignPlanReport("1", report), revision, pin(revision)));
  }

  @Test
  void rejectsTransformationGeneratorWhileMapper2IsOff() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithMapping();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Encode mapping map-init (cip-script-generator mappingIntentId=map-init)
        4. Encode mapping map-init (cip-transformation-generator mappingIntentId=map-init)
        5. Generate execution structure (cip-structure-generator)
        6. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        7. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    PlannerContractException thrown =
        assertThrows(
            PlannerContractException.class,
            () -> projector.project(new DesignPlanReport("1", report), revision, pin(revision)));

    assertTrue(
        thrown.getMessage().contains("cip-transformation-generator"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("mapper-2 is off"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("cip-script-generator"), thrown.getMessage());
  }

  @Test
  void extraMappingStepWithoutIdIsDroppedOnPassThroughRevision() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrders();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Generate Script element for Initialization (cip-script-generator)
        4. Generate execution structure (cip-structure-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    DesignExecutionPlan projected =
        projector.project(new DesignPlanReport("1", report), revision, pin(revision));
    assertTrue(
        projected.steps().stream()
            .noneMatch(step -> step.owningSkillIds().contains("cip-script-generator")));
  }

  @Test
  void retainsUnnamedScriptGeneratorForBehaviorOwnedCompleteTask() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithCompleteTask();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Generate Script element for completeTask (cip-script-generator)
        4. Generate execution structure (cip-structure-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    DesignExecutionPlan projected =
        projector.project(new DesignPlanReport("1", report), revision, pin(revision));
    List<DesignExecutionPlan.Step> scriptSteps =
        projected.steps().stream()
            .filter(step -> step.owningSkillIds().contains("cip-script-generator"))
            .toList();
    assertEquals(1, scriptSteps.size());
    assertTrue(scriptSteps.getFirst().mappingIntentId() == null
        || scriptSteps.getFirst().mappingIntentId().isBlank());
    assertTrue(revision.mappingIntents().isEmpty());
  }

  @Test
  void rejectsMissingScriptGeneratorWhenBehaviorOwnedShellExists() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrdersWithCompleteTask();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Generate execution structure (cip-structure-generator)
        4. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        5. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    PlannerContractException ex =
        assertThrows(
            PlannerContractException.class,
            () -> projector.project(new DesignPlanReport("1", report), revision, pin(revision)));
    assertTrue(ex.getMessage().contains("cip-script-generator"), ex.getMessage());
    assertTrue(ex.getMessage().contains(SemanticFixtures.COMPLETE_TASK_NODE_ID), ex.getMessage());
  }

  @Test
  void mixedOwnerStepKeepsServiceCallWhenPassThroughDropsScript() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrders();
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call and mapping (cip-service-call-generator + cip-script-generator)
        3. Generate execution structure (cip-structure-generator)
        4. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        5. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    DesignExecutionPlan projected =
        projector.project(new DesignPlanReport("1", report), revision, pin(revision));
    DesignExecutionPlan.Step mixed =
        projected.steps().stream()
            .filter(step -> step.owningSkillIds().contains("cip-service-call-generator"))
            .findFirst()
            .orElseThrow();
    assertFalse(mixed.owningSkillIds().contains("cip-script-generator"));
  }

  @Test
  void emptyMechanismSelectFails() {
    ChainSemanticRevision revision =
        SemanticFixtures.linear(
            "Orders",
            "revision-orders",
            "trigger-http",
            "node-call",
            "call-1",
            "createOrder",
            "Orders API",
            List.of(
                new MappingIntent(
                    "map-init",
                    "edge-1",
                    MappingPort.OUTPUT,
                    "edge-1",
                    MappingPort.REQUEST,
                    List.of())),
            List.of());
    String report =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Generate Service Call element (cip-service-call-generator)
        3. Encode mapping map-init (cip-transformation-generator mappingIntentId=map-init)
        4. Generate execution structure (cip-structure-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();
    assertThrows(
        PlannerContractException.class,
        () -> projector.project(new DesignPlanReport("1", report), revision, pin(revision)));
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
                    SemanticFixtures.linearOrdersWithMapping(),
                    samplePin(SemanticFixtures.linearOrdersWithMapping(), sampleDag())));
    assertTrue(ex.getMessage().contains("script"));
  }

  @Test
  void acceptsCatalogSystemNameThatIsNotASemanticIdentity() {
    ChainSemanticRevision revision =
        SemanticFixtures.linear(
            "HealthProxy",
            "revision-health-proxy",
            "trigger-http",
            "node-call",
            "call-inventory",
            "getInventory",
            "/health-proxy",
            List.of(),
            List.of());
    String report =
        """
        1. Analyze requirements and name chain HealthProxy (cip-requirement-analyzer + cip-naming-generator)
        2. Generate HTTP Trigger element with interface HTTP (cip-trigger-generator)
        3. Generate Service Call element for Petstore Ext.getInventory (cip-service-call-generator)
        4. Generate execution structure and element ordering (cip-structure-generator)
        5. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        6. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    DesignExecutionPlan projected =
        projector.project(
            new DesignPlanReport("1", report), revision, samplePin(revision, sampleDag()));

    assertEquals(6, projected.steps().size());
    for (DesignExecutionPlan.Step step : projected.steps()) {
      assertTrue(step.participantRefs().isEmpty(), step.stepId());
    }
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
                    SemanticFixtures.linearOrders(),
                    samplePin(SemanticFixtures.linearOrders(), cyclic)));
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
        7. Generate execution structure and element ordering (cip-structure-generator)
        8. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
        9. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        10. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }

  private static CompilerRunPin pin(ChainSemanticRevision revision) {
    return samplePin(revision, sampleDag());
  }

  private static CompilerRunPin samplePin(
      ChainSemanticRevision revision, ResolvedCompilerDag dag) {
    return samplePin(revision, dag, Map.of(), Map.of());
  }

  private static CompilerRunPin samplePin(
      ChainSemanticRevision revision,
      ResolvedCompilerDag dag,
      Map<String, String> skillHashes,
      Map<String, String> addonHashes) {
    return new CompilerRunPin(
        "compiler",
        "1",
        "pkg-digest",
        1,
        "1",
        "catalog-hash",
        dag,
        List.of(),
        skillHashes,
        addonHashes,
        List.of(),
        Kind.CHAIN_SEMANTIC_REVISION.name(),
        revision.schemaVersion(),
        revision.revisionId(),
        "design-input-hash",
        revision.compilerContractVersion(),
        "contract-sha");
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
                "cip-transformation-generator",
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
