package org.qubership.integration.platform.ai.a2a.persistence.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageClaimResult;
import org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageReceipt;
import org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptResult;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistenceException;
import org.qubership.integration.platform.ai.a2a.persistence.A2aReceiptProcessingState;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskCreate;

/**
 * PostgreSQL-backed Message receipt store. Caller-scoped receipts own idempotency and resumable
 * dispatch state. Initial create claims the receipt and the {@code WORKING} Task in one
 * transaction.
 */
@ApplicationScoped
public class JdbcA2aMessageReceiptRepository implements A2aMessageReceiptRepository {

  private static final String INSERT_TASK_RECEIPT_SQL =
      """
      INSERT INTO a2a_message_receipts (task_id, message_id, received_at, command_fingerprint)
      VALUES (?, ?, ?, ?)
      ON CONFLICT DO NOTHING
      """;

  private static final String SELECT_TASK_RECEIPT_FINGERPRINT_SQL =
      """
      SELECT command_fingerprint
      FROM a2a_message_receipts
      WHERE task_id = ? AND message_id = ?
      """;

  private static final String EXISTS_SQL =
      """
      SELECT 1
      FROM a2a_message_receipts
      WHERE task_id = ? AND message_id = ?
      """;

  private static final String CLAIM_CALLER_SQL =
      """
      INSERT INTO a2a_caller_message_receipts
          (tenant_id, subject_id, message_id, task_id, received_at, command_fingerprint,
           processing_state, fingerprint_version, command_kind, precondition_revision,
           command_descriptor, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, 'CLAIMED', ?, ?, ?, ?, ?)
      ON CONFLICT DO NOTHING
      """;

  private static final String FIND_CALLER_RECEIPT_SQL =
      """
      SELECT task_id, command_fingerprint, processing_state, last_task_revision,
             response_task_revision, command_kind, precondition_revision
      FROM a2a_caller_message_receipts
      WHERE tenant_id = ? AND subject_id = ? AND message_id = ?
      """;

  private static final String MARK_DISPATCHING_SQL =
      """
      UPDATE a2a_caller_message_receipts
      SET processing_state = 'DISPATCHING',
          dispatch_owner_token = ?,
          dispatch_lease_until = ?,
          updated_at = ?
      WHERE tenant_id = ? AND subject_id = ? AND message_id = ?
        AND (
          processing_state = 'CLAIMED'
          OR (processing_state = 'DISPATCHING'
              AND (dispatch_lease_until IS NULL OR dispatch_lease_until < ?))
        )
      """;

  private static final String RENEW_DISPATCH_SQL =
      """
      UPDATE a2a_caller_message_receipts
      SET dispatch_lease_until = ?,
          updated_at = ?
      WHERE tenant_id = ? AND subject_id = ? AND message_id = ?
        AND processing_state = 'DISPATCHING'
        AND dispatch_owner_token = ?
      """;

  private static final String MARK_COMPLETED_SQL =
      """
      UPDATE a2a_caller_message_receipts
      SET processing_state = 'COMPLETED',
          last_task_revision = ?,
          response_task_revision = ?,
          dispatch_owner_token = NULL,
          dispatch_lease_until = NULL,
          updated_at = ?
      WHERE tenant_id = ? AND subject_id = ? AND message_id = ?
        AND processing_state IN ('CLAIMED', 'DISPATCHING')
      """;

  private static final String COMPLETE_OWNED_SQL =
      """
      UPDATE a2a_caller_message_receipts
      SET processing_state = 'COMPLETED',
          last_task_revision = ?,
          response_task_revision = ?,
          dispatch_owner_token = NULL,
          dispatch_lease_until = NULL,
          updated_at = ?
      WHERE tenant_id = ? AND subject_id = ? AND message_id = ?
        AND processing_state = 'DISPATCHING'
        AND dispatch_owner_token = ?
      """;

