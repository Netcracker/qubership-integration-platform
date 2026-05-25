package org.qubership.integration.platform.ai.integration.catalog.binding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Post-merge cleanup for service-bound element properties before JSON Schema validation. */
@ApplicationScoped
public class OperationBindingMergeNormalizer {

  private final List<OperationBindingApplicator> applicators;

  @Inject
  public OperationBindingMergeNormalizer(Instance<OperationBindingApplicator> applicators) {
    this.applicators = new ArrayList<>();
    applicators.forEach(this.applicators::add);
  }

  public OperationBindingMergeNormalizer(List<OperationBindingApplicator> applicators) {
    this.applicators = List.copyOf(applicators);
  }

  public void normalizeMergedProperties(String elementType, Map<String, Object> merged) {
    if (elementType == null || merged == null || merged.isEmpty()) {
      return;
    }
    String trimmed = elementType.trim();
    for (OperationBindingApplicator applicator : this.applicators) {
      if (applicator.supports(trimmed)) {
        applicator.normalizeMergedProperties(trimmed, merged);
        return;
      }
    }
  }
}
