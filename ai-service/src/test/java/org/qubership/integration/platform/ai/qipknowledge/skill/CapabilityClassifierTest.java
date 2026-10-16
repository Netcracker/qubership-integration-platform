package org.qubership.integration.platform.ai.qipknowledge.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class CapabilityClassifierTest {

  private final CapabilityClassifier classifier = new CapabilityClassifier();
  private final QipKnowledgePackVersion version = new QipKnowledgePackVersion("v1_0_1", "v1_0_1");

  @Test
  void classifiesErrorHandlingGeneratorAsSupportedGenerator() {
    SkillDescriptor skill =
        new SkillDescriptor(
            "cip-error-handling-generator",
            "CIP Error Handling Generator",
            "skills/cip-error-handling-generator/SKILL.md",
            QipKnowledgeCapabilityPhase.GENERATOR,
            false,
            "summary");

    CapabilityDescriptor capability = classifier.toCapability(skill, version);

    assertEquals(QipKnowledgeCapabilityPhase.GENERATOR, capability.phase());
    assertTrue(capability.supported());
  }

  @Test
  void classifiesConfigurationValidatorAsSupportedValidator() {
    SkillDescriptor skill =
        new SkillDescriptor(
            "cip-configuration-validator",
            "CIP Configuration Validator",
            "skills/cip-configuration-validator/SKILL.md",
            QipKnowledgeCapabilityPhase.VALIDATOR,
            false,
            "summary");

    CapabilityDescriptor capability = classifier.toCapability(skill, version);

    assertEquals(QipKnowledgeCapabilityPhase.VALIDATOR, capability.phase());
    assertTrue(capability.supported());
  }

  @Test
  void classifiesChainAssemblerAsUnsupportedMaterializer() {
    assertEquals(QipKnowledgeCapabilityPhase.MATERIALIZER, classifier.classifyPhase("cip-chain-assembler"));

    SkillDescriptor skill =
        new SkillDescriptor(
            "cip-chain-assembler",
            "CIP Chain Assembler",
            "skills/cip-chain-assembler/SKILL.md",
            QipKnowledgeCapabilityPhase.MATERIALIZER,
            false,
            "summary");

    CapabilityDescriptor capability = classifier.toCapability(skill, version);

    assertFalse(capability.supported());
  }

  @Test
  void classifiesPublishingSkillsAsUnsupported() {
    assertEquals(QipKnowledgeCapabilityPhase.PUBLISHING, classifier.classifyPhase("cip-folder-organizer"));
    assertEquals(QipKnowledgeCapabilityPhase.PUBLISHING, classifier.classifyPhase("cip-deployment-packager"));

    SkillDescriptor folderOrganizer =
        new SkillDescriptor(
            "cip-folder-organizer",
            "CIP Folder Organizer",
            "skills/cip-folder-organizer/SKILL.md",
            QipKnowledgeCapabilityPhase.PUBLISHING,
            true,
            "summary");

    CapabilityDescriptor capability = classifier.toCapability(folderOrganizer, version);

    assertEquals(QipKnowledgeCapabilityPhase.PUBLISHING, capability.phase());
    assertFalse(capability.supported());
  }
}
