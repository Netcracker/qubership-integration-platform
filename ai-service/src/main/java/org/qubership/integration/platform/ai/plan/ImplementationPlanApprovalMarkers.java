package org.qubership.integration.platform.ai.plan;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;

/** Shared user-facing Agree markers for implementation-plan approval gates. */
public final class ImplementationPlanApprovalMarkers {

  private ImplementationPlanApprovalMarkers() {}

  /**
   * User-facing CTA after a plan candidate is stored. Content hash stays internal — do not put
   * revision ids or SHA digests in chat.
   */
  public static String forRevision(Revision revision) {
    return withoutRevision();
  }

  public static String withoutRevision() {
    return "Reply Agree to approve this plan and create the chain, or describe what to change.";
  }
}
