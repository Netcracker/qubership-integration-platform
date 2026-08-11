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
import org.qubership.integration.platform.ai.qipknowledge.rag.QipKnowledgeRagIngestionManifest;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;

/** Loads pre-built QIP knowledge indexes from a filesystem directory. */
public class FilesystemQipKnowledgePackRepository implements QipKnowledgePackRepository {

  private final Path baseDir;
  private final QipKnowledgePackVersion activeVersion;
  private final QipKnowledgePackIndexLoader loader;

  public FilesystemQipKnowledgePackRepository(Path baseDir, QipKnowledgePackVersion activeVersion) {
    this.baseDir = baseDir;
    this.activeVersion = activeVersion;
    this.loader = new QipKnowledgePackIndexLoader();
  }

  @Override
  public QipKnowledgePackVersion activeVersion() {
    return activeVersion;
  }

  @Override
  public QipKnowledgePackManifest loadManifest() {
    return load(loader::loadManifest);
  }

  @Override
  public CapabilityRegistry loadCapabilityRegistry() {
    return load(loader::loadCapabilityRegistry);
  }

  @Override
  public QipKnowledgeRagIngestionManifest loadRagIngestionManifest() {
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
  public PipelineCompatibilityReport loadPipelineCompatibilityReport() {
    return load(loader::loadPipelineCompatibilityReport);
  }

  @Override
  public List<UnsupportedQipKnowledgeItem> loadUnsupportedItems() {
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
    return QipKnowledgePackIndexLoader.resolveVersionDir(baseDir, activeVersion);
  }

  @FunctionalInterface
  private interface LoaderAction<T> {
    T load(Path versionDir) throws IOException;
  }
}
