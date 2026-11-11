package org.qubership.integration.platform.ai.qipknowledge.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.CipKnowledgeCapabilityPhase;

/** Metadata for one RAG-indexable document chunk from a QIP knowledge pack. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CipKnowledgeRagChunk(
    String chunkId,
    String sourcePath,
    CipKnowledgePackVersion packVersion,
    CipKnowledgeCapabilityPhase phase,
    List<String> capabilityIds,
    String title,
    int ordinal,
    String content) {}
