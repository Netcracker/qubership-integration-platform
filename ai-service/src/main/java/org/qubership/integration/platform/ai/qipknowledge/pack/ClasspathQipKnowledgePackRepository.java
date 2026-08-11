package org.qubership.integration.platform.ai.qipknowledge.pack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

/** Loads pre-built QIP knowledge indexes from classpath resources. No ingestion at runtime. */
public class ClasspathQipKnowledgePackRepository implements QipKnowledgePackRepository {

  private static final String CLASSPATH_ROOT = "qipknowledge/";

  private final QipKnowledgePackVersion activeVersion;
  private final ClassLoader classLoader;
  private final ObjectMapper objectMapper;

  public ClasspathQipKnowledgePackRepository(QipKnowledgePackVersion activeVersion) {
    this(
        activeVersion,
        ClasspathQipKnowledgePackRepository.class.getClassLoader(),
        new ObjectMapper());
  }

  ClasspathQipKnowledgePackRepository(
      QipKnowledgePackVersion activeVersion, ClassLoader classLoader, ObjectMapper objectMapper) {
    this.activeVersion = activeVersion;
    this.classLoader = classLoader;
    this.objectMapper = objectMapper.registerModule(new JavaTimeModule());
  }

  @Override
  public QipKnowledgePackVersion activeVersion() {
    return activeVersion;
  }

  @Override
  public QipKnowledgePackManifest loadManifest() {
    return readJson(QipKnowledgePackIndexLoader.MANIFEST_FILE, QipKnowledgePackManifest.class);
  }

  @Override
  public CapabilityRegistry loadCapabilityRegistry() {
    return readJson(
        QipKnowledgePackIndexLoader.CAPABILITY_REGISTRY_FILE, CapabilityRegistry.class);
  }

  @Override
  public QipKnowledgeRagIngestionManifest loadRagIngestionManifest() {
    return readJson(
        QipKnowledgePackIndexLoader.RAG_INGESTION_MANIFEST_FILE,
        QipKnowledgeRagIngestionManifest.class);
  }

  @Override
  public CompilerGeneratorPolicy loadCompilerGeneratorPolicy() {
    return readJson(
        QipKnowledgePackIndexLoader.COMPILER_GENERATOR_POLICY_FILE, CompilerGeneratorPolicy.class);
  }

  @Override
  public CompilerSkillCatalog loadCompilerSkillCatalog() {
    return readJson(
        QipKnowledgePackIndexLoader.COMPILER_SKILL_CATALOG_FILE, CompilerSkillCatalog.class);
  }

  @Override
  public CompilerGeneratorSpecIndex loadCompilerGeneratorSpecIndex() {
    return readJson(
        QipKnowledgePackIndexLoader.COMPILER_GENERATOR_SPEC_INDEX_FILE,
        CompilerGeneratorSpecIndex.class);
  }

  @Override
  public CompilerRuntimePackageIndex loadCompilerRuntimePackageIndex() {
    return readJson(
        QipKnowledgePackIndexLoader.COMPILER_RUNTIME_PACKAGE_INDEX_FILE,
        CompilerRuntimePackageIndex.class);
  }

  @Override
  public CompilerPipelineIndex loadCompilerPipelineIndex() {
    return readJson(
        QipKnowledgePackIndexLoader.COMPILER_PIPELINE_INDEX_FILE, CompilerPipelineIndex.class);
  }

  @Override
  public PipelineCompatibilityReport loadPipelineCompatibilityReport() {
    return readJson(
        QipKnowledgePackIndexLoader.PIPELINE_COMPATIBILITY_REPORT_FILE,
        PipelineCompatibilityReport.class);
  }

  @Override
  public List<UnsupportedQipKnowledgeItem> loadUnsupportedItems() {
    try {
      return objectMapper.readValue(
          readResource(QipKnowledgePackIndexLoader.UNSUPPORTED_ITEMS_FILE),
          new TypeReference<List<UnsupportedQipKnowledgeItem>>() {});
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to load unsupported QIP knowledge items for version "
              + activeVersion.normalized(),
          e);
    }
  }

  @Override
  public List<String> loadRuntimePromotedSkillIds() {
    try {
      String resourcePath =
          CLASSPATH_ROOT
              + activeVersion.normalized()
              + "/"
              + QipKnowledgePackIndexLoader.RUNTIME_PROMOTED_SKILLS_FILE;
      try (InputStream stream =
          QipKnowledgePackIndexLoader.openClasspathResource(classLoader, resourcePath)) {
        return objectMapper.readValue(stream, new TypeReference<List<String>>() {});
      }
    } catch (IOException e) {
      return List.of();
    }
  }

  @Override
  public ProductPipelinePackageIndex loadProductPipelinePackageIndex() {
    return readJson(
        QipKnowledgePackIndexLoader.PRODUCT_PIPELINE_PACKAGE_INDEX_FILE,
        ProductPipelinePackageIndex.class);
  }

  private <T> T readJson(String fileName, Class<T> type) {
    try {
      return objectMapper.readValue(readResource(fileName), type);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to load QIP knowledge index "
              + fileName
              + " for version "
              + activeVersion.normalized(),
          e);
    }
  }

  private String readResource(String fileName) throws IOException {
    String resourcePath = CLASSPATH_ROOT + activeVersion.normalized() + "/" + fileName;
    try (InputStream stream =
        QipKnowledgePackIndexLoader.openClasspathResource(classLoader, resourcePath)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
