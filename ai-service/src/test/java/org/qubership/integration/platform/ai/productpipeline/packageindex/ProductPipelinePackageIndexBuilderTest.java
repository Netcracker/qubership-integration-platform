package org.qubership.integration.platform.ai.productpipeline.packageindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class ProductPipelinePackageIndexBuilderTest {

  @Test
  void buildsIndexWithOnlySkillAddonAndRulePins() {
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    Path repoRoot = packRoot.getParent();

    ProductPipelinePackageIndex index =
        new ProductPipelinePackageIndexBuilder()
            .build(repoRoot, packRoot, List.of("cip-auth-generator"));

    assertEquals("experimental-migration", index.baselineId());
    assertFalse(index.dependencyPins().isEmpty());
    assertTrue(
        index.dependencyPins().stream()
            .allMatch(pin -> pin.sha256() != null && pin.sha256().length() == 64));
    assertTrue(
        index.capabilities().stream()
            .anyMatch(capability -> "planning".equals(capability.capabilityId())));
    assertTrue(
        index.dependencyPins().stream()
            .anyMatch(pin -> "cip-auth-generator".equals(pin.dependencyId())));
    assertTrue(
        index.dependencyPins().stream()
            .noneMatch(pin -> "knowledge".equals(pin.kind())));
  }

  @Test
  void shuffledPinsProduceStableClosureDigest() {
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    Path repoRoot = packRoot.getParent();
    ProductPipelineDependencyResolver resolver = new ProductPipelineDependencyResolver();
    List<CapabilityManifest> capabilities =
        resolver.loadCapabilities(packRoot.resolve("product-pipelines"));
    List<DependencyPin> pins =
        new ArrayList<>(resolver.resolveClosure(repoRoot, capabilities, List.of()));
    String first = resolver.closureDigest(pins);
    Collections.reverse(pins);
    String second = resolver.closureDigest(pins);
    assertEquals(first, second);
  }
}
