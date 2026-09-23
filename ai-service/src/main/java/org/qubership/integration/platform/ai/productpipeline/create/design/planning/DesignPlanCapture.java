package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

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
      @Description("Approved target kind") TargetKind targetKind,
      @Description("Exact approved target id") String targetId,
      @Description("Short human-readable step description") String summary) {}
}
