package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.List;
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
    Optional<String> repairEvidence =
        request.repairEvidenceText().isBlank()
            ? Optional.empty()
            : Optional.of(request.repairEvidenceText());
    String first =
        runner.runOnce(
            conversationId, SKILL_ID, input, Optional.empty(), repairEvidence, pinnedSkillHash);
    try {
      parseAndValidate(first, input);
      return new DesignPlanReport("1", first.trim());
    } catch (PlannerReportFormatException firstFailure) {
      String second =
          runner.runOnce(
              conversationId,
              SKILL_ID,
              input,
              Optional.of(firstFailure.getMessage()),
              repairEvidence,
              pinnedSkillHash);
      try {
        parseAndValidate(second, input);
        return new DesignPlanReport("1", second.trim());
      } catch (PlannerReportFormatException secondFailure) {
        throw new PlannerContractException(
            "cip-design-planner report failed format contract after one retry: "
                + secondFailure.getMessage(),
            secondFailure);
      }
    }
  }

  private ParsedPlannerReport parseAndValidate(String markdown, String input) {
    ParsedPlannerReport parsed = parser.parse(markdown);
    if (!input.contains("Binding resolution policy: CATALOG_ONLY")) {
      return parsed;
    }
    List<Integer> forbiddenSteps =
        parsed.steps().stream()
            .filter(step -> step.ownerKind() == ParsedPlannerReport.OwnerKind.APIHUB_TOOL)
            .map(ParsedPlannerReport.Step::reportOrdinal)
            .toList();
    if (!forbiddenSteps.isEmpty()) {
      throw new PlannerReportFormatException(
          "CATALOG_ONLY forbids APIHub planner steps; remove steps "
              + forbiddenSteps
              + " and plan the existing catalog binding with cip-service-call-generator");
    }
    return parsed;
  }
}
