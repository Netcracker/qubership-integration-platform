package org.qubership.integration.platform.ai.productpipeline.packageindex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class ProductPipelineDependencyResolverTest {

  private final ProductPipelineDependencyResolver resolver = new ProductPipelineDependencyResolver();

  @Test
  void failsClosedWhenMandatorySkillMissing() {
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    Path repoRoot = packRoot.getParent();
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
            () -> resolver.resolveClosure(repoRoot, List.of(broken), List.of()));
    assertTrue(error.getMessage().contains("missing-skill-xyz"));
  }

  @Test
  void closureContainsOnlySkillAddonAndRulePins() {
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    Path repoRoot = packRoot.getParent();
    List<CapabilityManifest> capabilities =
        resolver.loadCapabilities(packRoot.resolve("product-pipelines"));

    List<DependencyPin> pins =
        resolver.resolveClosure(repoRoot, capabilities, List.of());

    assertFalse(pins.isEmpty());
    assertTrue(
        pins.stream()
            .allMatch(
                pin ->
                    Set.of("skill", "addon", "rule").contains(pin.kind())));
  }
}
