package org.qubership.integration.platform.ai.qipknowledge.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.pack.ScannedQipKnowledgeFile;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;

/** Builds skill-only RAG ingestion metadata from an ingested QIP knowledge pack. */
public class QipKnowledgeRagManifestBuilder {

  public QipKnowledgeRagIngestionManifest build(QipKnowledgePackIngestionResult result) {
    QipKnowledgePackVersion version = result.manifest().version();
    Map<String, String> contentByPath = contentByPath(result.files());
    List<QipKnowledgeRagChunk> chunks = new ArrayList<>();
    for (CapabilityDescriptor capability : result.registry().capabilities()) {
      String sourcePath = "skills/" + capability.sourceSkillId() + "/SKILL.md";
      chunks.add(
          new QipKnowledgeRagChunk(
              capability.sourceSkillId(),
              sourcePath,
              version,
              capability.phase(),
              List.of(capability.id()),
              capability.sourceSkillId(),
              0,
              contentByPath.getOrDefault(sourcePath, "")));
    }
    chunks.sort(
        Comparator.comparing(QipKnowledgeRagChunk::sourcePath)
            .thenComparing(QipKnowledgeRagChunk::chunkId));
    List<QipKnowledgeRagChunk> indexed = new ArrayList<>();
    for (int index = 0; index < chunks.size(); index++) {
      QipKnowledgeRagChunk chunk = chunks.get(index);
      indexed.add(
          new QipKnowledgeRagChunk(
              chunk.chunkId(),
              chunk.sourcePath(),
              chunk.packVersion(),
              chunk.phase(),
              chunk.capabilityIds(),
              chunk.title(),
              index,
              chunk.content()));
    }
    return new QipKnowledgeRagIngestionManifest(version, List.copyOf(indexed));
  }

  private static Map<String, String> contentByPath(List<ScannedQipKnowledgeFile> files) {
    Map<String, String> byPath = new HashMap<>();
    for (ScannedQipKnowledgeFile file : files) {
      byPath.put(file.relativePath(), file.content());
    }
    return Map.copyOf(byPath);
  }
}
