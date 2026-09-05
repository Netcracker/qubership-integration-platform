package org.qubership.integration.platform.ai.chain.edit.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignProcessSkillRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.PlannerContractException;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.PlannerReportFormatException;

/**
 * Invokes pinned {@code cip-chain-edit-planner} once, with a single format retry, then validates
 * the JSON plan against the imported graph.
 */
@ApplicationScoped
public class CipChainEditPlannerAdapter {

  public static final String SKILL_ID = "cip-chain-edit-planner";

  private final DesignProcessSkillRunner runner;
  private final CipChainEditPlannerRequestBuilder requestBuilder;
  private final ChainEditStructuralPlanParser parser;
  private final ChainEditStructuralPlanValidator validator;

  @Inject
  public CipChainEditPlannerAdapter(
      DesignProcessSkillRunner runner,
      CipChainEditPlannerRequestBuilder requestBuilder,
      ObjectMapper objectMapper) {
    this(
        runner,
        requestBuilder,
        new ChainEditStructuralPlanParser(objectMapper),
        new ChainEditStructuralPlanValidator());
  }

  public CipChainEditPlannerAdapter(
      DesignProcessSkillRunner runner,
      CipChainEditPlannerRequestBuilder requestBuilder,
      ChainEditStructuralPlanParser parser,
      ChainEditStructuralPlanValidator validator) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.requestBuilder = Objects.requireNonNull(requestBuilder, "requestBuilder");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  public ChainEditStructuralPlan plan(ChainEditPlannerRequest request) {
    Objects.requireNonNull(request, "request");
    String input = requestBuilder.buildPrompt(request);
    String first =
        runner.runOnce(
            request.conversationId(),
            SKILL_ID,
            input,
            Optional.empty(),
            Optional.empty(),
            request.pinnedSkillHash());
    try {
      return parseAndValidate(first, request);
    } catch (RuntimeException firstFailure) {
      if (!(firstFailure instanceof PlannerReportFormatException)
          && !(firstFailure instanceof IllegalArgumentException)) {
        throw firstFailure;
      }
      String second =
          runner.runOnce(
              request.conversationId(),
              SKILL_ID,
              input,
              Optional.of(firstFailure.getMessage()),
              Optional.empty(),
              request.pinnedSkillHash());
      try {
        return parseAndValidate(second, request);
      } catch (RuntimeException secondFailure) {
        throw new PlannerContractException(
            "cip-chain-edit-planner plan failed contract after one retry: "
                + secondFailure.getMessage(),
            secondFailure);
      }
    }
  }

  private ChainEditStructuralPlan parseAndValidate(String raw, ChainEditPlannerRequest request) {
    ChainEditStructuralPlan plan = parser.parse(raw);
    validator.validate(plan, request.graph(), request.operationSchemas());
    return plan;
  }
}
