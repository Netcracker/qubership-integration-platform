package org.qubership.integration.platform.ai.compiler.capture;

import java.util.List;

/**
 * CDI-discoverable rule that mirrors a nested capture field onto a blank top-level twin when a
 * shared predicate passes.
 */
public interface CaptureFieldMirrorRule {

  boolean supports(Object capture);

  Object apply(Object capture);

  default List<CaptureFieldHint> hintsWhenStillBlank(Object capture) {
    return List.of();
  }
}
