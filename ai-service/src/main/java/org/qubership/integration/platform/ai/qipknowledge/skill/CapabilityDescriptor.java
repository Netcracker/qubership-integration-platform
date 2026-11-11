package org.qubership.integration.platform.ai.qipknowledge.skill;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;

/** One backend capability derived from a QIP knowledge skill. */
public record CapabilityDescriptor(
    String id,
    String sourceSkillId,
    CipKnowledgePackVersion packVersion,
    CipKnowledgeCapabilityPhase phase,
    boolean supported,
    String reasonIfUnsupported,
    List<String> requiredTools,
    List<String> executionOrderHints) {}
