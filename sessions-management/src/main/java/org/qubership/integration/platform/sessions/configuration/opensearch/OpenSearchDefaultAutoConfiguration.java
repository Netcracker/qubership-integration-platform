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

package org.qubership.integration.platform.sessions.configuration.opensearch;

import com.netcracker.cloud.dbaas.client.config.MSInfoProvider;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Collections;

import static com.netcracker.cloud.dbaas.client.DbaasConst.LOGICAL_DB_NAME;
import static com.netcracker.cloud.dbaas.client.opensearch.config.DbaasOpensearchConfiguration.TENANT_NATIVE_OPENSEARCH_CLIENT;

@Configuration
@EnableTenantDbaasOpensearch
@ConditionalOnProperty(name = "${qip.datasource.configuration.enabled}", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OpenSearchDefaultAutoConfiguration {

    @Primary
    @Bean(TENANT_NATIVE_OPENSEARCH_CLIENT)
    public DbaasOpensearchClient opensearchClient(DatabasePool dbaasConnectionPool,
                                                  DbaasClassifierFactory classifierFactory,
                                                  OpensearchProperties opensearchProperties) {
        DatabaseSettings dbSettings = getDatabaseSettings();
        DatabaseConfig.Builder databaseConfigBuilder = DatabaseConfig.builder()
                .userRole(opensearchProperties.getRuntimeUserRole())
                .databaseSettings(dbSettings);
        OpensearchConfig opensearchConfig = new OpensearchConfig(opensearchProperties, opensearchProperties.getTenant().getDelimiter());
        log.info("DbaaS opensearch client initialization...");
        return new DbaasOpensearchClientImpl(dbaasConnectionPool,
                classifierFactory.newTenantClassifierBuilder().withCustomKey(LOGICAL_DB_NAME, "sessions"), databaseConfigBuilder, opensearchConfig);
    }

    private DatabaseSettings getDatabaseSettings() {
        OpensearchDatabaseSettings opensearchDatabaseSettings = new OpensearchDatabaseSettings();
        opensearchDatabaseSettings.setResourcePrefix(true);
        opensearchDatabaseSettings.setCreateOnly(Collections.singletonList("user"));
        return opensearchDatabaseSettings;
    }

    /**
     * Warning!
     * This bean is a workaround for setting the same classifier with another microservice.
     * This won't work if this microservice needs to have its own database!
     *
     * @return CustomMSInfoProvider
     */
    @Bean
    @Primary
    public MSInfoProvider fakeMicroserviceMSInfoProvider() {
        return new FakeMicroserviceMSInfoProvider();
    }
}
