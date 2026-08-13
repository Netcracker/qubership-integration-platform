package org.qubership.integration.platform.ai.productpipeline.facade;

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
    return MARKER.matcher(prompt).replaceAll("").strip();
  }
}
