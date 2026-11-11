package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

/** Planner Markdown violated the upstream report grammar. */
public final class PlannerReportFormatException extends RuntimeException {

  public PlannerReportFormatException(String message) {
    super(message);
  }

  public PlannerReportFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