  private static final String RELEASE_OWNED_SQL =
      """
      UPDATE a2a_caller_message_receipts
      SET processing_state = 'CLAIMED',
          dispatch_owner_token = NULL,
          dispatch_lease_until = NULL,
          updated_at = ?
      WHERE tenant_id = ? AND subject_id = ? AND message_id = ?
        AND processing_state = 'DISPATCHING'
        AND dispatch_owner_token = ?
      """;

  private static final String INSERT_TASK_SQL =
      """
      INSERT INTO a2a_tasks (
        task_id, context_id, conversation_id, state, revision,
        tenant_id, subject_id, public_snapshot, message_history, artifact_metadata,
        created_at, updated_at, finalized_at
      ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private final DataSource dataSource;
  private volatile Clock clock;
  private volatile Duration dispatchLease;

  @Inject
  public JdbcA2aMessageReceiptRepository(
      DataSource dataSource,
      org.qubership.integration.platform.ai.configuration.AppConfig appConfig) {
    this.dataSource = dataSource;
    this.clock = Clock.systemUTC();
    this.dispatchLease = Objects.requireNonNull(appConfig.a2a().dispatchLease(), "dispatchLease");
  }

  /** Test seam: replaces the clock used for dispatch leases. */
  public void setClock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Test seam: replaces the dispatch lease duration. */
  public void setDispatchLease(Duration dispatchLease) {
    this.dispatchLease = Objects.requireNonNull(dispatchLease, "dispatchLease");
  }

  @Override
  public A2aMessageReceiptResult recordIfAbsent(
      String taskId, String messageId, String commandFingerprint) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(commandFingerprint, "commandFingerprint");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(INSERT_TASK_RECEIPT_SQL)) {
      statement.setString(1, taskId);
      statement.setString(2, messageId);
      statement.setTimestamp(3, Timestamp.from(Instant.now()));
      statement.setString(4, commandFingerprint);
      int inserted = statement.executeUpdate();
      if (inserted == 1) {
        return new A2aMessageReceiptResult.Accepted();
      }
      String existing = requireTaskReceiptFingerprint(connection, taskId, messageId);
      if (existing.equals(commandFingerprint)) {
        return new A2aMessageReceiptResult.Duplicate();
      }
      return new A2aMessageReceiptResult.FingerprintConflict(existing);
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to record message receipt for task "
              + taskId
              + " message "
              + messageId,
          ex);
    }
  }

  @Override
  public boolean exists(String taskId, String messageId) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(messageId, "messageId");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(EXISTS_SQL)) {
      statement.setString(1, taskId);
      statement.setString(2, messageId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to check message receipt for task "
              + taskId
              + " message "
              + messageId,
          ex);
    }
  }

  @Override
  public A2aCallerMessageClaimResult claimInitialWithWorkingTask(
      String tenantId,
      String subjectId,
      String messageId,
      String commandFingerprint,
      String commandKind,
      A2aTaskCreate workingTask) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(commandFingerprint, "commandFingerprint");
    Objects.requireNonNull(workingTask, "workingTask");
    Instant now = Instant.now();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        int claimed =
            insertCallerClaim(
                connection,
                tenantId,
                subjectId,
                messageId,
                workingTask.taskId(),
                commandFingerprint,
                commandKind,
                null,
                now);
        if (claimed != 1) {
          connection.rollback();
          return resolveExistingClaim(
              connection, tenantId, subjectId, messageId, commandFingerprint, workingTask.taskId());
        }

        try (PreparedStatement statement = connection.prepareStatement(INSERT_TASK_SQL)) {
          statement.setString(1, workingTask.taskId());
          setNullableString(statement, 2, workingTask.contextId());
          statement.setString(3, workingTask.conversationId());
          statement.setString(4, workingTask.state().name());
          setNullableString(statement, 5, workingTask.tenantId());
          setNullableString(statement, 6, workingTask.subjectId());
          statement.setString(7, workingTask.publicSnapshotJson());
          statement.setString(8, workingTask.messageHistoryJson());
          statement.setString(9, workingTask.artifactMetadataJson());
          statement.setTimestamp(10, Timestamp.from(now));
          statement.setTimestamp(11, Timestamp.from(now));
          setNullableInstant(statement, 12, workingTask.finalizedAt());
          statement.executeUpdate();
        }

        insertTaskReceipt(connection, workingTask.taskId(), messageId, commandFingerprint, now);
        connection.commit();
        return new A2aCallerMessageClaimResult.Claimed(workingTask.taskId());
      } catch (SQLException | A2aPersistenceException ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (A2aPersistenceException ex) {
      throw ex;
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to claim initial message receipt for message "
              + messageId,
          ex);
    }
  }

  @Override
  public A2aCallerMessageClaimResult claimContinuation(
      String tenantId,
      String subjectId,
      String messageId,
      String commandFingerprint,
      String commandKind,
      String taskId) {
    return claimContinuation(
        tenantId, subjectId, messageId, commandFingerprint, commandKind, taskId, null);
  }

  @Override
  public A2aCallerMessageClaimResult claimContinuation(
      String tenantId,
      String subjectId,
      String messageId,
      String commandFingerprint,
      String commandKind,
      String taskId,
      Long preconditionRevision) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(commandFingerprint, "commandFingerprint");
    Objects.requireNonNull(taskId, "taskId");
    Instant now = Instant.now();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        int claimed =
            insertCallerClaim(
                connection,
                tenantId,
                subjectId,
                messageId,
                taskId,
                commandFingerprint,
                commandKind,
                preconditionRevision,
                now);
        if (claimed != 1) {
          connection.rollback();
          return resolveExistingClaim(
              connection, tenantId, subjectId, messageId, commandFingerprint, taskId);
        }
        insertTaskReceipt(connection, taskId, messageId, commandFingerprint, now);
        connection.commit();
        return new A2aCallerMessageClaimResult.Claimed(taskId);
      } catch (SQLException | A2aPersistenceException ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (A2aPersistenceException ex) {
      throw ex;
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to claim continuation receipt for message "
              + messageId,
          ex);
    }
  }

  @Override
  public boolean markDispatching(String tenantId, String subjectId, String messageId) {
    return acquireDispatch(tenantId, subjectId, messageId).result()
        == A2aDispatchAcquisition.Result.ACQUIRED;
  }

  @Override
  public A2aDispatchAcquisition acquireDispatch(
      String tenantId, String subjectId, String messageId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    Instant now = clock.instant();
    UUID ownerToken = UUID.randomUUID();
    Instant leaseUntil = now.plus(dispatchLease);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(MARK_DISPATCHING_SQL)) {
      statement.setObject(1, ownerToken);
      statement.setTimestamp(2, Timestamp.from(leaseUntil));
      statement.setTimestamp(3, Timestamp.from(now));
      statement.setString(4, tenantId);
      statement.setString(5, subjectId);
      statement.setString(6, messageId);
      statement.setTimestamp(7, Timestamp.from(now));
      int updated = statement.executeUpdate();
      if (updated == 1) {
        return A2aDispatchAcquisition.acquired(ownerToken);
      }
      Optional<A2aCallerMessageReceipt> existing =
          findCallerReceipt(connection, tenantId, subjectId, messageId);
      if (existing.isPresent() && existing.get().completed()) {
        return A2aDispatchAcquisition.completed();
      }
      return A2aDispatchAcquisition.busy();
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to acquire dispatch ownership for message "
              + messageId,
          ex);
    }
  }

  @Override
  public boolean renewDispatch(
      String tenantId, String subjectId, String messageId, UUID ownerToken) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(ownerToken, "ownerToken");
    Instant now = clock.instant();
    Instant leaseUntil = now.plus(dispatchLease);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(RENEW_DISPATCH_SQL)) {
      statement.setTimestamp(1, Timestamp.from(leaseUntil));
      statement.setTimestamp(2, Timestamp.from(now));
      statement.setString(3, tenantId);
      statement.setString(4, subjectId);
      statement.setString(5, messageId);
      statement.setObject(6, ownerToken);
      return statement.executeUpdate() == 1;
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to renew dispatch lease for message " + messageId,
          ex);
    }
  }

  @Override
  public void markCompleted(
      String tenantId,
      String subjectId,
      String messageId,
      long lastTaskRevision,
      long responseTaskRevision) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(MARK_COMPLETED_SQL)) {
      statement.setLong(1, lastTaskRevision);
      statement.setLong(2, responseTaskRevision);
      statement.setTimestamp(3, Timestamp.from(clock.instant()));
      statement.setString(4, tenantId);
      statement.setString(5, subjectId);
      statement.setString(6, messageId);
      int updated = statement.executeUpdate();
      if (updated != 1) {
        A2aCallerMessageReceipt existing =
            findCallerReceipt(tenantId, subjectId, messageId)
                .orElseThrow(
                    () ->
                        new A2aPersistenceException(
                            "A2A task persistence failed: receipt missing while marking COMPLETED"));
        if (!existing.completed()) {
          throw new A2aPersistenceException(
              "A2A task persistence failed: illegal receipt transition to COMPLETED from "
                  + existing.processingState());
        }
      }
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to mark receipt COMPLETED for message " + messageId,
          ex);
    }
  }

  @Override
  public void completeDispatch(
      String tenantId,
      String subjectId,
      String messageId,
      UUID ownerToken,
      long lastTaskRevision,
      long responseTaskRevision) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(ownerToken, "ownerToken");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(COMPLETE_OWNED_SQL)) {
      statement.setLong(1, lastTaskRevision);
      statement.setLong(2, responseTaskRevision);
      statement.setTimestamp(3, Timestamp.from(clock.instant()));
      statement.setString(4, tenantId);
      statement.setString(5, subjectId);
      statement.setString(6, messageId);
      statement.setObject(7, ownerToken);
      int updated = statement.executeUpdate();
      if (updated != 1) {
        A2aCallerMessageReceipt existing =
            findCallerReceipt(tenantId, subjectId, messageId)
                .orElseThrow(
                    () ->
                        new A2aPersistenceException(
                            "A2A task persistence failed: receipt missing while completing dispatch"));
        if (!existing.completed()) {
          throw new A2aPersistenceException(
              "A2A task persistence failed: owner token cannot complete receipt in state "
                  + existing.processingState());
        }
      }
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to complete owned dispatch for message " + messageId,
          ex);
    }
  }

  @Override
  public void releaseDispatch(
      String tenantId, String subjectId, String messageId, UUID ownerToken) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(ownerToken, "ownerToken");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(RELEASE_OWNED_SQL)) {
      statement.setTimestamp(1, Timestamp.from(clock.instant()));
      statement.setString(2, tenantId);
      statement.setString(3, subjectId);
      statement.setString(4, messageId);
      statement.setObject(5, ownerToken);
      statement.executeUpdate();
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to release dispatch ownership for message "
              + messageId,
          ex);
    }
  }

  @Override
  public Optional<String> findTaskIdForCallerMessage(
      String tenantId, String subjectId, String messageId) {
    return findCallerReceipt(tenantId, subjectId, messageId).map(A2aCallerMessageReceipt::taskId);
  }

  @Override
  public Optional<A2aCallerMessageReceipt> findCallerReceipt(
      String tenantId, String subjectId, String messageId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(subjectId, "subjectId");
    Objects.requireNonNull(messageId, "messageId");
    try (Connection connection = dataSource.getConnection()) {
      return findCallerReceipt(connection, tenantId, subjectId, messageId);
    } catch (SQLException ex) {
      throw new A2aPersistenceException(
          "A2A task persistence failed: unable to look up caller message receipt for message "
              + messageId,
          ex);
    }
  }

  private static int insertCallerClaim(
      Connection connection,
      String tenantId,
      String subjectId,
      String messageId,
      String taskId,
      String commandFingerprint,
      String commandKind,
      Long preconditionRevision,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CLAIM_CALLER_SQL)) {
      statement.setString(1, tenantId);
      statement.setString(2, subjectId);
      statement.setString(3, messageId);
      statement.setString(4, taskId);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.setString(6, commandFingerprint);
      statement.setString(7, "v1");
      setNullableString(statement, 8, commandKind);
      if (preconditionRevision == null) {
        statement.setObject(9, null);
      } else {
        statement.setLong(9, preconditionRevision);
      }
      statement.setString(
          10,
          org.qubership.integration.platform.ai.a2a.transport.A2aCommandId.derive(
              tenantId, subjectId, messageId));
      statement.setTimestamp(11, Timestamp.from(now));
      return statement.executeUpdate();
    }
  }

  private static void insertTaskReceipt(
      Connection connection,
      String taskId,
      String messageId,
      String commandFingerprint,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_TASK_RECEIPT_SQL)) {
      statement.setString(1, taskId);
      statement.setString(2, messageId);
      statement.setTimestamp(3, Timestamp.from(now));
      statement.setString(4, commandFingerprint);
      statement.executeUpdate();
    }
  }

  private static A2aCallerMessageClaimResult resolveExistingClaim(
      Connection connection,
      String tenantId,
      String subjectId,
      String messageId,
      String commandFingerprint,
      String requestedTaskId)
      throws SQLException {
    A2aCallerMessageReceipt existing =
        findCallerReceipt(connection, tenantId, subjectId, messageId)
            .orElseThrow(
                () ->
                    new A2aPersistenceException(
                        "A2A task persistence failed: caller message receipt conflict without row"));
    if (!existing.commandFingerprint().equals(commandFingerprint)) {
      return new A2aCallerMessageClaimResult.FingerprintConflict(
          existing.taskId(), existing.commandFingerprint());
    }
    if (existing.incomplete()) {
      // Prefer Incomplete over TaskBindingConflict: lost-initial retries omit taskId, so the SDK
      // may stamp a new id. Resume the durable bound Task rather than reject the retry.
      return new A2aCallerMessageClaimResult.Incomplete(
          existing.taskId(), existing.processingState());
    }
    if (!existing.taskId().equals(requestedTaskId)) {
      return new A2aCallerMessageClaimResult.TaskBindingConflict(
          existing.taskId(), requestedTaskId);
    }
    return new A2aCallerMessageClaimResult.AlreadyBound(existing.taskId());
  }

  private static Optional<A2aCallerMessageReceipt> findCallerReceipt(
      Connection connection, String tenantId, String subjectId, String messageId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FIND_CALLER_RECEIPT_SQL)) {
      statement.setString(1, tenantId);
      statement.setString(2, subjectId);
      statement.setString(3, messageId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        Long lastRevision = (Long) resultSet.getObject("last_task_revision");
        Long responseRevision = (Long) resultSet.getObject("response_task_revision");
        String stateValue = resultSet.getString("processing_state");
        A2aReceiptProcessingState state =
            stateValue == null || stateValue.isBlank()
                ? A2aReceiptProcessingState.COMPLETED
                : A2aReceiptProcessingState.valueOf(stateValue);
        return Optional.of(
            new A2aCallerMessageReceipt(
                resultSet.getString("task_id"),
                resultSet.getString("command_fingerprint"),
                state,
                lastRevision,
                responseRevision,
                resultSet.getString("command_kind"),
                (Long) resultSet.getObject("precondition_revision")));
      }
    }
  }

  private static String requireTaskReceiptFingerprint(
      Connection connection, String taskId, String messageId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(SELECT_TASK_RECEIPT_FINGERPRINT_SQL)) {
      statement.setString(1, taskId);
      statement.setString(2, messageId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new A2aPersistenceException(
              "A2A task persistence failed: message receipt conflict without row for task "
                  + taskId
                  + " message "
                  + messageId);
        }
        String fingerprint = resultSet.getString(1);
        return fingerprint == null ? "" : fingerprint;
      }
    }
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
}
