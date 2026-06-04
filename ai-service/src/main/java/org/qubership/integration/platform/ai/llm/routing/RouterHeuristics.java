package org.qubership.integration.platform.ai.llm.routing;

import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Optional;
import java.util.regex.Pattern;

/** Deterministic routing for a very narrow set of intents before embedding / LLM classification. */
public final class RouterHeuristics {

  private static final Pattern IDS_ONLY =
      Pattern.compile("(?ius)\\bonly\\b.{0,160}\\b(design|ids|document|specification)\\b");

  /**
   * User wants to locate a concrete REST/catalog operation (not abstract IDS prose only). Route to
   * CREATE_CHAIN_PLAN so catalog read tools run.
   */
  private static final Pattern CATALOG_OPERATION_LOOKUP =
      Pattern.compile(
          "(?ius)\\b(find|search|look(?:\\s+up)?|locate|lookup)\\b.{0,220}\\b(operation|operations|endpoint|endpoints)\\b");

  /** User wants to import an ApiHub specification saved during planning. */
  private static final Pattern IMPORT_SPECIFICATION =
      Pattern.compile(
          "(?ius)\\b(import|upload)\\b.{0,120}\\b(specification|spec|api|openapi|swagger)\\b");

  /** HITL or UI answer confirming import before planning continues. */
  private static final Pattern IMPORT_SPECIFICATION_CONFIRM =
      Pattern.compile("(?ius)^\\s*import\\s+specification\\b");

  private static final Pattern CONFIRM_SHORT =
      Pattern.compile(
          "(?ius)^\\s*(yes|yep|yeah|ok|agree|confirm|confirmed|proceed|\\+)\\b[!.]?\\s*$");

  private static final Pattern YES_NO_WORD = Pattern.compile("(?iu)\\b(no|yes)\\b");

  private RouterHeuristics() {}

  /**
   * Fast path: only high-precision patterns retained (design-only disambiguation).
   *
   * @param recentTranscript labeled transcript (may already include active plan appendix)
   * @param planApproved whether the active plan for this conversation was approved (after {@code
   *     onUserMessage})
   */
  @SuppressWarnings("unused")
  public static Optional<ScenarioType> tryFastResolve(
      String userMessage,
      String recentTranscript,
      Optional<ActiveChainPlanSnapshot> activePlan,
      boolean planApproved) {
    if (userMessage == null || userMessage.isBlank()) {
      return Optional.empty();
    }
    String msg = userMessage.trim();

    if (IDS_ONLY.matcher(msg).find()) {
      return Optional.of(ScenarioType.CREATE_DESIGN);
    }

    if (CATALOG_OPERATION_LOOKUP.matcher(msg).find()) {
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    if (IMPORT_SPECIFICATION.matcher(msg).find()) {
      return Optional.of(ScenarioType.IMPORT_SPECIFICATION);
    }

    if (IMPORT_SPECIFICATION_CONFIRM.matcher(msg).find()) {
      return Optional.of(ScenarioType.IMPORT_SPECIFICATION);
    }

    return Optional.empty();
  }

  /**
   * Short confirmations / checklist answers when an implementation plan exists (guardrail bias).
   */
  public static boolean isShortConfirmationOrChecklist(String message) {
    if (message == null) {
      return false;
    }
    String msg = message.trim();
    if (CONFIRM_SHORT.matcher(msg).matches()) {
      return true;
    }
    return msg.length() < 500
        && (Pattern.compile("(?m)^\\s*\\d+\\.\\s+\\S+").matcher(msg).find()
            || YES_NO_WORD.matcher(msg).find());
  }
}
