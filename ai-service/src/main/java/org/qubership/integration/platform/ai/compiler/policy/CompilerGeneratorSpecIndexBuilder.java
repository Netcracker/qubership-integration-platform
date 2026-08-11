package org.qubership.integration.platform.ai.compiler.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackFileKind;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.ScannedQipKnowledgeFile;

/** Builds an index of generator specs from top-level specs and normalized skill catalog entries. */
public class CompilerGeneratorSpecIndexBuilder {

  private static final Pattern YAML_METADATA =
      Pattern.compile("(?s)##\\s+Metadata\\s*\\R\\s*```yaml\\s*\\R(.*?)\\R```");
  private static final Pattern FRONTMATTER =
      Pattern.compile("(?s)^---\\s*\\R(.*?)\\R---\\s*\\R");
  private static final Pattern GENERATOR_CONTRACT =
      Pattern.compile("generator-contract:.*::\\s*(GEN-\\d+)", Pattern.MULTILINE);
  private static final Pattern GENERATOR_ID =
      Pattern.compile("generator-id:\\s*(GEN-\\d+)", Pattern.MULTILINE);

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  public CompilerGeneratorSpecIndex build(QipKnowledgePackScanResult scanResult) {
    Map<String, MutableSpec> specs = new LinkedHashMap<>();
    Map<String, String> runtimeGeneratorIds =
        new CompilerRuntimeGeneratorRegistry().skillToGeneratorId(scanResult);
    for (ScannedQipKnowledgeFile file : scanResult.files()) {
      if (file.kind() == QipKnowledgePackFileKind.SKILL_SOURCE_SPECIFICATION) {
        readTopLevelSpec(file, specs);
      } else if (file.kind() == QipKnowledgePackFileKind.SKILL_CATALOG) {
        readSkillCatalog(file, specs);
      } else if (file.kind() == QipKnowledgePackFileKind.SKILL) {
        readSkillSource(file, specs);
      }
    }
    applyRuntimeGeneratorIds(specs, runtimeGeneratorIds);
    List<CompilerGeneratorSpec> result = new ArrayList<>();
    for (MutableSpec spec : specs.values()) {
      result.add(spec.toSpec());
    }
    result.sort(Comparator.comparing(spec -> spec.skillName()));
    return new CompilerGeneratorSpecIndex(result);
  }

  private static void applyRuntimeGeneratorIds(
      Map<String, MutableSpec> specs, Map<String, String> runtimeGeneratorIds) {
    for (Map.Entry<String, String> entry : runtimeGeneratorIds.entrySet()) {
      MutableSpec spec = spec(specs, entry.getKey());
      spec.skillName = entry.getKey();
      spec.generatorId = firstNonBlank(spec.generatorId, entry.getValue());
      spec.addSource(CompilerRuntimeGeneratorRegistry.MANIFEST_SOURCE);
    }
  }

  private void readSkillSource(ScannedQipKnowledgeFile file, Map<String, MutableSpec> specs) {
    String skillName = deriveFolderSkillId(file.relativePath());
    MutableSpec spec = spec(specs, skillName);
    spec.skillName = skillName;
    spec.addSource(file.relativePath());

    Map<String, String> metadata = parseMarkdownMetadata(file.content());
    spec.generatorId = firstNonBlank(spec.generatorId, metadata.get("generator-id"));
    if (spec.generatorId == null) {
      spec.generatorId = parseGeneratorContractId(file.content());
    }
    spec.compilerStage = firstNonBlank(spec.compilerStage, metadata.get("compiler-stage"));
    spec.category = firstNonBlank(spec.category, metadata.get("category"));
  }

  private void readTopLevelSpec(
      ScannedQipKnowledgeFile file, Map<String, MutableSpec> specs) {
    String skillName = deriveTopLevelSpecSkillId(file.relativePath());
    MutableSpec spec = spec(specs, skillName);
    spec.skillName = skillName;
    spec.addSource(file.relativePath());

    Map<String, String> metadata = parseMarkdownMetadata(file.content());
    spec.generatorId = firstNonBlank(spec.generatorId, metadata.get("generator-id"));
    spec.compilerStage = firstNonBlank(spec.compilerStage, metadata.get("compiler-stage"));
    spec.category = firstNonBlank(spec.category, metadata.get("category"));
  }

