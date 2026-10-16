package org.qubership.integration.platform.ai.qipknowledge.skill;

import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import java.util.List;

/** Registry of capabilities discovered in one QIP knowledge pack. */
public record CapabilityRegistry(QipKnowledgePackVersion version, List<CapabilityDescriptor> capabilities) {}
