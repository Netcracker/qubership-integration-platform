package org.qubership.integration.platform.engine.configuration.persistence;

import com.netcracker.cloud.dbaas.client.DbaasConst;
import com.netcracker.cloud.dbaas.client.entity.connection.PostgresDBConnection;
import com.netcracker.cloud.dbaas.client.entity.database.AbstractDatabase;
import com.netcracker.cloud.dbaas.client.entity.database.PostgresDatabase;
import com.netcracker.cloud.dbaas.client.management.DatabaseConfig;
import com.netcracker.cloud.dbaas.client.opensearch.entity.OpensearchDBType;
import com.netcracker.cloud.dbaas.client.opensearch.entity.OpensearchIndex;
import com.netcracker.cloud.dbaas.client.opensearch.entity.OpensearchIndexConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.opensearch.OpenSearchProperties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;
import java.util.Optional;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;
import static com.netcracker.cloud.dbaas.client.entity.database.type.PostgresDBType.INSTANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FakeDBaaSClientTest {

    private FakeDBaaSClient client;

    @Mock
    private OpenSearchProperties openSearchProperties;

    @Mock
    private OpenSearchProperties.ClientProperties openSearchClientProperties;

    @Mock
    private PostgresProperties postgresProperties;

    @Mock
    private PostgresProperties.ConnectionProperties schedulerDbConnectionProperties;

    @BeforeEach
    void setUp() {
        client = new FakeDBaaSClient();
        client.appPrefix = "qip";
        client.openSearchProperties = openSearchProperties;
        client.postgresProperties = postgresProperties;
    }

    @Test
    void shouldReturnPostgresDatabaseWhenTypeIsPostgres() {
        stubSchedulerDb(
        );

        PostgresDatabase database = client.getOrCreateDatabase(
                INSTANCE,
                "dev",
                Map.of(LOGICAL_DB_NAME, "configs"),
                DatabaseConfig.builder().build()
        );

        assertEquals("", database.getName());
        assertEquals("dev", database.getNamespace());
        assertEquals("configs", database.getClassifier().get(LOGICAL_DB_NAME));

        PostgresDBConnection connection = database.getConnectionProperties();
        assertEquals("jdbc:postgresql://scheduler-db", connection.getUrl());
        assertEquals("scheduler-user", connection.getUsername());
        assertEquals("scheduler-password", connection.getPassword());
        assertEquals("scheduler-role", connection.getRole());
    }

    @Test
    void shouldUseSchedulerDbWhenLogicalDbNameIsMissingAsCurrentBehavior() {
        stubSchedulerDb(
        );

        PostgresDatabase database = client.getOrCreateDatabase(
                INSTANCE,
                "dev",
                Map.of(),
                DatabaseConfig.builder().build()
        );

        PostgresDBConnection connection = database.getConnectionProperties();
        assertEquals("jdbc:postgresql://scheduler-db", connection.getUrl());
        assertEquals("scheduler-user", connection.getUsername());
        assertEquals("scheduler-password", connection.getPassword());
        assertEquals("scheduler-role", connection.getRole());
    }

    @Test
    void shouldDelegateToOverloadWithDefaultDatabaseConfig() {
        stubSchedulerDb(
        );

        PostgresDatabase database = client.getOrCreateDatabase(
                INSTANCE,
                "dev",
                Map.of(LOGICAL_DB_NAME, "configs")
        );

        PostgresDBConnection connection = database.getConnectionProperties();
        assertEquals("jdbc:postgresql://scheduler-db", connection.getUrl());
        assertEquals("scheduler-user", connection.getUsername());
        assertEquals("scheduler-password", connection.getPassword());
        assertEquals("scheduler-role", connection.getRole());
    }

    @Test
    void shouldReturnDatabaseFromGetDatabase() {
        stubSchedulerDb(
        );

        PostgresDatabase database = client.getDatabase(
                INSTANCE,
                "dev",
                "rw",
                Map.of(LOGICAL_DB_NAME, "configs")
        );

        assert database != null;
        assertEquals("dev", database.getNamespace());
        assertEquals("configs", database.getClassifier().get(LOGICAL_DB_NAME));
    }

    @Test
    void shouldReturnPostgresConnectionFromGetConnection() {
        stubSchedulerDb(
        );

        PostgresDBConnection connection = client.getConnection(
                INSTANCE,
                "dev",
                "rw",
                Map.of(LOGICAL_DB_NAME, "configs")
        );

        assert connection != null;
        assertEquals("jdbc:postgresql://scheduler-db", connection.getUrl());
        assertEquals("scheduler-user", connection.getUsername());
        assertEquals("scheduler-password", connection.getPassword());
        assertEquals("scheduler-role", connection.getRole());
    }

    @Test
    void shouldReturnOpensearchDatabaseWithConfiguredPrefix() {
        stubOpenSearch(
                Optional.of("resource-prefix"),
                Optional.of("user"),
                Optional.of("password")
        );

        OpensearchIndex database = client.getOrCreateDatabase(
                OpensearchDBType.INSTANCE,
                "dev",
                Map.of(DbaasConst.LOGICAL_DB_NAME, "sessions"),
                DatabaseConfig.builder().build()
        );

        assertEquals("", database.getName());
        assertEquals("dev", database.getNamespace());
        assertEquals("sessions", database.getClassifier().get(LOGICAL_DB_NAME));

        OpensearchIndexConnection connection = database.getConnectionProperties();
        assertEquals("http://opensearch:9200", connection.getUrl());
        assertEquals("opensearch", connection.getHost());
        assertEquals(9200, connection.getPort());
        assertEquals("resource-prefix", connection.getResourcePrefix());
        assertEquals("ism", connection.getRole());
        assertEquals("user", connection.getUsername());
        assertEquals("password", connection.getPassword());
    }

    @Test
    void shouldFallbackToAppPrefixWhenOpensearchPrefixIsMissing() {
        stubOpenSearch(
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );

        OpensearchIndex database = client.getOrCreateDatabase(
                OpensearchDBType.INSTANCE,
                "dev",
                Map.of(),
                DatabaseConfig.builder().build()
        );

        OpensearchIndexConnection connection = database.getConnectionProperties();
        assertEquals("qip", connection.getResourcePrefix());
        assertEquals("", connection.getUsername());
        assertEquals("", connection.getPassword());
    }

    @Test
    void shouldReturnNullWhenTypeIsUnsupported() {
        @SuppressWarnings("unchecked")
        com.netcracker.cloud.dbaas.client.entity.database.type.DatabaseType<Object, AbstractDatabase<Object>> type = mock(
                com.netcracker.cloud.dbaas.client.entity.database.type.DatabaseType.class
        );

        AbstractDatabase<Object> database = client.getOrCreateDatabase(
                type,
                "dev",
                Map.of(),
                DatabaseConfig.builder().build()
        );

        assertNull(database);
    }

    @Test
    void shouldReturnNullConnectionWhenTypeIsUnsupported() {
        @SuppressWarnings("unchecked")
        com.netcracker.cloud.dbaas.client.entity.database.type.DatabaseType<Object, AbstractDatabase<Object>> type = mock(
                com.netcracker.cloud.dbaas.client.entity.database.type.DatabaseType.class
        );

        Object connection = client.getConnection(type, "dev", "rw", Map.of());

        assertNull(connection);
    }

    @Test
    void shouldReturnNullPhysicalDatabases() {
        assertNull(client.getPhysicalDatabases("postgres"));
    }

    private void stubSchedulerDb(
            ) {
        when(postgresProperties.schedulerDb()).thenReturn(schedulerDbConnectionProperties);
        when(schedulerDbConnectionProperties.url()).thenReturn("jdbc:postgresql://scheduler-db");
        when(schedulerDbConnectionProperties.username()).thenReturn("scheduler-user");
        when(schedulerDbConnectionProperties.password()).thenReturn("scheduler-password");
        when(schedulerDbConnectionProperties.role()).thenReturn("scheduler-role");
    }

    private void stubOpenSearch(
            Optional<String> prefix,
            Optional<String> username,
            Optional<String> password
    ) {
        when(openSearchProperties.client()).thenReturn(openSearchClientProperties);
        when(openSearchClientProperties.urls()).thenReturn("http://opensearch:9200");
        when(openSearchClientProperties.host()).thenReturn("opensearch");
        when(openSearchClientProperties.port()).thenReturn(9200);
        when(openSearchClientProperties.prefix()).thenReturn(prefix);
        when(openSearchClientProperties.userName()).thenReturn(username);
        when(openSearchClientProperties.password()).thenReturn(password);
    }
}
