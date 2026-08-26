package org.qubership.integration.platform.ai.productpipeline.facade;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
   * The run needs one missing fact from the author. The next typed answer resumes the owning
   * producer; it is not a recoverable halt card.
   */
  public static final String STAGE_CLARIFICATION = "stage-clarification";

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

  /**
   * The stage broke an invariant inside the service. Retry re-enters the same defect, so the card
   * binds upstream producer stage ids. Without a producer, the run stays waiting for conversation.
   */
  public static final String STAGE_INTERNAL_FAILURE = "stage-internal-failure";

  /** The same failure repeated enough times that retry is no longer an offered exit. */
  public static final String STAGE_ESCALATED = "stage-escalated";

  /** Wire action for {@link #STAGE_RETRY}. */
  public static final String RETRY_ACTION = "retry";

  /** Wire action for {@link #STAGE_REVISE}. */
  public static final String REVISE_ACTION = "revise";

  /** Ends an escalated run while keeping its durable failure evidence. */
  public static final String STOP_WITH_REPORT_ACTION = "stop-with-report";

  /** Removes a blocking element when the profile declares that the stage can be skipped. */
  public static final String DROP_ELEMENT_ACTION = "drop-element";

  /**
   * Durable delimiter between the halt narrative and candidate stage ids on an owner-choice wait.
   * Parsed back into missing evidence; stripped before a reader sees the prompt.
   */
  static final String OWNER_CANDIDATES_MARKER = "__OWNER_CANDIDATES__";

  private static final String DROP_ELEMENT_MARKER = "__DROP_ELEMENT_ALLOWED__";
  private static final String HALT_IDENTITY_MARKER = "__HALT_IDENTITY__";

  /**
   * True when {@code action} is a halt-card button (Retry or Revise). A typed follow-up is not a
   * halt-card action.
   */
  public static boolean isHaltCardAction(String action) {
    return RETRY_ACTION.equals(action) || REVISE_ACTION.equals(action);
  }

  /**
   * True when the wait is a recoverable halt (Retry, Revise, internal failure, or owner choice). A
   * typed follow-up at such a wait stays on the run instead of being classified as a new request.
   */
  public static boolean isRecoverableHaltGate(String gateId) {
    return STAGE_RETRY.equals(gateId)
        || STAGE_REVISE.equals(gateId)
        || STAGE_INTERNAL_FAILURE.equals(gateId)
        || STAGE_ESCALATED.equals(gateId)
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
    String tagged = retag(OWNER_CHOICE, prompt);
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

  /** Binds an internal-failure card to the upstream stages it can reopen. */
  public static String tagInternalFailure(String prompt, List<String> stageIds) {
    String tagged = retag(STAGE_INTERNAL_FAILURE, prompt);
    List<String> ids = cleanStageIds(stageIds);
    return ids.isEmpty()
        ? tagged
        : tagged + OWNER_CANDIDATES_MARKER + String.join(",", ids);
  }

  /** Marks a repeated-failure wait and keeps its exits and identity on the durable prompt. */
  public static String tagEscalated(
      String prompt, List<String> stageIds, boolean dropAllowed, String haltIdentity) {
    String tagged = retag(STAGE_ESCALATED, prompt);
    List<String> ids = cleanStageIds(stageIds);
    if (!ids.isEmpty()) {
      tagged += OWNER_CANDIDATES_MARKER + String.join(",", ids);
    }
    if (dropAllowed) {
      tagged += DROP_ELEMENT_MARKER;
    }
    return tagHaltIdentity(tagged, haltIdentity);
  }

  /** Adds the normalized halt identity to a wait without exposing it to the reader. */
  public static String tagHaltIdentity(String prompt, String haltIdentity) {
    if (haltIdentity == null || haltIdentity.isBlank()) {
      return prompt == null ? "" : prompt;
    }
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(haltIdentity.getBytes(StandardCharsets.UTF_8));
    return (prompt == null ? "" : prompt) + HALT_IDENTITY_MARKER + encoded;
  }

  /** Normalized identity stored on a halt transition, or empty for an older transition. */
  public static Optional<String> haltIdentityOf(String prompt) {
    String encoded = markerValue(prompt, HALT_IDENTITY_MARKER);
    if (encoded.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  /** Whether the escalated card may offer dropping the blocking element. */
  public static boolean dropElementAllowed(String prompt) {
    return prompt != null && prompt.contains(DROP_ELEMENT_MARKER);
  }

  /** Ordered actions on an escalated card: producer stages, optional drop, then stop. */
  public static List<String> escalatedActionsOf(String prompt) {
    List<String> actions = new ArrayList<>(ownerCandidatesOf(prompt));
    if (dropElementAllowed(prompt)) {
      actions.add(DROP_ELEMENT_ACTION);
    }
    actions.add(STOP_WITH_REPORT_ACTION);
    return List.copyOf(actions);
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
    String raw = markerValue(prompt, OWNER_CANDIDATES_MARKER);
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
    int marker = firstInternalMarker(withoutGate);
    if (marker >= 0) {
      withoutGate = withoutGate.substring(0, marker);
    }
    return withoutGate.strip();
  }

  private static List<String> cleanStageIds(List<String> stageIds) {
    if (stageIds == null || stageIds.isEmpty()) {
      return List.of();
    }
    return stageIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .map(String::trim)
        .toList();
  }

  private static String markerValue(String prompt, String marker) {
    if (prompt == null || prompt.isBlank()) {
      return "";
    }
    int start = prompt.indexOf(marker);
    if (start < 0) {
      return "";
    }
    start += marker.length();
    int end = prompt.length();
    for (String candidate :
        List.of(OWNER_CANDIDATES_MARKER, DROP_ELEMENT_MARKER, HALT_IDENTITY_MARKER)) {
      int next = prompt.indexOf(candidate, start);
      if (next >= 0 && next < end) {
        end = next;
      }
    }
    return prompt.substring(start, end).strip();
  }

  private static int firstInternalMarker(String prompt) {
    int first = -1;
    for (String marker :
        List.of(OWNER_CANDIDATES_MARKER, DROP_ELEMENT_MARKER, HALT_IDENTITY_MARKER)) {
      int candidate = prompt.indexOf(marker);
      if (candidate >= 0 && (first < 0 || candidate < first)) {
        first = candidate;
      }
    }
    return first;
  }
}
