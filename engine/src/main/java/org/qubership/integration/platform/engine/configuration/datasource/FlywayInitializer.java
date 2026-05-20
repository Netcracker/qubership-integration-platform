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

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.qubership.integration.platform.engine.configuration.datasource.properties.FlywayConfigProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnProperty(name = "qip.flyway-initializer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({PersistenceCheckpointAutoConfiguration.class, PersistenceQuartzAutoConfiguration.class})
@EnableConfigurationProperties(FlywayConfigProperties.class)
public class FlywayInitializer {

    private final DataSource checkpointDataSource;
    private final DataSource qrtzDataSource;
    private final FlywayConfigProperties properties;

    public FlywayInitializer(@Qualifier("checkpointDataSource") DataSource checkpointDataSource,
                             @Qualifier("qrtzDataSource") DataSource qrtzDataSource,
                             FlywayConfigProperties properties) {
        this.checkpointDataSource = checkpointDataSource;
        this.qrtzDataSource = qrtzDataSource;
        this.properties = properties;
    }

    @PostConstruct
    public void migrate() {
        ClassicConfiguration ckptConfig = properties.getConfig("checkpoints-datasource");
        ckptConfig.setDataSource(checkpointDataSource);
        Flyway checkpointsFlyway = new Flyway(ckptConfig);
        checkpointsFlyway.migrate();

        ClassicConfiguration qrtzConfig = properties.getConfig("qrtz-datasource");
        qrtzConfig.setDataSource(qrtzDataSource);
        Flyway qrtzFlyway = new Flyway(qrtzConfig);
        qrtzFlyway.migrate();
    }
}
