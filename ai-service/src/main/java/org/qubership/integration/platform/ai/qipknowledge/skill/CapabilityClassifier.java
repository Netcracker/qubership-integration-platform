package org.qubership.integration.platform.ai.qipknowledge.skill;

import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import java.util.List;
import java.util.Locale;

/** Deterministic classifier for QIP knowledge skill capabilities. */
public class CapabilityClassifier {

  public CipKnowledgeCapabilityPhase classifyPhase(String skillId) {
    String id = skillId.toLowerCase(Locale.ROOT);
    if (id.equals("cip-requirement-analyzer") || id.equals("cip-design-parser")) {
      return CipKnowledgeCapabilityPhase.DISCOVERY;
    }
    if (id.equals("cip-chain-edit-planner") || id.equals("cip-design-planner")) {
      return CipKnowledgeCapabilityPhase.DECISION;
    }
    if (id.equals("cip-pattern-selector")) {
      return CipKnowledgeCapabilityPhase.DISCOVERY;
    }
    if (id.equals("cip-structure-generator")
        || id.equals("cip-chain-generator")
        || id.equals("cip-implementation-designer")) {
      return CipKnowledgeCapabilityPhase.GRAPH_CONSTRUCTION;
    }
    if (id.equals("cip-chain-assembler")) {
      return CipKnowledgeCapabilityPhase.MATERIALIZER;
    }
    if (id.equals("cip-chain-analyzer") || id.equals("cip-migration-planner")) {
      return CipKnowledgeCapabilityPhase.REVERSE;
    }
    if (id.equals("cip-folder-organizer") || id.equals("cip-deployment-packager")) {
      return CipKnowledgeCapabilityPhase.PUBLISHING;
    }
    if (id.endsWith("-validator") || id.equals("cip-validator")) {
      return CipKnowledgeCapabilityPhase.VALIDATOR;
    }
    if (id.endsWith("-generator")) {
      return CipKnowledgeCapabilityPhase.GENERATOR;
    }
  return CipKnowledgeCapabilityPhase.UNSUPPORTED;
  }

  public CapabilityDescriptor toCapability(
      SkillDescriptor skill, CipKnowledgePackVersion packVersion) {
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

  public boolean isSupported(String skillId, CipKnowledgeCapabilityPhase phase) {
    if (phase == CipKnowledgeCapabilityPhase.PUBLISHING
        || phase == CipKnowledgeCapabilityPhase.REVERSE
        || phase == CipKnowledgeCapabilityPhase.UNSUPPORTED) {
      return false;
    }
    if (phase == CipKnowledgeCapabilityPhase.MATERIALIZER) {
      return false;
    }
    return true;
  }

  private static String unsupportedReason(String skillId, CipKnowledgeCapabilityPhase phase) {
    return switch (phase) {
      case PUBLISHING -> "File publishing workflow is not applicable to ai-service backend";
      case REVERSE -> "Reverse/migration workflow is out of scope for chain build integration";
      case MATERIALIZER -> "Materialization is handled by ai-service implement pipeline, not external file assembler";
      case UNSUPPORTED -> "Skill phase is not mapped to a backend capability contract";
      default -> "Capability is not supported in Phase 02 ingestion";
    };
  }
}
