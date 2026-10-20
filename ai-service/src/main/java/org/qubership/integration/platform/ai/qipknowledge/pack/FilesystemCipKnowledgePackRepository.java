package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReport;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.productpipeline.packageindex.ProductPipelinePackageIndex;
import org.qubership.integration.platform.ai.qipknowledge.rag.CipKnowledgeRagIngestionManifest;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;
import org.qubership.integration.platform.ai.qipknowledge.support.ElementSupportMatrix;

/** Loads pre-built QIP knowledge indexes from a filesystem directory. */
public class FilesystemCipKnowledgePackRepository implements CipKnowledgePackRepository {

  private final Path baseDir;
  private final CipKnowledgePackVersion activeVersion;
  private final CipKnowledgePackIndexLoader loader;

  public FilesystemCipKnowledgePackRepository(Path baseDir, CipKnowledgePackVersion activeVersion) {
    this.baseDir = baseDir;
    this.activeVersion = activeVersion;
    this.loader = new CipKnowledgePackIndexLoader();
  }

  @Override
  public CipKnowledgePackVersion activeVersion() {
    return activeVersion;
  }

  @Override
  public CipKnowledgePackManifest loadManifest() {
    return load(loader::loadManifest);
  }

  @Override
  public CapabilityRegistry loadCapabilityRegistry() {
    return load(loader::loadCapabilityRegistry);
  }

  @Override
  public CipKnowledgeRagIngestionManifest loadRagIngestionManifest() {
    return load(loader::loadRagIngestionManifest);
  }

  @Override
  public CompilerGeneratorPolicy loadCompilerGeneratorPolicy() {
    return load(loader::loadCompilerGeneratorPolicy);
  }

  @Override
  public CompilerSkillCatalog loadCompilerSkillCatalog() {
    return load(loader::loadCompilerSkillCatalog);
  }

  @Override
  public CompilerGeneratorSpecIndex loadCompilerGeneratorSpecIndex() {
    return load(loader::loadCompilerGeneratorSpecIndex);
  }

  @Override
  public CompilerRuntimePackageIndex loadCompilerRuntimePackageIndex() {
    return load(loader::loadCompilerRuntimePackageIndex);
  }

  @Override
  public CompilerPipelineIndex loadCompilerPipelineIndex() {
    return load(loader::loadCompilerPipelineIndex);
  }

  @Override
  public ElementSupportMatrix loadElementSupportMatrix() {
    return load(loader::loadElementSupportMatrix);
  }

  @Override
  public PipelineCompatibilityReport loadPipelineCompatibilityReport() {
    return load(loader::loadPipelineCompatibilityReport);
  }

  @Override
  public List<UnsupportedCipKnowledgeItem> loadUnsupportedItems() {
    return load(loader::loadUnsupportedItems);
  }

  @Override
  public List<String> loadRuntimePromotedSkillIds() {
    return load(loader::loadRuntimePromotedSkillIds);
  }

  @Override
  public ProductPipelinePackageIndex loadProductPipelinePackageIndex() {
    return load(loader::loadProductPipelinePackageIndex);
  }

  private <T> T load(LoaderAction<T> action) {
    try {
      return action.load(versionDir());
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to load QIP knowledge indexes for version " + activeVersion.normalized(), e);
    }
  }

  private Path versionDir() {
    return CipKnowledgePackIndexLoader.resolveVersionDir(baseDir, activeVersion);
  }

  @FunctionalInterface
  private interface LoaderAction<T> {
    T load(Path versionDir) throws IOException;
  }
}
