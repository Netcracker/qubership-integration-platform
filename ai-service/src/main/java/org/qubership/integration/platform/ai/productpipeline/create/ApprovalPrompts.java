package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;

/**
 * LLM-authored approval / implement CTAs for create-chain@2 chat. English fallbacks only when the
 * prompt agent is absent or fails.
 */
public final class ApprovalPrompts {

  static final String FALLBACK_STAGE_APPROVAL =
      "Approve this candidate, or describe what to change.";

  static final String FALLBACK_IMPLEMENT =
      "Create the chain now, or describe what to change.";

  private static final Logger LOG = Logger.getLogger(ApprovalPrompts.class);

  private final ApprovalPromptAgent promptAgent;

  public ApprovalPrompts(ApprovalPromptAgent promptAgent) {
    this.promptAgent = promptAgent;
  }

  /** Test / runtime helper without LLM. */
  public ApprovalPrompts() {
    this(null);
  }

  public String stageApprovalPrompt(String stageId, String languageReference) {
    String reference =
        languageReference == null || languageReference.isBlank()
            ? "Create an integration chain."
            : languageReference.trim();
    String stage = stageId == null || stageId.isBlank() ? "current" : stageId.trim();
    if (promptAgent != null) {
      try {
        String authored = promptAgent.askStageApproval(stage, reference);
        if (authored != null && !authored.isBlank()) {
          return authored.trim();
        }
      } catch (RuntimeException ex) {
        LOG.warnf(ex, "Stage approval prompt LLM failed; using English fallback");
      }
    }
    return FALLBACK_STAGE_APPROVAL;
  }

  public String implementContinuationPrompt(String languageReference) {
    String reference =
        languageReference == null || languageReference.isBlank()
            ? "Create an integration chain."
            : languageReference.trim();
    if (promptAgent != null) {
      try {
        String authored = promptAgent.askImplementContinuation(reference);
        if (authored != null && !authored.isBlank()) {
          return authored.trim();
        }
      } catch (RuntimeException ex) {
        LOG.warnf(ex, "Implement continuation prompt LLM failed; using English fallback");
      }
    }
    return FALLBACK_IMPLEMENT;
  }

  /** Test double with fixed prompt text. */
  public static ApprovalPrompts withFixedPrompts(
      BiFunction<String, String, String> stageAuthor, Function<String, String> implementAuthor) {
    Objects.requireNonNull(stageAuthor, "stageAuthor");
    Objects.requireNonNull(implementAuthor, "implementAuthor");
    ApprovalPromptAgent stub =
        new ApprovalPromptAgent() {
          @Override
          public String askStageApproval(String stageId, String reference) {
            return stageAuthor.apply(stageId, reference);
          }

          @Override
          public String askImplementContinuation(String reference) {
            return implementAuthor.apply(reference);
          }

          @Override
          public String askImportConfirmation(String specification, String reference) {
            // Import questions are authored by the chat decision service, not by this double.
            return null;
          }
        };
    return new ApprovalPrompts(stub);
  }
}
