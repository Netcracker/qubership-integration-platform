package org.qubership.integration.platform.ai.qipknowledge.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackIngestionResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.pack.ScannedCipKnowledgeFile;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;

/** Builds skill-only RAG ingestion metadata from an ingested QIP knowledge pack. */
public class CipKnowledgeRagManifestBuilder {

  public CipKnowledgeRagIngestionManifest build(CipKnowledgePackIngestionResult result) {
    CipKnowledgePackVersion version = result.manifest().version();
    Map<String, String> contentByPath = contentByPath(result.files());
    List<CipKnowledgeRagChunk> chunks = new ArrayList<>();
    for (CapabilityDescriptor capability : result.registry().capabilities()) {
      String sourcePath = "skills/" + capability.sourceSkillId() + "/SKILL.md";
      if (!contentByPath.containsKey(sourcePath)) continue;
      chunks.add(
          new CipKnowledgeRagChunk(
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
        Comparator.comparing(CipKnowledgeRagChunk::sourcePath)
            .thenComparing(CipKnowledgeRagChunk::chunkId));
    List<CipKnowledgeRagChunk> indexed = new ArrayList<>();
    for (int index = 0; index < chunks.size(); index++) {
      CipKnowledgeRagChunk chunk = chunks.get(index);
      indexed.add(
          new CipKnowledgeRagChunk(
              chunk.chunkId(),
              chunk.sourcePath(),
              chunk.packVersion(),
              chunk.phase(),
              chunk.capabilityIds(),
              chunk.title(),
              index,
              chunk.content()));
    }
    return new CipKnowledgeRagIngestionManifest(version, List.copyOf(indexed));
  }

  private static Map<String, String> contentByPath(List<ScannedCipKnowledgeFile> files) {
    Map<String, String> byPath = new HashMap<>();
    for (ScannedCipKnowledgeFile file : files) {
      byPath.put(file.relativePath(), file.content());
    }
    return Map.copyOf(byPath);
  }
}
