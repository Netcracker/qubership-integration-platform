package org.qubership.integration.platform.ai.productpipeline.packageindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Builds the product-pipeline package index from skill/addon/rule pins. */
public final class ProductPipelinePackageIndexBuilder {

  public static final String PACKAGE_INDEX_FILE = "product-pipeline-package-index.json";

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private final ProductPipelineDependencyResolver resolver = new ProductPipelineDependencyResolver();

  public ProductPipelinePackageIndex build(
      Path repoRoot, Path packRoot, List<String> dynamicSkills) {
    Path productPipelines = packRoot.resolve("product-pipelines");
    ReferenceBaseline baseline =
        loadBaseline(productPipelines.resolve("reference-baseline-v1.yaml"));
    List<CapabilityManifest> capabilities =
        resolver.loadCapabilities(productPipelines);
    List<DependencyPin> pins =
        resolver.resolveClosure(repoRoot, capabilities, dynamicSkills);
    String closureDigest = resolver.closureDigest(pins);
    String baselineDigest = digestBaseline(baseline, pins);
    ProductPipelinePackageIndex index =
        new ProductPipelinePackageIndex(
            baseline.baselineId(),
            baselineDigest,
            closureDigest,
            "1.0.0",
            "24.4",
            capabilities,
            pins,
            baseline.artifacts());
    ReferenceBaselineValidator.validateTarget(baseline, repoRoot, pins);
    return index;
  }

  private ReferenceBaseline loadBaseline(Path baselineFile) {
    try {
      var tree = YAML.readTree(baselineFile.toFile());
      List<ReferenceArtifact> artifacts = new ArrayList<>();
      if (tree.path("artifacts").isArray()) {
        for (var node : tree.path("artifacts")) {
          artifacts.add(
              new ReferenceArtifact(
                  node.path("dependencyId").asText(),
                  node.path("kind").asText(),
                  optionalText(node, "referencePath"),
                  node.path("targetPath").asText(),
                  optionalText(node, "sourceSha256"),
                  node.path("targetSha256").asText(),
                  ReferenceDisposition.valueOf(node.path("disposition").asText("ADOPTED")),
                  optionalText(node, "adaptationReason")));
        }
      }
      return new ReferenceBaseline(
          tree.path("schemaVersion").asInt(1),
          tree.path("baselineId").asText(),
          tree.path("baselineVersion").asText(),
          tree.path("root").asText(),
          YAML.convertValue(tree.path("referenceEvidence"), java.util.Map.class),
          YAML.convertValue(tree.path("mapping"), java.util.Map.class),
          artifacts);
    } catch (Exception e) {
      throw new IllegalStateException("cannot load reference baseline " + baselineFile, e);
    }
  }

  private static Optional<String> optionalText(
      com.fasterxml.jackson.databind.JsonNode node, String field) {
    String value = node.path(field).asText(null);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  private static String digestBaseline(ReferenceBaseline baseline, List<DependencyPin> pins) {
    String material =
        baseline.baselineId()
            + "|"
            + baseline.baselineVersion()
            + "|"
            + pins.stream().map(DependencyPin::sha256).sorted().reduce("", (a, b) -> a + b);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("cannot digest baseline", e);
    }
  }
}
