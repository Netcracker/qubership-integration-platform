package org.qubership.integration.platform.runtime.catalog.db.migration.postgresql.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.Context;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.migration.TypedOperationBackfill;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * First concrete configs Java migration. It must be a {@code @Component}, or it never runs. Flyway runs only
 * through {@code FlywayInitializer}, which registers each {@code ConfigsJavaMigration} Spring bean through
 * {@code setJavaMigrations}; Spring Boot's own Flyway autoconfiguration is off. Flyway's classpath scan of the
 * configs location does not reach this class — the location {@code db/migration/postgresql/configs} maps to
 * package {@code db.migration.postgresql.configs}, while this class sits under
 * {@code org.qubership...db.migration.postgresql.configs} — so {@code setJavaMigrations} is the sole
 * registration and no duplicate-version conflict arises. Flyway instantiates the migration itself, not Spring,
 * so no collaborators are injected: all work runs over {@code context.getConnection()} plus Jackson.
 *
 * <p>Backfills the column-derived half only, reusing {@link TypedOperationBackfill}: {@code operations.typed}
 * from {@code path}/{@code method}/{@code specification}, and {@code models.specification_type} from the
 * system protocol. The reparse-only fields (WSDL {@code protocol}/{@code binding} and
 * {@code specification_version}) are left null on purpose; running a parser inside a migration is the wrong
 * place, so they fill on the next import or a one-off admin run. Both selects touch only rows still missing
 * the value, so a second run is a no-op. Multi-replica safe by Flyway's schema-history lock, which serializes
 * concurrent pods.
 */
@Slf4j
@Component
@SuppressWarnings("checkstyle:TypeName")
public class V112_001__BackfillTypedOperations extends ConfigsJavaMigration {

    static final String SELECT_OPERATIONS = """
            select o.id, o.path, o.method, o.specification, s.protocol
            from operations o
            join models m on o.model_id = m.id
            join api_group sg on m.api_group_id = sg.id
            join integration_system s on sg.system_id = s.id
            where o.typed is null""";

    static final String UPDATE_OPERATION_TYPED = "update operations set typed = cast(? as jsonb) where id = ?";

    static final String SELECT_MODELS = """
            select m.id, s.protocol
            from models m
            join api_group sg on m.api_group_id = sg.id
            join integration_system s on sg.system_id = s.id
            where m.specification_type is null""";

    static final String UPDATE_MODEL_SPECIFICATION_TYPE = "update models set specification_type = ? where id = ?";

    // Cursor page and batch flush share one size: neither side of the stream holds more rows than this.
    static final int BATCH_SIZE = 500;

    // JsonBinaryType reads the column with an equivalent mapper, so serialize the payload the same way.
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    // Column-derived path only; the reparse collaborators are unused, hence null (see the 3-arg overload).
    private static final TypedOperationBackfill BACKFILL = new TypedOperationBackfill(null, null);

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        int operations = backfillOperations(connection);
        int models = backfillModelSpecificationType(connection);
        log.info("Typed-operation backfill set typed on {} operation(s) and specification_type on {} model(s)",
                operations, models);
    }

    private int backfillOperations(Connection connection) throws Exception {
        return streamAndWrite(connection, SELECT_OPERATIONS, UPDATE_OPERATION_TYPED, row -> typedJson(
                row.getString("path"),
                row.getString("method"),
                readTree(row.getString("specification")),
                protocolOf(row.getString("protocol"))));
    }

    private int backfillModelSpecificationType(Connection connection) throws Exception {
        return streamAndWrite(connection, SELECT_MODELS, UPDATE_MODEL_SPECIFICATION_TYPE,
                row -> BACKFILL.backfillSpecificationType(protocolOf(row.getString("protocol"))));
    }

    // Reads through a cursor and flushes every BATCH_SIZE updates, so neither the result set nor the pending
    // batch grows with the table: holding every unfilled operation row, each with its specification jsonb,
    // spiked the startup heap, and an OOM inside @PostConstruct crash-loops the pod. setFetchSize takes effect
    // only with autoCommit off, which is what Flyway's per-migration transaction provides. A run that selects
    // nothing prepares the statement and executes no batch, so a second run stays a no-op.
    private int streamAndWrite(Connection connection, String selectSql, String updateSql, RowValue rowValue)
            throws Exception {
        int written = 0;
        int pending = 0;
        try (Statement select = connection.createStatement();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            select.setFetchSize(BATCH_SIZE);
            try (ResultSet rows = select.executeQuery(selectSql)) {
                while (rows.next()) {
                    String value = rowValue.of(rows);
                    if (value == null) {
                        continue;
                    }
                    update.setString(1, value);
                    update.setString(2, rows.getString("id"));
                    update.addBatch();
                    written++;
                    pending++;
                    if (pending == BATCH_SIZE) {
                        update.executeBatch();
                        pending = 0;
                    }
                }
            }
            if (pending > 0) {
                update.executeBatch();
            }
        }
        return written;
    }

    // Pure and database-free: rebuilds the typed payload from a row's columns and serializes it as the
    // JsonBinaryType reader expects. WSDL gets a bare WsdlOperation (method/path are constants; protocol/binding
    // reparse on the next import). Only METAMODEL has no API level, so it alone keeps a null typed.
    static String typedJson(String path, String method, JsonNode specification, OperationProtocol protocol) {
        if (protocol == null || protocol == OperationProtocol.METAMODEL) {
            return null;
        }
        Operation operation = new Operation();
        operation.setPath(path);
        operation.setMethod(method);
        operation.setSpecification(specification);
        TypedOperation typed = BACKFILL.backfillTyped(operation, specification, protocol);
        if (typed == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(typed);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize typed operation for path " + path, e);
        }
    }

    // integration_system.protocol is stored by enum name; an unknown or null value skips the row.
    static OperationProtocol protocolOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OperationProtocol.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonNode readTree(String json) throws JsonProcessingException {
        return json == null ? null : MAPPER.readTree(json);
    }

    // Derives the column value to write from the current row: typed jsonb for operations, specification_type
    // for models. A null skips the row.
    @FunctionalInterface
    private interface RowValue {
        String of(ResultSet row) throws Exception;
    }
}
