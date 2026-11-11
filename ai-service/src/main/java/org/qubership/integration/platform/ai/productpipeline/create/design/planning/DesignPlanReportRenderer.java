package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;

/** Renders the approval report without recovering semantics from model-authored prose. */
public final class DesignPlanReportRenderer {

  public static final String APPROVAL_SENTENCE =
      "If you agree, reply **Agree** or **Execute plan** to proceed.";

  public DesignPlanReport render(DesignPlanContract contract) {
    String contractHash = DesignPlanProjector.contractHash(contract);
    StringBuilder markdown = new StringBuilder();
    int ordinal = 1;
    for (DesignPlanContract.Step step : contract.steps()) {
      markdown.append(ordinal++).append(". ").append(sanitize(step.summary()));
      markdown.append(" [stepId=").append(step.stepId());
      markdown
          .append(" owner=")
          .append(step.owner().kind())
          .append(':')
          .append(step.owner().id());
      if (!step.claims().isEmpty()) {
        markdown
            .append(" claims=")
            .append(
                step.claims().stream()
                    .map(
                        claim ->
                            claim.targetKind() + ":" + claim.role() + ":" + claim.targetId())
                    .collect(Collectors.joining(",")));
      }
      if (!step.dependsOnStepIds().isEmpty()) {
        markdown.append(" dependsOn=").append(String.join(",", step.dependsOnStepIds()));
      }
      markdown.append("]\n");
    }
    markdown.append('\n').append(APPROVAL_SENTENCE);
    return new DesignPlanReport("2", markdown.toString(), contract.contractId(), contractHash);
  }

  private static String sanitize(String summary) {
    StringBuilder escaped = new StringBuilder();
    for (char character : summary.trim().toCharArray()) {
      switch (character) {
        case '\n', '\r' -> escaped.append(' ');
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '[' -> escaped.append("&#91;");
        case ']' -> escaped.append("&#93;");
        case '*' -> escaped.append("&#42;");
        case '_' -> escaped.append("&#95;");
        case '`' -> escaped.append("&#96;");
        case '#' -> escaped.append("&#35;");
        default -> escaped.append(character);
      }
    }
    return escaped.toString();
  }
}
