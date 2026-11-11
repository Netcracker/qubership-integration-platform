package org.qubership.integration.platform.ai.compiler;

import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.CipKnowledgeCapabilityPhase;

/** Loaded compiler skill markdown and metadata from the active QIP knowledge pack. */
public record CompilerSkillDocument(
    String capabilityId,
    String sourceSkillId,
    String sourcePath,
    String title,
    CipKnowledgeCapabilityPhase phase,
    boolean supported,
    CipKnowledgePackVersion packVersion,
    String markdown) {}
