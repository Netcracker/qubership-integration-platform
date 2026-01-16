package org.qubership.integration.platform.runtime.catalog.configuration.datasource;

import com.netcracker.cloud.dbaas.client.config.DbaasPostgresDataSourceProperties;
import com.netcracker.cloud.dbaas.client.management.PostgresDatasourceCreator;
import com.netcracker.cloud.dbaas.client.metrics.DbaaSMetricsRegistrar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties({DbaasPostgresDataSourceProperties.class})
@ConditionalOnProperty(value = "qip.standalone", havingValue = "false")
public class PersistenceConfiguration {

    @Bean
    @ConditionalOnProperty(value = "qip.standalone", havingValue = "false")
    public PostgresDatasourceCreator postgresDatasourceCreator(
            DbaasPostgresDataSourceProperties dbaasDsProperties,
            @Autowired(required = false) DbaaSMetricsRegistrar metricsRegistrar
    ) {
        return new PostgresDatasourceCreator(dbaasDsProperties, metricsRegistrar);
    }
}
