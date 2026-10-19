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
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.opensearch.OpenSearchProperties;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;
import java.util.Optional;

import static com.netcracker.cloud.dbaas.client.DbaasConst.*;
import static com.netcracker.cloud.dbaas.client.entity.database.type.PostgresDBType.INSTANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FakeDBaaSClientTest {

    // The classifiers a micro-engine pod requests: the default datasource, and the one DbaasDatasourceProducer names.
    private static final Map<String, Object> DEFAULT_CLASSIFIER = Map.of(
            MICROSERVICE_NAME, "qip-engine-dev",
            NAMESPACE, "dev",
            SCOPE, TENANT,
            TENANT_ID, "default"
    );
    private static final Map<String, Object> CONFIGS_CLASSIFIER = Map.of(
            CUSTOM_KEYS, Map.of(LOGICAL_DB_NAME, "configs"),
            MICROSERVICE_NAME, "qip-engine-dev",
            NAMESPACE, "dev",
            SCOPE, TENANT,
            TENANT_ID, "default"
    );

    private FakeDBaaSClient client;

    @Mock
    private OpenSearchProperties openSearchProperties;

    @Mock
    private OpenSearchProperties.ClientProperties openSearchClientProperties;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private PostgresProperties postgresProperties;

    @BeforeEach
    void setUp() {
        client = new FakeDBaaSClient();
        client.openSearchProperties = openSearchProperties;
        client.postgresProperties = postgresProperties;
    }

    @Test
    void shouldUseServiceDbForDefaultDatasource() {
        stubConnection(postgresProperties.serviceDb(), "service");

        PostgresDatabase database = client.getOrCreateDatabase(
                INSTANCE,
                "dev",
                DEFAULT_CLASSIFIER,
                DatabaseConfig.builder().build()
        );

        assertEquals("", database.getName());
        assertEquals("dev", database.getNamespace());
        assertEquals(DEFAULT_CLASSIFIER, database.getClassifier());
        assertConnection("service", database.getConnectionProperties());
    }

    @Test
    void shouldUseSchedulerDbForConfigsDatasource() {
        stubConnection(postgresProperties.schedulerDb(), "scheduler");

        PostgresDatabase database = client.getOrCreateDatabase(
                INSTANCE,
                "dev",
                CONFIGS_CLASSIFIER,
                DatabaseConfig.builder().build()
        );

        assertEquals(CONFIGS_CLASSIFIER, database.getClassifier());
        assertConnection("scheduler", database.getConnectionProperties());
    }

    @Test
    void shouldDelegateToOverloadWithDefaultDatabaseConfig() {
        stubConnection(postgresProperties.schedulerDb(), "scheduler");

        PostgresDatabase database = client.getOrCreateDatabase(INSTANCE, "dev", CONFIGS_CLASSIFIER);

        assertConnection("scheduler", database.getConnectionProperties());
    }

    @Test
    void shouldReturnDatabaseFromGetDatabase() {
        PostgresDatabase database = client.getDatabase(INSTANCE, "dev", "rw", DEFAULT_CLASSIFIER);

        assert database != null;
        assertEquals("dev", database.getNamespace());
        assertEquals(DEFAULT_CLASSIFIER, database.getClassifier());
    }

    @Test
    void shouldReturnPostgresConnectionFromGetConnection() {
        stubConnection(postgresProperties.schedulerDb(), "scheduler");

        PostgresDBConnection connection = client.getConnection(INSTANCE, "dev", "rw", CONFIGS_CLASSIFIER);

        assert connection != null;
        assertConnection("scheduler", connection);
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
        assertEquals("cip", connection.getResourcePrefix());
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

    private static void stubConnection(PostgresProperties.ConnectionProperties properties, String name) {
        when(properties.url()).thenReturn("jdbc:postgresql://" + name + "-db");
        when(properties.username()).thenReturn(name + "-user");
        when(properties.password()).thenReturn(name + "-password");
        when(properties.role()).thenReturn(name + "-role");
    }

    private static void assertConnection(String name, PostgresDBConnection connection) {
        assertEquals("jdbc:postgresql://" + name + "-db", connection.getUrl());
        assertEquals(name + "-user", connection.getUsername());
        assertEquals(name + "-password", connection.getPassword());
        assertEquals(name + "-role", connection.getRole());
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
