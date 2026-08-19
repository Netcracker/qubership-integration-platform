package org.qubership.integration.platform.ai.chain.edit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Structured edit decision returned by the intent agent.
 *
 * <p>Java validates required fields against the imported graph and applies them. It does not read
 * the user's wording to guess the action, the element type, the targets, or the placement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainEditCapture(
    @Description(
            "Required ChainEditAction name. Use NO_CHANGE when nothing should change. Never an empty string.")
        ChainEditAction action,
    @Description("Existing element ids this edit acts on or attaches next to.")
        List<String> targetNodeIds,
    @Description("One sentence saying what should be different.") String requestedChange,
    @Description("What to search the catalog for, when the request names something outside the chain.")
        String lookup,
    @Description("Catalog element type to add. Required for ADD_ELEMENTS.") String elementType,
    @Description("Cron or equivalent schedule when ADD_ELEMENTS places a scheduler. Empty otherwise.")
        String cronExpression,
    @Description(
            "Where ADD_ELEMENTS lands: ROOT_TRIGGER, AFTER_TARGET, or GENERATOR. UNSET when this is not an addition.")
        ChainEditPlacement placement,
    @Description("Candidates or the question to ask when the capture is incomplete.")
        List<String> ambiguities) {

  public ChainEditCapture {
    action = action == null ? ChainEditAction.NO_CHANGE : action;
    targetNodeIds = targetNodeIds == null ? List.of() : List.copyOf(targetNodeIds);
    requestedChange = requestedChange == null ? "" : requestedChange;
    lookup = blankToNull(lookup);
    elementType = blankToNull(elementType);
    cronExpression = blankToNull(cronExpression);
    placement = placement == null ? ChainEditPlacement.UNSET : placement;
    ambiguities = ambiguities == null ? List.of() : List.copyOf(ambiguities);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
