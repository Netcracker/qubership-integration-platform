package org.qubership.integration.platform.ai.chain.edit;

import java.util.List;

/** What resolving a service-call operation against the local catalog produced. */
public sealed interface ServiceCallBindingOutcome {

  /** Exactly one catalog operation matched, described completely. */
  record Resolved(ResolvedServiceCallBinding binding) implements ServiceCallBindingOutcome {}

  /**
   * Several operations matched. The candidates are named by what a reader recognizes — display
   * name, method and path — because nobody remembers an operation id.
   */
  record Ambiguous(String question, List<String> candidates) implements ServiceCallBindingOutcome {
    public Ambiguous {
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
  }

  /** Nothing matched. No identity is invented to fill the gap. */
  record NotFound(String message) implements ServiceCallBindingOutcome {}

  /**
   * A match exists only outside the local catalog, and importing it would create catalog artifacts.
   * That needs the reader to say so first.
   */
  record EscalationRequired(String message) implements ServiceCallBindingOutcome {}
}
