package org.qubership.integration.platform.ai.chain.deploy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.EnvironmentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SystemDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

/**
 * Classifies a deploy follow-up when Kafka MaaS topics or classifiers are missing, and collects
 * standalone {@code kafka-trigger-2} / {@code kafka-sender-2} plus catalog Kafka hops whose active
 * environment is {@code MAAS_BY_CLASSIFIER}.
 */
public final class MaasKafkaTopicsFollowUp {

  public enum Kind {
    DEPLOYED,
    MISSING_KAFKA_TOPICS,
    FAILED,
    PROCESSING
  }

  public record TopicPair(String namespace, String classifier) {}

  public record CollectedTopics(
      List<TopicPair> creatable,
      List<TopicPair> skippedTenant,
      List<TopicPair> skippedPlaceholder) {

    public CollectedTopics {
      creatable = creatable == null ? List.of() : List.copyOf(creatable);
      skippedTenant = skippedTenant == null ? List.of() : List.copyOf(skippedTenant);
      skippedPlaceholder =
          skippedPlaceholder == null ? List.of() : List.copyOf(skippedPlaceholder);
    }
  }

  static final String CLASSIFIER_MISS_PREFIX = "Failed to get classifier ";
  static final String CLASSIFIER_MISS_SUFFIX = " from MaaS";
  static final String PHYSICAL_TOPIC_PREFIX = "Kafka topics (";
  static final String PHYSICAL_TOPIC_SUFFIX = ") not found";

  private static final String KAFKA_TRIGGER_2 = "kafka-trigger-2";
  private static final String KAFKA_SENDER_2 = "kafka-sender-2";
  private static final String ASYNC_API_TRIGGER = "async-api-trigger";
  private static final String SERVICE_CALL = "service-call";
  private static final String CONNECTION_SOURCE_TYPE = "connectionSourceType";
  private static final String TOPICS_CLASSIFIER_NAME = "topicsClassifierName";
  private static final String MAAS_CLASSIFIER_NAMESPACE = "maasClassifierNamespace";
  private static final String MAAS_CLASSIFIER_TENANT_ENABLED = "maasClassifierTenantEnabled";
  private static final String INTEGRATION_SYSTEM_ID = "integrationSystemId";
  private static final String INTEGRATION_OPERATION_PROTOCOL_TYPE =
      "integrationOperationProtocolType";
  private static final String INTEGRATION_OPERATION_ASYNC_PROPERTIES =
      "integrationOperationAsyncProperties";
  private static final String ASYNC_MAAS_CLASSIFIER_NAME = "maas.classifier.name";
  private static final String ASYNC_MAAS_CLASSIFIER_NAMESPACE = "maas.classifier.namespace";
  private static final String ASYNC_MAAS_CLASSIFIER_TENANT_ENABLED =
      "maas.classifier.tenantEnabled";
  private static final String UNRESOLVED_PLACEHOLDER = "#{";
  private static final String MAAS = "maas";
  private static final String KAFKA = "kafka";
  private static final String MAAS_BY_CLASSIFIER = "MAAS_BY_CLASSIFIER";
  private static final String STATUS_DEPLOYED = "DEPLOYED";
  private static final String STATUS_FAILED = "FAILED";

  private MaasKafkaTopicsFollowUp() {}

