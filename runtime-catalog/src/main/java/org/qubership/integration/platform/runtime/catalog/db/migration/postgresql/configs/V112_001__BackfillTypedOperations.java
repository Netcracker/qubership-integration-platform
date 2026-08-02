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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
        List<IdValue> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(SELECT_OPERATIONS)) {
            while (resultSet.next()) {
                String typedJson = typedJson(
                        resultSet.getString("path"),
                        resultSet.getString("method"),
                        readTree(resultSet.getString("specification")),
                        protocolOf(resultSet.getString("protocol")));
                if (typedJson != null) {
                    rows.add(new IdValue(resultSet.getString("id"), typedJson));
                }
            }
        }
        return writeBatch(connection, UPDATE_OPERATION_TYPED, rows);
    }

    private int backfillModelSpecificationType(Connection connection) throws Exception {
        List<IdValue> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(SELECT_MODELS)) {
            while (resultSet.next()) {
                String specificationType = BACKFILL.backfillSpecificationType(
                        protocolOf(resultSet.getString("protocol")));
                if (specificationType != null) {
                    rows.add(new IdValue(resultSet.getString("id"), specificationType));
                }
            }
        }
        return writeBatch(connection, UPDATE_MODEL_SPECIFICATION_TYPE, rows);
    }

    // No rows means no statement is prepared, so a re-run that selects nothing writes nothing.
    private int writeBatch(Connection connection, String sql, List<IdValue> rows) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (IdValue row : rows) {
                statement.setString(1, row.value());
                statement.setString(2, row.id());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return rows.size();
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

    // Carries an id plus the column value to write: typed jsonb for operations, specification_type for models.
    private record IdValue(String id, String value) {
    }
}
