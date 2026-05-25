package org.qubership.integration.platform.ai.chat.chainplan;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.context.ConversationContextBuilder;
import org.qubership.integration.platform.ai.chat.context.ConversationContextBuilder.ContextAppendices;
import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.configuration.TranscriptLimits;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;

import java.util.regex.Pattern;

/**
 * Gate 1 free-text plan approval: intent extraction, deterministic veto, then LLM YES/NO
 * (fail-closed).
 */
@ApplicationScoped
public class PlanApprovalGate {

  private static final Logger LOG = Logger.getLogger(PlanApprovalGate.class);

  private static final String UI_APPENDIX_DASHES = "\n---\n";
  private static final String UI_APPENDIX_CHAIN = "\n## Current Chain";

  private static final Pattern VETO_CATALOG_EDIT =
      Pattern.compile(
          "(?ius)(\\b(move|transfer|delete|remove)\\b|update\\s+element|patch\\s+element)");

  private final PlanApprovalClassifier classifier;
  private final AppConfig appConfig;
  private final ConversationContextBuilder conversationContextBuilder;

  public PlanApprovalGate(
      PlanApprovalClassifier classifier,
      AppConfig appConfig,
      ConversationContextBuilder conversationContextBuilder) {
    this.classifier = classifier;
    this.appConfig = appConfig;
    this.conversationContextBuilder = conversationContextBuilder;
  }

  public boolean isPlanApproved(String conversationId, String fullUserText) {
    if (!appConfig.chainPlan().llmApprovalEnabled()) {
      return false;
    }
    String intentLine = extractIntent(fullUserText);
    if (vetoesApproval(intentLine)) {
      logVerdict(conversationId, "NO (veto)", intentLine);
      return false;
    }

    try {
      String context = buildContextBlock(conversationId);
      String verdict =
          classifier
              .classify(
                  QuteUserMessageEscaping.escapeForAiServiceUserMessage(context),
                  QuteUserMessageEscaping.escapeForAiServiceUserMessage(intentLine))
              .trim()
              .toUpperCase();
      boolean approved = "YES".equals(verdict);
      logVerdict(conversationId, verdict, intentLine);
      return approved;
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Plan approval classifier failed (fail-closed): conversationId=%s, intentPreview=%s",
          conversationId,
          preview(intentLine));
      return false;
    }
  }

  /** User intent before UI {@code ---} / {@code ## Current Chain} appendix. */
  static String extractIntent(String userText) {
    if (userText == null || userText.isBlank()) {
      return "";
    }
    String t = userText.trim();
    int dash = t.indexOf(UI_APPENDIX_DASHES);
    int chain = t.indexOf(UI_APPENDIX_CHAIN);
    int cut = -1;
    if (dash >= 0 && chain >= 0) {
      cut = Math.min(dash, chain);
    } else if (dash >= 0) {
      cut = dash;
    } else if (chain >= 0) {
      cut = chain;
    }
    return cut >= 0 ? t.substring(0, cut).trim() : t;
  }

  static boolean vetoesApproval(String intentLine) {
    if (intentLine == null || intentLine.isBlank()) {
      return true;
    }
    String msg = intentLine.trim();
    return UserIntentPatterns.matchesModifyPlan(msg) || VETO_CATALOG_EDIT.matcher(msg).find();
  }

  private String buildContextBlock(String conversationId) {
    return conversationContextBuilder.build(
        conversationId, guardrailLimits(), ContextAppendices.PLAN);
  }

  private TranscriptLimits guardrailLimits() {
    return TranscriptLimits.from(appConfig.guardrail());
  }

  private static void logVerdict(String conversationId, String verdict, String intentLine) {
    LOG.infof(
        "Plan approval classifier: conversationId=%s, verdict=%s, intentPreview=%s",
        conversationId, verdict, preview(intentLine));
  }

  private static String preview(String intentLine) {
    if (intentLine == null || intentLine.isEmpty()) {
      return "";
    }
    return intentLine.substring(0, Math.min(120, intentLine.length()))
        + (intentLine.length() > 120 ? "…" : "");
  }
}
