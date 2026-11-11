package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

/** Discriminator for a typed execution-edge route. */
public enum SemanticRouteKind {
  SEQUENCE,
  CONDITION_BRANCH,
  SPLIT_BRANCH,
  RECONVERGE,
  LOOP_BODY,
  LOOP_EXIT,
  RETRY_ATTEMPT,
  RETRY_EXHAUSTED,
  TRY_PATH,
  CATCH_PATH,
  FINALLY_PATH
}
