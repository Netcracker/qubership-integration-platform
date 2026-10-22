package org.qubership.integration.platform.ai.chain.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.EnvironmentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SystemDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

class MaasKafkaTopicsFollowUpTest {

  @Test
  void classifierMissingErrorIsAKafkaMaasMiss() {
    assertTrue(
        MaasKafkaTopicsFollowUp.isMissingTopicsError(
            "Failed to get classifier orders-in from MaaS"));
  }

  @Test
  void physicalTopicMissRequiresBothKafkaTopicsFragments() {
    assertTrue(
        MaasKafkaTopicsFollowUp.isMissingTopicsError(
            "Kafka topics (orders-in) not found, check if this topics exists in kafka"));
    assertFalse(
        MaasKafkaTopicsFollowUp.isMissingTopicsError(
            "Kafka predeploy check is failed. Connection configuration is invalid, "
                + "topics not found or broker is unavailable"));
  }

  @Test
  void physicalTopicMissDoesNotMatchWhenFragmentsAreSplitAcrossStates() {
    assertFalse(
        MaasKafkaTopicsFollowUp.isMissingTopicsError(
            List.of("Kafka topics (orders-in", ") not found")));
  }

  @Test
  void classifierMissOnAnySingleStateStillWins() {
    assertTrue(
        MaasKafkaTopicsFollowUp.isMissingTopicsError(
            List.of(
                "HTTP trigger context path already bound",
                "Failed to get classifier orders-in from MaaS")));
  }

  @Test
  void physicalTopicMissOnAnySingleStateStillWins() {
    assertTrue(
        MaasKafkaTopicsFollowUp.isMissingTopicsError(
            List.of(
                "HTTP trigger context path already bound",
                "Kafka topics (orders-in) not found, check if this topics exists in kafka")));
  }

  @Test
  void extractsNamedClassifierBetweenFailedToGetAndFromMaas() {
    assertEquals(
        List.of("orders-in"),
        MaasKafkaTopicsFollowUp.namedClassifiers(
            "Failed to get classifier orders-in from MaaS. retry"));
  }

  @Test
  void namedClassifierExtractIsCaseSensitive() {
    assertEquals(
        List.of("Orders-In"),
        MaasKafkaTopicsFollowUp.namedClassifiers("Failed to get classifier Orders-In from MaaS"));
  }

