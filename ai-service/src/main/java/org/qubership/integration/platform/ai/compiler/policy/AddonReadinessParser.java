package org.qubership.integration.platform.ai.compiler.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses structured readiness metadata from compiler skill addon documents. */
public final class AddonReadinessParser {

  private static final Pattern READINESS_SECTION =
      Pattern.compile("(?is)^##\\s+Readiness signals\\s*$([\\s\\S]*?)(?=^##\\s+|\\z)", Pattern.MULTILINE);
  private static final Pattern YAML_FENCE =
      Pattern.compile("(?s)```ya?ml\\s*\\R(.*?)```");

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  public CompilerGeneratorReadiness parseAddonContent(String content, String sourceLabel) {
    Matcher sectionMatcher = READINESS_SECTION.matcher(content);
    if (!sectionMatcher.find()) {
      return null;
    }
    Matcher fenceMatcher = YAML_FENCE.matcher(sectionMatcher.group(1));
    if (!fenceMatcher.find()) {
      throw new CompilerGeneratorPolicyParseException(
          "Readiness signals section in " + sourceLabel + " is missing a yaml code fence");
    }
    return parseYamlBlock(fenceMatcher.group(1).trim(), sourceLabel);
  }

  public CompilerGeneratorReadiness parseAddonFile(Path addonFile) {
    if (!Files.isRegularFile(addonFile)) {
      return null;
    }
    try {
      return parseAddonContent(Files.readString(addonFile), addonFile.toString());
    } catch (IOException e) {
      throw new CompilerGeneratorPolicyParseException(
          "Failed to read addon readiness from " + addonFile, e);
    }
  }

  private CompilerGeneratorReadiness parseYamlBlock(String yaml, String sourceLabel) {
    try {
      JsonNode root = yamlMapper.readTree(yaml);
      JsonNode readiness = root.path("readiness");
      if (readiness.isMissingNode()) {
        throw new CompilerGeneratorPolicyParseException(
            "Readiness YAML in " + sourceLabel + " must contain a readiness root key");
      }
      String mode = textOrNull(readiness.get("mode"));
      if (mode == null || mode.isBlank()) {
        throw new CompilerGeneratorPolicyParseException(
            "Readiness YAML in " + sourceLabel + " must define readiness.mode");
      }
      JsonNode signalsNode = readiness.get("signals");
      if (signalsNode == null || !signalsNode.isArray() || signalsNode.isEmpty()) {
        throw new CompilerGeneratorPolicyParseException(
            "Readiness YAML in " + sourceLabel + " must define a non-empty readiness.signals list");
      }
      List<String> signals = new ArrayList<>();
      for (JsonNode signal : signalsNode) {
        if (!signal.isTextual() || signal.asText().isBlank()) {
          throw new CompilerGeneratorPolicyParseException(
              "Readiness signals in " + sourceLabel + " must be non-empty strings");
        }
        signals.add(signal.asText().trim().toLowerCase(Locale.ROOT));
      }
      return new CompilerGeneratorReadiness(mode.trim(), List.copyOf(signals));
    } catch (IOException e) {
      throw new CompilerGeneratorPolicyParseException(
          "Failed to parse readiness YAML in " + sourceLabel, e);
    }
  }

  private static String textOrNull(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }
}
