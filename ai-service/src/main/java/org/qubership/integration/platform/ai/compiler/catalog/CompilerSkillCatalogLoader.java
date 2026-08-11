package org.qubership.integration.platform.ai.compiler.catalog;

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
import org.qubership.integration.platform.ai.compiler.policy.CompilerRuntimeGeneratorRegistry;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackFileKind;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.ScannedQipKnowledgeFile;

/** Loads the production compiler skill catalog from package indexes, specs, source metadata, and markers. */
public class CompilerSkillCatalogLoader {

  private static final Pattern YAML_METADATA =
      Pattern.compile("(?s)##\\s+Metadata\\s*\\R\\s*```yaml\\s*\\R(.*?)\\R```");
  private static final Pattern FRONTMATTER =
      Pattern.compile("(?s)^---\\s*\\R(.*?)\\R---\\s*\\R");

  private static final Pattern GENERATOR_CONTRACT =
      Pattern.compile("generator-contract:.*::\\s*(GEN-\\d+)", Pattern.MULTILINE);
  private static final Pattern GENERATOR_ID =
      Pattern.compile("generator-id:\\s*(GEN-\\d+)", Pattern.MULTILINE);

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  public CompilerSkillCatalog load(QipKnowledgePackScanResult scanResult) {
    Map<String, MutableSkill> skills = new LinkedHashMap<>();
    CompilerRuntimeGeneratorRegistry registry = new CompilerRuntimeGeneratorRegistry();

    for (ScannedQipKnowledgeFile file : scanResult.files()) {
      switch (file.kind()) {
        case RUNTIME_SKILL_INDEX -> readRuntimeSkillIndex(file, skills);
        case SKILL_CATALOG -> readSkillCatalog(file, skills);
        case SKILL -> readSkillSource(file, skills);
        case SKILL_SOURCE_SPECIFICATION -> readSourceSpec(file, skills);
        case SKILL_PRIVATE_MARKER -> markPrivate(file, skills);
        default -> {
          // Other scanned files do not define compiler skill catalog entries.
        }
      }
    }

    applyRuntimeGeneratorIds(skills, registry.skillToGeneratorId(scanResult));
    applyManifestBindings(skills, registry.loadBindings(scanResult));

    return new CompilerSkillCatalog(
        skills.values().stream()
            .map(MutableSkill::toDescriptor)
            .sorted(Comparator.comparing(CompilerSkillDescriptor::name))
            .toList());
  }

  private void readRuntimeSkillIndex(
      ScannedQipKnowledgeFile file, Map<String, MutableSkill> skills) {
    JsonNode root = readYaml(file);
    JsonNode skillNodes = root.path("skills");
    if (!skillNodes.isArray()) {
      return;
    }
    for (JsonNode node : skillNodes) {
      String name = text(node, "name");
      if (name == null) {
        continue;
      }
      MutableSkill skill = skill(skills, name);
      skill.name = name;
      skill.category = firstNonBlank(skill.category, text(node, "category"));
      skill.path = firstNonBlank(skill.path, text(node, "path"));
      skill.substrate = firstNonBlank(skill.substrate, text(node, "substrate"));
      skill.runtimeIndexEntry = true;
      skill.runtimeSkill = true;
      skill.consumes = merge(skill.consumes, strings(node.path("consumes")));
      skill.produces = merge(skill.produces, strings(node.path("produces")));
      skill.dependsOn = merge(skill.dependsOn, strings(node.path("depends-on")));
      skill.addSource(file.relativePath());
    }
  }

  private void readSkillCatalog(
      ScannedQipKnowledgeFile file, Map<String, MutableSkill> skills) {
    JsonNode root = readYaml(file);
    JsonNode skillNodes = root.path("normalized-skills");
    if (!skillNodes.isArray()) {
      return;
    }
    for (JsonNode node : skillNodes) {
      String name = text(node, "name");
      if (name == null) {
        continue;
      }
      MutableSkill skill = skill(skills, name);
      skill.name = name;
      skill.category = firstNonBlank(skill.category, text(node, "category"));
      skill.stage = firstNonBlank(skill.stage, text(node, "stage"));
      skill.generatedArtifacts =
          merge(skill.generatedArtifacts, strings(node.path("generated-artifacts")));
      skill.supportedElements =
          merge(skill.supportedElements, strings(node.path("supported-elements")));
      skill.dependsOn = merge(skill.dependsOn, strings(node.path("dependencies")));
      skill.catalogEntry = true;
      skill.addSource(file.relativePath());
    }
  }

  private void readSkillSource(ScannedQipKnowledgeFile file, Map<String, MutableSkill> skills) {
    String name = deriveFolderSkillId(file.relativePath());
    MutableSkill skill = skill(skills, name);
    skill.name = name;
    skill.path = firstNonBlank(skill.path, file.relativePath());
    skill.skillSource = true;
    skill.addSource(file.relativePath());

    Map<String, String> metadata = parseMarkdownMetadata(file.content());
    skill.category = firstNonBlank(skill.category, metadata.get("category"));
    skill.substrate = firstNonBlank(skill.substrate, metadata.get("substrate"));
    if (metadata.containsKey("public-api")) {
      skill.publicApi = Boolean.parseBoolean(metadata.get("public-api"));
      skill.publicApiExplicit = true;
    }
    if (metadata.containsKey("runtime-skill")) {
      skill.runtimeSkill = Boolean.parseBoolean(metadata.get("runtime-skill"));
    }
    skill.generatorId = firstNonBlank(skill.generatorId, metadata.get("generator-id"));
    if (skill.generatorId == null) {
      skill.generatorId = parseGeneratorContractId(file.content());
    }
  }

