package org.qubership.integration.platform.ai.llm.routing;

import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Optional;
import java.util.regex.Pattern;

/** Deterministic routing for a narrow set of intents before LLM classification. */
public final class RouterHeuristics {

  private static final Pattern IDS_ONLY =
      Pattern.compile("(?ius)\\bonly\\b.{0,160}\\b(design|ids|document|specification)\\b");

  private static final Pattern CATALOG_OPERATION_LOOKUP =
      Pattern.compile(
          "(?ius)\\b(find|search|look(?:\\s+up)?|locate|lookup)\\b.{0,220}\\b(operation|operations|endpoint|endpoints)\\b");

  private static final Pattern IMPORT_SPECIFICATION =
      Pattern.compile(
          "(?ius)\\b(import|upload)\\b.{0,120}\\b(specification|spec|api|openapi|swagger)\\b");

  private static final Pattern IMPORT_SPECIFICATION_CONFIRM =
      Pattern.compile("(?ius)^\\s*import\\s+specification\\b");

  private RouterHeuristics() {}

  public static Optional<ScenarioType> tryFastResolve(String userMessage) {
    if (userMessage == null || userMessage.isBlank()) {
      return Optional.empty();
    }
    String msg = userMessage.trim();

    // Import before IDS_ONLY: paste text often contains "…-only … design" which falsely matches
    // IDS_ONLY and skips cold IMPORT → soft-gather with importIntent (ADR 0001).
    if (IMPORT_SPECIFICATION.matcher(msg).find()) {
      return Optional.of(ScenarioType.IMPORT_SPECIFICATION);
    }

    if (IMPORT_SPECIFICATION_CONFIRM.matcher(msg).find()) {
      return Optional.of(ScenarioType.IMPORT_SPECIFICATION);
    }

    if (IDS_ONLY.matcher(msg).find()) {
      // Design / IDS-only phrasing remaps to product CREATE discovery (no IDS route).
      return Optional.of(ScenarioType.GATHER_REQUIREMENTS);
    }

    if (CATALOG_OPERATION_LOOKUP.matcher(msg).find()) {
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    return Optional.empty();
  }
}
