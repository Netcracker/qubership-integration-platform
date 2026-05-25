package org.qubership.integration.platform.ai.integration.catalog.binding;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OperationBindingApplicatorsTest {

  @Test
  void httpTriggerImplementedMapsMethodToHttpMethodRestrict() {
    HttpTriggerBindingApplicator applicator = new HttpTriggerBindingApplicator();
    ElementPlan node = new ElementPlan();
    node.setType("http-trigger");
    node.setExpectedProperties(
        Map.of("integrationSpecificationId", "model-1", "integrationOperationId", "op-1"));

    Map<String, Object> props = new LinkedHashMap<>(node.getExpectedProperties());
    OperationBindingResolved resolved =
        new OperationBindingResolved(
            new CatalogRestClient.OperationDto("op-1", "getPet", "GET", "/pet/{id}", null),
            "sys-1",
            "group-1",
            "http",
            Optional.of(new CatalogRestClient.SystemDto("sys-1", "Pet", "IMPLEMENTED", "http")));

    applicator.applyResolved(node, resolved, "model-1", props);

    assertEquals("GET", props.get("httpMethodRestrict"));
    assertEquals("/pet/{id}", props.get("integrationOperationPath"));
    assertFalse(props.containsKey("integrationOperationMethod"));
    assertFalse(props.containsKey("integrationOperationProtocolType"));
    assertFalse(props.containsKey("contextPath"));
  }

  @Test
  void mergeNormalizerStripsProtocolFromHttpTriggerMerged() {
    OperationBindingMergeNormalizer normalizer =
        new OperationBindingMergeNormalizer(java.util.List.of(new HttpTriggerBindingApplicator()));

    Map<String, Object> merged = new LinkedHashMap<>();
    merged.put("integrationOperationId", "op-1");
    merged.put("integrationOperationProtocolType", "http");
    merged.put("integrationOperationMethod", "GET");
    merged.put("integrationOperationPath", "/pet");

    normalizer.normalizeMergedProperties("http-trigger", merged);

    assertFalse(merged.containsKey("integrationOperationProtocolType"));
    assertFalse(merged.containsKey("integrationOperationMethod"));
    assertEquals("GET", merged.get("httpMethodRestrict"));
  }

  @Test
  void httpTriggerCustomEndpointShouldNotEnrich() {
    HttpTriggerBindingApplicator applicator = new HttpTriggerBindingApplicator();
    ElementPlan node = new ElementPlan();
    node.setType("http-trigger");
    node.setExpectedProperties(Map.of("contextPath", "/api/foo", "httpMethodRestrict", "POST"));

    assertFalse(applicator.shouldEnrich(node));
  }

  @Test
  void asyncApiTriggerFillsCanonicalFields() {
    AsyncApiTriggerBindingApplicator applicator = new AsyncApiTriggerBindingApplicator();
    ElementPlan node = new ElementPlan();
    node.setType("async-api-trigger");
    node.setExpectedProperties(
        Map.of("integrationSpecificationId", "model-k", "integrationOperationId", "op-k"));

    Map<String, Object> props = new LinkedHashMap<>(node.getExpectedProperties());
    OperationBindingResolved resolved =
        new OperationBindingResolved(
            new CatalogRestClient.OperationDto("op-k", "orders", "subscribe", "/orders", null),
            "sys-k",
            "group-k",
            "kafka",
            Optional.of(
                new CatalogRestClient.SystemDto("sys-k", "Orders", "IMPLEMENTED", "kafka")));

    applicator.applyResolved(node, resolved, "model-k", props);

    assertEquals("subscribe", props.get("integrationOperationMethod"));
    assertEquals("kafka", props.get("integrationOperationProtocolType"));
  }

  @Test
  void mergeNormalizerStripsGqlFromServiceCallMerged() {
    OperationBindingMergeNormalizer normalizer =
        new OperationBindingMergeNormalizer(
            java.util.List.of(
                new ServiceCallBindingApplicator(),
                new HttpTriggerBindingApplicator(),
                new AsyncApiTriggerBindingApplicator()));

    Map<String, Object> merged = new LinkedHashMap<>();
    merged.put("integrationOperationId", "op-1");
    merged.put("integrationOperationProtocolType", "http");
    merged.put("integrationOperationMethod", "GET");
    merged.put("integrationGqlQueryHeader", "CamelGraphQLQuery");

    normalizer.normalizeMergedProperties("service-call", merged);

    assertFalse(merged.containsKey("integrationGqlQueryHeader"));
  }
}
