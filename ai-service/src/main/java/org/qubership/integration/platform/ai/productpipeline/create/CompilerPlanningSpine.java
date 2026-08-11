package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Uni;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerPlanningRunner.PlanningSpineOutcome;

/**
 * Runs the design-required compiler planning skill spine for product CREATE (pattern selector →
 * chain generator → applicable generators → plan-validator). Does not finalize legacy bundles.
 */
public interface CompilerPlanningSpine {

  /**
   * Executes planning skills against the conversation workspace seeded from {@code request}'s
   * requirement brief. Completes when {@code PRE_BUILD_VALIDATION} is present or the planning
   * segment has no further runnable skills.
   */
  default Uni<PlanningSpineOutcome> execute(CompilerPlanningRequest request) {
    return execute(request, (skillId, status) -> {});
  }

  /**
   * Same as {@link #execute(CompilerPlanningRequest)}, and reports each skill start/finish via
   * {@code skillProgress} ({@code running} / {@code completed} / {@code error}).
   */
  Uni<PlanningSpineOutcome> execute(
      CompilerPlanningRequest request, BiConsumer<String, String> skillProgress);
}
