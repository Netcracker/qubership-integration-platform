package org.qubership.integration.platform.ai.compiler.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;

/** Reads skill bindings from the addon pack manifest. */
public final class CompilerRuntimeGeneratorRegistry {

  public static final String MANIFEST_FILE = "manifest.yaml";
  public static final String MANIFEST_SOURCE = "addons/manifest.yaml";

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  public record ManifestSkillBinding(String skillId, String generatorId, boolean wired) {}

  public Map<String, String> skillToGeneratorId(QipKnowledgePackScanResult scanResult) {
    Map<String, String> mappings = new LinkedHashMap<>();
    for (ManifestSkillBinding binding : loadBindings(scanResult)) {
      if (binding.generatorId() != null) {
        mappings.putIfAbsent(binding.skillId(), binding.generatorId());
      }
    }
    return Map.copyOf(mappings);
  }

  public Map<String, String> skillToGeneratorId(Path addonPackRoot) {
    Map<String, String> mappings = new LinkedHashMap<>();
    for (ManifestSkillBinding binding : loadBindings(addonPackRoot)) {
      if (binding.generatorId() != null) {
        mappings.putIfAbsent(binding.skillId(), binding.generatorId());
      }
    }
    return Map.copyOf(mappings);
  }

  public List<ManifestSkillBinding> loadBindings(QipKnowledgePackScanResult scanResult) {
    if (scanResult == null || scanResult.packRoot() == null) {
      return List.of();
    }
    return loadBindings(scanResult.packRoot().resolve("addons"));
  }

  public List<ManifestSkillBinding> loadBindings(Path addonPackRoot) {
    Path manifest = resolveManifest(addonPackRoot);
    if (manifest == null) {
      return List.of();
    }
    try {
      return parseManifest(Files.readString(manifest));
    } catch (IOException e) {
      throw new CompilerGeneratorPolicyParseException(
          "Failed to read addon manifest at " + manifest, e);
    }
  }

  private Path resolveManifest(Path addonPackRoot) {
    if (addonPackRoot == null) {
      return null;
    }
    Path direct = addonPackRoot.resolve(MANIFEST_FILE);
    if (Files.isRegularFile(direct)) {
      return direct;
    }
    Path nested = addonPackRoot.resolve("addons").resolve(MANIFEST_FILE);
    if (Files.isRegularFile(nested)) {
      return nested;
    }
    return null;
  }

  List<ManifestSkillBinding> parseManifest(String content) {
    JsonNode root;
    try {
      root = yamlMapper.readTree(content);
    } catch (IOException e) {
      throw new CompilerGeneratorPolicyParseException(
          "Failed to parse addon manifest from " + MANIFEST_SOURCE, e);
    }
    JsonNode skills = root.path("skills");
    if (!skills.isObject()) {
      return List.of();
    }
    List<ManifestSkillBinding> bindings = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> fields = skills.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      JsonNode skillNode = entry.getValue();
      bindings.add(
          new ManifestSkillBinding(
              entry.getKey(), text(skillNode, "generatorId"), skillNode.path("wired").asBoolean(false)));
    }
    return List.copyOf(bindings);
  }

  private static String text(JsonNode node, String fieldName) {
    JsonNode value = node.path(fieldName);
    return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }
}
