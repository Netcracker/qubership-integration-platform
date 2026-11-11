package org.qubership.integration.platform.ai.qipknowledge.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import java.util.List;

/** Build-time metadata manifest for future RAG ingestion of QIP knowledge pack documents. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CipKnowledgeRagIngestionManifest(
    CipKnowledgePackVersion version, List<CipKnowledgeRagChunk> chunks) {}
