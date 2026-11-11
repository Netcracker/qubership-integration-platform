package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads scalar compiler metadata; the Metadata section overrides frontmatter. */
public final class CompilerMarkdownMetadata {

  private static final Pattern YAML_METADATA =
      Pattern.compile("(?s)##\\s+Metadata\\s*\\R\\s*```yaml\\s*\\R(.*?)\\R```");
  private static final Pattern FRONTMATTER =
      Pattern.compile("(?s)^---\\s*\\R(.*?)\\R---\\s*\\R");

  private CompilerMarkdownMetadata() {}

  public static Map<String, String> parse(String content, ObjectMapper yamlMapper) {
    Map<String, String> metadata = new LinkedHashMap<>();
    Matcher frontmatterMatcher = FRONTMATTER.matcher(content);
    if (frontmatterMatcher.find()) {
      metadata.putAll(readFlatYaml(frontmatterMatcher.group(1), yamlMapper));
    }
    Matcher metadataMatcher = YAML_METADATA.matcher(content);
    if (metadataMatcher.find()) {
      metadata.putAll(readFlatYaml(metadataMatcher.group(1), yamlMapper));
    }
    return metadata;
  }

  private static Map<String, String> readFlatYaml(String yaml, ObjectMapper yamlMapper) {
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
            entry.getKey().toLowerCase(Locale.ROOT).replace("_", "-").replace(" ", "-"),
            value.isTextual() ? value.asText() : value.toString());
      }
    }
    return result;
  }
}