  @Test
  void collectsMaasStandalonePairsAndSkipsManual() {
    CatalogElementResponseDto maasTrigger = element(
        "el-trigger",
        "kafka-trigger-2",
        Map.of(
            "connectionSourceType", "maas",
            "topicsClassifierName", "orders-in",
            "maasClassifierNamespace", "qip-dev"));
    CatalogElementResponseDto maasSender = element(
        "el-sender",
        "kafka-sender-2",
        Map.of(
            "connectionSourceType", "maas",
            "topicsClassifierName", "orders-out",
            "maasClassifierNamespace", "qip-dev"));
    CatalogElementResponseDto manual = element(
        "el-manual",
        "kafka-trigger-2",
        Map.of(
            "connectionSourceType", "manual",
            "topicsClassifierName", "legacy-in",
            "maasClassifierNamespace", "qip-dev"));

    List<MaasKafkaTopicsFollowUp.TopicPair> pairs =
        MaasKafkaTopicsFollowUp.collectStandalone(List.of(maasTrigger, maasSender, manual));

    assertEquals(
        List.of(
            new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-in"),
            new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-out")),
        pairs);
  }

  @Test
  void collectsCatalogKafkaHopsWhenActiveEnvIsMaasByClassifier() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    stubSystem(catalog, "sys-1", "env-maas", "MAAS_BY_CLASSIFIER");
    CatalogElementResponseDto trigger =
        catalogHop("el-async", "async-api-trigger", "sys-1", "wfms-start", "qip-dev");
    CatalogElementResponseDto call =
        catalogHop("el-call", "service-call", "sys-1", "wfms-result", "qip-dev");

    List<MaasKafkaTopicsFollowUp.TopicPair> pairs =
        MaasKafkaTopicsFollowUp.collect(List.of(trigger, call), catalog);

    assertEquals(
        List.of(
            new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "wfms-start"),
            new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "wfms-result")),
        pairs);
    verify(catalog, times(1)).getSystem("sys-1");
    verify(catalog, times(1)).getEnvironments("sys-1");
  }

  @Test
  void skipsCatalogHopOnManualEnvEvenWithLeftoverClassifier() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    stubSystem(catalog, "sys-1", "env-manual", "MANUAL");
    CatalogElementResponseDto trigger =
        catalogHop("el-async", "async-api-trigger", "sys-1", "leftover", "qip-dev");

    assertEquals(List.of(), MaasKafkaTopicsFollowUp.collect(List.of(trigger), catalog));
  }

  @Test
  void skipsCatalogHopWhenSystemIdIsBlankOrLookupFails() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getSystem("sys-missing"))
        .thenThrow(new RuntimeException("system not found"));
    CatalogElementResponseDto blank =
        catalogHop("el-blank", "async-api-trigger", "", "wfms-start", "qip-dev");
    CatalogElementResponseDto missing =
        catalogHop("el-missing", "service-call", "sys-missing", "wfms-result", "qip-dev");

    assertEquals(List.of(), MaasKafkaTopicsFollowUp.collect(List.of(blank, missing), catalog));
  }

  @Test
  void usesFirstEnvironmentWhenActiveEnvironmentIdIsNull() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getSystem("sys-1"))
        .thenReturn(new SystemDto("sys-1", "Kafka", "INTERNAL", "kafka"));
    when(catalog.getEnvironments("sys-1"))
        .thenReturn(
            List.of(new EnvironmentDto("env-1", "maas", null, "MAAS_BY_CLASSIFIER")));
    CatalogElementResponseDto trigger =
        catalogHop("el-async", "async-api-trigger", "sys-1", "wfms-start", "");

    assertEquals(
        List.of(new MaasKafkaTopicsFollowUp.TopicPair("", "wfms-start")),
        MaasKafkaTopicsFollowUp.collect(List.of(trigger), catalog));
  }

  @Test
  void matchesActiveEnvironmentNotTheFirstEnv() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getSystem("sys-1"))
        .thenReturn(new SystemDto("sys-1", "Kafka", "INTERNAL", "kafka", "env-active"));
    when(catalog.getEnvironments("sys-1"))
        .thenReturn(
            List.of(
                new EnvironmentDto("env-first", "maas", null, "MAAS_BY_CLASSIFIER"),
                new EnvironmentDto("env-active", "manual", "localhost:9092", "MANUAL")));
    CatalogElementResponseDto trigger =
        catalogHop("el-async", "async-api-trigger", "sys-1", "leftover", "qip-dev");

    assertEquals(List.of(), MaasKafkaTopicsFollowUp.collect(List.of(trigger), catalog));
  }

  @Test
  void namedClassifierRestrictsTheOfferToChainMatches() {
    List<MaasKafkaTopicsFollowUp.TopicPair> collected =
        List.of(
            new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-in"),
            new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-out"));

    assertEquals(
        List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-in")),
        MaasKafkaTopicsFollowUp.offer(collected, List.of("orders-in")));
    assertEquals(collected, MaasKafkaTopicsFollowUp.offer(collected, List.of()));
  }

  @Test
  void classifyPrefersDeployedThenKafkaMissThenFailedThenProcessing() {
    List<MaasKafkaTopicsFollowUp.TopicPair> pairs =
        List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-in"));
    assertEquals(
        MaasKafkaTopicsFollowUp.Kind.DEPLOYED,
        MaasKafkaTopicsFollowUp.classify("DEPLOYED", true, pairs));
    assertEquals(
        MaasKafkaTopicsFollowUp.Kind.MISSING_KAFKA_TOPICS,
        MaasKafkaTopicsFollowUp.classify("PROCESSING", true, pairs));
    assertEquals(
        MaasKafkaTopicsFollowUp.Kind.MISSING_KAFKA_TOPICS,
        MaasKafkaTopicsFollowUp.classify("FAILED", true, pairs));
    assertEquals(
        MaasKafkaTopicsFollowUp.Kind.FAILED,
        MaasKafkaTopicsFollowUp.classify("FAILED", false, pairs));
    assertEquals(
        MaasKafkaTopicsFollowUp.Kind.PROCESSING,
        MaasKafkaTopicsFollowUp.classify("PROCESSING", true, List.of()));
    assertEquals(
        MaasKafkaTopicsFollowUp.Kind.FAILED,
        MaasKafkaTopicsFollowUp.classify("FAILED", true, List.of()));
    assertEquals(
        MaasKafkaTopicsFollowUp.Kind.PROCESSING,
        MaasKafkaTopicsFollowUp.classify("PROCESSING", false, pairs));
  }

  @ParameterizedTest
  @MethodSource("tenantTrueValues")
  void skipsTenantEnabledStandalone(Object tenantValue) {
    CatalogElementResponseDto tenant =
        element(
            "el-tenant",
            "kafka-trigger-2",
            map(
                "connectionSourceType",
                "maas",
                "topicsClassifierName",
                "orders-tenant",
                "maasClassifierNamespace",
                "qip-dev",
                "maasClassifierTenantEnabled",
                tenantValue));
    CatalogElementResponseDto creatable = element(
        "el-ok",
        "kafka-sender-2",
        Map.of(
            "connectionSourceType", "maas",
            "topicsClassifierName", "orders-in",
            "maasClassifierNamespace", "qip-dev"));

    MaasKafkaTopicsFollowUp.CollectedTopics collected =
        MaasKafkaTopicsFollowUp.collectTopics(List.of(tenant, creatable), null);

    assertEquals(
        List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-in")),
        collected.creatable());
    assertEquals(
        List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-tenant")),
        collected.skippedTenant());
    assertEquals(List.of(), collected.skippedPlaceholder());
  }

  @Test
  void keepsStandaloneWhenTenantIsFalse() {
    CatalogElementResponseDto element =
        element(
            "el-ok",
            "kafka-trigger-2",
            map(
                "connectionSourceType",
                "maas",
                "topicsClassifierName",
                "orders-in",
                "maasClassifierNamespace",
                "qip-dev",
                "maasClassifierTenantEnabled",
                false));

    assertEquals(
        List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-in")),
        MaasKafkaTopicsFollowUp.collectStandalone(List.of(element)));
  }

  @ParameterizedTest
  @MethodSource("tenantTrueValues")
  @SuppressWarnings("unchecked")
  void skipsCatalogHopWhenAsyncTenantIsTrue(Object tenantValue) {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    stubSystem(catalog, "sys-1", "env-maas", "MAAS_BY_CLASSIFIER");
    CatalogElementResponseDto trigger =
        catalogHop("el-async", "async-api-trigger", "sys-1", "wfms-start", "qip-dev");
    Map<String, Object> async =
        (Map<String, Object>) trigger.properties.get("integrationOperationAsyncProperties");
    async.put("maas.classifier.tenantEnabled", tenantValue);

    MaasKafkaTopicsFollowUp.CollectedTopics collected =
        MaasKafkaTopicsFollowUp.collectTopics(List.of(trigger), catalog);

    assertEquals(List.of(), collected.creatable());
    assertEquals(
        List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "wfms-start")),
        collected.skippedTenant());
  }

  @Test
  void skipsUnresolvedPlaceholderInClassifierOrNamespace() {
    CatalogElementResponseDto classifier =
        element(
            "el-class",
            "kafka-trigger-2",
            Map.of(
                "connectionSourceType", "maas",
                "topicsClassifierName", "#{ordersTopic}",
                "maasClassifierNamespace", "qip-dev"));
    CatalogElementResponseDto namespace =
        element(
            "el-ns",
            "kafka-sender-2",
            Map.of(
                "connectionSourceType", "maas",
                "topicsClassifierName", "orders-out",
                "maasClassifierNamespace", "#{namespace}"));

    MaasKafkaTopicsFollowUp.CollectedTopics collected =
        MaasKafkaTopicsFollowUp.collectTopics(List.of(classifier, namespace), null);

    assertEquals(List.of(), collected.creatable());
    assertEquals(
        List.of(
            new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "#{ordersTopic}"),
            new MaasKafkaTopicsFollowUp.TopicPair("#{namespace}", "orders-out")),
        collected.skippedPlaceholder());
  }

  private static Stream<Object> tenantTrueValues() {
    return Stream.of(true, "true", "TRUE", " True ");
  }

  private static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      properties.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return properties;
  }

  private static CatalogElementResponseDto element(
      String id, String type, Map<String, Object> properties) {
    CatalogElementResponseDto dto = new CatalogElementResponseDto();
    dto.id = id;
    dto.type = type;
    dto.properties = properties;
    return dto;
  }

  private static CatalogElementResponseDto catalogHop(
      String id, String type, String systemId, String classifier, String namespace) {
    Map<String, Object> async = new LinkedHashMap<>();
    async.put("maas.classifier.name", classifier);
    if (namespace != null && !namespace.isBlank()) {
      async.put("maas.classifier.namespace", namespace);
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("integrationSystemId", systemId);
    properties.put("integrationOperationProtocolType", "kafka");
    properties.put("integrationOperationAsyncProperties", async);
    return element(id, type, properties);
  }

  private static void stubSystem(
      CatalogRestClient catalog, String systemId, String envId, String sourceType) {
    when(catalog.getSystem(systemId))
        .thenReturn(new SystemDto(systemId, "Kafka", "INTERNAL", "kafka", envId));
    when(catalog.getEnvironments(systemId))
        .thenReturn(List.of(new EnvironmentDto(envId, "env", null, sourceType)));
  }
}
