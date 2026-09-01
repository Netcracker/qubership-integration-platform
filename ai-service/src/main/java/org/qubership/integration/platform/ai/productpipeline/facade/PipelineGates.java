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

  /** A temporary dependency failure for which another create attempt can change the outcome. */
  public static final String RECOVERY_RETRY_TECHNICAL = "recovery-retry-technical";

  /** A failed execution output that can be regenerated from the approved inputs. */
  public static final String RECOVERY_REGENERATE_EXECUTION =
      "recovery-regenerate-execution";

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
   * binds upstream producer stage ids. Without a producer, the card offers Stop with report.
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
  private static final String GUARD_MARKER = "__GUARD__";
  private static final String RECOVERY_TECHNICAL_DETAILS_MARKER =
      "__RECOVERY_TECHNICAL_DETAILS__";
  private static final String RECOVERY_RETRY_DELAY_MARKER = "__RECOVERY_RETRY_DELAY_MS__";

  /**
   * True when {@code action} is a halt-card button (Retry or Revise). A typed follow-up is not a
   * halt-card action.
   */
  public static boolean isHaltCardAction(String action) {
    return RETRY_ACTION.equals(action) || REVISE_ACTION.equals(action);
  }

  /**
   * True when the wait is a recoverable halt (Retry, Revise, internal failure, owner choice, or
   * clarification). A typed follow-up at such a wait stays on the run instead of being classified
   * as a new request.
   */
  public static boolean isRecoverableHaltGate(String gateId) {
    return RECOVERY_RETRY_TECHNICAL.equals(gateId)
        || RECOVERY_REGENERATE_EXECUTION.equals(gateId)
        || STAGE_RETRY.equals(gateId)
        || STAGE_REVISE.equals(gateId)
        || STAGE_INTERNAL_FAILURE.equals(gateId)
        || STAGE_ESCALATED.equals(gateId)
        || OWNER_CHOICE.equals(gateId)
        || STAGE_CLARIFICATION.equals(gateId);
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

  /** Records which recovery guard authored this wait. Stripped before a reader sees the prompt. */
  public static String tagGuard(String prompt, String guardName) {
    if (guardName == null || guardName.isBlank()) {
      return prompt == null ? "" : prompt;
    }
    String text = prompt == null ? "" : prompt;
    if (guardOf(text).isPresent()) {
      return text;
    }
    return text + GUARD_MARKER + guardName.trim();
  }

  /** Keeps raw recovery evidence and an optional retry delay on the durable wait. */
  public static String tagRecoveryDetails(
      String prompt, String technicalDetails, Long retryDelayMs) {
    String tagged = prompt == null ? "" : prompt;
    if (technicalDetails != null && !technicalDetails.isBlank()) {
      String encoded =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(technicalDetails.getBytes(StandardCharsets.UTF_8));
      tagged += RECOVERY_TECHNICAL_DETAILS_MARKER + encoded;
    }
    if (retryDelayMs != null && retryDelayMs > 0L) {
      tagged += RECOVERY_RETRY_DELAY_MARKER + retryDelayMs;
    }
    return tagged;
  }

  /** Raw technical evidence stored on a contextual recovery wait. */
  public static Optional<String> recoveryTechnicalDetailsOf(String prompt) {
    String encoded = markerValue(prompt, RECOVERY_TECHNICAL_DETAILS_MARKER);
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

  /** Delay before a contextual retry becomes useful. */
  public static Optional<Long> recoveryRetryDelayMsOf(String prompt) {
    String value = markerValue(prompt, RECOVERY_RETRY_DELAY_MARKER);
    if (value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException malformed) {
      return Optional.empty();
    }
  }

  /** Guard recorded on a wait, or empty when the wait names none. */
  public static Optional<String> guardOf(String prompt) {
    String value = markerValue(prompt, GUARD_MARKER);
    return value.isBlank() ? Optional.empty() : Optional.of(value);
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

  /**
   * Actions an internal-failure card offers: bound producer stage ids, or Stop with report when
   * the candidate set is empty so the author still has an exit.
   */
  public static List<String> internalFailureActionsOf(String prompt) {
    List<String> owners = ownerCandidatesOf(prompt);
    if (!owners.isEmpty()) {
      return owners;
    }
    return List.of(STOP_WITH_REPORT_ACTION);
  }

  /**
   * Rebuilds {@code prompt} with a new reader-visible body, keeping the gate, owner candidates,
   * drop marker, halt identity, and guard.
   */
  public static String withStrippedBody(String prompt, String body) {
    String text = body == null ? "" : body;
    if (prompt == null || prompt.isBlank()) {
      return text;
    }
    String gate = gateOf(prompt).orElse("");
    List<String> owners = ownerCandidatesOf(prompt);
    boolean drop = dropElementAllowed(prompt);
    String identity = haltIdentityOf(prompt).orElse("");
    String guard = guardOf(prompt).orElse("");
    String technicalDetails = recoveryTechnicalDetailsOf(prompt).orElse("");
    Long retryDelayMs = recoveryRetryDelayMsOf(prompt).orElse(null);
    String rebuilt;
    if (STAGE_ESCALATED.equals(gate)) {
      rebuilt = tagEscalated(text, owners, drop, identity);
    } else if (STAGE_INTERNAL_FAILURE.equals(gate)) {
      rebuilt = tagInternalFailure(text, owners);
      if (!identity.isBlank()) {
        rebuilt = tagHaltIdentity(rebuilt, identity);
      }
    } else if (OWNER_CHOICE.equals(gate)) {
      rebuilt = tagOwnerChoice(text, owners);
      if (!identity.isBlank()) {
        rebuilt = tagHaltIdentity(rebuilt, identity);
      }
    } else if (!gate.isBlank()) {
      rebuilt = retag(gate, text);
      if (!identity.isBlank()) {
        rebuilt = tagHaltIdentity(rebuilt, identity);
      }
    } else {
      rebuilt = text;
    }
    if (!guard.isBlank()) {
      rebuilt = tagGuard(rebuilt, guard);
    }
    return tagRecoveryDetails(rebuilt, technicalDetails, retryDelayMs);
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
        List.of(
            OWNER_CANDIDATES_MARKER,
            DROP_ELEMENT_MARKER,
            HALT_IDENTITY_MARKER,
            GUARD_MARKER,
            RECOVERY_TECHNICAL_DETAILS_MARKER,
            RECOVERY_RETRY_DELAY_MARKER)) {
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
        List.of(
            OWNER_CANDIDATES_MARKER,
            DROP_ELEMENT_MARKER,
            HALT_IDENTITY_MARKER,
            GUARD_MARKER,
            RECOVERY_TECHNICAL_DETAILS_MARKER,
            RECOVERY_RETRY_DELAY_MARKER)) {
      int candidate = prompt.indexOf(marker);
      if (candidate >= 0 && (first < 0 || candidate < first)) {
        first = candidate;
      }
    }
    return first;
  }
}
