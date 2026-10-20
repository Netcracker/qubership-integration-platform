package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemCipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;

class CompilerSkillCapabilityGateTest {

  private static CompilerSkillCapabilityGate gate;

  @BeforeAll
  static void setUpPack() throws Exception {
    Path outputDir = Files.createTempDirectory("qip-capability-gate-test");
    CipKnowledgePackTestSupport.configureAddonPackRoot();
    CipKnowledgePackBuildGenerator.generate(CipKnowledgePackFixturePaths.packRoot(), outputDir);
    CipKnowledgePackVersion version = CipKnowledgePackVersion.fromPath(CipKnowledgePackFixturePaths.packRoot());
    CipKnowledgePackRepository repository =
        new FilesystemCipKnowledgePackRepository(outputDir, version);
    gate = new CompilerSkillCapabilityGate(repository);
  }

  @Test
  void allowsSupportedGeneratorSkills() {
    assertTrue(gate.allowsGenericExecution("cip-security-generator"));
    assertTrue(gate.allowsGenericExecution("cip-error-handling-generator"));
    assertTrue(gate.allowsGenericExecution("cip-routing-generator"));
    assertTrue(gate.allowsGenericExecution("cip-service-call-generator"));
    assertTrue(gate.allowsGenericExecution("cip-loop-generator"));
  }

  @Test
  void allowsChainGeneratorOnlyForGraphConstruction() {
    assertTrue(gate.allowsGenericExecution("cip-chain-generator"));
    assertTrue(gate.allowsGenericExecution("cip-structure-generator"));
    assertTrue(gate.allowsGenericExecution("cip-pattern-selector"));
  }

  @Test
  void allowsPromotedDiscoverySkill() {
    assertTrue(gate.allowsGenericExecution("cip-requirement-analyzer"));
  }

  @Test
  void rejectsUnpromotedDiscoverySkills() {
    assertFalse(gate.allowsGenericExecution("cip-design-parser"));
  }

  @Test
  void rejectsInternalAndDeterministicSkills() {
    assertFalse(gate.allowsGenericExecution("plan-validator"));
    assertFalse(gate.allowsGenericExecution("cip-chain-assembler"));
    assertFalse(gate.allowsGenericExecution("chain-assembler"));
  }

  @Test
  void rejectsUnsupportedPublishingSkills() {
    assertFalse(gate.allowsGenericExecution("cip-folder-organizer"));
    String reason = gate.rejectReason("cip-folder-organizer");
    assertTrue(
        reason.contains("not applicable to ai-service backend")
            || reason.contains("Excluded by compiler skill catalog"));
  }

  @Test
  void rejectReasonForUnknownSkill() {
    assertTrue(gate.rejectReason("missing-skill").contains("No compiler skill registered"));
  }
}
