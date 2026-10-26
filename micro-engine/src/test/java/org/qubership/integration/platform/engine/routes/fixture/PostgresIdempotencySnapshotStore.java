package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.qubership.integration.platform.engine.camel.idempotency.PostgresIdempotentRepository;
import org.qubership.integration.platform.engine.persistence.shared.entity.IdempotencyRecord;
import org.qubership.integration.platform.engine.persistence.shared.repository.IdempotencyRecordRepository;
import org.qubership.integration.platform.engine.service.IdempotencyRecordService;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PostgresIdempotencySnapshotStore implements AutoCloseable {
    private static final String MIGRATION = "db/migration/postgresql/static/V102_000__idempotency-records.sql";
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.2");
    private final List<InsertionAttempt> insertionAttempts = new CopyOnWriteArrayList<>();
    private SessionFactory sessionFactory;
    private PostgresIdempotentRepository repository;

    void start() throws Exception {
        postgres.start();
        try (Connection connection = postgres.createConnection("");
                Statement statement = connection.createStatement();
                InputStream migration = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            statement.execute("create schema engine");
            statement.execute("set search_path to engine");
            statement.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
        }
        sessionFactory = new Configuration()
                .addAnnotatedClass(IdempotencyRecord.class)
                .setProperty("hibernate.connection.url", postgres.getJdbcUrl())
                .setProperty("hibernate.connection.username", postgres.getUsername())
                .setProperty("hibernate.connection.password", postgres.getPassword())
                .setProperty("hibernate.connection.driver_class", "org.postgresql.Driver")
                .setProperty("hibernate.default_schema", "engine")
                .setProperty("hibernate.hbm2ddl.auto", "none")
                .buildSessionFactory();
        repository = new PostgresIdempotentRepository(new IdempotencyRecordService(
                ObjectMappers.getObjectMapper(), new TransactionalRepository()));
    }

    PostgresIdempotentRepository repository() {
        return repository;
    }

    void resetAttempts() {
        insertionAttempts.clear();
    }

    Map<String, Object> observations(Map<String, StoredRecord> previous) throws Exception {
        Map<String, StoredRecord> records = readRecords();
        InsertionAttempt last = insertionAttempts.isEmpty() ? null : insertionAttempts.getLast();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("insertionAttempts", insertionAttempts.size());
        values.put("key", last == null ? null : last.key());
        values.put("keyExpiry", last == null ? null : last.ttl());
        values.put("storedKeys", List.copyOf(records.keySet()));
        values.put("updatedKeys", records.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(previous.get(entry.getKey())))
                .map(Map.Entry::getKey).toList());
        List<Map<String, Object>> recordValues = new ArrayList<>();
        for (Map.Entry<String, StoredRecord> entry : records.entrySet()) {
            StoredRecord record = entry.getValue();
            recordValues.add(Map.of("key", entry.getKey(), "data", record.data(),
                    "keyExpiry", record.ttl(), "active", record.active()));
        }
        values.put("records", recordValues);
        return values;
    }

    Map<String, StoredRecord> readRecords() throws Exception {
        String sql = """
                select key, data, created_at, expires_at,
                    extract(epoch from expires_at - created_at)::integer as ttl,
                    expires_at >= now() as active
                from engine.idempotency_records order by key collate "C"
                """;
        Map<String, StoredRecord> records = new LinkedHashMap<>();
        try (Connection connection = postgres.createConnection("");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Map<String, Object> data = ObjectMappers.getObjectMapper().readValue(
                        result.getString("data"), new TypeReference<>() { });
                assertEquals(Map.of("status", "RECEIVED"), data,
                        "Idempotency record must preserve the production service payload.");
                records.put(result.getString("key"), new StoredRecord(data,
                        result.getObject("created_at", OffsetDateTime.class),
                        result.getObject("expires_at", OffsetDateTime.class),
                        result.getInt("ttl"), result.getBoolean("active")));
            }
        }
        return records;
    }

    void expire(String key) throws Exception {
        try (Connection connection = postgres.createConnection("");
                PreparedStatement statement = connection.prepareStatement("""
                        update engine.idempotency_records
                        set expires_at = now() - interval '1 second' where key = ?
                        """)) {
            statement.setString(1, key);
            assertEquals(1, statement.executeUpdate(), "Idempotency fixture must expire one existing key.");
        }
    }

    @Override
    public void close() {
        try {
            if (sessionFactory != null) {
                sessionFactory.close();
            }
        } finally {
            postgres.close();
        }
    }

    record StoredRecord(Map<String, Object> data, OffsetDateTime createdAt, OffsetDateTime expiresAt,
            int ttl, boolean active) {
    }

    private record InsertionAttempt(String key, int ttl) {
    }

    private final class TransactionalRepository extends IdempotencyRecordRepository {
        private final ThreadLocal<EntityManager> currentEntityManager = new ThreadLocal<>();

        @Override
        public EntityManager getEntityManager() {
            return currentEntityManager.get();
        }

        @Override
        public int insertIfNotExistsOrUpdateIfExpired(String key, String data, int ttl) {
            insertionAttempts.add(new InsertionAttempt(key, ttl));
            return transact(() -> super.insertIfNotExistsOrUpdateIfExpired(key, data, ttl));
        }

        @Override
        public boolean existsByKeyAndNotExpired(String key) {
            return transact(() -> super.existsByKeyAndNotExpired(key));
        }

        @Override
        public int deleteByKeyAndNotExpired(String key) {
            return transact(() -> super.deleteByKeyAndNotExpired(key));
        }

        private <T> T transact(Supplier<T> operation) {
            try (EntityManager entityManager = sessionFactory.createEntityManager()) {
                currentEntityManager.set(entityManager);
                entityManager.getTransaction().begin();
                try {
                    T result = operation.get();
                    entityManager.getTransaction().commit();
                    return result;
                } catch (RuntimeException | Error failure) {
                    if (entityManager.getTransaction().isActive()) {
                        entityManager.getTransaction().rollback();
                    }
                    throw failure;
                }
            } finally {
                currentEntityManager.remove();
            }
        }
    }
}
