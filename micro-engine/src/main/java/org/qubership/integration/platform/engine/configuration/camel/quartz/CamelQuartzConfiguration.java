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

package org.qubership.integration.platform.engine.configuration.camel.quartz;

import org.qubership.integration.platform.engine.camel.scheduler.StdSchedulerFactoryProxy;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.utils.PoolingConnectionProvider;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import java.util.function.Consumer;

@Slf4j
@Configuration
public class CamelQuartzConfiguration {

    public static final String DATASOURCE_NAME_PREFIX = "camelQuartzDatasource";
    private static final String DEV_DB_SCHEMA = "engine";

    private static DataSource qrtzDataSource;

    private final String qrtzSchemaName;

    private final ServerConfiguration serverConfiguration;

    @Value("${qip.camel.component.quartz.thread-pool-count}")
    private String threadPoolCount;

    @Autowired
    public CamelQuartzConfiguration(ServerConfiguration serverConfiguration,
                                    @Qualifier("qrtzDataSource") DataSource qrtzDataSource,
                                    @Value("${spring.jpa.properties.hibernate.default_schema}") String defaultSchemaName) {
        this.serverConfiguration = serverConfiguration;
        CamelQuartzConfiguration.qrtzDataSource = qrtzDataSource;
        this.qrtzSchemaName = defaultSchemaName;
    }

    @Bean("schedulerFactoryProxy")
    public StdSchedulerFactoryProxy schedulerFactoryProxy(
        @Qualifier("camelQuartzPropertiesCustomizer") Consumer<Properties> propCustomizer
    ) throws SchedulerException {
        log.debug("Create stdSchedulerFactoryProxy");
        return new StdSchedulerFactoryProxy(camelQuartzProperties(propCustomizer));
    }

    @Bean("camelQuartzPropertiesCustomizer")
    @ConditionalOnMissingBean(name = "camelQuartzPropertiesCustomizer")
    Consumer<Properties> camelQuartzPropertiesCustomizer() {
        return this::addDevDataSource;
    }

    public static String getPropDataSourcePrefix() {
        String datasourceName = DATASOURCE_NAME_PREFIX;
        String propDatasourcePrefix =
            StdSchedulerFactory.PROP_DATASOURCE_PREFIX + "." + datasourceName + ".";
        return propDatasourcePrefix;
    }

    public Properties camelQuartzProperties(Consumer<Properties> propCustomizer) {
        Properties properties = new Properties();

        propCustomizer.accept(properties);

        // scheduler
        properties.setProperty(StdSchedulerFactory.PROP_SCHED_INSTANCE_NAME,
            "engine-" + serverConfiguration.getDomain());
        properties.setProperty(StdSchedulerFactory.PROP_SCHED_INSTANCE_ID, "AUTO");
        properties.setProperty(StdSchedulerFactory.PROP_SCHED_JOB_FACTORY_CLASS,
            "org.quartz.simpl.SimpleJobFactory");

        // JobStore
        properties.setProperty(StdSchedulerFactory.PROP_JOB_STORE_CLASS,
            "org.quartz.impl.jdbcjobstore.JobStoreTX");
        properties.setProperty("org.quartz.jobStore.driverDelegateClass",
            "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");

        String datasourceName = DATASOURCE_NAME_PREFIX;
        properties.setProperty("org.quartz.jobStore.dataSource", datasourceName);
        properties.setProperty("org.quartz.jobStore.tablePrefix", qrtzSchemaName + ".QRTZ_");
        properties.setProperty("org.quartz.jobStore.isClustered", "true");
        properties.setProperty("org.quartz.jobStore.misfireThreshold", "15000");

        // thread pool
        properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        properties.setProperty("org.quartz.threadPool.threadCount", threadPoolCount);

        // other
        properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");

        return properties;
    }

    private void addDevDataSource(Properties properties) {
        if (qrtzDataSource instanceof HikariDataSource dataSource) {
            String propDatasourcePrefix = getPropDataSourcePrefix();
            String url = dataSource.getJdbcUrl() + "?currentSchema=" + DEV_DB_SCHEMA;
            String username = dataSource.getUsername();
            String password = dataSource.getPassword();

            properties.setProperty(propDatasourcePrefix + PoolingConnectionProvider.DB_DRIVER,
                "org.postgresql.Driver");
            properties.setProperty(propDatasourcePrefix + PoolingConnectionProvider.DB_URL, url);
            properties.setProperty(propDatasourcePrefix + PoolingConnectionProvider.DB_USER,
                username);
            properties.setProperty(propDatasourcePrefix + PoolingConnectionProvider.DB_PASSWORD,
                password);
            properties.setProperty(
                propDatasourcePrefix + PoolingConnectionProvider.DB_MAX_CONNECTIONS, "12");
        } else {
            log.error("Failed to get database parameters for CamelQuartzConfiguration." +
                " DataSource instance is not HikariDataSource," +
                " camel quartz scheduler may be not work properly!");
            throw new BeanInitializationException("Failed to create CamelQuartzConfiguration bean");
        }
    }

    /**
     * Can be used only for SchedulerDatasourceConnectionProvider
     */
    public static DataSource getDataSource() {
        if (qrtzDataSource != null) {
            return qrtzDataSource;
        } else {
            throw new RuntimeException("DataSource not available now!");
        }
    }
}
