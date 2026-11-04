package org.qubership.integration.platform.ai.config.persistence;

import static com.netcracker.cloud.dbaas.client.DbaasConst.CUSTOM_KEYS;
import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;
import static com.netcracker.cloud.dbaas.client.DbaasConst.MICROSERVICE_NAME;
import static com.netcracker.cloud.dbaas.client.DbaasConst.NAMESPACE;
import static com.netcracker.cloud.dbaas.client.DbaasConst.SCOPE;
import static com.netcracker.cloud.dbaas.client.DbaasConst.TENANT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.netcracker.cloud.core.quarkus.dbaas.datasource.service.DbaaSPostgresDbCreationService;
import com.netcracker.cloud.dbaas.client.entity.database.DatasourceConnectorSettings;
import com.netcracker.cloud.dbaas.client.management.DbaasDbClassifier;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DbaasDatasourceProducerTest {

  private static final String MS_NAME = "qip-ai-service";
  private static final String NS = "qip-ns";
  private static final String TENANT = "t-1";
  private static final String TENANT_DEFAULT_ID_PROPERTY = "tenant.default.id";

  private String previousTenantId;

  @BeforeEach
  void setDefaultTenantId() {
    previousTenantId = System.setProperty(TENANT_DEFAULT_ID_PROPERTY, TENANT);
  }

  @AfterEach
  void restoreDefaultTenantId() {
    if (previousTenantId == null) {
      System.clearProperty(TENANT_DEFAULT_ID_PROPERTY);
    } else {
      System.setProperty(TENANT_DEFAULT_ID_PROPERTY, previousTenantId);
    }
  }

  @Test
  void aiA2aDataSource_usesLogicalDbNameAndTenantClassifier() {
    DbaaSPostgresDbCreationService creationService =
        org.mockito.Mockito.mock(DbaaSPostgresDbCreationService.class);
    ArgumentCaptor<DbaasDbClassifier> classifierCaptor =
        ArgumentCaptor.forClass(DbaasDbClassifier.class);
    when(creationService.getOrCreatePostgresDatabase(
            classifierCaptor.capture(),
            any(DatasourceConnectorSettings.class),
            org.mockito.ArgumentMatchers.isNull()))
        .thenThrow(new IllegalStateException("classifier-captured"));

    DbaasDatasourceProducer producer = new DbaasDatasourceProducer();
    producer.microserviceName = MS_NAME;
    producer.namespace = NS;

    assertThrows(
        IllegalStateException.class,
        () -> producer.aiA2aDataSource(creationService).getConnection());

    var classifier = classifierCaptor.getValue().asMap();
    @SuppressWarnings("unchecked")
    Map<String, Object> customKeys = (Map<String, Object>) classifier.get(CUSTOM_KEYS);
    assertNotNull(customKeys);
    assertEquals("ai_a2a", customKeys.get(LOGICAL_DB_NAME));
    assertEquals(MS_NAME, classifier.get(MICROSERVICE_NAME));
    assertEquals(NS, classifier.get(NAMESPACE));
    assertEquals("tenant", classifier.get(SCOPE));
    assertEquals(TENANT, classifier.get(TENANT_ID));
  }
}
