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
  void isBindingSatisfiedServiceCallFalseForApiHubOperationIdInExpectedProperties() {
    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationOperationId",
            "serviceCatalogManagement-v4-serviceCatalogManagement-v4-serviceSpecification-_id_-get"));

    assertTrue(
        PlanServiceBindingRules.looksLikeApiHubOperationId(
            "serviceCatalogManagement-v4-serviceCatalogManagement-v4-serviceSpecification-_id_-get"));
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

  @Test
  void isBindingSatisfiedServiceCallFalseForCompleteApiHubImportMetadataUntilCatalogIds() {
    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setImportRequired(true);
    node.setApiHubPackageId("S.ActProv.SvcCat");
    node.setApiHubVersion("2026.1@1");
    node.setApiHubOperationId(
        "serviceCatalogManagement-v4-serviceCatalogManagement-v4-serviceSpecification-_id_-get");
    node.setApiHubSpecificationName("Service Catalog");
    node.setCatalogSystemName("Service Catalog Management");
    node.setCatalogSystemType("EXTERNAL");

    assertFalse(PlanServiceBindingRules.isBindingSatisfied(node));
    assertTrue(PlanServiceBindingRules.hasCompleteApiHubImportMetadata(node));

    node.setExpectedProperties(
        Map.of(
            "integrationSystemId",
            "sys-1",
            "integrationOperationId",
            "op-catalog-1"));
    assertTrue(PlanServiceBindingRules.isBindingSatisfied(node));
  }

  @Test
  void isBindingSatisfiedServiceCallFalseForImportRequiredWithoutMetadata() {
    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setImportRequired(true);

    assertFalse(PlanServiceBindingRules.isBindingSatisfied(node));
    assertFalse(PlanServiceBindingRules.hasCompleteApiHubImportMetadata(node));
  }

  @Test
  void isValidCatalogSystemTypeAcceptsInternalAndExternal() {
    assertTrue(PlanServiceBindingRules.isValidCatalogSystemType("INTERNAL"));
    assertTrue(PlanServiceBindingRules.isValidCatalogSystemType("external"));
    assertFalse(PlanServiceBindingRules.isValidCatalogSystemType("CUSTOM"));
  }
}
