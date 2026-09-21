package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogUpdateEnvironmentRequest;

class InternalAsyncMaasEnvironmentConfigurerTest {

  private CatalogRestClient catalogRestClient;
  private InternalAsyncMaasEnvironmentConfigurer configurer;

  @BeforeEach
  void setUp() {
    catalogRestClient = mock(CatalogRestClient.class);
    configurer = new InternalAsyncMaasEnvironmentConfigurer(catalogRestClient, new ObjectMapper());
  }

  @Test
  void configuresKafkaInternalManualEnvironmentAsMaas() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders", "INTERNAL", "kafka"));
    when(catalogRestClient.getEnvironments("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", "", "MANUAL")));

    configurer.configure("sys-1");

    verify(catalogRestClient)
        .updateEnvironment(
            eq("sys-1"),
            eq("env-1"),
            any(CatalogUpdateEnvironmentRequest.class));
  }

  @Test
  void configuresAmqpInternalManualEnvironmentWithRabbitDefaults() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "events", "INTERNAL", "amqp"));
    when(catalogRestClient.getEnvironments("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", "", "MANUAL")));

    configurer.configure("sys-1");

    verify(catalogRestClient)
        .updateEnvironment(
            eq("sys-1"),
            eq("env-1"),
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    "MAAS_BY_CLASSIFIER".equals(request.sourceType())
                        && "AUTO".equals(request.properties().get("acknowledgeMode").asText())
                        && "".equals(request.properties().get("routingKey").asText())));
  }

  @Test
  void skipsHttpInternalServices() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders", "INTERNAL", "http"));
    when(catalogRestClient.getEnvironments("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", "", "MANUAL")));

    configurer.configure("sys-1");

    verify(catalogRestClient, never()).updateEnvironment(any(), any(), any());
  }

  @Test
  void skipsExternalKafkaServices() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders", "EXTERNAL", "kafka"));
    when(catalogRestClient.getEnvironments("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", "", "MANUAL")));

    configurer.configure("sys-1");

    verify(catalogRestClient, never()).updateEnvironment(any(), any(), any());
  }

  @Test
  void skipsAlreadyMaasEnvironment() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders", "INTERNAL", "kafka"));
    when(catalogRestClient.getEnvironments("sys-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.EnvironmentDto(
                    "env-1", "default", "", "MAAS_BY_CLASSIFIER")));

    configurer.configure("sys-1");

    verify(catalogRestClient, never()).updateEnvironment(any(), any(), any());
  }

  @Test
  void skipsWhenEnvironmentMissing() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders", "INTERNAL", "kafka"));
    when(catalogRestClient.getEnvironments("sys-1")).thenReturn(List.of());

    configurer.configure("sys-1");

    verify(catalogRestClient, never()).updateEnvironment(any(), any(), any());
  }

  @Test
  void doesNotPropagateCatalogUpdateFailures() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders", "INTERNAL", "kafka"));
    when(catalogRestClient.getEnvironments("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", "", "MANUAL")));
    when(catalogRestClient.updateEnvironment(eq("sys-1"), eq("env-1"), any()))
        .thenThrow(new RuntimeException("catalog unavailable"));

    configurer.configure("sys-1");
  }

  @Test
  void kafkaMaasPropertiesAreEmptyObject() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders", "INTERNAL", "kafka"));
    when(catalogRestClient.getEnvironments("sys-1"))
        .thenReturn(
            List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", "", "MANUAL")));

    configurer.configure("sys-1");

    verify(catalogRestClient)
        .updateEnvironment(
            eq("sys-1"),
            eq("env-1"),
            org.mockito.ArgumentMatchers.argThat(
                request -> request.properties() != null && request.properties().isEmpty()));
  }

  @Test
  void treatsNullSourceTypeAsManual() {
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders", "INTERNAL", "kafka"));
    when(catalogRestClient.getEnvironments("sys-1"))
        .thenReturn(List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", "", null)));

    configurer.configure("sys-1");

    verify(catalogRestClient).updateEnvironment(eq("sys-1"), eq("env-1"), any());
  }
}
