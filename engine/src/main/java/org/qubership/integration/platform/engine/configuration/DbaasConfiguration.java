package org.qubership.integration.platform.engine.configuration;

import com.netcracker.cloud.dbaas.client.config.DefaultMSInfoProvider;
import com.netcracker.cloud.dbaas.client.config.EnableDbaasDefault;
import com.netcracker.cloud.dbaas.client.config.MSInfoProvider;
import com.netcracker.cloud.dbaas.client.management.DatabasePool;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaasClassifierFactory;
import org.qubership.integration.platform.engine.configuration.datasource.DbaasClassifierFactoryWithTenant;
import org.qubership.integration.platform.engine.configuration.tenant.TenantConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
@EnableDbaasDefault
@Import(TenantConfiguration.class)
public class DbaasConfiguration {
    private final TenantConfiguration tenantConfiguration;

    @Autowired
    public DbaasConfiguration(TenantConfiguration tenantConfiguration) {
        this.tenantConfiguration = tenantConfiguration;
    }

    @Primary
    @Bean(name = "defaultDatabasePool")
    public DatabasePool defaultDatabasePool(
        @Qualifier("dbaasConnectionPool") DatabasePool databasePool) {
        return databasePool;
    }

    @Bean
    @ConditionalOnMissingBean({MSInfoProvider.class})
    MSInfoProvider defaultMsInfoProvider() {
        return new DefaultMSInfoProvider();
    }

    @Primary
    @Bean
    DbaasClassifierFactory dbaasClassifierFactory(MSInfoProvider msInfoProvider) {
        return new DbaasClassifierFactoryWithTenant(msInfoProvider, tenantConfiguration.getDefaultTenant());
    }
}
