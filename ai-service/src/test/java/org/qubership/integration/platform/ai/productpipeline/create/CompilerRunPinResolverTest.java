package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPackageIdentity;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineDependency;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexBuilder;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSource;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.CompilerPipelinePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionService;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;

class CompilerRunPinResolverTest {

  private CompilerPipelineIndex activeIndex;
  private ProductPipelineProfile createChainProfile;
  private KnowledgeQueryContext fullKnowledgeContext;
  private CompilerRunPinResolver resolver;

  @BeforeEach
  void setUp() {
    activeIndex = readySchemaV2IndexFixture();
    createChainProfile = createChainProfile();
    fullKnowledgeContext =
        new KnowledgeQueryContext(
            new KnowledgePackageRef(
                "artifact-full@1.0.0",
                "1.0.0",
                "1.0.0",
                "sha256:pinned",
                "CERTIFIED",
                "sha256:certificate"));
    resolver = resolverFor(activeIndex);
  }

  @Test
  void resolvesAndPinsCreateChainClosure() {
    CompilerRunPin pin =
        resolverFor(readySchemaV2IndexFixture()).resolve(createChainProfile, fullKnowledgeContext);

    assertEquals(2, pin.pipelineIndexSchemaVersion());
    assertEquals(activeIndex.packageIdentity().packageDigest(), pin.compilerPackageDigest());
    assertTrue(
        pin.resolvedDag().nodes().stream()
            .anyMatch(node -> node.skillId().equals("cip-structure-generator")));
    assertEquals(
        "captureChainStructure",
        pin.resolvedDag().nodes().stream()
            .filter(node -> node.skillId().equals("cip-structure-generator"))
            .findFirst()
            .orElseThrow()
            .captureTool());
    assertEquals(
        "graph-assembly",
        pin.resolvedDag().nodes().stream()
            .filter(node -> node.skillId().equals("cip-chain-assembler"))
            .findFirst()
            .orElseThrow()
            .adapterId());
    assertFalse(pin.skillSha256ById().isEmpty());
    assertFalse(pin.addonSha256ById().isEmpty());
  }

  @Test
  void rejectsRequiredNodeThatIsNotRuntimeReady() {
    CompilerRunPinResolver unresolvedResolver =
        resolverFor(indexWithUnresolvedRequiredNode("cip-structure-generator"));

    assertThrows(
        IllegalStateException.class,
        () -> unresolvedResolver.resolve(createChainProfile, fullKnowledgeContext));
  }

  @Test
  void productionCreateChainPinMarksStructureGeneratorRuntimeReady() {
    CompilerRunPin pin = resolveProductionCreateChainPin();

    assertTrue(
        pin.resolvedDag().nodes().stream()
            .filter(node -> node.skillId().equals("cip-structure-generator"))
            .findFirst()
            .orElseThrow()
            .runtimeReady());
    assertEquals(
        "captureChainStructure",
        pin.resolvedDag().nodes().stream()
            .filter(node -> node.skillId().equals("cip-structure-generator"))
            .findFirst()
            .orElseThrow()
            .captureTool());
    assertTrue(pin.addonSha256ById().containsKey("cip-structure-generator"));
    assertFalse(pin.addonSha256ById().get("cip-structure-generator").isBlank());
  }

  @Test
  void productionCreateChainPinSchedulesPatternSelectorAfterSeed() {
    CompilerRunPin pin = resolverFor(buildProductionIndex()).resolve(createChainProfile, fullKnowledgeContext);
    CompilerDerivedPlanningScheduler scheduler =
        new CompilerDerivedPlanningScheduler(
            mock(SkillExecutorRegistry.class), mock(CompilerNodeExecutionAdapterRegistry.class));

    assertEquals(
        "cip-pattern-selector",
        scheduler.next(CompilerDerivedPlanningScheduler.seededState(pin.resolvedDag()))
            .orElseThrow()
            .skillId());
  }

