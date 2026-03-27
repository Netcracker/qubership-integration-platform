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

package org.qubership.integration.platform.engine.configuration;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@Getter
public class PredeployCheckKafkaConfiguration {

    @Value("${qip.camel.component.kafka.predeploy-check-enabled}")
    private boolean camelKafkaPredeployCheckEnabled;

    @Value("${qip.local-truststore.store.path}")
    private String truststoreLocation;

    @Value("${qip.local-truststore.store.password}")
    private String truststorePassword;

    public Map<String, Object> createValidationKafkaAdminConfig(String brokers,
        String securityProtocol,
        String saslMechanism,
        String saslJaasConfig) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
            StringUtils.isEmpty(securityProtocol) ? "PLAINTEXT" : securityProtocol);
        config.put(SaslConfigs.SASL_MECHANISM,
            StringUtils.isEmpty(saslMechanism) ? "PLAIN" : saslMechanism);
        if (StringUtils.isNotEmpty(saslJaasConfig)) {
            config.put(SaslConfigs.SASL_JAAS_CONFIG, saslJaasConfig);
        }

        config.put(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, 5000L);
        config.put(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG, 5000L);
        config.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(CommonClientConfigs.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);

        config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststoreLocation);
        config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, truststorePassword);
        config.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "JKS");

        return config;
    }
}
