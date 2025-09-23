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

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
//import org.quartz.utils.PoolingConnectionProvider;
import org.qubership.integration.platform.engine.camel.scheduler.StdSchedulerFactoryProxy;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;

import java.util.Properties;
import java.util.function.Consumer;

@Slf4j
@ApplicationScoped
public class CamelQuartzConfiguration {

    @Inject
    ServerConfiguration serverConfiguration;

    @ConfigProperty(name = "quarkus.datasource.quartz.jdbc.driver")
    String driver;

    @ConfigProperty(name = "quarkus.datasource.quartz.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.quartz.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.quartz.password")
    String password;

    @ConfigProperty(name = "quarkus.hibernate-orm.quartz.database.default-schema")
    String schema;

    @ConfigProperty(name = "qip.camel.component.quartz.thread-pool-count")
    String threadPoolCount;

    // TODO [migration to quarkus]
    public static final String DATASOURCE_NAME = "camelQuartzDatasource";

    @Produces
    @Named("schedulerFactoryProxy")
    public StdSchedulerFactoryProxy schedulerFactoryProxy(
        @Named("camelQuartzPropertiesCustomizer") Consumer<Properties> propCustomizer
    ) throws SchedulerException {
        log.debug("Create stdSchedulerFactoryProxy");
        return new StdSchedulerFactoryProxy(camelQuartzProperties(propCustomizer));
    }

    @Produces
    @Named("camelQuartzPropertiesCustomizer")
    @DefaultBean
    Consumer<Properties> camelQuartzPropertiesCustomizer() {
        return this::addDevDataSource;
    }

    public static String getPropDataSourcePrefix() {
        return StdSchedulerFactory.PROP_DATASOURCE_PREFIX + "." + DATASOURCE_NAME + ".";
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

        properties.setProperty("org.quartz.jobStore.dataSource", DATASOURCE_NAME);
        properties.setProperty("org.quartz.jobStore.tablePrefix", schema + ".QRTZ_");
        properties.setProperty("org.quartz.jobStore.isClustered", "true");
        properties.setProperty("org.quartz.jobStore.misfireThreshold", "15000");

        // thread pool
        properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        properties.setProperty("org.quartz.threadPool.threadCount", threadPoolCount);

        // other
        properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");

        properties.setProperty(StdSchedulerFactory.PROP_CONNECTION_PROVIDER_CLASS, "io.quarkus.quartz.runtime.QuarkusQuartzConnectionPoolProvider");

        return properties;
    }

    private void addDevDataSource(Properties properties) {
        /*
        String propDatasourcePrefix = getPropDataSourcePrefix();
        properties.setProperty(propDatasourcePrefix + PoolingConnectionProvider.DB_DRIVER,
            driver);
        properties.setProperty(propDatasourcePrefix + PoolingConnectionProvider.DB_URL, jdbcUrl);
        properties.setProperty(propDatasourcePrefix + PoolingConnectionProvider.DB_USER,
            username);
        properties.setProperty(propDatasourcePrefix + PoolingConnectionProvider.DB_PASSWORD,
            password);
        properties.setProperty(
            propDatasourcePrefix + PoolingConnectionProvider.DB_MAX_CONNECTIONS, "12");

         */
    }
}
