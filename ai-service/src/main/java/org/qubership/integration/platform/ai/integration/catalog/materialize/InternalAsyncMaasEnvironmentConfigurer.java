package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogUpdateEnvironmentRequest;

@ApplicationScoped
public class InternalAsyncMaasEnvironmentConfigurer {

  private static final Logger LOG = Logger.getLogger(InternalAsyncMaasEnvironmentConfigurer.class);

  private static final String SYSTEM_TYPE_INTERNAL = "INTERNAL";
  private static final String SOURCE_TYPE_MANUAL = "MANUAL";
  private static final String SOURCE_TYPE_MAAS_BY_CLASSIFIER = "MAAS_BY_CLASSIFIER";
  private static final String PROTOCOL_KAFKA = "kafka";
  private static final String PROTOCOL_AMQP = "amqp";

  private final CatalogRestClient catalogRestClient;
  private final ObjectMapper objectMapper;

  @Inject
  public InternalAsyncMaasEnvironmentConfigurer(
      @RestClient CatalogRestClient catalogRestClient, ObjectMapper objectMapper) {
    this.catalogRestClient = catalogRestClient;
    this.objectMapper = objectMapper;
  }

  public void configure(String systemId) {
    if (systemId == null || systemId.isBlank()) {
      return;
    }
    try {
      CatalogRestClient.SystemDto system = catalogRestClient.getSystem(systemId);
      if (system == null || !isInternal(system.type())) {
        return;
      }
      String protocol = normalizeProtocol(system.protocol());
      if (!isAsyncProtocol(protocol)) {
        return;
      }

      List<CatalogRestClient.EnvironmentDto> environments =
          catalogRestClient.getEnvironments(systemId);
      if (environments == null || environments.isEmpty()) {
        LOG.warnf("Skipping MaaS environment configure: no environment for systemId=%s", systemId);
        return;
      }
      if (environments.size() != 1) {
        LOG.warnf(
            "Skipping MaaS environment configure: expected one environment for INTERNAL"
                + " systemId=%s but found %d",
            systemId,
            environments.size());
        return;
      }

      CatalogRestClient.EnvironmentDto environment = environments.get(0);
      if (environment == null
          || environment.id() == null
          || environment.id().isBlank()
          || !requiresManualSourceType(environment.sourceType())) {
        return;
      }

      String address = environment.address() == null ? "" : environment.address();
      String name =
          environment.name() == null || environment.name().isBlank()
              ? "default"
              : environment.name();
      catalogRestClient.updateEnvironment(
          systemId,
          environment.id(),
          new CatalogUpdateEnvironmentRequest(
              name, address, SOURCE_TYPE_MAAS_BY_CLASSIFIER, maasProperties(protocol)));
      LOG.infof(
          "Configured MaaS environment for INTERNAL %s service systemId=%s environmentId=%s",
          protocol,
          systemId,
          environment.id());
    } catch (Exception e) {
      LOG.warnf(e, "Failed to configure MaaS environment for systemId=%s", systemId);
    }
  }

  private static boolean isInternal(String systemType) {
    return systemType != null
        && SYSTEM_TYPE_INTERNAL.equals(systemType.trim().toUpperCase(Locale.ROOT));
  }

  private static String normalizeProtocol(String protocol) {
    return protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isAsyncProtocol(String protocol) {
    return PROTOCOL_KAFKA.equals(protocol) || PROTOCOL_AMQP.equals(protocol);
  }

  private static boolean requiresManualSourceType(String sourceType) {
    if (sourceType == null || sourceType.isBlank()) {
      return true;
    }
    return SOURCE_TYPE_MANUAL.equals(sourceType.trim().toUpperCase(Locale.ROOT));
  }

  private JsonNode maasProperties(String protocol) {
    if (PROTOCOL_AMQP.equals(protocol)) {
      ObjectNode properties = objectMapper.createObjectNode();
      properties.put("routingKey", "");
      properties.put("acknowledgeMode", "AUTO");
      return properties;
    }
    return objectMapper.createObjectNode();
  }
}
