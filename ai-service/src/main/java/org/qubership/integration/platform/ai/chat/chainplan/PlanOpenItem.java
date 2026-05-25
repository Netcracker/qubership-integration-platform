package org.qubership.integration.platform.ai.chat.chainplan;

import java.util.List;

/**
 * One unresolved item attached to the active chain plan snapshot (debt /
 * verification).
 *
 * @param itemId          stable id for merge and de-duplication within a
 *                        conversation revision
 * @param dismissedByUser when true, item is excluded from blocking HITL prompts
 *                        but still listed
 *                        under verify
 */
public record PlanOpenItem(
    String itemId,
    PlanOpenItemKind kind,
    String clientId,
    String elementId,
    String elementType,
    String message,
    List<String> removedKeys,
    boolean dismissedByUser) {

  public PlanOpenItem {
    removedKeys = removedKeys == null ? List.of() : List.copyOf(removedKeys);
  }

  public static String idUnknownProperty(String clientId, String propertyKey) {
    return "unknown-prop:" + nullSafe(clientId) + ":" + nullSafe(propertyKey);
  }

  public static String idPatchFailure(String clientId) {
    return "patch-fail:" + nullSafe(clientId);
  }

  public static String idSkippedPartial(String clientId, List<String> sortedKeys) {
    return "skipped-partial:" + nullSafe(clientId) + ":" + String.join(",", sortedKeys);
  }

  public static String idServiceBindingUnresolved(String clientId) {
    return "service-binding:" + nullSafe(clientId);
  }

  public static String idMissingRuntimeConnections() {
    return "missing-runtime-connections";
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }
}
