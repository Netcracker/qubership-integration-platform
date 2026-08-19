package org.qubership.integration.platform.ai.chain.edit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * Structured edit decision returned by the intent agent.
 *
 * <p>Java validates required fields against the imported graph and applies them. It does not read
 * the user's wording to guess the action, the element type, the targets, or the disposition.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainEditCapture(
    @Description(
            "Required ChainEditAction name. Use NO_CHANGE when nothing should change. Never an empty string.")
        ChainEditAction action,
    @Description(
            "Existing element ids this edit acts on or attaches next to. For KEEP, one or two ids:"
                + " the element the insertion follows, optionally followed by the element it"
                + " precedes. For REMOVE, only the element being replaced. Empty for a new root"
                + " trigger unless the request names the start that trigger should fan into.")
        List<String> targetNodeIds,
    @Description("One sentence saying what should be different.") String requestedChange,
    @Description("What to search the catalog for, when the request names something outside the chain.")
        String lookup,
    @Description("Catalog element type to add. Required for ADD_ELEMENTS.") String elementType,
    @Description("Cron or equivalent schedule when ADD_ELEMENTS places a scheduler. Empty otherwise.")
        String cronExpression,
    @Description(
            "Property keys this edit changes. Required for CONFIGURE. Use the catalog's own property"
                + " key names, not a paraphrase. Empty for every other action.")
        List<String> propertyKeys,
    @Description("Candidates or the question to ask when the capture is incomplete.")
        List<String> ambiguities,
    @Description(
            "What happens to the existing element at the insertion address: KEEP it, NEST it in the"
                + " new structure, or REMOVE it and put the new subgraph in its place. UNSET when"
                + " this is not an addition, or for a new root trigger. Java infers KEEP when"
                + " target ids are present and this field is empty.")
        ChainEditDisposition disposition) {

  /** Capture without an explicit disposition; Java infers KEEP from a named address. */
  public ChainEditCapture(
      ChainEditAction action,
      List<String> targetNodeIds,
      String requestedChange,
      String lookup,
      String elementType,
      String cronExpression,
      List<String> propertyKeys,
      List<String> ambiguities) {
    this(
        action,
        targetNodeIds,
        requestedChange,
        lookup,
        elementType,
        cronExpression,
        propertyKeys,
        ambiguities,
        ChainEditDisposition.UNSET);
  }

  public ChainEditCapture {
    action = action == null ? ChainEditAction.NO_CHANGE : action;
    targetNodeIds = targetNodeIds == null ? List.of() : List.copyOf(targetNodeIds);
    requestedChange = requestedChange == null ? "" : requestedChange;
    lookup = blankToNull(lookup);
    elementType = blankToNull(elementType);
    cronExpression = blankToNull(cronExpression);
    propertyKeys = propertyKeys == null ? List.of() : List.copyOf(propertyKeys);
    ambiguities = ambiguities == null ? List.of() : List.copyOf(ambiguities);
    disposition = disposition == null ? ChainEditDisposition.UNSET : disposition;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
