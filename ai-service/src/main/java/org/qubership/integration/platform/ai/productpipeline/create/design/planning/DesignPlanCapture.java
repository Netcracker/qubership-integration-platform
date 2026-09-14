package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.OwnerKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;

/** LLM-facing input for {@link DesignPlanCaptureTool#captureDesignPlan}. */
public record DesignPlanCapture(
    @Description("Ordered implementation steps") List<Step> steps) {

  public DesignPlanCapture {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  public record Step(
      @Description("Stable unique step id used by dependsOnStepIds") String stepId,
      @Description("Human-readable summary; it has no machine semantics") String summary,
      @Description("The exact skill or APIHub tool operation that owns this step") Owner owner,
      @Description("Semantic targets produced or referenced by this step") List<Claim> claims,
      @Description("Exact step ids that must complete first") List<String> dependsOnStepIds) {
    public Step {
      claims = claims == null ? List.of() : List.copyOf(claims);
      dependsOnStepIds = dependsOnStepIds == null ? List.of() : List.copyOf(dependsOnStepIds);
    }
  }

  public record Owner(
      @Description("SKILL or APIHUB_TOOL") OwnerKind kind,
      @Description("Exact owning skill id or APIHub tool operation") String id) {}

  public record Claim(
      @Description("Canonical target kind") TargetKind targetKind,
      @Description("Canonical id copied from the design input") String targetId,
      @Description("PRODUCER creates the target; REFERENCE only uses it") ClaimRole role) {}
}
