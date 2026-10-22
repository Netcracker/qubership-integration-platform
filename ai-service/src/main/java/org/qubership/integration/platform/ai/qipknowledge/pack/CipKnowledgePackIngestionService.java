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
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
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
import org.qubership.integration.platform.ai.qipknowledge.rag.CipKnowledgeRagIngestionManifest;
import org.qubership.integration.platform.ai.qipknowledge.rag.CipKnowledgeRagManifestBuilder;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityClassifier;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;
import org.qubership.integration.platform.ai.qipknowledge.skill.SkillDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.SkillParser;
import org.qubership.integration.platform.ai.qipknowledge.support.ElementSupportMatrix;
import org.qubership.integration.platform.ai.qipknowledge.support.ElementSupportMatrixBuilder;

/** Orchestrates deterministic ingestion of one QIP skill pack. */
public class CipKnowledgePackIngestionService {

  private static final ObjectMapper ARTIFACT_MAPPER =
      new ObjectMapper()
          .registerModule(new Jdk8Module())
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final CipKnowledgePackScanner scanner;
  private final SkillParser skillParser;
  private final CapabilityClassifier classifier;
  private final CipKnowledgePackCompatibilityReporter reporter;
  private final CipKnowledgeRagManifestBuilder ragManifestBuilder;

  public CipKnowledgePackIngestionService() {
    this(
        new CipKnowledgePackScanner(),
        new SkillParser(),
        new CapabilityClassifier(),
        new CipKnowledgePackCompatibilityReporter(),
        new CipKnowledgeRagManifestBuilder());
  }

  CipKnowledgePackIngestionService(
      CipKnowledgePackScanner scanner,
      SkillParser skillParser,
      CapabilityClassifier classifier,
      CipKnowledgePackCompatibilityReporter reporter,
      CipKnowledgeRagManifestBuilder ragManifestBuilder) {
    this.scanner = scanner;
    this.skillParser = skillParser;
    this.classifier = classifier;
    this.reporter = reporter;
    this.ragManifestBuilder = ragManifestBuilder;
  }

