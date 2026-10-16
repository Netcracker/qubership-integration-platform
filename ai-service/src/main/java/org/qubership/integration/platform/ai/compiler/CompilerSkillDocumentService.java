package org.qubership.integration.platform.ai.compiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.pipeline.InternalPipelineSkills;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.rag.QipKnowledgeRagChunk;
import org.qubership.integration.platform.ai.qipknowledge.rag.QipKnowledgeRagIngestionManifest;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;

/** Loads full compiler skill documents from pre-built QIP knowledge pack indexes. */
@ApplicationScoped
public class CompilerSkillDocumentService {

  private final QipKnowledgePackRepository repository;

  @Inject
  public CompilerSkillDocumentService(QipKnowledgePackRepository repository) {
    this.repository = repository;
  }

  public CompilerSkillDocument loadByCapabilityId(String capabilityId) {
    Objects.requireNonNull(capabilityId, "capabilityId");
    String key = capabilityId.trim();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("capabilityId is required");
    }

    return InternalPipelineSkills.document(key)
        .orElseGet(() -> loadPackDocument(key));
  }

  private CompilerSkillDocument loadPackDocument(String key) {
    CapabilityDescriptor capability = findCapability(repository.loadCapabilityRegistry(), key);
    String sourcePath = "skills/" + capability.sourceSkillId() + "/SKILL.md";
    QipKnowledgeRagChunk chunk = findSkillChunk(repository.loadRagIngestionManifest(), sourcePath, key);

    return new CompilerSkillDocument(
        capability.id(),
        capability.sourceSkillId(),
        sourcePath,
        chunk.title(),
        capability.phase(),
        capability.supported(),
        repository.activeVersion(),
        chunk.content());
  }

  private static CapabilityDescriptor findCapability(CapabilityRegistry registry, String capabilityId) {
    return registry.capabilities().stream()
        .filter(
            capability ->
                capabilityId.equals(capability.id())
                    || capabilityId.equals(capability.sourceSkillId()))
        .findFirst()
        .orElseThrow(() -> new CompilerSkillNotFoundException(capabilityId));
  }

  private static QipKnowledgeRagChunk findSkillChunk(
      QipKnowledgeRagIngestionManifest manifest, String sourcePath, String capabilityId) {
    return manifest.chunks().stream()
        .filter(chunk -> sourcePath.equals(chunk.sourcePath()))
        .filter(chunk -> chunk.capabilityIds().contains(capabilityId))
        .findFirst()
        .or(
            () ->
                manifest.chunks().stream()
                    .filter(chunk -> sourcePath.equals(chunk.sourcePath()))
                    .findFirst())
        .orElseThrow(() -> new CompilerSkillNotFoundException(capabilityId));
  }
}