  public static boolean isMissingTopicsError(Iterable<String> errors) {
    if (errors == null) {
      return false;
    }
    for (String error : errors) {
      if (isMissingTopicsError(error)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isMissingTopicsError(String error) {
    if (error == null || error.isBlank()) {
      return false;
    }
    return error.contains(CLASSIFIER_MISS_PREFIX)
        || (error.contains(PHYSICAL_TOPIC_PREFIX) && error.contains(PHYSICAL_TOPIC_SUFFIX));
  }

  public static List<String> namedClassifiers(String errors) {
    if (errors == null || errors.isBlank()) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    int from = 0;
    int start = errors.indexOf(CLASSIFIER_MISS_PREFIX, from);
    while (start >= 0) {
      int nameStart = start + CLASSIFIER_MISS_PREFIX.length();
      int end = errors.indexOf(CLASSIFIER_MISS_SUFFIX, nameStart);
      if (end < 0) {
        return List.copyOf(names);
      }
      String name = errors.substring(nameStart, end).strip();
      if (!name.isBlank()) {
        names.add(name);
      }
      from = end + CLASSIFIER_MISS_SUFFIX.length();
      start = errors.indexOf(CLASSIFIER_MISS_PREFIX, from);
    }
    return List.copyOf(names);
  }

  public static List<TopicPair> collectStandalone(List<CatalogElementResponseDto> elements) {
    return collect(elements, null);
  }

  public static List<TopicPair> collect(
      List<CatalogElementResponseDto> elements, CatalogRestClient catalog) {
    return collectTopics(elements, catalog).creatable();
  }

  public static CollectedTopics collectTopics(
      List<CatalogElementResponseDto> elements, CatalogRestClient catalog) {
    LinkedHashSet<TopicPair> pairs = new LinkedHashSet<>();
    LinkedHashSet<TopicPair> skippedTenant = new LinkedHashSet<>();
    LinkedHashSet<TopicPair> skippedPlaceholder = new LinkedHashSet<>();
    Set<String> seenIds = new HashSet<>();
    Map<String, Boolean> maasBySystemId = new HashMap<>();
    collect(
        elements, catalog, pairs, skippedTenant, skippedPlaceholder, seenIds, maasBySystemId);
    return new CollectedTopics(
        List.copyOf(pairs), List.copyOf(skippedTenant), List.copyOf(skippedPlaceholder));
  }

  private static void collect(
      List<CatalogElementResponseDto> elements,
      CatalogRestClient catalog,
      LinkedHashSet<TopicPair> pairs,
      LinkedHashSet<TopicPair> skippedTenant,
      LinkedHashSet<TopicPair> skippedPlaceholder,
      Set<String> seenIds,
      Map<String, Boolean> maasBySystemId) {
    if (elements == null) {
      return;
    }
    for (CatalogElementResponseDto element : elements) {
      if (element == null || element.id == null || !seenIds.add(element.id)) {
        continue;
      }
      TopicPair pair = standalonePair(element);
      if (pair == null) {
        pair = catalogHopPair(element, catalog, maasBySystemId);
      }
      if (pair != null) {
        addCollected(element, pair, pairs, skippedTenant, skippedPlaceholder);
      }
      collect(
          element.children,
          catalog,
          pairs,
          skippedTenant,
          skippedPlaceholder,
          seenIds,
          maasBySystemId);
    }
  }

  private static void addCollected(
      CatalogElementResponseDto element,
      TopicPair pair,
      LinkedHashSet<TopicPair> pairs,
      LinkedHashSet<TopicPair> skippedTenant,
      LinkedHashSet<TopicPair> skippedPlaceholder) {
    if (tenantEnabled(element)) {
      skippedTenant.add(pair);
      return;
    }
    if (unresolvedPlaceholder(pair)) {
      skippedPlaceholder.add(pair);
      return;
    }
    pairs.add(pair);
  }

  private static boolean unresolvedPlaceholder(TopicPair pair) {
    return pair != null
        && (containsPlaceholder(pair.classifier()) || containsPlaceholder(pair.namespace()));
  }

  public static boolean containsPlaceholder(String value) {
    return value != null && value.contains(UNRESOLVED_PLACEHOLDER);
  }

  private static boolean tenantEnabled(CatalogElementResponseDto element) {
    Map<String, Object> properties = element.properties == null ? Map.of() : element.properties;
    if (isFlagTrue(properties.get(MAAS_CLASSIFIER_TENANT_ENABLED))) {
      return true;
    }
    Map<?, ?> async = mapProperty(properties, INTEGRATION_OPERATION_ASYNC_PROPERTIES);
    return async != null && isFlagTrue(async.get(ASYNC_MAAS_CLASSIFIER_TENANT_ENABLED));
  }

  private static boolean isFlagTrue(Object value) {
    if (value instanceof Boolean flag) {
      return flag;
    }
    if (value instanceof String text) {
      return "true".equalsIgnoreCase(text.strip());
    }
    return false;
  }

  private static TopicPair standalonePair(CatalogElementResponseDto element) {
    if (!KAFKA_TRIGGER_2.equals(element.type) && !KAFKA_SENDER_2.equals(element.type)) {
      return null;
    }
    Map<String, Object> properties = element.properties == null ? Map.of() : element.properties;
    if (!MAAS.equalsIgnoreCase(stringProperty(properties, CONNECTION_SOURCE_TYPE))) {
      return null;
    }
    String classifier = stringProperty(properties, TOPICS_CLASSIFIER_NAME);
    if (classifier == null || classifier.isBlank()) {
      return null;
    }
    String namespace = stringProperty(properties, MAAS_CLASSIFIER_NAMESPACE);
    return new TopicPair(namespace == null ? "" : namespace, classifier);
  }

  private static TopicPair catalogHopPair(
      CatalogElementResponseDto element,
      CatalogRestClient catalog,
      Map<String, Boolean> maasBySystemId) {
    if (!ASYNC_API_TRIGGER.equals(element.type) && !SERVICE_CALL.equals(element.type)) {
      return null;
    }
    Map<String, Object> properties = element.properties == null ? Map.of() : element.properties;
    if (!KAFKA.equalsIgnoreCase(stringProperty(properties, INTEGRATION_OPERATION_PROTOCOL_TYPE))) {
      return null;
    }
    Map<?, ?> async = mapProperty(properties, INTEGRATION_OPERATION_ASYNC_PROPERTIES);
    if (async == null) {
      return null;
    }
    String classifier = stringValue(async.get(ASYNC_MAAS_CLASSIFIER_NAME));
    if (classifier == null || classifier.isBlank()) {
      return null;
    }
    String systemId = stringProperty(properties, INTEGRATION_SYSTEM_ID);
    if (!isMaasByClassifier(systemId, catalog, maasBySystemId)) {
      return null;
    }
    String namespace = stringValue(async.get(ASYNC_MAAS_CLASSIFIER_NAMESPACE));
    return new TopicPair(namespace == null ? "" : namespace, classifier);
  }

  private static boolean isMaasByClassifier(
      String systemId, CatalogRestClient catalog, Map<String, Boolean> maasBySystemId) {
    if (catalog == null || systemId == null || systemId.isBlank()) {
      return false;
    }
    Boolean cached = maasBySystemId.get(systemId);
    if (cached != null) {
      return cached;
    }
    boolean maas = lookupMaasByClassifier(systemId, catalog);
    maasBySystemId.put(systemId, maas);
    return maas;
  }

  private static boolean lookupMaasByClassifier(String systemId, CatalogRestClient catalog) {
    try {
      SystemDto system = catalog.getSystem(systemId);
      if (system == null) {
        return false;
      }
      List<EnvironmentDto> environments = catalog.getEnvironments(systemId);
      EnvironmentDto active = activeEnvironment(system, environments);
      return active != null && MAAS_BY_CLASSIFIER.equals(active.sourceType());
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static EnvironmentDto activeEnvironment(
      SystemDto system, List<EnvironmentDto> environments) {
    if (environments == null || environments.isEmpty()) {
      return null;
    }
    String activeId = system.activeEnvironmentId();
    if (activeId != null && !activeId.isBlank()) {
      for (EnvironmentDto environment : environments) {
        if (environment != null && activeId.equals(environment.id())) {
          return environment;
        }
      }
    }
    return environments.get(0);
  }

  private static Map<?, ?> mapProperty(Map<String, Object> properties, String key) {
    Object value = properties.get(key);
    return value instanceof Map<?, ?> map ? map : null;
  }

  private static String stringValue(Object value) {
    return value instanceof String text ? text : null;
  }

  private static String stringProperty(Map<String, Object> properties, String key) {
    return stringValue(properties.get(key));
  }

  public static List<TopicPair> offer(List<TopicPair> collected, List<String> namedClassifiers) {
    List<TopicPair> source = collected == null ? List.of() : collected;
    if (namedClassifiers == null || namedClassifiers.isEmpty()) {
      return List.copyOf(source);
    }
    Set<String> named = new LinkedHashSet<>(namedClassifiers);
    List<TopicPair> restricted = new ArrayList<>();
    for (TopicPair pair : source) {
      if (pair != null && named.contains(pair.classifier())) {
        restricted.add(pair);
      }
    }
    return List.copyOf(restricted);
  }

  public static Kind classify(
      String catalogStatus, boolean kafkaMiss, List<TopicPair> offered) {
    if (STATUS_DEPLOYED.equals(catalogStatus)) {
      return Kind.DEPLOYED;
    }
    if (kafkaMiss && offered != null && !offered.isEmpty()) {
      return Kind.MISSING_KAFKA_TOPICS;
    }
    if (STATUS_FAILED.equals(catalogStatus)) {
      return Kind.FAILED;
    }
    return Kind.PROCESSING;
  }
}