  private void readSkillCatalog(
      ScannedQipKnowledgeFile file, Map<String, MutableSpec> specs) {
    JsonNode root = readYaml(file);
    JsonNode skillNodes = root.path("normalized-skills");
    if (!skillNodes.isArray()) {
      return;
    }
    for (JsonNode node : skillNodes) {
      String skillName = text(node, "name");
      if (skillName == null) {
        continue;
      }
      MutableSpec spec = spec(specs, skillName);
      spec.skillName = skillName;
      spec.category = firstNonBlank(spec.category, text(node, "category"));
      spec.compilerStage = firstNonBlank(spec.compilerStage, text(node, "stage"));
      spec.inputs = merge(spec.inputs, strings(node.path("inputs")));
      spec.outputs = merge(spec.outputs, strings(node.path("outputs")));
      spec.dependencies = merge(spec.dependencies, strings(node.path("dependencies")));
      spec.generatedArtifacts =
          merge(spec.generatedArtifacts, strings(node.path("generated-artifacts")));
      spec.supportedElements =
          merge(spec.supportedElements, strings(node.path("supported-elements")));
      spec.addSource(file.relativePath());
    }
  }

  private Map<String, String> parseMarkdownMetadata(String content) {
    Map<String, String> metadata = new LinkedHashMap<>();
    Matcher frontmatterMatcher = FRONTMATTER.matcher(content);
    if (frontmatterMatcher.find()) {
      metadata.putAll(readFlatYaml(frontmatterMatcher.group(1)));
    }
    Matcher metadataMatcher = YAML_METADATA.matcher(content);
    if (metadataMatcher.find()) {
      metadata.putAll(readFlatYaml(metadataMatcher.group(1)));
    }
    return metadata;
  }

  private Map<String, String> readFlatYaml(String yaml) {
    JsonNode root;
    try {
      root = yamlMapper.readTree(yaml);
    } catch (IOException e) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : root.properties()) {
      JsonNode value = entry.getValue();
      if (value.isValueNode()) {
        result.put(
            normalizeKey(entry.getKey()),
            value.isTextual() ? value.asText() : value.toString());
      }
    }
    return result;
  }

  private static String parseGeneratorContractId(String content) {
    Matcher contractMatcher = GENERATOR_CONTRACT.matcher(content);
    if (contractMatcher.find()) {
      return contractMatcher.group(1);
    }
    Matcher idMatcher = GENERATOR_ID.matcher(content);
    if (idMatcher.find()) {
      return idMatcher.group(1);
    }
    return null;
  }

  private static MutableSpec spec(Map<String, MutableSpec> specs, String skillName) {
    return specs.computeIfAbsent(skillName, ignored -> new MutableSpec());
  }

  private static List<String> strings(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (JsonNode item : node) {
      if (item.isTextual()) {
        result.add(item.asText());
      } else if (!item.isNull() && !item.isMissingNode()) {
        result.add(item.toString());
      }
    }
    return List.copyOf(result);
  }

  private static List<String> merge(List<String> existing, List<String> additions) {
    if (additions.isEmpty()) {
      return existing;
    }
    List<String> result = new ArrayList<>(existing);
    for (String addition : additions) {
      if (!result.contains(addition)) {
        result.add(addition);
      }
    }
    return List.copyOf(result);
  }

  private static String deriveFolderSkillId(String relativePath) {
    String normalized = relativePath.replace('\\', '/');
    String tail = normalized.substring("skills/".length());
    int slash = tail.indexOf('/');
    return slash > 0 ? tail.substring(0, slash) : tail;
  }

  private static String deriveTopLevelSpecSkillId(String relativePath) {
    String fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1);
    return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
  }

  private JsonNode readYaml(ScannedQipKnowledgeFile file) {
    try {
      return yamlMapper.readTree(file.content());
    } catch (IOException e) {
      throw new CompilerGeneratorPolicyParseException(
          "Failed to parse compiler generator spec YAML: " + file.relativePath(), e);
    }
  }

  private static String text(JsonNode node, String fieldName) {
    JsonNode value = node.path(fieldName);
    return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    return second != null && !second.isBlank() ? second : first;
  }

  private static String normalizeKey(String key) {
    return key.toLowerCase(Locale.ROOT).replace("_", "-").replace(" ", "-");
  }

  private static final class MutableSpec {
    String skillName;
    String generatorId;
    String compilerStage;
    String category;
    List<String> inputs = List.of();
    List<String> outputs = List.of();
    List<String> dependencies = List.of();
    List<String> generatedArtifacts = List.of();
    List<String> supportedElements = List.of();
    List<String> sourcePaths = List.of();

    void addSource(String sourcePath) {
      sourcePaths = merge(sourcePaths, List.of(sourcePath));
    }

    CompilerGeneratorSpec toSpec() {
      return new CompilerGeneratorSpec(
          skillName,
          generatorId,
          compilerStage,
          category,
          inputs,
          outputs,
          dependencies,
          generatedArtifacts,
          supportedElements,
          sourcePaths);
    }
  }
}
