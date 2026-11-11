package org.qubership.integration.platform.ai.flow.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Ticket 01: Flyway owns the Quarkus Flow JPA tables on the existing PostgreSQL datasource.
 */
@QuarkusTest
class FlowPersistenceSchemaIT {

  @Inject DataSource dataSource;
  @Inject FlowPersistenceReadiness readiness;
  @Inject PersistenceInstanceHandlers persistenceHandlers;

  @Test
  void flywayCreatesFlowJpaTablesOnTheExistingDatasource() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      Set<String> tables = loadTables(connection);
      assertTrue(tables.contains("cloud_event_entity"), tables::toString);
      assertTrue(tables.contains("workflow_instance_entity"), tables::toString);
      assertTrue(tables.contains("task_info_entity"), tables::toString);
    }
  }

  @Test
  void readinessPingRequiresTheJpaProviderAndFlowTables() {
    assertNotNull(persistenceHandlers);
    readiness.ping();
  }

  @Test
  void flywayRecordsTheFlowSchemaMigrationOnce() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT version, success
                FROM flyway_schema_history
                WHERE version = '7'
                ORDER BY installed_rank
                """);
        ResultSet resultSet = statement.executeQuery()) {
      assertTrue(resultSet.next(), "Flyway must record V7");
      assertEquals("7", resultSet.getString("version"));
      assertTrue(resultSet.getBoolean("success"));
      assertTrue(
          !resultSet.next(),
          "V7 must appear once; repeated boots rely on Flyway history, not IF NOT EXISTS");
    }
  }

  private static Set<String> loadTables(Connection connection) throws Exception {
    Set<String> tables = new HashSet<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                """);
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        tables.add(resultSet.getString("table_name"));
      }
    }
    return tables;
  }
}
