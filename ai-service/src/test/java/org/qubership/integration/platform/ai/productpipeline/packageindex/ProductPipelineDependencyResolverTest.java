package org.qubership.integration.platform.ai.productpipeline.packageindex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

class ProductPipelineDependencyResolverTest {

  private final ProductPipelineDependencyResolver resolver = new ProductPipelineDependencyResolver();

  @Test
  void failsClosedWhenMandatorySkillMissing() {
    Path packRoot = CipKnowledgePackFixturePaths.packRoot();
    Path repoRoot = CipKnowledgePackFixturePaths.repositoryRoot();
    CapabilityManifest broken =
        new CapabilityManifest(
            1,
            "broken",
            "1",
            List.of(),
            List.of(),
            List.of("missing-skill-xyz"),
            "none",
            List.of(),
            List.of());
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> resolver.resolveClosure(repoRoot, packRoot, List.of(broken), List.of()));
    assertTrue(error.getMessage().contains("missing-skill-xyz"));
  }

  @Test
  void closureContainsOnlySkillAddonAndRulePins() {
    Path packRoot = CipKnowledgePackFixturePaths.packRoot();
    Path repoRoot = CipKnowledgePackFixturePaths.repositoryRoot();
    List<CapabilityManifest> capabilities =
        resolver.loadCapabilities(ProductPipelinePackageIndexBuilder.productPipelinesRoot(packRoot));

    List<DependencyPin> pins =
        resolver.resolveClosure(repoRoot, packRoot, capabilities, List.of());

    assertFalse(pins.isEmpty());
    assertTrue(
        pins.stream()
            .allMatch(
                pin ->
                    Set.of("skill", "addon", "runtime-metadata", "rule").contains(pin.kind())));
  }
}
