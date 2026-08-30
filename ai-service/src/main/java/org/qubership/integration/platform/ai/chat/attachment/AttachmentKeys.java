package org.qubership.integration.platform.ai.chat.attachment;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/** Shared validation and normalization for S3 object keys used as chat attachments. */
public final class AttachmentKeys {

  private static final Logger LOG = Logger.getLogger(AttachmentKeys.class);

  private static final Pattern BULLET =
      Pattern.compile("^\\s*(?:[-*]|\\d+\\.)\\s+(\\S+)\\s*$");
  private static final Pattern KEY_PARAM = Pattern.compile("[?&]key=([^&]+)");

  private AttachmentKeys() {}

  /** Returns true when {@code key} is non-null, non-blank, and contains no path-traversal segment. */
  public static boolean isSafe(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    for (String segment : key.split("/")) {
      if ("..".equals(segment)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Normalizes raw attachment-key entries. Each entry may be:
   *
   * <ul>
   *   <li>a single S3 object key
   *   <li>a newline-separated list of keys
   *   <li>a markdown bullet line that contains a download URL
   *   <li>a URL with a {@code key} query parameter
   * </ul>
   *
   * Returns a de-duplicated list of safe S3 object keys, preserving insertion order.
   */
  public static List<String> normalize(Collection<String> rawKeys) {
    if (rawKeys == null || rawKeys.isEmpty()) {
      return List.of();
    }
    Set<String> seen = new LinkedHashSet<>();
    for (String raw : rawKeys) {
      if (raw == null) {
        continue;
      }
      for (String line : raw.split("\\R")) {
        String token = line.trim();
        if (token.isEmpty()) {
          continue;
        }
        Matcher bullet = BULLET.matcher(token);
        if (bullet.matches()) {
          token = bullet.group(1);
        }
        Matcher keyParam = KEY_PARAM.matcher(token);
        if (keyParam.find()) {
          token = urlDecode(keyParam.group(1));
        } else if (looksLikeUrl(token)) {
          LOG.warnf("Ignoring attachment URL without key parameter: %s", token);
          continue;
        }
        token = token.trim();
        if (isSafe(token)) {
          seen.add(token);
        } else {
          LOG.warnf("Rejecting unsafe attachment key: %s", token);
        }
      }
    }
    return List.copyOf(seen);
  }

  private static String urlDecode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      LOG.warnf(e, "Bad URL-encoded key parameter: %s", value);
      return value;
    }
  }

  private static boolean looksLikeUrl(String value) {
    return value.regionMatches(true, 0, "http://", 0, 7)
        || value.regionMatches(true, 0, "https://", 0, 8);
  }
}
