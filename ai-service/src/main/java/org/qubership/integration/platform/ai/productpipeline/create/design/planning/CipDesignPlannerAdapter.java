package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;

/**
 * Invokes pinned {@code cip-design-planner} once, with a single format retry on {@link
 * PlannerReportFormatException}.
 */
public final class CipDesignPlannerAdapter {

  public static final String SKILL_ID = "cip-design-planner";

  private final DesignProcessSkillRunner runner;
  private final CipDesignPlannerReportParser parser;

  public CipDesignPlannerAdapter(
      DesignProcessSkillRunner runner, CipDesignPlannerReportParser parser) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.parser = Objects.requireNonNull(parser, "parser");
  }

  public DesignPlanReport plan(PlannerRequest request) {
    Objects.requireNonNull(request, "request");
    String conversationId = request.conversationId();
    String input = request.input();
    String pinnedSkillHash = request.pinnedSkillHash();
    String first =
        runner.runOnce(conversationId, SKILL_ID, input, Optional.empty(), pinnedSkillHash);
    try {
      parser.parse(first);
      return new DesignPlanReport("1", first.trim());
    } catch (PlannerReportFormatException firstFailure) {
      String second =
          runner.runOnce(
              conversationId,
              SKILL_ID,
              input,
              Optional.of(firstFailure.getMessage()),
              pinnedSkillHash);
      try {
        parser.parse(second);
        return new DesignPlanReport("1", second.trim());
      } catch (PlannerReportFormatException secondFailure) {
        throw new PlannerContractException(
            "cip-design-planner report failed format contract after one retry: "
                + secondFailure.getMessage(),
            secondFailure);
      }
    }
  }
}
