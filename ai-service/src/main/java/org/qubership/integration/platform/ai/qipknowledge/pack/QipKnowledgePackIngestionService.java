package org.qubership.integration.platform.ai.qipknowledge.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillCatalogAddonOverlay;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalogLoader;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDescriptor;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDisposition;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineCompatibilityAnalyzer;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexBuilder;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReport;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicyBuilder;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndexBuilder;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndexLoader;
import org.qubership.integration.platform.ai.productpipeline.packageindex.ProductPipelinePackageIndex;
import org.qubership.integration.platform.ai.productpipeline.packageindex.ProductPipelinePackageIndexBuilder;
import org.qubership.integration.platform.ai.qipknowledge.rag.QipKnowledgeRagIngestionManifest;
import org.qubership.integration.platform.ai.qipknowledge.rag.QipKnowledgeRagManifestBuilder;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityClassifier;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;
import org.qubership.integration.platform.ai.qipknowledge.skill.SkillDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.SkillParser;

/** Orchestrates deterministic ingestion of one QIP skill pack. */
public class QipKnowledgePackIngestionService {

  private static final ObjectMapper ARTIFACT_MAPPER =
      new ObjectMapper()
          .registerModule(new Jdk8Module())
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final QipKnowledgePackScanner scanner;
  private final SkillParser skillParser;
  private final CapabilityClassifier classifier;
  private final QipKnowledgePackCompatibilityReporter reporter;
  private final QipKnowledgeRagManifestBuilder ragManifestBuilder;

  public QipKnowledgePackIngestionService() {
    this(
        new QipKnowledgePackScanner(),
        new SkillParser(),
        new CapabilityClassifier(),
        new QipKnowledgePackCompatibilityReporter(),
        new QipKnowledgeRagManifestBuilder());
  }

  QipKnowledgePackIngestionService(
      QipKnowledgePackScanner scanner,
      SkillParser skillParser,
      CapabilityClassifier classifier,
      QipKnowledgePackCompatibilityReporter reporter,
      QipKnowledgeRagManifestBuilder ragManifestBuilder) {
    this.scanner = scanner;
    this.skillParser = skillParser;
    this.classifier = classifier;
    this.reporter = reporter;
    this.ragManifestBuilder = ragManifestBuilder;
  }

  public QipKnowledgePackIngestionResult ingest(Path packRoot) {
    QipKnowledgePackScanResult scanResult = scanner.scan(packRoot);
    CompilerSkillCatalog skillCatalog = new CompilerSkillCatalogLoader().load(scanResult);

    List<SkillDescriptor> skills = new ArrayList<>();
    for (ScannedQipKnowledgeFile file : scanResult.files()) {
      if (file.kind() == QipKnowledgePackFileKind.SKILL) {
        skills.add(skillParser.parse(file));
      }
    }
    skills.sort((a, b) -> a.skillId().compareTo(b.skillId()));

    List<CapabilityDescriptor> capabilities = new ArrayList<>();
    List<UnsupportedQipKnowledgeItem> unsupportedItems = new ArrayList<>();
    List<String> supportedIds = new ArrayList<>();
    List<String> unsupportedIds = new ArrayList<>();

    for (SkillDescriptor skill : skills) {
      CapabilityDescriptor capability = classifier.toCapability(skill, scanResult.version());
      capability = applyCatalogExclusion(skillCatalog, skill.skillId(), capability);
      capabilities.add(capability);
      if (capability.supported()) {
        supportedIds.add(capability.id());
      } else {
        unsupportedIds.add(capability.id());
        unsupportedItems.add(
            new UnsupportedQipKnowledgeItem(
                capability.id(), skill.sourcePath(), capability.reasonIfUnsupported()));
      }
    }

    CapabilityRegistry registry =
        new CapabilityRegistry(scanResult.version(), List.copyOf(capabilities));

    Map<String, String> checksums = new TreeMap<>();
    for (ScannedQipKnowledgeFile file : scanResult.files()) {
      checksums.put(file.relativePath(), file.sha256());
    }

    QipKnowledgePackManifest manifest =
        new QipKnowledgePackManifest(
            scanResult.version(),
            scanResult.packRoot().toString(),
            Instant.now(),
            Map.copyOf(checksums),
            skills.stream().map(QipKnowledgePackIngestionService::skillId).toList(),
            List.copyOf(supportedIds),
            List.copyOf(unsupportedIds));

    return new QipKnowledgePackIngestionResult(
        manifest,
        registry,
        List.copyOf(unsupportedItems),
        reporter.buildReport(
            manifest, registry, List.copyOf(unsupportedItems), skillCatalog),
        scanResult.files());
  }

  public void writeArtifacts(QipKnowledgePackIngestionResult result, Path outputDir)
      throws IOException {
    writeArtifacts(result, outputDir, null);
  }

