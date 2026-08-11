package org.qubership.integration.platform.ai.compiler;

import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

/** Loaded compiler skill markdown and metadata from the active QIP knowledge pack. */
public record CompilerSkillDocument(
    String capabilityId,
    String sourceSkillId,
    String sourcePath,
    String title,
    QipKnowledgeCapabilityPhase phase,
    boolean supported,
    QipKnowledgePackVersion packVersion,
    String markdown) {}
