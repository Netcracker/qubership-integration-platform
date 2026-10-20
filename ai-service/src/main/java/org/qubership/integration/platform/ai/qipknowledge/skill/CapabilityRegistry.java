package org.qubership.integration.platform.ai.qipknowledge.skill;

import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import java.util.List;

/** Registry of capabilities discovered in one QIP knowledge pack. */
public record CapabilityRegistry(CipKnowledgePackVersion version, List<CapabilityDescriptor> capabilities) {}