  @Test
  void productionCreateChainPinIncludesQuartzSchedulerInClosure() {
    CompilerRunPin pin =
        resolverFor(buildProductionIndex()).resolve(createChainProfile, fullKnowledgeContext);

    assertTrue(
        pin.capabilityClosure().contains("cip-quartz-scheduler-generator"),
        "quartz must be in pin closure via all-generation-skills expansion from skill-catalog");
    assertTrue(
        pin.resolvedDag().nodes().stream()
            .anyMatch(node -> "cip-quartz-scheduler-generator".equals(node.skillId())));
    assertTrue(
        pin.resolvedDag().nodes().stream()
            .filter(node -> "cip-chain-assembler".equals(node.skillId()))
            .findFirst()
            .orElseThrow()
            .dependsOn()
            .contains("cip-quartz-scheduler-generator"),
        "assembler dependsOn must include quartz so resolveClosure walks to it");
  }

  @Test
  void productionCreateChainPinIncludesHttpTriggerEndpointInClosure() {
    CompilerRunPin pin =
        resolverFor(buildProductionIndex()).resolve(createChainProfile, fullKnowledgeContext);

    assertTrue(
        pin.capabilityClosure().contains("cip-http-trigger-endpoint-generator"),
        "http-trigger Custom URI specialist must be in pin closure via all-generation-skills");
    assertTrue(
        pin.resolvedDag().nodes().stream()
            .anyMatch(node -> "cip-http-trigger-endpoint-generator".equals(node.skillId())));
    GraphPatchOwnershipPolicy ownership =
        pin.resolvedDag().nodes().stream()
            .filter(node -> "cip-http-trigger-endpoint-generator".equals(node.skillId()))
            .findFirst()
            .orElseThrow()
            .ownership();
    assertEquals(
        Set.of("contextPath", "httpMethodRestrict", "externalRoute", "privateRoute"),
        ownership.properties().get("http-trigger"));
    assertTrue(
        pin.resolvedDag().nodes().stream()
            .filter(node -> "cip-chain-assembler".equals(node.skillId()))
            .findFirst()
            .orElseThrow()
            .dependsOn()
            .contains("cip-http-trigger-endpoint-generator"),
        "assembler dependsOn must include the HTTP trigger endpoint specialist");
  }

  @Test
  void productionCreateChainPinOwnsNativeKafkaAndRabbitTriggerIdentityOnMessaging() {
    CompilerRunPin pin =
        resolverFor(buildProductionIndex()).resolve(createChainProfile, fullKnowledgeContext);

    GraphPatchOwnershipPolicy ownership =
        pin.resolvedDag().nodes().stream()
            .filter(node -> "cip-messaging-generator".equals(node.skillId()))
            .findFirst()
            .orElseThrow()
            .ownership();
    assertEquals(
        Set.of(
            "connectionSourceType",
            "brokers",
            "topics",
            "groupId",
            "topicsClassifierName",
            "maasClassifierNamespace",
            "maasClassifierTenantEnabled",
            "maasClassifierTenantId"),
        ownership.properties().get("kafka-trigger-2"));
    assertEquals(
        Set.of(
            "connectionSourceType",
            "addresses",
            "exchange",
            "routingKey",
            "queues",
            "vhostClassifierName",
            "maasClassifierNamespace",
            "username"),
        ownership.properties().get("rabbitmq-trigger-2"));
    assertTrue(
        pin.capabilityClosure().contains("cip-messaging-generator"),
        "messaging must be in pin closure via all-generation-skills");
  }

  @Test
  void runPinRetainsEffectiveOwnership() {
    CompilerRunPin pin = resolver.resolve(createChainProfile, fullKnowledgeContext);
    GraphPatchOwnershipPolicy policy =
        pin.resolvedDag().nodes().stream()
            .filter(node -> node.skillId().equals("cip-script-generator"))
            .findFirst()
            .orElseThrow()
            .ownership();

    assertEquals(Set.of("script"), policy.nodeTypes());
    assertEquals(Set.of("script"), policy.properties().get("script"));
  }

