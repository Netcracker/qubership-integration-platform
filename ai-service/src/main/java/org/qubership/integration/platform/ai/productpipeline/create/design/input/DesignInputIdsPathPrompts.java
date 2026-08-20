package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.llm.agent.DesignInputPromptAgent;
import org.qubership.integration.platform.ai.productpipeline.create.ResponseLocaleResolver;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignMode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Design-input IDS path display prompts (LLM-authored) and machine routing to {@link DesignMode}.
 * English system instructions and English fallback copy live here; user-facing wording is produced
 * by {@link DesignInputPromptAgent} in the conversation language.
 */
public final class DesignInputIdsPathPrompts {

  public static final String PENDING_DESIGN_MODE_ATTR = "pendingDesignMode";

  /**
   * Asks for the document, not for a comparison.
   *
   * <p>What the pipeline does when the answer is no is an internal shortcut, and naming it invites
   * the reader to weigh two documents when only one of them will ever be shown.
   */
  static final String FALLBACK_IDS_PATH_CHOICE =
      "Do you want an integration design document (IDS) for these requirements?";

  /**
   * Durable delimiter between the short mapping-gap question and readable edge lines stored in the
   * wait reason. Parsed back into {@code missingEvidence} for the decision card; not shown as
   * prose above the card.
   */
  static final String MAPPING_GAP_EDGES_MARKER = "__MAPPING_EDGES__";

  /**
   * Short card question when mappings are missing. Edges and actions live on the decision card;
   * do not instruct the reader to type {@code PASS_THROUGH}.
   */
  static final String FALLBACK_MAPPING_GAP =
      "Some data mappings are still missing before design can continue. "
          + "Pass through the payload as-is, or describe the field mappings.";

  private static final Logger LOG = Logger.getLogger(DesignInputIdsPathPrompts.class);

  private final DesignInputPromptAgent promptAgent;

  public DesignInputIdsPathPrompts(DesignInputPromptAgent promptAgent) {
    this.promptAgent = promptAgent;
  }

  /** Test helper without LLM. */
  public DesignInputIdsPathPrompts() {
    this(null);
  }

  public String idsPathChoicePrompt(RequirementBrief brief, String... referenceTexts) {
    return idsPathChoicePrompt(ResponseLocaleResolver.DEFAULT_LOCALE, brief, referenceTexts);
  }

  public String idsPathChoicePrompt(
      String responseLocale, RequirementBrief brief, String... referenceTexts) {
    String reference = languageReference(brief, referenceTexts);
    if (promptAgent != null) {
      try {
        String authored = promptAgent.askIdsPathChoice(normalizedLocale(responseLocale), reference);
        if (authored != null && !authored.isBlank()) {
          return authored.trim();
        }
      } catch (RuntimeException ex) {
        LOG.warnf(ex, "IDS path choice prompt LLM failed; using English fallback");
      }
    }
    return FALLBACK_IDS_PATH_CHOICE;
  }

  public String mappingGapPrompt(
      RequirementBrief brief,
      DesignMode pendingMode,
      List<String> missingEdges,
      String... referenceTexts) {
    return mappingGapPrompt(
        ResponseLocaleResolver.DEFAULT_LOCALE, brief, pendingMode, missingEdges, referenceTexts);
  }

  public String mappingGapPrompt(
      String responseLocale,
      RequirementBrief brief,
      DesignMode pendingMode,
      List<String> missingEdges,
      String... referenceTexts) {
    Objects.requireNonNull(pendingMode, "pendingMode");
    Objects.requireNonNull(missingEdges, "missingEdges");
    String edges =
        missingEdges.isEmpty()
            ? "(none listed)"
            : String.join("\n", missingEdges.stream().map(edge -> "- " + edge).toList());
    String pendingLabel =
        pendingMode == DesignMode.GENERATE ? "GENERATE full IDS" : "DERIVE minimal IDS";
    String reference = languageReference(brief, referenceTexts);
    if (promptAgent != null) {
      try {
        String authored =
            promptAgent.askMappingGap(normalizedLocale(responseLocale), reference, edges, pendingLabel);
        if (authored != null && !authored.isBlank()) {
          return authored.trim();
        }
      } catch (RuntimeException ex) {
        LOG.warnf(ex, "Mapping gap prompt LLM failed; using English fallback");
      }
    }
    return FALLBACK_MAPPING_GAP;
  }

