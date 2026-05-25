package org.qubership.integration.platform.ai.integration.catalog.binding;

import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.Map;

/**
 * Element-type-specific overlay after {@link OperationBindingCore} (mirrors UI per-type save
 * rules).
 */
public interface OperationBindingApplicator {

  boolean supports(String elementType);

  /** Whether this node should receive catalog operation enrich at materialize. */
  boolean shouldEnrich(ElementPlan node);

  void applyResolved(
      ElementPlan node,
      OperationBindingResolved resolved,
      String specIdFromPlan,
      Map<String, Object> props);

  /** Post-merge cleanup on live catalog properties before schema validation. */
  void normalizeMergedProperties(String elementType, Map<String, Object> merged);
}
