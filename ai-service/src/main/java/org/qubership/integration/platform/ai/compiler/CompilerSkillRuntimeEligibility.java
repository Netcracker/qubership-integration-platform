package org.qubership.integration.platform.ai.compiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDescriptor;
import org.qubership.integration.platform.ai.compiler.pipeline.InternalPipelineSkills;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;

/** Runtime gate for catalog-backed compiler skill traversal and prompt context. */
@ApplicationScoped
public class CompilerSkillRuntimeEligibility {

  private final QipKnowledgePackRepository repository;

  @Inject
  public CompilerSkillRuntimeEligibility(QipKnowledgePackRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  public boolean allowsRuntimeAccess(String skillId) {
    return catalog().allowsRuntimeAccess(requireSkillId(skillId));
  }

  public void requirePromptContext(String skillId) {
    String key = requireSkillId(skillId);
    if (InternalPipelineSkills.isInternal(key)) {
      return;
    }
    exclusionReason(key)
        .ifPresent(
            reason -> {
              throw new CompilerSkillRuntimeExcludedException(key, reason);
            });
  }

  public Optional<String> exclusionReason(String skillId) {
    String key = requireSkillId(skillId);
    return catalog()
        .find(key)
        .filter(catalog()::excludesFromRuntimePolicy)
        .map(CompilerSkillRuntimeEligibility::exclusionMessage);
  }

  private CompilerSkillCatalog catalog() {
    return repository.loadCompilerSkillCatalog();
  }

  private static String requireSkillId(String skillId) {
    Objects.requireNonNull(skillId, "skillId");
    String key = skillId.trim();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("skillId is required");
    }
    return key;
  }

  private static String exclusionMessage(CompilerSkillDescriptor descriptor) {
    return "Excluded by compiler skill catalog: " + descriptor.disposition();
  }
}