  private void readSourceSpec(ScannedQipKnowledgeFile file, Map<String, MutableSkill> skills) {
    String name = deriveTopLevelSpecSkillId(file.relativePath());
    MutableSkill skill = skill(skills, name);
    skill.name = name;
    skill.sourceSpecification = true;
    skill.addSource(file.relativePath());

    Map<String, String> metadata = parseMarkdownMetadata(file.content());
    skill.category = firstNonBlank(skill.category, metadata.get("category"));
    skill.stage = firstNonBlank(skill.stage, metadata.get("compiler-stage"));
    skill.generatorId = firstNonBlank(skill.generatorId, metadata.get("generator-id"));
  }

  private static void markPrivate(
      ScannedQipKnowledgeFile file, Map<String, MutableSkill> skills) {
    String name = deriveFolderSkillId(file.relativePath());
    MutableSkill skill = skill(skills, name);
    skill.name = name;
    skill.privateMarker = true;
    skill.addSource(file.relativePath());
  }

  private static void applyRuntimeGeneratorIds(
      Map<String, MutableSkill> skills, Map<String, String> runtimeGeneratorIds) {
    for (Map.Entry<String, String> entry : runtimeGeneratorIds.entrySet()) {
      MutableSkill skill = skill(skills, entry.getKey());
      skill.name = entry.getKey();
      skill.generatorId = firstNonBlank(skill.generatorId, entry.getValue());
      skill.addSource(CompilerRuntimeGeneratorRegistry.MANIFEST_SOURCE);
    }
  }

  private static void applyManifestBindings(
      Map<String, MutableSkill> skills,
      List<CompilerRuntimeGeneratorRegistry.ManifestSkillBinding> bindings) {
    for (CompilerRuntimeGeneratorRegistry.ManifestSkillBinding binding : bindings) {
      MutableSkill skill = skill(skills, binding.skillId());
      skill.name = binding.skillId();
      skill.generatorId = firstNonBlank(skill.generatorId, binding.generatorId());
      if (binding.wired()) {
        skill.catalogEntry = true;
        skill.runtimeSkill = true;
      }
      skill.addSource(CompilerRuntimeGeneratorRegistry.MANIFEST_SOURCE);
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

  private JsonNode readYaml(ScannedQipKnowledgeFile file) {
    try {
      return yamlMapper.readTree(file.content());
    } catch (IOException e) {
      throw new CompilerSkillCatalogParseException(
          "Failed to parse compiler skill catalog YAML: " + file.relativePath(), e);
    }
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

  private static MutableSkill skill(Map<String, MutableSkill> skills, String name) {
    return skills.computeIfAbsent(name, ignored -> new MutableSkill());
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

  private static final class MutableSkill {
    String name;
    String category;
    String path;
    boolean runtimeSkill;
    boolean publicApi = true;
    boolean publicApiExplicit;
    boolean privateMarker;
    String substrate;
    String stage;
    String generatorId;
    boolean runtimeIndexEntry;
    boolean catalogEntry;
    boolean skillSource;
    boolean sourceSpecification;
    List<String> sourcePaths = List.of();
    List<String> consumes = List.of();
    List<String> produces = List.of();
    List<String> dependsOn = List.of();
    List<String> generatedArtifacts = List.of();
    List<String> supportedElements = List.of();

    void addSource(String sourcePath) {
      sourcePaths = merge(sourcePaths, List.of(sourcePath));
    }

    CompilerSkillDescriptor toDescriptor() {
      return new CompilerSkillDescriptor(
          name,
          category,
          path,
          runtimeSkill,
          publicApi,
          privateMarker,
          disposition(),
          sourcePaths,
          substrate,
          consumes,
          produces,
          dependsOn);
    }

    private CompilerSkillDisposition disposition() {
      if (privateMarker && !publicApi) {
        return CompilerSkillDisposition.PRIVATE;
      }
      String normalizedCategory =
          category == null ? "" : category.toLowerCase(Locale.ROOT).replace("-", "");
      if ("validation".equals(normalizedCategory)
          || normalizedCategory.endsWith("validator")
          || (name != null && name.endsWith("-validator"))) {
        return CompilerSkillDisposition.VALIDATOR;
      }
      if ("prompt-library".equals(substrate)
          || "knowledge".equals(normalizedCategory)
          || "compiler".equals(normalizedCategory)
          || "operations".equals(normalizedCategory)) {
        return CompilerSkillDisposition.BUILD_TIME;
      }
      if (runtimeIndexEntry && runtimeSkill && publicApi) {
        return CompilerSkillDisposition.PUBLIC_RUNTIME;
      }
      if (skillSource
          && generatorId != null
          && !generatorId.isBlank()
          && publicApi
          && (catalogEntry || runtimeIndexEntry || sourceSpecification)
          && !"validation".equals(normalizedCategory)
          && !normalizedCategory.endsWith("validator")) {
        return CompilerSkillDisposition.PUBLIC_RUNTIME;
      }
      if (sourceSpecification && !skillSource && !runtimeIndexEntry && !catalogEntry) {
        return CompilerSkillDisposition.SPECIFICATION_ONLY;
      }
      return CompilerSkillDisposition.UNSUPPORTED;
    }
  }
}
