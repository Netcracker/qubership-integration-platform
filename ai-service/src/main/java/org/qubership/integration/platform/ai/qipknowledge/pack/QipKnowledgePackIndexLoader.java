package org.qubership.integration.platform.ai.qipknowledge.pack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

/** Loads pre-built QIP knowledge pack indexes from a version directory. */
public class QipKnowledgePackIndexLoader {

  static final String MANIFEST_FILE = "qip-knowledge-pack-manifest.json";
  static final String CAPABILITY_REGISTRY_FILE = "capability-registry.json";
  static final String UNSUPPORTED_ITEMS_FILE = "unsupported-items.json";
  static final String COMPATIBILITY_REPORT_FILE = "compatibility-report.md";
  static final String RAG_INGESTION_MANIFEST_FILE = "rag-ingestion-manifest.json";
  static final String COMPILER_SKILL_CATALOG_FILE = "compiler-skill-catalog.json";
  static final String COMPILER_GENERATOR_SPEC_INDEX_FILE = "compiler-generator-spec-index.json";
  static final String COMPILER_RUNTIME_PACKAGE_INDEX_FILE = "compiler-runtime-package-index.json";
  static final String COMPILER_GENERATOR_POLICY_FILE = "compiler-generator-policy.json";
  static final String COMPILER_PIPELINE_INDEX_FILE = "compiler-pipeline-index.json";
  static final String PIPELINE_COMPATIBILITY_REPORT_FILE = "pipeline-compatibility-report.json";
  static final String RUNTIME_PROMOTED_SKILLS_FILE = "runtime-promoted-skills.json";
  public static final String PRODUCT_PIPELINE_PACKAGE_INDEX_FILE =
      "product-pipeline-package-index.json";

  private final ObjectMapper objectMapper;

  public QipKnowledgePackIndexLoader() {
    this(new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule()));
  }

  QipKnowledgePackIndexLoader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public QipKnowledgePackManifest loadManifest(Path versionDir) throws IOException {
    return readJson(versionDir.resolve(MANIFEST_FILE), QipKnowledgePackManifest.class);
  }

  public CapabilityRegistry loadCapabilityRegistry(Path versionDir) throws IOException {
    return readJson(versionDir.resolve(CAPABILITY_REGISTRY_FILE), CapabilityRegistry.class);
  }

  public List<UnsupportedQipKnowledgeItem> loadUnsupportedItems(Path versionDir) throws IOException {
    return objectMapper.readValue(
        Files.readString(versionDir.resolve(UNSUPPORTED_ITEMS_FILE)),
        new TypeReference<List<UnsupportedQipKnowledgeItem>>() {});
  }

  public String loadCompatibilityReport(Path versionDir) throws IOException {
    return Files.readString(versionDir.resolve(COMPATIBILITY_REPORT_FILE));
  }

  public QipKnowledgeRagIngestionManifest loadRagIngestionManifest(Path versionDir)
      throws IOException {
    return readJson(
        versionDir.resolve(RAG_INGESTION_MANIFEST_FILE), QipKnowledgeRagIngestionManifest.class);
  }

  public CompilerSkillCatalog loadCompilerSkillCatalog(Path versionDir) throws IOException {
    return readJson(versionDir.resolve(COMPILER_SKILL_CATALOG_FILE), CompilerSkillCatalog.class);
  }

  public CompilerGeneratorSpecIndex loadCompilerGeneratorSpecIndex(Path versionDir)
      throws IOException {
    return readJson(
        versionDir.resolve(COMPILER_GENERATOR_SPEC_INDEX_FILE), CompilerGeneratorSpecIndex.class);
  }

  public CompilerRuntimePackageIndex loadCompilerRuntimePackageIndex(Path versionDir)
      throws IOException {
    return readJson(
        versionDir.resolve(COMPILER_RUNTIME_PACKAGE_INDEX_FILE),
        CompilerRuntimePackageIndex.class);
  }

  public CompilerGeneratorPolicy loadCompilerGeneratorPolicy(Path versionDir) throws IOException {
    return readJson(
        versionDir.resolve(COMPILER_GENERATOR_POLICY_FILE), CompilerGeneratorPolicy.class);
  }

  public CompilerPipelineIndex loadCompilerPipelineIndex(Path versionDir) throws IOException {
    return readJson(versionDir.resolve(COMPILER_PIPELINE_INDEX_FILE), CompilerPipelineIndex.class);
  }

  public PipelineCompatibilityReport loadPipelineCompatibilityReport(Path versionDir)
      throws IOException {
    return readJson(
        versionDir.resolve(PIPELINE_COMPATIBILITY_REPORT_FILE), PipelineCompatibilityReport.class);
  }

  public ProductPipelinePackageIndex loadProductPipelinePackageIndex(Path versionDir)
      throws IOException {
    return readJson(
        versionDir.resolve(PRODUCT_PIPELINE_PACKAGE_INDEX_FILE), ProductPipelinePackageIndex.class);
  }

  public List<String> loadRuntimePromotedSkillIds(Path versionDir) throws IOException {
    Path file = versionDir.resolve(RUNTIME_PROMOTED_SKILLS_FILE);
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    return objectMapper.readValue(Files.readString(file), new TypeReference<List<String>>() {});
  }

  private <T> T readJson(Path file, Class<T> type) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IOException("Missing QIP knowledge index file: " + file);
    }
    return objectMapper.readValue(Files.readString(file), type);
  }

  static Path resolveVersionDir(Path baseDir, QipKnowledgePackVersion version) {
    return baseDir.resolve(version.normalized());
  }

  static InputStream openClasspathResource(ClassLoader classLoader, String resourcePath) {
    InputStream stream = classLoader.getResourceAsStream(resourcePath);
    if (stream == null) {
      throw new IllegalStateException("Missing classpath QIP knowledge resource: " + resourcePath);
    }
    return stream;
  }
}
