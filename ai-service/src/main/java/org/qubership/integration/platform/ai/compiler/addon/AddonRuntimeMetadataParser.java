package org.qubership.integration.platform.ai.compiler.addon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicyParseException;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

/** Parses structured runtime promotion metadata from compiler skill addon documents. */
public final class AddonRuntimeMetadataParser {

  private static final Pattern RUNTIME_SECTION =
      Pattern.compile("(?is)^##\\s+Runtime metadata\\s*$([\\s\\S]*?)(?=^##\\s+|\\z)", Pattern.MULTILINE);
  private static final Pattern YAML_FENCE =
      Pattern.compile("(?s)```ya?ml\\s*\\R(.*?)```");
  private static final Pattern INPUT_ARTIFACTS_LINE =
      Pattern.compile("(?im)^-\\s*Input artifacts:\\s*(.+)$");
  private static final Pattern OUTPUT_ARTIFACTS_LINE =
      Pattern.compile("(?im)^-\\s*Output artifacts:\\s*(.+)$");
  private static final Pattern BACKTICK_TOKEN = Pattern.compile("`([^`]+)`");
  private static final Pattern TYPED_ARTIFACT = Pattern.compile("^[A-Z][A-Z0-9_]*$");

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  public AddonRuntimeMetadata parseAddonContent(String content, String sourceLabel) {
    Matcher sectionMatcher = RUNTIME_SECTION.matcher(content);
    if (!sectionMatcher.find()) {
      return null;
    }
    Matcher fenceMatcher = YAML_FENCE.matcher(sectionMatcher.group(1));
    if (!fenceMatcher.find()) {
      throw new CompilerGeneratorPolicyParseException(
          "Runtime metadata section in " + sourceLabel + " is missing a yaml code fence");
    }
    AddonRuntimeMetadata metadata = parseYamlBlock(fenceMatcher.group(1).trim(), sourceLabel);
    if (metadata == null) {
      return null;
    }
    return new AddonRuntimeMetadata(
        metadata.promoted(),
        metadata.category(),
        metadata.runtimeSkill(),
        metadata.captureTool(),
        metadata.ownership(),
        parseTypedArtifacts(content, INPUT_ARTIFACTS_LINE),
        parseTypedArtifacts(content, OUTPUT_ARTIFACTS_LINE));
  }

  public AddonRuntimeMetadata parseAddonFile(Path addonFile) {
    if (!Files.isRegularFile(addonFile)) {
      return null;
    }
    try {
      return parseAddonContent(Files.readString(addonFile), addonFile.toString());
    } catch (IOException e) {
      throw new CompilerGeneratorPolicyParseException(
          "Failed to read addon runtime metadata from " + addonFile, e);
    }
  }

  private AddonRuntimeMetadata parseYamlBlock(String yaml, String sourceLabel) {
    try {
      JsonNode root = yamlMapper.readTree(yaml);
      JsonNode runtime = root.path("runtime");
      if (runtime.isMissingNode()) {
        throw new CompilerGeneratorPolicyParseException(
            "Runtime metadata YAML in " + sourceLabel + " must contain a runtime root key");
      }
      boolean promoted = runtime.path("promoted").asBoolean(false);
      if (!promoted) {
        return null;
      }
      String category = textOrNull(runtime.get("category"));
      if (category == null || category.isBlank()) {
        throw new CompilerGeneratorPolicyParseException(
            "Runtime metadata YAML in " + sourceLabel + " must define runtime.category when promoted");
      }
      boolean runtimeSkill = runtime.path("runtime-skill").asBoolean(true);
      CaptureTool captureTool = parseCaptureTool(runtime, sourceLabel);
      GraphPatchOwnershipPolicy ownership = parseOwnership(runtime);
      return new AddonRuntimeMetadata(
          true, category, runtimeSkill, captureTool, ownership, List.of(), List.of());
    } catch (IOException e) {
      throw new CompilerGeneratorPolicyParseException(
          "Failed to parse runtime metadata YAML in " + sourceLabel, e);
    }
  }

  private static List<String> parseTypedArtifacts(String content, Pattern linePattern) {
    Matcher lineMatcher = linePattern.matcher(content);
    if (!lineMatcher.find()) {
      return List.of();
    }
    LinkedHashSet<String> values = new LinkedHashSet<>();
    Matcher tokenMatcher = BACKTICK_TOKEN.matcher(lineMatcher.group(1));
    while (tokenMatcher.find()) {
      String token = tokenMatcher.group(1).trim();
      if (TYPED_ARTIFACT.matcher(token).matches()) {
        values.add(token);
      }
    }
    return values.isEmpty() ? List.of() : List.copyOf(values);
  }

  private static String textOrNull(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private static CaptureTool parseCaptureTool(JsonNode runtime, String sourceLabel) {
    JsonNode capture = runtime.path("capture");
    if (capture.isMissingNode()) {
      return null;
    }
    String toolName = textOrNull(capture.get("tool"));
    if (toolName == null || toolName.isBlank()) {
      throw new CompilerGeneratorPolicyParseException(
          "Runtime metadata YAML in " + sourceLabel + " must define runtime.capture.tool");
    }
    try {
      return CaptureTool.fromToolName(toolName);
    } catch (IllegalArgumentException e) {
      throw new CompilerGeneratorPolicyParseException(
          "Runtime metadata YAML in " + sourceLabel + " has unsupported runtime.capture.tool: "
              + toolName,
          e);
    }
  }

  private static GraphPatchOwnershipPolicy parseOwnership(JsonNode runtime) {
    JsonNode ownershipNode = runtime.path("ownership");
    if (ownershipNode.isMissingNode() || ownershipNode.isNull()) {
      return null;
    }
    boolean mayAddNodes = ownershipNode.path("mayAddNodes").asBoolean(false);
    boolean mayAddEdges = ownershipNode.path("mayAddEdges").asBoolean(false);
    Set<String> nodeTypes = stringSet(ownershipNode.path("nodeTypes"));
    Set<String> chainFields = stringSet(ownershipNode.path("chainFields"));
    Map<String, Set<String>> properties = parseProperties(ownershipNode.path("properties"));
    return new GraphPatchOwnershipPolicy(
        mayAddNodes, mayAddEdges, nodeTypes, chainFields, properties);
  }

  private static Set<String> stringSet(JsonNode node) {
    if (node == null || node.isMissingNode() || !node.isArray()) {
      return Set.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode item : node) {
      if (!item.isTextual()) {
        continue;
      }
      String value = item.asText().trim();
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return values.isEmpty() ? Set.of() : Set.copyOf(values);
  }

  private static Map<String, Set<String>> parseProperties(JsonNode node) {
    if (node == null || node.isMissingNode() || !node.isObject()) {
      return Map.of();
    }
    Map<String, Set<String>> properties = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            field -> {
              String key = field.getKey();
              if (key == null || key.isBlank()) {
                return;
              }
              properties.put(key, stringSet(field.getValue()));
            });
    return properties.isEmpty() ? Map.of() : Map.copyOf(properties);
  }
}
