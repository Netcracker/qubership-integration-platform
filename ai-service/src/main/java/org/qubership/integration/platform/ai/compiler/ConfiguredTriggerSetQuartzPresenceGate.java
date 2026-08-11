package org.qubership.integration.platform.ai.compiler;

import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;

/**
 * Rejects HTTP-only trigger captures when the element skeleton already requires quartz-scheduler.
 *
 * <p>Does not invent cron defaults; only checks that a quartz trigger entry is present.
 */
final class ConfiguredTriggerSetQuartzPresenceGate {

  static final String QUARTZ_ELEMENT_TYPE = "quartz-scheduler";

  static final String MISSING_QUARTZ_TRIGGER_MESSAGE =
      "ConfiguredTriggerSet is missing required quartz-scheduler. The element skeleton includes a"
          + " quartz-scheduler entry role; include a quartz-scheduler trigger in this capture."
          + " Leave cron and deleteJob to cip-quartz-scheduler-generator after structure"
          + " generation.";

  private ConfiguredTriggerSetQuartzPresenceGate() {}

  /** Returns a validation message when the capture omits a required quartz trigger; otherwise null. */
  static String validate(ElementSkeleton skeleton, ConfiguredTriggerSet capture) {
    if (!skeletonRequiresQuartz(skeleton)) {
      return null;
    }
    if (captureHasQuartz(capture)) {
      return null;
    }
    return MISSING_QUARTZ_TRIGGER_MESSAGE;
  }

  static boolean skeletonRequiresQuartz(ElementSkeleton skeleton) {
    if (skeleton == null || skeleton.elementRoles() == null) {
      return false;
    }
    for (ElementRole role : skeleton.elementRoles()) {
      if (role != null && isQuartzElementType(role.elementType())) {
        return true;
      }
    }
    return false;
  }

  static boolean captureHasQuartz(ConfiguredTriggerSet capture) {
    if (capture == null || capture.triggers() == null) {
      return false;
    }
    for (ConfiguredTrigger trigger : capture.triggers()) {
      if (trigger != null && isQuartzElementType(trigger.elementType())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isQuartzElementType(String elementType) {
    return QUARTZ_ELEMENT_TYPE.equalsIgnoreCase(elementType == null ? "" : elementType.trim());
  }
}
