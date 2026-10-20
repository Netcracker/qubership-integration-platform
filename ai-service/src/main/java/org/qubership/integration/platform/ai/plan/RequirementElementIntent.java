package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Author-supplied element placement and values, separate from business interactions. */
public record RequirementElementIntent(
    String elementRef,
    String elementType,
    Placement placement,
    @NullableCaptureValue String ownerInteractionId,
    @JsonAlias("beforeElementRef")
    @NullableCaptureValue(description = "The next element: this element executes before the successor. For elementRef A and"
        + " successorElementRef B, the required order is A -> B. Null when unspecified or when the elements belong to alternative branches.") String successorElementRef,
    @JsonAlias("afterElementRef")
    @NullableCaptureValue(description = "The preceding element: this element executes after the predecessor. For elementRef A and"
        + " predecessorElementRef B, the required order is B -> A. Null when unspecified or when the elements belong to alternative branches.") String predecessorElementRef,
    @NullableCaptureValue(description = "The containing elementRef when this element is explicitly nested inside a container."
        + " This is structural membership, not execution order. Null when unspecified.") String parentElementRef,
    @NullableCaptureValue(description = "Exact display name explicitly supplied for this element, including an entry trigger or call"
        + " with ANCHOR placement. The interaction participant names the other party, not this element."
        + " Keep the name here even when no properties are requested. Null when the author supplied no element name.") String requestedName,
    List<PlanProperty> requestedProperties,
    List<String> exactValues) {

  public RequirementElementIntent(
      String elementRef,
      String elementType,
      Placement placement,
      String ownerInteractionId,
      String successorElementRef,
      String predecessorElementRef,
      String requestedName,
      List<PlanProperty> requestedProperties,
      List<String> exactValues) {
    this(elementRef, elementType, placement, ownerInteractionId, successorElementRef,
        predecessorElementRef, null, requestedName, requestedProperties, exactValues);
  }

  public enum Placement { ANCHOR, LOCAL_NODE, BEFORE_HOOK, AFTER_HOOK, CHAIN }
}
