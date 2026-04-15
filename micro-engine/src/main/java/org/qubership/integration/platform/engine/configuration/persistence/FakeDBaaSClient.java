package org.qubership.integration.platform.engine.configuration.persistence;

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
import com.netcracker.cloud.dbaas.client.opensearch.entity.OpensearchDBType;
import com.netcracker.cloud.dbaas.client.opensearch.entity.OpensearchIndex;
import com.netcracker.cloud.dbaas.client.opensearch.entity.OpensearchIndexConnection;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.qubership.integration.platform.engine.configuration.opensearch.OpenSearchProperties;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;

@Slf4j
@ApplicationScoped
@IfBuildProfile("development")
public class FakeDBaaSClient implements DbaasClient {
    @ConfigProperty(name = "application.prefix")
    String appPrefix;

    @Inject
    OpenSearchProperties openSearchProperties;

    @Inject
    PostgresProperties postgresProperties;

    @Override
    public <T, D extends AbstractDatabase<T>> D getOrCreateDatabase(DatabaseType<T, D> type, String namespace, Map<String, Object> classifier, DatabaseConfig databaseConfig) throws DbaasException, DbaasUnavailableException {
        log.info("Request to get or create database of type {} with classifier {}", type, classifier);
        if (PostgresDBType.INSTANCE.equals(type)) {
            return type.getDatabaseClass().cast(getPostgresDatabase(namespace, classifier));
        } else if (OpensearchDBType.INSTANCE.equals(type)) {
            return type.getDatabaseClass().cast(getOpensearchDatabase(namespace, classifier));
        }
        return null;
    }

    @Override
    public <T, D extends AbstractDatabase<T>> D getOrCreateDatabase(DatabaseType<T, D> type, String namespace, Map<String, Object> classifier) throws DbaasException, DbaasUnavailableException {
        return getOrCreateDatabase(type, namespace, classifier, DatabaseConfig.builder().build());
    }

    @Override
    public @Nullable <T, D extends AbstractDatabase<T>> D getDatabase(DatabaseType<T, D> type, String namespace, String userRole, Map<String, Object> classifier) throws DbaasException, DbaasUnavailableException {
        log.info("Request to get database of type {} with {}", type, classifier);
        return getOrCreateDatabase(type, namespace, classifier);
    }

    @Override
    public @Nullable <T, D extends AbstractDatabase<T>> T getConnection(DatabaseType<T, D> type, String namespace, String userRole, Map<String, Object> classifier) throws DbaasException, DbaasUnavailableException {
        D database = getDatabase(type, namespace, userRole, classifier);
        return database != null ? database.getConnectionProperties() : null;
    }

    @Override
    public PhysicalDatabases getPhysicalDatabases(String type) throws DbaasException, DbaasUnavailableException {
        log.info("Request to get physical databases of type {}", type);
        return null;
    }

    private PostgresDatabase getPostgresDatabase(
            String namespace,
            Map<String, Object> classifier
    ) {
        PostgresDatabase database = new PostgresDatabase();
        database.setName("");
        database.setNamespace(namespace);
        database.setClassifier(new TreeMap<>(classifier));
        String logicalDbName = Optional.ofNullable(classifier.get(LOGICAL_DB_NAME)).toString();
        PostgresProperties.ConnectionProperties connectionProperties =
                StringUtils.isBlank(logicalDbName)
                        ? postgresProperties.serviceDb()
                        : postgresProperties.schedulerDb();;
        database.setConnectionProperties(new PostgresDBConnection(
                connectionProperties.url(),
                connectionProperties.username(),
                connectionProperties.password(),
                connectionProperties.role()
        ));
        return database;
    }

    private OpensearchIndex getOpensearchDatabase(
            String namespace,
            Map<String, Object> classifier
    ) {
        OpensearchIndex database = new OpensearchIndex();
        database.setName("");
        database.setNamespace(namespace);
        database.setClassifier(new TreeMap<>(classifier));

        OpensearchIndexConnection connectionProperties = new OpensearchIndexConnection();
        connectionProperties.setUrl(openSearchProperties.client().urls());
        connectionProperties.setHost(openSearchProperties.client().host());
        connectionProperties.setPort(openSearchProperties.client().port());
        connectionProperties.setResourcePrefix(openSearchProperties.client().prefix().orElse(appPrefix));
        connectionProperties.setRole("ism");
        connectionProperties.setUsername(openSearchProperties.client().userName().orElse(""));
        connectionProperties.setPassword(openSearchProperties.client().password().orElse(""));

        database.setConnectionProperties(connectionProperties);
        return database;
    }
}
