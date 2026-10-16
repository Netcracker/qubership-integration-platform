package org.qubership.integration.platform.ai.a2a.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.jdbc.JdbcA2aTaskRepository;

/**
 * Slice 6: datasource boot contract and typed English persistence failures.
 */
@QuarkusTest
class A2aDatasourceIT {

  @Inject A2aTaskRepository taskRepository;

  @Test
  void bootsWithPostgreSQLFlywayAndRepositoryReadinessPing() {
    taskRepository.ping();
    assertTrue(
        taskRepository.findByTaskId("missing-task-for-readiness").isEmpty(),
        "readiness-level find must succeed against an empty result");
  }

  @Test
  void applicationPropertiesDocumentStandardDatasourceEnvContract() throws IOException {
    String properties;
    try (InputStream stream =
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream("application.properties")) {
      properties = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(properties.contains("QUARKUS_DATASOURCE_JDBC_URL"));
    assertTrue(properties.contains("QUARKUS_DATASOURCE_USERNAME"));
    assertTrue(properties.contains("QUARKUS_DATASOURCE_PASSWORD"));
    assertTrue(properties.contains("QUARKUS_FLYWAY_MIGRATE_AT_START"));
    assertTrue(properties.contains("quarkus.datasource.db-kind=postgresql"));
    assertTrue(properties.contains("%prod.quarkus.datasource.devservices.enabled=false"));
    assertTrue(properties.contains("ai_a2a"));
  }

  @Test
  void invalidDatasourceFailsWithClearEnglishPersistenceError() throws Exception {
    DataSource broken = mock(DataSource.class);
    when(broken.getConnection()).thenThrow(new SQLException("Connection to localhost:1 refused"));
    JdbcA2aTaskRepository repository = new JdbcA2aTaskRepository(broken);
    A2aPersistenceException failure =
        assertThrows(A2aPersistenceException.class, repository::ping);
    assertTrue(failure.getMessage().startsWith("A2A task persistence failed:"));
    assertTrue(failure.getMessage().contains("unable to ping A2A datasource"));
    assertFalse(failure.getMessage().isBlank());
  }
}
