package org.qubership.integration.platform.ai.compiler.capture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Applies the first matching {@link CaptureFieldMirrorRule} to a capture DTO before validation.
 */
@ApplicationScoped
public class CaptureFieldFillPolicy {

  private final List<CaptureFieldMirrorRule> rules;

  @Inject
  public CaptureFieldFillPolicy(Instance<CaptureFieldMirrorRule> rules) {
    this.rules = rules.stream().toList();
  }

  /** Test helper without CDI. */
  public CaptureFieldFillPolicy(List<CaptureFieldMirrorRule> rules) {
    this.rules = List.copyOf(rules);
  }

  public Object apply(Object capture) {
    for (CaptureFieldMirrorRule rule : rules) {
      if (rule.supports(capture)) {
        return rule.apply(capture);
      }
    }
    return capture;
  }

  public List<CaptureFieldHint> hintsWhenStillBlank(Object capture) {
    for (CaptureFieldMirrorRule rule : rules) {
      if (rule.supports(capture)) {
        return rule.hintsWhenStillBlank(capture);
      }
    }
    return List.of();
  }
}
