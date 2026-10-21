package org.qubership.integration.platform.ai.qipknowledge.skill;

/** Parsed description of one QIP knowledge skill file. */
public record SkillDescriptor(
    String skillId,
    String title,
    String sourcePath,
    CipKnowledgeCapabilityPhase phase,
    boolean fileTransportOnly,
    String rawSummary) {}
