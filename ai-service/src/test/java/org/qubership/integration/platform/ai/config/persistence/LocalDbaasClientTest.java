package org.qubership.integration.platform.ai.config.persistence;

import static com.netcracker.cloud.dbaas.client.DbaasConst.CUSTOM_KEYS;
import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.netcracker.cloud.dbaas.client.entity.database.AbstractDatabase;
import com.netcracker.cloud.dbaas.client.entity.connection.PostgresDBConnection;
import com.netcracker.cloud.dbaas.client.entity.database.PostgresDatabase;
import com.netcracker.cloud.dbaas.client.entity.database.type.DatabaseType;
import com.netcracker.cloud.dbaas.client.entity.database.type.PostgresDBType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalDbaasClientTest {

  @Test
  void getOrCreatePostgresDatabase_usesConfiguredJdbcUrl() throws Exception {
    LocalDbaasClient client = new LocalDbaasClient();
    client.jdbcUrl = "jdbc:postgresql://localhost:5432/ai_a2a";
    client.username = "postgres";
    client.password = "secret";

    PostgresDatabase database =
        client.getOrCreateDatabase(
            PostgresDBType.INSTANCE,
            "local",
            Map.of(CUSTOM_KEYS, Map.of(LOGICAL_DB_NAME, "ai_a2a")));

    PostgresDBConnection connection = database.getConnectionProperties();
    assertEquals("jdbc:postgresql://localhost:5432/ai_a2a", connection.getUrl());
    assertEquals("postgres", connection.getUsername());
    assertEquals("secret", connection.getPassword());
  }

  @Test
  void getOrCreateDatabase_nonPostgres_throws() {
    LocalDbaasClient client = new LocalDbaasClient();
    DatabaseType<Object, AbstractDatabase<Object>> other = mock(DatabaseType.class);

    assertThrows(
        UnsupportedOperationException.class,
        () -> client.getOrCreateDatabase(other, "local", Map.of()));
  }
}
