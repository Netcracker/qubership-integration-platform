package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class ElementSchemaMaterializationIT {
  @ParameterizedTest
  @MethodSource("runtimeProperties")
  void schemaTypedPropertiesReachTheCatalogPayload(String type, String json) throws Exception {
    var mapper = new ObjectMapper();
    var schemas = DeterministicElementSchemaService.createForUnitTests(mapper);
    var properties = mapper.readTree(json);
    var planProperties = properties.properties().stream()
        .map(entry -> new PlanProperty(entry.getKey(), entry.getValue().isTextual()
            ? entry.getValue().asText() : entry.getValue().toString())).toList();
    var node = new ChainPlanNode("node", type, "Element", null, null, planProperties);
    var graph = new ChainPlanGraph("1.0", new ChainSection("Schema contract", null), List.of(node), List.of());
    var catalog = mock(CatalogRestClient.class);
    var current = new CatalogElementResponseDto();
    current.properties = Map.of("catalogMetadata", "preserved");
    when(catalog.getElement("chain", "element")).thenReturn(current);
    var materializer = new ChainPlanPropertiesMaterializer(catalog, schemas, mapper);
    var result = materializer.apply(graph,
        new MaterializationMap("chain", Map.of("node", "element"), Map.of(), Map.of()));
    assertEquals(1, result.patchedCount(), result.toString());
    ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
    verify(catalog).updateElement(eq("chain"), eq("element"), patch.capture());
    var actual = mapper.readTree(mapper.writeValueAsString(patch.getValue().get("properties")));
    properties.properties().forEach(entry ->
        assertEquals(entry.getValue(), actual.get(entry.getKey()), type + "." + entry.getKey()));
    assertEquals("preserved", actual.path("catalogMetadata").asText());
  }

  private static Stream<Arguments> runtimeProperties() {
    return Stream.of(
        Arguments.of("chain-call-2", """
            {"elementId":"resolved-target-id","timeout":30000,"block":true,"failIfNoConsumers":false}
            """),
        Arguments.of("chain-call-2", """
            {"elementId":"resolved-target-id","block":true}
            """),
        Arguments.of("context-storage", """
            {"contextServiceId":"context","operation":"GET","useCorrelationId":true,"target":"BODY",
             "overrideContextParams":{"X-Request-Id":"request-id"}}
            """),
        Arguments.of("rabbitmq-trigger-2", """
            {"connectionSourceType":"manual","exchange":"orders","addresses":"localhost",
             "vhost":"/orders","sslProtocol":"TLSv1.2","queues":"orders"}
            """),
        Arguments.of("rabbitmq-sender-2", """
            {"connectionSourceType":"manual","exchange":"orders","addresses":"localhost","vhost":"/orders"}
            """),
        Arguments.of("rabbitmq-sender-2", """
            {"connectionSourceType":"maas","exchange":"orders","vhostClassifierName":"public"}
            """));
  }
}
