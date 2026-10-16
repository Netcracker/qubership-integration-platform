package org.qubership.integration.platform.ai.a2a.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Slice 5: schema indexes and Flyway history safety.
 */
@QuarkusTest
class A2aTaskSchemaContractIT {

  @Inject DataSource dataSource;

  @Test
  void migrationCreatesLookupFinalizationAndReceiptIndexes() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      Set<String> indexes = loadIndexes(connection, "a2a_tasks");
      assertTrue(indexes.contains("idx_a2a_tasks_conversation_id"), indexes::toString);
      assertTrue(indexes.contains("idx_a2a_tasks_finalized_at"), indexes::toString);

      Set<String> receiptIndexes = loadIndexes(connection, "a2a_message_receipts");
      assertTrue(receiptIndexes.contains("idx_a2a_message_receipts_task_id"), receiptIndexes::toString);
      assertTrue(receiptIndexes.contains("pk_a2a_message_receipts"), receiptIndexes::toString);
    }
  }

  @Test
  void repeatedMigrationIsTrackedByFlywayHistory() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT version, success, checksum
                FROM flyway_schema_history
                WHERE version = '1'
                ORDER BY installed_rank
                """);
        ResultSet resultSet = statement.executeQuery()) {
      assertTrue(resultSet.next(), "Flyway must record V1");
      assertEquals("1", resultSet.getString("version"));
      assertTrue(resultSet.getBoolean("success"));
      resultSet.getInt("checksum");
      assertTrue(
          !resultSet.next(),
          "V1 must appear once; repeated boots rely on Flyway history, not IF NOT EXISTS");
    }
  }

  private static Set<String> loadIndexes(Connection connection, String table) throws Exception {
    Set<String> indexes = new HashSet<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = ?
                """)) {
      statement.setString(1, table);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          indexes.add(resultSet.getString("indexname"));
        }
      }
    }
    return indexes;
  }
}