  @Test
  void productionCreateChainPinDeniesTopologyOnSpecializedGenerators() {
    CompilerRunPin pin =
        resolverFor(buildProductionIndex()).resolve(createChainProfile, fullKnowledgeContext);

    for (String skillId : List.of("cip-script-generator", "cip-service-call-generator")) {
      GraphPatchOwnershipPolicy ownership =
          pin.resolvedDag().nodes().stream()
              .filter(node -> skillId.equals(node.skillId()))
              .findFirst()
              .orElseThrow()
              .ownership();
      assertFalse(ownership.mayAddNodes(), skillId + " must not add nodes");
      assertFalse(ownership.mayAddEdges(), skillId + " must not add edges");
    }

    assertEquals(
        "captureChainStructure",
        pin.resolvedDag().nodes().stream()
            .filter(node -> "cip-structure-generator".equals(node.skillId()))
            .findFirst()
            .orElseThrow()
            .captureTool());
  }

  @Test
  void resumeRejectsUnavailablePinnedCompilerPackage() {
    RunManifest manifest = manifestPinnedTo("old-compiler-sha");
    assertThrows(IllegalStateException.class, () -> resolver.verifyAvailable(manifest));
  }

  private CompilerRunPin resolveProductionCreateChainPin() {
    CompilerPipelineNode productionStructure = productionStructureNode();
    if (!productionStructure.runtimeReady()
        || !"captureChainStructure".equals(productionStructure.captureTool())) {
      throw new IllegalStateException(
          "production cip-structure-generator is not pin-ready: ready="
              + productionStructure.runtimeReady()
              + " capture="
              + productionStructure.captureTool()
              + " findings="
              + productionStructure.runtimeReadinessFindings());
    }
    return resolverFor(indexWithProductionStructureNode(productionStructure))
        .resolve(createChainProfile, fullKnowledgeContext);
  }

