package org.qubership.integration.platform.ai.productpipeline.facade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names the gate a wait belongs to, so the chat can pick a card without reading the prose.
 *
 * <p>A wait prompt is authored by a model in the language of the conversation. Deciding which card
 * to render by matching English words in it works until the first reply in another language, which
 * is the bug this replaces. The gate id travels as a marker inside the prompt because a wait is
 * re-emitted from its durable transition reason on resume — a field on the signal would not
 * survive that, and the reason is a string.
 *
 * <p>The marker never reaches a reader: every path that turns a prompt into chat text strips it.
 */
public final class PipelineGates {

  /** The API Hub specification is selected and waiting to be imported into the catalog. */
  public static final String IMPORT_SPECIFICATION = "import-specification";

  /** The run asks whether to write an integration design document. */
  public static final String IDS_PATH_CHOICE = "ids-path-choice";

  /** The design needs field mappings, or permission to pass the payload through as-is. */
  public static final String MAPPING_GAP = "mapping-gap";

  /** The current stage halted; Retry re-enters it without treating the click as new requirements. */
  public static final String STAGE_RETRY = "stage-retry";

  /**
   * Validation, domain, or contract halt with a diagnosed owner. Retry and Revise are both on the
   * card; Revise does not overwrite requirements.
   */
  public static final String STAGE_REVISE = "stage-revise";

  /**
   * More than one owner stayed plausible. Actions are the candidate stage ids in missing evidence,
   * not free text.
   */
  public static final String OWNER_CHOICE = "owner-choice";

  /** Wire action for {@link #STAGE_RETRY}. */
  public static final String RETRY_ACTION = "retry";

  /** Wire action for {@link #STAGE_REVISE}. */
  public static final String REVISE_ACTION = "revise";

  /**
   * Durable delimiter between the halt narrative and candidate stage ids on an owner-choice wait.
   * Parsed back into missing evidence; stripped before a reader sees the prompt.
   */
  static final String OWNER_CANDIDATES_MARKER = "__OWNER_CANDIDATES__";

  /**
   * True when {@code action} is a halt-card button (Retry or Revise). A typed follow-up is not a
   * halt-card action.
   */
  public static boolean isHaltCardAction(String action) {
    return RETRY_ACTION.equals(action) || REVISE_ACTION.equals(action);
  }

  /** True when the wait is a recoverable halt (Retry, Revise, or owner choice). */
  public static boolean isRecoverableHaltGate(String gateId) {
    return STAGE_RETRY.equals(gateId)
        || STAGE_REVISE.equals(gateId)
        || OWNER_CHOICE.equals(gateId);
  }

  private static final Pattern MARKER = Pattern.compile("__GATE:([a-z0-9-]+)__");

  private PipelineGates() {}

  /** Marks {@code prompt} as belonging to {@code gateId}; a prompt already marked is left alone. */
  public static String tag(String gateId, String prompt) {
    String text = prompt == null ? "" : prompt;
    if (gateId == null || gateId.isBlank() || gateOf(text).isPresent()) {
      return text;
    }
    return "__GATE:" + gateId + "__" + text;
  }

  /** Replaces any existing gate marker with {@code gateId}. */
  public static String retag(String gateId, String prompt) {
    return tag(gateId, strip(prompt));
  }

  /**
   * Owner-choice wait: gate plus narrative, then candidate stage ids after {@link
   * #OWNER_CANDIDATES_MARKER}.
   */
  public static String tagOwnerChoice(String prompt, List<String> stageIds) {
    String tagged = tag(OWNER_CHOICE, prompt);
    if (stageIds == null || stageIds.isEmpty()) {
      return tagged;
    }
    List<String> ids = new ArrayList<>();
    for (String stageId : stageIds) {
      if (stageId != null && !stageId.isBlank()) {
        ids.add(stageId.trim());
      }
    }
    if (ids.isEmpty()) {
      return tagged;
    }
    return tagged + OWNER_CANDIDATES_MARKER + String.join(",", ids);
  }

  /** Candidate stage ids encoded on an owner-choice wait, or empty. */
  public static List<String> ownerCandidatesOf(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return List.of();
    }
    int marker = prompt.indexOf(OWNER_CANDIDATES_MARKER);
    if (marker < 0) {
      return List.of();
    }
    String raw = prompt.substring(marker + OWNER_CANDIDATES_MARKER.length()).strip();
    if (raw.isBlank()) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (String part : raw.split(",")) {
      if (part != null && !part.isBlank()) {
        ids.add(part.trim());
      }
    }
    return List.copyOf(ids);
  }

  /** The gate this prompt names, or empty when it names none. */
  public static Optional<String> gateOf(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = MARKER.matcher(prompt);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  /** The prompt as a reader should see it. Safe to call on text that carries no marker. */
  public static String strip(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return "";
    }
    String withoutGate = MARKER.matcher(prompt).replaceAll("");
    int marker = withoutGate.indexOf(OWNER_CANDIDATES_MARKER);
    if (marker >= 0) {
      withoutGate = withoutGate.substring(0, marker);
    }
    return withoutGate.strip();
  }
}
