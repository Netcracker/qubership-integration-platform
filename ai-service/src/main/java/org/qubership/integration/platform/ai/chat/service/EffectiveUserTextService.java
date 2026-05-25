package org.qubership.integration.platform.ai.chat.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.storage.S3Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the full user prompt: {@code message + separator + attachment} where attachment is inline
 * text, and/or S3 object bodies loaded by {@code attachmentObjectKeys}, and/or legacy download URLs
 * in the attachment string.
 */
@ApplicationScoped
public class EffectiveUserTextService {

  private static final Logger LOG = Logger.getLogger(EffectiveUserTextService.class);

  private static final Pattern BULLET_URL = Pattern.compile("^\\s*-\\s+(\\S+)\\s*$");
  private static final Pattern KEY_PARAM = Pattern.compile("[?&]key=([^&]+)");

  @Inject S3Service s3Service;

  @Inject ConversationService conversationService;

  /**
   * Resolves object keys and inlines S3/legacy URLs; registers allowed keys for the conversation.
   * Call after {@code ConversationService#getOrCreate} so per-conversation state exists.
   */
  public String resolve(ChatRequest request, String conversationId) {
    String message = request.getMessage() != null ? request.getMessage() : "";
    List<String> requestedKeys = normalizeObjectKeys(request.getAttachmentObjectKeys());
    conversationService.registerAllowedAttachmentKeys(conversationId, requestedKeys);

    String maybeBlank = request.getAttachment();
    if (maybeBlank != null && !maybeBlank.isBlank()) {
      conversationService.registerAllowedAttachmentKeys(
          conversationId, extractKeysFromLegacyText(maybeBlank));
    }
    boolean hasLegacy = maybeBlank != null && !maybeBlank.isBlank();

    List<String> keysToInline = resolveKeysToInline(requestedKeys, conversationId);
    if (keysToInline.isEmpty() && !hasLegacy) {
      return message;
    }

    String keyBlocks = keysToInline.isEmpty() ? "" : inlineObjectKeys(conversationId, keysToInline);

    if (hasLegacy) {
      String legacyExpanded = expandStorageObjectUrls(maybeBlank);
      if (!keyBlocks.isEmpty()) {
        return message + "\n\n---\n\n" + keyBlocks + "\n\n---\n\n" + legacyExpanded;
      }
      return message + "\n\n---\n\n" + legacyExpanded;
    }
    if (keyBlocks.isEmpty()) {
      return message;
    }
    return message + "\n\n---\n\n" + keyBlocks;
  }

  /**
   * Keys to load from S3 this turn: request keys when present, otherwise all allowed keys for the
   * conversation.
   */
  private List<String> resolveKeysToInline(List<String> requestedKeys, String conversationId) {
    if (!requestedKeys.isEmpty()) {
      return requestedKeys;
    }
    return conversationService.getAllowedAttachmentKeys(conversationId);
  }

  private String inlineObjectKeys(String conversationId, List<String> keysToInline) {
    int reinlineCount = 0;
    for (String key : keysToInline) {
      if (conversationService.isAttachmentKeyMaterialized(conversationId, key)) {
        reinlineCount++;
      }
    }
    if (reinlineCount > 0) {
      LOG.infof(
          "Re-inlining %d previously materialized attachment(s) conversationId=%s",
          reinlineCount, conversationId);
    }

    StringBuilder sb = new StringBuilder();
    List<String> actuallyLoaded = new ArrayList<>();
    for (String key : keysToInline) {
      if (!isSafeObjectKey(key)) {
        continue;
      }
      String content = fetchUtf8OrPlaceholder(key);
      String label = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
      sb.append("- `").append(label).append("` (inlined)\n\n");
      sb.append(content).append("\n\n");
      actuallyLoaded.add(key);
    }
    String keyBlocks = sb.toString().stripTrailing();
    if (!keyBlocks.isEmpty()) {
      conversationService.addMaterializedAttachmentKeys(conversationId, actuallyLoaded);
    }
    return keyBlocks;
  }

  private static List<String> extractKeysFromLegacyText(String attachment) {
    if (attachment == null || attachment.isBlank()) {
      return Collections.emptyList();
    }
    var found = new ArrayList<String>();
    var m = KEY_PARAM.matcher(attachment);
    while (m.find()) {
      String k = urlDecode(m.group(1).trim());
      if (isSafeObjectKey(k)) {
        found.add(k);
      }
    }
    return found;
  }

  private static List<String> normalizeObjectKeys(List<String> input) {
    if (input == null || input.isEmpty()) {
      return List.of();
    }
    var seen = new HashSet<String>();
    List<String> out = new ArrayList<>();
    for (String k : input) {
      if (k == null) {
        continue;
      }
      String t = k.trim();
      if (t.isEmpty() || !seen.add(t)) {
        continue;
      }
      if (isSafeObjectKey(t)) {
        out.add(t);
      } else {
        LOG.warnf("Rejecting unsafe attachment key: %s", k);
      }
    }
    return out;
  }

  private String expandStorageObjectUrls(String attachment) {
    Map<String, String> cache = new LinkedHashMap<>();
    StringBuilder result = new StringBuilder();
    for (String line : attachment.split("\n", -1)) {
      Matcher bullet = BULLET_URL.matcher(line);
      if (bullet.matches()) {
        String token = bullet.group(1);
        Matcher keyMatcher = KEY_PARAM.matcher(token);
        if (keyMatcher.find()) {
          String encodedKey = keyMatcher.group(1);
          String key = urlDecode(encodedKey);
          if (isSafeObjectKey(key)) {
            String content = cache.computeIfAbsent(key, this::fetchUtf8OrPlaceholder);
            String label = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
            result.append("- `").append(label).append("` (inlined)\n\n");
            result.append(content).append("\n\n");
            continue;
          }
        }
      }
      result.append(line).append("\n");
    }
    String out = result.toString();
    if (out.endsWith("\n")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }

  private static String urlDecode(String encodedKey) {
    try {
      return URLDecoder.decode(encodedKey, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      LOG.warnf(e, "Bad URL-encoded key parameter: %s", encodedKey);
      return encodedKey;
    }
  }

  private static boolean isSafeObjectKey(String key) {
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

  private String fetchUtf8OrPlaceholder(String key) {
    try {
      return s3Service.readObjectUtf8(key);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Failed to load attachment object key=%s", key);
      return "[Could not load attachment: " + key + "]";
    }
  }
}