  private static CompilerPipelineNode productionStructureNode() {
    return buildProductionIndex().nodes().stream()
        .filter(node -> "cip-structure-generator".equals(node.skillId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing production node: cip-structure-generator"));
  }

  private CompilerPipelineIndex indexWithProductionStructureNode(
      CompilerPipelineNode productionStructure) {
    CompilerPipelineIndex ready = readySchemaV2IndexFixture();
    List<CompilerPipelineNode> nodes = new ArrayList<>();
    for (CompilerPipelineNode node : ready.nodes()) {
      if ("cip-structure-generator".equals(node.skillId())) {
        nodes.add(
            node(
                productionStructure.skillId(),
                productionStructure.compilerPhase(),
                productionStructure.generatorId(),
                node.consumes(),
                node.produces(),
                node.dependsOn(),
                productionStructure.captureTool(),
                productionStructure.runtimeReady(),
                productionStructure.runtimeReadinessFindings(),
                productionStructure.skillSha256(),
                productionStructure.addonSha256(),
                node.topologicalLevel(),
                node.stableTieBreaker(),
                node.executionMode(),
                node.adapterId()));
      } else {
        nodes.add(node);
      }
    }
    return new CompilerPipelineIndex(
        ready.schemaVersion(),
        ready.packVersion(),
        ready.sources(),
        ready.entries(),
        ready.packageIdentity(),
        ready.sourceDigests(),
        nodes,
        ready.dependencies());
  }

  private static CompilerPipelineIndex buildProductionIndex() {
    try {
      QipKnowledgePackTestSupport.configureAddonPackRoot();
      var policy = QipKnowledgePackTestSupport.buildPolicyFromFixture();
      var packRoot = QipKnowledgePackFixturePaths.packRoot();
      QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();
      var result = ingestionService.ingest(packRoot);
      QipKnowledgePackScanResult scanResult =
          new QipKnowledgePackScanResult(packRoot, result.manifest().version(), result.files());
      return new CompilerPipelineIndexBuilder()
          .build(scanResult, policy, QipKnowledgePackFixturePaths.addonRoot());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build production pipeline index", e);
    }
  }

  private static CompilerRunPinResolver resolverFor(CompilerPipelineIndex index) {
    return new CompilerRunPinResolver(index);
  }

  private static ProductPipelineProfile createChainProfile() {
    return new ProductPipelineProfile(
        1,
        "create-chain",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of(),
        new CompilerPipelinePolicy(
            List.of(2),
            List.of("Discovery", "Planning", "Generation", "Assembly", "Validation"),
            List.of(new ArtifactTypeRef("requirement-brief", 1)),
            List.of(
                new ArtifactTypeRef("graph-assembly-result", 1),
                new ArtifactTypeRef("compiler-validation-bundle", 1))));
  }

  private CompilerPipelineIndex readySchemaV2IndexFixture() {
    CompilerPipelineNode pattern =
        node(
            "cip-pattern-selector",
            "Discovery",
            "GEN-01",
            List.of("REQUIREMENT_BRIEF"),
            List.of("ELEMENT_SKELETON"),
            List.of(),
            "captureSelectedPattern",
            true,
            List.of(),
            "skill-sha-pattern",
            "addon-sha-pattern",
            0,
            0,
            CompilerNodeExecutionMode.LLM_SKILL,
            null);
    CompilerPipelineNode structure =
        node(
            "cip-structure-generator",
            "Planning",
            "GEN-03",
            List.of("ELEMENT_SKELETON"),
            List.of("CHAIN_STRUCTURE"),
            List.of("cip-pattern-selector", "cip-script-generator"),
            "captureChainStructure",
            true,
            List.of(),
            "skill-sha-structure",
            "addon-sha-structure",
            1,
            0,
            CompilerNodeExecutionMode.LLM_SKILL,
            null);
    CompilerPipelineNode script =
        node(
            "cip-script-generator",
            "Generation",
            "GEN-10",
            List.of("CHAIN_STRUCTURE"),
            List.of("CHAIN_STRUCTURE"),
            List.of("cip-pattern-selector"),
            "repairScriptBodies",
            true,
            List.of(),
            "skill-sha-script",
            "addon-sha-script",
            1,
            1,
            CompilerNodeExecutionMode.LLM_SKILL,
            null,
            new GraphPatchOwnershipPolicy(
                true, true, Set.of("script"), Set.of(), Map.of("script", Set.of("script"))));
    CompilerPipelineNode assembler =
        node(
            "cip-chain-assembler",
            "Assembly",
            "",
            List.of("CHAIN_STRUCTURE"),
            List.of("GRAPH_ASSEMBLY_RESULT"),
            List.of("cip-structure-generator"),
            null,
            true,
            List.of(),
            "skill-sha-assembler",
            "addon-sha-assembler",
            2,
            0,
            CompilerNodeExecutionMode.JAVA_ADAPTER,
            "graph-assembly");
    CompilerPipelineNode validator =
        node(
            "cip-structural-validator",
            "Validation",
            "",
            List.of("GRAPH_ASSEMBLY_RESULT"),
            List.of("COMPILER_VALIDATION_BUNDLE"),
            List.of("cip-chain-assembler"),
            null,
            true,
            List.of(),
            "skill-sha-validator",
            "addon-sha-validator",
            3,
            0,
            CompilerNodeExecutionMode.JAVA_ADAPTER,
            "structural-validation");
    return new CompilerPipelineIndex(
        CompilerPipelineIndexBuilder.SCHEMA_VERSION,
        new QipKnowledgePackVersion("v1_0_0", "v1_0_0"),
        new CompilerPipelineIndexSource("catalog", "policy"),
        List.of(),
        new CompilerPackageIdentity("compiler-v2", "1.0.0", "digest-ready-v2"),
        Map.of("skill-catalog.yaml", "abc"),
        List.of(pattern, structure, script, assembler, validator),
        List.of(
            new CompilerPipelineDependency(
                "cip-pattern-selector", "cip-structure-generator", List.of("ELEMENT_SKELETON")),
            new CompilerPipelineDependency(
                "cip-pattern-selector", "cip-script-generator", List.of("ELEMENT_SKELETON")),
            new CompilerPipelineDependency(
                "cip-script-generator", "cip-structure-generator", List.of("CHAIN_STRUCTURE")),
            new CompilerPipelineDependency(
                "cip-structure-generator", "cip-chain-assembler", List.of("CHAIN_STRUCTURE")),
            new CompilerPipelineDependency(
                "cip-chain-assembler",
                "cip-structural-validator",
                List.of("GRAPH_ASSEMBLY_RESULT"))));
  }

  private CompilerPipelineIndex indexWithUnresolvedRequiredNode(String skillId) {
    CompilerPipelineIndex ready = readySchemaV2IndexFixture();
    List<CompilerPipelineNode> nodes = new ArrayList<>();
    for (CompilerPipelineNode node : ready.nodes()) {
      if (skillId.equals(node.skillId())) {
        nodes.add(
            node(
                node.skillId(),
                node.compilerPhase(),
                node.generatorId(),
                node.consumes(),
                node.produces(),
                node.dependsOn(),
                node.captureTool(),
                false,
                List.of("MISSING_ADDON_RUNTIME_METADATA"),
                node.skillSha256(),
                node.addonSha256(),
                node.topologicalLevel(),
                node.stableTieBreaker(),
                node.executionMode(),
                node.adapterId()));
      } else {
        nodes.add(node);
      }
    }
    return new CompilerPipelineIndex(
        ready.schemaVersion(),
        ready.packVersion(),
        ready.sources(),
        ready.entries(),
        ready.packageIdentity(),
        ready.sourceDigests(),
        nodes,
        ready.dependencies());
  }

  private RunManifest manifestPinnedTo(String compilerPackageDigest) {
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler-v2",
            "1.0.0",
            compilerPackageDigest,
            2,
            "v1_0_0",
            "index-digest",
            new ResolvedCompilerDag(List.of(), List.of(), "dag-digest"),
            List.of("cip-structure-generator"),
            Map.of("cip-structure-generator", "skill-sha-structure"),
            Map.of("cip-structure-generator", "addon-sha-structure"),
            List.of(new ArtifactTypeRef("graph-assembly-result", 1)));
    return new RunManifest(
        "run-1",
        null,
        List.of(),
        "product",
        "create-chain",
        "1",
        "create-chain@1",
        "reference-baseline-v1",
        "reference-baseline-v1",
        List.of(),
        "closure",
        new KnowledgePackageRef(
            "artifact-full",
            "1.0.0",
            "1.0.0",
            "pinned",
            "CERTIFIED",
            "sha256:certificate"),
        "2026.1",
        List.of(),
        pin);
  }

  private static CompilerPipelineNode node(
      String skillId,
      String phase,
      String generatorId,
      List<String> consumes,
      List<String> produces,
      List<String> dependsOn,
      String captureTool,
      boolean runtimeReady,
      List<String> findings,
      String skillSha,
      String addonSha,
      int level,
      int tie,
      CompilerNodeExecutionMode mode,
      String adapterId) {
    return node(
        skillId,
        phase,
        generatorId,
        consumes,
        produces,
        dependsOn,
        captureTool,
        runtimeReady,
        findings,
        skillSha,
        addonSha,
        level,
        tie,
        mode,
        adapterId,
        GraphPatchOwnershipPolicy.denyAll());
  }

  private static CompilerPipelineNode node(
      String skillId,
      String phase,
      String generatorId,
      List<String> consumes,
      List<String> produces,
      List<String> dependsOn,
      String captureTool,
      boolean runtimeReady,
      List<String> findings,
      String skillSha,
      String addonSha,
      int level,
      int tie,
      CompilerNodeExecutionMode mode,
      String adapterId,
      GraphPatchOwnershipPolicy ownership) {
    return new CompilerPipelineNode(
        skillId,
        phase,
        generatorId,
        consumes,
        produces,
        dependsOn,
        captureTool,
        List.of(),
        List.of(),
        runtimeReady,
        findings,
        skillSha,
        addonSha,
        level,
        tie,
        true,
        mode,
        adapterId,
        ownership);
  }
}
