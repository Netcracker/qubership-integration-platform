package org.qubership.integration.platform.engine.configuration.opensearch;

import com.netcracker.cloud.dbaas.client.entity.database.DatabaseSettings;
import com.netcracker.cloud.dbaas.client.management.DatabaseConfig;
import com.netcracker.cloud.dbaas.client.management.DatabasePool;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaasClassifierFactory;
import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClientImpl;
import com.netcracker.cloud.dbaas.client.opensearch.config.EnableTenantDbaasOpensearch;
import com.netcracker.cloud.dbaas.client.opensearch.config.OpensearchConfig;
import com.netcracker.cloud.dbaas.client.opensearch.entity.OpensearchDatabaseSettings;
import com.netcracker.cloud.dbaas.client.opensearch.entity.OpensearchProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.Collections;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;
import static com.netcracker.cloud.dbaas.client.opensearch.config.DbaasOpensearchConfiguration.TENANT_NATIVE_OPENSEARCH_CLIENT;

@AutoConfiguration
@ConditionalOnProperty(name = "qip.standalone", havingValue = "false")
@EnableTenantDbaasOpensearch
public class OpenSearchDbaasAutoConfiguration {

    @Bean(TENANT_NATIVE_OPENSEARCH_CLIENT)
    public DbaasOpensearchClient opensearchClient(
            DatabasePool dbaasConnectionPool,
            DbaasClassifierFactory classifierFactory,
            OpensearchProperties opensearchProperties
    ) {
        DatabaseSettings dbSettings = getDatabaseSettings();
        DatabaseConfig.Builder databaseConfigBuilder = DatabaseConfig.builder()
                .userRole(opensearchProperties.getRuntimeUserRole())
                .databaseSettings(dbSettings);
        OpensearchConfig opensearchConfig =
                new OpensearchConfig(opensearchProperties, opensearchProperties.getTenant().getDelimiter());

        return new DbaasOpensearchClientImpl(
                dbaasConnectionPool,
                classifierFactory.newTenantClassifierBuilder()
                        .withCustomKey(LOGICAL_DB_NAME, "sessions"),
                databaseConfigBuilder,
                opensearchConfig
        );
    }

    private DatabaseSettings getDatabaseSettings() {
        OpensearchDatabaseSettings opensearchDatabaseSettings = new OpensearchDatabaseSettings();
        opensearchDatabaseSettings.setResourcePrefix(true);
        opensearchDatabaseSettings.setCreateOnly(Collections.singletonList("user"));
        return opensearchDatabaseSettings;
    }
}
