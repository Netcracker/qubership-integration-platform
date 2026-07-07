/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.configuration.datasource;

import com.netcracker.cloud.dbaas.client.entity.DbaasApiProperties;
import com.netcracker.cloud.dbaas.client.entity.settings.PostgresSettings;
import com.netcracker.cloud.dbaas.client.management.DatabaseConfig;
import com.netcracker.cloud.dbaas.client.management.DatabasePool;
import com.netcracker.cloud.dbaas.client.management.DbaasPostgresProxyDataSource;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaasClassifierFactory;
import jakarta.persistence.SharedCacheMode;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Collections;
import java.util.Properties;
import javax.sql.DataSource;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;

@AutoConfiguration
@EnableTransactionManagement
@EnableConfigurationProperties(JpaProperties.class)
@EnableJpaRepositories(
        basePackages = "org.qubership.integration.platform.engine.persistence.configs.repository",
        transactionManagerRef = "schedulerTransactionManager"
)
public class PersistenceQuartzAutoConfiguration {

    private static final String JPA_ENTITIES_PACKAGE_SCAN =
            "org.qubership.integration.platform.engine.persistence.configs.entity";

    private final JpaProperties jpaProperties;

    @Autowired
    public PersistenceQuartzAutoConfiguration(
            JpaProperties jpaProperties
    ) {
        this.jpaProperties = jpaProperties;
    }

    /**
     * Used for chains quartz scheduler
     */
    @Primary
    @Bean("qrtzDataSource")
    @ConditionalOnProperty(value = "qip.standalone", havingValue = "false")
    public DataSource qrtzDataSource(DatabasePool dbaasConnectionPool,
                              DbaasClassifierFactory classifierFactory,
                              @Autowired(required = false) @Qualifier("dbaasApiProperties") DbaasApiProperties dbaasApiProperties) {
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
                classifierFactory
                        .newTenantClassifierBuilder()
                        .withCustomKey(LOGICAL_DB_NAME, "configs"),
                builder.build());
    }

    @Primary
    @Bean("entityManagerFactory")
    public LocalContainerEntityManagerFactoryBean schedulerEntityManagerFactory(DataSource qrtzDataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();

        HibernateJpaVendorAdapter jpaVendorAdapter = new HibernateJpaVendorAdapter();
        jpaVendorAdapter.setDatabase(jpaProperties.getDatabase());
        jpaVendorAdapter.setGenerateDdl(jpaProperties.isGenerateDdl());
        jpaVendorAdapter.setShowSql(jpaProperties.isShowSql());

        em.setDataSource(qrtzDataSource);
        em.setJpaVendorAdapter(jpaVendorAdapter);
        em.setPackagesToScan(JPA_ENTITIES_PACKAGE_SCAN);
        em.setPersistenceProvider(new HibernatePersistenceProvider());
        em.setJpaProperties(additionalProperties());
        em.setSharedCacheMode(SharedCacheMode.ENABLE_SELECTIVE);
        return em;
    }

    @Primary
    @Bean("schedulerTransactionManager")
    public PlatformTransactionManager schedulerTransactionManager(
            @Qualifier("entityManagerFactory") LocalContainerEntityManagerFactoryBean schedulerEntityManagerFactory
    ) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(schedulerEntityManagerFactory.getObject());
        return transactionManager;
    }

    private Properties additionalProperties() {
        Properties properties = new Properties();
        if (jpaProperties != null) {
            properties.putAll(jpaProperties.getProperties());
        }
        return properties;
    }
}
