package org.qubership.integration.platform.ai.compiler.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the Generator Summary table from {@code generator-rule-mapping.md}. */
final class GeneratorRuleMappingParser {

  private static final Pattern SUMMARY_ROW =
      Pattern.compile("^\\|\\s*(GEN-\\d+)\\b[^|]*\\|\\s*\\d+\\s*\\|\\s*([^|]+?)\\s*\\|", Pattern.MULTILINE);
  private static final Pattern RULE_ID = Pattern.compile("R-\\d+");

  static Map<String, List<String>> parseGeneratorSummary(String content) {
    int sectionStart = content.indexOf("## Generator Summary");
    if (sectionStart < 0) {
      throw new CompilerGeneratorPolicyParseException("Missing section: ## Generator Summary");
    }
    Matcher matcher = SUMMARY_ROW.matcher(content.substring(sectionStart));
    Map<String, List<String>> summary = new LinkedHashMap<>();
    while (matcher.find()) {
      String generatorId = matcher.group(1);
      Matcher ruleMatcher = RULE_ID.matcher(matcher.group(2));
      List<String> rules = new ArrayList<>();
      while (ruleMatcher.find()) {
        rules.add(ruleMatcher.group());
      }
      summary.put(generatorId, List.copyOf(rules));
    }
    if (summary.isEmpty()) {
      throw new CompilerGeneratorPolicyParseException(
          "Generator Summary table contains no GEN rows");
    }
    return Map.copyOf(summary);
  }

  private GeneratorRuleMappingParser() {}
}