  public void writeArtifacts(
      QipKnowledgePackIngestionResult result, Path outputDir, Path addonPackRoot)
      throws IOException {
    Path versionDir =
        QipKnowledgePackIndexLoader.resolveVersionDir(outputDir, result.manifest().version());
    Files.createDirectories(versionDir);
    QipKnowledgePackScanResult writeScanResult =
        new QipKnowledgePackScanResult(
            Path.of(result.manifest().sourcePath()), result.manifest().version(), result.files());
    CompilerSkillCatalog skillCatalog = new CompilerSkillCatalogLoader().load(writeScanResult);
    CompilerSkillCatalogAddonOverlay.OverlayResult overlay =
        new CompilerSkillCatalogAddonOverlay()
            .apply(
                skillCatalog,
                result.registry(),
                result.manifest(),
                result.unsupportedItems(),
                addonPackRoot);
    writeJson(versionDir.resolve(QipKnowledgePackIndexLoader.MANIFEST_FILE), overlay.manifest());
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.CAPABILITY_REGISTRY_FILE),
        overlay.registry());
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.UNSUPPORTED_ITEMS_FILE),
        overlay.unsupportedItems());
    QipKnowledgeRagIngestionManifest ragManifest = ragManifestBuilder.build(result);
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.RAG_INGESTION_MANIFEST_FILE), ragManifest);
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_SKILL_CATALOG_FILE),
        overlay.catalog());
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.RUNTIME_PROMOTED_SKILLS_FILE),
        overlay.runtimePromotedSkillIds());
    CompilerGeneratorSpecIndex specIndex =
        new CompilerGeneratorSpecIndexBuilder().build(writeScanResult);
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_GENERATOR_SPEC_INDEX_FILE),
        specIndex);
    CompilerRuntimePackageIndex runtimePackageIndex =
        new CompilerRuntimePackageIndexLoader().load(writeScanResult);
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_RUNTIME_PACKAGE_INDEX_FILE),
        runtimePackageIndex);
    CompilerGeneratorPolicy policy =
        new CompilerGeneratorPolicyBuilder()
            .build(writeScanResult, overlay.registry(), overlay.catalog(), specIndex, addonPackRoot)
            .policy();
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_GENERATOR_POLICY_FILE), policy);
    CompilerPipelineIndex pipelineIndex =
        new CompilerPipelineIndexBuilder().build(writeScanResult, policy, addonPackRoot);
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.COMPILER_PIPELINE_INDEX_FILE),
        pipelineIndex);
    PipelineCompatibilityReport compatibilityReport =
        new CompilerPipelineCompatibilityAnalyzer()
            .compare(loadPreviousCertifiedPipelineIndex(), pipelineIndex);
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.PIPELINE_COMPATIBILITY_REPORT_FILE),
        compatibilityReport);
    writeProductPipelineIndexes(
        Path.of(result.manifest().sourcePath()).getParent() == null
            ? Path.of(result.manifest().sourcePath())
            : Path.of(result.manifest().sourcePath()),
        versionDir,
        policy);
    Files.writeString(
        versionDir.resolve(QipKnowledgePackIndexLoader.COMPATIBILITY_REPORT_FILE),
        result.compatibilityReportMarkdown());
  }

  private void writeProductPipelineIndexes(
      Path packRoot, Path versionDir, CompilerGeneratorPolicy policy) throws IOException {
    Path productPipelines = packRoot.resolve("product-pipelines");
    if (!Files.isDirectory(productPipelines)) {
      return;
    }
    Path repoRoot = packRoot.getParent() == null ? packRoot : packRoot.getParent();
    List<String> dynamicSkills =
        policy == null || policy.generators() == null
            ? List.of()
            : policy.generators().stream()
                .map(
                    org.qubership.integration.platform.ai.compiler.policy
                            .CompilerGeneratorDescriptor
                        ::skillId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    ProductPipelinePackageIndex index =
        new ProductPipelinePackageIndexBuilder().build(repoRoot, packRoot, dynamicSkills);
    writeJson(
        versionDir.resolve(QipKnowledgePackIndexLoader.PRODUCT_PIPELINE_PACKAGE_INDEX_FILE),
        index);
  }

  private static CapabilityDescriptor applyCatalogExclusion(
      CompilerSkillCatalog skillCatalog, String skillId, CapabilityDescriptor capability) {
    return skillCatalog
        .find(skillId)
        .filter(skillCatalog::excludesFromRuntimePolicy)
        .map(entry -> toCatalogExcludedCapability(capability, entry))
        .orElse(capability);
  }

  private static CompilerPipelineIndex loadPreviousCertifiedPipelineIndex() {
    String path = System.getProperty("qip.ai.qipknowledge.previous-certified-pipeline-index");
    if (path == null || path.isBlank()) {
      return null;
    }
    Path file = Path.of(path).normalize().toAbsolutePath();
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException(
          "previous certified pipeline index is not a regular file: " + file);
    }
    try {
      return ARTIFACT_MAPPER.readValue(Files.readString(file), CompilerPipelineIndex.class);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to read previous certified pipeline index: " + file, e);
    }
  }

  private static CapabilityDescriptor toCatalogExcludedCapability(
      CapabilityDescriptor capability, CompilerSkillDescriptor entry) {
    return new CapabilityDescriptor(
        capability.id(),
        capability.sourceSkillId(),
        capability.packVersion(),
        capability.phase(),
        false,
        catalogExclusionReason(entry.disposition()),
        capability.requiredTools(),
        capability.executionOrderHints());
  }

  private static String catalogExclusionReason(CompilerSkillDisposition disposition) {
    return "Excluded by compiler skill catalog: " + disposition;
  }

  private static void writeJson(Path file, Object value) throws IOException {
    ARTIFACT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
  }

  private static String skillId(SkillDescriptor skill) {
    return skill.skillId();
  }
}
