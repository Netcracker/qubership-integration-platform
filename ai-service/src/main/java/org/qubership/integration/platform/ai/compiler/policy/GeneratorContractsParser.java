package org.qubership.integration.platform.ai.compiler.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code GENERATOR_CONTRACTS.md} sections used by the policy builder. */
final class GeneratorContractsParser {

  private static final Pattern GEN_HEADING =
      Pattern.compile("^## (GEN-\\d+):\\s*(.+)$", Pattern.MULTILINE);
  private static final Pattern EXECUTION_ORDER_LINE =
      Pattern.compile("^\\s*\\d+\\.\\s+(GEN-\\d+)\\s+", Pattern.MULTILINE);

  record ParsedContract(String generatorId, String name) {}

  static List<String> parseExecutionOrder(String content) {
    int sectionStart = content.indexOf("## Generator Execution Order");
    if (sectionStart < 0) {
      throw new CompilerGeneratorPolicyParseException(
          "Missing section: ## Generator Execution Order");
    }
    String section = content.substring(sectionStart);
    Matcher matcher = EXECUTION_ORDER_LINE.matcher(section);
    List<String> order = new ArrayList<>();
    while (matcher.find()) {
      order.add(matcher.group(1));
    }
    if (order.isEmpty()) {
      throw new CompilerGeneratorPolicyParseException(
          "Generator Execution Order section contains no GEN entries");
    }
    return List.copyOf(order);
  }

  static Map<String, ParsedContract> parseContracts(String content) {
    Matcher headingMatcher = GEN_HEADING.matcher(content);
    List<int[]> headings = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    List<String> names = new ArrayList<>();
    while (headingMatcher.find()) {
      headings.add(new int[] {headingMatcher.start(), headingMatcher.end()});
      ids.add(headingMatcher.group(1));
      names.add(headingMatcher.group(2).trim());
    }
    Map<String, ParsedContract> contracts = new LinkedHashMap<>();
    for (int i = 0; i < headings.size(); i++) {
      contracts.put(ids.get(i), new ParsedContract(ids.get(i), names.get(i)));
    }
    if (contracts.isEmpty()) {
      throw new CompilerGeneratorPolicyParseException(
          "GENERATOR_CONTRACTS.md contains no GEN contract sections");
    }
    return Map.copyOf(contracts);
  }

  static String toPlanArtifact(String contractName) {
    String normalized =
        contractName
            .replace(" Generator", "")
            .replace(" Gen", "")
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    return normalized + "-plan.yaml";
  }

  private GeneratorContractsParser() {}
}