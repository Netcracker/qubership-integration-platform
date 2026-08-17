package org.qubership.integration.platform.ai.flow.persistence;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Fails startup when the Flow JPA provider or its Flyway tables are missing from PostgreSQL.
 */
@ApplicationScoped
@Unremovable
public class FlowPersistenceReadiness {

  static final List<String> REQUIRED_TABLES =
      List.of("cloud_event_entity", "workflow_instance_entity", "task_info_entity");

  private final DataSource dataSource;
  private final Instance<PersistenceInstanceHandlers> persistenceHandlers;

  @Inject
  public FlowPersistenceReadiness(
      DataSource dataSource, Instance<PersistenceInstanceHandlers> persistenceHandlers) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.persistenceHandlers = Objects.requireNonNull(persistenceHandlers, "persistenceHandlers");
  }

  void onStart(@Observes StartupEvent ignored) {
    ping();
  }

  public void ping() {
    if (persistenceHandlers.isUnsatisfied()) {
      throw new FlowPersistenceException(
          "Flow persistence failed: JPA persistence provider is not available");
    }
    persistenceHandlers.get();
    try (Connection connection = dataSource.getConnection()) {
      for (String table : REQUIRED_TABLES) {
        if (!tableExists(connection, table)) {
          throw new FlowPersistenceException(
              "Flow persistence failed: required table "
                  + table
                  + " is missing from the PostgreSQL datasource");
        }
      }
    } catch (FlowPersistenceException failure) {
      throw failure;
    } catch (SQLException exception) {
      throw new FlowPersistenceException(
          "Flow persistence failed: unable to ping Flow datasource", exception);
    }
  }

  private static boolean tableExists(Connection connection, String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = current_schema()
              AND table_name = ?
            """)) {
      statement.setString(1, table);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }
}
