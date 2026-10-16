package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;

/** Model-authored descriptions for approved planning targets. */
public record DesignPlanCapture(
    @Description("Optional descriptions for exact approved targets; use an empty list when none are needed")
        List<Note> notes) {

  public DesignPlanCapture {
    notes = notes == null ? List.of() : List.copyOf(notes);
  }

  public record Note(
      @Description("Approved target kind")
        @JsonProperty(required = true) TargetKind targetKind,
      @Description("Exact approved target id")
        @JsonProperty(required = true) String targetId,
      @Description("Short human-readable step description")
        @JsonProperty(required = true) String summary) {}
}
