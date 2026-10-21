package org.qubership.integration.platform.ai.catalog.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class CatalogOperationProjectorTest {

  @Test
  void httpOperationProjectsIdentityFields() {
    ResolvedServiceCallBinding binding =
        CatalogOperationProjector.project(
            "node-1",
            "call-1",
            new CatalogRestClient.SystemDto("sys-1", "Petstore", "EXTERNAL", "http"),
            "grp-1",
            "spec-1",
            new CatalogRestClient.OperationDto("op-1", "getInventory", "GET", "/store/inventory", null),
            ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
            "catalog",
            "catalog-read:sys-1/spec-1/op-1",
            "");
    assertEquals("http", binding.protocolType());
    assertEquals("GET", binding.method());
    assertEquals("/store/inventory", binding.path());
    assertEquals("call-1", binding.serviceCallId());
    assertEquals("node-1", binding.targetNodeId());
  }

  @Test
  void soapCatalogProtocolStampsHttpRuntimeBranch() {
    ResolvedServiceCallBinding binding =
        CatalogOperationProjector.project(
            "node-1",
            "call-1",
            new CatalogRestClient.SystemDto("sys-1", "Soap", "INTERNAL", "soap"),
            "grp-1",
            "spec-1",
            new CatalogRestClient.OperationDto("op-1", "List", "POST", "", null),
            ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
            "",
            "ev",
            "");
    assertEquals("http", binding.protocolType());
    assertEquals("POST", binding.method());
    assertEquals("", binding.path());
  }

  @Test
  void httpWithoutPathFailsClosed() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CatalogOperationProjector.project(
                    "node-1",
                    "call-1",
                    new CatalogRestClient.SystemDto("sys-1", "Petstore", "EXTERNAL", "http"),
                    "grp-1",
                    "spec-1",
                    new CatalogRestClient.OperationDto("op-1", "getInventory", "GET", null, null),
                    ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
                    "",
                    "ev",
                    ""));
    assertTrue(ex.getMessage().contains("http"));
    assertTrue(ex.getMessage().contains("integrationOperationPath"));
    assertFalse(ex.getMessage().contains("-"));
  }

  @Test
  void httpWithoutMethodFailsClosed() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CatalogOperationProjector.project(
                    "node-1",
                    "call-1",
                    new CatalogRestClient.SystemDto("sys-1", "Petstore", "EXTERNAL", "http"),
                    "grp-1",
                    "spec-1",
                    new CatalogRestClient.OperationDto("op-1", "getInventory", null, "/x", null),
                    ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
                    "",
                    "ev",
                    ""));
    assertTrue(ex.getMessage().contains("http"));
    assertFalse(ex.getMessage().contains("-"));
  }

  @Test
  void grpcFailsClosedUntilCatalogExposesSyncFlag() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CatalogOperationProjector.project(
                    "node-1",
                    "call-1",
                    new CatalogRestClient.SystemDto("sys-1", "G", "EXTERNAL", "grpc"),
                    "grp-1",
                    "spec-1",
                    new CatalogRestClient.OperationDto("op-1", "Run", "Run", null, null),
                    ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
                    "",
                    "ev",
                    ""));
    assertTrue(ex.getMessage().contains("grpc"));
    assertTrue(ex.getMessage().contains("synchronousGrpcCall"));
  }

  @Test
  void graphqlFailsClosedUntilCatalogExposesQuery() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CatalogOperationProjector.project(
                    "node-1",
                    "call-1",
                    new CatalogRestClient.SystemDto("sys-1", "Gql", "EXTERNAL", "graphql"),
                    "grp-1",
                    "spec-1",
                    new CatalogRestClient.OperationDto("op-1", "Books", null, null, null),
                    ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
                    "",
                    "ev",
                    ""));
    assertTrue(ex.getMessage().contains("graphql"));
    assertTrue(ex.getMessage().contains("integrationGqlQuery"));
  }

  @Test
  void unsupportedProtocolFailsClosed() {
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto("sys-1", "MQTT", "EXTERNAL", "mqtt");
    CatalogRestClient.OperationDto operation =
        new CatalogRestClient.OperationDto("op-1", "onMessage", "subscribe", null, null);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CatalogOperationProjector.project(
                    "node-1",
                    "call-1",
                    system,
                    "grp-1",
                    "spec-1",
                    operation,
                    ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
                    "",
                    "ev",
                    ""));
    assertTrue(ex.getMessage().contains("mqtt"));
  }

  @Test
  void kafkaSubscribeOmitsPath() {
    ResolvedServiceCallBinding binding =
        CatalogOperationProjector.project(
            "node-1",
            "call-1",
            new CatalogRestClient.SystemDto("sys-1", "Events", "INTERNAL", "kafka"),
            "grp-1",
            "spec-1",
            new CatalogRestClient.OperationDto("op-1", "onOrder", "subscribe", null, null),
            ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
            "",
            "ev",
            "");
    assertEquals("kafka", binding.protocolType());
    assertEquals("subscribe", binding.method());
    assertEquals("", binding.path());
  }

  @Test
  void kafkaSpecificationSuppliesTopicClassifierAndGroupId() throws Exception {
    JsonNode specification =
        new ObjectMapper()
            .readTree(
                """
                {
                  "topic": "task.wfms_createWorkOrder.start",
                  "maasClassifierName": "wfms",
                  "groupId": "g-1"
                }
                """);
    ResolvedServiceCallBinding binding =
        CatalogOperationProjector.project(
            "trigger-async",
            "consume-om",
            new CatalogRestClient.SystemDto("sys-om", "OM WFMS", "INTERNAL", "kafka"),
            "sg-om",
            "spec-om",
            new CatalogRestClient.OperationDto(
                "op-om", "onTaskStart", "subscribe", null, "spec-om", specification),
            ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
            "catalog",
            "ev",
            "");
    assertEquals("kafka", binding.protocolType());
    assertEquals("subscribe", binding.method());
    assertEquals("task.wfms_createWorkOrder.start", binding.path());
    assertEquals("wfms", binding.maasClassifierName());
    assertEquals("g-1", binding.groupId());
  }
}
