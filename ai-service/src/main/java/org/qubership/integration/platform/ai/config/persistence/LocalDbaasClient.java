package org.qubership.integration.platform.ai.config.persistence;

import com.netcracker.cloud.dbaas.client.DbaasClient;
import com.netcracker.cloud.dbaas.client.entity.PhysicalDatabases;
import com.netcracker.cloud.dbaas.client.entity.connection.PostgresDBConnection;
import com.netcracker.cloud.dbaas.client.entity.database.AbstractDatabase;
import com.netcracker.cloud.dbaas.client.entity.database.PostgresDatabase;
import com.netcracker.cloud.dbaas.client.entity.database.type.DatabaseType;
import com.netcracker.cloud.dbaas.client.entity.database.type.PostgresDBType;
import com.netcracker.cloud.dbaas.client.exceptions.DbaasException;
import com.netcracker.cloud.dbaas.client.exceptions.DbaasUnavailableException;
import com.netcracker.cloud.dbaas.client.management.DatabaseConfig;
import io.quarkus.arc.properties.UnlessBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Local/dev substitute for dbaas-agent: maps every Postgres logical DB to {@code quarkus.datasource.jdbc.*}.
 */
@ApplicationScoped
@UnlessBuildProperty(name = "qip.ai.dbaas.agent.enabled", stringValue = "true", enableIfMissing = true)
public class LocalDbaasClient implements DbaasClient {

  private static final Logger log = Logger.getLogger(LocalDbaasClient.class);

  @ConfigProperty(name = "quarkus.datasource.jdbc.url")
  String jdbcUrl;

  @ConfigProperty(name = "quarkus.datasource.username")
  String username;

  @ConfigProperty(name = "quarkus.datasource.password")
  String password;

  @Override
  public <T, D extends AbstractDatabase<T>> D getOrCreateDatabase(
      DatabaseType<T, D> type,
      String namespace,
      Map<String, Object> classifier,
      DatabaseConfig databaseConfig)
      throws DbaasException, DbaasUnavailableException {
    log.debugf("Local DBaaS getOrCreateDatabase type=%s classifier=%s", type, classifier);
    if (PostgresDBType.INSTANCE.equals(type)) {
      return type.getDatabaseClass().cast(postgresDatabase(namespace, classifier));
    }
    throw new UnsupportedOperationException("Local DBaaS supports Postgres only, got " + type);
  }

  @Override
  public <T, D extends AbstractDatabase<T>> D getOrCreateDatabase(
      DatabaseType<T, D> type, String namespace, Map<String, Object> classifier)
      throws DbaasException, DbaasUnavailableException {
    return getOrCreateDatabase(type, namespace, classifier, DatabaseConfig.builder().build());
  }

  @Override
  public @Nullable <T, D extends AbstractDatabase<T>> D getDatabase(
      DatabaseType<T, D> type, String namespace, String userRole, Map<String, Object> classifier)
      throws DbaasException, DbaasUnavailableException {
    return getOrCreateDatabase(type, namespace, classifier);
  }

  @Override
  public @Nullable <T, D extends AbstractDatabase<T>> T getConnection(
      DatabaseType<T, D> type, String namespace, String userRole, Map<String, Object> classifier)
      throws DbaasException, DbaasUnavailableException {
    D database = getDatabase(type, namespace, userRole, classifier);
    return database != null ? database.getConnectionProperties() : null;
  }

  @Override
  public PhysicalDatabases getPhysicalDatabases(String type)
      throws DbaasException, DbaasUnavailableException {
    return null;
  }

  private PostgresDatabase postgresDatabase(String namespace, Map<String, Object> classifier) {
    PostgresDatabase database = new PostgresDatabase();
    database.setName("");
    database.setNamespace(namespace);
    database.setClassifier(new TreeMap<>(classifier));
    database.setConnectionProperties(
        new PostgresDBConnection(jdbcUrl, username, password, username));
    return database;
  }
}
