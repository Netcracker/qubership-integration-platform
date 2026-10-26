package org.qubership.integration.platform.ai.plan;

/**
 * Groups element intents that describe the same element.
 *
 * <p>A chain identity does not use the raw element reference. An anchor identity uses the owning
 * interaction. Local nodes and inline hooks use {@code elementRef}. Two identities of different
 * kinds stay distinct even when those strings match.
 */
public record ElementIdentity(Kind kind, String key) {

  public enum Kind {
    CHAIN,
    ANCHOR,
    LOCAL_NODE,
    BEFORE_HOOK,
    AFTER_HOOK
  }

  public static ElementIdentity of(RequirementElementIntent intent) {
    return switch (intent.placement()) {
      case CHAIN -> new ElementIdentity(Kind.CHAIN, null);
      case ANCHOR -> new ElementIdentity(Kind.ANCHOR, intent.ownerInteractionId());
      case LOCAL_NODE -> new ElementIdentity(Kind.LOCAL_NODE, intent.elementRef());
      case BEFORE_HOOK -> new ElementIdentity(Kind.BEFORE_HOOK, intent.elementRef());
      case AFTER_HOOK -> new ElementIdentity(Kind.AFTER_HOOK, intent.elementRef());
    };
  }
}
