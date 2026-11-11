package org.qubership.integration.platform.ai.a2a.persistence.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistenceException;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskCreate;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskTransitionResult;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskUpdate;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * PostgreSQL-backed {@link A2aTaskRepository} using optimistic {@code revision} updates.
 */
@ApplicationScoped
public class JdbcA2aTaskRepository implements A2aTaskRepository {

  private static final String INSERT_SQL =
      """
      INSERT INTO a2a_tasks (
        task_id, context_id, conversation_id, state, revision,
        tenant_id, subject_id, public_snapshot, message_history, artifact_metadata,
        created_at, updated_at, finalized_at
      ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String SELECT_BY_ID_SQL =
      """
      SELECT task_id, context_id, conversation_id, state, revision,
             tenant_id, subject_id, public_snapshot, message_history, artifact_metadata,
             created_at, updated_at, finalized_at
      FROM a2a_tasks
      WHERE task_id = ?
      """;

  private static final String UPDATE_SQL =
      """
      UPDATE a2a_tasks
      SET state = ?,
          public_snapshot = ?,
          message_history = ?,
          artifact_metadata = ?,
          finalized_at = ?,
          updated_at = ?,
          revision = revision + 1
      WHERE task_id = ? AND revision = ?
      """;

  private final DataSource dataSource;

  @Inject
  public JdbcA2aTaskRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public A2aPersistedTask insert(A2aTaskCreate create) {
    Objects.requireNonNull(create, "create");
    Instant now = Instant.now();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      statement.setString(1, create.taskId());
      setNullableString(statement, 2, create.contextId());
      statement.setString(3, create.conversationId());
      statement.setString(4, create.state().name());
      setNullableString(statement, 5, create.tenantId());
      setNullableString(statement, 6, create.subjectId());
      statement.setString(7, create.publicSnapshotJson());
      statement.setString(8, create.messageHistoryJson());
      statement.setString(9, create.artifactMetadataJson());
      statement.setTimestamp(10, Timestamp.from(now));
      statement.setTimestamp(11, Timestamp.from(now));
      setNullableInstant(statement, 12, create.finalizedAt());
      statement.executeUpdate();
    } catch (SQLException ex) {
      throw persistenceFailure("insert A2A task " + create.taskId(), ex);
    }
    return findByTaskId(create.taskId())
        .orElseThrow(
            () ->
                new A2aPersistenceException(
                    "A2A task persistence failed: inserted task " + create.taskId() + " is missing"));
  }

  @Override
  public Optional<A2aPersistedTask> findByTaskId(String taskId) {
    Objects.requireNonNull(taskId, "taskId");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID_SQL)) {
      statement.setString(1, taskId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        return Optional.of(mapRow(resultSet));
      }
    } catch (SQLException ex) {
      throw persistenceFailure("load A2A task " + taskId, ex);
    }
  }

  @Override
  public A2aTaskTransitionResult transition(String taskId, A2aTaskUpdate update) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(update, "update");
    Instant now = Instant.now();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
          statement.setString(1, update.state().name());
          statement.setString(2, update.publicSnapshotJson());
          statement.setString(3, update.messageHistoryJson());
          statement.setString(4, update.artifactMetadataJson());
          setNullableInstant(statement, 5, update.finalizedAt());
          statement.setTimestamp(6, Timestamp.from(now));
          statement.setString(7, taskId);
          statement.setLong(8, update.expectedRevision());
          updated = statement.executeUpdate();
        }
        if (updated == 1) {
          connection.commit();
          A2aPersistedTask applied =
              findByTaskId(connection, taskId)
                  .orElseThrow(
                      () ->
                          new A2aPersistenceException(
                              "A2A task persistence failed: updated task "
                                  + taskId
                                  + " is missing"));
          return new A2aTaskTransitionResult.Applied(applied);
        }
        connection.rollback();
        A2aPersistedTask current =
            findByTaskId(connection, taskId)
                .orElseThrow(
                    () ->
                        new A2aPersistenceException(
                            "A2A task persistence failed: task " + taskId + " was not found"));
        return new A2aTaskTransitionResult.StaleRevision(current);
      } catch (SQLException | A2aPersistenceException ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (A2aPersistenceException ex) {
      throw ex;
    } catch (SQLException ex) {
      throw persistenceFailure("transition A2A task " + taskId, ex);
    }
  }

  @Override
  public void ping() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement("SELECT 1");
        ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        throw new A2aPersistenceException("A2A task persistence failed: readiness query returned no row");
      }
    } catch (SQLException ex) {
      throw persistenceFailure("ping A2A datasource", ex);
    }
  }

  private Optional<A2aPersistedTask> findByTaskId(Connection connection, String taskId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID_SQL)) {
      statement.setString(1, taskId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        return Optional.of(mapRow(resultSet));
      }
    }
  }

  private static A2aPersistedTask mapRow(ResultSet resultSet) throws SQLException {
    return new A2aPersistedTask(
        resultSet.getString("task_id"),
        resultSet.getString("context_id"),
        resultSet.getString("conversation_id"),
        A2aTaskState.valueOf(resultSet.getString("state")),
        resultSet.getLong("revision"),
        resultSet.getString("tenant_id"),
        resultSet.getString("subject_id"),
        resultSet.getString("public_snapshot"),
        resultSet.getString("message_history"),
        resultSet.getString("artifact_metadata"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        toInstant(resultSet.getTimestamp("finalized_at")));
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static void setNullableString(PreparedStatement statement, int index, String value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, value);
    }
  }

  private static void setNullableInstant(PreparedStatement statement, int index, Instant value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      statement.setTimestamp(index, Timestamp.from(value));
    }
  }

  private static A2aPersistenceException persistenceFailure(String action, SQLException cause) {
    return new A2aPersistenceException("A2A task persistence failed: unable to " + action, cause);
  }
}
