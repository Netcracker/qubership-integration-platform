package org.qubership.integration.platform.ai.productpipeline.packageindex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;

/** Expands capability manifests into a hashed skill/addon/rule dependency closure. */
public final class ProductPipelineDependencyResolver {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  public List<CapabilityManifest> loadCapabilities(Path productPipelinesRoot) {
    Path capabilitiesDir = productPipelinesRoot.resolve("capabilities");
    if (!Files.isDirectory(capabilitiesDir)) {
      throw new IllegalArgumentException("capabilities directory missing: " + capabilitiesDir);
    }
    try (Stream<Path> files = Files.list(capabilitiesDir)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".yaml"))
          .sorted()
          .map(this::parseCapability)
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("cannot list capabilities", e);
    }
  }

  public List<DependencyPin> resolveClosure(
      Path repoRoot, List<CapabilityManifest> capabilities, List<String> dynamicSkills) {
    Set<String> skillIds = new LinkedHashSet<>();
    Set<String> addonIds = new LinkedHashSet<>();
    Set<String> ruleIds = new LinkedHashSet<>();
    for (CapabilityManifest capability : capabilities) {
      skillIds.addAll(capability.requiredSkills());
      addonIds.addAll(capability.requiredAddons());
      ruleIds.addAll(capability.requiredRules());
    }
    if (dynamicSkills != null) {
      skillIds.addAll(dynamicSkills);
    }

    List<DependencyPin> pins = new ArrayList<>();
    for (String skillId : skillIds) {
      pins.add(pinSkill(repoRoot, skillId));
    }
    for (String addonId : addonIds) {
      pins.add(pinAddon(repoRoot, addonId));
    }
    for (String ruleId : ruleIds) {
      pins.add(pinRule(repoRoot, ruleId));
    }
    pins.sort(Comparator.comparing(DependencyPin::dependencyId).thenComparing(DependencyPin::kind));
    return List.copyOf(pins);
  }

  public String closureDigest(List<DependencyPin> pins) {
    List<DependencyPin> ordered =
        pins.stream()
            .sorted(
                Comparator.comparing(DependencyPin::dependencyId)
                    .thenComparing(DependencyPin::kind))
            .toList();
    StringBuilder material = new StringBuilder();
    for (DependencyPin pin : ordered) {
      material
          .append(pin.dependencyId())
          .append('|')
          .append(pin.kind())
          .append('|')
          .append(pin.sha256())
          .append('\n');
    }
    return sha256Bytes(material.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private CapabilityManifest parseCapability(Path file) {
    try {
      JsonNode root = YAML.readTree(file.toFile());
      return new CapabilityManifest(
          root.path("schemaVersion").asInt(1),
          text(root, "capabilityId"),
          text(root, "capabilityVersion"),
          readArtifactRefs(root.path("consumes")),
          readArtifactRefs(root.path("produces")),
          readStringList(root.path("skills").path("required")),
          textOr(root.path("skills").path("dynamicSet"), "none"),
          readStringList(root.path("addons").path("required")),
          readStringList(root.path("rules").path("required")));
    } catch (IOException e) {
      throw new IllegalStateException("cannot parse capability " + file, e);
    }
  }

  private DependencyPin pinSkill(Path repoRoot, String skillId) {
    Path path = repoRoot.resolve("integration-platform-skills/.apm/skills/" + skillId + "/SKILL.md");
    if (Files.isRegularFile(path)) {
      return new DependencyPin(
          skillId,
          "skill",
          relativize(repoRoot, path),
          sha256(path),
          ReferenceDisposition.ADOPTED,
          null);
    }
    if ("plan-validator".equals(skillId)) {
      Path prompt =
          repoRoot.resolve("ai-service/src/main/resources/prompts/roles/plan-validator.md");
      requireFile(prompt, "skill", skillId);
      return new DependencyPin(
          skillId,
          "skill",
          relativize(repoRoot, prompt),
          sha256(prompt),
          ReferenceDisposition.TARGET_ONLY,
          "ai-service plan-validator role prompt");
    }
    requireFile(path, "skill", skillId);
    throw new IllegalStateException("unreachable");
  }

  private DependencyPin pinAddon(Path repoRoot, String addonId) {
    Path path =
        repoRoot.resolve("integration-platform-skills/addons/skills/" + addonId + ".addon.md");
    requireFile(path, "addon", addonId);
    return new DependencyPin(
        addonId,
        "addon",
        relativize(repoRoot, path),
        sha256(path),
        ReferenceDisposition.TARGET_ONLY,
        "ai-service addon with no reference counterpart");
  }

  private DependencyPin pinRule(Path repoRoot, String ruleId) {
    Path product =
        repoRoot.resolve("integration-platform-skills/product-pipelines/rules/" + ruleId + ".yaml");
    if (Files.isRegularFile(product)) {
      return new DependencyPin(
          ruleId,
          "rule",
          relativize(repoRoot, product),
          sha256(product),
          ReferenceDisposition.TARGET_ONLY,
          "product-pipeline rule");
    }
    if ("compiler-generator-policy".equals(ruleId) || "qip-element-schemas".equals(ruleId)) {
      return new DependencyPin(
          ruleId,
          "rule",
          "generated/" + ruleId,
          sha256Bytes(ruleId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          ReferenceDisposition.TARGET_ONLY,
          "generated pack index / schema contract");
    }
    Path path = repoRoot.resolve("integration-platform-skills/.apm/instructions/" + ruleId + ".md");
    requireFile(path, "rule", ruleId);
    return new DependencyPin(
        ruleId, "rule", relativize(repoRoot, path), sha256(path), ReferenceDisposition.ADOPTED, null);
  }

  private static void requireFile(Path path, String kind, String id) {
    if (!Files.isRegularFile(path)) {
      throw new IllegalStateException("missing mandatory " + kind + " " + id + " at " + path);
    }
  }

  private static List<ArtifactTypeRef> readArtifactRefs(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<ArtifactTypeRef> refs = new ArrayList<>();
    for (JsonNode item : node) {
      refs.add(new ArtifactTypeRef(text(item, "type"), item.path("schemaVersion").asInt(1)));
    }
    return List.copyOf(refs);
  }

  private static List<String> readStringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    node.forEach(item -> values.add(item.asText()));
    return List.copyOf(values);
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).asText(null);
  }

  private static String textOr(JsonNode node, String fallback) {
    String value = node.asText(null);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String relativize(Path repoRoot, Path file) {
    return repoRoot.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  private static String sha256(Path file) {
    try {
      return sha256Bytes(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new IllegalStateException("cannot hash " + file, e);
    }
  }

  private static String sha256Bytes(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
