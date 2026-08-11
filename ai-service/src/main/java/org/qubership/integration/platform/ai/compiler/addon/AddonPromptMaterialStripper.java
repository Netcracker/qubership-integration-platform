package org.qubership.integration.platform.ai.compiler.addon;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes machine-facing H2 sections from compiler skill addon markdown before prompt assembly.
 *
 * <p>Raw addon files stay intact for metadata parsers; only prompt material is stripped.
 */
public final class AddonPromptMaterialStripper {

  private static final Set<String> DENYLISTED_H2_TITLES =
      Set.of(
          "upstream",
          "runtime contract",
          "examples",
          "readiness signals",
          "runtime metadata",
          "open questions",
          "resolved");

  private static final Pattern H2_HEADING =
      Pattern.compile("(?m)^(##)\\s+(.+?)\\s*$");

  private AddonPromptMaterialStripper() {}

  /**
   * Returns addon markdown with denylisted {@code ##} sections removed. Unknown sections are kept.
   * Returns an empty string when nothing remains for the prompt.
   */
  public static String stripForPrompt(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }

    Matcher matcher = H2_HEADING.matcher(markdown);
    StringBuilder kept = new StringBuilder();
    int cursor = 0;
    boolean keepCurrent = true;

    while (matcher.find()) {
      if (keepCurrent) {
        kept.append(markdown, cursor, matcher.start());
      }
      String title = matcher.group(2).trim().toLowerCase(Locale.ROOT);
      keepCurrent = !DENYLISTED_H2_TITLES.contains(title);
      cursor = keepCurrent ? matcher.start() : matcher.end();
    }

    if (keepCurrent) {
      kept.append(markdown, cursor, markdown.length());
    }

    return kept.toString().strip();
  }
}