  public CipKnowledgePackIngestionResult ingest(Path packRoot) {
    CipKnowledgePackScanResult scanResult = scanner.scan(packRoot);
    CompilerSkillCatalog skillCatalog = new CompilerSkillCatalogLoader().load(scanResult);

    List<SkillDescriptor> skills = new ArrayList<>();
    for (ScannedCipKnowledgeFile file : scanResult.files()) {
      if (file.kind() == CipKnowledgePackFileKind.SKILL) {
        skills.add(skillParser.parse(file));
      }
    }
    skills.sort((a, b) -> a.skillId().compareTo(b.skillId()));

    List<CapabilityDescriptor> capabilities = new ArrayList<>();
    List<UnsupportedCipKnowledgeItem> unsupportedItems = new ArrayList<>();
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
            new UnsupportedCipKnowledgeItem(
                capability.id(), skill.sourcePath(), capability.reasonIfUnsupported()));
      }
    }

    Path stagesPath = packRoot.resolve("runtime/java-stages.json");
    if (Files.isRegularFile(stagesPath)) {
      try {
        var stages = ARTIFACT_MAPPER.readValue(stagesPath.toFile(), CapabilityDescriptor[].class);
        for (var stage : stages) {
          if (capabilities.stream().anyMatch(value -> value.id().equals(stage.id()))) {
            throw new IllegalArgumentException("Duplicate capability: " + stage.id());
          }
          capabilities.add(stage);
          (stage.supported() ? supportedIds : unsupportedIds).add(stage.id());
        }
      } catch (IOException e) {
        throw new IllegalStateException("Failed to load Java stage metadata", e);
      }
    }
    capabilities.sort(java.util.Comparator.comparing(CapabilityDescriptor::id));
    supportedIds.sort(String::compareTo);
    unsupportedIds.sort(String::compareTo);
    CapabilityRegistry registry =
        new CapabilityRegistry(scanResult.version(), List.copyOf(capabilities));

    Map<String, String> checksums = new TreeMap<>();
    for (ScannedCipKnowledgeFile file : scanResult.files()) {
      checksums.put(file.relativePath(), file.sha256());
    }

    CipKnowledgePackManifest manifest =
        new CipKnowledgePackManifest(
            scanResult.version(),
            scanResult.packRoot().toString(),
            Instant.now(),
            Map.copyOf(checksums),
            skills.stream().map(CipKnowledgePackIngestionService::skillId).toList(),
            List.copyOf(supportedIds),
            List.copyOf(unsupportedIds));

    return new CipKnowledgePackIngestionResult(
        manifest,
        registry,
        List.copyOf(unsupportedItems),
        reporter.buildReport(
            manifest, registry, List.copyOf(unsupportedItems), skillCatalog),
        scanResult.files());
  }

  public void writeArtifacts(CipKnowledgePackIngestionResult result, Path outputDir)
      throws IOException {
    writeArtifacts(result, outputDir, null);
  }

  public void writeArtifacts(
      CipKnowledgePackIngestionResult result, Path outputDir, Path addonPackRoot)
      throws IOException {
    Path versionDir =
        CipKnowledgePackIndexLoader.resolveVersionDir(outputDir, result.manifest().version());
    Files.createDirectories(versionDir);
    CipKnowledgePackScanResult writeScanResult =
        new CipKnowledgePackScanResult(
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
    writeJson(versionDir.resolve(CipKnowledgePackIndexLoader.MANIFEST_FILE), overlay.manifest());
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.CAPABILITY_REGISTRY_FILE),
        overlay.registry());
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.UNSUPPORTED_ITEMS_FILE),
        overlay.unsupportedItems());
    CipKnowledgeRagIngestionManifest ragManifest = ragManifestBuilder.build(result);
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.RAG_INGESTION_MANIFEST_FILE), ragManifest);
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_SKILL_CATALOG_FILE),
        overlay.catalog());
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.RUNTIME_PROMOTED_SKILLS_FILE),
        overlay.runtimePromotedSkillIds());
    CompilerGeneratorSpecIndex specIndex =
        new CompilerGeneratorSpecIndexBuilder().build(writeScanResult);
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_GENERATOR_SPEC_INDEX_FILE),
        specIndex);
    CompilerRuntimePackageIndex runtimePackageIndex =
        new CompilerRuntimePackageIndexLoader().load(writeScanResult);
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_RUNTIME_PACKAGE_INDEX_FILE),
        runtimePackageIndex);
    CompilerGeneratorPolicy policy =
        new CompilerGeneratorPolicyBuilder()
            .build(writeScanResult, overlay.registry(), overlay.catalog(), specIndex, addonPackRoot)
            .policy();
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_GENERATOR_POLICY_FILE), policy);
    CompilerPipelineIndex pipelineIndex =
        new CompilerPipelineIndexBuilder().build(writeScanResult, policy, addonPackRoot);
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.COMPILER_PIPELINE_INDEX_FILE),
        pipelineIndex);
    Path packRoot = Path.of(result.manifest().sourcePath());
    Path repoRoot = repositoryRoot(packRoot);
    CompilerContract compilerContract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    ElementSupportMatrix supportMatrix =
        new ElementSupportMatrixBuilder().build(repoRoot, pipelineIndex, compilerContract);
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.ELEMENT_SUPPORT_MATRIX_FILE),
        supportMatrix);
    PipelineCompatibilityReport compatibilityReport =
        new CompilerPipelineCompatibilityAnalyzer()
            .compare(loadPreviousCertifiedPipelineIndex(), pipelineIndex);
    writeJson(
        versionDir.resolve(CipKnowledgePackIndexLoader.PIPELINE_COMPATIBILITY_REPORT_FILE),
        compatibilityReport);
    writeProductPipelineIndexes(
        packRoot,
        versionDir,
        policy);
    Files.writeString(
        versionDir.resolve(CipKnowledgePackIndexLoader.COMPATIBILITY_REPORT_FILE),
        result.compatibilityReportMarkdown());
  }

  private static Path repositoryRoot(Path packRoot) {
    Path parent = packRoot.toAbsolutePath().normalize().getParent();
    if (parent == null) return packRoot;
    return ".private".equals(parent.getFileName().toString()) ? parent.getParent() : parent;
  }

  private void writeProductPipelineIndexes(
      Path packRoot, Path versionDir, CompilerGeneratorPolicy policy) throws IOException {
    Path productPipelines = ProductPipelinePackageIndexBuilder.productPipelinesRoot(packRoot);
    if (!Files.isDirectory(productPipelines)) {
      return;
    }
    Path repoRoot = repositoryRoot(packRoot);
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
        versionDir.resolve(CipKnowledgePackIndexLoader.PRODUCT_PIPELINE_PACKAGE_INDEX_FILE),
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
