package org.qubership.integration.platform.ai.productpipeline.facade;

import java.util.List;

/**
 * What a pipeline run is waiting for, named without reference to the pipeline that produced it.
 *
 * <p>Readers outside a pipeline package — the chat above all — see a wait through this view, so a
 * second pipeline profile needs no second reader. A pipeline keeps its own sealed hierarchy and
 * extends this interface from it.
 */
public interface PendingAction {

  /** Stable wire name of the action: {@code approve} or {@code clarify}. */
  String action();

  /** Approval of one exact artifact at one exact run revision. */
  interface Approve extends PendingAction {

    String artifactType();

    String artifactHash();

    long revision();

    /** Question authored for the reader, or an empty string when the wait carried none. */
    String prompt();
  }

  /** Request for missing evidence, answered as free text or by the actions its gate declares. */
  interface Clarify extends PendingAction {

    String reason();

    List<String> missingEvidence();

    /**
     * Gate this clarification belongs to, or an empty string when the run names none.
     *
     * @see PipelineGates
     */
    String gateId();
  }
}
