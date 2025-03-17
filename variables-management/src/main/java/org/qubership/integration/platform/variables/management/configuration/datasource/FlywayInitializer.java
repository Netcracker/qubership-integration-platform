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

package org.qubership.integration.platform.variables.management.configuration.datasource;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.qubership.integration.platform.variables.management.configuration.datasource.properties.FlywayConfigProperties;
import org.qubership.integration.platform.variables.management.db.migration.postrgesql.configs.ConfigsJavaMigration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "qip.flyway-initializer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(PersistenceAutoConfiguration.class)
@EnableConfigurationProperties(FlywayConfigProperties.class)
public class FlywayInitializer {
    private final DataSource configsDataSource;
    private final FlywayConfigProperties properties;
    private final List<ConfigsJavaMigration> configsJavaMigrationList;

    public FlywayInitializer(@Qualifier("configsDataSource") DataSource configsDataSource,
                             FlywayConfigProperties properties,
                             List<ConfigsJavaMigration> configsJavaMigrationList) {
        this.configsDataSource = configsDataSource;
        this.properties = properties;
        this.configsJavaMigrationList = configsJavaMigrationList;
    }

    @PostConstruct
    public void migrate() {
        ClassicConfiguration configsConfig = properties.getConfig("configs-datasource");
        configsConfig.setDataSource(configsDataSource);
        configsConfig.setJavaMigrations(configsJavaMigrationList.toArray(new JavaMigration[0]));
        new Flyway(configsConfig).migrate();
    }
}
