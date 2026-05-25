package org.qubership.integration.platform.ai.chat.chainplan;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanServiceBindingRulesTest {

  @Test
  void looksLikePlaceholderOperationIdDetectsCommonPatterns() {
    assertTrue(PlanServiceBindingRules.looksLikePlaceholderOperationId("operation-id-placeholder"));
    assertTrue(PlanServiceBindingRules.looksLikePlaceholderOperationId("TBD"));
    assertTrue(
        PlanServiceBindingRules.looksLikePlaceholderOperationId(
            "replace with actual operation id"));
    assertFalse(
        PlanServiceBindingRules.looksLikePlaceholderOperationId(
            "364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7-placeOrder"));
  }

  @Test
  void isBindingSatisfiedServiceCallFalseForPlaceholderOperationId() {
    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationSystemId",
            "364ea2f4-8918-4e47-9fc3-17652f1706d3",
            "integrationOperationId",
            "operation-id-placeholder"));

    assertFalse(PlanServiceBindingRules.isBindingSatisfied(node));
  }

  @Test
  void isBindingSatisfiedServiceCallTrueForCatalogOperationId() {
    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationSystemId",
            "364ea2f4-8918-4e47-9fc3-17652f1706d3",
            "integrationOperationId",
            "364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7-placeOrder"));

    assertTrue(PlanServiceBindingRules.isBindingSatisfied(node));
  }
}
