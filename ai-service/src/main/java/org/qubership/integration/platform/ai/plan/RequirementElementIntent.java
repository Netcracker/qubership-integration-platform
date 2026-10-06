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
    @NullableCaptureValue String requestedName,
    List<PlanProperty> requestedProperties,
    List<String> exactValues) {

  public enum Placement { ANCHOR, LOCAL_NODE, BEFORE_HOOK, AFTER_HOOK, CHAIN }
}
