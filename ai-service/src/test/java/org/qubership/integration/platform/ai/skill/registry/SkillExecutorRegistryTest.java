package org.qubership.integration.platform.ai.skill.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.CompilerSkillCapabilityGate;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntime;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemQipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.impl.CompilerSkillExecutor;

class SkillExecutorRegistryTest {

  private static final String DEDICATED_SKILL = "dedicated-skill";
  private static final String GENERATOR_SKILL = "cip-security-generator";
  private static final String CHAIN_GENERATOR_SKILL = "cip-chain-generator";

  private static QipKnowledgePackRepository packRepository;

  @BeforeAll
  static void setUpPack() throws Exception {
    Path outputDir = Files.createTempDirectory("qip-skill-executor-registry-test");
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    QipKnowledgePackBuildGenerator.generate(QipKnowledgePackFixturePaths.packRoot(), outputDir);
    QipKnowledgePackVersion version = QipKnowledgePackVersion.fromPath(QipKnowledgePackFixturePaths.packRoot());
    packRepository = new FilesystemQipKnowledgePackRepository(outputDir, version);
  }

  @Test
  void requireReturnsDedicatedBeanBeforeGenericFallback() {
    SkillExecutor dedicated = stubExecutor(DEDICATED_SKILL);
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);

    SkillExecutorRegistry registry =
        SkillExecutorRegistry.forTest(
            Map.of(DEDICATED_SKILL, dedicated, GENERATOR_SKILL, dedicated), gate, runtime);

    assertSame(dedicated, registry.require(DEDICATED_SKILL));
  }

  @Test
  void requireFallsBackToCompilerSkillExecutorForSupportedGenerator() {
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of(), gate, runtime);

    SkillExecutor executor = registry.require(GENERATOR_SKILL);

    assertInstanceOf(CompilerSkillExecutor.class, executor);
    assertEquals(GENERATOR_SKILL, executor.skillId());
    assertEquals(SkillExecutorKind.AGENT, executor.kind());
  }

  @Test
  void requireFallsBackToCompilerSkillExecutorForErrorHandlingWithoutDedicatedBean() {
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of(), gate, runtime);

    SkillExecutor executor = registry.require("cip-error-handling-generator");

    assertInstanceOf(CompilerSkillExecutor.class, executor);
    assertEquals("cip-error-handling-generator", executor.skillId());
  }

  @Test
  void requireFallsBackToCompilerSkillExecutorForChainGenerator() {
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of(), gate, runtime);

    SkillExecutor chainGenerator = registry.require(CHAIN_GENERATOR_SKILL);

    assertInstanceOf(CompilerSkillExecutor.class, chainGenerator);
    assertEquals(CHAIN_GENERATOR_SKILL, chainGenerator.skillId());
  }

  @Test
  void requireFallsBackToCompilerSkillExecutorForPromotedStructureGenerator() {
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of(), gate, runtime);

    SkillExecutor executor = registry.require("cip-structure-generator");

    assertInstanceOf(CompilerSkillExecutor.class, executor);
    assertEquals("cip-structure-generator", executor.skillId());
  }

  @Test
  void requireFallsBackToCompilerSkillExecutorForPromotedPatternSelector() {
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of(), gate, runtime);

    SkillExecutor executor = registry.require("cip-pattern-selector");

    assertInstanceOf(CompilerSkillExecutor.class, executor);
    assertEquals("cip-pattern-selector", executor.skillId());
  }

  @Test
  void requireRejectsUnknownSkill() {
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of(), gate, runtime);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> registry.require("unknown-skill"));

    assertTrue(error.getMessage().contains("unknown-skill"));
    assertTrue(error.getMessage().contains("No compiler skill registered"));
  }

  @Test
  void requireFallsBackToCompilerSkillExecutorForPromotedRequirementAnalyzer() {
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of(), gate, runtime);

    SkillExecutor executor = registry.require("cip-requirement-analyzer");

    assertInstanceOf(CompilerSkillExecutor.class, executor);
    assertEquals("cip-requirement-analyzer", executor.skillId());
  }

  @Test
  void requireReturnsDedicatedPlanValidatorExecutor() {
    SkillExecutor dedicated = stubExecutor("plan-validator");
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of("plan-validator", dedicated));

    assertSame(dedicated, registry.require("plan-validator"));
    assertEquals("plan-validator", registry.require("plan-validator").skillId());
  }

  @Test
  void requireDoesNotFallBackToGenericCompilerExecutorForInternalPlanValidator() {
    CompilerSkillCapabilityGate gate = capabilityGate();
    CompilerSkillRuntime runtime = mock(CompilerSkillRuntime.class);
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of(), gate, runtime);

    assertThrows(IllegalStateException.class, () -> registry.require("plan-validator"));
  }

  @Test
  void forTestWithoutFallbackThrowsWhenSkillMissing() {
    SkillExecutorRegistry registry = SkillExecutorRegistry.forTest(Map.of());

    assertThrows(IllegalStateException.class, () -> registry.require(GENERATOR_SKILL));
  }

  private static SkillExecutor stubExecutor(String skillId) {
    return new SkillExecutor() {
      @Override
      public String skillId() {
        return skillId;
      }

      @Override
      public SkillExecutorKind kind() {
        return SkillExecutorKind.DETERMINISTIC;
      }

      @Override
      public java.util.Set<org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType>
          requiredInputs() {
        return java.util.Set.of();
      }

      @Override
      public java.util.Set<org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType>
          outputTypes() {
        return java.util.Set.of();
      }

      @Override
      public io.smallrye.mutiny.Uni<
              org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult>
          run(
              org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext context,
              org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace workspace) {
        return io.smallrye.mutiny.Uni.createFrom()
            .item(
                org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult.completed(
                    java.util.List.of(), "done"));
      }
    };
  }

  private static CompilerSkillCapabilityGate capabilityGate() {
    return new CompilerSkillCapabilityGate(packRepository);
  }
}
