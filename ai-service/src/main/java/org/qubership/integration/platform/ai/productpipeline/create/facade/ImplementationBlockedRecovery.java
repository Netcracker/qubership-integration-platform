package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.Objects;

/**
 * Recovery descriptor when automatic implementation cannot start after plan approval.
 *
 * <p>Adapters map this to {@code INPUT_REQUIRED}. There is no public {@code implement} action.
 */
public sealed interface ImplementationBlockedRecovery {

  String reason();

  /** Continue with the same normalized approve command using expected plan evidence. */
  record ApprovePlanEvidence(
      String reason, String artifactType, String artifactHash, long revision)
      implements ImplementationBlockedRecovery {

    public ApprovePlanEvidence {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(artifactType, "artifactType");
      Objects.requireNonNull(artifactHash, "artifactHash");
    }
  }

  /**
   * Request clarification after the facade restores an input-capable transition.
   *
   * <p>Never advertise this when the runtime is still {@code WAITING_FOR_IMPLEMENT} with no legal
   * recovery path.
   */
  record ClarifyMissingEvidence(String reason, java.util.List<String> missingEvidence)
      implements ImplementationBlockedRecovery {

    public ClarifyMissingEvidence {
      Objects.requireNonNull(reason, "reason");
      missingEvidence =
          missingEvidence == null ? java.util.List.of() : java.util.List.copyOf(missingEvidence);
    }
  }
}
