package org.qubership.integration.platform.ai.llm.ratelimit;

import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;

/** A repeated read is diagnostic evidence, not a rejected mutation or a grant of repair credit. */
public final class ReadNoProgressException extends IllegalStateException {
  public ReadNoProgressException(String evidence) {
    super("Repeated tool reads returned identical data without changing the accepted state. "
        + "Use the retained result and choose a different action. Preserve requirements and accepted progress. "
        + "Read evidence: " + evidence);
  }

  public RecoveryCause recoveryCause() {
    return new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE,
        List.of(new PlanValidationFinding("TOOL_READ_NO_PROGRESS", getMessage(), true)), "", true);
  }

  public static Optional<ReadNoProgressException> find(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof ReadNoProgressException repeated) return Optional.of(repeated);
      if (current == current.getCause()) break;
    }
    return Optional.empty();
  }
}
