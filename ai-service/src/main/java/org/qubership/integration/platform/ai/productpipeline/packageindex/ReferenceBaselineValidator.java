package org.qubership.integration.platform.ai.productpipeline.packageindex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates frozen target hashes from {@link ReferenceBaseline} without reading the experimental
 * tree.
 */
public final class ReferenceBaselineValidator {

  private ReferenceBaselineValidator() {}

  public static void validateTarget(
      ReferenceBaseline baseline, Path repoRoot, Collection<DependencyPin> pins) {
    if (baseline == null) {
      throw new IllegalArgumentException("baseline is required");
    }
    if (repoRoot == null || !Files.isDirectory(repoRoot)) {
      throw new IllegalArgumentException("repoRoot must be a directory");
    }
    Map<String, ReferenceArtifact> byKey =
        baseline.artifacts().stream()
            .collect(
                Collectors.toMap(
                    artifact -> artifactKey(artifact.dependencyId(), artifact.kind()),
                    Function.identity(),
                    (a, b) -> a));
    List<String> errors = new ArrayList<>();
    for (DependencyPin pin : pins == null ? List.<DependencyPin>of() : pins) {
      ReferenceArtifact artifact = byKey.get(artifactKey(pin.dependencyId(), pin.kind()));
      if (artifact == null) {
        if (pin.disposition() != ReferenceDisposition.TARGET_ONLY) {
          errors.add("missing baseline artifact for " + pin.dependencyId() + " (" + pin.kind() + ")");
        }
        continue;
      }
      if (artifact.targetPath().startsWith("generated/")
          && artifact.disposition() == ReferenceDisposition.TARGET_ONLY) {
        continue;
      }
      String expectedHash = artifact.targetSha256();
      String actual;
      if (isClasspathTarget(artifact.targetPath())) {
        actual = sha256Classpath(readClasspathBytes(classpathResourcePath(artifact.targetPath())));
      } else {
        Path target = resolveKnowledgeTarget(repoRoot, artifact.targetPath(), pin.path());
        if (!Files.isRegularFile(target)) {
          errors.add("missing target file for " + pin.dependencyId() + ": " + artifact.targetPath());
          continue;
        }
        actual = sha256(target);
      }
      if (!actual.equalsIgnoreCase(expectedHash)) {
        errors.add(
            "target hash mismatch for "
                + pin.dependencyId()
                + ": expected "
                + expectedHash
                + " got "
                + actual);
      }
      if ((artifact.disposition() == ReferenceDisposition.ADAPTED
              || artifact.disposition() == ReferenceDisposition.TARGET_ONLY)
          && artifact.adaptationReason().filter(reason -> !reason.isBlank()).isEmpty()) {
        errors.add(artifact.disposition() + " requires reason for " + pin.dependencyId());
      }
    }
    if (!errors.isEmpty()) {
      throw new IllegalStateException(String.join("; ", errors));
    }
  }

  private static Path resolveKnowledgeTarget(Path repoRoot, String baselineTargetPath, String pinPath) {
    Path target = repoRoot.resolve(baselineTargetPath).normalize();
    if (Files.isRegularFile(target)) {
      return target;
    }
    Path pinned = repoRoot.resolve(pinPath).normalize();
    if (Files.isRegularFile(pinned)) {
      return pinned;
    }
    return target;
  }

  private static String artifactKey(String dependencyId, String kind) {
    return dependencyId + "|" + kind;
  }

  static String sha256(Path file) {
    try {
      return sha256Classpath(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new IllegalStateException("cannot hash " + file, e);
    }
  }

  private static boolean isClasspathTarget(String targetPath) {
    return targetPath != null && targetPath.startsWith("classpath:");
  }

  private static String classpathResourcePath(String targetPath) {
    String resource = targetPath.substring("classpath:".length());
    return resource.startsWith("/") ? resource.substring(1) : resource;
  }

  private static byte[] readClasspathBytes(String resourcePath) {
    try (InputStream in =
        ReferenceBaselineValidator.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("missing classpath resource: " + resourcePath);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("cannot read classpath resource: " + resourcePath, e);
    }
  }

  private static String sha256Classpath(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
