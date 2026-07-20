package org.qubership.integration.platform.runtime.catalog.configuration.datasource;

import com.netcracker.cloud.dbaas.client.config.EnableDbaasDefault;
import com.netcracker.cloud.dbaas.client.entity.DbaasApiProperties;
import com.netcracker.cloud.dbaas.client.entity.settings.PostgresSettings;
import com.netcracker.cloud.dbaas.client.management.DatabaseConfig;
import com.netcracker.cloud.dbaas.client.management.DatabasePool;
import com.netcracker.cloud.dbaas.client.management.DbaasDbClassifier;
import com.netcracker.cloud.dbaas.client.management.DbaasPostgresProxyDataSource;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaaSChainClassifierBuilder;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaasClassifierFactory;
import com.netcracker.cloud.framework.contexts.tenant.context.TenantContext;
import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.configuration.tenant.TenantConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Collections;
import javax.sql.DataSource;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;

@Getter
@AutoConfiguration
@Order(Ordered.HIGHEST_PRECEDENCE)
@EnableJpaAuditing
@EnableDbaasDefault
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = {
                "org.qubership.integration.platform.runtime.catalog.persistence.configs.repository",
        },
        transactionManagerRef = "configsTransactionManager"
)
public class PersistenceConfigsAutoConfiguration {

    private final TenantConfiguration tenantConfiguration;

    @Autowired
    public PersistenceConfigsAutoConfiguration(TenantConfiguration tenantConfiguration) {
        this.tenantConfiguration = tenantConfiguration;
    }

    @Primary
    @Bean("configsDataSource")
    @ConditionalOnProperty(value = "qip.datasource.configuration.enabled", havingValue = "true", matchIfMissing = true)
    DataSource dataSource(DatabasePool dbaasConnectionPool,
                          DbaasClassifierFactory classifierFactory,
                          @Autowired(required = false) @Qualifier("dbaasApiProperties") DbaasApiProperties dbaasApiProperties
    ) {
        PostgresSettings databaseSettings =
                new PostgresSettings(dbaasApiProperties == null
                        ? Collections.emptyMap()
                        : dbaasApiProperties.getDatabaseSettings(DbaasApiProperties.DbScope.TENANT));

        DatabaseConfig.Builder builder = DatabaseConfig.builder()
                .databaseSettings(databaseSettings);

        if (dbaasApiProperties != null) {
            builder
                    .userRole(dbaasApiProperties.getRuntimeUserRole())
                    .dbNamePrefix(dbaasApiProperties.getDbPrefix());
        }

        return new DbaasPostgresProxyDataSource(
                dbaasConnectionPool,
                new DefaultTenantDbaaSClassifierBuilder(
                        tenantConfiguration.getDefaultTenant(),
                        classifierFactory
                                .newTenantClassifierBuilder()
                                .withCustomKey(LOGICAL_DB_NAME, "configs")
                ),
                builder.build());
    }

    static class DefaultTenantDbaaSClassifierBuilder extends DbaaSChainClassifierBuilder {

        private final String tenant;
        private final DbaaSChainClassifierBuilder dbaaSClassifierBuilder;

        public DefaultTenantDbaaSClassifierBuilder(String tenant, DbaaSChainClassifierBuilder dbaaSClassifierBuilder) {
            super(null);
            this.tenant = tenant;
            this.dbaaSClassifierBuilder = dbaaSClassifierBuilder;
        }

        @Override
        public DbaasDbClassifier build() {
            TenantContext.set(tenant);
            return dbaaSClassifierBuilder.build();
        }
    }
}