  /**
   * Packs the short question and readable edges into one wait reason so durable clarify can restore
   * both fields.
   */
  public static String encodeMappingGapWait(String question, List<String> readableEdges) {
    String q =
        question == null || question.isBlank() ? FALLBACK_MAPPING_GAP : question.strip();
    List<String> edges =
        readableEdges == null
            ? List.of()
            : readableEdges.stream()
                .filter(edge -> edge != null && !edge.isBlank())
                .map(String::strip)
                .toList();
    if (edges.isEmpty()) {
      return q;
    }
    return q + "\n\n" + MAPPING_GAP_EDGES_MARKER + "\n" + String.join("\n", edges);
  }

  /** Short question plus readable edge lines recovered from an encoded wait reason. */
  public record MappingGapView(String question, List<String> missingEdges) {
    public MappingGapView {
      question = question == null ? "" : question.strip();
      missingEdges = missingEdges == null ? List.of() : List.copyOf(missingEdges);
    }
  }

  public static MappingGapView parseMappingGapWait(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return new MappingGapView(FALLBACK_MAPPING_GAP, List.of());
    }
    String trimmed = prompt.strip();
    int marker = trimmed.indexOf(MAPPING_GAP_EDGES_MARKER);
    if (marker < 0) {
      return new MappingGapView(trimmed, List.of());
    }
    String question = trimmed.substring(0, marker).strip();
    String edgesBlock = trimmed.substring(marker + MAPPING_GAP_EDGES_MARKER.length()).strip();
    List<String> edges =
        edgesBlock.isBlank()
            ? List.of()
            : edgesBlock
                .lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .map(line -> line.startsWith("- ") ? line.substring(2).strip() : line)
                .toList();
    return new MappingGapView(
        question.isBlank() ? FALLBACK_MAPPING_GAP : question, edges);
  }

  /**
   * Resolves GENERATE / DERIVE from the user reply. English keyword fast-path first; optional LLM
   * classifier for other languages. Null when not a path choice.
   */
  public DesignMode resolveIdsPathChoice(String userText) {
    DesignMode fast = resolveIdsPathChoiceKeywords(userText);
    if (fast != null) {
      return fast;
    }
    if (userText == null || userText.isBlank() || promptAgent == null) {
      return null;
    }
    // Stage-approval tokens must never become GENERATE/DERIVE via the LLM classifier.
    if (isStageApprovalToken(userText)) {
      return null;
    }
    try {
      String token = normalizeToken(promptAgent.classifyIdsPathChoice(userText));
      if ("GENERATE".equals(token)) {
        return DesignMode.GENERATE;
      }
      if ("DERIVE".equals(token)) {
        return DesignMode.DERIVE;
      }
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "IDS path choice classification failed");
    }
    return null;
  }

  /**
   * English-only keyword routing. Used by design-input when classifying the current stage reply —
   * never run the LLM against stale discovery text carried in {@code userText}.
   */
  public static DesignMode resolveIdsPathChoiceKeywords(String userText) {
    if (userText == null || userText.isBlank()) {
      return null;
    }
    if (isStageApprovalToken(userText)) {
      return null;
    }
    String normalized = userText.toLowerCase(Locale.ROOT).trim();
    if (isGenerateChoice(normalized)) {
      return DesignMode.GENERATE;
    }
    if (isDeriveChoice(normalized)) {
      return DesignMode.DERIVE;
    }
    return null;
  }

  /** True for short stage-approval replies (Agree / approve / approved). */
  public static boolean isStageApprovalToken(String userText) {
    if (userText == null || userText.isBlank()) {
      return false;
    }
    String normalized = userText.toLowerCase(Locale.ROOT).trim();
    return normalized.equals("agree")
        || normalized.equals("approve")
        || normalized.equals("approved");
  }

  public boolean isPassThroughConfirmation(String userText) {
    if (isPassThroughKeyword(userText)) {
      return true;
    }
    if (userText == null || userText.isBlank() || promptAgent == null) {
      return false;
    }
    try {
      return "PASS_THROUGH".equals(normalizeToken(promptAgent.classifyMappingReply(userText)));
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "Mapping reply classification failed");
      return false;
    }
  }

  static boolean isPassThroughKeyword(String userText) {
    if (userText == null || userText.isBlank()) {
      return false;
    }
    String normalized = userText.toLowerCase(Locale.ROOT).trim();
    return normalized.contains("pass_through")
        || normalized.contains("pass-through")
        || normalized.contains("passthrough")
        || normalized.contains("pass through")
        || normalized.equals("agree");
  }

  public static String languageReference(RequirementBrief brief, String... referenceTexts) {
    StringBuilder sample = new StringBuilder();
    if (brief != null) {
      if (brief.summary() != null && !brief.summary().isBlank()) {
        sample.append(brief.summary().trim());
      } else if (brief.goal() != null && !brief.goal().isBlank()) {
        sample.append(brief.goal().trim());
      }
      if (brief.approvedDraftText() != null && !brief.approvedDraftText().isBlank()) {
        if (!sample.isEmpty()) {
          sample.append('\n');
        }
        sample.append(brief.approvedDraftText().trim());
      }
    }
    if (referenceTexts != null) {
      for (String text : referenceTexts) {
        if (text != null && !text.isBlank()) {
          if (!sample.isEmpty()) {
            sample.append('\n');
          }
          sample.append(text.trim());
        }
      }
    }
    return sample.isEmpty() ? "Create an integration chain." : sample.toString();
  }

  private static String normalizeToken(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    int newline = trimmed.indexOf('\n');
    if (newline >= 0) {
      trimmed = trimmed.substring(0, newline).trim();
    }
    return trimmed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
  }

  private static String normalizedLocale(String responseLocale) {
    return responseLocale == null || responseLocale.isBlank()
        ? ResponseLocaleResolver.DEFAULT_LOCALE
        : responseLocale.trim();
  }

  private static boolean isGenerateChoice(String normalized) {
    if (normalized.contains("derive")
        && (normalized.contains("no") || normalized.contains("minimal") || normalized.contains("skip"))) {
      return false;
    }
    if (normalized.equals("yes")
        || normalized.equals("y")
        || normalized.contains("write the document")) {
      return true;
    }
    return normalized.contains("generate full ids")
        || normalized.contains("generate the full")
        || (normalized.contains("generate") && normalized.contains("ids"))
        || (normalized.contains("generate") && normalized.contains("document"));
  }

  private static boolean isDeriveChoice(String normalized) {
    if (normalized.equals("no")
        || normalized.equals("n")
        || normalized.contains("carry on without")) {
      return true;
    }
    return normalized.contains("derive minimal ids")
        || normalized.contains("derive minimal")
        || (normalized.contains("derive") && normalized.contains("ids"))
        || (normalized.contains("minimal") && normalized.contains("ids"))
        || normalized.contains("skip full")
        || normalized.contains("skip generation");
  }

  /** Test double: fixed prompts, keyword-only classification. */
  public static DesignInputIdsPathPrompts withFixedPrompts(
      Function<String, String> idsChoiceAuthor, Function<String, String> mappingAuthor) {
    DesignInputPromptAgent stub =
        new DesignInputPromptAgent() {
          @Override
          public String askIdsPathChoice(String responseLocale, String reference) {
            return idsChoiceAuthor.apply(reference);
          }

          @Override
          public String askMappingGap(
              String responseLocale, String reference, String missingEdges, String pendingMode) {
            return mappingAuthor.apply(missingEdges);
          }

          @Override
          public String classifyIdsPathChoice(String userText) {
            DesignMode mode = resolveIdsPathChoiceKeywords(userText);
            if (mode == DesignMode.GENERATE) {
              return "GENERATE";
            }
            if (mode == DesignMode.DERIVE) {
              return "DERIVE";
            }
            return "NONE";
          }

          @Override
          public String classifyMappingReply(String userText) {
            return isPassThroughKeyword(userText) ? "PASS_THROUGH" : "NONE";
          }
        };
    return new DesignInputIdsPathPrompts(stub);
  }
}
