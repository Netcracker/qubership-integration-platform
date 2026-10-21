package org.qubership.integration.platform.ai.productpipeline.packageindex;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceBaselineValidatorTest {

  @Test
  void failsClosedWhenTargetMissingEvenIfDefaultSeedExists(@TempDir Path repoRoot) throws Exception {
    String relative = "packs/demo/file.txt";
    Path defaultSeed =
        repoRoot.resolve("integration-platform-skills/knowledge/default").resolve(relative);
    Files.createDirectories(defaultSeed.getParent());
    Files.writeString(defaultSeed, "seed-content", StandardCharsets.UTF_8);

    String targetPath = "integration-platform-skills/knowledge/slim/" + relative;
    Path missingTarget = repoRoot.resolve(targetPath);

    ReferenceBaseline baseline =
        new ReferenceBaseline(
            1,
            "baseline",
            "1",
            "integration-platform-skills",
            Map.of(),
            Map.of(),
            List.of(
                new ReferenceArtifact(
                    "dep-1",
                    "knowledge",
                    Optional.empty(),
                    targetPath,
                    Optional.empty(),
                    "deadbeef",
                    ReferenceDisposition.ADOPTED,
                    Optional.empty())));
    DependencyPin pin =
        new DependencyPin(
            "dep-1",
            "knowledge",
            "integration-platform-skills/knowledge/slim/" + relative + "-missing",
            "deadbeef",
            ReferenceDisposition.ADOPTED,
            null);

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> ReferenceBaselineValidator.validateTarget(baseline, repoRoot, List.of(pin)));
    assertTrue(ex.getMessage().contains("missing target file"));
    assertTrue(Files.isRegularFile(defaultSeed));
    assertTrue(!Files.isRegularFile(missingTarget));
  }
}
