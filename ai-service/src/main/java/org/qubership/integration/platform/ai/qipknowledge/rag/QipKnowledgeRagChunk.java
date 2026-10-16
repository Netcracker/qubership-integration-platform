package org.qubership.integration.platform.ai.qipknowledge.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

/** Metadata for one RAG-indexable document chunk from a QIP knowledge pack. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QipKnowledgeRagChunk(
    String chunkId,
    String sourcePath,
    QipKnowledgePackVersion packVersion,
    QipKnowledgeCapabilityPhase phase,
    List<String> capabilityIds,
    String title,
    int ordinal,
    String content) {}
