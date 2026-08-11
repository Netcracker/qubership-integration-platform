package org.qubership.integration.platform.ai.qipknowledge.skill;

import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import java.util.List;
import java.util.Locale;

/** Deterministic classifier for QIP knowledge skill capabilities. */
public class CapabilityClassifier {

  public QipKnowledgeCapabilityPhase classifyPhase(String skillId) {
    String id = skillId.toLowerCase(Locale.ROOT);
    if (id.equals("cip-requirement-analyzer") || id.equals("cip-design-parser")) {
      return QipKnowledgeCapabilityPhase.DISCOVERY;
    }
    if (id.equals("cip-pattern-selector")) {
      return QipKnowledgeCapabilityPhase.DISCOVERY;
    }
    if (id.equals("cip-structure-generator")
        || id.equals("cip-chain-generator")
        || id.equals("cip-implementation-designer")) {
      return QipKnowledgeCapabilityPhase.GRAPH_CONSTRUCTION;
    }
    if (id.equals("cip-chain-assembler")) {
      return QipKnowledgeCapabilityPhase.MATERIALIZER;
    }
    if (id.equals("cip-chain-analyzer") || id.equals("cip-migration-planner")) {
      return QipKnowledgeCapabilityPhase.REVERSE;
    }
    if (id.equals("cip-folder-organizer") || id.equals("cip-deployment-packager")) {
      return QipKnowledgeCapabilityPhase.PUBLISHING;
    }
    if (id.endsWith("-validator") || id.equals("cip-validator")) {
      return QipKnowledgeCapabilityPhase.VALIDATOR;
    }
    if (id.endsWith("-generator")) {
      return QipKnowledgeCapabilityPhase.GENERATOR;
    }
  return QipKnowledgeCapabilityPhase.UNSUPPORTED;
  }

  public CapabilityDescriptor toCapability(
      SkillDescriptor skill, QipKnowledgePackVersion packVersion) {
    boolean supported = isSupported(skill.skillId(), skill.phase());
    String reason = supported ? null : unsupportedReason(skill.skillId(), skill.phase());
    return new CapabilityDescriptor(
        skill.skillId(),
        skill.skillId(),
        packVersion,
        skill.phase(),
        supported,
        reason,
        List.of(),
        List.of());
  }

  public boolean isSupported(String skillId, QipKnowledgeCapabilityPhase phase) {
    if (phase == QipKnowledgeCapabilityPhase.PUBLISHING
        || phase == QipKnowledgeCapabilityPhase.REVERSE
        || phase == QipKnowledgeCapabilityPhase.UNSUPPORTED) {
      return false;
    }
    if (phase == QipKnowledgeCapabilityPhase.MATERIALIZER) {
      return false;
    }
    return true;
  }

  private static String unsupportedReason(String skillId, QipKnowledgeCapabilityPhase phase) {
    return switch (phase) {
      case PUBLISHING -> "File publishing workflow is not applicable to ai-service backend";
      case REVERSE -> "Reverse/migration workflow is out of scope for chain build integration";
      case MATERIALIZER -> "Materialization is handled by ai-service implement pipeline, not external file assembler";
      case UNSUPPORTED -> "Skill phase is not mapped to a backend capability contract";
      default -> "Capability is not supported in Phase 02 ingestion";
    };
  }
}
