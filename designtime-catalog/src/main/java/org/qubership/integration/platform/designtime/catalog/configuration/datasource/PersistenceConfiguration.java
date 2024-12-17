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

package org.qubership.integration.platform.designtime.catalog.configuration.datasource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.SharedCacheMode;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Properties;

@AutoConfiguration
@EnableJpaAuditing
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "org.qubership.integration.platform.catalog.persistence.configs.repository",
        transactionManagerRef = "configsTransactionManager"
)
@EnableConfigurationProperties({JpaProperties.class, HikariConfigProperties.class})
public class PersistenceConfiguration {
    private static final String JPA_ENTITIES_PACKAGE_SCAN =
            "org.qubership.integration.platform.catalog.persistence.configs.entity";

    private final JpaProperties jpaProperties;
    private final HikariConfigProperties hikariProperties;

    @Autowired
    public PersistenceConfiguration(JpaProperties jpaProperties, HikariConfigProperties hikariProperties) {
        this.jpaProperties = jpaProperties;
        this.hikariProperties = hikariProperties;
    }

    @Primary
    @Bean("configsDataSource")
    @ConditionalOnMissingBean(name = "configsDataSource")
    public DataSource configsDataSource() {
        return new HikariDataSource(hikariProperties.getDatasource("datasource"));
    }

    @Bean
    JdbcTemplate jdbcTemplate(@Qualifier("configsDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(@Qualifier("configsDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Primary
    @Bean("entityManagerFactory")
    public LocalContainerEntityManagerFactoryBean configsEntityManagerFactory(
            @Qualifier("configsDataSource") DataSource dataSource
    ) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();

        HibernateJpaVendorAdapter jpaVendorAdapter = new HibernateJpaVendorAdapter();
        jpaVendorAdapter.setDatabase(jpaProperties.getDatabase());
        jpaVendorAdapter.setGenerateDdl(jpaProperties.isGenerateDdl());
        jpaVendorAdapter.setShowSql(jpaProperties.isShowSql());

        em.setDataSource(dataSource);
        em.setJpaVendorAdapter(jpaVendorAdapter);
        em.setPackagesToScan(JPA_ENTITIES_PACKAGE_SCAN);
        em.setPersistenceProvider(new HibernatePersistenceProvider());
        em.setJpaProperties(additionalProperties());
        em.setSharedCacheMode(SharedCacheMode.ENABLE_SELECTIVE);
        return em;
    }

    @Primary
    @Bean("configsTransactionManager")
    public PlatformTransactionManager configsTransactionManager(
            @Qualifier("entityManagerFactory") LocalContainerEntityManagerFactoryBean entityManagerFactory
    ) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
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
